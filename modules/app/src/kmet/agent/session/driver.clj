(ns kmet.agent.session.driver
  "The session driver: what builds an engine and runs it.

   `bootstrap` constructs the engine (agent state, session, config-derived run
   options, system prompt, extension runtime handle) and the operations here
   drive it: prompts and steering, cancellation, session switching, compaction
   dispatch, extension commands, and disposal. The value itself — and what a
   front end may read — is `kmet.agent.session.runtime`, one rank below this.

   Design rules:

   - The event stream is the truth. `subscribe!` takes a fn of one plain EDN
     event; callbacks run on the emitting thread and must not block. A throwing
     subscriber is logged and isolated — it cannot kill the loop or starve the
     other subscribers.
   - `:on-text`/`:on-thinking`/`:on-done`/`:on-error` stay sugar: `prompt!`
     passes them straight to `run-agent-turn`, derived from the same emission
     path, so they cannot drift from the stream.
   - Extension wiring is runtime-scoped: `bootstrap` registers the session
     handle, the context sink and the headless UI port (with the front-end kind)
     on the engine's tree — the `[:app :session]` and `[:app :ui]` owners — and
     `dispose!` drops those owners. The extension atoms stay the no-node read
     cache for extension code that has no node in hand. A front end with its own
     UI port (interactive) re-installs after its widgets exist —
     `extensions/install-front-end!` is the install point.
   - Registrations are not engine state (P2): commands, tools, skills, prompt
     templates, hooks, renderers, markdown transformers and flags live in the
     app's registries, each under an owner path, so every engine reads the same
     set and a registration outlives the engine that happened to be current when
     it was made.
   - `dispose!` cancels an in-flight run and waits (bounded) for it to settle
     before clearing the subscribers, so the terminal `:agent-settled` event
     reaches them; the settle path runs in the loop's `finally`."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [kmet.agent.commands :as commands]
   [kmet.agent.compaction.stage :as agent-compact]
   [kmet.agent.config :as cfg]
   [kmet.agent.debug :as debug]
   [kmet.agent.event-bus :as event-bus]
   [kmet.agent.extensions.core :as extensions]
   [kmet.agent.loop :as agent]
   [kmet.agent.loop.state :as loop-state]
   [kmet.agent.model-resolver :as resolver]
   [kmet.agent.session.core :as session]
   [kmet.ai.models :as models]
   [kmet.agent.session.runtime :refer [agent-ctx agent-state compacting? compaction-error compaction-queued config cwd ensure-cwd-session-dir find-recent-session map->Engine pending-compaction session switch-model! turn-running?]]
   [kmet.agent.skills :as skills]
   [kmet.app.loop.driver :as driver]
   [kmet.libs.concurrent :as concurrent]
   [kmet.srce.core :as srce]))

(declare cancel! runtime-capabilities start-pending-compactions! subscribe!)

(defn- default-ui-port
  "The capability map a front end without its own port gets: the headless
   auto-answers (extensions/headless-ui-port) plus the `build-context`
   callback every map must provide (the extension ctx
   :model/:scoped-models/:thinking-level read the live agent). Interactive
   replaces the map from `kmet.ui.tui.interactive.ui-registry` once its
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
                     engine's session follows an overridden :session).
                     Anything the state takes rides here, which is why
                     `:system-prompt-fn` (the builder the state calls on an
                     active-tools change; the default is the ctx's
                     `:systemPrompt` row, and kmet1's wrapper is the last
                     stand-in) and an overridden `:ctx` are read from here.

     The rest is the front end's policy, not the state's, and none of the three
     has a default — an engine bootstrapped without one behaves as if the
     process had none, because the Engine does not know how this process keeps
     its resources:

     :ctx          — the ctx the engine runs on: the caller names the ctx whose
                     `:tools` service is the table this engine lists and
                     dispatches through, and whose `:loop-stages` the run reads
                     (4c-iii-c); kmet1's modes name the app root. A state with
                     no ctx has no tool table — `bootstrap` fails at the
                     prompt rather than mid-turn
     :expand-input — the fn the engine applies to queued and submitted text
                     before it reaches the run, replacing skill commands and
                     prompt templates with what they stand for; kmet1's front
                     ends pass `kmet.agent.packages/expand-input`, and an
                     engine given none queues text as written
     :load-resources! — a thunk `bootstrap` calls once, before the prompt is
                     built, to put the caller's skills and prompt templates
                     into the app's registries; kmet1's front ends pass
                     `kmet.agent.packages/load-resources!` (the unified
                     package resolution), and an engine given none loads none

   Loads the caller's resources (`:load-resources!`), builds the system prompt
   (custom prompt, appended prompt, project context files, active tools),
   derives every run option from CONFIG with the same defaults the TUI and
   print mode used to wire by hand, seeds the scoped-model list, and installs
   the extension runtime handle (session, context sink, UI registry)."
  [config opts]
  (let [{:keys [model provider ui-context mode has-ui agent-opts ctx
                load-resources! expand-input]} opts
        ;; the resolved session (the layout/print-mode policy output); any
        ;; :agent-opts override is folded back in below via the agent state
        sess (:session opts)
        ;; the ctx the engine runs on: the caller's (a subagent's child ctx),
        ;; or the app root kmet1's modes pass — the prompt's tool table and the
        ;; agent state both read this one value, so the two can never name
        ;; different tables
        ctx (or (:ctx agent-opts) ctx)
        ;; the caller's resources land once the ctx is named, before the prompt
        ;; is built from them
        _ (when load-resources! (load-resources!))
        ;; the one builder this engine builds (and rebuilds) its prompt with:
        ;; the state calls it when an active-tools change must re-render the
        ;; prompt, so the two build sites below cannot disagree with it. The
        ;; caller's own comes first, then the composition's `:systemPrompt`
        ;; row (kmet1 boots kmet2 behind its app root, so the row is the
        ;; prompt policy they share), and the process-bound wrapper last.
        system-prompt-fn (or (:system-prompt-fn agent-opts)
                             (srce/get ctx :system-prompt)
                             skills/build-system-prompt)
        subscribers (atom [])
        ;; the run's pieces, derived once in the app layer: provider and model
        ;; (the config's, or the caller's override), the runtime cwd, the tool
        ;; table from the ctx's `:tools`, and the prompt from the builder this
        ;; engine resolved above. kmet2's driver derives its state the same
        ;; way, so a run option cannot be wired here and forgotten there.
        {:keys [state] runtime-cwd :cwd}
        (driver/agent-state config ctx
                            {:session sess
                             :model model
                             :provider provider
                             :system-prompt-fn system-prompt-fn
                             :on-event (fn [ev]
                                         (doseq [f @subscribers]
                                           (try (f ev)
                                                (catch Throwable t
                                                  (debug/log "session subscriber failed: "
                                                             t)))))
                             :agent-opts agent-opts})
        agent-state state
        _ (loop-state/init-scoped-models! agent-state config)
        engine (map->Engine {:agent-state (atom agent-state)
                             :session (atom (:session agent-state))
                             :config (atom config)
                             :subscribers subscribers
                             :last-run (atom nil)
                             :disposed? (atom false)
                             :cwd (atom runtime-cwd)
                             :expand-input expand-input
                             :turn-running? (atom false)
                             :compaction-queued (atom [])
                             :pending-compaction (atom [])
                             :pending-ui (atom {})
                             :run-callbacks (atom nil)})]
    ;; Runtime-scoped extension surface: the session facades and the ctx
    ;; :model/:scoped-models/:thinking-level read the live agent, and the
    ;; front-end binding carries the port and kind. The session and sink are
    ;; process caches (dispose! clears them), the binding is the front end's;
    ;; a front end with its own port re-installs once its widgets exist.
    (extensions/install-session! (:session agent-state))
    (extensions/install-context-sink!
     (fn [msg] (loop-state/add-context-message! (agent-state engine) msg)))
    (extensions/install-front-end!
     {:mode mode
      :has-ui has-ui
      ;; a front end supplies only its own capabilities; the runtime's
      ;; build-context callback always rides along
      :ui (merge (default-ui-port engine) ui-context)})
    (extensions/bind-runtime-capabilities! (runtime-capabilities engine))
    ;; The internal subscriber is the only trigger for deferred manual
    ;; compactions (see request-compaction!): a /compact that arrived while a
    ;; prompt was running starts when that prompt settles — after the
    ;; front end's teardown and the post-run compaction check.
    (subscribe! engine
                (fn [ev]
                  (when (= :agent-settled (:type ev))
                    (start-pending-compactions! engine))))
    engine))

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
    (reset! (:system ag) (apply (or (:system-prompt-fn ag) skills/build-system-prompt)
                                (mapcat identity opts)))))

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
  [engine payload]
  (future
    (try
      (event-bus/emit-event! payload (agent-ctx engine))
      (extensions/discover-resources! :startup)
      (catch Exception e (debug/log "session-start: " e)))))

(defn switch-session!
  "Swap the live session to SESS (the runtime owns the swap; front ends
   keep the UX around it): point the session atom and the
   extension session handle at it, replace the agent's session and rebuild
   its in-memory context from the new branch, apply the session-derived
   model/thinking/tool settings when the opts :apply-settings? is true, drop
   the compaction queue and any deferred compaction request (both belong to
   the outgoing session), and point the runtime cwd at the session's.
   Callers own the before/after lifecycle events. Returns SESS."
  [engine sess {:keys [apply-settings?]}]
  (let [new-ag (assoc (agent-state engine) :session sess)]
    (reset! (:session engine) sess)
    (extensions/install-session! sess)
    (reset! (:compaction-queued engine) [])
    (reset! (:pending-compaction engine) [])
    (reset! (:agent-state engine) new-ag)
    (loop-state/restore-session-context! new-ag)
    (when apply-settings?
      ;; pi: createRuntime builds a fresh session with the config-derived
      ;; tool set — the outgoing session's set-active-tools!/tools-selector
      ;; selection must not carry over (the switch also emits :session-start,
      ;; letting extensions restore their own per-session state).
      (loop-state/set-active-tools! new-ag nil)
      (loop-state/apply-session-settings! new-ag))
    (apply-session-cwd! engine sess)
    sess))

(defn- extension-cancelled?
  "Emit a before-event (session-before-switch / session-before-fork) from the
   engine's ctx and report whether an extension cancelled the pending
   operation (pi: emitBeforeSwitch/emitBeforeFork → cancel)."
  [ctx event]
  (boolean (:cancel (event-bus/emit-event! event ctx))))

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
   turn/compaction (dropping a compaction deferred for the outgoing
   session), emit :session-shutdown, create and switch to the new
   session, reset the loop's per-run state, and emit :session-start
   asynchronously. The opts :before-settle fn runs after the cancel check
   and before the settle wait — the front end cancels its own resources
   (e.g. a running bash command) there so their results still land in the
   outgoing session. Returns {:session new} | {:cancelled true}; the front
   end owns UI cleanup (chat, editors, bash widgets)."
  ([engine] (new-session! engine nil))
  ([engine {:keys [before-settle]}]
   (let [previous-file (:file (session engine))]
     (if (extension-cancelled? (agent-ctx engine)
                               {:type :session-before-switch
                                :reason :new
                                :target-session-file previous-file})
       {:cancelled true}
       (do
         (when before-settle (before-settle))
         ;; a compaction deferred for the outgoing session dies with it: the
         ;; settle below cancels the running turn and would otherwise let the
         ;; :agent-settled drain start the request against the swap
         (reset! (:pending-compaction engine) [])
         (let [was-running (settle-for-switch! engine)]
           ;; pi: teardownCurrent — after the run is settled, tell extensions the
           ;; runtime is being torn down (reason :new, destination session file)
           ;; so they can persist state before the swap.
           (event-bus/emit-event! {:type :session-shutdown :reason :new
                                   :target-session-file previous-file}
                                  (agent-ctx engine))
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
              engine
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
    (if (extension-cancelled? (agent-ctx engine)
                              {:type :session-before-switch
                               :reason :resume
                               :target-session-file path})
      {:cancelled true}
      (let [sess (session/load-session path)]
        (switch-session! engine sess {:apply-settings? true})
        (emit-session-start-async!
         engine
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
    (loop-state/restore-session-context! (agent-state engine))
    (reset! (:compaction-queued engine) [])
    (reset! (:pending-compaction engine) [])
    (event-bus/emit-event!
     (cond-> {:type :session-tree
              :new-leaf-id @(:leaf-id sess)
              :old-leaf-id old-leaf
              :from-extension? (boolean from-extension?)}
       summary-entry (assoc :summary-entry summary-entry))
     (agent-ctx engine))
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
      (extension-cancelled? (agent-ctx engine)
                            {:type :session-before-fork
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
      (extension-cancelled? (agent-ctx engine)
                            {:type :session-before-fork
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

(defn steer! [engine text] (loop-state/steer! (agent-state engine) text))

(defn follow-up! [engine text] (loop-state/follow-up! (agent-state engine) text))

(defn cancel!
  "Cancel the in-flight run (if any): the turn flag drops synchronously and
   the loop settles the prompt with :outcome :aborted."
  [engine]
  (reset! (:turn-running? engine) false)
  (agent/cancel-turn (agent-state engine)))

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
  "Ask the bound front end for an answer: builds
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

(defn expand-queued-text
  "Expand TEXT the way this engine's front end expands input — kmet1's skill
   commands and prompt templates — for a steered, follow-up or
   compaction-queued message (pi: steer/followUp expand skill+template before
   queueing; prompt applies the full chain). Queued text is raw (pre-hook);
   the flush applies input hooks first (idle path) and expansion here matches
   pi's steer/followUp for the running-turn path. Extension commands are not
   expanded here — they are not allowed on the queue paths. An engine
   bootstrapped without `:expand-input` queues its text as written."
  [engine text]
  (if-some [expand! (:expand-input engine)]
    (expand! text)
    text))

(defn expand-user-message-text
  "pi: prompt() expansion chain (sendUserMessage with
   :expand-prompt-templates?): extension commands execute immediately and
   consume the message; then skill commands and prompt templates expand.
   Builtin commands are NOT dispatched (pi: _tryExecuteExtensionCommand
   only). Returns the expanded text, or nil when an extension command
   consumed the message."
  [engine text]
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
          (expand-queued-text engine text)))
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
   opt the engine's `:expand-input` chain runs first (pi: the
   unknown-slash-command path); :run-opts are the front end's prompt
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
                   (expand-queued-text engine hooked)
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
        (prompt! engine (merge run-opts {:text (expand-queued-text engine text)})))
      (let [text (expand-queued-text engine (:text m))]
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
                       (loop-state/clear-queues! (agent-state engine))
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
   reason are loop/pi-shaped (see kmet.agent.compaction.stage/compact-context!)."
  ([engine] (agent-compact/compact-context! (agent-state engine)))
  ([engine custom-instructions reason]
   (agent-compact/compact-context! (agent-state engine) custom-instructions reason)))

(defn- run-manual-compaction!
  "Run one queued manual compaction request and report it through the
   request's callbacks. A :failed compaction reports through :on-error with
   pi's `Compaction failed: …` message; a completed one (true, or false when
   there was nothing to compact) and :aborted report through :on-complete —
   the :compaction-end event carries the rest to the UI. An exception
   escaping the compaction reports through :on-error too (logged either
   way); one escaping a callback is contained so it cannot kill the drain."
  [engine {:keys [custom-instructions on-complete on-error]}]
  (let [outcome (try
                  {:result (compact! engine custom-instructions :manual)}
                  (catch Exception e
                    ;; a future that swallows the exception hides real bugs,
                    ;; so log it (pi: handleCompactCommand catches and ignores
                    ;; — the events carry the failure)
                    (debug/log-error "manual compaction failed: " e)
                    {:error e}))]
    (try
      (if-let [e (:error outcome)]
        (when on-error (on-error e))
        (let [result (:result outcome)]
          (if (= :failed result)
            (when on-error
              (on-error (ex-info (or (compaction-error engine)
                                     "Compaction failed: Unknown error")
                                 {:type :compaction-failed})))
            (when on-complete (on-complete result)))))
      (catch Exception e
        (debug/log-error "compaction callback failed: " e)))))

(defn- start-pending-compactions!
  "Start the manual compactions queued in :pending-compaction — the deferred
   ones from a running prompt and an idle request-compaction! alike. Runs on
   a future so the caller/settle emit stays non-blocking, and serializes the
   drains (locking on the queue): a settle and an idle request can both
   trigger one, and two concurrent compact-context! calls would race — the
   second returns false because one is in flight. When a newer prompt
   already owns the runtime — or a compaction is somehow in flight — the
   requests stay queued for the next settle. A disposed engine drops them."
  [engine]
  (when (and (not @(:disposed? engine))
             (seq (pending-compaction engine)))
    (future
      (let [queue (:pending-compaction engine)]
        (locking queue
          (when-not (or (turn-running? engine)
                        (compacting? engine))
            (loop []
              (when-not @(:disposed? engine)
                (let [[claimed _] (swap-vals! queue
                                              (fn [qs] (if (seq qs) (subvec qs 1) qs)))]
                  (when-let [req (first claimed)]
                    (run-manual-compaction! engine req)
                    (recur)))))))))))

(defn request-compaction!
  "Request a manual compaction (CUSTOM-INSTRUCTIONS optional). While the
   runtime is idle the compaction starts immediately; while a prompt is
   still running it is queued and starts when the prompt settles — kmet's
   cancel is cooperative, so compacting under a live run would race it (pi
   instead aborts the run first). OPTS:
     :on-complete — (fn [result]): a completed compaction (true, or false
                    when there was nothing to compact), or :aborted.
     :on-error    — (fn [error]): an exception escaping the compaction, or a
                    :failed compaction with its `Compaction failed: …`
                    message.
   Returns :started (the compaction launched), :scheduled (deferred until
   the running prompt settles), or :compacting (one is already in flight —
   nothing is queued)."
  ([engine] (request-compaction! engine nil nil))
  ([engine custom-instructions opts]
   (if (compacting? engine)
     :compacting
     (let [scheduled? (turn-running? engine)]
       (swap! (:pending-compaction engine) conj
              (assoc opts :custom-instructions custom-instructions))
       (when-not scheduled?
         (start-pending-compactions! engine))
       (if scheduled? :scheduled :started)))))

(defn abort-compaction!
  "Abort the in-flight compaction without cancelling a running turn."
  [engine]
  (agent-compact/abort-compaction! (agent-state engine)))

(defn generate-branch-summary
  "LLM summary of abandoned branch entries for tree navigation. Returns
   {:summary str :usage map} | {:aborted true} | {:error msg}; SIGNAL
   aborts the call."
  [engine entries custom-instructions signal replace-instructions?]
  (agent-compact/generate-branch-summary (agent-state engine) entries custom-instructions
                                         signal replace-instructions?))

(defn- reject-compacting!
  "Extension sends cannot queue into a compaction (pi: prompt() throws)."
  [ag]
  (when @(:compacting? ag)
    (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                    {:type :compaction-in-progress}))))

(defn- set-model-capability
  "pi: ctx.setModel — auth-gated; the switch applies the position-preserving
   thinking rule and persists the settings default (the front-end view rides
   the :model-select / :thinking-level-select events). Returns true when the
  was applied, false when it has no configured auth."
  [engine model]
  (if (and model (models/has-configured-auth model))
    (do (switch-model! engine model nil)
        true)
    false))

(defn- send-user-message-capability
  "pi: ctx.sendUserMessage — the message steers the running turn or starts
   one, after the optional expansion chain."
  [engine text {:keys [deliver-as expand-prompt-templates?]}]
  (reject-compacting! (agent-state engine))
  (let [text (if expand-prompt-templates? (expand-user-message-text engine text) text)]
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
        (= :next-turn deliver-as) (loop-state/queue-next-turn-message! ag msg)

        ;; streaming with a trigger: into the run (steer is the default)
        (and busy? (not (false? trigger-turn)))
        (if (= :follow-up deliver-as)
          (follow-up! engine msg)
          (steer! engine msg))

        ;; idle with a trigger: append now, then run on the new context
        trigger-turn (do (loop-state/append-custom-message! ag msg)
                         (prompt! engine {}))

        ;; streaming without a trigger: defer to the turn-end flush
        busy? (loop-state/defer-custom-message! ag msg)

        ;; idle without a trigger: append only
        :else (loop-state/append-custom-message! ag msg)))
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
                         (when (models/valid-thinking-level? level)
                           (loop-state/set-thinking-level! (agent-state engine) level))
                         nil)
   :send-user-message #(send-user-message-capability engine %1 %2)
   :send-message! #(send-message-capability engine %1 %2)
   :get-active-tools #(mapv :name (loop-state/active-tools (agent-state engine)))
   :set-active-tools (fn [names]
                       (loop-state/set-active-tools! (agent-state engine) names)
                       nil)})

(defn dispose!
  "Release the engine: cancel an in-flight run or compaction, wait (bounded,
   2s) for a run to settle so the terminal :agent-settled reaches the
   subscribers, then clear the subscribers, resolve any pending UI requests
   with their degraded defaults, and unbind the extension runtime handle.
   Idempotent."
  [engine]
  (when (compare-and-set! (:disposed? engine) false true)
    (let [ag (agent-state engine)
          run @(:last-run engine)
          run-pending? (and run (not (realized? run)))]
      (when (or run-pending? @(:compacting? ag))
        (agent/cancel-turn ag)
        (when run-pending?
          (deref run 2000 nil))))
    (reset! (:subscribers engine) [])
    (reset! (:turn-running? engine) false)
    (reset! (:pending-compaction engine) [])
    (some-> (:run-callbacks engine) (reset! nil))
    ;; no front end is listening after dispose: a waiting extension dialog
    ;; must not park the shutting-down process. Each request resolves with
    ;; its degraded default (pi: abort resolves a dialog with its default)
    (doseq [{:keys [promise default]} (vals @(:pending-ui engine))]
      (deliver promise default))
    (reset! (:pending-ui engine) {})
    ;; The extension surface is the host's process caches and the front-end
    ;; binding: clearing them restores the headless defaults.
    (extensions/clear-runtime!)
    (extensions/unbind-extensions!))
  nil)
