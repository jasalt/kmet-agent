(ns kmet.modes.test-rpc
  "RPC-mode tests (engine.md step 6a; pi: modes/rpc/rpc-mode.ts) — run()
   serves EDN commands and streams wire events: ids correlate without
   ordering, a malformed record answers a parse response without an id, and
   EOF disposes the engine."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.modes.rpc :as rpc]
            [kmet.app.loop :as agent]
            [kmet.app.session-runtime :as runtime]))

(defn- opts
  "Minimal run opts — :model/:provider bypass catalog resolution; sessions
   land under target/ so test runs leave the checkout clean."
  ([] (opts nil))
  ([extra]
   (merge {:model "deepseek-v4-flash"
           :provider :opencode-go
           :config {:provider :opencode-go
                    :model "deepseek-v4-flash"
                    :session-dir "target/test-rpc-sessions"}}
          extra)))

(defn- run-lines
  "Run the protocol for LINES (maps are pr-str'd), returning
   [result records]: the run's result and the parsed stdout records —
   responses and events interleave, so callers filter by :type."
  [lines & [extra]]
  (let [out (java.io.StringWriter.)
        result (rpc/run (merge (opts extra)
                               {:in (java.io.StringReader. (str (str/join "\n" lines) "\n"))
                                :out out}))
        written (str/trim (str out))]
    [result (if (str/blank? written)
              []
              (mapv edn/read-string (str/split-lines written)))]))

(defn- responses [records]
  (filterv #(= :response (:type %)) records))

(defn- emit!
  "Emit EVENT through the stubbed agent's own stream — the path the real
   loop uses."
  [ag event]
  ((:on-event ag) event))

(deftest test-prompt-streams-events-and-settles
  (testing "prompt starts a run, answers with the disposition, and the
            events stream before :agent-settled"
    (with-redefs [agent/run-agent-turn
                  (fn [ag _]
                    (emit! ag {:type :message-update
                               :delta {:type :text :content "hello"}})
                    (emit! ag {:type :message-end
                               :message {:role :assistant
                                         :content [{:type :text :text "hello"}]}})
                    (emit! ag {:type :agent-settled :outcome :completed})
                    nil)]
      (let [[result records] (run-lines [{:type :prompt :id "req-1" :message "hi"}])]
        (is (= :ok result))
        (is (= [{:type :response :id "req-1" :command :prompt :success true
                 :data {:disposition :started}}]
               (responses records))
            "the response correlates the id and reports the disposition")
        (is (= [:message-update :message-end :agent-settled]
               (mapv :type (remove #(= :response (:type %)) records)))
            "the wire events follow, in order, and settle last")))))

(deftest test-malformed-line-answers-a-parse-response
  (testing "unparsable EDN answers a parse response without an id (there is
            nothing to correlate it with) and the loop keeps serving"
    (let [[result records] (run-lines ["{:type :prompt" {:type :get-state :id "s1"}])]
      (is (= :ok result))
      (let [parse (first records)]
        (is (= :response (:type parse)))
        (is (= :parse (:command parse)))
        (is (false? (:success parse)))
        (is (str/starts-with? (:error parse) "Failed to parse command:"))
        (is (not (contains? parse :id))))
      (is (= :get-state (:command (second records))) "the next line still served")
      (is (= "s1" (:id (second records)))))))

(deftest test-unknown-and-shapeless-records
  (testing "an unknown :type and a record without one fail as responses,
            never as a crashed loop"
    (let [[_ records] (run-lines [{:type :fly :id "x"}
                                  {:id "y"}
                                  "42"])
          [fly no-type scalar] records]
      (is (= {:type :response :id "x" :command :fly :success false
              :error "Unknown command: :fly"}
             fly))
      (is (= "A command record requires a :type" (:error no-type)))
      (is (= "A command must be an EDN map" (:error scalar)))
      (is (false? (:success scalar))))))

(deftest test-missing-id-is-omitted
  (testing "the response omits :id when the command had none"
    (let [[_ records] (run-lines [{:type :get-state}])]
      (is (not (contains? (first records) :id))))))

(deftest test-get-state-reports-the-session
  (testing "get-state returns the live model, status, and session summary"
    (let [[_ records] (run-lines [{:type :get-state :id 1}])
          data (:data (first records))]
      (is (= "deepseek-v4-flash" (:model data)))
      (is (= :opencode-go (:provider data)))
      (is (false? (:turn-running? data)))
      (is (false? (:compacting? data)))
      (is (= 0 (:message-count (:session data))))
      (is (str/includes? (get-in data [:session :file]) "test-rpc-sessions"))
      (is (contains? data :last-outcome)))))

(deftest test-steer-follow-up-and-clear-queue
  (testing "steer/follow-up queue and clear-queue returns the dropped texts"
    (let [[_ records] (run-lines [{:type :steer :id "a" :message "look at tests"}
                                  {:type :follow-up :id "b" :message "then summarize"}
                                  {:type :clear-queue :id "c"}])
          [steer follow-up clear] (responses records)]
      (is (= {:disposition :queued} (:data steer)))
      (is (= {:disposition :queued} (:data follow-up)))
      (is (= {:steering ["look at tests"] :follow-up ["then summarize"]}
             (:data clear)))
      (let [[_ after] (run-lines [{:type :clear-queue :id "d"}])]
        ;; a fresh run has empty queues — clear-queue never errors on idle
        (is (= {:steering [] :follow-up []} (:data (first (responses after)))))))))

(deftest test-abort-cancels-and-waits-for-idle
  (testing "abort cancels the run and reports the session idle"
    (with-redefs [agent/run-agent-turn (fn [_ _] nil)]  ;; never settles
      (let [[_ records] (run-lines [{:type :prompt :id 1 :message "hi"}
                                    {:type :get-state :id 2}
                                    {:type :abort :id 3}
                                    {:type :get-state :id 4}])
            [prompt running abort idle] (responses records)]
        (is (= {:disposition :started} (:data prompt)))
        (is (true? (:turn-running? (:data running))))
        (is (= {:idle true} (:data abort)))
        (is (false? (:turn-running? (:data idle))))))))

(deftest test-prompt-requires-a-message
  (testing "a prompt without text fails as a response"
    (let [[_ records] (run-lines [{:type :prompt :id 1}])]
      (is (= {:type :response :id 1 :command :prompt :success false
              :error "prompt requires a non-empty :message string"}
             (first records))))))

(deftest test-new-session-switches-the-live-session
  (testing "new-session swaps the engine's session and later state reads see it"
    (let [[_ records] (run-lines [{:type :get-state :id 1}
                                  {:type :new-session :id 2}
                                  {:type :get-state :id 3}])
          [before new-session after] (responses records)]
      (is (false? (:cancelled (:data new-session))))
      (is (not= (get-in before [:data :session :id])
                (get-in new-session [:data :session-id])))
      (is (= (get-in new-session [:data :session-id])
             (get-in after [:data :session :id]))))))

(deftest test-eof-disposes-and-binds-rpc-mode
  (testing "stdin EOF disposes the engine after the loop ends, and the
            engine is bound to extensions as :rpc"
    (let [disposed (promise)
          started (promise)
          orig-dispose runtime/dispose!
          orig-bootstrap runtime/bootstrap]
      (with-redefs [runtime/dispose! (fn [engine] (deliver disposed engine) (orig-dispose engine))
                    runtime/bootstrap (fn [config opts]
                                        (deliver started opts)
                                        (orig-bootstrap config opts))]
        (let [[result _] (run-lines [{:type :get-state :id 1}])]
          (is (= :ok result))
          (is (some? (deref disposed 1000 nil)) "dispose! ran on EOF")
          (is (= :rpc (:mode (deref started 1000 nil))))
          (is (false? (:has-ui (deref started 1000 nil)))))))))

(deftest test-prompt-during-a-run-requires-streaming-behavior
  (testing "pi parity: a prompt while a run is active needs an explicit
            :streaming-behavior; the queued prompt lands on that queue"
    (with-redefs [agent/run-agent-turn (fn [_ _] nil)]  ;; never settles
      (let [[_ records] (run-lines [{:type :prompt :id 1 :message "first"}
                                    {:type :prompt :id 2 :message "second"}
                                    {:type :prompt :id 3 :message "third"
                                     :streaming-behavior :steer}
                                    {:type :follow-up :id 4 :message "later"}
                                    {:type :clear-queue :id 5}])
            [started rejected steered followed cleared] (responses records)]
        (is (= {:disposition :started} (:data started)))
        (is (false? (:success rejected)))
        (is (str/includes? (:error rejected) "already running"))
        (is (= {:disposition :queued} (:data steered)))
        (is (= {:disposition :queued} (:data followed)))
        (is (= {:steering ["third"] :follow-up ["later"]} (:data cleared)))))))

(deftest test-prompt-rejects-an-unknown-streaming-behavior
  (with-redefs [agent/run-agent-turn (fn [_ _] nil)]
    (let [[_ records] (run-lines [{:type :prompt :id 1 :message "hi"
                                   :streaming-behavior :bogus}])]
      (is (= {:type :response :id 1 :command :prompt :success false
              :error "streaming-behavior must be :steer or :follow-up"}
             (first records))))))

(deftest test-streaming-behavior-is-ignored-when-idle
  (testing "pi applies streamingBehavior only while streaming: an idle
            prompt starts a run instead of queueing the message"
    (with-redefs [agent/run-agent-turn (fn [_ _] nil)]
      (let [[_ records] (run-lines [{:type :prompt :id 1 :message "hi"
                                     :streaming-behavior :steer}
                                    {:type :clear-queue :id 2}])
            [started cleared] (responses records)]
        (is (= {:disposition :started} (:data started)))
        (is (= {:steering [] :follow-up []} (:data cleared)))))))

(deftest test-prompt-passes-images-to-the-run
  (testing "images ride the started path into the run's options"
    (let [received (promise)]
      (with-redefs [agent/run-agent-turn (fn [_ opts] (deliver received opts) nil)]
        (let [images [{:type :image :data "AAA" :mime-type "image/png"}]
              [_ records] (run-lines [{:type :prompt :id 1 :message "look"
                                       :images images}])]
          (is (= {:disposition :started} (:data (first (responses records)))))
          (is (= images (:images (deref received 1000 nil)))))))))

(deftest test-steer-rejects-images
  (testing "the steering queue carries no image blocks yet — rejecting is
            honest where dropping would lose data"
    (let [[_ records] (run-lines [{:type :steer :id 1 :message "look"
                                   :images [{:type :image :data "AAA"}]}])]
      (is (= {:type :response :id 1 :command :steer :success false
              :error "steer does not support :images yet"}
             (first records))))))

(deftest test-abort-when-idle-is-a-no-op
  (let [[_ records] (run-lines [{:type :abort :id 1}])]
    (is (= {:idle true} (:data (first (responses records)))))))

(deftest test-trailing-content-is-a-parse-error
  (testing "a line must be exactly one EDN form (JSON.parse parity)"
    (let [[_ records] (run-lines ["{:type :get-state} {:type :get-state}"])
          trailing (first records)]
      (is (= :parse (:command trailing)))
      (is (false? (:success trailing)))
      (is (str/includes? (:error trailing) "Unexpected content after the command record")))
    (let [[_ records] (run-lines ["{:type :get-state} ]"])
          invalid (first records)]
      (is (false? (:success invalid)))
      (is (str/starts-with? (:error invalid) "Failed to parse command:")))))

(deftest test-unknown-session-fails-startup
  (testing "--session matching nothing reports to stderr and returns nil"
    (let [err (java.io.StringWriter.)
          result (binding [*err* err]
                   (rpc/run (merge (opts) {:session "no-such-session"
                                           :in (java.io.StringReader. "")
                                           :out (java.io.StringWriter.)})))]
      (is (nil? result))
      (is (str/includes? (str err) "No session found matching")))))
