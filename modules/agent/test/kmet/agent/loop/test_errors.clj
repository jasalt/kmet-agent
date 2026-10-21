(ns kmet.agent.loop.test-errors
  "Cancellation, error events, and retry classification: how failures surface and
   which ones the loop retries."
  (:require [clojure.test :as t]
            [kmet.ai.llm :as llm]
            [kmet.agent.tools.core :as tools]
            [kmet.agent.loop :as loop]
            [kmet.agent.retry :as retry]
            [kmet.agent.config :as cfg]))

;; ─── Cancellation ─────────────────────────────────────────────────────────

(t/deftest test-loop-cancel-delivers-promise
  (let [errors (atom [])
        dones (atom 0)
        events (atom [])
        started (promise)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  ;; Signal entry, then leave the call unresolved. Only the
                  ;; agent's cancel signal may settle it.
                  (fn [_]
                    (deliver started true)
                    (promise))]
      (let [fut (loop/run-agent-turn agent
                                     {:message "hi"
                                      :on-done (fn [_] (swap! dones inc))
                                      :on-error (fn [e] (swap! errors conj e))})]
        (t/is (true? (deref started 2000 false)) "the LLM call is in flight")
        (loop/cancel-turn agent)
        (t/is (not= ::timeout (deref fut 2000 ::timeout))
              "run settles after cancel")
        (t/is (empty? @errors) "no error callback on cancel")
        (t/is (zero? @dones) "no done callback on cancel")
        (t/is (= :idle @(:status agent)) "status idle after cancel")
        ;; The cancelled call settles as a stopReason-aborted attempt
        ;; (pi: an aborted partial persists to the session history)
        (t/is (some #(and (= :message-end (:type %))
                          (= :aborted (:stop-reason (:message %))))
                    @events)
              "cancel delivers an aborted :message-end")
        (let [ae-idx (first (keep-indexed #(when (= :agent-end (:type %2)) %1) @events))
              as-idx (first (keep-indexed #(when (= :agent-settled (:type %2)) %1) @events))]
          (t/is (some? ae-idx) ":agent-end emitted on cancel")
          (t/is (some? as-idx) ":agent-settled emitted on cancel")
          (t/is (< ae-idx as-idx) ":agent-settled follows :agent-end on cancel"))))))

;; ─── Error events ─────────────────────────────────────────────────────────

(t/deftest test-loop-error-event-emitted
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "boom"))
                      :done))]
      @(loop/run-agent-turn agent
                            {:message "hi"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (let [types (mapv :type @events)]
      (t/is (some #{:error} types) ":error event emitted on LLM error")
      (t/is (some #(and (= :agent-end (:type %)) (= "boom" (:error %))) @events)
            ":agent-end carries the error")
      (t/is (some #{:agent-settled} types)
            ":agent-settled emitted after an errored run"))))

(t/deftest test-loop-cancel-during-tool-execution
  (let [errors (atom [])
        dones (atom 0)
        started (promise)
        release (promise)
        agent (loop/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (deliver started true)
                    (deref release 2000 :timeout)
                    {:content "ok" :is-error false})]
      (let [fut (loop/run-agent-turn agent
                                     {:message "hi"
                                      :on-done (fn [_] (swap! dones inc))
                                      :on-error (fn [e] (swap! errors conj e))})]
        (t/is (true? (deref started 2000 false)) "the tool is executing")
        (loop/cancel-turn agent)
        ;; Let the stub return after cancellation. The bounded future wait
        ;; still catches a loop that fails to observe the signal.
        (deliver release true)
        (t/is (not= ::timeout (deref fut 2000 ::timeout))
              "run settles after cancellation during tool execution")
        (t/is (empty? @errors) "no error callback on cancel")
        (t/is (zero? @dones) "no done callback on cancel")
        (t/is (= :idle @(:status agent)) "status idle after cancel")))))

;; ─── Retry classification ─────────────────────────────────────────────────

(t/deftest test-loop-retryable-error?
  (t/is (retry/retryable-error? "rate limit exceeded"))
  (t/is (retry/retryable-error? "429 Too Many Requests"))
  (t/is (retry/retryable-error? "503 service unavailable"))
  (t/is (retry/retryable-error? "Internal Server Error"))
  (t/is (retry/retryable-error? "connection refused"))
  (t/is (retry/retryable-error? "ECONNRESET: socket hang up"))
  ;; Network transport failures — the llm layer reports these with a stable
  ;; 'network error' token (java.net.http ConnectExceptions carry a nil
  ;; message on this JDK; see kmet.ai.llm/transport-error-message)
  (t/is (retry/retryable-error? "network error: ConnectException"))
  (t/is (retry/retryable-error? "network error: Connection reset"))
  (t/is (retry/retryable-error? "network error: request timed out"))
  (t/is (retry/retryable-error? "Request timed out"))
  (t/is (retry/retryable-error? "HTTP/1.1 header parser received no bytes"))
  (t/is (retry/retryable-error? "Provider returned error: upstream connect"))
  ;; HTTP/2 RST_STREAM (java.net.http throws a plain IOException whose
  ;; message is "Received RST_STREAM: <code>") — same class of transport
  ;; reset as "Connection reset", so it must be retried too; both the raw
  ;; llm-layer message and the sse read-path "Stream error: ..." prefix.
  (t/is (retry/retryable-error? "Received RST_STREAM: Protocol error"))
  (t/is (retry/retryable-error? "Stream error: Received RST_STREAM: Protocol error"))
  (t/is (retry/retryable-error? "Received RST_STREAM: CANCEL"))
  (t/is (retry/retryable-error? "Stream error: Connection reset"))
  ;; Other JVM transport-reset phrasings (OS/JDK-specific) surfacing on the
  ;; sse read path without the 'network error' token.
  (t/is (retry/retryable-error? "Software caused connection abort: recv failed"))
  (t/is (retry/retryable-error? "Stream error: Software caused connection abort"))
  (t/is (retry/retryable-error? "An existing connection was forcibly closed by the remote host"))
  (t/is (retry/retryable-error? "Broken pipe"))
  ;; Mid-stream close on the sse read path — java.net.http surfaces a
  ;; dropped connection as a bare "closed" IOException, wrapped by kmet's
  ;; 'Stream error: ' prefix (no other token in the message)
  (t/is (retry/retryable-error? "Stream error: closed"))
  (t/is (retry/retryable-error? "Stream error: Connection is closed"))
  ;; premature EOF on the response stream — same mid-drop family as the
  ;; stream-close / connection-lost tokens ('EOF reached while reading' is
  ;; the JDK HTTP client's wording for a connection that ended mid-body)
  (t/is (retry/retryable-error? "Error: EOF reached while reading"))
  (t/is (retry/retryable-error? "Unexpected EOF"))
  (t/is (not (retry/retryable-error? "EOF Exception: quota exceeded")))
  ;; non-close stream errors stay non-retryable
  (t/is (not (retry/retryable-error? "Stream error: Bedrock stream frame CRC mismatch")))
  ;; OpenRouter upstream-routing failure — transient even without a status
  ;; token in the body (with one, the 'HTTP 5xx: ' prefix matches first)
  (t/is (retry/retryable-error? "Upstream request failed: Endpoint  is unavailable."))
  (t/is (retry/retryable-error? "HTTP 503: Upstream request failed: Endpoint  is unavailable."))
  (t/is (not (retry/retryable-error? "insufficient_quota")))
  (t/is (not (retry/retryable-error? "Monthly usage limit reached")))
  (t/is (not (retry/retryable-error? "GoUsageLimitError")))
  (t/is (not (retry/retryable-error? "Invalid API key provided")))
  (t/is (not (retry/retryable-error? nil)))
  (t/is (not (retry/retryable-error? ""))))

(t/deftest test-loop-retry-delay-and-call
  (t/testing "retry-delay-ms — pi: retryDelayMs (exponential, capped)"
    (t/is (= 2000 (retry/retry-delay-ms {:base-delay-ms 2000} 1)))
    (t/is (= 4000 (retry/retry-delay-ms {:base-delay-ms 2000} 2)))
    (t/is (= 60000 (retry/retry-delay-ms {:base-delay-ms 2000} 6))
          "the default cap (pi: maxAgentDelayMs ?? 60s)")
    (t/is (= 4 (retry/retry-delay-ms {:base-delay-ms 1 :max-agent-delay-ms 5} 3)))
    (t/is (= 5 (retry/retry-delay-ms {:base-delay-ms 1 :max-agent-delay-ms 5} 4))
          "an explicit cap wins")
    (t/is (= 60000 (:delay-ms (retry/retry-decision {:err "rate limit"
                                                     :retry-count 6
                                                     :max-retries 10
                                                     :base-delay-ms 2000})))
          "the turn-retry decision caps its backoff too (pi: _prepareRetry)"))
  (t/testing "retry-call! — pi: retryAssistantCall"
    (let [policy {:max-retries 3 :base-delay-ms 1 :max-agent-delay-ms 5}
          calls (atom [])
          events (atom [])
          callbacks {:on-retry-scheduled
                     (fn [attempt max-attempts delay-ms err]
                       (swap! events conj [:scheduled attempt max-attempts delay-ms err]))
                     :on-retry-attempt-start
                     (fn [] (swap! events conj [:attempt-start]))
                     :on-retry-finished
                     (fn [success attempt & [final-error]]
                       (swap! events conj [:finished success attempt final-error]))}]
      (t/is (= {:stop-reason :stop :text "ok"}
               (retry/retry-call!
                (fn []
                  (swap! calls conj :call)
                  (if (= 1 (count @calls))
                    {:stop-reason :error :error-message "rate limit exceeded"}
                    {:stop-reason :stop :text "ok"}))
                policy (atom false) callbacks))
            "a retryable error is retried; the retry's result is returned")
      (t/is (= 2 (count @calls)))
      (t/is (= [[:scheduled 1 3 1 "rate limit exceeded"]
                [:attempt-start]
                [:finished true 1 nil]]
               @events)
            "pi's callback order and payloads")

      (reset! calls [])
      (reset! events [])
      (t/is (= {:stop-reason :error :error-message "insufficient_quota"}
               (retry/retry-call!
                (fn [] (swap! calls conj :call)
                  {:stop-reason :error :error-message "insufficient_quota"})
                policy (atom false) callbacks))
            "a non-retryable failure returns immediately")
      (t/is (= 1 (count @calls)))
      (t/is (empty? @events) "no retry callbacks for a deterministic failure")

      (reset! calls [])
      (reset! events [])
      (t/is (= {:stop-reason :error :error-message "rate limit exceeded"}
               (retry/retry-call!
                (fn [] (swap! calls conj :call)
                  {:stop-reason :error :error-message "rate limit exceeded"})
                (assoc policy :max-retries 1) (atom false) callbacks)))
      (t/is (= 2 (count @calls)) "one retry: the initial call plus max-retries")
      (t/is (= [[:scheduled 1 1 1 "rate limit exceeded"]
                [:attempt-start]
                [:finished false 1 "rate limit exceeded"]]
               @events))

      (reset! calls [])
      (reset! events [])
      (t/is (= {:stop-reason :aborted :text ""}
               (retry/retry-call!
                (fn [] (swap! calls conj :call)
                  {:stop-reason :error :error-message "rate limit exceeded" :text ""})
                policy (atom true) callbacks))
            "an abort during the backoff normalizes to an aborted result (pi)")
      (t/is (= 1 (count @calls)))
      (t/is (= [[:scheduled 1 3 1 "rate limit exceeded"]
                [:finished false 1 "rate limit exceeded"]]
               @events)
            "no attempt-start after an aborted sleep (pi)"))))

(t/deftest test-loop-context-overflow?
  (t/is (retry/context-overflow? "prompt is too long: 213462 tokens > 200000 maximum"))
  (t/is (retry/context-overflow? "This model's maximum context length is 128000 tokens"))
  (t/is (retry/context-overflow? "Your input exceeds the context window of this model"))
  (t/is (retry/context-overflow? "context_length_exceeded"))
  (t/is (retry/context-overflow? "exceeded model token limit: 100000 (requested: 200000)"))
  (t/is (not (retry/context-overflow? "rate limit exceeded")))
  (t/is (not (retry/context-overflow? "Throttling error: Too many tokens, please wait")))
  (t/is (not (retry/context-overflow? nil))))
