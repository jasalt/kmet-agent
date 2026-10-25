(ns kmet.app.session.driver
  "The session driver: what builds an engine and runs it.

   `bootstrap` constructs the engine (agent state, session, config-derived run
   options, system prompt, extension runtime handle) and the operations here
   drive it: prompts and steering, cancellation, session switching, compaction
   dispatch, extension commands, and disposal. The value itself — and what a
   front end may read — is `kmet.app.session.runtime`, one rank below this.

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
   [kmet.app.compaction.stage :as agent-compact]
   [kmet.app.config :as cfg]
   [kmet.app.debug :as debug]
   [kmet.app.extensions.core :as extensions]
   [kmet.app.loop :as agent]
   [kmet.app.loop.state :as loop-state]
   [kmet.app.session.compaction :as manual]
   [kmet.app.session.core :as session]
   [kmet.app.session.lifecycle :as lifecycle]
   [kmet.ai.models :as models]
   [kmet.app.session.runtime :refer [agent-state ensure-cwd-session-dir extension-context find-recent-session map->Engine switch-model! turn-running?]]
   [kmet.app.session.submission :as submission]
   [kmet.app.session.summary :as summary]
   [kmet.app.skills :as skills]
   [kmet.app.loop.driver :as driver]
   [kmet.libs.concurrent :as concurrent]
   [kmet.srce.core :as srce]))

(declare cancel! runtime-capabilities subscribe!)

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
         (fn [] (extension-context engine))))

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
                     ends pass `kmet.app.packages/expand-input`, and an
                     engine given none queues text as written
     :load-resources! — a thunk `bootstrap` calls once, before the prompt is
                     built, to put the caller's skills and prompt templates
                     into the app's registries; kmet1's front ends pass
                     `kmet.app.packages/load-resources!` (the unified
                     package resolution), and an engine given none loads none

   Loads the caller's resources (`:load-resources!`), builds the system prompt
   (custom prompt, appended prompt, project context files, active tools),
   derives every run option from CONFIG with the same defaults the TUI and
   print mode used to wire by hand (the scoped-model list included — the
   builder seeds it), and installs the extension runtime handle (session,
   context sink, UI registry)."
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
                    (manual/start-pending-compactions! engine))))
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

;; The lifecycle itself lives below both drivers (`kmet.app.session.lifecycle`),
;; because kmet2's handle runs the same switch without a process-wide extension
;; slot. These wrappers keep kmet1's contract: the switch installs the session
;; into the process's extension slot, which the front end reads outside a run.

(defn apply-session-cwd!
  "SESS's working directory becomes the runtime cwd (the system prompt's cwd
   line, the footer, extension ctx.cwd and tool resolution follow it). Returns
   the cwd in effect."
  [engine sess]
  (lifecycle/apply-session-cwd! engine sess))

(defn emit-session-start-async!
  "Emit :session-start away from the input path, then the resources_discover
   pass (see `lifecycle/emit-session-start-async!`)."
  [engine payload]
  (lifecycle/emit-session-start-async! engine payload))

(defn switch-session!
  "Swap the live session to SESS and install it into the process-wide extension
   slot (see `lifecycle/switch-session!` for the swap's contract)."
  ([engine sess]
   (lifecycle/switch-session! engine sess {:install! extensions/install-session!}))
  ([engine sess opts]
   (lifecycle/switch-session! engine sess (assoc opts :install! extensions/install-session!))))

(defn new-session!
  "Start a fresh session (see `lifecycle/new-session!`), installing it into the
   process-wide extension slot."
  ([engine]
   (lifecycle/new-session! engine {:install! extensions/install-session!}))
  ([engine opts]
   (lifecycle/new-session! engine (assoc opts :install! extensions/install-session!))))

(defn resume!
  "Load PATH and switch to it (see `lifecycle/resume!`), installing it into the
   process-wide extension slot."
  [engine path]
  (lifecycle/resume! engine path {:install! extensions/install-session!}))

(defn branch-session!
  "Branch the live session (see `lifecycle/branch-session!`)."
  [engine opts]
  (lifecycle/branch-session! engine opts))

(defn fork!
  "Fork the session before ENTRY-ID (see `lifecycle/fork!`), installing the
   fork into the process-wide extension slot."
  [engine entry-id]
  (lifecycle/fork! engine entry-id {:install! extensions/install-session!}))

(defn clone!
  "Clone the live session at its current position (see `lifecycle/clone!`),
   installing the clone into the process-wide extension slot."
  [engine]
  (lifecycle/clone! engine {:install! extensions/install-session!}))

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
  "Cancel the in-flight run, if any (see `lifecycle/cancel-run!`)."
  [engine]
  (lifecycle/cancel-run! engine))

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
  (manual/queue-compaction-message! engine text mode))

(defn clear-compaction-queue!
  "Drop all compaction-queued messages; returns the dropped messages."
  [engine]
  (manual/clear-compaction-queue! engine))

(defn extension-command?
  "True when TEXT is a registered extension command (see
   `submission/extension-command?`)."
  [text]
  (submission/extension-command? text))

(defn execute-extension-command!
  "Run a registered extension command with the extension context (see
   `submission/execute-extension-command!`)."
  [text]
  (submission/execute-extension-command! text))

(defn expand-queued-text
  "Expand TEXT the way this engine's front end expands input — kmet1's skill
   commands and prompt templates — for a steered, follow-up or
   compaction-queued message (see `submission/expand-text`)."
  [engine text]
  (submission/expand-text engine text))

(defn expand-user-message-text
  "pi: prompt() expansion chain (sendUserMessage with
   :expand-prompt-templates?): extension commands execute immediately and
   consume the message; then skill commands and prompt templates expand.
   Builtin commands are NOT dispatched (pi: _tryExecuteExtensionCommand
   only). Returns the expanded text, or nil when an extension command
   consumed the message."
  [engine text]
  (submission/expand-user-message-text engine text))

(defn submit!
  "Submit TEXT from a front end (pi: session.prompt's input chain; see
   `submission/submit!`). Returns
   {:action :compaction-queued | :handled | :started | :steered,
   :text <hook-transformed text>} — the front end records :text in its editor
   history."
  ([engine text] (submit! engine text nil))
  ([engine text {:keys [expand? run-opts]}]
   (submission/submit! engine text
                       {:expand? expand?
                        :run-opts run-opts
                        :deliver! (fn [text target opts]
                                    (if (turn-running? engine)
                                      (do (if (= :next-turn target)
                                            (follow-up! engine text)
                                            (steer! engine text))
                                          :steered)
                                      (do (prompt! engine (merge opts {:text text}))
                                          :started)))})))

(defn flush-compaction-queue!
  "Deliver messages queued during compaction (pi: flushCompactionQueue — see
   `submission/flush-compaction-queue!`). RUN-OPTS are the front end's run
   callbacks for a prompt this starts. A failure restoring the queue re-queues
   the messages so they are not lost; returns nil, or {:error e} for the front
   end to report."
  [engine will-retry run-opts]
  (submission/flush-compaction-queue!
   engine will-retry
   {:start! (fn [text] (prompt! engine (merge run-opts {:text text})))}))

(defn compact!
  "Compact the session (manual /compact path); custom-instructions and
   reason are loop/pi-shaped (see kmet.app.compaction.stage/compact-context!)."
  ([engine] (agent-compact/compact-context! (agent-state engine)))
  ([engine custom-instructions reason]
   (agent-compact/compact-context! (agent-state engine) custom-instructions reason)))

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
   (manual/request-compaction! engine custom-instructions opts)))

(defn abort-compaction!
  "Abort the in-flight compaction without cancelling a running turn."
  [engine]
  (manual/abort-compaction! engine))

(defn generate-branch-summary
  "LLM summary of abandoned branch entries for tree navigation (see
   `kmet.app.session.summary/generate-branch-summary`). Returns
   {:summary str :usage map} | {:aborted true} | {:error msg}; SIGNAL
   aborts the call."
  [engine entries custom-instructions signal replace-instructions?]
  (summary/generate-branch-summary engine entries custom-instructions
                                   signal replace-instructions?))

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
  (manual/reject-submit! engine)
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
    (manual/reject-submit! engine)
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
