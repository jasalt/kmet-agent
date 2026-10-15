(ns kmet.modes.rpc
  "RPC mode — headless, long-lived: EDN commands on stdin, protocol records
   on stdout (engine.md step 6; pi: modes/rpc/rpc-mode.ts). A command is a
   map with a :type and an optional string :id the matching response
   repeats; events are the print-mode wire projection
   (event-bus/wire-event); extension UI dialogs arrive as
   {:type :ui-request ...} records and are answered with
   {:type :respond ...} (the runtime's request-ui!/respond! pair). stdout
   carries protocol only — kmet.debug writes files and nothing else in the
   runtime prints."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.prompts :as prompts]
            [kmet.app.session :as session]
            [kmet.app.session-runtime :as runtime]
            [kmet.app.skills :as skills]
            [kmet.config :as cfg]))

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
  ([command] {:type :response :command command :success true})
  ([command data] {:type :response :command command :success true :data data}))

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

;; ─── Commands ──────────────────────────────────────────────────────────────

(defn- command-prompt
  "Start, steer, or handle a prompt (pi: prompt). The response reports the
   disposition — accepted is not completed; the client waits for
   :agent-settled."
  [engine command]
  (if-let [text (message-of command)]
    (let [result (runtime/submit! engine text {:expand? true})]
      (ok :prompt {:disposition (case (:action result)
                                  :started :started
                                  :handled :handled
                                  :queued)}))
    (failed :prompt "prompt requires a non-empty :message string")))

(defn- command-steer
  "Queue a message for mid-turn injection (pi: steer)."
  [engine command]
  (if-let [text (message-of command)]
    (do (runtime/steer! engine (expand-queued-text text))
        (ok :steer {:disposition :queued}))
    (failed :steer "steer requires a non-empty :message string")))

(defn- command-follow-up
  "Queue a message for after the run settles (pi: follow_up)."
  [engine command]
  (if-let [text (message-of command)]
    (do (runtime/follow-up! engine (expand-queued-text text))
        (ok :follow-up {:disposition :queued}))
    (failed :follow-up "follow-up requires a non-empty :message string")))

(defn- command-abort
  "Cancel the in-flight run and wait (bounded) for the session to become
   idle before responding (pi: abort)."
  [engine _]
  (runtime/cancel! engine)
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
        {:keys [steering follow-up]} (:queues snap)]
    (ok :get-state
        (cond-> {:model (:model snap)
                 :provider (:provider snap)
                 :thinking-level (:thinking snap)
                 :turn-running? (runtime/turn-running? engine)
                 :compacting? @(:compacting? (runtime/agent-state engine))
                 :queued-count (+ (count steering) (count follow-up))
                 :last-outcome (:last-outcome snap)}
          sess (assoc :session {:id (:id sess)
                                :file (:file sess)
                                :name (session/get-session-name sess)
                                :message-count (session/get-message-count sess)})))))

(def ^:private command-table
  {:prompt command-prompt
   :steer command-steer
   :follow-up command-follow-up
   :abort command-abort
   :clear-queue command-clear-queue
   :new-session command-new-session
   :get-state command-get-state})

(defn- handle-command
  "Handle one parsed command record, returning the response (never nil).
   A handler throw is the command's failure, not the process's."
  [engine command]
  (cond
    (not (map? command))
    (failed nil "A command must be an EDN map")

    (nil? (:type command))
    (failed nil "A command record requires a :type")

    :else
    (if-let [handler (get command-table (:type command))]
      (try
        (handler engine command)
        (catch Exception e
          (failed (:type command) (or (ex-message e) (str e)))))
      (failed (:type command) (str "Unknown command: " (pr-str (:type command)))))))

;; ─── Transport ─────────────────────────────────────────────────────────────

(defn- parse-line
  "Parse one stdin LINE; returns {:command value} or {:error message}."
  [line]
  (try
    {:command (edn/read-string line)}
    (catch Exception e
      {:error (or (ex-message e) (str e))})))

(defn- serve
  "Serve commands from RDR until EOF. Returns nil."
  [engine rdr out]
  (loop []
    (when-let [line (.readLine rdr)]
      (let [{:keys [command error]} (parse-line line)]
        (if error
          ;; a malformed record answers a parse response without an id
          ;; (pi discipline: there is nothing to correlate it with)
          (write-record! out (failed :parse (str "Failed to parse command: " error)))
          (write-record! out (correlated (handle-command engine command) (:id command)))))
      (recur))))

(defn run
  "Run RPC mode: EDN commands on IN, protocol records on OUT. Resolves the
   session like print mode, bootstraps the engine bound to extensions as
   :rpc, streams wire events, serves commands until stdin closes, then
   disposes.

   opts: :config, :session, :continue, :model, :provider, :in, :out (the
   last two default to *in*/*out*; tests pass a StringReader/StringWriter).
   Returns :ok on an orderly shutdown, nil when startup failed (the caller
   exits 1)."
  [opts]
  (let [config (cfg/apply-cli-overrides (or (:config opts) (cfg/load-config :no-env? true)) opts)
        in (or (:in opts) *in*)
        out (or (:out opts) *out*)
        session (runtime/resolve-run-session config opts)]
    (if (nil? session)
      (do (binding [*out* *err*]
            (println (str "No session found matching '" (:session opts) "'")))
          nil)
      (let [engine (runtime/bootstrap config {:session session
                                              :model (:model opts)
                                              :provider (:provider opts)
                                              :mode :rpc
                                              ;; the RPC UI port (dialog and
                                              ;; output records) lands with
                                              ;; step 6c; until then extensions
                                              ;; see the headless defaults
                                              :has-ui false})]
        (try
          (let [unsubscribe (runtime/subscribe! engine (fn [event]
                                                         (write-record! out (event-bus/wire-event event))))]
            (try
              (with-open [rdr (io/reader in)]
                (serve engine rdr out))
              (finally (unsubscribe))))
          (finally (runtime/dispose! engine)))
        :ok))))
