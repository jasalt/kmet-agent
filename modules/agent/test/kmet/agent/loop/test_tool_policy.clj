(ns kmet.agent.loop.test-tool-policy
  "The permission gate in the loop: a deny settles the call without executing,
  an `:ask` goes to the approvals seam, a scripted inner call is gated too, and
  two agents in one process decide independently because the policy is read
  from each agent's own ctx."
  (:require [kmet.agent.loop.state :as state]
            [clojure.test :refer [deftest is use-fixtures]]
            [kmet.ai.llm :as llm]
            [kmet.app.permissions :as permissions]
            [kmet.agent.app-runtime :as app-runtime]
            [kmet.agent.config :as cfg]
            [kmet.agent.loop :as loop]
            [kmet.agent.loop.test-support :as support]
            [kmet.libs.json :as json]
            [kmet.srce.core :as srce]))

(def ^:private agent-ctxs
  "Every agent ctx this namespace built, so the fixture can detach them."
  (atom []))

(defn- agent-ctx
  "A child of the app root — so the loop's own services resolve — named NAME and
   carrying POLICY as this agent's `:permissions`, if given. The app root mounts
   no policy, so a child may provide one (one provider per name per chain).

   The app root is process-wide, so a ctx left attached outlives the test:
   `detach-agent-ctxs!` releases them, or `/units` would list a line per test."
  ([name]
   (let [ctx (srce/extend (app-runtime/ctx) {:name name})]
     (swap! agent-ctxs conj ctx)
     ctx))
  ([name policy]
   (let [ctx (agent-ctx name)]
     (srce/provide! ctx :permissions (permissions/policy policy))
     ctx)))

(defn- detach-agent-ctxs!
  "Release the agent ctxs a test attached to the process-wide app root. A mount
   left under one makes the detach throw `:srce/ctx-busy` — the refusal is the
   point, so the test fails rather than leaking."
  [f]
  (reset! agent-ctxs [])
  (try
    (f)
    (finally
      (doseq [ctx @agent-ctxs] (srce/detach! ctx))
      (reset! agent-ctxs []))))

(use-fixtures :each detach-agent-ctxs!)

(defn- run-bash-turn
  "Run one turn on CTX whose model asks for a `bash` tool call, then text.
   Returns the run's `:tool-execution-end` event."
  [ctx]
  (let [events (atom [])
        agent (support/make-agent-state :ctx ctx
                                        :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (first (filter #(= :tool-execution-end (:type %)) @events))))

(deftest a-denied-call-is-settled-without-executing
  (let [executed (atom false)
        events (atom [])
        agent (support/make-agent-state
               :ctx (agent-ctx :agent/deny (fn [_ _] {:decision :deny
                                                      :reason "read-only workspace"}))
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _]
                                           (reset! executed true)
                                           {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (is (false? @executed) "a denied tool call must not reach the executor")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (is (= "bash" (:tool-name end)))
      (is (true? (:is-error end)) "the result is an error the model can read")
      (is (= "read-only workspace" (get-in end [:result :content]))))))

(deftest an-allowed-call-runs
  (let [end (run-bash-turn (agent-ctx :agent/allow))]
    (is (= "ran" (get-in end [:result :content])))
    (is (false? (:is-error end)))))

(deftest an-unanswerable-ask-is-a-deny
  ;; the real composition mounts the terminal approver (kmet.ui.tui), and a unit
  ;; test runs no terminal: an agent policy that says :ask still denies, because
  ;; an approval nobody can answer is not a yes.
  (let [end (run-bash-turn (agent-ctx :agent/ask (fn [_ _] :ask)))]
    (is (true? (:is-error end)))
    (is (= "no terminal is running — nobody can ask"
           (get-in end [:result :content])))))

(deftest two-agents-decide-independently
  (let [denied (run-bash-turn (agent-ctx :agent/deny (fn [_ _] :deny)))
        allowed (run-bash-turn (agent-ctx :agent/allow))]
    (is (true? (:is-error denied)) "the denied agent's call is blocked")
    (is (false? (:is-error allowed)) "the other agent's same call runs")
    (is (not= (get-in denied [:result :content]) (get-in allowed [:result :content]))
        "one process, one policy service, two verdicts")))

(deftest a-scripted-inner-call-goes-through-the-gate
  ;; the policy reaches calls a run_code script makes: the loop composes the
  ;; gate into run-code's tool hooks, which the bridge hands to its workers
  (let [seen (atom [])
        calls (atom 0)
        agent (support/make-agent-state
               :ctx (agent-ctx :agent/script (fn [_ request]
                                               (swap! seen conj (:tool-name request))
                                               :allow)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "run_code"
                                      :arguments (json/generate-string
                                                  {:code "(deref (tools/call \"read\" {:path \"deps.edn\"}))"})
                                      :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "ok"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (is (some #{"run_code"} @seen) "the outer call was judged")
    (is (some #{"read"} @seen) "and so was the call the script made")))
