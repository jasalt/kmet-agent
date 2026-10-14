(ns kmet.modes.print
  "Print mode — non-interactive: send message, print response, exit.
   pi: modes/print-mode.ts."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
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
      (let [dir (session/session-dir-for-cwd base-dir cwd)
            _ (fs/create-dirs dir)]
        (if-let [path (and (:continue opts)
                           (session/find-most-recent-session dir cwd))]
          (session/load-session path)
          (session/create-session dir))))))

(defn run
  "Run in non-interactive mode: send message, print response, exit.
   opts: :model, :provider, :messages, :system-prompt, :append-system-prompt,
         :config, :session (id/prefix/path), :continue"
  [opts]
  (let [{:keys [model provider messages config]} opts
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
            ;; options, extension handle); print mode owns only its callbacks
            engine (runtime/bootstrap config {:session session
                                              :model model
                                              :provider provider})
            result-promise (promise)
            ;; pi: session.prompt expands skill commands + prompt templates
            message (-> (str/join " " messages)
                        (skills/expand-skill-command)
                        (prompts/expand-prompt-template (prompts/get-prompt-templates)))]
        (try
          (runtime/prompt! engine
                           {:text message
                            :on-text (fn [t] (print t) (flush))
                            :on-done (fn [text] (println) (deliver result-promise text))
                            :on-error (fn [e] (binding [*out* *err*] (println "Error:" e))
                                        (deliver result-promise nil))})
          @result-promise
          (finally
            ;; the run owns the globals it installed (one-shot mode)
            (runtime/dispose! engine)))))))
