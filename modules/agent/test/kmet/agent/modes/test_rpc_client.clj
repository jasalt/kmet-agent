(ns kmet.agent.modes.test-rpc-client
  "End-to-end RPC client tests: spawn a real `--mode rpc`
   process and drive it over real stdin/stdout through
   kmet.agent.modes.rpc-client. ^:slow — a subprocess spawn plus the child's cold
   start loading the catalogs.

   The child runs with a private agent dir under target/ that pins a custom
   provider with no credential: a prompt therefore settles with :error
   without touching the network, which is what makes the full prompt →
   events → settle path testable offline. The provider id is not in the
   env-var table, so no ambient key can turn the run into a real request."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.agent.modes.rpc-client :as client]))

(def ^:private target-root
  (str (fs/path (fs/cwd) "target" "test-rpc-client")))

(def ^:private agent-dir
  (str (fs/path target-root "agent")))

(defn- start-client!
  "Write the private agent dir's models.edn and spawn the RPC child. The
   custom provider is deliberately unauthenticated (see the ns docstring).
   The returned client has answered a get-state, so the child is past its
   cold start and in the serve loop — stop! then sees a prompt EOF instead
   of racing the startup against its stop timeout."
  []
  (fs/create-dirs agent-dir)
  (spit (str (fs/path agent-dir "models.edn"))
        (pr-str {:providers {:rpc-client-e2e
                             {:base-url "http://127.0.0.1:1/v1"
                              :api :openai-completions
                              :models [{:id "e2e-model" :name "E2E"}]}}}))
  (let [c (client/start {:cwd (str (fs/cwd))
                         :env {"KMET_CODING_AGENT_DIR" agent-dir}
                         :args ["--provider" "rpc-client-e2e"
                                "--model" "e2e-model"
                                "--session-dir" (str (fs/path target-root "sessions"))]})
        state (client/request! c {:type :get-state} 30000)]
    (when-not (:success state)
      (throw (ex-info "RPC child failed to start"
                      {:response state :stderr (client/stderr c)})))
    c))

(deftest ^:slow test-client-command-round-trip
  (testing "responses correlate by :id over real stdio, and stdin EOF
            disposes the child with exit 0"
    (let [c (start-client!)]
      (try
        (testing "get-state reports the pinned model and provider"
          (let [state (client/request! c {:type :get-state})]
            (is (= :response (:type state)))
            (is (true? (:success state)))
            (is (= :get-state (:command state)))
            (is (str/starts-with? (:id state) "req-")
                "the client assigned the id and got it back")
            (is (= "e2e-model" (get-in state [:data :model])))
            (is (= :rpc-client-e2e (get-in state [:data :provider])))))
        (testing "get-commands answers the RPC-filtered command surface"
          (let [commands (client/request! c {:type :get-commands})]
            (is (true? (:success commands)))
            (is (some #(= "compact" (:name %)) (get-in commands [:data :commands]))
                "a portable builtin is listed")))
        (testing "steer queues and clear-queue returns the dropped text"
          (is (= {:disposition :queued}
                 (:data (client/request! c {:type :steer :message "queued"}))))
          (is (= {:steering ["queued"] :follow-up []}
                 (:data (client/request! c {:type :clear-queue})))))
        (is (= [] (client/unparsed-lines c))
            "stdout carried only protocol records")
        (is (str/blank? (client/stderr c))
            "a clean start writes nothing to stderr")
        (finally
          (is (zero? (client/stop! c))))))))

(deftest ^:slow test-client-prompt-streams-events-and-settles
  (testing "prompt answers its disposition; the run's stream and settle
            arrive as events, and wait-idle reports the finished run"
    (let [c (start-client!)
          events (atom [])
          settled (client/on-settle! c)]
      (client/on-event! c #(swap! events conj %))
      (try
        (let [resp (client/request! c {:type :prompt :message "hello"})]
          (is (= {:disposition :started} (:data resp))
              "accepted is not completed — the settle is an event"))
        (is (= :error (:outcome (deref (:settle settled) 15000 nil)))
            "the unauthenticated provider settles the run with :error")
        (is (some #(= :message-start (:type %)) @events)
            "the submitted message reached the stream")
        (is (= {:type :agent-settled :outcome :error}
               (client/wait-idle! c 15000))
            "an already-settled engine is reported from the state probe")
        (finally
          (is (zero? (client/stop! c))))))))

(deftest ^:slow test-client-malformed-line-answers-a-parse-response
  (testing "a malformed record answers a parse response without an id (there
            is nothing to correlate it with) and the loop keeps serving"
    (let [c (start-client!)
          parsed (promise)]
      (client/on-event! c (fn [record]
                            (when (and (= :response (:type record))
                                       (= :parse (:command record)))
                              (deliver parsed record))))
      (try
        (client/send-line! c "{:type :prompt")
        (let [resp (deref parsed 15000 nil)]
          (is (some? resp) "the malformed line answered a parse response")
          (is (false? (:success resp)))
          (is (str/starts-with? (:error resp) "Failed to parse command:"))
          (is (not (contains? resp :id))))
        (is (true? (:success (client/request! c {:type :get-state}))))
        (is (= [] (client/unparsed-lines c)))
        (finally
          (is (zero? (client/stop! c))))))))

(deftest ^:slow test-client-request-after-stop-throws
  (testing "once the process is gone, a request fails with :rpc-exited"
    (let [c (start-client!)]
      (is (zero? (client/stop! c)))
      (let [e (try (client/request! c {:type :get-state})
                   nil
                   (catch Exception e e))]
        (is (some? e))
        (is (= :rpc-exited (:type (ex-data e))))))))
