(ns kmet.app.commands.builtins
  "Engine-portable builtin slash commands (engine.md step 4): they run
   against the command ctx's :runtime and the shared command-UI
   capabilities (:message, :info, :warning), so any front end can dispatch
   them. The interactive mode registers its view-bound commands itself
   (kmet.modes.interactive.commands)."
  (:require [clojure.string :as str]
            [kmet.debug :as debug]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.session-export :as session-export]
            [kmet.app.session-runtime :as runtime]
            [kmet.app.tools.core :as tools]))

(defn- handle-continue
  [{:keys [ui] :as ctx} _]
  (let [engine (:runtime ctx)
        agent-state (runtime/agent-state engine)]
    (cond
      ;; A run may have ended in :error (network failure, retries exhausted)
      ;; — that's exactly when /continue is useful, so only actively-running
      ;; states refuse. Both the UI turn flag (set synchronously on submit)
      ;; and the agent status (:thinking/:executing, set by the run future)
      ;; are checked.
      (or (runtime/turn-running? engine)
          (contains? #{:thinking :executing} @(:status agent-state)))
      ((:info ui) "Continue" "Wait for the current response to finish before continuing.")

      @(:compacting? agent-state)
      ((:info ui) "Continue" "Wait for the in-progress compaction to finish before continuing.")

      (empty? (agent/get-context agent-state))
      ((:info ui) "Continue" "No conversation to continue.")

      :else
      (do (debug/log "/continue command")
          (runtime/prompt! engine {})))))

(defn- handle-name
  [{:keys [ui] :as ctx} args]
  (let [engine (:runtime ctx)
        sess (runtime/session engine)]
    (if (nil? sess)
      ((:message ui) "No active session.")
      (if (seq args)
        (let [sanitized (session/sanitize-session-name args)]
          (session/append-session-info! sess sanitized)
          ;; pi: session_info_changed — extensions track the display name
          (event-bus/emit-event!
           {:type :session-info-changed
            :session-file (:file sess)
            :name sanitized})
          ((:set-title ui))
          (when-not (= args sanitized)
            ;; pi: warn when normalization changed the input
            ((:warning ui) (str "Session name was normalized from " (pr-str args)
                                " to " (pr-str sanitized))))
          ((:info ui) "Name" (str "Session name set: " sanitized)))
        (if-let [current (session/get-session-name sess)]
          ((:info ui) "Name" (str "Session name: " current))
          ((:warning ui) "Usage: /name <name>"))))))

(defn- handle-export
  [{:keys [ui] :as ctx} args]
  (let [engine (:runtime ctx)
        sess (runtime/session engine)
        arg (session-export/parse-path-argument args)]
    (cond
      (nil? sess)
      ((:info ui) "Export" "No active session.")

      (str/ends-with? (str/lower-case (or arg "")) ".jsonl")
      ((:info ui) "Export" "JSONL export is not supported — kmet sessions are EDN-only. Use /export or /export <path.html>.")

      :else
      (try
        (let [ag (runtime/agent-state engine)
              system-prompt (or @(:system-prompt-override ag) @(:system ag))
              tool-defs (vals (tools/get-all-tools))
              opts (cond-> (when arg {:path arg})
                     ;; without an explicit path the export lands in the
                     ;; session's runtime cwd (where its tools work), not the
                     ;; launch directory
                     (not arg) (assoc :cwd (runtime/cwd engine))
                     system-prompt (assoc :system-prompt system-prompt)
                     (seq tool-defs) (assoc :tools tool-defs))
              path (session-export/export-to-html! sess opts)]
          ((:info ui) "Export" (str "Session exported to: " path)))
        (catch Exception e
          ((:info ui) "Export" (str "Failed to export session: "
                                    (or (ex-message e) (str e)))))))))

(defn- handle-compact
  [{:keys [ui] :as ctx} args]
  (let [engine (:runtime ctx)
        agent-state (runtime/agent-state engine)
        instructions (when (seq args) args)]
    (cond
      (runtime/turn-running? engine)
      ((:info ui) "Compact" "Wait for the current response to finish before compacting.")

      @(:compacting? agent-state)
      ((:info ui) "Compact" "Compaction already in progress.")

      :else
      ;; Runs on a future so the input thread stays live and escape can
      ;; cancel the compaction (pi: session.compact is async). The
      ;; :compaction-end event reports an aborted compaction; only the
      ;; result replies go here.
      (future
        (try
          (let [result (runtime/compact! engine instructions :manual)]
            (when-not (or (= :aborted result) (= :failed result))
              ;; pi: handleCompactCommand ignores the thrown compaction error
              ;; — compaction_end surfaces it (showError for manual); the
              ;; future only replies for success and nothing-to-compact.
              ((:info ui) "Compact" (if result
                                      "Session compacted."
                                      "Nothing to compact (session too small)."))))
          (catch Exception e
            ;; pi: handleCompactCommand catches and ignores (the events carry
            ;; the failure) — but a future that swallows the exception hides
            ;; real bugs, so log it.
            (debug/log-error "manual compaction failed: " e)))))))

(defn register-runtime-commands!
  "Register the engine-portable builtins (untagged: every front end
   dispatches them). A front end calls this with its builtin registration."
  []
  (commands/register-builtin!
   {:name "continue"
    :description "Continue where the agent left off (e.g. after a network error)"
    :handler handle-continue})
  (commands/register-builtin!
   {:name "name"
    :description "Set session display name"
    :argument-hint "<name>"
    :handler handle-name})
  (commands/register-builtin!
   {:name "export"
    :description "Export session to HTML (JSONL is not supported by design)"
    :argument-hint "<path>"
    :handler handle-export})
  (commands/register-builtin!
   {:name "compact"
    :description "Manually compact the session context"
    :argument-hint "<instructions>"
    :handler handle-compact}))
