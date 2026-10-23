(ns kmet.agent.test-runtime
  "The agent runtime: the app root ctx and the process's stage entry points."
  (:require [clojure.test :as t :refer [deftest is]]
            [kmet.agent.app-runtime :as runtime]
            [kmet.app.loop.stages :as stages]
            [kmet.srce.core :as srce]))

(deftest the-app-root-ctx-is-one-ctx-carrying-the-loop-stages
  ;; The seam this file owns: kmet1's process root boots carrying the stage
  ;; map (the map's own shape is kmet.app.loop.test-stages' subject).
  (let [ctx (runtime/ctx)]
    (is (identical? ctx (runtime/ctx)) "one app root per process")
    (is (srce/ctx? ctx) "the app root is a ctx, not a store node")
    (is (= stages/defaults (stages/for-ctx ctx))
        "and it carries the builtin map, so a run with no override reads one")))

(deftest a-child-ctx-carries-its-own-stages
  (let [child (runtime/child-ctx (runtime/ctx)
                                 {:retry-policy {:config {:max-retries 1}}})
        stage-map (stages/for-ctx child)]
    (is (srce/inside? child (runtime/ctx)))
    (is (= {:max-retries 1} (get-in stage-map [:retry-policy :config]))
        "the override rides the child's ctx")
    (is (fn? (get-in stage-map [:retry-policy :call]))
        "over the defaults, not instead of them")
    (is (= stages/defaults (stages/for-ctx (runtime/ctx)))
        "and does not leak into the root")))

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
  ;; because a ctx cannot be found from inside a function that has none. The
  ;; root has to *seat* its own atom for that to mean anything — a ctx that
  ;; carries none reads the builtin map by inheritance and a refresh of it is a
  ;; no-op, which is why the test poisons the atom instead of the defaults var.
  (let [stage-atom (:loop-stages (runtime/ctx))
        poisoned {:loop-guard {:call (fn [& _] :poisoned)}}]
    (is (some? stage-atom) "the root seats the atom a refresh re-seats")
    (try
      (reset! stage-atom poisoned)
      (is (= poisoned (stages/for-ctx (runtime/ctx))))
      (runtime/refresh-stages!)
      (is (= stages/defaults (stages/for-ctx (runtime/ctx)))
          "the no-arg form re-seated the root's own atom")
      (finally (runtime/refresh-stages!)))))
