(ns kmet.modes.print
  "Print mode — non-interactive: send message, print response, exit.
   pi: modes/print-mode.ts. A pure consumer of the runtime's event stream:
   :text streams assistant text and returns the final message's text; :edn
   prints the session header and every event as EDN (pi's --mode json, the
   machine-readable variant)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.session :as session]
            [kmet.app.session-runtime :as runtime]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.config :as cfg]))

(defn- session-for-run
  "Resolve the run's session (pi: main.ts resolveSessionPath/continueRecent —
   resolved before mode dispatch there and shared by every mode). --session
   wins over --continue: it takes a session id, id prefix, or path; --continue
   loads the most recent session for the process cwd, falling back to a fresh
   one when none exists (pi: continueRecent); otherwise a fresh session starts.
   New sessions land in the cwd-encoded sessions dir, like interactive mode.
   Returns the session record, or nil when --session matches nothing. Session
   *policy* stays here; the runtime takes the resolved session."
  [config opts]
  (let [base-dir (cfg/get-session-dir config)
        cwd (str (fs/cwd))]
    (if-let [arg (:session opts)]
      (when-let [path (session/resolve-session-path arg base-dir)]
        (session/load-session path))
      (if-let [path (and (:continue opts)
                         (runtime/find-recent-session base-dir cwd))]
        (session/load-session path)
        (session/create-session (runtime/ensure-cwd-session-dir base-dir cwd))))))

(defn- message-text
  "The concatenated text blocks of MESSAGE, or nil when it has none."
  [message]
  (let [text (->> (:content message)
                  (keep #(when (= :text (:type %)) (:text %)))
                  (apply str))]
    (not-empty text)))

(defn- settle-result
  "The run's exit result at :agent-settled (pi: runPrintMode's text branch).

   Text mode returns the final assistant message's text; a failed or
   aborted run reports to stderr and yields nil, which exits 1 (core).
   Edn mode's result is only a success flag: the events on stdout carry
   the detail, so a failure stays silent there."
  [mode message error outcome]
  (if (= :completed outcome)
    (if (= :edn mode) :completed (or (message-text message) ""))
    (do (when (= :text mode)
          (binding [*out* *err*]
            (println "Error:" (or error
                                  (:error-message message)
                                  (case outcome
                                    :aborted "Request aborted"
                                    "Request failed")))))
        nil)))

(defn- event-consumer
  "The print run's subscriber for MODE: streams text deltas in :text mode,
   prints every wire event in :edn mode, and delivers the run's result at
   :agent-settled. The last :message-end and :error payloads decide the
   result's text and error detail."
  [mode result-promise]
  (let [last-message (atom nil)
        last-error (atom nil)]
    (fn [event]
      (when (= :edn mode)
        (prn (event-bus/wire-event event))
        (flush))
      (case (:type event)
        :message-update
        (when (and (= :text mode) (= :text (get-in event [:delta :type])))
          (print (get-in event [:delta :content]))
          (flush))

        :message-end
        (reset! last-message (:message event))

        :error
        (reset! last-error (:message event))

        :agent-settled
        (deliver result-promise
                 (settle-result mode @last-message @last-error (:outcome event)))

        nil))))

(defn run
  "Run in non-interactive mode: send message, print response, exit.
   opts: :model, :provider, :messages, :system-prompt, :append-system-prompt,
         :config, :session (id/prefix/path), :continue, :mode (:text | :edn)"
  [opts]
  (let [{:keys [model provider messages config]} opts
        mode (:mode opts :text)
        config (cfg/apply-cli-overrides (or config (cfg/load-config :no-env? true)) opts)
        session (session-for-run config opts)]
    (if (nil? session)
      ;; --session matched nothing: report like interactive mode and return
      ;; nil — core exits 1 on a nil result
      (do
        (binding [*out* *err*]
          (println (str "No session found matching '" (:session opts) "'")))
        nil)
      (let [;; the runtime owns the wiring (agent state, system prompt, run
            ;; options, extension handle); print mode owns only its consumer
            engine (runtime/bootstrap config {:session session
                                              :model model
                                              :provider provider})
            result-promise (promise)
            unsubscribe (runtime/subscribe! engine (event-consumer mode result-promise))
            ;; pi: session.prompt expands skill commands + prompt templates
            message (-> (str/join " " messages)
                        (skills/expand-skill-command)
                        (prompts/expand-prompt-template (prompts/get-prompt-templates)))]
        (try
          (when (= :edn mode)
            (when-let [header (:header session)]
              (prn header)
              (flush)))
          (runtime/prompt! engine {:text message})
          @result-promise
          (finally
            ;; the run owns the globals it installed (one-shot mode)
            (unsubscribe)
            (runtime/dispose! engine)))))))
