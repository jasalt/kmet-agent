(ns kmet.app.loop.test-driver
  "The `:agentLoop` row: it registers the `:agents` factory, and the handle it
   publishes is a runnable agent — a state over the agent's ctx, its own
   session, the `:systemPrompt` row's builder, and a run that starts on the
   first message and settles once."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [kmet.agent.config :as cfg]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.session.backend :as backend]
            [kmet.agent.event-bus :as event-bus]
            [kmet.ai.llm :as llm]
            [kmet.app.agents :as agents]
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

(defn- bind-host!
  "Hand the extension host its services for this root — the handle its own
   tags live in and the ctx an `:inject` mounts under. The composition does
   not have this row yet (the host is still kmet1's process binding), so the
   test stands it up the way that row will."
  [root]
  (extensions/bind-services! (fn [] {:registry (registries/service)
                                     :ctx root})))

(defn- mounted
  "A booted root with the rows the driver injects — the log, the registries,
   the prompt row, the agent registry — and the driver over them."
  []
  (fs/create-dirs sessions-dir)
  (let [ctx (srce/root {:name :app})]
    (bind-host! ctx)
    (doseq [row [(persistence-row) sessions/plugin projections/plugin
                 registries/tools registries/skills system-prompt/plugin
                 agents/plugin driver/plugin]]
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
        unsubscribe (event-bus/on-event :agent-settled (fn [ev] (swap! settles conj ev)))]
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
                "the turn's messages are the agent's context"))))
      (finally (unsubscribe)))))

(deftest two-agents-run-their-own-turns
  ;; The landing's gate: two agents created through `create!` each run a turn
  ;; and settle once — one registry, two ctxs, two states.
  (let [{:keys [root agents]} (mounted)
        settles (atom [])
        unsubscribe (event-bus/on-event :agent-settled (fn [ev] (swap! settles conj ev)))]
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
