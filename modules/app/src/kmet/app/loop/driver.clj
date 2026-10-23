(ns kmet.app.loop.driver
  "The loop's driver: the agent state a run starts from, and the `:agentLoop`
   row that registers the `:agents` factory.

   **The state is derived once, here.** `agent-state` is the whole option
   derivation a run needs — provider and model resolution, the tool table, the
   compaction and retry settings, the prompt — and it is the one place it
   happens. kmet1's engine calls it with the ctx its front end named; kmet2's
   driver calls it with the child ctx the registry minted. A second copy would
   be a run option wired in one place and forgotten in the other, which is the
   drift the app layer exists to prevent.

   What the state does *not* take from here is its event sink: `:on-event` is
   the caller's, because the front end owns what it renders and the loop only
   reports."
  (:require [babashka.fs :as fs]
            [kmet.agent.config :as cfg]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.loop :as loop]
            [kmet.agent.loop.state :as loop-state]
            [kmet.agent.project-context :as context]
            [kmet.agent.session.core :as session]
            [kmet.agent.tools.registry :as tools]
            [kmet.ai.models :as models]
            [kmet.srce.core :as srce]))

;; ── extension hook chains ─────────────────────────────────────────────────

(defn- extension-before-tool-call
  "Chain extension tool-call hooks (pi: beforeToolCall) — the runtime wires
   them for every run, so write-reject / edit-warn hooks fire headless too.
   Every hook runs; the value is nil | {:args ...} | the first blocking hook's
   result verbatim, so its :terminate reaches the call's block result (a later
   block or rewrite is ignored once blocked)."
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
          (recur (next hooks) (or blocked r) args)
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

;; ── the state ─────────────────────────────────────────────────────────────

(defn- prompt-options
  "The options the run's prompt is built from: what the config says, the run's
   cwd, the project context files, and the tool table the state will resolve
   through its ctx."
  [config ctx {:keys [cwd default-tools]}]
  {:custom-prompt (cfg/get-custom-prompt config)
   :append-prompt (cfg/get-append-system-prompt config)
   :cwd cwd
   :context-files (context/load-project-context-files
                   (cfg/get-agent-dir) (str (fs/cwd)))
   :tools (loop-state/active-tools-for
           (loop-state/ctx-tool-table ctx)
           {:default-tools default-tools})})

(defn agent-state
  "The loop's AgentState for CTX from CONFIG and the run's resolved pieces:

   :session         the session the run is over (its cwd line and its log)
   :system-prompt-fn the builder the state calls when the prompt must be
                    rebuilt (an active-tools change) — the caller's, never a
                    process default
   :on-event        the run's event sink, or nil (the loop reports; the front
                    end decides what to do with it)
   :model/:provider overrides; the config's when absent
   :cwd             the runtime cwd override; the session's, else the process's
   :agent-opts      extra/overriding make-agent-state options (the escape
                    hatch, applied last but for the ctx)

   Returns {:state <AgentState> :cwd <runtime cwd>} — the cwd is the engine's
   to keep for the next prompt rebuild, and the state carries the rest."
  [config ctx {:keys [session model provider cwd system-prompt-fn on-event
                      agent-opts]}]
  ;; no default is the rule, so a missing builder is a named failure here
  ;; rather than an NPE where the prompt is built
  (when-not (fn? system-prompt-fn)
    (throw (ex-info "a run's state needs a prompt builder: name one (the ctx's :systemPrompt row, or a caller's own)"
                    {:type :kmet.app/no-system-prompt})))
  (let [provider (or provider (cfg/get-provider config))
        model (or model (models/resolve-config-model config))
        model-rec (models/get-model provider model)
        default-tools (tools/resolve-default-tools (cfg/get-default-tools config))
        ;; the prompt's cwd line is the runtime cwd (the resumed session's,
        ;; or a subagent's), but everything project-scoped — context files
        ;; included — stays with the launch dir
        runtime-cwd (or cwd (session/session-cwd session) (str (fs/cwd)))
        prompt-opts (prompt-options config ctx {:cwd runtime-cwd
                                                :default-tools default-tools})
        system-prompt (apply system-prompt-fn (mapcat identity prompt-opts))
        thinking (let [raw (:thinking config :off)]
                   (if (:reasoning model-rec)
                     (models/clamp-thinking-level model-rec raw)
                     raw))
        retry-settings (cfg/get-retry-settings config)
        loop-guard-settings (cfg/get-loop-guard-settings config)
        ;; select-keys omits an absent setting but keeps an explicit nil, so
        ;; make-agent-state's own defaults survive an absent one
        timeout-opts (select-keys config [:http-idle-timeout-ms :http-total-timeout-ms])
        state (apply loop/make-agent-state
                     (mapcat identity
                             (merge
                              {:model model
                               :provider provider
                               :system system-prompt
                               :system-prompt-opts prompt-opts
                               :system-prompt-fn system-prompt-fn
                               :session session
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
                               ;; pi: retry settings (settings.edn :retry block —
                               ;; enabled gates max-retries to 0)
                               :max-retries (if (:enabled retry-settings)
                                              (:max-retries retry-settings)
                                              0)
                               :base-delay-ms (:base-delay-ms retry-settings)
                               :max-agent-delay-ms (:max-agent-delay-ms retry-settings)
                               ;; Repeat-loop guard (kmet-specific): settings.edn
                               ;; :loop-guard block — enabled gates threshold to 0
                               :loop-guard-enabled (:enabled loop-guard-settings)
                               :loop-guard-threshold (:threshold loop-guard-settings)
                               :thinking-loop-guard-enabled
                               (get config :thinking-loop-guard-enabled true)
                               :before-tool-call extension-before-tool-call
                               :after-tool-call extension-after-tool-call
                               :on-event on-event}
                              timeout-opts
                              agent-opts
                              ;; the state reads the ctx resolved above, so its
                              ;; tool table and the prompt's are the same table
                              {:ctx ctx})))]
    {:state state :cwd runtime-cwd}))

;; ── the handle ────────────────────────────────────────────────────────────

(defn- session-for
  "A fresh session for a new agent over LOG (the `:sessions` service), placed
   the way kmet1's engine places one: the cwd-encoded directory under the
   config's sessions dir."
  [log config cwd]
  (let [dir (session/session-dir-for-cwd (cfg/get-session-dir config) cwd)]
    (fs/create-dirs dir)
    ((:create! log) {:dir dir :cwd cwd})))

(defn- agent-handle
  "The handle the registry publishes for one agent: its state, the run it
   owns, and the control surface `kmet.app.agents/handle-contract` requires.

   A run starts on the first message and settles on its own thread — the
   registry's `send!` is the wake-up, never a block. A message that arrives
   while a run is live goes into the loop's queue (`:next-step` mid-turn,
   `:next-turn` at the next boundary), which the loop drains itself."
  [child {:keys [id session config prompt-fn projections]}]
  (let [{:keys [state]} (agent-state config child {:session session
                                                   :system-prompt-fn prompt-fn})
        live (atom state)
        running? (atom false)
        last-run (atom nil)
        settle! (fn [] (reset! running? false))
        stop! (fn []
                (loop/cancel-turn @live)
                (settle!))
        ;; true when this call claimed the run: the CAS is what decides, so a
        ;; message arriving as another thread starts a run is queued by the
        ;; caller rather than dropped
        ;; a start that throws before it has a run to settle must not leave
        ;; the handle claiming one: every later message would queue behind a
        ;; run that does not exist
        start! (fn [text]
                 (boolean
                  (when (compare-and-set! running? false true)
                    (try
                      (reset! last-run
                              (loop/run-agent-turn
                               @live
                               {:message text
                                :on-done (fn [_] (settle!))
                                :on-error (fn [_] (settle!))}))
                      (catch Throwable t
                        (settle!)
                        (throw t))))))]
    {:id id
     :ctx child
     :agent state
     :session (fn [] (:session @live))
     :send! (fn [msg opts]
              (let [text (if (map? msg) (:text msg) msg)
                    target (:target opts :next-turn)]
                (when-not (and (not @running?) (start! text))
                  (if (= :next-step target)
                    (loop-state/steer! @live text)
                    (loop-state/follow-up! @live text)))
                ;; the run has the message now: the inbox's pending entry
                ;; folds away with the delivery fact, so a long-lived server's
                ;; inbox stays a queue rather than a log of everything sent
                (when-let [entry (:entry opts)]
                  ((:deliver! projections) (:session @live) [(:id entry)]))))
     :cancel! (fn [_opts] (stop!))
     :when-idle (fn [] (when @running? (deref @last-run)))
     :status (fn [] (if @running? :running :idle))
     :dispose! stop!}))

;; ── the row ───────────────────────────────────────────────────────────────

(defn- serve
  "The `:agentLoop` row: register the `:agents` factory, so `create!` has one
   and the handle it publishes is a runnable agent. The disposer is the
   registration's, so the factory goes with the fiber.

   The run config is loaded per creation (a settings change reaches the next
   agent) unless the caller names one (`:config` in the create options).

   A create option that is kmet1's CLI override (`:model`, `:provider`) is
   applied the way `kmet --model` applies it — an agent's model is an option of
   the agent, not of the process, so two agents of one server can differ.

   `:resume` is refused rather than approximated: the registry's resume passes
   the *handle* id it once published, and a handle id is not a session — an
   agent brought back as a fresh session would answer with a different
   conversation and say nothing. It waits for an agent record that outlives
   the handle."
  [ctx _config]
  (let [scope (:parent ctx)
        agents (srce/get ctx :agents)
        log (srce/get ctx :sessions)
        projections (srce/get ctx :projections)
        prompt-fn (srce/get ctx :system-prompt)
        cwd (str (fs/cwd))]
    ((:register-factory! agents) scope
                                 (fn [child opts]
                                   (when-let [id (:resume opts)]
                                     (throw (ex-info
                                             (str "the loop driver cannot resume agent "
                                                  (pr-str id) ": a handle id is not a session, "
                                                  "and a fresh one would answer with a different "
                                                  "conversation")
                                             {:type :kmet.app/unsupported-op
                                              :op :resume
                                              :id id})))
                                   (let [config (-> (or (:config opts) (cfg/load-config))
                                                    (cfg/apply-cli-overrides opts))]
                                     (agent-handle child {:id (:id opts)
                                                          :config config
                                                          :session (or (:session opts) (session-for log config cwd))
                                                          :prompt-fn prompt-fn
                                                          :projections projections}))))))

(srce/defplugin plugin
  {:name :agent-loop
   :inject [:agents :sessions :projections :system-prompt]
   :apply serve})
