(ns kmet.modes.rpc
  "RPC mode — headless, long-lived: EDN commands on stdin, protocol records
   on stdout (engine.md step 6; pi: modes/rpc/rpc-mode.ts). A command is a
   map with a :type and an optional string :id the matching response
   repeats; events are the print-mode wire projection
   (event-bus/wire-event). Extension UI dialogs and output channels arrive
   as {:type :ui-request :id ... :method ...} records and a dialog is
   answered with {:type :respond :id ...} (the runtime's request-ui!/
   respond! pair). stdout carries protocol only — kmet.debug writes files
   and nothing else in the runtime prints."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.models :as models]
            [kmet.app.commands :as commands]
            [kmet.app.commands.builtins :as builtins]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.extensions :as extensions]
            [kmet.app.prompts :as prompts]
            [kmet.app.session :as session]
            [kmet.app.session-runtime :as runtime]
            [kmet.app.skills :as skills]
            [kmet.config :as cfg]
            [kmet.debug :as debug]))

;; ─── Records ───────────────────────────────────────────────────────────────

(defn- write-record!
  "Write one protocol record as EDN + LF. The only stdout writer; locked
   because run events and command responses arrive on different threads."
  [out record]
  (locking out
    (binding [*out* out]
      (prn record)
      (flush))))

(defn- ok
  "A success response carrying DATA as its :data payload; a nil data omits
   the key (pi's data-less success responses)."
  [command data]
  (cond-> {:type :response :command command :success true}
    (some? data) (assoc :data data)))

(defn- failed
  "A failure response: COMMAND names the command that failed (nil when the
   record carried none), MESSAGE is the human-readable reason."
  [command message]
  {:type :response :command command :success false :error (str message)})

(defn- correlated
  "Attach the request's :id when it had one (an absent id stays absent)."
  [record id]
  (cond-> record (some? id) (assoc :id id)))

;; ─── Shared helpers ────────────────────────────────────────────────────────

(defn- message-of
  "The command's non-empty :message string, or nil."
  [command]
  (let [message (:message command)]
    (when (and (string? message) (not (str/blank? message)))
      message)))

(defn- required-string
  "KEY's non-blank string value, or nil when the key is absent. A present
   non-string or blank value throws — that is a command-shape error the
   caller should have prevented (validated at the transport edge)."
  [command k]
  (let [v (get command k)]
    (when (some? v)
      (if (and (string? v) (not (str/blank? v)))
        v
        (throw (ex-info (str (name (:type command)) " requires " k
                             " to be a non-empty string")
                        {:type :invalid-command}))))))

(defn- queued-text
  "The text of a queued entry: plain strings queue directly, custom
   messages queue as maps."
  [item]
  (cond
    (string? item) item
    (map? item) (str (or (:text item) (:content item) (:custom-type item)))
    :else (str item)))

(defn- expand-queued-text
  "Expand skill commands and prompt templates for a steered/follow-up
   message (pi: steer/follow_up — extension commands are not allowed there,
   so unlike prompt's expansion chain they are left as text)."
  [text]
  (-> text
      (skills/expand-skill-command)
      (prompts/expand-prompt-template (prompts/get-prompt-templates))))

(defn- command-queue
  "The shared steer/follow-up path: the queues carry no image blocks, so
   reject :images rather than drop them; expand skill commands and prompt
   templates (pi: steer/follow_up — extension commands are not allowed
   there); queue; answer with the queued disposition."
  [engine {:keys [type] :as command} enqueue!]
  (when (:images command)
    (throw (ex-info (str (name type) " does not support :images yet")
                    {:type :unsupported})))
  (if-let [text (message-of command)]
    (do (enqueue! engine (expand-queued-text text))
        (ok type {:disposition :queued}))
    (failed type (str (name type) " requires a non-empty :message string"))))

;; ─── The extension UI port (engine.md §6.2) ────────────────────────────────

(def ^:private dialog-methods
  "Request kinds the client answers (pi: the dialog extension UI methods)."
  #{:select :confirm :input :editor})

(defn- write-ui-record!
  "Write a fire-and-forget extension UI record (the port's output channels);
   every one carries its own id so clients can correlate or dedupe."
  [out record]
  (write-record! out (assoc record :type :ui-request :id (str (random-uuid)))))

(defn- forward-ui-request
  "The port's :request capability: a dialog becomes a :ui-request record
   (kind → method) and stays pending until the client answers with :respond
   or the request's :timeout auto-resolves it (pi:
   ExtensionUIDialogOptions.timeout). Kinds with no client surface keep the
   degraded default (pi: :custom is not bridged)."
  [engine-ref out request]
  (let [{:keys [kind timeout]} request]
    (if (contains? dialog-methods kind)
      (do
        (write-record! out (-> request
                               (dissoc :kind)
                               (assoc :type :ui-request :method kind)))
        (when (and timeout (pos? timeout))
          (future
            (Thread/sleep (long timeout))
            (when-let [engine @engine-ref]
              (runtime/respond! engine (:id request)
                                (runtime/request-default engine (:id request))))))
        extensions/request-pending)
      (extensions/ui-request-default kind))))

(defn- ui-port
  "The RPC UI port (engine.md §6.2): the headless degraded answers plus
   everything the protocol carries — dialogs through :request and the
   fire-and-forget output channels. ENGINE-REF holds the engine (the port is
   built before bootstrap, which installs it). Widget hiccup cannot cross
   the wire: only nil or string lines are forwarded (pi: component
   factories are ignored); the other TUI-only capabilities stay degraded."
  [engine-ref out]
  (assoc (extensions/headless-ui-port)
         :request #(forward-ui-request engine-ref out %)
         :notify (fn [message type]
                   (write-ui-record! out (cond-> {:method :notify :message message}
                                           type (assoc :notify-type type))))
         :set-status (fn [key text]
                       (write-ui-record! out {:method :set-status
                                              :status-key key
                                              :status-text text}))
         :set-widget (fn [key content {:keys [placement]}]
                       (when (or (nil? content)
                                 (and (sequential? content) (every? string? content)))
                         (write-ui-record! out (cond-> {:method :set-widget
                                                        :widget-key key
                                                        :widget-lines content}
                                                 placement (assoc :widget-placement placement)))))
         :set-title (fn [title]
                      (write-ui-record! out {:method :set-title :title title}))
         :chat-info (fn [label content]
                      (write-ui-record! out (cond-> {:method :chat-info :content content}
                                              label (assoc :label label))))
         :set-editor-text (fn [text]
                            (write-ui-record! out {:method :set-editor-text :text text}))
         :paste-to-editor (fn [text]
                            (write-ui-record! out {:method :set-editor-text :text text}))))

(defn- command-ui
  "The command-UI value portable builtins receive over RPC (6.3): the shared
   capabilities mapped onto the port's output channels. :set-title is absent
   — a terminal title has no RPC meaning (the /name reply carries the
   name)."
  [port]
  {:message (fn [text] ((:chat-info port) nil text))
   :info (:chat-info port)
   :warning (fn [text] ((:notify port) text :warning))
   :status (fn [text] ((:set-status port) "status" text))
   :notify (:notify port)})

(defn- dispatch-command
  "Dispatch a slash-command prompt through the registry (engine.md §6.3): a
   command available to :rpc runs with the command ctx {:runtime :ui
   :frontend}; an extension command receives the extension context (pi:
   handler(args, ctx)) and executes immediately, even mid-stream. Returns
   the handled response, or nil when TEXT is not a known command — an
   unknown slash stays a normal prompt (skill and template expansion happen
   in submit!)."
  [engine command-ui text]
  (when-let [{:keys [name args]} (commands/parse-command-line text)]
    (when-let [c (commands/find-command name :rpc)]
      (if-let [extension-handler (:extension-handler c)]
        (extension-handler (extensions/build-extension-context) args)
        ((:handler c) {:runtime engine :ui command-ui :frontend :rpc} args))
      (ok :prompt {:disposition :handled}))))

;; ─── Commands ──────────────────────────────────────────────────────────────

(defn- command-steer
  "Queue a message for mid-turn injection (pi: steer)."
  [engine command]
  (command-queue engine command runtime/steer!))

(defn- command-follow-up
  "Queue a message for after the run settles (pi: follow_up)."
  [engine command]
  (command-queue engine command runtime/follow-up!))

(defn- command-prompt
  "Start, steer, queue, or handle a prompt (pi: prompt). A slash-command
   line dispatches through the registry first (6.3) — extension commands
   run immediately, even mid-stream (pi). Otherwise, while a run is active
   the client must choose the :streaming-behavior (:steer or :follow-up);
   pi errors without one. The response reports the disposition; accepted is
   not completed, so the client waits for :agent-settled. :images ride the
   started path into the run."
  [engine command command-ui]
  (if-let [text (message-of command)]
    (or (dispatch-command engine command-ui text)
        (let [behavior (:streaming-behavior command)
              running? (runtime/turn-running? engine)]
          (cond
            (and behavior (not (contains? #{:steer :follow-up} behavior)))
            (failed :prompt "streaming-behavior must be :steer or :follow-up")

            (and running? (nil? behavior))
            (failed :prompt "The agent is already running; specify :streaming-behavior :steer or :follow-up")

            (and running? behavior)
            (do ((if (= :steer behavior) runtime/steer! runtime/follow-up!)
                 engine (expand-queued-text text))
                (ok :prompt {:disposition :queued}))

            :else
            (let [result (runtime/submit! engine text
                                          (cond-> {:expand? true}
                                            (:images command) (assoc :run-opts {:images (:images command)})))]
              (ok :prompt {:disposition (case (:action result)
                                          :started :started
                                          :handled :handled
                                          :queued)})))))
    (failed :prompt "prompt requires a non-empty :message string")))

(defn- command-respond
  "Answer the pending :ui-request ID (pi: extension_ui_response). A valid
   answer is silent — pi writes no response record for one; a malformed
   answer fails (the id is what correlates an answer, so without one there
   is nothing to resolve). :cancelled resolves the request's default
   (nil), :confirmed a boolean, otherwise the answer is :value."
  [engine command]
  (let [id (:id command)]
    (if (and (string? id) (not (str/blank? id)))
      (do (runtime/respond! engine id
                            (cond
                              (:cancelled command) nil
                              (contains? command :confirmed) (boolean (:confirmed command))
                              :else (:value command)))
          nil)
      (failed :respond "respond requires a string :id"))))

(defn- command-abort
  "Cancel the in-flight run or compaction and wait (bounded) for the
   session to become idle before responding (pi: abort). An idle abort is a
   no-op: cancel! would still drop queued messages and emit an :aborted
   queue-update even with nothing to cancel."
  [engine _]
  (when (or (runtime/turn-running? engine)
            @(:compacting? (runtime/agent-state engine)))
    (runtime/cancel! engine))
  (ok :abort {:idle (runtime/wait-idle! engine)}))

(defn- command-clear-queue
  "Drop the queued steering/follow-up messages and return their texts
   (pi: clear_queue)."
  [engine _]
  (let [{:keys [steering follow-up]} (runtime/queues engine)]
    (runtime/clear-queues! engine)
    (ok :clear-queue {:steering (mapv queued-text steering)
                      :follow-up (mapv queued-text follow-up)})))

(defn- command-new-session
  "Start a fresh session in the runtime cwd (pi: new_session). The engine
   keeps serving; the client learns the new session here and through the
   session events."
  [engine _]
  (let [{:keys [cancelled session was-running]} (runtime/new-session! engine)]
    (if cancelled
      (ok :new-session {:cancelled true})
      (ok :new-session {:cancelled false
                        :session-id (:id session)
                        :session-file (:file session)
                        :was-running (boolean was-running)}))))

(defn- command-get-state
  "The live session state a client polls when it reconnects (pi: get_state)."
  [engine _]
  (let [snap (runtime/snapshot engine)
        sess (:session snap)
        ag (runtime/agent-state engine)
        cfg @(:cfg ag)
        session-name (some-> sess session/get-session-name)
        {:keys [steering follow-up]} (:queues snap)]
    (ok :get-state
        (cond-> {:model (:model snap)
                 :provider (:provider snap)
                 :thinking-level (:thinking snap)
                 :turn-running? (runtime/turn-running? engine)
                 :compacting? @(:compacting? ag)
                 :steering-mode (:steering-mode cfg)
                 :follow-up-mode (:follow-up-mode cfg)
                 :auto-compaction-enabled (boolean (:auto-compact cfg))
                 :pending-message-count (+ (count steering) (count follow-up))
                 :last-outcome (:last-outcome snap)}
          sess (assoc :session
                      (cond-> {:id (:id sess)
                               :file (:file sess)
                               :message-count (session/get-message-count sess)}
                        ;; pi omits sessionName when unset
                        session-name (assoc :name session-name)))))))

;; ─── Model ────────────────────────────────────────────────────────────────

(defn- available-model
  "The available (authenticated) model record for PROVIDER + MODEL-ID, or
   nil (pi: modelRuntime.getAvailableSnapshot)."
  [provider model-id]
  (first (filter #(and (= (keyword provider) (:provider %))
                       (= model-id (:id %)))
                 (models/get-available))))

(defn- command-set-model
  "Switch the session model (pi: set_model) — the model must be available
   (authenticated, as in pi's snapshot)."
  [engine command]
  (let [provider (:provider command)
        model-id (:model-id command)]
    (if (and (string? provider) (seq provider)
             (string? model-id) (seq model-id))
      (if-let [model (available-model provider model-id)]
        (do (runtime/set-model! engine model)
            (ok :set-model model))
        (failed :set-model (str "Model not found: " provider "/" model-id)))
      (failed :set-model "set-model requires :provider and :model-id strings"))))

(defn- command-cycle-model
  "Cycle to the next scoped/available model (pi: cycle_model); nil data when
   fewer than two models are available."
  [engine _]
  (if-let [model-id (runtime/cycle-model! engine)]
    (let [ag (runtime/agent-state engine)]
      (ok :cycle-model {:model (models/get-model @(:provider ag) model-id)
                        :thinking-level @(:thinking ag)
                        :scoped? (boolean (seq (runtime/scoped-models engine)))}))
    (ok :cycle-model nil)))

(defn- command-get-available-models [_ _]
  (ok :get-available-models {:models (models/get-available)}))

;; ─── Thinking ──────────────────────────────────────────────────────────────

(defn- command-set-thinking-level
  "Set the thinking level (pi: set_thinking_level)."
  [engine command]
  (let [level (:level command)]
    (if (shared/valid-thinking-level? level)
      (do (runtime/set-thinking-level! engine level)
          (ok :set-thinking-level nil))
      (failed :set-thinking-level (str "Unknown thinking level: " (pr-str level))))))

(defn- command-cycle-thinking-level
  "Advance the thinking level (pi: cycle_thinking_level); nil data when the
   model offers fewer than two levels."
  [engine _]
  (if-let [level (runtime/cycle-thinking-level! engine)]
    (ok :cycle-thinking-level {:level level})
    (ok :cycle-thinking-level nil)))

(defn- command-get-available-thinking-levels
  "The current model's supported levels (pi:
   get_available_thinking_levels)."
  [engine _]
  (let [ag (runtime/agent-state engine)
        model (models/get-model @(:provider ag) @(:model ag))]
    (ok :get-available-thinking-levels
        {:levels (if model (shared/get-supported-thinking-levels model) [:off])})))

;; ─── Queue modes ───────────────────────────────────────────────────────────

(defn- command-set-queue-mode
  "The shared steering/follow-up setter path: the runtime setter validates
   the mode, so an invalid one answers a failure (the handler catch)."
  [engine {:keys [type] :as command} setter!]
  (setter! engine (:mode command))
  (ok type nil))

(defn- command-set-steering-mode [engine command]
  (command-set-queue-mode engine command runtime/set-steering-mode!))

(defn- command-set-follow-up-mode [engine command]
  (command-set-queue-mode engine command runtime/set-follow-up-mode!))

;; ─── Compaction ────────────────────────────────────────────────────────────

(defn- command-compact
  "Start a manual compaction (pi: compact). Accepted is not completed: the
   response says :accepted and the outcome arrives as :compaction-end /
   :session-compact(-failed) events. The work runs on a future so the reader
   stays live — like the /compact builtin, abort can cancel it."
  [engine command]
  (let [instructions (:custom-instructions command)]
    (cond
      (and (some? instructions) (not (string? instructions)))
      (failed :compact ":custom-instructions must be a string")

      (runtime/turn-running? engine)
      (failed :compact "Wait for the current response to finish before compacting")

      @(:compacting? (runtime/agent-state engine))
      (failed :compact "Compaction already in progress")

      :else
      (do (future
            (try
              (runtime/compact! engine instructions :manual)
              (catch Exception e
                (debug/log-error "RPC compaction failed: " e))))
          (ok :compact {:accepted true})))))

(defn- command-set-auto-compaction
  "Toggle proactive compaction (pi: set_auto_compaction)."
  [engine command]
  (if (boolean? (:enabled command))
    (do (runtime/set-auto-compact! engine (:enabled command))
        (ok :set-auto-compaction nil))
    (failed :set-auto-compaction "set-auto-compaction requires a boolean :enabled")))

;; ─── Session ───────────────────────────────────────────────────────────────

(defn- command-get-session-stats [engine _]
  (if-let [sess (runtime/session engine)]
    (ok :get-session-stats (session/get-session-stats sess))
    (failed :get-session-stats "No active session")))

(defn- command-export-html
  "Export the session to HTML (pi: export_html); the default path lands in
   the session's runtime cwd."
  [engine command]
  (ok :export-html
      {:path (runtime/export-html! engine {:path (required-string command :output-path)})}))

(defn- command-switch-session
  "Load and switch to a session file (pi: switch_session). The switch emits
   the session lifecycle events."
  [engine command]
  (if-let [path (required-string command :session-path)]
    (let [result (runtime/resume! engine path)]
      (ok :switch-session {:cancelled (boolean (:cancelled result))}))
    (failed :switch-session "switch-session requires a non-empty :session-path string")))

(defn- command-fork
  "Fork the session before the user message ENTRY-ID (pi: fork); :text is
   the message text for the client to restore to its editor."
  [engine command]
  (if-let [entry-id (required-string command :entry-id)]
    (let [result (runtime/fork! engine entry-id)]
      (cond
        (:cancelled result) (ok :fork {:cancelled true})
        (:error result) (failed :fork (if (= :unknown-entry (:error result))
                                        (str "Entry not found: " entry-id)
                                        "Cannot fork session"))
        :else (ok :fork {:cancelled false
                         :text (session/session-entry-text (:entry result))})))
    (failed :fork "fork requires a non-empty :entry-id string")))

(defn- command-clone
  "Duplicate the session at its current position (pi: clone)."
  [engine _]
  (let [result (runtime/clone! engine)]
    (cond
      (:cancelled result) (ok :clone {:cancelled true})
      (:error result) (failed :clone (case (:error result)
                                       :no-leaf "Cannot clone session: no current entry selected"
                                       :not-persisted "Cannot clone session: the session is not persisted yet"
                                       "Cannot clone session"))
      :else (ok :clone {:cancelled false}))))

(defn- command-get-entries
  "The session's entries, all of them or those after :since (pi:
   get_entries), plus the current leaf id."
  [engine command]
  (if-let [sess (runtime/session engine)]
    (let [entries (vec (runtime/entries engine))
          since (required-string command :since)]
      (if (and since (not-any? #(= since (:id %)) entries))
        (failed :get-entries (str "Entry not found: " since))
        (let [entries (if since
                        (vec (rest (drop-while #(not= since (:id %)) entries)))
                        entries)]
          (ok :get-entries {:entries entries :leaf-id @(:leaf-id sess)}))))
    (failed :get-entries "No active session")))

(defn- command-get-tree [engine _]
  (if-let [sess (runtime/session engine)]
    (ok :get-tree {:tree (session/get-tree sess) :leaf-id @(:leaf-id sess)})
    (failed :get-tree "No active session")))

(defn- command-get-last-assistant-text [engine _]
  (ok :get-last-assistant-text
      {:text (some-> (runtime/session engine) session/get-last-assistant-text)}))

(defn- command-set-session-name
  "Set the session display name (pi: set_session_name); the append + event
   logic is shared with the /name builtin."
  [engine command]
  (let [name (:name command)]
    (if (and (string? name) (not (str/blank? name)))
      (do (runtime/set-session-name! engine name)
          (ok :set-session-name nil))
      (failed :set-session-name "Session name cannot be empty"))))

;; ─── Messages ──────────────────────────────────────────────────────────────

(defn- command-get-messages [engine _]
  (ok :get-messages {:messages (vec (runtime/messages engine))}))

;; ─── Commands (available for invocation via prompt) ────────────────────

(defn- slash-command
  "A registry/prompt/skill entry in the get_commands shape (pi:
   RpcSlashCommand — kmet adds :builtin as a source and keeps the
   :argument-hint). COMMAND sets :extension-handler, which is how the
   registry distinguishes extension commands from builtins."
  [source command]
  (cond-> {:name (:name command) :source source}
    (:description command) (assoc :description (:description command))
    (:argument-hint command) (assoc :argument-hint (:argument-hint command))))

(defn- command-get-commands [_ _]
  (ok :get-commands
      {:commands (vec (concat (map #(slash-command (if (:extension-handler %) :extension :builtin) %)
                                   (commands/get-commands :rpc))
                              (map #(slash-command :prompt %)
                                   (prompts/as-command-maps (prompts/get-prompt-templates)))
                              (map #(slash-command :skill %)
                                   (skills/as-command-maps (skills/get-skills)))))}))

(defn- command-table
  "The dispatch table; the prompt row carries COMMAND-UI (the portable
   builtin dispatch, nil when there is no UI port)."
  [command-ui]
  {:prompt (fn [engine command] (command-prompt engine command command-ui))
   :respond command-respond
   :steer command-steer
   :follow-up command-follow-up
   :abort command-abort
   :clear-queue command-clear-queue
   :new-session command-new-session
   :get-state command-get-state
   :set-model command-set-model
   :cycle-model command-cycle-model
   :get-available-models command-get-available-models
   :set-thinking-level command-set-thinking-level
   :cycle-thinking-level command-cycle-thinking-level
   :get-available-thinking-levels command-get-available-thinking-levels
   :set-steering-mode command-set-steering-mode
   :set-follow-up-mode command-set-follow-up-mode
   :compact command-compact
   :set-auto-compaction command-set-auto-compaction
   :get-session-stats command-get-session-stats
   :export-html command-export-html
   :switch-session command-switch-session
   :fork command-fork
   :clone command-clone
   :get-entries command-get-entries
   :get-tree command-get-tree
   :get-last-assistant-text command-get-last-assistant-text
   :set-session-name command-set-session-name
   :get-messages command-get-messages
   :get-commands command-get-commands})

(defn- handle-command
  "Handle one parsed command record, returning the response — or nil for a
   fire-and-forget command (:respond), which pi answers nothing to. A
   handler throw is the command's failure, not the process's."
  [table engine command]
  (cond
    (not (map? command))
    (failed nil "A command must be an EDN map")

    (nil? (:type command))
    (failed nil "A command record requires a :type")

    :else
    (if-let [handler (get table (:type command))]
      (try
        (handler engine command)
        (catch Exception e
          (failed (:type command) (or (ex-message e) (str e)))))
      (failed (:type command) (str "Unknown command: " (pr-str (:type command)))))))

;; ─── Transport ─────────────────────────────────────────────────────────────

(defn- parse-line
  "Parse one stdin LINE as exactly one EDN form (pi: JSON.parse rejects
   trailing content); returns {:command value} or {:error message}."
  [line]
  (try
    (let [rdr (java.io.PushbackReader. (java.io.StringReader. line))
          value (edn/read {:eof ::eof} rdr)]
      (cond
        (= ::eof value) {:error "EOF while reading"}
        (= ::eof (edn/read {:eof ::eof} rdr)) {:command value}
        :else {:error "Unexpected content after the command record"}))
    (catch Exception e
      {:error (or (ex-message e) (str e))})))

(defn- serve
  "Serve commands from RDR until EOF. Returns nil."
  [table engine rdr out]
  (loop []
    (when-let [line (.readLine rdr)]
      (let [{:keys [command error]} (parse-line line)]
        (if error
          ;; a malformed record answers a parse response without an id
          ;; (pi discipline: there is nothing to correlate it with)
          (write-record! out (failed :parse (str "Failed to parse command: " error)))
          ;; a fire-and-forget command (respond) answers nothing
          (when-let [response (handle-command table engine command)]
            (write-record! out (correlated response (:id command))))))
      (recur))))

(defn run
  "Run RPC mode: EDN commands on IN, protocol records on OUT. Resolves the
   session like print mode, bootstraps the engine bound to extensions as
   :rpc with the RPC UI port, streams wire events, serves commands until
   stdin closes, then disposes.

   opts: :config, :session, :continue, :model, :provider, :in, :out (the
   last two default to *in*/*out*; tests pass a StringReader/StringWriter).
   Returns :ok on an orderly shutdown, nil when startup failed (the caller
   exits 1)."
  [opts]
  (let [config (cfg/apply-cli-overrides (or (:config opts) (cfg/load-config :no-env? true)) opts)
        in (or (:in opts) *in*)
        out (or (:out opts) *out*)]
    ;; the portable builtins this front end dispatches over prompt (6.3) and
    ;; reports through get-commands; registration is idempotent
    (builtins/register-runtime-commands!)
    (if-let [session (runtime/resolve-run-session config opts)]
      (let [;; the port is installed by bootstrap but needs the engine for a
            ;; dialog timeout's auto-answer; the ref closes the cycle
            engine-ref (atom nil)
            port (ui-port engine-ref out)
            engine (runtime/bootstrap config {:session session
                                              :model (:model opts)
                                              :provider (:provider opts)
                                              :mode :rpc
                                              :has-ui true
                                              :ui-context port})
            table (command-table (command-ui port))]
        (reset! engine-ref engine)
        (try
          (let [unsubscribe (runtime/subscribe! engine (fn [event]
                                                         (write-record! out (event-bus/wire-event event))))]
            (try
              (with-open [rdr (io/reader in)]
                (serve table engine rdr out))
              (finally (unsubscribe))))
          (finally (runtime/dispose! engine)))
        :ok)
      (do (binding [*out* *err*]
            (println (str "No session found matching '" (:session opts) "'")))
          nil))))
