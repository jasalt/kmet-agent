(ns kmet.agent.loop.test-queues
  "Message queues: steering and follow-up delivery, per-queue modes, and how they
   interact with a running turn."
  (:require [clojure.test :as t]
            [kmet.ai.llm :as llm]
            [kmet.agent.tools.core :as tools]
            [kmet.agent.event-bus :as event-bus]
            [kmet.agent.compaction.stage :as compact]
            [kmet.agent.loop.state :as state]
            [kmet.agent.loop :as loop]
            [kmet.agent.config :as cfg]))

;; ─── Queues (steering / follow-up) ────────────────────────────────────────

(t/deftest test-loop-steer-queues-message
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (state/steer! agent "first")
    (state/steer! agent "second")
    (t/is (= ["first" "second"] @(:steering agent)))
    (t/is (state/has-queued-messages? agent))
    (t/is (= {:steering ["first" "second"] :follow-up []}
             (state/queued-messages agent)))
    (t/is (= :queue-update (:type (last @events)))
          "steer! emits :queue-update")
    (state/clear-queues! agent)
    (t/is (empty? @(:steering agent)))
    (t/is (empty? @(:follow-up agent)))
    (t/is (not (state/has-queued-messages? agent)))
    (t/is (= :queue-update (:type (last @events)))
          "clear-queues! emits :queue-update")
    (t/is (not (contains? (last @events) :reason))
          "a plain clear carries no drop reason")))

(t/deftest test-loop-followup-queues-message
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (state/follow-up! agent "later")
    (t/is (= ["later"] @(:follow-up agent)))
    (t/is (state/has-queued-messages? agent))
    (t/is (= :queue-update (:type (last @events)))
          "follow-up! emits :queue-update")))

(t/deftest test-loop-peek-queued-messages
  ;; pi: Agent.peekQueuedMessages — a non-destructive preview of the batch
  ;; the loop consumes next: steering while it has messages (the inner loop
  ;; polls it first), otherwise the follow-up queue; each per its queue mode.
  (let [agent (loop/make-agent-state)
        next-drain (fn []
                     (if (seq @(:steering agent))
                       (state/drain-queue! (:steering agent)
                                           (:steering-mode @(:cfg agent)))
                       (state/drain-queue! (:follow-up agent)
                                           (:follow-up-mode @(:cfg agent)))))]
    (t/is (= [] (state/peek-queued-messages agent))
          "empty queues preview nothing")
    (state/steer! agent "s1")
    (state/steer! agent "s2")
    (t/is (= ["s1" "s2"] (state/peek-queued-messages agent)))
    (state/follow-up! agent "f1")
    (t/is (= ["s1" "s2"] (state/peek-queued-messages agent))
          "steering wins while it has messages")
    (t/is (= ["s1" "s2"] @(:steering agent)) "peeking drains nothing")
    (t/is (= ["f1"] @(:follow-up agent)) "peeking drains nothing")
    (swap! (:cfg agent) assoc :steering-mode :one-at-a-time)
    (t/is (= ["s1"] (state/peek-queued-messages agent))
          ":one-at-a-time previews the single message the drain takes")
    (t/is (= ["s1"] (vec (next-drain)))
          "the preview is exactly what the next drain consumes")
    (t/is (= ["s2"] (state/peek-queued-messages agent))
          "the unconsumed message is still previewed")
    (swap! (:cfg agent) assoc :steering-mode :all)
    (state/clear-queues! agent)
    (state/follow-up! agent "f1")
    (state/follow-up! agent "f2")
    (swap! (:cfg agent) assoc :follow-up-mode :all)
    (t/is (= ["f1" "f2"] (state/peek-queued-messages agent))
          "follow-up previews all queued messages when set to :all")
    (swap! (:cfg agent) assoc :follow-up-mode :one-at-a-time)
    (t/is (= ["f1"] (state/peek-queued-messages agent)))
    (t/is (= ["f1"] (vec (next-drain)))
          "the preview is exactly what the next drain consumes")))

(t/deftest test-loop-cancel-clears-queues
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (state/steer! agent "x")
    (state/follow-up! agent "y")
    (loop/cancel-turn agent)
    (t/is (empty? @(:steering agent)) "cancel drops steering messages")
    (t/is (empty? @(:follow-up agent)) "cancel drops follow-up messages")
    (t/is (= :idle @(:status agent)))
    (t/is (= {:type :queue-update :steering [] :follow-up [] :reason :aborted}
             (->> @events (filter #(= :queue-update (:type %))) last))
          "the cancel drop is distinguishable from a drain")))

(t/deftest test-loop-steer-injects-mid-run
  (let [calls (atom 0)
        events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        ;; second call: verify the steered message reached context
                        (do (when-let [on-text (:on-text opts)]
                              (on-text (if (some #(= "steered" (get-in % [:content 0 :text]))
                                                 (:messages opts))
                                         "ok" "MISSING")))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (state/steer! agent "steered")
                    {:content "ok" :is-error false})]
      ;; deref inside with-redefs keeps the rebinding until the run completes
      @(loop/run-agent-turn agent
                            {:message "first"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 2 @calls) "exactly two LLM calls: initial + steered")
    (t/is (some #(= "steered" (get-in % [:content 0 :text])) (state/get-context agent))
          "steered message should be in context")
    (t/is (= "ok" (get-in (last (state/get-context agent)) [:content 0 :text]))
          "final response should see the steered message")
    (t/is (empty? @(:steering agent)) "steering queue drained")
    (let [queue-updates (filter #(= :queue-update (:type %)) @events)]
      (t/is (= 2 (count queue-updates))
            "one :queue-update from steer!, one from consumption")
      (t/is (= {:steering ["steered"] :follow-up []}
               (select-keys (first queue-updates) [:steering :follow-up]))
            "steer! reports the queued message")
      (t/is (= {:steering [] :follow-up []}
               (select-keys (last queue-updates) [:steering :follow-up]))
            "consumption reports the drained queue"))
    (t/is (= :idle @(:status agent)) "agent idle after run")))

(t/deftest test-loop-followup-continues-run
  (let [calls (atom 0)
        done-count (atom 0)
        events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text (if (= 2 @calls) "second" "third")))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (state/follow-up! agent "followup")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-done (fn [_] (swap! done-count inc))
                             :on-error (fn [_])}))
    (t/is (= 3 @calls) "three LLM calls: initial, tool follow-up, queued follow-up")
    (t/is (= 1 @done-count) "on-done called once, at the very end")
    (let [ctx (state/get-context agent)]
      (t/is (some #(= "followup" (get-in % [:content 0 :text])) ctx)
            "follow-up message should be in context")
      (t/is (some #(and (= :assistant (:role %))
                        (= "second" (get-in % [:content 0 :text]))) ctx))
      (t/is (some #(and (= :assistant (:role %))
                        (= "third" (get-in % [:content 0 :text]))) ctx)))
    (t/is (empty? @(:follow-up agent)) "follow-up queue drained")
    (let [queue-updates (filter #(= :queue-update (:type %)) @events)]
      (t/is (= 2 (count queue-updates))
            "one :queue-update from follow-up!, one from consumption")
      (t/is (= {:steering [] :follow-up ["followup"]}
               (select-keys (first queue-updates) [:steering :follow-up]))
            "follow-up! reports the queued message")
      (t/is (= {:steering [] :follow-up []}
               (select-keys (last queue-updates) [:steering :follow-up]))
            "consumption reports the drained queue"))
    (t/is (= :idle @(:status agent)) "agent idle after run")))

(t/deftest test-loop-agent-end-queued-messages-start-attempts
  ;; pi: _handlePostAgentRun ends with hasQueuedMessages(), so messages an
  ;; :agent-end handler queues run in fresh attempts of the same prompt —
  ;; agent.continue drains the queue into that attempt's prompt (steering
  ;; first, then follow-up). Messages queued by :agent-settled handlers stay
  ;; queued: settle fires in the prompt's finally.
  (let [calls (atom 0)
        dones (atom 0)
        seen (atom [])
        events (atom [])
        queue-once (atom true)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))
        dereg-end (event-bus/on-event
                   :agent-end
                   (fn [_]
                     (when (compare-and-set! queue-once true false)
                       (state/steer! agent "steered by agent-end")
                       (state/follow-up! agent "followed up by agent-end"))))
        dereg-settled (event-bus/on-event
                       :agent-settled
                       (fn [_] (state/follow-up! agent "queued by agent-settled")))]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (swap! seen conj (:messages opts))
                      (future
                        (when-let [on-text (:on-text opts)]
                          (on-text (str "response-" (swap! calls inc))))
                        (when-let [on-done (:on-done opts)]
                          (on-done :stop))
                        :done))]
        @(loop/run-agent-turn agent
                              {:message "hi"
                               :on-done (fn [_] (swap! dones inc))
                               :on-error (fn [_])}))
      (finally
        (dereg-end)
        (dereg-settled)))
    (t/is (= 3 (count @seen))
          "three requests: the steering batch opens attempt 2, whose own
           follow-up drain then runs the third (pi: runLoop's outer poll)")
    (t/is (= 1 @dones)
          "on-done is the prompt's teardown — once per prompt")
    (t/is (= 2 (count (filter #(= :agent-start (:type %)) @events)))
          "one :agent-start per attempt")
    (t/is (= 2 (count (filter #(= :agent-end (:type %)) @events)))
          "one :agent-end per attempt")
    (t/is (= 1 (count (filter #(= :agent-settled (:type %)) @events)))
          "exactly one :agent-settled per prompt, after the continuations")
    (let [user-text (fn [msgs]
                      (mapv #(get-in % [:content 0 :text])
                            (filter #(= :user (:role %)) msgs)))]
      (t/is (= ["hi" "steered by agent-end"] (user-text (second @seen)))
            "the steering batch drains first, as the next attempt's prompt")
      (t/is (= ["hi" "steered by agent-end" "followed up by agent-end"]
               (user-text (nth @seen 2)))
            "the follow-up batch is drained by attempt 2's own poll, before it
             ends — it costs no extra attempt"))
    (t/is (= ["hi" "steered by agent-end" "followed up by agent-end"]
             (mapv #(get-in % [:content 0 :text])
                   (filter #(= :user (:role %)) (state/get-context agent)))))
    (t/is (= ["queued by agent-settled"] @(:follow-up agent))
          "a message queued after the last :agent-end stays queued")
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-steer-one-at-a-time
  (let [calls (atom 0)
        agent (loop/make-agent-state :steering-mode :one-at-a-time)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (state/steer! agent "s1")
                    (state/steer! agent "s2")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 3 @calls) "three LLM calls: initial + one per steered message")
    (t/is (some #(= "s1" (get-in % [:content 0 :text])) (state/get-context agent)))
    (t/is (some #(= "s2" (get-in % [:content 0 :text])) (state/get-context agent)))
    (t/is (empty? @(:steering agent)) "steering queue drained")
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-consumed-queued-messages-emit-user-message-start
  ;; The UI displays consumed steering/follow-up messages on :message-start
  ;; (pi: message_start → addMessageToChat), so the loop must emit it with
  ;; the user role and the queued text when a message is drained.
  (let [calls (atom 0)
        events (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (state/steer! agent "steered")
                    (state/follow-up! agent "followup")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-done (fn [_])
                             :on-error (fn [_])})
      (let [user-starts (filter #(and (= :message-start (:type %))
                                      (= :user (:role (:message %))))
                                @events)
            texts (mapv #(get-in % [:message :content 0 :text]) user-starts)]
        (t/is (some #(= "steered" %) texts)
              "steered message emits :message-start with role :user on consumption")
        (t/is (some #(= "followup" %) texts)
              "follow-up message emits :message-start with role :user on consumption")))))

(t/deftest test-loop-no-api-key-emits-user-message-start
  ;; The run cannot start without an API key, but the submitted message must
  ;; still be displayed (pi emits message_start for prompt messages before
  ;; running the loop) — the UI adds it to the chat on :message-start.
  (let [events (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] nil)]
      @(loop/run-agent-turn agent
                            {:message "hello"
                             :on-error (fn [_])})
      (let [user-start (first (filter #(and (= :message-start (:type %))
                                            (= :user (:role (:message %))))
                                      @events))]
        (t/is (some? user-start) ":message-start emitted for the submitted message")
        (t/is (= "hello" (get-in user-start [:message :content 0 :text])))
        (t/is (empty? (state/get-context agent))
              "message is display-only — not added to context without a run")))))

;; ─── Queue modes (per-queue) ───────────────────────────────────────────────

(t/deftest test-loop-queue-modes-default
  (let [agent (loop/make-agent-state)]
    (t/is (= :all (:steering-mode @(:cfg agent))))
    (t/is (= :one-at-a-time (:follow-up-mode @(:cfg agent))))))

(t/deftest test-loop-queue-mode-setters
  ;; /settings applies queue modes + idle timeout live (pi: setSteeringMode /
  ;; setFollowUpMode / setHttpIdleTimeoutMs)
  (let [agent (loop/make-agent-state)]
    (swap! (:cfg agent) assoc :steering-mode :one-at-a-time)
    (swap! (:cfg agent) assoc :follow-up-mode :one-at-a-time)
    (t/is (= :one-at-a-time (:steering-mode @(:cfg agent))))
    (t/is (= :one-at-a-time (:follow-up-mode @(:cfg agent))))
    (state/set-http-idle-timeout-ms! agent 60000)
    (t/is (= 60000 (:http-idle-timeout-ms @(:cfg agent))))))

(t/deftest test-loop-auto-compact-flag
  ;; pi: autoCompact — the flag gates proactive compaction, defaults on,
  ;; and is settable live (/settings row)
  (let [agent (loop/make-agent-state)]
    (t/is (true? (:auto-compact @(:cfg agent))))
    (state/set-auto-compact! agent false)
    (t/is (false? (:auto-compact @(:cfg agent))))
    (t/is (false? (compact/maybe-compact! agent))
          "no session → no proactive compaction regardless"))
  (let [agent (loop/make-agent-state :auto-compact false)]
    (t/is (false? (:auto-compact @(:cfg agent))))))

(t/deftest test-loop-followup-one-at-a-time
  (let [calls (atom 0)
        agent (loop/make-agent-state :follow-up-mode :one-at-a-time)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (state/follow-up! agent "f1")
                    (state/follow-up! agent "f2")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 4 @calls) "four LLM calls: initial + tool + one per follow-up")
    (t/is (empty? @(:follow-up agent)) "follow-up queue drained")))
