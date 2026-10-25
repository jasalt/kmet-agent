(ns kmet.app.loop.test-driver
  "The `:agentLoop` row: it registers the `:agents` factory, and the handle it
   publishes is a runnable agent — a state over the agent's ctx, its own
   session, the `:systemPrompt` row's builder, and a run that starts on the
   first message and settles once."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.agent.config :as cfg]
            [kmet.agent.event-bus :as event-bus]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.session.backend :as backend]
            [kmet.agent.session.core :as session]
            [kmet.agent.session.runtime :as runtime]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.app.agents :as agents]
            [kmet.app.events :as events]
            [kmet.app.extensions :as host]
            [kmet.app.loop.driver :as driver]
            [kmet.app.projections :as projections]
            [kmet.app.registries :as registries]
            [kmet.app.sessions :as sessions]
            [kmet.app.system-prompt :as system-prompt]
            [kmet.srce.core :as srce]))

(def ^:private sessions-dir
  "Where the test composition keeps its session logs: a real EDNL backend, not
   the in-process one — the loop appends to the session it is handed, so a
   log it can write is part of what the driver needs."
  "target/test-driver-sessions")

(defn- persistence-row []
  {:name :sessionPersistence
   :provide #{:sessionPersistence}
   :apply (fn [mount _]
            (srce/provide! mount :sessionPersistence (backend/ednl)
                           {:in (:parent mount)}))})

(defn- mounted
  "A booted root with the rows the driver injects — the log, the registries,
   the prompt row, the agent registry — and the driver over them. The host's
   row comes too: a run's first extension hook resolves through the binding it
   makes, which is why a composition has it."
  []
  (fs/create-dirs sessions-dir)
  (let [ctx (srce/root {:name :app})]
    (doseq [row [host/plugin (persistence-row) sessions/plugin
                 projections/plugin registries/tools registries/skills
                 system-prompt/plugin agents/plugin driver/plugin]]
      (srce/plugin! ctx row))
    {:root ctx :agents (srce/get ctx :agents)}))

(def ^:private test-config
  "A sparse run config: everything the state derives falls back to its default,
   under a scratch sessions dir. The model id is never resolved to a record
   here — the LLM call is stubbed."
  {:provider :opencode-go :model "deepseek-v4-flash" :session-dir sessions-dir})

(defn- with-stubbed-llm
  "Run F with the model call stubbed (an immediate stop, or a wait on GATE when
   one is given) and the API key supplied. F takes the call atom."
  [f & [gate]]
  (let [calls (atom [])]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (swap! calls conj opts)
                    (future
                      (when gate @gate)
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      (f calls))))

(defn- create!
  "An agent created through the registry, run over TEST-CONFIG."
  [{:keys [root agents]}]
  ((:create! agents) root {:config test-config}))

(defn- user-texts
  "AGENT's user messages, as their text: one per message."
  [agent]
  (into [] (comp (filter (comp #{:user} :role))
                 (map (comp :text first :content)))
        @(:messages agent)))

(deftest the-row-registers-the-factory
  (let [{:keys [root agents]} (mounted)]
    (is (fn? (:register-factory! agents)))
    (let [handle (create! {:root root :agents agents})]
      (is (some? handle) "create! has a factory: the driver's")
      (is (= :idle ((:status agents) handle)))
      (is (some? ((:get agents) root (:id handle))) "and the bare agent is listed"))))

(deftest an-agent-runs-a-turn-and-settles-once
  (let [{:keys [root agents]} (mounted)
        settles (atom [])
        unsubscribe (events/on! root :agent-settled (fn [ev] (swap! settles conj ev)))]
    (try
      (with-stubbed-llm
        (fn [calls]
          (let [handle (create! {:root root :agents agents})]
            ((:send! agents) handle "hello" {:target :next-turn})
            ((:when-idle agents) handle)
            (is (= 1 (count @calls)) "the run called the model once")
            (is (= 1 (count @settles)) "and settled exactly once")
            (is (= :completed (:outcome (first @settles))))
            (is (= :completed @(:last-outcome (:agent handle))))
            (is (= [:user :assistant] (mapv :role @(:messages (:agent handle))))
                "the turn's messages are the agent's context")
            (is (= ["hello"] (user-texts (:agent handle))) "the sent text, once"))))
      (finally (unsubscribe)))))

(deftest a-state-needs-a-prompt-builder
  ;; The builder has no default on purpose; a state without one fails here,
  ;; naming the option, rather than where the prompt would be built. The map is
  ;; built rather than written because lint refuses a literal nil for an
  ;; argument it knows is a function — and the guard is exactly what a caller
  ;; that got there another way hits.
  (is (= :kmet.app/no-system-prompt
         (try (driver/agent-state {} (srce/root) (hash-map :system-prompt-fn nil))
              nil
              (catch Exception e (:type (ex-data e)))))))

(deftest resume-is-refused-not-approximated
  ;; The registry's resume names a handle id it once published; a handle id is
  ;; not a session. Answering with a fresh conversation would be a wrong answer
  ;; rather than an unimplemented one.
  (let [{:keys [root agents]} (mounted)]
    (is (= :kmet.app/agent-create-failed
           (try ((:create! agents) root {:config test-config :resume "abc12345"})
                nil
                (catch Exception e (:type (ex-data e)))))
        "create! fails")
    (is (= :kmet.app/unsupported-op
           (try ((:create! agents) root {:config test-config :resume "abc12345"})
                nil
                (catch Exception e (:type (ex-data (ex-cause e))))))
        "and says what it cannot do")))

(deftest two-agents-run-their-own-turns
  ;; The landing's gate: two agents created through `create!` each run a turn
  ;; and settle once — one registry, two ctxs, two states.
  (let [{:keys [root agents]} (mounted)
        settles (atom [])
        unsubscribe (events/on! root :agent-settled (fn [ev] (swap! settles conj ev)))]
    (try
      (with-stubbed-llm
        (fn [_calls]
          (let [a (create! {:root root :agents agents})
                b (create! {:root root :agents agents})]
            (is (not= (:id a) (:id b)))
            (is (not (identical? (:ctx a) (:ctx b))) "one child ctx per agent")
            ((:send! agents) a "for a" {:target :next-turn})
            ((:send! agents) b "for b" {:target :next-turn})
            ((:when-idle agents) a)
            ((:when-idle agents) b)
            (is (= 2 (count @settles)) "each run settled once")
            (is (= ["for a"] (user-texts (:agent a)))
                "agent a's context holds its own message")
            (is (= ["for b"] (user-texts (:agent b)))
                "and agent b's its own"))))
      (finally (unsubscribe)))))

(deftest a-message-while-running-queues
  (let [{:keys [root agents]} (mounted)
        gate (promise)]
    (with-stubbed-llm
      (fn [_calls]
        (let [handle (create! {:root root :agents agents})]
          ((:send! agents) handle "first" {:target :next-turn})
          (is (= :running ((:status agents) handle)))
          ((:send! agents) handle "second" {:target :next-turn})
          (is (empty? (get-in ((:inbox agents) root handle) [:next-turn]))
              "a handed-off message is delivered, not left pending")
          (is (= ["second"] @(:follow-up (:agent handle)))
              "and waits in the loop's next-turn queue, which the run drains")
          (deliver gate :go)
          ((:when-idle agents) handle)
          (is (= :idle ((:status agents) handle))
              "and the run has settled")))
      gate)))

(deftest disposal-cancels-the-run
  (let [{:keys [root agents]} (mounted)
        gate (promise)]
    (with-stubbed-llm
      (fn [_calls]
        (let [handle (create! {:root root :agents agents})]
          ((:send! agents) handle "first" {:target :next-turn})
          (is (= :running ((:status agents) handle)))
          ((:dispose! agents) handle)
          (deliver gate :go)
          (is (= :kmet.app/unknown-agent
                 (try ((:dispose! agents) handle) nil
                      (catch Exception e (:type (ex-data e)))))
              "the capability is spent"))))))

(deftest the-handle-is-the-value-a-front-end-reads
  ;; The handle carries `session.runtime`'s shape, so a panel reads the same
  ;; accessors it read on an engine and a settings op writes the agent's own
  ;; atoms.
  (let [{:keys [root agents]} (mounted)
        handle (create! {:root root :agents agents})]
    (is (= "deepseek-v4-flash" (runtime/model handle)))
    (is (= :opencode-go (runtime/provider handle)))
    (is (= :off (runtime/thinking-level handle)))
    (is (some? (runtime/session handle)) "a created agent has its session")
    (is (= (str (fs/cwd)) (runtime/cwd handle)))
    (is (false? (runtime/turn-running? handle)))
    (is (false? (runtime/compacting? handle)))
    (is (= {:steering [] :follow-up []} (runtime/queues handle)))
    (is (= test-config (runtime/config handle)))
    (is (= (:agent handle) ((:get agents) root (:id handle)))
        "the contract's bare agent is the state the handle runs")))

(deftest a-setting-writes-one-agents-state
  ;; Every op lands on the handle it was called with: two agents of one process
  ;; hold two models, two thinking levels, two queue modes.
  (let [{:keys [root agents]} (mounted)
        one (create! {:root root :agents agents})
        two (create! {:root root :agents agents})
        pro (assoc (models/get-model :opencode-go "deepseek-v4-flash")
                   :id "deepseek-v4-pro")]
    ((:set-model! agents) one pro)
    ((:set-thinking-level! agents) one :high)
    ((:set-steering-mode! agents) one :one-at-a-time)
    ((:set-auto-compaction! agents) one false)
    (is (= "deepseek-v4-pro" (runtime/model one)))
    (is (= :high (runtime/thinking-level one)))
    (is (= :one-at-a-time (:steering-mode (runtime/run-options one))))
    (is (false? (:auto-compact (runtime/run-options one))))
    (is (= "deepseek-v4-flash" (runtime/model two)) "the sibling is untouched")
    (is (= :off (runtime/thinking-level two)))
    (is (= :all (:steering-mode (runtime/run-options two))))
    (is (true? (:auto-compact (runtime/run-options two))))))

(deftest images-reach-the-provider-and-never-a-queue
  (let [{:keys [root agents]} (mounted)
        image {:type :image :data "aGVsbG8=" :mime-type "image/png"}]
    (with-stubbed-llm
      (fn [calls]
        (let [handle (create! {:root root :agents agents})]
          ((:send! agents) handle {:text "look" :images [image]} {:target :next-turn})
          ((:when-idle agents) handle)
          (is (= 1 (count @calls)))
          (is (some #(= :image (:type %))
                    (->> (:messages (first @calls))
                         (filter (comp #{:user} :role))
                         first :content))
              "the image block is in the request the provider received")
          (is (every? empty? (vals ((:inbox agents) root handle)))
              "a prompt is in the session log, not the queue")))))
  (let [{:keys [root agents]} (mounted)
        image {:type :image :data "aGVsbG8=" :mime-type "image/png"}
        gate (promise)]
    (with-stubbed-llm
      (fn [_calls]
        (let [handle (create! {:root root :agents agents})]
          ((:send! agents) handle "first" {:target :next-turn})
          (try
            ((:send! agents) handle {:text "mid-run" :images [image]}
                             {:target :next-turn})
            (is false "a queued image is refused, not dropped")
            (catch Exception e
              (is (= :kmet.app/unsupported-images (:type (ex-data e))))
              (is (= :next-turn (:target (ex-data e)))))
            (finally (deliver gate :done)))))
      gate)))

(deftest an-agent-listener-hears-only-its-agent
  ;; 5f's note: a mode subscribed on the app scope, so a second agent's settle
  ;; woke the first mode's promise. The fix is the scope: an agent's ctx hears
  ;; only that agent (and its subagents), which is what print and rpc subscribe
  ;; on now.
  (let [{:keys [root agents]} (mounted)
        one (create! {:root root :agents agents})
        two (create! {:root root :agents agents})
        heard (atom [])
        unsubscribe [(events/on! (:ctx one) :agent-settled
                                 (fn [ev] (swap! heard conj [:one (:outcome ev)])))
                     (events/on! (:ctx two) :agent-settled
                                 (fn [ev] (swap! heard conj [:two (:outcome ev)])))]]
    (try
      (with-stubbed-llm
        (fn [_calls]
          ((:send! agents) one "one" {:target :next-turn})
          ((:when-idle agents) one)
          ((:send! agents) two "two" {:target :next-turn})
          ((:when-idle agents) two)
          (is (= 2 (count @heard)) "one settle each, not two per attempt")
          (is (some #(= [:one :completed] %) @heard))
          (is (some #(= [:two :completed] %) @heard))))
      (finally (doseq [f unsubscribe] (f))))))

(deftest a-compaction-request-defers-to-the-settle
  ;; The handle's manual compaction: idle starts it, a live prompt defers it to
  ;; the settle (kmet's cancel is cooperative, so compacting under the run would
  ;; race it), and a disposed handle drops what is queued rather than compacting
  ;; a session nobody owns.
  (let [{:keys [root agents]} (mounted)
        gate (promise)]
    (with-stubbed-llm
      (fn [_calls]
        (let [handle (create! {:root root :agents agents})]
          ((:send! agents) handle "first" {:target :next-turn})
          (is (= :scheduled ((:request-compaction! agents) handle nil nil))
              "a request while the prompt runs waits for the settle")
          (is (= 1 (count (runtime/pending-compaction handle)))
              "and is queued on this agent's value")
          ((:dispose! agents) handle)
          (is (true? @(:disposed? handle)))
          (deliver gate :done))))))

(deftest a-runs-extension-surface-is-the-agent-it-runs-inside
  ;; Two agents, one process: each run's handler reads its own session and
  ;; model — not the process slot kmet1's session runtime installs, and not the
  ;; other agent's. Before the run's binding existed, `:model` was nil and the
  ;; session facades answered nil in a kmet2 run.
  (let [{:keys [root agents]} (mounted)
        seen (atom [])
        off (event-bus/on-event
             :agent-start nil
             (fn [_]
               (swap! seen conj
                      {:session (:id (extensions/get-session))
                       :model (:model (extensions/build-extension-context))})))]
    (try
      (with-stubbed-llm
        (fn [_]
          (let [a (create! {:root root :agents agents})
                b (create! {:root root :agents agents})]
            (doseq [h [a b]]
              ((:send! agents) h "hi" {:target :next-turn})
              ((:when-idle agents) h))
            (is (= [(:id (runtime/session a)) (:id (runtime/session b))]
                   (mapv :session @seen))
                "each run's handler saw its own agent's session")
            (is (= [(runtime/model-record a) (runtime/model-record b)]
                   (mapv :model @seen))
                "and its own agent's model"))))
      (finally (off)))))

(deftest an-extension-send-user-message-acts-on-the-running-agent
  ;; The runtime capability resolves through the run's binding: a handler that
  ;; steers steers this agent, and the message reaches this agent's context.
  ;; The handler is scoped to the handle's ctx, so another run in the same
  ;; process cannot answer for this one.
  (let [{:keys [root agents]} (mounted)
        gate (promise)
        handled (promise)
        once (atom false)]
    (try
      (with-stubbed-llm
        (fn [_]
          (let [h (create! {:root root :agents agents})
                off (event-bus/on-event
                     :agent-start nil
                     (fn [_]
                       (when (compare-and-set! once false true)
                         (extensions/send-user-message "steer me" {:deliver-as :steer})
                         (deliver handled true)))
                     (:ctx h))]
            (try
              ((:send! agents) h "hi" {:target :next-turn})
              (is (true? (deref handled 2000 false)) "the handler ran")
              (deliver gate :go)
              ((:when-idle agents) h)
              (is (some #(str/includes? (pr-str %) "steer me")
                        @(:messages (:agent h)))
                  "the steered text reached the agent's context")
              (is (= :idle ((:status agents) h)))
              (finally (off)))))
        gate)
      (finally
        (deliver gate :go)))))

(deftest inject!-follows-the-custom-message-branches
  ;; pi's sendCustomMessage, as the registry's `:inject!`: idle appends, a
  ;; next-turn message queues without a run, and a trigger starts one.
  (let [{:keys [root agents]} (mounted)
        h (create! {:root root :agents agents})
        custom (fn [] (map :content (filter #(= :custom-message (:role %))
                                            (runtime/entries h))))]
    ((:inject! agents) h {:custom-type :note :content "idle"})
    (is (= ["idle"] (custom)) "an idle message is persisted, not run")
    (is (= :idle ((:status agents) h)))
    ((:inject! agents) h {:custom-type :note :content "next"} {:deliver-as :next-turn})
    (is (= ["idle"] (custom)) "next-turn does not persist yet")
    (is (= ["next"] (mapv :content @(:pending-next-turn (:agent h)))))
    (with-stubbed-llm
      (fn [_]
        ((:inject! agents) h {:custom-type :note :content "run"} {:trigger-turn true})
        ((:when-idle agents) h)
        (is (= ["idle" "run" "next"] (custom))
            "the trigger appends first, and the run flushes the next-turn one")
        (is (= :completed @(:last-outcome (:agent h))))))))

(deftest the-handle-answers-every-registry-op
  ;; `handle-ops` is the registry's list; the loop handle is the one
  ;; implementation behind it, so a name added there without a body fails here
  ;; instead of at a caller's first use.
  (let [{:keys [root agents]} (mounted)
        handle (create! {:root root :agents agents})]
    (doseq [op agents/handle-ops]
      (is (fn? (get handle op)) (str "the loop handle answers " op)))))

(deftest a-handle-moves-its-own-session
  (let [{:keys [root agents]} (mounted)]
    (with-stubbed-llm
      (fn [_]
        (let [a (create! {:root root :agents agents})
              b (create! {:root root :agents agents})]
          (try
            ((:send! agents) a "hi" {:target :next-turn})
            ((:when-idle agents) a)
            (let [a-session (runtime/session a)
                  b-session (runtime/session b)
                  result ((:new-session! agents) a)
                  started (:session result)]
              (is (fs/exists? (:file a-session)) "the run wrote its log")
              (is (some? started))
              (is (not= (:id a-session) (:id started)) "new-session! starts a new log")
              (is (= (:id started) (:id (runtime/session a)))
                  "and the handle's live session moved")
              (is (identical? b-session (runtime/session b))
                  "a sibling handle keeps its own session")
              (is (not (identical? (runtime/session a) (extensions/get-session)))
                  "no process-wide extension install: a handle is not a front end")
              ;; resume! loads the session the handle started with
              (let [resumed ((:resume-session! agents) a (:file a-session))]
                (is (= (:id a-session) (:id (:session resumed))))
                (is (= (:id a-session) (:id (runtime/session a)))))
              ;; switch-session! takes a record: point a at b's log, b unaffected
              ((:switch-session! agents) a b-session)
              (is (identical? b-session (runtime/session a)))
              (is (identical? b-session (runtime/session b))))
            (finally
              ((:dispose! agents) a)
              ((:dispose! agents) b))))))))

(deftest a-handle-forks-and-branches
  (let [{:keys [root agents]} (mounted)]
    (with-stubbed-llm
      (fn [_]
        (let [handle (create! {:root root :agents agents})]
          (try
            ((:send! agents) handle "hi" {:target :next-turn})
            ((:when-idle agents) handle)
            (let [entry (first (filter #(= :user (:role %)) (runtime/entries handle)))
                  result ((:fork! agents) handle (:id entry))
                  fork (:session result)]
              (is (= :user (:role (:entry result))))
              (is (some? fork) "the fork exists")
              (is (= (:file fork) (:file (runtime/session handle)))
                  "the handle switched to the fork")
              ;; branching the fork back to the root rebuilds an empty context
              (is (nil? ((:branch-session! agents) handle {:target-leaf nil})))
              (is (= [] (runtime/messages handle))))
            (finally
              ((:dispose! agents) handle))))))))

(deftest a-handle-names-and-exports-its-session
  (let [{:keys [root agents]} (mounted)
        path (str (fs/path sessions-dir (str "export-" (random-uuid) ".html")))]
    (with-stubbed-llm
      (fn [_]
        (let [handle (create! {:root root :agents agents})]
          (try
            ((:send! agents) handle "hi" {:target :next-turn})
            ((:when-idle agents) handle)
            (is (= "a name" ((:set-session-name! agents) handle "a name")))
            (is (= "a name" (session/get-session-name (runtime/session handle))))
            (is (= path ((:export-html! agents) handle {:path path})))
            (is (fs/exists? path))
            (is (pos? (fs/size path)))
            (finally
              ((:dispose! agents) handle))))))))
