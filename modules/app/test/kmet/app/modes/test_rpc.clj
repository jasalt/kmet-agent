(ns kmet.app.modes.test-rpc
  "RPC mode end to end: `--profile rpc` boots the same composition print mode
   does, serves the EDN protocol on stdin and stdout, and streams a run as the
   wire projection. One agent, one stream, commands in.

   The model is stubbed and the session log lives under target/
   (`kmet.app.test-support`), as for every mode test."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.agent.extensions.core :as extensions]
            [kmet.ai.models :as models]
            [kmet.app.test-support :as support :refer [answering
                                                       with-mode-run
                                                       with-model-catalog]]))

(defn- run-rpc
  "Run the mode with LINES on stdin, answering `{:code :err :records}` — the
   protocol records it wrote, in order.

   stdin is a loopback socket, so the test owns both ends: it can keep the
   mode's stdin open while a run streams and close it when it is done. That
   matters because closing stdin ends the mode — and a mode that ends while its
   run is live cancels the run, which is right for a client that left and wrong
   for a test that only wanted to read the answer. `:until` is a string to wait
   for on the wire before closing. ARGS replace `--profile rpc` when a test
   wants another spelling of the invocation."
  [{:keys [lines args until]}]
  (let [server (java.net.ServerSocket. 0)
        accepted (future (.accept server))
        client (java.net.Socket. "127.0.0.1" (.getLocalPort server))
        peer @accepted
        reader (io/reader (.getInputStream peer))
        writer (io/writer (.getOutputStream client))
        out (java.io.StringWriter.)
        result (future (binding [*in* reader]
                         (support/run-main-into (or args ["--profile" "rpc"]) out)))]
    (doseq [line lines]
      (.write writer (str line "\n")))
    (.flush writer)
    (try
      (when until
        (support/wait-until #(str/includes? (str out) until)))
      (finally
        ;; EOF for the mode: the end of its input. The mode's own end of the
        ;; socket stays open until it has read to that EOF and returned.
        (.close client)))
    (let [{:keys [code err]} (deref result 60000 ::timeout)]
      (.close peer)
      (.close server)
      {:code code
       :err err
       :records (mapv edn/read-string (remove str/blank? (str/split-lines (str out))))})))

(defn- responses
  [records]
  (filter #(= :response (:type %)) records))

(defn- by-id
  "The one response that carries ID — every command in these tests names one,
   because the id is how a client correlates an answer with its request."
  [records id]
  (first (filter #(= id (:id %)) (responses records))))

(deftest rpc-mode-serves-a-prompt-and-streams-the-wire
  (with-mode-run
    (answering "hello from the model")
    (fn []
      (let [{:keys [code records]}
            (run-rpc {:lines ["{:type :prompt :id \"p1\" :message \"hi\"}"]
                      :until ":agent-settled"})]
        (is (= 0 code) "stdin closing ends the mode, and an orderly end exits 0")
        (is (= {:type :response :command :prompt :success true :id "p1"
                :data {:disposition :started}}
               (by-id records "p1"))
            "the command is answered with the id it carried, and accepted is
             not completed")
        (is (some #(and (= :message-update (:type %))
                        (= "hello from the model" (get-in % [:delta :content])))
                  records)
            "the run streams as the wire projection — the same events edn print
             mode prints")
        (is (some #(= :agent-settled (:type %)) records)
            "and the settle is the end a client waits for")))))

(deftest rpc-mode-answers-a-malformed-line-and-an-unknown-command
  (with-mode-run
    (answering "never runs")
    (fn []
      (let [{:keys [code records]} (run-rpc {:lines ["not edn at all"
                                                     "{:type :frobnicate :id \"f1\"}"]})]
        (is (= 0 code) "a bad line is an answer, not a crash")
        (is (= :parse (:command (first (responses records))))
            "a malformed line answers a parse failure without an id: there is
             nothing to correlate it with")
        (is (str/includes? (str (:error (by-id records "f1"))) "Unknown command")
            "an unknown type says so, so a client can tell a typo from a
             refusal")))))

(deftest a-later-landings-command-is-refused-by-name
  ;; kmet1's protocol has commands kmet2 cannot answer yet. Each names the
  ;; landing that fills it: a silent success would be a wrong answer, and
  ;; "Unknown command" would read as a typo.
  (with-mode-run
    (answering "never runs")
    (fn []
      (let [{:keys [records]}
            (run-rpc {:lines ["{:type :set-model :id \"m1\" :provider \"p\" :model-id \"m\"}"
                              "{:type :compact :id \"c1\"}"
                              "{:type :get-commands :id \"g1\"}"]})]
        (is (str/includes? (str (:error (by-id records "m1"))) "landing 6")
            "the model surface is landing 6's")
        (is (str/includes? (str (:error (by-id records "c1"))) "landing 7")
            "compaction is landing 7's")
        (is (every? #(str/includes? (str (:error (by-id records %))) "is not implemented in rpc mode yet")
                    ["m1" "c1" "g1"])
            "and every one of them is the named refusal")))))

(deftest the-state-a-client-polls
  (with-mode-run
    (answering "ok")
    (fn []
      (let [{:keys [records]}
            (run-rpc {:lines ["{:type :prompt :id \"p\" :message \"hi\"}"
                              "{:type :get-state :id \"s\"}"]})
            state (:data (by-id records "s"))]
        (is (= "deepseek-v4-flash" (:model state))
            "the agent's model, as the run's creation option set it")
        (is (= :opencode-go (:provider state)))
        (is (string? (get-in state [:session :id]))
            "and the session the events belong to")
        (is (number? (get-in state [:session :message-count])))
        (is (contains? state :turn-running?)
            "whether a turn is in flight, which is what a reconnecting client
             folds")))))

(deftest the-invocations-model-reaches-the-run
  ;; `--model` and `--provider` are the agent's creation options here too — the
  ;; driver resolves the pattern per creation, exactly as in print mode. Without
  ;; this the mode would silently run the settings' model while the command line
  ;; said otherwise.
  (with-model-catalog
    (fn []
      (with-mode-run
        (answering "ok")
        (fn []
          (let [{:keys [records]}
                (run-rpc {:lines ["{:type :get-state :id \"s\"}"]
                          :args ["--profile" "rpc"
                                 "--model" "opencode-go/deepseek-v4-flash:high"]})
                state (:data (by-id records "s"))]
            (is (= "deepseek-v4-flash" (:model state))
                "the run's model is the one the pattern named")
            (is (= :opencode-go (:provider state)))
            (is (= :high (:thinking-level state))
                "and its thinking level, which the settings did not set")))))))

(deftest the-model-catalog-is-on-the-wire
  ;; get-available-models answers the snapshot a `--model` pattern resolves
  ;; against. What the catalog contains is kmet.ai.models' subject; what this
  ;; mode owes is the answer.
  (with-redefs [models/get-available (fn [& _] [{:provider :opencode-go
                                                 :id "deepseek-v4-flash"}])]
    (with-mode-run
      (answering "ok")
      (fn []
        (let [{:keys [records]} (run-rpc {:lines ["{:type :get-available-models :id \"m\"}"]})]
          (is (= [{:provider :opencode-go :id "deepseek-v4-flash"}]
                 (:models (:data (by-id records "m"))))))))))

(deftest an-idle-abort-is-a-no-op
  (with-mode-run
    (answering "ok")
    (fn []
      (let [{:keys [records]} (run-rpc {:lines ["{:type :abort :id \"a\"}"]})]
        (is (= {:type :response :command :abort :success true :id "a"
                :data {:idle true}}
               (by-id records "a"))
            "nothing was running, so nothing was cancelled")))))

(deftest rpc-mode-binds-a-front-end-with-a-port
  ;; kmet1's rpc mode is `:mode :rpc`, `has-ui true`, with a port whose output
  ;; channels travel the protocol: an extension's message reaches the client as
  ;; a `:ui-request` record.
  (let [seen (atom nil)]
    (with-mode-run
      (fn [opts]
        (reset! seen {:mode (extensions/extension-mode)
                      :has-ui (extensions/extension-has-ui?)})
        (extensions/ui-notify "hello from an extension" :warning)
        (future
          (when-let [on-text (:on-text opts)] (on-text "ok"))
          (when-let [on-done (:on-done opts)] (on-done :stop))
          :done))
      (fn []
        (let [{:keys [records]}
              (run-rpc {:lines ["{:type :prompt :message \"hi\"}"]
                        :until ":agent-settled"})]
          (is (= {:mode :rpc :has-ui true} @seen)
              "the run reports a front end with a port, not print mode's headless one")
          (is (some #(and (= :ui-request (:type %))
                          (= :notify (:method %))
                          (= "hello from an extension" (:message %))
                          (= :warning (:notify-type %)))
                    records)
              "and the port's channel is the wire: the client sees what the
               extension asked for"))))))

(deftest a-diagnostic-mount-creates-no-agent
  ;; `--dump-state` mounts every row of the profile, this one included: with no
  ;; `:on-result` the mode must stay inert — no agent, no stdin read.
  (let [{:keys [code out]} (support/run-main ["--profile" "rpc" "--dump-state"])]
    (is (= 0 code))
    (is (some #(and (str/includes? % ":mode-rpc") (str/includes? % ":active"))
              (str/split-lines out))
        "the mode row mounts and activates in a diagnostic boot")
    (is (not (str/includes? out ":agent/"))
        "and no agent was created to serve a run nobody asked for")))

(deftest kmet1s-mode-alias-reaches-the-same-mode
  ;; `--mode rpc` is the kmet1 spelling of `--profile rpc` (landing 5b): it now
  ;; boots a mode instead of reporting that the profile has none.
  (with-mode-run
    (answering "ok")
    (fn []
      (let [{:keys [code records]} (run-rpc {:lines ["{:type :get-state :id \"g\"}"]
                                             :args ["--mode" "rpc"]})]
        (is (= 0 code))
        (is (some? (by-id records "g"))
            "the alias boots the same protocol mode")))))

(deftest an-abort-leaves-the-reader-serving
  ;; The provider stalls, so the run is live for every command after the first.
  ;; The abort answers without waiting for it — a reader parked in a wait would
  ;; stop serving, and the get-state that follows is what proves it did not.
  (let [release (promise)
        stalled (fn [opts]
                  (future
                    (deref release 10000 ::timeout)
                    (when-let [on-text (:on-text opts)] (on-text "ok"))
                    (when-let [on-done (:on-done opts)] (on-done :stop))
                    :done))]
    (with-mode-run
      stalled
      (fn []
        (try
          (let [{:keys [records]}
                (run-rpc {:lines ["{:type :prompt :id \"1\" :message \"hi\"}"
                                  "{:type :prompt :id \"2\" :message \"more\"}"
                                  "{:type :prompt :id \"3\" :message \"more\" :streaming-behavior :steer}"
                                  "{:type :abort :id \"4\"}"
                                  "{:type :get-state :id \"5\"}"]})]
            (is (str/includes? (str (:error (by-id records "2"))) "already running")
                "a prompt while a run is live must say which queue to use")
            (is (= {:disposition :queued} (:data (by-id records "3")))
                "and with one named, the message queues")
            (is (= {:idle true} (:data (by-id records "4")))
                "the abort cancelled the run and says the agent is idle")
            (is (map? (:data (by-id records "5")))
                "and the reader kept serving commands"))
          (finally (deliver release true)))))))
