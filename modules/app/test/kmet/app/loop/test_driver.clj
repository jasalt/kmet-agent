(ns kmet.app.loop.test-driver
  "The `:agentLoop` row: it registers the `:agents` factory, and the handle it
   publishes is a runnable agent — a state over the agent's ctx, its own
   session, the `:systemPrompt` row's builder, and a run that starts on the
   first message and settles once."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [kmet.agent.config :as cfg]
            [kmet.agent.session.backend :as backend]
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
