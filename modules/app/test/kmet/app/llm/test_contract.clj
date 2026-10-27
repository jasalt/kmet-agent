(ns kmet.app.llm.test-contract
  "The `:llm` contract (kmet.app.llm): one request operation, resolved through
   the agent's composition with the library as the fallback, and the row that
   provides it."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.ai.llm :as ai]
            [kmet.app.llm :as llm]
            [kmet.srce.core :as srce]))

(deftest a-state-outside-a-composition-reaches-the-library
  ;; no ctx, no service: the contract calls kmet.ai.llm — late-bound, so a
  ;; reloaded library (or a stub here) is what runs, and nothing reaches the net
  (let [calls (atom [])]
    (with-redefs [ai/send-message (fn [opts] (swap! calls conj opts) :sent)]
      (is (= :sent (llm/send-message {:ctx nil} {:model "m" :messages []}))
          "the builtin answers")
      (is (= [{:model "m" :messages []}] @calls)
          "and it is the library var, not a capture"))))

(deftest a-mounted-implementation-is-what-a-run-reaches
  (let [ctx (srce/root)
        seen (atom [])
        agent {:ctx ctx}
        mounted {:send-message (fn [opts] (swap! seen conj opts) :replaced)}]
    (srce/provide! ctx :llm mounted)
    (is (= :replaced (llm/send-message agent {:model "m" :messages []}))
        "the caller reaches the mounted request fn, not the library")
    (is (= [{:model "m" :messages []}] @seen) "with the opts it was given")))

(deftest the-row-provides-the-builtin-and-takes-no-config
  (let [world (srce/root)]
    (srce/plugin! world #'llm/plugin nil)
    (is (= llm/builtin (srce/get world :llm))
        "the row's value is the builtin; a profile replaces it by id")
    (testing "a :config the row would ignore is refused, not silently dropped"
      (is (thrown-with-msg? Exception #"takes no :config"
                            ((:apply llm/plugin)
                             (srce/extend world {}) {:model "m"}))))))
