(ns kmet.agent.test-runtime
  "The agent runtime: the app root ctx, its loop stages, and the flat /units
   render."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kmet.agent.app-runtime :as runtime]
            [kmet.libs.scope :as scope]
            [kmet.srce.core :as srce]))

(deftest the-app-root-ctx-is-one-ctx-carrying-the-loop-stages
  (let [ctx (runtime/ctx)
        stages (runtime/loop-stages ctx)]
    (is (identical? ctx (runtime/ctx)) "one app root per process")
    (is (srce/ctx? ctx) "the app root is a ctx, not a store node")
    (is (= #{:loop-guard :retry-policy} (set (keys stages))))
    (is (fn? (get-in stages [:loop-guard :call])))
    (is (fn? (get-in stages [:retry-policy :call])))
    (is (nil? (get-in stages [:loop-guard :config])))
    (is (nil? (get-in stages [:retry-policy :config]))
        "the builtin stages carry no config")))

(deftest a-child-ctx-carries-its-own-stages
  (let [child (runtime/child-ctx (runtime/ctx)
                                 {:retry-policy {:config {:max-retries 1}}})
        stages (runtime/loop-stages child)]
    (is (srce/inside? child (runtime/ctx)))
    (is (= {:max-retries 1} (get-in stages [:retry-policy :config])))
    (is (fn? (get-in stages [:retry-policy :call]))
        "the default handle survives the merge")
    (is (nil? (get-in stages [:loop-guard :config])))
    (is (= runtime/default-loop-stages (runtime/loop-stages (runtime/ctx)))
        "a child ctx's override does not leak into the root")))

(deftest a-ctx-without-stages-reads-the-defaults
  (is (= runtime/default-loop-stages (runtime/loop-stages (srce/root))))
  (is (= runtime/default-loop-stages (runtime/loop-stages nil))
      "a node-less state reads the builtin stages"))

(deftest a-child-ctx-joins-its-parents-children
  ;; The parent link is half the nesting contract: a child the parent's list
  ;; does not hold would not be in its subtree (srce/inside?).
  (let [parent (srce/root)
        child (runtime/child-ctx parent)]
    (is (some #(identical? % child) (srce/children parent)))
    (is (srce/inside? child parent))
    (is (not (srce/inside? parent child)))))

(deftest refresh-stages-reseats-a-ctx
  ;; A reloaded unit needs the stages re-seated: the ctx holds the map it was
  ;; created with.
  (let [changed (fn [& _] :changed)
        ctx (runtime/child-ctx)
        guard #(get-in (runtime/loop-stages ctx) [:loop-guard :call])]
    (try
      (with-redefs [runtime/default-loop-stages
                    (assoc runtime/default-loop-stages
                           :loop-guard {:call changed})]
        (runtime/refresh-stages! ctx)
        (is (identical? changed (guard))))
      (finally (runtime/refresh-stages! ctx)))
    (is (not (identical? changed (guard))) "the real unit is back")))

(deftest refresh-stages-defaults-to-the-app-root
  (let [changed (fn [& _] :changed)]
    (try
      (with-redefs [runtime/default-loop-stages
                    (assoc runtime/default-loop-stages
                           :loop-guard {:call changed})]
        (runtime/refresh-stages!)
        (is (identical? changed
                        (get-in (runtime/loop-stages (runtime/ctx))
                                [:loop-guard :call]))))
      (finally (runtime/refresh-stages!)))))

(deftest owner-listing-is-flat-and-in-registration-order
  (let [node (scope/node)]
    (scope/register! node [:app] :k/root "r")
    (scope/register! node [:app :extensions "probe-ext"]
                     [:commands :probe-cmd] {:name "probe-cmd"})
    (scope/register! node [:app :extensions "probe-ext" :deep] :k/deep "d")
    (scope/register! node [:app :commands]
                     [:kmet/command "probe"] {:name "probe"})
    (is (= ["[:app] keys=[:k/root]"
            "[:app :extensions \"probe-ext\"] keys=[[:commands :probe-cmd]]"
            "[:app :extensions \"probe-ext\" :deep] keys=[:k/deep]"
            "[:app :commands] keys=[[:kmet/command \"probe\"]]"]
           (str/split-lines (runtime/owner-listing node)))
        "one line per owner, first-registration order, no indentation")))

(deftest owner-listing-of-an-empty-node-is-empty
  (is (= "" (runtime/owner-listing (scope/node)))))

(deftest owner-listing-skips-teardown-only-owners
  ;; An owner with teardowns but no registrations has nothing to show; the
  ;; listing renders what a registration made, not the owner's existence.
  (let [node (scope/node)]
    (scope/add-teardown! node [:app :empty] (fn [] nil))
    (is (= "" (runtime/owner-listing node)))))
