(ns kmet.app.modes.rpc
  "RPC mode as a row: EDN commands on stdin, protocol records on stdout, and the
   run's events as the wire projection (pi: modes/rpc). It is print mode's
   sibling — one agent, one stream, the same `event-bus/wire-event` — with the
   run driven by commands instead of by a prompt on the command line.

   **A record in, a record out.** A command is a map with a `:type` and an
   optional `:id` the matching response repeats; a command that starts work
   answers `:success true` and the outcome arrives as events, so `:started` is
   not `:settled` and the client waits for `:agent-settled`. A malformed line
   answers a `:parse` failure, a command the table knows nothing about answers
   `\"Unknown command\"`, and one this mode does not implement yet answers the
   landing that fills it — never a silent success, never a wrong answer.

   **The surface is a front end's.** kmet1's rpc mode binds `:mode :rpc`,
   `has-ui true` and a port that answers extension UI calls over the wire: the
   fire-and-forget channels here, dialogs left to landing 6's
   `:request`/`:response` pair (this mode has one agent and no dialog
   correlation, and a bridged dialog that never got an answer would be worse
   than the headless default).

   **The commands are the ones kmet2's services answer.** A prompt, a steer, a
   follow-up, an abort, the live state, the session's entries, the model
   catalog: each is a call on the `:agents` handle or the `:sessions` log. The
   session lifecycle, model switching, compaction and the dialog pair need a
   per-agent surface kmet2 does not have yet, and each names its landing
   (`unsupported`) instead of pretending. The server row
   (`kmet.app.modes.server`) serves this same protocol over a socket, one agent
   per connection, out of this namespace's `handle-line`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.agent.event-bus :as event-bus]
            [kmet.agent.extensions.core :as extensions]
            [kmet.ai.models :as models]
            [kmet.app.events :as events]
            [kmet.app.front-end :as front-end]
            [kmet.app.modes.out :as out]
            [kmet.srce.core :as srce]))

;; ── protocol records ──────────────────────────────────────────────────────

(defn- ok
  "A success response carrying DATA as its `:data` payload; nil DATA omits the
   key (pi's data-less success responses)."
  [command data]
  (cond-> {:type :response :command command :success true}
    (some? data) (assoc :data data)))

(defn- failed
  "A failure response: COMMAND names the command that failed (nil when the
   record carried none), MESSAGE is the reason a client can read."
  [command message]
  {:type :response :command command :success false :error (str message)})

(defn- correlated
  "Attach the request's `:id` when it had one (an absent id stays absent)."
  [record id]
  (cond-> record (some? id) (assoc :id id)))

(defn- message-of
  "The command's non-empty `:message`, or nil."
  [command]
  (let [message (:message command)]
    (when (and (string? message) (not (str/blank? message)))
      message)))

;; ── the extension UI port ────────────────────────────────────────────────

(defn- write-ui-record!
  "An extension UI record — one of the port's fire-and-forget channels. Every
   one carries its own id so a client can correlate or dedupe."
  [out- record]
  (out/record! out- (assoc record :type :ui-request :id (str (random-uuid)))))

(defn- ui-port
  "The RPC UI port: the headless answers plus what the protocol carries.
   Widget content cannot cross the wire, so only nil or string lines are
   forwarded (pi: component factories are ignored) and the TUI-only
   capabilities stay degraded. `:request` is the headless one: a dialog is
   landing 6's pair, not this mode's."
  [out-]
  (let [set-editor-text (fn [text]
                          (write-ui-record! out- {:method :set-editor-text :text text}))]
    (assoc (extensions/headless-ui-port)
           :notify (fn [message type]
                     (write-ui-record! out- (cond-> {:method :notify :message message}
                                              type (assoc :notify-type type))))
           :set-status (fn [key text]
                         (write-ui-record! out- {:method :set-status
                                                 :status-key key
                                                 :status-text text}))
           :set-widget (fn [key content {:keys [placement]}]
                         (when (or (nil? content)
                                   (and (sequential? content) (every? string? content)))
                           (write-ui-record! out- (cond-> {:method :set-widget
                                                           :widget-key key
                                                           :widget-lines content}
                                                    placement (assoc :widget-placement placement)))))
           :set-title (fn [title]
                        (write-ui-record! out- {:method :set-title :title title}))
           :chat-info (fn [label content]
                        (write-ui-record! out- (cond-> {:method :chat-info :content content}
                                                 label (assoc :label label))))
           :set-editor-text set-editor-text
           ;; pi: pasteToEditor delegates to setEditorText
           :paste-to-editor set-editor-text)))

;; ── commands ──────────────────────────────────────────────────────────────

(defn- running?
  [agents handle]
  (= :running ((:status agents) handle)))

(defn- enqueue!
  "Queue TEXT for BEHAVIOR — `:steer` for the next step, `:follow-up` for the
   next turn. The two targets are the loop's queues; the registry's ops are what
   reach them."
  [{:keys [agents handle]} behavior text]
  ((get agents (if (= :steer behavior) :steer! :follow-up!)) handle text))

(defn- command-prompt
  "Start, steer or queue a prompt (pi: prompt). While a run is live the client
   must choose `:streaming-behavior` — pi errors without one, because the two
   targets mean different things and guessing would drop the message or
   deliver it at the wrong boundary."
  [state command]
  (let [{:keys [agents handle]} state
        text (message-of command)
        behavior (:streaming-behavior command)
        running? (running? agents handle)]
    (cond
      ;; the wire carries text: kmet2's handle takes a string, and an image
      ;; quietly dropped would be a wrong answer to a client that sent one
      (:images command)
      (failed :prompt "prompt does not support :images yet")

      (nil? text)
      (failed :prompt "prompt requires a non-empty :message string")

      (and (some? behavior) (not (#{:steer :follow-up} behavior)))
      (failed :prompt "streaming-behavior must be :steer or :follow-up")

      (and running? (nil? behavior))
      (failed :prompt (str "The agent is already running; specify"
                           " :streaming-behavior :steer or :follow-up"))

      running?
      (do (enqueue! state behavior text)
          (ok :prompt {:disposition :queued}))

      :else
      (do ((:send! agents) handle text {:target :next-turn})
          (ok :prompt {:disposition :started})))))

(defn- command-queue
  "The `:steer` / `:follow-up` commands: the queues carry no image blocks, so
   reject `:images` rather than drop them, and answer with the queued
   disposition. The command's own `:type` is the behavior it queues for."
  [state command]
  (let [type (:type command)
        text (message-of command)]
    (cond
      (:images command)
      (failed type (str (name type) " does not support :images yet"))

      (nil? text)
      (failed type (str (name type) " requires a non-empty :message string"))

      :else
      (do (enqueue! state type text)
          (ok type {:disposition :queued})))))

(defn- command-abort
  "Cancel the in-flight run and answer whether the agent is idle now (pi:
   abort). An idle abort is a no-op: cancel! would drop nothing and report an
   abort that never happened.

   The answer is a read, not a wait. The handle's cancel drops its run flag on
   the calling thread, so this mode answers `:idle true` by the time the
   cancel returns; a front end whose cancel is only a request reports `:idle
   false`, and the client waits for `:agent-settled` — the same rule a prompt's
   `:started` follows. kmet1 polls its engine for the same flag (plus a
   compaction it knows about); kmet2's handle has neither at stake here, and a
   wait here would be the reader thread's time, which is the one thread an
   abort must not occupy."
  [{:keys [agents handle]} _command]
  (when (running? agents handle)
    ((:cancel! agents) handle {:kind :user}))
  (ok :abort {:idle (not (running? agents handle))}))

(defn- pending-count
  "How many messages the agent has queued (the durable inbox, by target)."
  [agents scope handle]
  (reduce + 0 (map count (vals ((:inbox agents) scope handle)))))

(defn- command-get-state
  "The live state a client polls after a reconnect (pi: get-state): the agent's
   model and thinking level, whether a turn is in flight, what is queued, and
   the session it all belongs to."
  [{:keys [agents scope handle sessions]} _command]
  (let [state (:agent handle)
        session (:session state)
        ;; the state is live: its model, provider, thinking level and run
        ;; knobs move while a run cycles them, so every value here is read
        ;; through its atom
        cfg @(:cfg state)]
    (ok :get-state
        (cond-> {:model @(:model state)
                 :provider @(:provider state)
                 :thinking-level @(:thinking state)
                 :turn-running? (running? agents handle)
                 :steering-mode (:steering-mode cfg)
                 :follow-up-mode (:follow-up-mode cfg)
                 :auto-compaction-enabled (boolean (:auto-compact cfg))
                 :pending-message-count (pending-count agents scope handle)}
          session (assoc :session
                         {:id (:id session)
                          :file (:file session)
                          :message-count (count ((:entries sessions) session))})))))

(defn- command-get-entries
  "The session's entries, the durable log the run appends (pi: get-entries).
   kmet2's log is the entry list itself — there is no leaf id to report, so the
   answer is the whole branch."
  [{:keys [handle sessions]} _command]
  (if-let [session (:session (:agent handle))]
    (ok :get-entries {:entries (vec ((:entries sessions) session))})
    (failed :get-entries "No active session")))

(defn- command-get-available-models
  "The models this process can run (pi: get-available-models): the catalog's
   authenticated entries — the same snapshot a `--model` pattern resolves
   against."
  [_state _command]
  (ok :get-available-models {:models (models/get-available)}))

(def ^:private unsupported
  "kmet1's other commands, each with the reason it has no answer here and the
   landing that gives it one. A named refusal: `\"Unknown command\"` would read
   as a typo, and a silent success would be a wrong answer.

   | group | why not yet |
   |---|---|
   | session lifecycle | one agent per mode; switching, forking and cloning need the session work landing 6 owns |
   | model and thinking setters | an agent's model is its creation option; a per-agent setter is landing 6's surface |
   | queue modes | the queue belongs to the agent's loop; draining it from the wire is landing 6's queue primitive |
   | compaction | the loop's compaction seam is landing 7's |
   | commands listing | the slash-command registry's wire shape moves with the dispatcher |"
  {:new-session "one agent serves this mode; creating and switching sessions is landing 6's server"
   :switch-session "one agent serves this mode; switching sessions is landing 6's server"
   :fork "session forking needs the branch-aware log landing 6 adds"
   :clone "session cloning needs the branch-aware log landing 6 adds"
   :get-tree "the entry tree is landing 6's session surface"
   :get-last-assistant-text "the transcript view is landing 6's session surface"
   :get-messages "the transcript view is landing 6's session surface"
   :set-session-name "session naming is landing 6's session surface"
   :get-session-stats "session stats are landing 6's session surface"
   :export-html "HTML export is landing 6's session surface"

   :set-model "an agent's model is its creation option; switching it is landing 6's per-agent surface"
   :cycle-model "an agent's model is its creation option; switching it is landing 6's per-agent surface"
   :set-thinking-level "an agent's thinking level is its creation option; changing it is landing 6's per-agent surface"
   :cycle-thinking-level "an agent's thinking level is its creation option; changing it is landing 6's per-agent surface"
   :get-available-thinking-levels "the per-model thinking levels arrive with landing 6's model surface"

   :set-steering-mode "the queue belongs to the agent's loop; its modes are landing 6's queue primitive"
   :set-follow-up-mode "the queue belongs to the agent's loop; its modes are landing 6's queue primitive"
   :clear-queue "the queue belongs to the agent's loop; draining it from the wire is landing 6's queue primitive"

   :compact "compaction rides the loop's compaction seam (landing 7)"
   :set-auto-compaction "compaction rides the loop's compaction seam (landing 7)"

   :respond "dialogs are landing 6's :request/:response pair on the wire"
   :get-commands "the slash-command registry's wire shape moves with the dispatcher"})

(defn- handlers
  "The dispatch table for STATE — the agent's door: the registry, the log, and
   the handle this mode owns."
  [state]
  {:prompt #(command-prompt state %)
   :steer #(command-queue state %)
   :follow-up #(command-queue state %)
   :abort #(command-abort state %)
   :get-state #(command-get-state state %)
   :get-entries #(command-get-entries state %)
   :get-available-models #(command-get-available-models state %)})

(defn handle-command
  "Handle one parsed command, returning the response — never nil: every command
   is answered. A handler that throws is that command's failure, not the
   mode's."
  [state command]
  (let [type (:type command)]
    (cond
      (not (map? command))
      (failed nil "A command must be an EDN map")

      (nil? type)
      (failed nil "A command record requires a :type")

      :else
      (if-let [handler (get (handlers state) type)]
        (try
          (handler command)
          (catch Throwable t
            (failed type (or (ex-message t) (str t)))))
        (if-let [reason (get unsupported type)]
          (failed type (str (name type) " is not implemented in rpc mode yet — " reason))
          (failed type (str "Unknown command: " (pr-str type))))))))

;; ── transport ─────────────────────────────────────────────────────────────

(defn parse-line
  "Parse one line as exactly one EDN form (pi: JSON.parse rejects trailing
   content); returns `{:command value}` or `{:error message}`. Public because the
   server mode asks what a record *is* before dispatching it: a `:response` from
   a client answers a dialog, every other record is a command."
  [line]
  (try
    (let [rdr (java.io.PushbackReader. (java.io.StringReader. line))
          value (edn/read {:eof ::eof} rdr)]
      (cond
        (= ::eof value) {:error "EOF while reading"}
        (= ::eof (edn/read {:eof ::eof} rdr)) {:command value}
        :else {:error "Unexpected content after the command record"}))
    (catch Throwable t
      {:error (or (ex-message t) (str t))})))

(defn parse-failure
  "The response a line that is not one EDN map answers: a parse failure carrying
   no id, because there is nothing to correlate it with."
  [error]
  (failed :parse (str "Failed to parse command: " error)))

(defn handle-record
  "RECORD — already parsed — served against STATE, with the request's `:id`
   echoed when it carried one. The transport parses and this answers: a line
   crossing a wire is parsed once, by whoever read it (`handle-line` for stdio,
   the server for a socket, which must classify the record before serving it)."
  [state record]
  (correlated (handle-command state record) (:id record)))

(defn handle-line
  "LINE served against STATE — one record out, whatever LINE was: the command's
   response, a parse failure, or a command answered with the `:id` it carried.

   STATE is `{:agents :sessions :scope :handle}`: the registry, the log, the app
   scope, and the one handle this door serves."
  [state line]
  (let [{:keys [command error]} (parse-line line)]
    (if error
      (parse-failure error)
      (handle-record state command))))

(defn- serve-commands
  "Serve commands from IN until it closes. Only OUT writes: the reader here and
   the loop's event listener share it, and `out/record!` is what keeps two
   records from interleaving."
  [state in out-]
  (with-open [rdr (io/reader in)]
    (loop []
      (when-let [line (.readLine rdr)]
        (out/record! out- (handle-line state line))
        (recur)))))

;; ── the row ───────────────────────────────────────────────────────────────

(defn- serve
  "The `:mode-rpc` row's apply: create the mode's one agent, bind the front-end
   surface, stream the wire, then serve commands until stdin closes.

   A mode runs only when the entry point hands it somewhere to report
   (`:on-result`): `--dump-state` mounts this row without one, and a diagnostic
   mount must prove the row composes without creating an agent or reading
   stdin. Its input is `*in*` and its stream `*out*`, both read here — inside
   the entry point's bindings — so a caller drives the mode by binding them, not
   by smuggling a reader through the row's config."
  [ctx config]
  (let [scope (:parent ctx)
        agents (srce/get ctx :agents)
        sessions (srce/get ctx :sessions)
        {:keys [on-result]} config]
    (when on-result
      (let [out- *out*
            in *in*
            dispose (events/on-every scope (fn [event]
                                             (out/record! out- (event-bus/wire-event event))))
            ;; kmet1's rpc mode binds exactly this: a front end with a port,
            ;; whose extension UI calls travel the protocol
            restore (front-end/install! {:mode :rpc
                                         :has-ui true
                                         :ui (ui-port out-)})]
        (future
          (on-result
           {:exit (try
                    ;; the invocation's model and provider are the agent's
                    ;; own options, exactly as in print mode: the driver
                    ;; resolves a `--model` pattern per creation
                    (let [handle ((:create! agents) scope (select-keys config [:model :provider]))]
                      (try
                        (serve-commands {:agents agents :sessions sessions
                                         :scope scope :handle handle}
                                        in out-)
                        0
                        (finally ((:dispose! agents) handle))))
                    (catch Throwable t
                      (out/error! (or (ex-message t) (str t)))
                      1))}))
        (fn []
          (dispose)
          (restore))))))

(srce/defplugin plugin
  {:name :mode-rpc
   :inject [:agents :sessions]
   :apply serve})
