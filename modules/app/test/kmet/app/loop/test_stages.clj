(ns kmet.app.loop.test-stages
  "The loop's stage map: what a ctx carries, what a run reads, and what a
   reload re-seats."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.loop.stages :as stages]
            [kmet.srce.core :as srce]))

(deftest the-defaults-are-the-two-stages-the-loop-reads
  (is (= #{:loop-guard :retry-policy} (set (keys stages/defaults))))
  (is (every? (comp fn? :call val) stages/defaults))
  (is (every? (comp nil? :config val) stages/defaults)
      "the builtin stages carry no config: a run adds its own"))

(deftest a-ctxs-stage-map-rides-the-ctx-chain
  (testing "the nearest atom wins, and it is read whole — no per-stage merge"
    (let [parent (srce/root {:loop-stages (atom {:loop-guard {:call :parent}})})
          child (srce/extend parent {:loop-stages (atom {:retry-policy {:call :child}})})
          sibling (srce/extend parent {})]
      (is (= {:retry-policy {:call :child}} (stages/for-ctx child))
          "a ctx that carries a map with its own is the map; merging over the
           defaults is the builder's job (app-runtime/child-ctx)")
      (is (= {:loop-guard {:call :parent}} (stages/for-ctx sibling))
          "a child with no atom of its own reads the parent's")))
  (testing "a ctx with no stage atom anywhere, or none at all, reads the defaults"
    (is (= stages/defaults (stages/for-ctx (srce/root))))
    (is (= stages/defaults (stages/for-ctx nil))
        "a hand-built state reads the builtin stages")))

(deftest a-refresh-re-seats-only-the-ctx-it-names
  (let [changed (fn [& _] :changed)
        parent (srce/root {:loop-stages (atom {:loop-guard {:call :parent}})})
        child (srce/extend parent {:loop-stages (atom {:loop-guard {:call :child}})})]
    (try
      (with-redefs [stages/defaults (assoc stages/defaults :loop-guard {:call changed})]
        (stages/refresh! child)
        (is (identical? changed (get-in (stages/for-ctx child) [:loop-guard :call]))
            "the target's own atom is re-seated")
        (is (= :parent (get-in (stages/for-ctx parent) [:loop-guard :call]))
            "and nothing up the chain is reached — for-ctx inherits, a refresh names its target")
        (testing "a ctx that carries no stage atom is left alone"
          (stages/refresh! (srce/extend parent {}))
          (stages/refresh! nil)
          (is (= :parent (get-in (stages/for-ctx parent) [:loop-guard :call])))))
      (finally (stages/refresh! child)))
    (is (not (identical? changed (get-in (stages/for-ctx child) [:loop-guard :call])))
        "the real stage is back")))
