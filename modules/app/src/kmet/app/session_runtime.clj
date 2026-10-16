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
     handle, the context sink and the headless UI port (with the front-end
     kind), and `dispose!` unbinds them. A front end with its own UI port
     (interactive) re-binds after its widgets exist —
     `extensions/bind-extensions!` is the front-end-owned install point.
   - `dispose!` cancels an in-flight run and waits (bounded) for it to
     settle before clearing the subscribers, so the terminal
     `:agent-settled` event reaches them; the settle path runs in the
     loop's `finally`."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.config :as cfg]
            [kmet.debug :as debug]
            [kmet.libs.concurrent :as concurrent]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.models :as models]
            [kmet.app.commands :as commands]
            [kmet.app.context :as context]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.extensions :as extensions]
            [kmet.app.loop :as agent]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.packages :as packages]
            [kmet.app.prompts :as prompts]
            [kmet.app.session :as session]
            [kmet.app.session-export :as session-export]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]))

(defrecord Engine [agent-state session config subscribers last-run disposed?
                   cwd turn-running? compaction-queued pending-ui run-callbacks])

(declare cancel! turn-running? runtime-capabilities)

(defn agent-state
  "The live AgentState record. The engine holds it in an atom because a
   session switch replaces the record (the mode assoc's :session and resets
   the atom) — the layout's CoreState :agent-state is this same atom. nil
   for a bare test engine without one."
  [engine]
  (some-> engine :agent-state deref))

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

(defn- default-ui-port
  "The capability map a front end without its own port gets: the headless
   auto-answers (extensions/headless-ui-port) plus the `build-context`
   callback every map must provide (the extension ctx
   :model/:scoped-models/:thinking-level read the live agent). Interactive
   replaces the map from `kmet.modes.interactive.ui-registry` once its
   widgets exist."
  [engine]
  (assoc (extensions/headless-ui-port)
         :build-context
         (fn []
           (let [agent-state (agent-state engine)]
             {:model (models/get-model @(:provider agent-state) @(:model agent-state))
              :scoped-models (resolver/scoped-models-for-context
                              @(:scoped-models agent-state) @(:provider agent-state))
              :thinking-level @(:thinking agent-state)}))))

(defn bootstrap
  "Build the runtime engine for CONFIG.

   opts:
     :session      — the resolved Session record (mode policy resolves it;
                     nil is allowed and means a context-less run)
     :model        — model id override (CLI); defaults to the config model
     :provider     — provider override; defaults to the config provider
     :cwd          — runtime cwd override for the prompt; defaults to the
                     session's recorded cwd, else the process cwd
     :mode         — front-end kind bound to extensions (pi: mode);
                     defaults to :print
     :has-ui       — whether the front end has a UI port (ctx.has-ui);
                     defaults to false
     :ui-context   — front-end capability map; defaults to the headless
                     port + build-context (session-runtime/default-ui-port)
     :agent-opts   — extra/overriding make-agent-state options (escape hatch
                     for tests and front-end-specific run options; the
                     engine's session follows an overridden :session)

   Loads skills + prompt templates, builds the system prompt (custom prompt,
   appended prompt, project context files, active tools), derives every
   run option from CONFIG with the same defaults the TUI and print mode
   used to wire by hand, seeds the scoped-model list, and installs the
   extension runtime handle (session, context sink, UI registry)."
  [config opts]
  (let [{:keys [model provider cwd ui-context mode has-ui agent-opts]} opts
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
        retry-settings (cfg/get-retry-settings config)
        loop-guard-settings (cfg/get-loop-guard-settings config)
        subscribers (atom [])
        ;; select-keys omits an absent setting but keeps an explicit nil, so
        ;; make-agent-state's own defaults survive an absent one
        timeout-opts (select-keys config [:http-idle-timeout-ms :http-total-timeout-ms])
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
                                     :max-retries (if (:enabled retry-settings)
                                                    (:max-retries retry-settings)
                                                    0)
                                     :base-delay-ms (:base-delay-ms retry-settings)
                                     :max-agent-delay-ms (:max-agent-delay-ms retry-settings)
                                     ;; Repeat-loop guard (kmet-specific):
                                     ;; settings.edn :loop-guard block — enabled
                                     ;; gates threshold to 0 (off)
                                     :loop-guard-enabled (:enabled loop-guard-settings)
                                     :loop-guard-threshold (:threshold loop-guard-settings)
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
        engine (map->Engine {:agent-state (atom agent-state)
                             :session (atom (:session agent-state))
                             :config (atom config)
                             :subscribers subscribers
                             :last-run (atom nil)
                             :disposed? (atom false)
                             :cwd (atom runtime-cwd)
                             :turn-running? (atom false)
                             :compaction-queued (atom [])
                             :pending-ui (atom {})
                             :run-callbacks (atom nil)
                             :event-listeners (atom {})})]
    ;; Runtime-scoped extension handle: the session facades and the ctx
    ;; :model/:scoped-models/:thinking-level read the live agent. The
    ;; front-end binding is runtime-scoped too (engine.md §4.5): bootstrap
    ;; installs the headless port and the front-end kind, dispose! unbinds.
    ;; A front end with its own port re-binds after its widgets exist.
    (event-bus/set-default-event-listener-atom! (:event-listeners engine))
    (extensions/set-session! (:session agent-state))
    (extensions/set-context-sink! (fn [msg] (agent/add-context-message! (agent-state engine) msg)))
    (extensions/bind-extensions!
     {:mode mode
      :has-ui has-ui
      ;; a front end supplies only its own capabilities; the runtime's
      ;; build-context callback always rides along
      :ui (merge (default-ui-port engine) ui-context)})
    (extensions/bind-runtime-capabilities! (runtime-capabilities engine))
    engine))

(defn session
  "The live session (an atom's value; a session switch replaces it). nil
   for a context-less engine or a bare test engine."
  [engine]
  (some-> engine :session deref))

(defn ensure-cwd-session-dir
  "The cwd-encoded sessions dir for CWD under BASE-DIR, created if missing
   (pi: getDefaultSessionDir — per-project isolation). BASE-DIR is the
   config's sessions dir; CWD is the process cwd at startup, or the
   session's runtime cwd after a switch, so a session resumed from another
   project keeps creating its sessions in that project's dir."
  [base-dir cwd]
  (let [dir (session/session-dir-for-cwd base-dir cwd)]
    (fs/create-dirs dir)
    dir))

(defn find-recent-session
  "The most recent session file for CWD under BASE-DIR, or nil (pi:
   continueRecent → findMostRecentSession). Header-based discovery: legacy
   headerless sessions and other-cwd files are excluded."
  [base-dir cwd]
  (session/find-most-recent-session (ensure-cwd-session-dir base-dir cwd) cwd))

(defn resolve-run-session
  "Resolve the session a non-interactive run starts from — the policy print
   mode and RPC mode share (pi: main.ts resolveSessionPath/continueRecent,
   resolved before mode dispatch there). --session wins over --continue: it
   takes a session id, id prefix, or path; --continue loads the most recent
   session for the process cwd, falling back to a fresh one when none
   exists; otherwise a fresh session starts in the cwd-encoded sessions dir.
   Returns the session record, or nil when --session matches nothing."
  [config opts]
  (let [base-dir (cfg/get-session-dir config)
        cwd (str (fs/cwd))]
    (if-let [arg (:session opts)]
      (when-let [path (session/resolve-session-path arg base-dir)]
        (session/load-session path))
      (if-let [path (and (:continue opts)
                         (find-recent-session base-dir cwd))]
        (session/load-session path)
        (session/create-session (ensure-cwd-session-dir base-dir cwd))))))

(defn config
  "The live process config (the engine holds it in an atom because /reload
   replaces it)."
  [engine]
  (some-> engine :config deref))

(defn set-config!
  "Replace the live process config (/reload re-reads settings.edn and
   extensions); session-dir and run-option lookups follow it."
  [engine config]
  (reset! (:config engine) config)
  nil)

(defn cwd
  "The runtime working directory (pi: AgentSession._cwd — the resumed
   session's recorded cwd, else the process cwd at bootstrap). nil for a
   bare test engine without a :cwd atom."
  [engine]
  (some-> engine :cwd deref))

(defn- refresh-prompt-cwd!
  "Point the system prompt's `Current working directory` line at CWD (the
   session's runtime cwd). Project context files are NOT re-read: everything
   project-scoped — settings.edn, context files, project extensions/skills/
   prompts/themes/packages — stays with the launch directory, whatever
   session is imported; only the transcript (and the working directory its
   tools run in) crosses the switch."
  [engine cwd]
  (let [ag (agent-state engine)
        opts (assoc @(:system-prompt-opts ag) :cwd cwd)]
    (reset! (:system-prompt-opts ag) opts)
    (reset! (:system ag) (apply skills/build-system-prompt (mapcat identity opts)))))

(defn apply-session-cwd!
  "Point the runtime at SESS's working directory (pi: createRuntime's cwd +
   footerDataProvider.setCwd — a resumed or imported session brings its
   recorded cwd): the system prompt's cwd line and everything reading the
   runtime cwd (footer pwd, extension ctx.cwd, tool resolution) follow it.
   A recorded cwd that no longer exists keeps the current one (the front end
   reports it); returns the cwd in effect."
  [engine sess]
  (let [sess-cwd (session/session-cwd sess)
        current (cwd engine)
        same? (and sess-cwd current
                   (= sess-cwd (str (fs/normalize (str current)))))]
    (cond
      same? current
      sess-cwd (do (reset! (:cwd engine) sess-cwd)
                   (refresh-prompt-cwd! engine sess-cwd)
                   sess-cwd)
      :else current)))

(defn emit-session-start-async!
  "Emit :session-start away from the input path — pi: AgentSession emits
   session_start on every runtime creation (startup, resume, new, fork) and
   extension handlers may block on dialog promises. The resources_discover
   pass follows (pi: extendResourcesFromExtensions after session_start)."
  [payload]
  (future
    (try
      (event-bus/emit-event! payload)
      (extensions/discover-resources! :startup)
      (catch Exception e (debug/log "session-start: " e)))))

(defn switch-session!
  "Swap the live session to SESS (the runtime owns the swap — engine.md
   §4.4; front ends keep the UX around it): point the session atom and the
   extension session handle at it, replace the agent's session and rebuild
   its in-memory context from the new branch, apply the session-derived
   model/thinking/tool settings when the opts :apply-settings? is true, drop
   the compaction queue, and point the runtime cwd at the session's.
   Callers own the before/after lifecycle events. Returns SESS."
  [engine sess {:keys [apply-settings?]}]
  (let [new-ag (assoc (agent-state engine) :session sess)]
    (reset! (:session engine) sess)
    (extensions/set-session! sess)
    (reset! (:compaction-queued engine) [])
    (reset! (:agent-state engine) new-ag)
    (agent/restore-session-context! new-ag)
    (when apply-settings?
      ;; pi: createRuntime builds a fresh session with the config-derived
      ;; tool set — the outgoing session's set-active-tools!/tools-selector
      ;; selection must not carry over (the switch also emits :session-start,
      ;; letting extensions restore their own per-session state).
      (agent/set-active-tools! new-ag nil)
      (agent/apply-session-settings! new-ag))
    (apply-session-cwd! engine sess)
    sess))

(defn- extension-cancelled?
  "Emit a before-event (session-before-switch / session-before-fork) and
   report whether an extension cancelled the pending operation (pi:
   emitBeforeSwitch/emitBeforeFork → cancel)."
  [event]
  (boolean (:cancel (event-bus/emit-event! event))))

(defn- settle-for-switch!
  "Settle an in-flight turn and compaction before a session swap so the
   aborted work persists to the outgoing session and cannot race the swap;
   waits (bounded, 3s) for the pending bash results to drain and for the
   compaction to finish. Clears the cancel signal when everything settled."
  [engine]
  (let [ag (agent-state engine)
        was-running (turn-running? engine)]
    (when was-running
      ;; cancel! drops the turn flag too: a cancelled run never runs the
      ;; wrapped :on-done
      (cancel! engine))
    (when @(:compacting? ag)
      (reset! (:signal ag) true))
    (let [deadline (+ (concurrent/monotonic-ms) 3000)]
      (loop []
        (when (and (or (seq @(:pending-bash ag)) @(:compacting? ag))
                   (< (concurrent/monotonic-ms) deadline))
          (Thread/sleep 10)
          (recur))))
    (when (and (not @(:compacting? ag))
               (empty? @(:pending-bash ag)))
      (reset! (:signal ag) false))
    was-running))

(defn new-session!
  "Start a fresh session in the runtime cwd (pi: runtimeHost.newSession):
   emit :session-before-switch (extensions may cancel), settle the in-flight
   turn/compaction, emit :session-shutdown, create and switch to the new
   session, reset the loop's per-run state, and emit :session-start
   asynchronously. The opts :before-settle fn runs after the cancel check
   and before the settle wait — the front end cancels its own resources
   (e.g. a running bash command) there so their results still land in the
   outgoing session. Returns {:session new} | {:cancelled true}; the front
   end owns UI cleanup (chat, editors, bash widgets)."
  ([engine] (new-session! engine nil))
  ([engine {:keys [before-settle]}]
   (let [previous-file (:file (session engine))]
     (if (extension-cancelled? {:type :session-before-switch
                                :reason :new
                                :target-session-file previous-file})
       {:cancelled true}
       (do
         (when before-settle (before-settle))
         (let [was-running (settle-for-switch! engine)]
           ;; pi: teardownCurrent — after the run is settled, tell extensions the
           ;; runtime is being torn down (reason :new, destination session file)
           ;; so they can persist state before the swap.
           (event-bus/emit-event! {:type :session-shutdown :reason :new
                                   :target-session-file previous-file})
           (let [cwd (cwd engine)
                 sessions-dir (cfg/get-session-dir (config engine))
                 new-session (session/create-session (ensure-cwd-session-dir sessions-dir cwd)
                                                     {:cwd cwd})]
             (debug/log "new session created: " (:id new-session))
             (switch-session! engine new-session {:apply-settings? false})
             ;; Rebuild the in-memory context from the new session — empty — and
             ;; reset per-run state (pi: createRuntime builds a fresh agent
             ;; state; the hook/config atoms are kept — not session state).
             (let [ag (agent-state engine)]
               (reset! (:status ag) :idle)
               (reset! (:steering ag) [])
               (reset! (:follow-up ag) [])
               (reset! (:pending-bash ag) [])
               (reset! (:overflow-recovered ag) false)
               (reset! (:retry-count ag) 0))
             ;; pi: newSession emits session_start (reason "new",
             ;; previousSessionFile) — on a future, handlers may block on dialog
             ;; promises (same as /reload).
             (emit-session-start-async!
              (cond-> {:type :session-start :reason :new}
                previous-file (assoc :previous-session-file previous-file)))
             {:session new-session :was-running was-running})))))))
(defn resume!
  "Load PATH and switch to it (pi: switchSession, reason :resume): emit
   :session-before-switch (extensions may cancel), load, switch with the
   session-derived settings applied, and emit :session-start asynchronously.
   Returns {:session sess} | {:cancelled true}."
  [engine path]
  (let [previous-file (:file (session engine))]
    (if (extension-cancelled? {:type :session-before-switch
                               :reason :resume
                               :target-session-file path})
      {:cancelled true}
      (let [sess (session/load-session path)]
        (switch-session! engine sess {:apply-settings? true})
        (emit-session-start-async!
         (cond-> {:type :session-start :reason :resume}
           previous-file (assoc :previous-session-file previous-file)))
        {:session sess}))))

(defn branch-session!
  "Branch the live session to TARGET-LEAF (nil = the root), optionally with
   a branch SUMMARY-RESULT ({:summary str :usage map}), label the summary
   entry (or the target), rebuild the agent's in-memory context, drop the
   compaction queue (pi: renderCurrentSessionState on session rebind), and
   emit :session-tree. Returns the branch-summary entry or nil; the front
   end keeps the tree UI and replays the new branch into its view."
  [engine {:keys [old-leaf target-leaf summary-result label from-extension?]}]
  (let [sess (session engine)
        summary-entry (if summary-result
                        (session/branch-with-summary!
                         sess target-leaf (:summary summary-result)
                         (when (:usage summary-result)
                           {:usage (:usage summary-result)}))
                        (do (if (nil? target-leaf)
                              (session/reset-leaf! sess)
                              (session/branch! sess target-leaf))
                            nil))
        label-target (if summary-entry (:id summary-entry) target-leaf)]
    (when (and label label-target)
      (session/set-label! sess label-target label))
    (agent/restore-session-context! (agent-state engine))
    (reset! (:compaction-queued engine) [])
    (event-bus/emit-event!
     (cond-> {:type :session-tree
              :new-leaf-id @(:leaf-id sess)
              :old-leaf-id old-leaf
              :from-extension? (boolean from-extension?)}
       summary-entry (assoc :summary-entry summary-entry)))
    summary-entry))

(defn fork!
  "Fork the session before the user message ENTRY-ID and switch to the fork
   (pi: runtimeHost.fork — the new session starts at the message's parent;
   the front end restores the message text to its editor): emit
   :session-before-fork (extensions may cancel), fork, or create an empty
   session linked to this one when the entry has no parent. Returns
   {:session fork :entry entry} | {:cancelled true} | {:error :unknown-entry}."
  [engine entry-id]
  (let [sess (session engine)
        entry (session/get-entry sess entry-id)]
    (cond
      (nil? entry) {:error :unknown-entry}
      (extension-cancelled? {:type :session-before-fork
                             :entry-id entry-id
                             :position :at})
      {:cancelled true}
      :else
      (let [cwd (cwd engine)
            sessions-dir (cfg/get-session-dir (config engine))
            fork (if (:parent-id entry)
                   (session/fork-session sess (:parent-id entry))
                   (session/create-session (ensure-cwd-session-dir sessions-dir cwd)
                                           {:parent-session (:file sess)
                                            :cwd cwd}))]
        (if (nil? fork)
          {:error :fork-failed}
          (do
            (debug/log "forked session " (:id fork) " from " (:id sess))
            (switch-session! engine fork {:apply-settings? false})
            {:session fork :entry entry}))))))

(defn clone!
  "Duplicate the live session at its current position (pi: /clone → fork at
   the current leaf) and switch to the clone: emit :session-before-fork
   (extensions may cancel), clone, switch without applying settings (pi:
   navigateTree keeps the current agent state). Returns
   {:session clone} | {:cancelled true} | {:error :no-session|:no-leaf|
   :not-persisted|:clone-failed}."
  [engine]
  (let [sess (session engine)]
    (cond
      (nil? sess) {:error :no-session}
      (nil? @(:leaf-id sess)) {:error :no-leaf}
      (not (fs/exists? (:file sess))) {:error :not-persisted}
      (extension-cancelled? {:type :session-before-fork
                             :entry-id @(:leaf-id sess)
                             :position :at})
      {:cancelled true}
      :else
      (let [clone (session/clone-session sess)]
        (if (nil? clone)
          {:error :clone-failed}
          (do
            (debug/log "cloned session " (:id clone) " from " (:id sess))
            (switch-session! engine clone {:apply-settings? false})
            {:session clone}))))))

(defn entries
  "The live session's entries (the session's own atom)."
  [engine]
  (some-> @(:session engine) :entries deref))

(defn messages
  "The agent's in-memory context messages (pi: session.messages — what the
   next request sends, compaction summaries and context edits applied)."
  [engine]
  (agent/get-context (agent-state engine)))

(defn set-session-name!
  "Set the session display name (the /name builtin and RPC set_session_name
   share this): sanitize, append the session_info entry, emit
   :session-info-changed. Returns the sanitized name."
  [engine name]
  (let [sess (session engine)
        sanitized (session/sanitize-session-name name)]
    (session/append-session-info! sess sanitized)
    (event-bus/emit-event!
     {:type :session-info-changed
      :session-file (:file sess)
      :name sanitized})
    sanitized))

(defn export-html!
  "Export the live session to HTML (the /export builtin and RPC export_html
   share this). PATH, or the session's runtime cwd default; the engine's
   system prompt and tool definitions are embedded (pi: exportSessionToHtml
   state). Returns the written path; throws when there is nothing to
   export."
  [engine {:keys [path]}]
  (let [ag (agent-state engine)
        system-prompt (or @(:system-prompt-override ag) @(:system ag))
        tool-defs (vals (tools/get-all-tools))]
    (session-export/export-to-html!
     (session engine)
     (cond-> (when path {:path path})
       (not path) (assoc :cwd (cwd engine))
       system-prompt (assoc :system-prompt system-prompt)
       (seq tool-defs) (assoc :tools tool-defs)))))

(defn snapshot
  "A plain-data view of the engine: the session, the agent's status /
   model / provider / thinking, the pending queues and the last outcome.
   Fields are read from independent atoms, so the snapshot is not one
   atomic cut (engine.md §4.1) — display consumers are fine with that."
  [engine]
  (let [agent-state (agent-state engine)]
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
  @(:last-outcome (agent-state engine)))

(defn turn-running?
  "True while a prompt started by `prompt!` has not settled. The runtime
   owns the flag: it is set synchronously by `prompt!` so a second
   submission steers instead of starting a concurrent run, cleared by
   `cancel!`, and cleared in the wrapped :on-done/:on-error before the
   front end's callbacks run — a callback interleaved with a background
   status revival can never see a settled turn as running."
  [engine]
  (boolean (some-> engine :turn-running? deref)))

(defn wait-idle!
  "Wait (bounded, default 2s) until no run and no compaction is in flight —
   pi's waitForIdle, the abort-response discipline. Returns true when idle,
   false on timeout. Polls on the monotonic clock."
  ([engine] (wait-idle! engine 2000))
  ([engine timeout-ms]
   (let [deadline (+ (concurrent/monotonic-ms) timeout-ms)
         idle? #(and (not (turn-running? engine))
                     (not @(:compacting? (agent-state engine))))]
     (loop []
       (cond
         (idle?) true
         (< (concurrent/monotonic-ms) deadline) (do (Thread/sleep 10) (recur))
         :else false)))))

(defn subscribe!
  "Register F as an event subscriber. Returns an unsubscribe fn. F runs on
   the emitting thread, must not block, and receives plain EDN events; a
   throw is logged and isolated."
  [engine f]
  (swap! (:subscribers engine) conj f)
  (fn unsubscribe []
    (swap! (:subscribers engine) (fn [subs] (filterv #(not (identical? % f)) subs)))
    nil))

(defn set-run-callbacks!
  "Install the front end's default run presentation callbacks (:on-text,
   :on-thinking, :on-done, :on-error — see prompt!). A run the runtime
   starts on its own (an extension send) presents through them; a headless
   front end installs none. Call-site opts win over the defaults."
  [engine callbacks]
  (when-let [a (:run-callbacks engine)]
    (reset! a callbacks))
  nil)

(defn prompt!
  "Start a prompt and return nil immediately — completion is observed as
   :agent-settled. TEXT is the initial user message (nil/absent continues on
   the existing context); :images attaches image blocks. :on-text,
   :on-thinking, :on-done and :on-error are run-agent-turn's callbacks —
   the front end's defaults (set-run-callbacks!) merged under OPTS — with
   the turn flag (turn-running?) managed around them."
  [engine opts]
  (let [opts (merge (some-> (:run-callbacks engine) deref) opts)
        on-done (:on-done opts)
        on-error (:on-error opts)
        clear! #(reset! (:turn-running? engine) false)]
    (reset! (:turn-running? engine) true)
    (reset! (:last-run engine)
            (agent/run-agent-turn
             (agent-state engine)
             (assoc (select-keys opts [:images :on-text :on-thinking])
                    :on-done (fn [text] (clear!) (when on-done (on-done text)))
                    :on-error (fn [err] (clear!) (when on-error (on-error err)))
                    :message (:text opts))))
    nil))

(defn steer! [engine text] (agent/steer! (agent-state engine) text))
(defn follow-up! [engine text] (agent/follow-up! (agent-state engine) text))
(defn queues [engine] (agent/queued-messages (agent-state engine)))
(defn clear-queues!
  "Drop the steering/follow-up queues (the restore-to-editor path clears
   them before cancelling); the loop emits the matching :queue-update."
  [engine]
  (agent/clear-queues! (agent-state engine)))
(defn cancel!
  "Cancel the in-flight run (if any): the turn flag drops synchronously and
   the loop settles the prompt with :outcome :aborted."
  [engine]
  (reset! (:turn-running? engine) false)
  (agent/cancel-turn (agent-state engine)))

;; ─── UI requests (the port's async answer protocol, engine.md §4.5) ───────

(defn respond!
  "Answer the pending :ui-request ID with VALUE (pi: the RPC
   extension_ui_response). A missing or already-answered id is ignored.
   Returns nil."
  [engine id value]
  (when-let [{:keys [promise]} (get @(:pending-ui engine) id)]
    (swap! (:pending-ui engine) dissoc id)
    (deliver promise value))
  nil)

(defn request-default
  "The default answer recorded for the pending :ui-request ID (nil when the
   id is unknown) — a forwarding port's own timeout deadline resolves with
   it, matching what `dispose!` would answer."
  [engine id]
  (:default (get @(:pending-ui engine) id)))

(defn request-ui!
  "Ask the bound front end for an answer (engine.md §4.5): builds
   `{:type :ui-request :id ... :kind ...}` merged with PAYLOAD, delivers it
   to the port's :request capability, and returns a promise of the answer.

   A port that answers in-process returns the value (a blocking dialog may
   block — §4.2 rule 4) or `extensions/request-pending` when its in-process
   dialog has not resolved yet; a port that forwards the request to an
   asynchronous client returns `request-pending` and the answer arrives
   through `respond!`. Without a :request capability the degraded default
   for KIND answers immediately (`:default` in PAYLOAD overrides it); the
   same default resolves a request still pending at `dispose!`."
  [engine kind payload]
  (let [id (str (random-uuid))
        default (get payload :default (extensions/ui-request-default kind))
        request (-> payload
                    (dissoc :default)
                    (assoc :type :ui-request :id id :kind kind))
        p (promise)
        request-fn (extensions/ui-capability :request)]
    (if request-fn
      (do (swap! (:pending-ui engine) assoc id {:promise p :default default})
          (let [reply (try (request-fn request)
                           (catch Throwable t
                             ;; a throwing port still cleans up its
                             ;; pending entry; the error reaches the caller
                             (respond! engine id default)
                             (throw t)))]
            (when-not (= extensions/request-pending reply)
              (respond! engine id reply))
            p))
      ;; no port: the degraded default is the answer
      (do (deliver p default) p))))

;; ─── Submission + compaction queue ─────────────────────────────────────────

(defn compaction-queued
  "Messages queued while a compaction was running, in order
   ({:text s :mode :steer|:follow-up} — pi: compactionQueuedMessages);
   flushed by flush-compaction-queue! when the compaction ends."
  [engine]
  (or (some-> engine :compaction-queued deref) []))

(defn queue-compaction-message!
  "Queue TEXT typed during compaction for delivery after it
   (pi: queueCompactionMessage); MODE is :steer or :follow-up."
  [engine text mode]
  (swap! (:compaction-queued engine) conj {:text text :mode mode})
  nil)

(defn clear-compaction-queue!
  "Drop all compaction-queued messages; returns the dropped messages."
  [engine]
  (let [q (compaction-queued engine)]
    (reset! (:compaction-queued engine) [])
    q))

(defn- split-command
  "Split a slash-command line into [name args]. Non-command text returns
   [nil nil]."
  [text]
  (when (str/starts-with? text "/")
    (let [sp (str/index-of text " ")]
      (if sp
        [(subs text 1 sp) (subs text (inc sp))]
        [(subs text 1) ""]))))

(defn extension-command?
  "True when TEXT is a registered extension command (pi: isExtensionCommand)."
  [text]
  (some? (some-> (split-command text) first commands/find-command :extension-handler)))

(defn execute-extension-command!
  "Run a registered extension command with the extension context (pi:
   prompt() → _tryExecuteExtensionCommand → handler(args, ctx))."
  [text]
  (let [[name args] (split-command text)
        c (commands/find-command name)]
    ((:extension-handler c) (extensions/build-extension-context) (or args ""))))

(defn- expand-compaction-text
  "Expand a compaction-queued message's skill commands and prompt templates
   (pi: steer/followUp expand skill+template before queueing; prompt applies
   the full chain). Queued text is raw (pre-hook); the flush applies input
   hooks first (idle path) and expansion here matches pi's steer/followUp
   for the running-turn path."
  [text]
  (-> text
      (skills/expand-skill-command)
      (prompts/expand-prompt-template (prompts/get-prompt-templates))))

(defn expand-user-message-text
  "pi: prompt() expansion chain (sendUserMessage with
   :expand-prompt-templates?): extension commands execute immediately and
   consume the message; then skill commands and prompt templates expand.
   Builtin commands are NOT dispatched (pi: _tryExecuteExtensionCommand
   only). Returns the expanded text, or nil when an extension command
   consumed the message."
  [text]
  (let [trimmed (str/trim text)]
    (if (and (str/starts-with? trimmed "/")
             (not (str/includes? trimmed "\n")))
      (let [space (str/index-of trimmed " ")
            cmd (if (nil? space) (subs trimmed 1) (subs trimmed 1 space))
            args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
        (if-let [c (commands/find-command cmd)]
          (if-let [eh (:extension-handler c)]
            (do (eh (extensions/build-extension-context) args)
                nil ;; consumed — nothing to send
                )
            text) ;; builtin command — not dispatched here (pi parity)
          (-> text
              (skills/expand-skill-command)
              (prompts/expand-prompt-template (prompts/get-prompt-templates)))))
      text)))

(defn- apply-input-hooks
  "Run extension input hooks on TEXT; returns the (possibly transformed)
   text, or nil when a hook consumed the input (pi: session.prompt input
   event). :streaming-behavior tells hooks whether the input will be
   steered."
  [engine text]
  (let [input (extensions/apply-input-hooks text :interactive
                                            {:streaming-behavior (when (turn-running? engine) :steer)})]
    (if (= :handled (:action input))
      (do (debug/log "input handled by extension: " text) nil)
      (if (contains? input :text) (:text input) text))))

(defn submit!
  "Submit TEXT from a front end (pi: session.prompt's input chain). While a
   compaction runs the raw text queues as a steer message; otherwise the
   extension input hooks run (a :handled input is consumed), and the text
   either steers the running turn or starts a new one. With the :expand?
   opt the skill-command/prompt-template expansion chain runs first (pi:
   the unknown-slash-command path); :run-opts are the front end's prompt
   callbacks for a run this starts. Returns {:action :compaction-queued |
   :handled | :started | :steered :text <hook-transformed text>} — the
   front end records :text in its editor history."
  ([engine text] (submit! engine text nil))
  ([engine text {:keys [expand? run-opts]}]
   (if @(:compacting? (agent-state engine))
     (do (queue-compaction-message! engine text :steer)
         {:action :compaction-queued :text text})
     (if-let [hooked (apply-input-hooks engine text)]
       (let [out (if expand?
                   (-> hooked
                       (skills/expand-skill-command)
                       (prompts/expand-prompt-template (prompts/get-prompt-templates)))
                   hooked)]
         (if (turn-running? engine)
           (do (steer! engine out) {:action :steered :text hooked})
           (do (prompt! engine (merge run-opts {:text out})) {:action :started :text hooked})))
       {:action :handled}))))

(defn- deliver-compaction-message!
  "Deliver one compaction-queued M: extension commands execute immediately;
   otherwise the message queues into the run per its mode (steer/follow-up,
   with skill/template expansion). With :allow-prompt? and no run active,
   the idle path runs the input hooks and starts a prompt instead (pi:
   flushCompactionQueue → session.prompt); the overflow-retry flush never
   prompts — every message queues via steer/followUp."
  [engine m {:keys [allow-prompt? run-opts]}]
  (if (extension-command? (:text m))
    (execute-extension-command! (:text m))
    (if (and allow-prompt? (not (turn-running? engine)))
      (when-let [text (apply-input-hooks engine (:text m))]
        (prompt! engine (merge run-opts {:text (expand-compaction-text text)})))
      (let [text (expand-compaction-text (:text m))]
        (if (= :follow-up (:mode m))
          (follow-up! engine text)
          (steer! engine text))))))

(defn flush-compaction-queue!
  "Deliver messages queued during compaction (pi: flushCompactionQueue —
   runs on every compaction_end). With WILL-RETRY (overflow compaction: the
   interrupted turn continues), every message queues into the running turn
   via steer/follow-up per its mode. Otherwise the first non-extension
   message becomes a prompt (starts a run when idle), extension commands
   execute immediately, and the rest queue per mode. RUN-OPTS are the
   front end's run callbacks for a prompt this starts. A failure restoring
   the queue re-queues the messages so they are not lost; returns nil, or
   {:error e} for the front end to report."
  [engine will-retry run-opts]
  (let [msgs (compaction-queued engine)]
    (when (seq msgs)
      (clear-compaction-queue! engine)
      (let [restore! (fn []
                        ;; pi: restoreQueue — the session queue is cleared too,
                        ;; so messages already delivered by the failed flush are
                        ;; not delivered again when the queue is re-flushed
                       (agent/clear-queues! (agent-state engine))
                       (reset! (:compaction-queued engine) msgs))]
        (try
          (if will-retry
             ;; Overflow compaction: the turn retries — queue everything into it
             ;; (pi: flushCompactionQueue willRetry — steer/followUp only, never
             ;; a fresh prompt)
            (doseq [m msgs] (deliver-compaction-message! engine m nil))
             ;; Normal completion: first non-extension message prompts, the
             ;; rest queue per mode
            (let [first-idx (first (keep-indexed
                                    (fn [i m] (when-not (extension-command? (:text m)) i))
                                    msgs))]
              (if first-idx
                (do
                   ;; extension commands before the first prompt execute now
                  (doseq [m (take first-idx msgs)]
                    (execute-extension-command! (:text m)))
                   ;; the first message: prompt when idle, else queue per mode
                  (deliver-compaction-message! engine (nth msgs first-idx)
                                               {:allow-prompt? true :run-opts run-opts})
                   ;; remaining messages queue per mode — never a fresh prompt
                   ;; (pi: the rest go through steer/followUp)
                  (doseq [m (drop (inc first-idx) msgs)]
                    (deliver-compaction-message! engine m nil)))
                 ;; all extension commands — execute them all
                (doseq [m msgs] (execute-extension-command! (:text m))))))
          nil
          (catch Exception e
            (restore!)
            (debug/log "compaction queue flush failed: " e)
            {:error e :count (count msgs)}))))))

(defn compact!
  "Compact the session (manual /compact path); custom-instructions and
   reason are loop/pi-shaped (see kmet.app.loop/compact-context!)."
  ([engine] (agent/compact-context! (agent-state engine)))
  ([engine custom-instructions reason]
   (agent/compact-context! (agent-state engine) custom-instructions reason)))

(defn set-model! [engine model] (agent/set-model! (agent-state engine) model))
(defn cycle-model!
  "Cycle the session model forward (1-arity) or in a direction (-1/1)."
  ([engine] (cycle-model! engine 1))
  ([engine direction] (agent/cycle-model! (agent-state engine) direction)))
(defn set-thinking-level! [engine level] (agent/set-thinking-level! (agent-state engine) level))
(defn cycle-thinking-level!
  "Advance the thinking level through the current model's levels; returns
   the new level, or nil when the model offers fewer than two."
  [engine]
  (agent/cycle-thinking-level! (agent-state engine)))
(defn set-auto-compact! [engine enabled?] (agent/set-auto-compact! (agent-state engine) enabled?))
(defn set-steering-mode! [engine mode] (agent/set-steering-mode! (agent-state engine) mode))
(defn set-follow-up-mode! [engine mode] (agent/set-follow-up-mode! (agent-state engine) mode))
(defn set-block-images!
  "Block or allow image content in provider requests (the /settings row and
   /reload re-applying the config value)."
  [engine blocked?]
  (agent/set-block-images! (agent-state engine) blocked?))
(defn scoped-models [engine] @(:scoped-models (agent-state engine)))
(defn set-scoped-models! [engine models]
  (agent/set-scoped-models! (agent-state engine) models))

;; ─── Runtime-owned extension capabilities (pi: bindCore) ───────────────────

(defn- reject-compacting!
  "Extension sends cannot queue into a compaction (pi: prompt() throws)."
  [ag]
  (when @(:compacting? ag)
    (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                    {:type :compaction-in-progress}))))

(defn- set-model-capability
  "pi: ctx.setModel — auth-gated, with the position-preserving thinking
   switch and the settings default persisted (the front-end view rides the
   :model-select / :thinking-level-select events). Returns true when the
   model was applied, false when it has no configured auth."
  [engine model]
  (if (and model (models/has-configured-auth model))
    (let [ag (agent-state engine)
          old-model (models/get-model @(:provider ag) @(:model ag))]
      (agent/set-model! ag model)
      (agent/set-thinking-level! ag (agent/switch-thinking-level old-model model @(:thinking ag) nil))
      (cfg/set-default-model! (:provider model) (:id model))
      true)
    false))

(defn- send-user-message-capability
  "pi: ctx.sendUserMessage — the message steers the running turn or starts
   one, after the optional expansion chain."
  [engine text {:keys [deliver-as expand-prompt-templates?]}]
  (reject-compacting! (agent-state engine))
  (let [text (if expand-prompt-templates? (expand-user-message-text text) text)]
    (when text
      (cond
        (not (turn-running? engine)) (prompt! engine {:text text})
        (= :steer deliver-as) (steer! engine text)
        :else (follow-up! engine text))))
  nil)

(defn- send-message-capability
  "pi: ctx.sendCustomMessage — a custom message follows the deliver-as /
   trigger-turn branches (see the extension api docs)."
  [engine message {:keys [deliver-as trigger-turn]}]
  (let [ag (agent-state engine)]
    (reject-compacting! ag)
    (let [msg {:role :custom
               :custom-type (or (:custom-type message) :custom)
               :content (:content message)
               :display (if (nil? (:display message)) true (:display message))
               :details (:details message)}
          busy? (turn-running? engine)]
      (cond
        ;; next-turn never triggers: it rides the next prompt
        (= :next-turn deliver-as) (agent/queue-next-turn-message! ag msg)

        ;; streaming with a trigger: into the run (steer is the default)
        (and busy? (not (false? trigger-turn)))
        (if (= :follow-up deliver-as)
          (follow-up! engine msg)
          (steer! engine msg))

        ;; idle with a trigger: append now, then run on the new context
        trigger-turn (do (agent/append-custom-message! ag msg)
                         (prompt! engine {}))

        ;; streaming without a trigger: defer to the turn-end flush
        busy? (agent/defer-custom-message! ag msg)

        ;; idle without a trigger: append only
        :else (agent/append-custom-message! ag msg)))
    true))

(defn runtime-capabilities
  "The runtime-owned extension capabilities (pi: bindCore): agent control
   every front end shares, independent of the UI port — model/thinking,
   sending messages, the active tool set. bootstrap installs them;
   extensions dispatch through extensions/runtime-call."
  [engine]
  {:set-model #(set-model-capability engine %)
   :get-thinking-level #(deref (:thinking (agent-state engine)))
   :set-thinking-level (fn [level]
                         (when (shared/valid-thinking-level? level)
                           (agent/set-thinking-level! (agent-state engine) level))
                         nil)
   :send-user-message #(send-user-message-capability engine %1 %2)
   :send-message! #(send-message-capability engine %1 %2)
   :get-active-tools #(mapv :name (agent/active-tools (agent-state engine)))
   :set-active-tools (fn [names]
                       (agent/set-active-tools! (agent-state engine) names)
                       nil)})

(defn dispose!
  "Release the engine: cancel an in-flight run or compaction, wait (bounded,
   2s) for a run to settle so the terminal :agent-settled reaches the
   subscribers, then clear the subscribers, resolve any pending UI requests
   with their degraded defaults, and unbind the extension runtime handle.
   Idempotent."
  [engine]
  (when (compare-and-set! (:disposed? engine) false true)
    (let [agent-state (agent-state engine)
          run @(:last-run engine)
          run-pending? (and run (not (realized? run)))]
      (when (or run-pending? @(:compacting? agent-state))
        (agent/cancel-turn agent-state)
        (when run-pending?
          (deref run 2000 nil))))
    (reset! (:subscribers engine) [])
    (reset! (:turn-running? engine) false)
    (some-> (:run-callbacks engine) (reset! nil))
    ;; no front end is listening after dispose: a waiting extension dialog
    ;; must not park the shutting-down process. Each request resolves with
    ;; its degraded default (pi: abort resolves a dialog with its default)
    (doseq [{:keys [promise default]} (vals @(:pending-ui engine))]
      (deliver promise default))
    (reset! (:pending-ui engine) {})
    ;; drop this engine's extension event-listeners from circulation; with v1's
    ;; single engine, the process default points back at a fresh empty atom.
    (reset! (event-bus/default-event-listener-atom) {})
    (event-bus/set-default-event-listener-atom! (atom {}))
    (extensions/set-session! nil)
    (extensions/set-context-sink! nil)
    (extensions/unbind-extensions!))
  nil)
