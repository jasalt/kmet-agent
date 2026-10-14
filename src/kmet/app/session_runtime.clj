(ns kmet.app.session-runtime
  "The session runtime: the engine a front end drives.

   One constructor (`bootstrap`) builds the agent state, the session, the
   config-derived run options, the system prompt, and the extension runtime
   handle, and returns an `Engine`. Front ends then drive it through
   `prompt!` / `steer!` / `follow-up!` / `cancel!` / `compact!` and observe
   it through `subscribe!`. The engine owns no view state: rendering,
   dialogs and input belong to the front end (kmet.modes.*), which
   subscribes to the event stream instead of reaching into the agent.

   Design rules (engine.md §4.1):

   - The event stream is the truth. `subscribe!` takes a fn of one plain
     EDN event; callbacks run on the emitting thread and must not block.
     A throwing subscriber is logged and isolated — it cannot kill the
     loop or starve the other subscribers.
   - `:on-text`/`:on-thinking`/`:on-done`/`:on-error` stay sugar: `prompt!`
     passes them straight to `run-agent-turn`, derived from the same
     emission path, so they cannot drift from the stream.
   - Extension wiring is runtime-scoped: `bootstrap` installs the session
     handle, the context sink and a default UI registry, and `dispose!`
     clears them. A front end with its own UI port (interactive) installs
     it after its widgets exist — `extensions/set-ui-registry!` is the
     front-end-owned install point.
   - `dispose!` cancels an in-flight run and waits (bounded) for it to
     settle before clearing the subscribers, so the terminal
     `:agent-settled` event reaches them; the settle path runs in the
     loop's `finally`."
  (:require [babashka.fs :as fs]
            [kmet.config :as cfg]
            [kmet.debug :as debug]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.models :as models]
            [kmet.app.context :as context]
            [kmet.app.extensions :as extensions]
            [kmet.app.loop :as agent]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.packages :as packages]
            [kmet.app.session :as session]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]))

(defrecord Engine [agent-state session config subscribers last-run disposed?])

(defn- ag
  "The live AgentState. The engine holds it in an atom because a session
   switch replaces the record (session_admin assoc's :session) — the
   layout's CoreState :agent-state is this same atom."
  [engine]
  @(:agent-state engine))

(defn agent-state
  "The live AgentState record."
  [engine]
  (ag engine))

(defn- extension-before-tool-call
  "Chain extension tool-call hooks (pi: beforeToolCall) — the runtime wires
   them for every front end, so write-reject / edit-warn hooks fire in
   headless runs too. Returns nil | {:block true :reason} | {:args ...}."
  [ctx]
  (loop [hooks (extensions/get-tool-call-hooks)
         blocked nil
         args (:args ctx)]
    (if-let [hook (first hooks)]
      (let [r (try (hook (assoc ctx :args args))
                   (catch Exception e
                     {:block true
                      :reason (str "tool-call hook error: " (ex-message e))}))]
        (cond
          (:block r)
          (recur (next hooks) (or blocked {:block true :reason (:reason r)}) args)
          (contains? r :args)
          (recur (next hooks) blocked (:args r))
          :else
          (recur (next hooks) blocked args)))
      (or blocked (when (not= args (:args ctx)) {:args args})))))

(defn- extension-after-tool-call
  "Chain extension tool-result hooks (pi: afterToolCall). Returns the
   (possibly rewritten) result."
  [ctx]
  (reduce (fn [result hook]
            (if-let [r (try (hook (assoc ctx :result result
                                         :is-error (:is-error result false)))
                            (catch Exception e
                              {:content (str "tool-result hook error: " (ex-message e))
                               :is-error true}))]
              (cond-> result
                (:content r) (assoc :content (:content r))
                (contains? r :is-error) (assoc :is-error (:is-error r)))
              result))
          (:result ctx)
          (extensions/get-tool-result-hooks)))

(defn- default-ui-registry
  "The UI registry a front end without its own UI port gets: the
   `build-context` callback every registry must provide (the extension ctx
   :model/:scoped-models/:thinking-level read the live agent). Print mode
   used exactly this block; interactive replaces the registry from
   `kmet.modes.interactive.ui-registry` once its widgets exist."
  [engine]
  {:build-context
   (fn []
     (let [agent-state (ag engine)]
       {:model (models/get-model @(:provider agent-state) @(:model agent-state))
        :scoped-models (resolver/scoped-models-for-context
                        @(:scoped-models agent-state) @(:provider agent-state))
        :thinking-level @(:thinking agent-state)}))})

(defn bootstrap
  "Build the runtime engine for CONFIG.

   opts:
     :session      — the resolved Session record (mode policy resolves it;
                     nil is allowed and means a context-less run)
     :model        — model id override (CLI); defaults to the config model
     :provider     — provider override; defaults to the config provider
     :cwd          — runtime cwd override for the prompt; defaults to the
                     session's recorded cwd, else the process cwd
     :ui-registry  — front-end UI registry; defaults to build-context only
     :agent-opts   — extra/overriding make-agent-state options (escape hatch
                     for tests and front-end-specific run options; the
                     engine's session follows an overridden :session)

   Loads skills + prompt templates, builds the system prompt (custom prompt,
   appended prompt, project context files, active tools), derives every
   run option from CONFIG with the same defaults the TUI and print mode
   used to wire by hand, seeds the scoped-model list, and installs the
   extension runtime handle (session, context sink, UI registry)."
  [config opts]
  (let [{:keys [model provider cwd ui-registry agent-opts]} opts
        ;; the resolved session (the layout/print-mode policy output); any
        ;; :agent-opts override is folded back in below via the agent state
        sess (:session opts)
        _ (packages/load-skills!)
        _ (packages/load-prompts!)
        provider (or provider (cfg/get-provider config))
        model (or model (models/resolve-config-model config))
        model-rec (models/get-model provider model)
        default-tools (tools/resolve-default-tools (cfg/get-default-tools config))
        ;; the prompt's cwd line is the runtime cwd (the resumed session's),
        ;; but everything project-scoped — context files included — stays
        ;; with the launch dir
        runtime-cwd (or cwd (session/session-cwd sess) (str (fs/cwd)))
        system-prompt-opts {:custom-prompt (cfg/get-custom-prompt config)
                            :append-prompt (cfg/get-append-system-prompt config)
                            :cwd runtime-cwd
                            :context-files (context/load-project-context-files
                                            (cfg/get-agent-dir) (str (fs/cwd)))
                            :tools (agent/active-tools-for
                                    {:default-tools default-tools})}
        system-prompt (apply skills/build-system-prompt
                             (mapcat identity system-prompt-opts))
        thinking (let [raw (:thinking config :off)]
                   (if (:reasoning model-rec)
                     (shared/clamp-thinking-level model-rec raw)
                     raw))
        subscribers (atom [])
        ;; absent settings must keep make-agent-state's own defaults (an
        ;; explicit nil stays meaningful), so these keys are merged only
        ;; when the config carries them
        timeout-opts (cond-> {}
                       (contains? config :http-idle-timeout-ms)
                       (assoc :http-idle-timeout-ms (:http-idle-timeout-ms config))
                       (contains? config :http-total-timeout-ms)
                       (assoc :http-total-timeout-ms (:http-total-timeout-ms config)))
        agent-state (apply agent/make-agent-state
                           (mapcat identity
                                   (merge
                                    {:model model
                                     :provider provider
                                     :system system-prompt
                                     :system-prompt-opts system-prompt-opts
                                     :session sess
                                     :default-tools default-tools
                                     :context-window (or (:context-window model-rec)
                                                         (:context-window config))
                                     :compact-reserve-tokens (or (:compact-reserve-tokens config)
                                                                 16384)
                                     :compact-model-overrides (:compact-model-overrides config)
                                     :branch-summary-reserve-tokens
                                     (or (:reserve-tokens (cfg/get-branch-summary-settings config))
                                         16384)
                                     :compact-token-threshold (:compact-token-threshold config)
                                     :auto-compact (get config :auto-compact true)
                                     :steering-mode (or (:steering-mode config) :all)
                                     :follow-up-mode (or (:follow-up-mode config) :one-at-a-time)
                                     :keep-recent-tokens (or (:keep-recent-tokens config) 20000)
                                     :block-images (cfg/get-block-images config)
                                     :thinking thinking
                                     ;; pi: retry settings (settings.edn :retry
                                     ;; block — enabled gates max-retries to 0)
                                     :max-retries (let [retry (cfg/get-retry-settings config)]
                                                    (if (:enabled retry) (:max-retries retry) 0))
                                     :base-delay-ms (:base-delay-ms (cfg/get-retry-settings config))
                                     :max-agent-delay-ms (:max-agent-delay-ms
                                                          (cfg/get-retry-settings config))
                                     ;; Repeat-loop guard (kmet-specific):
                                     ;; settings.edn :loop-guard block — enabled
                                     ;; gates threshold to 0 (off)
                                     :loop-guard-enabled (:enabled
                                                          (cfg/get-loop-guard-settings config))
                                     :loop-guard-threshold (:threshold
                                                            (cfg/get-loop-guard-settings config))
                                     :thinking-loop-guard-enabled
                                     (get config :thinking-loop-guard-enabled true)
                                     :before-tool-call extension-before-tool-call
                                     :after-tool-call extension-after-tool-call
                                     :on-event (fn [ev]
                                                 (doseq [f @subscribers]
                                                   (try (f ev)
                                                        (catch Throwable t
                                                          (debug/log "session subscriber failed: "
                                                                     t)))))}
                                    timeout-opts
                                    agent-opts)))
        _ (agent/init-scoped-models! agent-state config)
        engine (->Engine (atom agent-state)
                         (atom (:session agent-state))
                         config
                         subscribers
                         (atom nil)
                         (atom false))]
    ;; Runtime-scoped extension handle: the session facades and the ctx
    ;; :model/:scoped-models/:thinking-level read the live agent. The UI
    ;; registry is the default unless the front end installs its own later.
    (extensions/set-session! (:session agent-state))
    (extensions/set-context-sink! (fn [msg] (agent/add-context-message! (ag engine) msg)))
    (extensions/set-ui-registry! (or ui-registry (default-ui-registry engine)))
    engine))

(defn session
  "The live session (an atom's value; a session switch replaces it)."
  [engine]
  @(:session engine))

(defn entries
  "The live session's entries (the session's own atom)."
  [engine]
  (some->> @(:session engine) :entries deref))

(defn snapshot
  "A plain-data view of the engine: the session, the agent's status /
   model / provider / thinking, the pending queues and the last outcome.
   Fields are read from independent atoms, so the snapshot is not one
   atomic cut (engine.md §4.1) — display consumers are fine with that."
  [engine]
  (let [agent-state (ag engine)]
    {:session @(:session engine)
     :status @(:status agent-state)
     :model @(:model agent-state)
     :provider @(:provider agent-state)
     :thinking @(:thinking agent-state)
     :queues (agent/queued-messages agent-state)
     :last-outcome @(:last-outcome agent-state)}))

(defn last-outcome
  "The outcome of the last settled prompt (:completed | :error | :aborted)."
  [engine]
  @(:last-outcome (ag engine)))

(defn subscribe!
  "Register F as an event subscriber. Returns an unsubscribe fn. F runs on
   the emitting thread, must not block, and receives plain EDN events; a
   throw is logged and isolated."
  [engine f]
  (swap! (:subscribers engine) conj f)
  (fn unsubscribe []
    (swap! (:subscribers engine)
           (fn [subs] (into [] (remove #(identical? % f)) subs)))
    nil))

(defn prompt!
  "Start a prompt and return nil immediately — completion is observed as
   :agent-settled. TEXT is the initial user message (nil/absent continues on
   the existing context); :images attaches image blocks. :on-text,
   :on-thinking, :on-done and :on-error are run-agent-turn's callbacks,
   passed straight through."
  [engine opts]
  (reset! (:last-run engine)
          (agent/run-agent-turn (ag engine)
                                (select-keys (cond-> opts
                                               (contains? opts :text)
                                               (assoc :message (:text opts)))
                                             [:message :images :on-text
                                              :on-thinking :on-done :on-error])))
  nil)

(defn steer! [engine text] (agent/steer! (ag engine) text))
(defn follow-up! [engine text] (agent/follow-up! (ag engine) text))
(defn queues [engine] (agent/queued-messages (ag engine)))
(defn cancel! [engine] (agent/cancel-turn (ag engine)))

(defn compact!
  "Compact the session (manual /compact path); custom-instructions and
   reason are loop/pi-shaped (see kmet.app.loop/compact-context!)."
  ([engine] (agent/compact-context! (ag engine)))
  ([engine custom-instructions reason]
   (agent/compact-context! (ag engine) custom-instructions reason)))

(defn set-model! [engine model] (agent/set-model! (ag engine) model))
(defn cycle-model!
  "Cycle the session model forward (1-arity) or in a direction (-1/1)."
  ([engine] (cycle-model! engine 1))
  ([engine direction] (agent/cycle-model! (ag engine) direction)))
(defn set-thinking-level! [engine level] (agent/set-thinking-level! (ag engine) level))
(defn scoped-models [engine] @(:scoped-models (ag engine)))
(defn set-scoped-models! [engine models]
  (agent/set-scoped-models! (ag engine) models))

(defn dispose!
  "Release the engine: cancel an in-flight run or compaction, wait (bounded,
   2s) for a run to settle so the terminal :agent-settled reaches the
   subscribers, then clear the subscribers and the extension runtime
   handle. Idempotent."
  [engine]
  (when (compare-and-set! (:disposed? engine) false true)
    (let [agent-state (ag engine)
          run @(:last-run engine)
          run-pending? (and run (not (realized? run)))]
      (when (or run-pending? (boolean @(:compacting? agent-state)))
        (agent/cancel-turn agent-state)
        (when run-pending?
          (deref run 2000 nil))))
    (reset! (:subscribers engine) [])
    (extensions/set-session! nil)
    (extensions/set-context-sink! nil)
    (extensions/clear-ui-registry!))
  nil)
