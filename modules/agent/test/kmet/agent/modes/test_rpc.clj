(ns kmet.agent.modes.test-rpc
  "RPC-mode tests (pi: modes/rpc/rpc-mode.ts) — run()
   serves EDN commands and streams wire events: ids correlate without
   ordering, a malformed record answers a parse response without an id, and
   EOF disposes the engine."
  (:require [kmet.agent.runtime :as app-runtime]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.agent.modes.rpc :as rpc]
            [kmet.ai.models :as models]
            [kmet.agent.commands :as commands]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.loop.state :as agent-state]
            [kmet.agent.loop :as agent]
            [kmet.agent.prompts :as prompts]
            [kmet.agent.session.core :as session]
            [kmet.agent.session.runtime :as runtime]
            [kmet.agent.skills :as skills]
            [kmet.test-utils :as tu]))

;; RPC mode bootstraps real engines onto the app tree: the runtime commands it
;; registers and the developer's own skills/prompts must not reach later
;; namespaces.
(t/use-fixtures :each tu/with-clean-app-registries)

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
  "Run the protocol for LINES (maps are pr-str'd, strings are served as-is),
   returning [result records]: the run's result and the parsed stdout
   records — responses and events interleave, so callers filter by :type."
  [lines & [extra]]
  (let [out (java.io.StringWriter.)
        text (str/join "\n" (map #(if (string? %) % (pr-str %)) lines))
        result (rpc/run (merge (opts extra)
                               {:in (java.io.StringReader. (str text "\n"))
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
            engine is bound to extensions as :rpc with the UI port"
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
          (is (true? (:has-ui (deref started 1000 nil))))
          (is (map? (:ui-context (deref started 1000 nil))) "the UI port rides along"))))))

(deftest test-eof-streams-the-terminal-settle-of-an-in-flight-run
  (testing "stdin EOF disposes the engine; a run still active is cancelled
            and its terminal :agent-settled reaches the stream before the
            subscriber is dropped (the terminal-event
            contract)"
    (with-redefs [agent/run-agent-turn
                  (fn [ag _]
                    (future
                      (while (not @(:signal ag))
                        (Thread/sleep 5))
                      (emit! ag {:type :agent-settled :outcome :aborted})))]
      (let [[result records] (run-lines [{:type :prompt :id 1 :message "go"}])]
        (is (= :ok result))
        (is (= [:prompt] (mapv :command (responses records))))
        (is (= :agent-settled (:type (last records)))
            "the cancelled run's settle was written before dispose dropped the
             subscriber")
        (is (= :aborted (:outcome (last records))))))))

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
  (testing "an idle abort leaves the queues alone and emits no :aborted
            queue-update (cancel! would do both, having nothing to cancel)"
    (let [[_ records] (run-lines [{:type :steer :id 1 :message "keep me"}
                                  {:type :abort :id 2}
                                  {:type :clear-queue :id 3}])
          [steered aborted cleared] (responses records)]
      (is (= {:disposition :queued} (:data steered)))
      (is (= {:idle true} (:data aborted)))
      (is (= {:steering ["keep me"] :follow-up []} (:data cleared))
          "the queued message survived the idle abort")
      (is (empty? (filter #(and (= :queue-update (:type %))
                                (= :aborted (:reason %)))
                          records))
          "no spurious aborted queue-update"))))

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

(defn- session-with
  "Create a persisted session under target/ with ENTRIES appended; returns
   {:id id :file path :entries [built entries]}. Include an assistant entry
   — the file is only written once one exists (lazy creation)."
  [entries]
  (let [dir (str "target/test-rpc-sessions/setup-" (System/currentTimeMillis)
                 "-" (rand-int 100000))
        sess (session/create-session dir {:cwd (str (fs/cwd))})]
    {:id (:id sess)
     :file (:file sess)
     :entries (mapv #(session/append-entry sess %) entries)}))

(deftest test-get-state-reports-the-queue-modes-and-pending-count
  (testing "get-state carries the queue modes, auto-compaction and the
            pending count; the mode setters apply live (pi parity)"
    (let [[_ records] (run-lines [{:type :get-state :id 1}
                                  {:type :set-steering-mode :id 2 :mode :one-at-a-time}
                                  {:type :set-follow-up-mode :id 3 :mode :all}
                                  {:type :get-state :id 4}
                                  {:type :set-steering-mode :id 5 :mode :bogus}
                                  {:type :steer :id 6 :message "queued"}
                                  {:type :get-state :id 7}])
          [before steer-set follow-set after bad _ queued] (responses records)]
      (is (= :all (:steering-mode (:data before))))
      (is (= :one-at-a-time (:follow-up-mode (:data before))))
      (is (true? (:success steer-set)))
      (is (true? (:success follow-set)))
      (is (= :one-at-a-time (:steering-mode (:data after))))
      (is (= :all (:follow-up-mode (:data after))))
      (is (false? (:success bad)))
      (is (str/includes? (:error bad) "Unknown queue mode"))
      (is (= 1 (:pending-message-count (:data queued)))))))

(deftest test-set-auto-compaction
  (testing "set-auto-compaction toggles the live flag with no data payload"
    (let [[_ records] (run-lines [{:type :get-state :id 1}
                                  {:type :set-auto-compaction :id 2 :enabled false}
                                  {:type :get-state :id 3}
                                  {:type :set-auto-compaction :id 4 :enabled "yes"}])
          [before set-ok after bad] (responses records)]
      (is (true? (:success set-ok)))
      (is (not (contains? set-ok :data)) "pi's set_auto_compaction has no data")
      (is (not= (:auto-compaction-enabled (:data before))
                (:auto-compaction-enabled (:data after))))
      (is (false? (:success bad)))
      (is (str/includes? (:error bad) "boolean")))))

(deftest test-compact-defers-while-a-run-is-in-flight
  (testing "compact served while a run is active answers :accepted with
            :scheduled true and queues the request for the settle"
    (let [dir (str "target/test-rpc-compact-" (System/currentTimeMillis))
          sess (session/create-session dir {:cwd (str (fs/cwd))})
          engine (runtime/bootstrap (:config (opts)) {:session sess})]
      (try
        (reset! (:turn-running? engine) true)
        (is (= {:type :response :command :compact :success true
                :data {:accepted true :scheduled true}}
               ((var rpc/command-compact) engine {:type :compact :id "c"})))
        (is (= 1 (count (runtime/pending-compaction engine)))
            "the request is queued for the settle")
        (finally
          (reset! (:turn-running? engine) false)
          (runtime/dispose! engine)
          (fs/delete-tree dir))))))

(deftest test-model-commands
  (testing "set-model resolves an available record, cycle-model walks the
            catalog, get-available-models answers the snapshot"
    (let [m1 {:id "m1" :provider :alpha :name "M1" :reasoning false}
          m2 {:id "m2" :provider :alpha :name "M2" :reasoning false}
          by-model (fn [p id] (first (filter #(and (= p (:provider %)) (= id (:id %)))
                                             [m1 m2])))]
      (with-redefs [models/get-available (fn [] [m1 m2])
                    models/get-model by-model]
        (let [[_ records] (run-lines [{:type :get-available-models :id 1}
                                      {:type :set-model :id 2 :provider "alpha" :model-id "m2"}
                                      {:type :set-model :id 3 :provider "alpha" :model-id "nope"}
                                      {:type :set-model :id 4 :provider "alpha"}
                                      {:type :cycle-model :id 5}
                                      {:type :get-state :id 6}])
              [available set-ok set-miss set-shapeless cycle state] (responses records)]
          (is (= [m1 m2] (get-in available [:data :models])))
          (is (= m2 (:data set-ok)) "the switched model record comes back")
          (is (= "Model not found: alpha/nope" (:error set-miss)))
          (is (false? (:success set-shapeless)))
          (is (= "m1" (get-in cycle [:data :model :id])) "cycled off m2")
          (is (= :off (get-in cycle [:data :thinking-level])))
          (is (false? (get-in cycle [:data :scoped?])))
          (is (= "m1" (:model (:data state)))))))))

(deftest test-thinking-commands
  (testing "thinking levels list the model's supported set; set validates;
            cycle advances through the list"
    (let [model {:id "r1" :provider :opencode-go :reasoning true
                 :thinking-level-map {:xhigh "x" :max "m"}}]
      (with-redefs [models/get-model (fn [_ _] model)]
        (let [[_ records] (run-lines [{:type :get-available-thinking-levels :id 1}
                                      {:type :set-thinking-level :id 2 :level :low}
                                      {:type :cycle-thinking-level :id 3}
                                      {:type :set-thinking-level :id 4 :level :bogus}
                                      {:type :get-state :id 5}])
              [levels set-ok cycle bad state] (responses records)]
          (is (= [:off :minimal :low :medium :high :xhigh :max]
                 (get-in levels [:data :levels])))
          (is (true? (:success set-ok)))
          (is (not (contains? set-ok :data)) "pi's set_thinking_level has no data")
          (is (= :medium (get-in cycle [:data :level])) "cycles on from :low")
          (is (false? (:success bad)))
          (is (str/includes? (:error bad) "Unknown thinking level"))
          (is (= :medium (:thinking-level (:data state)))))))))

(deftest test-messages-stats-and-last-assistant-text
  (testing "get-messages answers the live context; stats and the last
            assistant text read the session"
    (with-redefs [agent/run-agent-turn
                  (fn [ag _]
                    (let [sess (:session ag)]
                      (session/append-entry sess {:role :user :content "hello"})
                      (session/append-entry sess {:role :assistant
                                                  :content [{:type :text :text "hi there"}]
                                                  :usage {:input 10 :output 5}})
                      (agent-state/restore-session-context! ag))
                    nil)]
      (let [[_ records] (run-lines [{:type :prompt :id 1 :message "hello"}
                                    {:type :get-messages :id 2}
                                    {:type :get-last-assistant-text :id 3}
                                    {:type :get-session-stats :id 4}])
            [_ messages last-text stats] (responses records)]
        (is (= [:user :assistant] (mapv :role (get-in messages [:data :messages]))))
        (is (= "hi there" (get-in last-text [:data :text])))
        (is (= 1 (:user-messages (:data stats))))
        (is (= 1 (:assistant-messages (:data stats))))
        (is (= 2 (:total-messages (:data stats))))
        (is (= 10 (get-in stats [:data :tokens :input])))
        (is (= 5 (get-in stats [:data :tokens :output])))))))

(deftest test-session-entries-tree-fork-and-clone
  (testing "entries/tree read the session; fork switches to the parent,
            clone duplicates the current position (pi)"
    (let [{:keys [id file entries]}
          (session-with [{:role :user :content "first"}
                         {:role :assistant :content [{:type :text :text "ok"}]}
                         {:role :user :content "second"}])
          [e1 _ e3] entries
          [_ records] (run-lines [{:type :get-entries :id 1}
                                  {:type :get-tree :id 2}
                                  {:type :get-entries :id 3 :since (:id e1)}
                                  {:type :get-entries :id 4 :since "nope"}
                                  {:type :fork :id 5 :entry-id (:id e3)}
                                  {:type :get-state :id 6}
                                  {:type :clone :id 7}
                                  {:type :fork :id 8 :entry-id "nope"}]
                                 {:session file})
          [all tree since missing forked state cloned fork-missing] (responses records)]
      (is (= 3 (count (get-in all [:data :entries]))))
      (is (= (:id e3) (get-in all [:data :leaf-id])))
      (is (= [(:id e1)] (mapv :id (get-in tree [:data :tree]))) "one root")
      (is (= 2 (count (get-in since [:data :entries]))) "since drops the anchor")
      (is (false? (:success missing)))
      (is (= {:cancelled false :text "second"} (:data forked)))
      (is (not= id (get-in state [:data :session :id])) "the fork is live")
      (is (= 2 (get-in state [:data :session :message-count]))
          "the fork starts at the message's parent (user + assistant)")
      (is (= {:cancelled false} (:data cloned)))
      (is (= "Entry not found: nope" (:error fork-missing))))))

(deftest test-set-session-name
  (testing "set-session-name persists the name for later get-state reads"
    (let [[_ records] (run-lines [{:type :set-session-name :id 1 :name "My Session"}
                                  {:type :get-state :id 2}
                                  {:type :set-session-name :id 3 :name "   "}
                                  {:type :set-session-name :id 4}])
          [named state blank missing] (responses records)]
      (is (true? (:success named)))
      (is (not (contains? named :data)) "pi's set_session_name has no data")
      (is (= "My Session" (get-in state [:data :session :name])))
      (is (= "Session name cannot be empty" (:error blank)))
      (is (= "Session name cannot be empty" (:error missing))))))

(deftest test-export-html
  (testing "export-html writes the HTML export and reports its path"
    (let [{:keys [file]} (session-with [{:role :user :content "hi"}
                                        {:role :assistant :content [{:type :text :text "ok"}]}])
          path (str "target/test-rpc-export-" (System/currentTimeMillis) "/out.html")
          [_ records] (run-lines [{:type :export-html :id 1 :output-path path}]
                                 {:session file})]
      (is (= path (:path (:data (first (responses records))))))
      (is (fs/exists? path))))
  (testing "nothing to export fails as a response"
    (let [[_ records] (run-lines [{:type :export-html :id 1}])]
      (is (str/starts-with? (:error (first (responses records)))
                            "Nothing to export yet")))))

(deftest test-get-commands-lists-the-rpc-surface
  (testing "get-commands reports registry commands (extension vs builtin),
            prompt templates and skills, filtered to this front end"
    (commands/register-command! (app-runtime/node) {:name "ext-demo" :description "An extension command"
                                                    :extension-handler (fn [& _] nil)})
    (commands/register-command! (app-runtime/node) {:name "tui-only" :description "Interactive only"
                                                    :frontend :interactive
                                                    :handler (fn [& _] nil)})
    (try
      (with-redefs [prompts/get-prompt-templates (fn [] [{:name "template" :description "T"}])
                    skills/get-skills (fn [] [{:name "demo" :description "S"}])]
        (let [[_ records] (run-lines [{:type :get-commands :id 1}])
              cmds (get-in (first (responses records)) [:data :commands])
              by-name (into {} (map (juxt :name identity) cmds))]
          (is (= :extension (:source (by-name "ext-demo"))))
          (is (= :builtin (:source (by-name "compact"))) "portable builtins listed")
          (is (= :prompt (:source (by-name "template"))))
          (is (= :skill (:source (by-name "skill:demo"))))
          (is (not (contains? by-name "tui-only")) "interactive-only filtered out")))
      (finally
        (commands/unregister-command! (app-runtime/node) "ext-demo")
        (commands/unregister-command! (app-runtime/node) "tui-only")))))

(defn- records-so-far
  "The records OUT holds so far (a thread-safe snapshot: write-record!
   locks OUT)."
  [out]
  (locking out
    (->> (str/split-lines (str out))
         (remove str/blank?)
         (mapv edn/read-string))))

(defn- await-record
  "Poll OUT for the first record of TYPE, up to ~2s (a piped client answers
   while the transport is still reading)."
  [out type]
  (loop [n 0]
    (when (< n 200)
      (if-let [r (first (filter #(= type (:type %)) (records-so-far out)))]
        r
        (do (Thread/sleep 10) (recur (inc n)))))))

(defn- blocking-input
  "A blocking stdin for the round-trip test: neither host ships a
   java.io.PipedReader, so this is a Reader over a queue — :write! appends a
   line, :end! signals EOF. The transport keeps reading while the test
   answers a pending dialog."
  []
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        state (atom {:text "" :pos 0 :eof false})]
    {:reader (proxy [java.io.Reader] []
               (read [cbuf off len]
                 (loop []
                   (let [{:keys [text pos eof]} @state]
                     (if (and (not eof) (< pos (count text)))
                       (let [n (min len (- (count text) pos))]
                         (dotimes [i n]
                           (aset cbuf (+ off i) (.charAt text (+ pos i))))
                         (swap! state update :pos + n)
                         n)
                       (if eof
                         -1
                         (let [item (.take q)]
                           (if (= ::eof item)
                             (do (swap! state assoc :eof true) -1)
                             (do (reset! state {:text item :pos 0 :eof false})
                                 (recur)))))))))
               (close [] (swap! state assoc :eof true)))
     :write! (fn [line] (.put q (str line "\n")) nil)
     :end! (fn [] (.put q ::eof) nil)}))

(deftest test-extension-ui-output-channels
  (testing "extension output calls become fire-and-forget :ui-request
            records; widget hiccup cannot cross the wire"
    (with-redefs [agent/run-agent-turn
                  (fn [_ _]
                    (extensions/ui-notify "hello" :warning)
                    (extensions/ui-chat-info "Label" "content")
                    (extensions/ui-set-status "key" "text")
                    (extensions/ui-set-title "kmet")
                    (extensions/ui-set-editor-text "draft")
                    (extensions/ui-paste-to-editor "pasted")
                    (extensions/ui-set-widget "w" ["line 1" "line 2"]
                                              {:placement :above-editor})
                    (extensions/ui-set-widget "hiccup" [:text {} "ignored"])
                    nil)]
      (let [[_ records] (run-lines [{:type :prompt :id 1 :message "go"}])
            uis (mapv #(dissoc % :id) (filter #(= :ui-request (:type %)) records))]
        (is (= [{:type :ui-request :method :notify :message "hello" :notify-type :warning}
                {:type :ui-request :method :chat-info :label "Label" :content "content"}
                {:type :ui-request :method :set-status :status-key "key" :status-text "text"}
                {:type :ui-request :method :set-title :title "kmet"}
                {:type :ui-request :method :set-editor-text :text "draft"}
                {:type :ui-request :method :set-editor-text :text "pasted"}
                {:type :ui-request :method :set-widget :widget-key "w"
                 :widget-lines ["line 1" "line 2"] :widget-placement :above-editor}]
               uis))))))

(defn- with-dialog
  "Run RPC mode with a stubbed turn that resolves (DIALOG engine) off the
   reader thread (the real loop runs on its own future), so the reader stays
   free to serve later lines. F receives {:runner :out :write! :end!
   :answered}; :answered holds the dialog's resolved value."
  [dialog f]
  (let [{:keys [reader write! end!]} (blocking-input)
        out (java.io.StringWriter.)
        engine-p (promise)
        answered (promise)
        orig-bootstrap runtime/bootstrap]
    (with-redefs [runtime/bootstrap (fn [config opts]
                                      (let [engine (orig-bootstrap config opts)]
                                        (deliver engine-p engine)
                                        engine))
                  agent/run-agent-turn
                  (fn [_ _]
                    (future
                      (let [engine (deref engine-p 5000 nil)]
                        (deliver answered (dialog engine))))
                    nil)]
      (f {:runner (future (rpc/run (opts {:in (io/reader reader) :out out})))
          :out out :write! write! :end! end! :answered answered}))))

(deftest test-ui-request-dialog-round-trip
  (testing "a run-driven dialog forwards as a :ui-request and the client's
            :respond resolves the pending promise (the async answer seam)"
    (with-dialog
      (fn [engine]
        (deref (runtime/request-ui! engine :confirm {:title "Allow?" :message "Sure?"})
               5000 ::timeout))
      (fn [{:keys [runner out write! end! answered]}]
        (try
          (write! "{:type :prompt :id \"p\" :message \"go\"}")
          (let [request (await-record out :ui-request)]
            (is (some? request) "the dialog reached the client")
            (is (= :confirm (:method request)))
            (is (= "Allow?" (:title request)))
            (is (= "Sure?" (:message request)))
            (write! (pr-str {:type :respond :id (:id request) :confirmed true}))
            (is (true? (deref answered 3000 ::timeout))
                "the client's answer resolved the pending request")
            ;; a second answer for the same id is ignored (already resolved)
            (write! (pr-str {:type :respond :id (:id request) :confirmed false})))
          (finally (end!)))
        (is (= :ok (deref runner 5000 ::timeout)))))))

(deftest test-dialog-timeout-auto-resolves
  (testing "a request carrying :timeout resolves with its default when the
            client never answers (pi: ExtensionUIDialogOptions.timeout)"
    (with-dialog
      (fn [engine]
        (deref (runtime/request-ui! engine :confirm {:title "Allow?" :timeout 50})
               3000 ::timeout))
      (fn [{:keys [runner out write! end! answered]}]
        (try
          (write! "{:type :prompt :id \"p\" :message \"go\"}")
          (is (some? (await-record out :ui-request)) "the dialog reached the client")
          (is (false? (deref answered 3000 ::timeout))
              "the :confirm default resolved it, not a client answer")
          (finally (end!)))
        (is (= :ok (deref runner 5000 ::timeout)))))))

(deftest test-unbridged-request-kinds-auto-answer
  (testing ":custom has no client surface (pi limitation): the degraded
            default resolves it while the engine is alive, and no record is
            written"
    (with-dialog
      (fn [engine]
        (deref (runtime/request-ui! engine :custom {:title "Component"})
               1000 ::timeout))
      (fn [{:keys [runner out write! end! answered]}]
        (try
          (write! "{:type :prompt :id \"p\" :message \"go\"}")
          (is (nil? (deref answered 2000 ::timeout))
              "the :custom default (nil) answered in-process")
          (is (not-any? #(= :ui-request (:type %)) (records-so-far out))
              "nothing reached the wire")
          (finally (end!)))
        (is (= :ok (deref runner 5000 ::timeout)))))))

(deftest test-respond-is-silent-and-validated
  (testing "a valid :respond answers nothing (pi answers no
            extension_ui_response); a shapeless one answers a failure"
    (let [[_ records] (run-lines [{:type :respond :id "unknown" :value "v"}
                                  {:type :respond :cancelled true}])]
      (is (= [{:type :response :command :respond :success false
               :error "respond requires a string :id"}]
             (responses records))))))

(deftest test-prompt-dispatches-portable-commands
  (testing "a known /command runs through the registry with the RPC command
            ctx and answers handled; unknown slash text stays a prompt"
    (let [handled (promise)
          texts (atom [])]
      (commands/register-command! (app-runtime/node)
                                  {:name "rpc-demo" :description "Demo"
                                   :handler (fn [ctx args]
                                              (deliver handled {:args args
                                                                :frontend (:frontend ctx)
                                                                :has-runtime? (some? (:runtime ctx))})
                                              ((:info (:ui ctx)) "Demo" (str "ran " args)))})
      (try
        (with-redefs [agent/run-agent-turn
                      (fn [_ opts]
                        (swap! texts conj (:message opts))
                        (when-let [done (:on-done opts)] (done "ok"))
                        nil)]
          (let [[_ records] (run-lines [{:type :prompt :id 1 :message "/rpc-demo a b"}
                                        {:type :prompt :id 2 :message "/nope"}
                                        {:type :prompt :id 3 :message "plain"}])
                [handled-resp unknown _plain] (responses records)
                uis (filterv #(= :ui-request (:type %)) records)]
            (is (= {:disposition :handled} (:data handled-resp)))
            (is (= {:args "a b" :frontend :rpc :has-runtime? true}
                   (deref handled 1000 nil)))
            (is (= {:type :ui-request :method :chat-info :label "Demo" :content "ran a b"}
                   (dissoc (first uis) :id))
                "the command's :info capability became a record")
            (is (= {:disposition :started} (:data unknown)))
            (is (= ["/nope" "plain"] @texts)
                "unknown slash and plain text reach the run as prompts")))
        (finally (commands/unregister-command! (app-runtime/node) "rpc-demo"))))))

(deftest test-portable-builtin-over-prompt
  (testing "the real engine-portable builtins dispatch over prompt (the
            6.3 payoff): /name runs and later state reads see the name"
    (let [[_ records] (run-lines [{:type :prompt :id 1 :message "/name Portable"}
                                  {:type :get-state :id 2}])
          [handled state] (responses records)]
      (is (= {:disposition :handled} (:data handled)))
      (is (= "Portable" (get-in state [:data :session :name]))))))

(deftest test-prompt-dispatches-extension-commands
  (testing "an extension command over prompt gets the extension context"
    (let [seen (promise)]
      (commands/register-command! (app-runtime/node)
                                  {:name "rpc-ext" :description "Ext"
                                   :extension-handler (fn [ctx args]
                                                        (deliver seen {:mode (:mode ctx)
                                                                       :has-ui (:has-ui ctx)
                                                                       :args args}))})
      (try
        (let [[_ records] (run-lines [{:type :prompt :id 1 :message "/rpc-ext x"}])
              resp (first (responses records))]
          (is (= {:disposition :handled} (:data resp)))
          (is (= {:mode :rpc :has-ui true :args "x"} (deref seen 1000 nil))))
        (finally (commands/unregister-command! (app-runtime/node) "rpc-ext")))))
  (testing "extension commands execute immediately, even mid-stream (pi)"
    (with-redefs [agent/run-agent-turn (fn [_ _] nil)]  ;; never settles
      (let [seen (atom [])]
        (commands/register-command! (app-runtime/node)
                                    {:name "rpc-ext-mid" :description "Ext"
                                     :extension-handler (fn [_ctx args] (swap! seen conj args))})
        (try
          (let [[_ records] (run-lines [{:type :prompt :id 1 :message "go"}
                                        {:type :prompt :id 2 :message "/rpc-ext-mid y"}])
                [started ext] (responses records)]
            (is (= {:disposition :started} (:data started)))
            (is (= {:disposition :handled} (:data ext)))
            (is (= ["y"] @seen)))
          (finally (commands/unregister-command! (app-runtime/node) "rpc-ext-mid")))))))

(deftest test-unknown-session-fails-startup
  (testing "--session matching nothing reports to stderr and returns nil"
    (let [err (java.io.StringWriter.)
          result (binding [*err* err]
                   (rpc/run (merge (opts) {:session "no-such-session"
                                           :in (java.io.StringReader. "")
                                           :out (java.io.StringWriter.)})))]
      (is (nil? result))
      (is (str/includes? (str err) "No session found matching")))))
