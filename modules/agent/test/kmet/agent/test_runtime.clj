(ns kmet.agent.test-runtime
  "The agent runtime: the app node, its loop stages, and the flat /units
   render."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kmet.agent.runtime :as runtime]
            [kmet.libs.scope :as scope]))

(deftest the-app-node-is-one-node-carrying-the-loop-stages
  (let [node (runtime/node)
        stages (runtime/loop-stages node)]
    (is (identical? node (runtime/node)) "one app tree per process")
    (is (= #{:loop-guard :retry-policy} (set (keys stages))))
    (is (fn? (get-in stages [:loop-guard :call])))
    (is (fn? (get-in stages [:retry-policy :call])))
    (is (nil? (get-in stages [:loop-guard :config])))
    (is (nil? (get-in stages [:retry-policy :config]))
        "the builtin stages carry no config")))

(deftest a-child-node-carries-its-own-stages
  (let [parent (scope/node)
        child (runtime/child-node parent {:retry-policy {:config {:max-retries 1}}})
        stages (runtime/loop-stages child)]
    (is (scope/in-subtree? child parent))
    (is (= {:max-retries 1} (get-in stages [:retry-policy :config])))
    (is (fn? (get-in stages [:retry-policy :call]))
        "the default handle survives the merge")
    (is (nil? (get-in stages [:loop-guard :config])))))

(deftest a-bare-store-node-reads-the-defaults
  (is (= runtime/default-loop-stages (runtime/loop-stages (scope/node)))))

(deftest a-child-node-joins-its-parents-children
  ;; The parent link is half the nesting contract: a child the parent's list
  ;; does not hold would survive its parent's disposal (scope.md §5).
  (let [parent (runtime/child-node)
        child (runtime/child-node parent)]
    (is (some #(identical? % child) @(:children parent)))))

(deftest refresh-stages-reseats-a-node
  ;; A reloaded unit needs the stages re-seated: the node holds the map it was
  ;; created with.
  (let [changed (fn [& _] :changed)
        node (runtime/child-node)
        guard #(get-in (runtime/loop-stages node) [:loop-guard :call])]
    (try
      (with-redefs [runtime/default-loop-stages
                    (assoc runtime/default-loop-stages
                           :loop-guard {:call changed})]
        (runtime/refresh-stages! node)
        (is (identical? changed (guard))))
      (finally (runtime/refresh-stages! node)))
    (is (not (identical? changed (guard))) "the real unit is back")))

(deftest refresh-stages-defaults-to-the-app-root
  (let [changed (fn [& _] :changed)]
    (try
      (with-redefs [runtime/default-loop-stages
                    (assoc runtime/default-loop-stages
                           :loop-guard {:call changed})]
        (runtime/refresh-stages!)
        (is (identical? changed
                        (get-in (runtime/loop-stages (runtime/node))
                                [:loop-guard :call]))))
      (finally (runtime/refresh-stages!)))))

(deftest owner-listing-is-flat-and-in-registration-order
  (let [node (runtime/child-node)]
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
  (is (= "" (runtime/owner-listing (runtime/child-node)))))

(deftest owner-listing-skips-teardown-only-owners
  ;; An owner with teardowns but no registrations has nothing to show; the
  ;; listing renders what a registration made, not the owner's existence.
  (let [node (runtime/child-node)]
    (scope/add-teardown! node [:app :empty] (fn [] nil))
    (is (= "" (runtime/owner-listing node)))))
