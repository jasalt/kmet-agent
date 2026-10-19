(ns kmet.agent.commands.builtins
  "Engine-portable builtin slash commands: they run
   against the command ctx's :runtime and the shared command-UI
   capabilities (:message, :info, :warning, :set-title), so any front end
   can dispatch them. The interactive mode registers its view-bound
   commands itself (kmet.agent.modes.interactive.commands)."
  (:require [clojure.string :as str]
            [kmet.agent.debug :as debug]
            [kmet.agent.commands :as commands]
            [kmet.agent.loop.state :as agent-state]
            [kmet.agent.runtime :as app-runtime]
            [kmet.agent.session.core :as session]
            [kmet.agent.session.export :as session-export]
            [kmet.agent.session.runtime :as runtime]))

(defn- out
  "Call a shared command-UI capability; an absent capability is inert (a
   front end need not implement every output channel)."
  [ui capability & args]
  (when-let [f (get ui capability)]
    (apply f args)))

(defn- handle-continue
  [{:keys [ui] engine :runtime} _]
  (let [agent-state (runtime/agent-state engine)]
    (cond
      ;; A run may have ended in :error (network failure, retries exhausted)
      ;; — that's exactly when /continue is useful, so only actively-running
      ;; states refuse. Both the UI turn flag (set synchronously on submit)
      ;; and the agent status (:thinking/:executing, set by the run future)
      ;; are checked.
      (or (runtime/turn-running? engine)
          (contains? #{:thinking :executing} @(:status agent-state)))
      (out ui :info "Continue" "Wait for the current response to finish before continuing.")

      @(:compacting? agent-state)
      (out ui :info "Continue" "Wait for the in-progress compaction to finish before continuing.")

      (empty? (agent-state/get-context agent-state))
      (out ui :info "Continue" "No conversation to continue.")

      :else
      (do (debug/log "/continue command")
          (runtime/prompt! engine {})))))

(defn- handle-name
  [{:keys [ui] engine :runtime} args]
  (let [sess (runtime/session engine)]
    (cond
      (nil? sess)
      (out ui :message "No active session.")

      (seq args)
      (let [sanitized (runtime/set-session-name! engine args)]
        (out ui :set-title)
        (when-not (= args sanitized)
          ;; pi: warn when normalization changed the input
          (out ui :warning (str "Session name was normalized from " (pr-str args)
                                " to " (pr-str sanitized))))
        (out ui :info "Name" (str "Session name set: " sanitized)))

      :else
      (if-let [current (session/get-session-name sess)]
        (out ui :info "Name" (str "Session name: " current))
        (out ui :warning "Usage: /name <name>")))))

(defn- handle-export
  [{:keys [ui] engine :runtime} args]
  (let [sess (runtime/session engine)
        arg (session-export/parse-path-argument args)]
    (cond
      (nil? sess)
      (out ui :info "Export" "No active session.")

      (str/ends-with? (str/lower-case (or arg "")) ".jsonl")
      (out ui :info "Export" "JSONL export is not supported — kmet sessions are EDN-only. Use /export or /export <path.html>.")

      :else
      (try
        (let [path (runtime/export-html! engine {:path arg})]
          (out ui :info "Export" (str "Session exported to: " path)))
        (catch Exception e
          (out ui :info "Export" (str "Failed to export session: "
                                      (or (ex-message e) (str e)))))))))

(defn- handle-compact
  [{:keys [ui] engine :runtime} args]
  (let [instructions (when (seq args) args)
        outcome (runtime/request-compaction!
                 engine instructions
                 {:on-complete (fn [result]
                                 (when-not (= :aborted result)
                                   ;; pi: handleCompactCommand ignores the
                                   ;; thrown compaction error — compaction_end
                                   ;; surfaces it (showError for manual); the
                                   ;; completion reply is only for success and
                                   ;; nothing-to-compact.
                                   (out ui :info "Compact" (if result
                                                             "Session compacted."
                                                             "Nothing to compact (session too small)."))))})]
    (case outcome
      ;; A run in flight defers the compaction (kmet's cancel is cooperative,
      ;; so compacting under a live run would race it — pi instead aborts the
      ;; run first).
      :scheduled
      (out ui :info "Compact"
           "Compaction scheduled — it will run when the current response finishes.")

      :compacting
      (out ui :info "Compact" "Compaction already in progress.")

      ;; :started — the completion reply arrives through :on-complete
      nil)))

(defn- handle-units
  "Show every store owner: its address and the keys registered under it, one
   flat line each (registries, extension registrations; scope.md §2, §6).
   The session engine's node when it has one — a session running in its own
   tree shows that tree — and the app root otherwise."
  [{:keys [ui] engine :runtime} _]
  (let [node (or (runtime/agent-node engine) (app-runtime/node))]
    (out ui :info "Units" (app-runtime/owner-listing node))))

(defn register-runtime-commands!
  "Register the engine-portable builtins (untagged: every front end
   dispatches them) on the app root. A front end calls this with its
   builtin registration."
  []
  (commands/register-builtin!
   (app-runtime/node)
   {:name "units"
    :description "List store owners and their registered keys"
    :handler handle-units})
  (commands/register-builtin!
   (app-runtime/node)
   {:name "continue"
    :description "Continue where the agent left off (e.g. after a network error)"
    :handler handle-continue})
  (commands/register-builtin!
   (app-runtime/node)
   {:name "name"
    :description "Set session display name"
    :argument-hint "<name>"
    :handler handle-name})
  (commands/register-builtin!
   (app-runtime/node)
   {:name "export"
    :description "Export session to HTML (JSONL is not supported by design)"
    :argument-hint "<path>"
    :handler handle-export})
  (commands/register-builtin!
   (app-runtime/node)
   {:name "compact"
    :description "Manually compact the session context"
    :argument-hint "<instructions>"
    :handler handle-compact}))
