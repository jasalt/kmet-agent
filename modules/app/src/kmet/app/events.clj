(ns kmet.app.events
  "The app's event layer: what the events are called, and who they belong to.

   The mechanism is the kernel's (`srce.md` §5): the listener table, the six
   non-awaiting dispatch modes and the one exception rule are `kmet.srce.core`. This
   namespace owns exactly the two things the kernel must not know — the
   **vocabulary** (43 names with their documentation; a kernel cannot name an
   app event) — and the **owner** a registration carries, which is a kmet
   concept (an extension's path, the loop, nothing).

   `on!` registers through `srce/on` with `{:data owner}`, so a registration is
   an effect of the registering fiber (an unload unregisters it LIFO), what a
   listener hears is decided by where it registers (the ctx and its descendants),
   and `srce/collect-all!` reports `[owner result]` pairs — a contribution
   applied under the owner that made it. `emit!` dispatches by the event's
   declared mode, so a declaration is the contract a dispatch honours.

   Registration is never bound to a ctx by the service: `on!` always takes the
   registrant's own ctx, because that is the listener's lifetime and its scope.
   The `:events` service binds the *emitting* half to the scope it serves.

   The kernel's own three names (`:srce/status`, `:srce/service`,
   `:srce/failure`) are documented in `srce.md` §5 and cannot appear here —
   provision sits above the kernel.

   The **wire projections** (`wire-event`, `wire-error`) are not here: they are
   transport, so they live with the transport that serializes
   (`kmet.app.event-bus`), not with the bus."
  (:require [kmet.srce.core :as srce]))
;; ── declarations ─────────────────────────────────────────────────────────
;; The vocabulary is declared by the plugin map at the end of this file and read
;; from it directly (declared-events): the declaration is the truth, there is no
;; projection in the path, and a mounted plugin's own `:events` is what
;; inspection reads. The generated plugin catalog stays what landing 1 made it —
;; `--dump-config`'s card projection, so composition can be printed without
;; loading a plugin namespace.

(def domains
  "Which half of the app emits an event: `:loop` (the agent loop's own lifecycle
   and streaming) or `:app` (the interactive mode, the session admin and the
   provider bridges) — the split a front end's event handler needs, because it
   consumes the loop's stream and may ignore the app's."
  #{:loop :app})

(declare plugin known-event-type?)

(defn declared-events
  "The vocabulary this namespace declares, as
   `[{:name :mode :domain :doc :public}]` in name order — read from the plugin
   map **in this file**, which is the declaration the kernel mounts. Nothing is
   loaded beyond the namespace that holds it, and there is no projection to go
   stale.

   A plugin declares its own events the same way (`:events` on `defplugin`); this
   is the row that owns the built-in vocabulary until the loop, the session and
   the mode plugins land and claim theirs. A second plugin may re-declare a name
   only with the same mode, domain and doc — `kmet.app.runtime/boot` checks every
   mounted fiber's declarations against each other."
  []
  (->> (:events plugin)
       (map (fn [[event {:keys [mode domain doc public]}]]
              {:name event
               :mode (or mode :emit)
               :domain (or domain :loop)
               :doc doc
               ;; an extension-only event is not on the public stream: its
               ;; payload is request machinery (a provider payload, headers, an
               ;; abort signal), not a fact a wire consumer renders
               :public (not (false? public))}))
       (sort-by (comp str :name))
       vec))

(defn declarations
  "The vocabulary as `{name {:mode :domain :doc :public}}` — the plugin map's
   own declaration, read directly. This is what `emit!` dispatches by."
  []
  (:events plugin))

(defn emit!
  "Dispatch EVENT on CTX by its declared mode — the declaration drives the
   dispatcher, so an event's `:mode` is its contract, not a comment.

   Returns what the mode answers: nil for `:emit`, the last non-nil result for
   `:collect-last`, `[owner result]` pairs for `:collect-all`, the accumulated
   decision map for `:boundary`. An undeclared type dispatches as `:emit` and is
   reported, as an undeclared registration is."
  [ctx event]
  (let [event-type (:type event)
        declaration (get (declarations) event-type)
        mode (:mode declaration :emit)]
    (when-not (srce/ctx? ctx)
      (throw (ex-info (str "event " (pr-str event-type)
                           " has no emitting ctx — every emission belongs to a scope")
                      {:type :kmet.app/bad-event-ctx
                       :event event-type})))
    (when-not declaration
      (srce/log! ctx :warn "events: unknown event type" event-type))
    (when-not (contains? srce/dispatch-modes mode)
      (throw (ex-info (str "event " (pr-str event-type) " declares an unknown dispatch mode")
                      {:type :kmet.app/bad-event-mode
                       :event event-type
                       :mode mode})))
    (case mode
      :emit (srce/emit! ctx event-type event)
      :bail (srce/bail! ctx event-type event)
      :waterfall (srce/waterfall! ctx event-type event)
      :collect-last (srce/collect-last! ctx event-type event)
      :collect-all (srce/collect-all! ctx event-type event)
      :boundary (srce/boundary! ctx event-type event))))

;; ── registration ──────────────────────────────────────────────────────────

(defn on!
  "Register F for TYPE on CTX with OPTIONS:

     :owner  the value `srce/collect-all!` reports with F's result (an
             extension path, `:loop`, nil for a host registration)

   An unknown TYPE is reported and registered anyway — the vocabulary is
   documented, not a gate. Returns the disposer; requires an active fiber, so
   it belongs to the fiber's effects."
  ([ctx type f] (on! ctx type nil f))
  ([ctx type {:keys [owner]} f]
   (when-not (known-event-type? type)
     (srce/log! ctx :warn "events: unknown event type" type))
   (srce/on ctx type {:data owner} f)))

(defn on-every
  "Register F for every **public** event the vocabulary declares, as one
   subscription: F sees each event with its `:type`. Returns the disposer for
   the lot — a consumer that wants the whole stream (a front end, a mode
   writing the wire) subscribes once, not once per name.

   Extension-only events are excluded: `:context` / `:before-provider-*` /
   `:after-provider-response` carry the request's payload and headers, and the
   `:session-before-*` hooks carry a control object. They are for extensions,
   which subscribe by name; a wire must not carry them."
  [ctx f]
  (let [disposers (mapv (fn [event] (on! ctx (:name event) f))
                        (filter :public (declared-events)))]
    (fn []
      (doseq [dispose disposers] (dispose))
      nil)))

(defn unsubscribe!
  "Dispose the subscription a late-bound ATOM holds, once; nothing to do when
   it holds none. A front end that subscribes after the thing it listens to
   exists (print and rpc create their agent first) keeps the disposer in an
   atom: the mode's teardown and the run's `finally` both call this, and only
   one of them gets the fn."
  [subscription]
  (when-let [f (first (reset-vals! subscription nil))]
    (f)))

(defn once!
  "Register F for TYPE's next emit on CTX; disposed before F runs."
  ([ctx type f] (once! ctx type nil f))
  ([ctx type {:keys [owner]} f]
   (srce/once ctx type {:data owner} f)))

;; ── the service ───────────────────────────────────────────────────────────

(defn service
  "The `:events` service value for the scope (a ctx) it serves: the dispatch
   modes bound to that scope, so a holder of the app ctx emits without knowing a
   narrower one. A caller with a narrower ctx calls the kernel's fns with it
   instead — the ctx is the scope, and the service is only the app-wide default.
   The loop is the first such caller and the reason to keep the distinction: a
   run emits from the agent's own ctx (`loop.state/emit`), so its listeners hear
   its subtree; emitting through this service would put every run's events at
   the app's scope and out of reach of the ctx they belong to.

   The binding is a closure, not a `:ctx` entry: a service value must not carry
   the ctx, or printing it prints the kernel (and every fiber in it)."
  [ctx]
  {:emit! (fn [type event] (srce/emit! ctx type event))
   :bail! (fn [type event] (srce/bail! ctx type event))
   :waterfall! (fn [type event] (srce/waterfall! ctx type event))
   :collect-last! (fn [type event] (srce/collect-last! ctx type event))
   :collect-all! (fn [type event] (srce/collect-all! ctx type event))
   :boundary! (fn [type event] (srce/boundary! ctx type event))
   :types (fn [] (srce/event-names ctx))})

(defn- serve
  "The `:events` row's apply: provide the service into the app **scope** — the
   mount site's ctx — so the app's other rows see it, while the entry stays this
   fiber's effect and the service emits from the scope it serves."
  [ctx _config]
  (let [scope (:parent ctx)]
    (srce/provide! ctx :events (service scope) {:in scope})))

(srce/defplugin plugin
  {:name :events
   :provide #{:events}
   :events
   {:session-compact {:mode :emit :domain :loop
                      :doc "Fired after a successful compaction, carrying the appended entry (pi:
                        session_compact). Payload: :compaction-entry (the :role :compaction
                        session entry), :from-extension (true when a :session-before-compact
                        handler supplied the summary — pi: fromExtension), :reason,
                        :will-retry (true for overflow recovery)."}
    :user-bash {:mode :emit :domain :app
                :doc "Fired when the user runs a bash command (!/!!).
                        Payload: :command, :exclude-from-context?, :cwd."}
    :summarization-retry-finished {:mode :emit :domain :loop
                                   :doc "Fired once when a summarization retry loop ends — the retried call
                        succeeded, failed terminally, or the backoff was aborted (pi:
                        summarization_retry_finished). No payload."}
    :thinking-level-select {:mode :emit :domain :loop
                            :doc "Thinking level changed (pi: thinking_level_select, emitted by
                        kmet.app.loop.state/set-thinking-level! on an actual change).
                        Payload: :level, :previous-level."}
    :session-before-switch {:mode :collect-last :domain :app :public false
                            :doc "Actionable: fired before /new or /resume replaces the active session
                        (pi: emitBeforeSwitch). Payload: :reason (:new | :resume),
                        :target-session-file (optional; the destination path, or the outgoing
                        file for :new). Handlers may return {:cancel true} to abort the switch
                        — the session stays untouched."}
    :message-start {:mode :emit :domain :loop
                    :doc "Fired when a message is added to the context, or when assistant
                        streaming begins.
                        Payload: :message."}
    :turn-end {:mode :boundary :domain :loop
               :doc "Actionable: fired after a turn completes — the LLM call plus any tool
                        execution — including a response that errored or was aborted, where it
                        precedes :agent-end (pi: turn_end, emitted by the loop's finishTurn
                        call). Payload: :turn-index, :message, :tool-results, :outcome
                        (:completed | :error | :aborted), :message-entry-id (the persisted
                        session entry the turn's assistant message became; nil without a
                        session) and :tool-result-entry-ids (the turn's tool results' entry ids,
                        in call order — pi: _dispatchTurnEndBoundary's messageEntryId /
                        toolResultEntryIds), plus the boundary state: :context-entries /
                        :context-messages / :llm-messages (the session projection, so a handler
                        can read the transcript or pick an edit target without touching the
                        session — pi: BoundaryContextPreview; :llm-messages is the
                        provider-agnostic part of pi's convertToLlm), :pending-messages (the
                        queued batches the next attempt would start
                        from, plus custom messages deferred while streaming — pi:
                        _getPendingBoundaryMessages), :can-continue, :entries [] and :continue
                        false. Handlers may
                        return {:entries [entry ...] :continue bool} (pi: emitBoundary): entries
                        are session entry maps appended in order (pi: SessionBoundaryDraft — a
                        :custom-message entry also enters the context; a :context-edit entry
                        {:role :context-edit :target-id id :replacement nil | {:content ...}}
                        omits or rewrites that earlier message in the projection and rebuilds
                        the live context — pi: appendContextEdit + _refreshFinalizedContext).
                        :continue asks for one more request, honored once the entries are
                        committed when the context can back it, and reported then dropped
                        otherwise (:can-continue is that check as the boundary stood). Each
                        handler sees the previous handlers' :entries/:continue merged in, as pi
                        does."}
    :session-tree {:mode :emit :domain :app
                   :doc "Fired after a session-tree navigation branched (pi: session_tree).
                        Payload: :new-leaf-id, :old-leaf-id, :summary-entry (optional),
                        :from-extension? (when the summary came from an extension)."}
    :summarization-retry-attempt-start {:mode :emit :domain :loop
                                        :doc "Fired after a summarization retry's backoff, before the retried call
                        (pi: summarization_retry_attempt_start). Payload: :source
                        (:compaction | :branch-summary) and, for a compaction, :reason."}
    :tool-execution-start {:mode :emit :domain :loop
                           :doc "Fired when a tool execution begins.
                        Payload: :tool-call-id, :tool-name, :args."}
    :agent-start {:mode :emit :domain :loop
                  :doc "Fired at the start of each attempt: once per user submission, and once
                        more for every auto-retry, overflow-recovery, or agent-end-queued
                        attempt (pi: agent_start, emitted by agent.prompt and by every
                        agent.continue)."}
    :session-before-tree {:mode :collect-last :domain :app :public false
                          :doc "Fired before a session-tree navigation branches (pi: session_before_tree).
                        Payload: :preparation {:target-id :old-leaf-id :common-ancestor-id
                        :entries-to-summarize :user-wants-summary :custom-instructions
                        :replace-instructions :label}, :signal (abort atom). Handlers may return
                        a map: {:cancel true} aborts the navigation, {:summary str :details …}
                        (when :user-wants-summary) supplies the branch summary, and
                        :custom-instructions/:label override the inputs."}
    :tool-execution-update {:mode :emit :domain :loop
                            :doc "Fired when a streaming tool emits partial output (e.g. bash live output).
                        Payload: :tool-call-id, :content, :is-partial."}
    :tool-execution-end {:mode :emit :domain :loop
                         :doc "Fired when a tool execution completes.
                        Payload: :tool-call-id, :tool-name, :args, :result, :is-error."}
    :queue-update {:mode :emit :domain :loop
                   :doc "Fired when steering/follow-up queues change: a message queued (steer! /
                        follow-up!), drained into a turn, or dropped. Payload: :steering,
                        :follow-up — the complete current queues; :reason :aborted when
                        cancel-turn dropped pending messages, so a headless consumer can tell a
                        drop from a drain that delivered the same (empty) queues."}
    :session-before-fork {:mode :collect-last :domain :app :public false
                          :doc "Actionable: fired before forking or cloning at a message (pi:
                        session_before_fork). Payload: :entry-id, :position (:at). Handlers may
                        return {:cancel true} to abort the fork."}
    :context-with-system {:mode :collect-last :domain :app :public false
                          :doc "Fired after :context with the full transcript — system prompt included — and
                        the result is sent verbatim (pi: context_with_system). Payload: :messages.
                        Handlers may return {:messages [...]}; a result that drops the leading
                        system message is reported as an extension error and honored, as pi does.
                        The last non-nil result wins."}
    :after-provider-response {:mode :collect-last :domain :app :public false
                              :doc "Fired after the provider response is received, before its body is
                        consumed (pi: after_provider_response). Payload: :status, :headers."}
    :compaction-end {:mode :emit :domain :loop
                     :doc "Fired when compaction finishes (pi: compaction_end).
                        Payload: :reason, :result (the compaction result map — pi:
                        CompactionResult, kmet keys: :summary, :first-kept-id, :tokens-before,
                        :estimated-tokens-after, :usage, :details — or nil when the compaction
                        failed or was cancelled), :aborted
                        (true when the user cancelled mid-compaction — session untouched),
                        :from-extension (true when the summary came from a
                        :session-before-compact handler's :compaction result), :error-message
                        (set when the summarization call failed — pi:
                        `Compaction failed: …` manual, `Auto-compaction failed: …` threshold,
                        `Context overflow recovery failed: …` overflow, each carrying the
                        summarization failure's cause), :will-retry
                        (true for a successful overflow compaction — the interrupted turn
                        retries; pi: compaction_end willRetry)."}
    :agent-settled {:mode :emit :domain :loop
                    :doc "Fired when the prompt is fully settled — after the last attempt's
                        :agent-end, once per prompt, after any retries, overflow recovery,
                        post-run compaction, error, timeout, or cancel. The agent is idle and no
                        further events for this prompt will be emitted (pi: agent_settled,
                        emitted from a finally block after the whole prompt loop). Payload:
                        :outcome (:completed | :error | :aborted — :aborted when the run's
                        cancel signal fired; a prompt that cannot start, e.g. no configured
                        auth, also settles, with :error)."}
    :message-end {:mode :emit :domain :loop
                  :doc "Fired when an assistant message is finalized — added to context on
                        success, or recorded in the session history only when the LLM call
                        failed or was aborted (:stop-reason :error with :error-message, or
                        :aborted; pi: message_end for a stopReason-error/aborted partial that
                        _prepareRetry then drops from state). Payload: :message."}
    :loop-guard {:mode :emit :domain :loop
                 :doc "Repeat-loop circuit breaker tripped: the model kept issuing identical
                        tool calls (or repeating its reasoning), so the run was stopped early
                        to avoid burning tokens on a dead end (kmet-specific — no pi
                        counterpart). Payload: :reason (:tool-calls | :thinking),
                        :details (human-readable explanation)."}
    :session-compact-failed {:mode :emit :domain :loop
                             :doc "Fired after context compaction fails or is aborted (pi:
                        session_compact_failed). Payload: :reason (:manual | :threshold |
                        :overflow), :error-message (when a non-abort failure — same text as
                        :compaction-end), :aborted, :from-extension, :will-retry (false — a
                        failed compaction never retries the turn; pi:
                        _emitSessionCompactFailed)."}
    :auto-retry-end {:mode :emit :domain :loop
                     :doc "Fired when retries finish — success, exhausted, or cancelled.
                        Payload: :success, :attempt, :final-error (on failure)."}
    :status {:mode :emit :domain :loop
             :doc "UI status change.
                        Payload: :status (:idle :thinking :executing :error)."}
    :agent-end {:mode :emit :domain :loop
                :doc "Fired when an attempt finishes (success or error) — one per attempt
                        (pi: agent_end). Payload: :messages (the messages added during this
                        attempt, accumulated as they are appended — pi: newMessages — so a
                        mid-run context replacement does not shrink the list, and an aborted or
                        errored attempt is included), :error (optional), :will-retry (true when
                        this attempt's transient error will be retried; pi: willRetry, added for
                        public listeners only — extensions never see the key). A handler that
                        queues a message here (steer! / follow-up!) starts a fresh attempt in
                        the same prompt, with the queued messages as its starting context (pi:
                        _handlePostAgentRun's hasQueuedMessages → agent.continue); messages
                        queued by :agent-settled handlers stay queued for the next submission."}
    :message-update {:mode :emit :domain :loop
                     :doc "Fired for each streaming delta (assistant only).
                        Payload: :message (partial), :delta
                        {:type :text | :thinking | :tool-call ...}."}
    :context-replaced {:mode :emit :domain :loop
                       :doc "Fired when the conversation context is replaced: prepareNextTurn's
                        :context update, or a compaction (auto or manual) rebuilding the context
                        from the compacted session (pi: compaction_end re-renders the chat).
                        Payload: :messages — the new conversation messages."}
    :before-provider-headers {:mode :collect-last :domain :app :public false
                              :doc "Fired with the final request headers before the provider HTTP call (pi:
                        before_provider_headers — handlers mutate the map in place there; kmet's
                        maps are immutable, so handlers RETURN the replacement map and a nil
                        header value deletes that header). Payload: :headers. The last non-nil
                        map result replaces the headers."}
    :context {:mode :collect-last :domain :app :public false
              :doc "Fired before each LLM call with the conversation the request would send,
                        without the system prompt (pi: context — emitContext). Payload: :messages.
                        Handlers may return {:messages [...]} to replace the conversation; the
                        leading system message is re-attached, so a handler cannot drop the prompt
                        (pi: restoreSystemMessages). The last non-nil result wins (pi chains
                        handler results; kmet's bus keeps the last)."}
    :compaction-start {:mode :emit :domain :loop
                       :doc "Fired before session compaction begins (pi: compaction_start).
                        Payload: :reason (:manual | :threshold | :overflow | :auto)."}
    :error {:mode :emit :domain :loop
            :doc "Unhandled error inside the agent loop.
                        Payload: :message."}
    :summarization-retry-scheduled {:mode :emit :domain :loop
                                    :doc "Fired before a summarization retry's backoff sleep — a compaction or
                        branch-summary call failed with a transient error (pi:
                        summarization_retry_scheduled; drives the UI's retry countdown).
                        Payload: :attempt, :max-attempts, :delay-ms, :error-message."}
    :session-shutdown {:mode :emit :domain :app
                       :doc "Fired before the extension runtime is torn down — quit, reload, or
                        session replacement (pi: session_shutdown, emitted by teardownCurrent /
                        session.reload BEFORE the extensions are unloaded, so handlers can
                        persist state while still alive). Payload: :reason (:reload | :new |
                        :resume | :fork | :quit), :target-session-file (optional; the
                        destination session on replacement). Extensions restore their state on
                        the following :session-start."}
    :session-start {:mode :emit :domain :app
                    :doc "Fired once after the interactive TUI is built and the extension UI
                        registry is live, before the render loop starts (pi: session_start).
                        Payload: :reason (:startup | :reload | :new | :resume | :fork),
                        :previous-session-file (optional). Extensions use this to set up
                        widgets, statuses, footers, and custom editors."}
    :auto-retry-start {:mode :emit :domain :loop
                       :doc "Fired before a retry attempt's backoff sleep when a transient LLM error
                        is detected (pi: auto_retry_start).
                        Payload: :attempt, :max-attempts, :delay-ms, :error-message."}
    :resources-discover {:mode :collect-all :domain :app :public false
                         :doc "Fired after :session-start to collect extension-contributed resource
                        paths (pi: resources_discover — fired after session_start). Payload:
                        :cwd, :reason (:startup | :reload). Handlers return
                        {:skill-paths [...] :prompt-paths [...] :theme-paths [...]} (paths are
                        directories); ALL handler results are collected, not reduced
                        (collect-all!)."}
    :agent-before-settle {:mode :boundary :domain :loop
                          :doc "Actionable: fired once before the prompt settles, after the queued-message
                        continuation check, and before :agent-settled (pi: agent_before_settle,
                        emitted by _runBeforeSettleBoundary). Payload: :outcome — the last turn's
                        outcome (:completed | :error | :aborted) — plus the boundary state:
                        :context-entries / :context-messages / :llm-messages (the session
                        projection — pi: BoundaryContextPreview), :pending-messages (see
                        :turn-end), :can-continue, :entries [] and :continue false.
                        Handlers return {:entries [...] :continue bool} as for :turn-end; one
                        that merely queues a message also runs one more attempt before the
                        prompt settles (pi: shouldContinue includes hasQueuedMessages). A
                        cancelled prompt never reaches this event."}
    :model-select {:mode :emit :domain :loop
                   :doc "Model was changed (pi: model_select).
                        Payload: :model, :previous-model (resolved Model records; :previous-model
                        nil when the previous model is not registered), :source (:set | :cycle)."}
    :session-before-compact {:mode :collect-last :domain :loop :public false
                             :doc "Actionable: fired before context compaction — manual, threshold, or
                        overflow (pi: session_before_compact). Payload: :preparation (the
                        projection-aware CompactionPreparation: :messages, :tokens-before,
                        :settings), :branch-entries, :custom-instructions, :reason
                        (:manual | :threshold | :overflow), :will-retry (true for overflow
                        recovery), :signal (the run's abort atom). Handlers may return
                        {:cancel true} to skip the compaction, or {:compaction {:summary ..
                        :first-kept-id .. :tokens-before .. :usage .. :details ..}} to supply
                        the content instead of the summarizer (pi:
                        SessionBeforeCompactResult) — the entry then records :from-hook and the
                        compaction events carry :from-extension."}
    :session-info-changed {:mode :emit :domain :app
                           :doc "Session display name changed (pi: session_info_changed, emitted by the
                        /name command).
                        Payload: :session-file, :name."}
    :before-provider-request {:mode :collect-last :domain :app :public false
                              :doc "Fired with the assembled request payload before the provider HTTP call
                        (pi: before_provider_request). Payload: :payload. The last non-nil
                        handler result replaces the payload."}
    :turn-start {:mode :emit :domain :loop
                 :doc "Fired before each LLM call.
                        Payload: :turn-index."}}
   :apply serve})

;; ── the vocabulary (read from the declaration above) ──────────────────────

(def event-types
  "Map of event type keyword → description. The canonical vocabulary, read from
   this namespace's own declaration: what the loop, the interactive mode and the
   provider bridges emit."
  (into {} (map (juxt :name :doc)) (declared-events)))

(def app-event-types
  "The `:app`-domain events: emitted outside the agent loop's path — the
   interactive mode, extensions and the provider bridges. A front end's event
   handler consumes the loop's stream; these arrive by other routes."
  (into #{} (comp (filter #(= :app (:domain %))) (map :name)) (declared-events)))

(def loop-event-types
  "The `:loop`-domain events: the agent loop emits every one of them."
  (apply dissoc event-types app-event-types))

(def ^:private event-modes
  "Event type keyword → its declared dispatch mode."
  (into {} (map (juxt :name :mode)) (declared-events)))

(defn mode-of
  "The declared dispatch mode for TYPE, `:emit` when the event declares none."
  [type]
  (get event-modes type :emit))

(defn known-event-type?
  "True if the keyword is part of the documented vocabulary."
  [type]
  (contains? event-types type))
