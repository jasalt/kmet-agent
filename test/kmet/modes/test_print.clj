(ns kmet.modes.test-print
  "Print-mode tests (pi: print-mode.ts) — run() sends the message through
   the agent loop and returns the final text via :on-done (nil on error)."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.modes.print :as print-mode]
            [kmet.ai.models :as m]
            [kmet.app.loop :as agent]
            [kmet.app.extensions :as extensions]))

(defn- opts
  "Minimal run opts — :model/:provider bypass catalog resolution."
  []
  {:model "deepseek-v4-flash"
   :provider :opencode-go
   :messages ["hi"]
   :config {:provider :opencode-go :model "deepseek-v4-flash"}})

(deftest test-run-delivers-final-text
  (testing "run returns the agent's final text delivered via :on-done"
    (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                         ((:on-done ag-opts) "hello world"))]
      (is (= "hello world" (print-mode/run (opts)))))))

(deftest test-run-error-returns-nil
  (testing "run returns nil when the agent reports an error"
    ;; print-mode surfaces the error to stderr — capture it in the test.
    (binding [*err* (java.io.StringWriter.)]
      (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                           ((:on-error ag-opts) (ex-info "boom" {})))]
        (is (nil? (print-mode/run (opts))))))))

(deftest test-run-passes-message
  (testing "the joined user message reaches the agent"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                           (reset! seen (:message ag-opts))
                                           ((:on-done ag-opts) "ok"))]
        (print-mode/run (assoc (opts) :messages ["hello" "world"]))
        (is (= "hello world" @seen))))))

(deftest test-run-seeds-block-images
  (testing "images.blockImages reaches the print-mode agent (pi: SDK-level)"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                           (reset! seen (:block-images @(:cfg ag)))
                                           ((:on-done ag-opts) "ok"))]
        (print-mode/run (assoc (opts) :config {:provider :opencode-go
                                               :model "deepseek-v4-flash"
                                               :images {:block-images true}}))
        (is (true? @seen)))
      (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                           (reset! seen (:block-images @(:cfg ag)))
                                           ((:on-done ag-opts) "ok"))]
        (print-mode/run (opts))
        (is (false? @seen) "default off")))))

(deftest test-run-wires-the-extension-context
  (testing "print-mode handlers see the running agent (pi: runner-bound ctx)"
    (let [model (m/map->Model {:provider :opencode-go :id "deepseek-v4-flash"})
          seen (atom nil)]
      (with-redefs [m/providers-atom (atom {:opencode-go {:models [model]}})
                    agent/run-agent-turn (fn [_ag ag-opts]
                                           (reset! seen
                                                   {:ctx (extensions/build-extension-context)
                                                    :session (extensions/get-session)})
                                           ((:on-done ag-opts) "ok"))]
        (is (= "ok" (print-mode/run
                     (assoc (opts) :config {:provider :opencode-go
                                            :model "deepseek-v4-flash"
                                            :models ["opencode-go/deepseek-v4-flash"]}))))
        (let [{:keys [ctx session]} @seen]
          (is (= :print (:mode ctx)))
          (is (identical? model (:model ctx)))
          (is (= [{:model model}] (:scoped-models ctx)))
          (is (= :off (:thinking-level ctx)))
          (is (some? session) "the run's session is bound for extensions")
          (is (string? (:id session)))))
      (is (nil? (extensions/get-session))
          "the print run tears its extension wiring down"))))

