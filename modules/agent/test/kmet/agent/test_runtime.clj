(ns kmet.agent.test-runtime
  "The agent runtime: the app root ctx and the process's stage entry points."
  (:require [clojure.test :as t :refer [deftest is]]
            [kmet.agent.app-runtime :as runtime]
            [kmet.app.loop.stages :as stages]
            [kmet.srce.core :as srce]))

(deftest the-app-root-ctx-is-one-ctx-carrying-the-loop-stages
  (let [ctx (runtime/ctx)
        stage-map (stages/for-ctx ctx)]
    (is (identical? ctx (runtime/ctx)) "one app root per process")
    (is (srce/ctx? ctx) "the app root is a ctx, not a store node")
    (is (= #{:loop-guard :retry-policy} (set (keys stage-map))))
    (is (fn? (get-in stage-map [:loop-guard :call])))
    (is (fn? (get-in stage-map [:retry-policy :call])))
    (is (nil? (get-in stage-map [:loop-guard :config])))
    (is (nil? (get-in stage-map [:retry-policy :config]))
        "the builtin stages carry no config")))

(deftest a-child-ctx-carries-its-own-stages
  (let [child (runtime/child-ctx (runtime/ctx)
                                 {:retry-policy {:config {:max-retries 1}}})
        stage-map (stages/for-ctx child)]
    (is (srce/inside? child (runtime/ctx)))
    (is (= {:max-retries 1} (get-in stage-map [:retry-policy :config])))
    (is (fn? (get-in stage-map [:retry-policy :call]))
        "the default handle survives the merge")
    (is (nil? (get-in stage-map [:loop-guard :config])))
    (is (= stages/defaults (stages/for-ctx (runtime/ctx)))
        "a child ctx's override does not leak into the root")))

(deftest a-child-ctx-joins-its-parents-children
  ;; The parent link is half the nesting contract: a child the parent's list
  ;; does not hold would not be in its subtree (srce/inside?).
  (let [parent (srce/root)
        child (runtime/child-ctx parent)]
    (is (some #(identical? % child) (srce/children parent)))
    (is (srce/inside? child parent))
    (is (not (srce/inside? parent child)))))

(deftest refresh-stages-defaults-to-the-app-root
  ;; `/reload` calls the no-arg form: the process shim names the app root,
  ;; because a ctx cannot be found from inside a function that has none.
  (let [changed (fn [& _] :changed)]
    (try
      (with-redefs [stages/defaults
                    (assoc stages/defaults :loop-guard {:call changed})]
        (runtime/refresh-stages!)
        (is (identical? changed
                        (get-in (stages/for-ctx (runtime/ctx))
                                [:loop-guard :call]))))
      (finally (runtime/refresh-stages!)))))
