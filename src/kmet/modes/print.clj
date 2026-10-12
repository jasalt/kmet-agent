(ns kmet.modes.print
  "Print mode — non-interactive: send message, print response, exit.
   pi: modes/print-mode.ts."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.ai.models :as models]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.app.context :as context]
            [kmet.app.prompts :as prompts]
            [kmet.app.extensions :as extensions]
            [kmet.app.packages :as packages]
            [kmet.config :as cfg]))

(defn- extension-before-tool-call
  "Chain extension tool-call hooks (pi: beforeToolCall) — print mode wires
   them like interactive mode, so write-reject / edit-warn hooks fire in
   headless runs too. Returns nil | {:block true :reason} | {:args ...}."
  [ctx]
  (loop [hooks (extensions/get-tool-call-hooks)
         blocked nil
         args (:args ctx)]
    (if-let [hook (first hooks)]
      (let [r (try (hook (assoc ctx :args args))
                   (catch Exception e
                     {:block true
                      :reason (str "tool-call hook error: " (ex-message e))}))]
        (cond
          (:block r)
          (recur (next hooks) (or blocked {:block true :reason (:reason r)}) args)
          (contains? r :args)
          (recur (next hooks) blocked (:args r))
          :else
          (recur (next hooks) blocked args)))
      (or blocked (when (not= args (:args ctx)) {:args args})))))

(defn- extension-after-tool-call
  "Chain extension tool-result hooks (pi: afterToolCall) — print mode wires
   them like interactive mode. Returns the (possibly rewritten) result."
  [ctx]
  (reduce (fn [result hook]
            (if-let [r (try (hook (assoc ctx :result result
                                         :is-error (:is-error result false)))
                            (catch Exception e
                              {:content (str "tool-result hook error: " (ex-message e))
                               :is-error true}))]
              (cond-> result
                (:content r) (assoc :content (:content r))
                (contains? r :is-error) (assoc :is-error (:is-error r)))
              result))
          (:result ctx)
          (extensions/get-tool-result-hooks)))

(defn- session-for-run
  "Resolve the run's session (pi: main.ts resolveSessionPath/continueRecent —
   resolved before mode dispatch there and shared by every mode). --session
   wins over --continue: it takes a session id, id prefix, or path; --continue
   loads the most recent session for the process cwd, falling back to a fresh
   one when none exists (pi: continueRecent); otherwise a fresh session starts.
   New sessions land in the cwd-encoded sessions dir, like interactive mode.
   Returns the session record, or nil when --session matches nothing."
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
        _ (packages/load-skills!)
        _ (packages/load-prompts!)
        session (session-for-run config opts)]
    (if (nil? session)
      ;; --session matched nothing: report like interactive mode and return
      ;; nil — core exits 1 on a nil result
      (do
        (binding [*out* *err*]
          (println (str "No session found matching '" (:session opts) "'")))
        nil)
      (let [;; a resumed session brings its recorded working directory: the
            ;; prompt's cwd line follows it (the agent loop already resolves
            ;; tool paths against it) while project-scoped context files stay
            ;; with the launch dir (pi: runtime cwd;
            ;; kmet.modes.interactive.layout has the same split)
            cwd (or (session/session-cwd session) (str (fs/cwd)))
            ;; pi: defaultTools — the built-in tools active at startup; extension
            ;; tools stay enabled
            default-tools (tools/resolve-default-tools (cfg/get-default-tools config))
            system-prompt-opts {:custom-prompt (cfg/get-custom-prompt config)
                                :append-prompt (cfg/get-append-system-prompt config)
                                :cwd cwd
                                :context-files (context/load-project-context-files
                                                (cfg/get-agent-dir) (str (fs/cwd)))
                                :tools (agent/active-tools-for
                                        {:default-tools default-tools})}
            system-prompt (apply skills/build-system-prompt
                                 (mapcat identity system-prompt-opts))
            resolved-provider (or provider (cfg/get-provider config))
            resolved-model (or model (models/resolve-config-model config))
            ;; A session record (pi print-mode's AgentSessionRuntime always has
            ;; one — sessionManager.getSessionId drives the opencode
            ;; x-opencode-session attribution headers and the openai-responses
            ;; prompt_cache_key; the zen free tier rejects requests without a
            ;; session id: "OpenCode's free tier can only be used in OpenCode")
            ag (agent/make-agent-state
                :model resolved-model
                :provider resolved-provider
                :system system-prompt
                :system-prompt-opts system-prompt-opts
                :session session
                :default-tools default-tools
                :before-tool-call extension-before-tool-call
                :after-tool-call extension-after-tool-call
                ;; pi: retry settings (settings.edn :retry block — enabled gates
                ;; max-retries to 0)
                :max-retries (let [retry (cfg/get-retry-settings config)]
                               (if (:enabled retry) (:max-retries retry) 0))
                :base-delay-ms (:base-delay-ms (cfg/get-retry-settings config))
                :max-agent-delay-ms (:max-agent-delay-ms (cfg/get-retry-settings config))
                ;; Repeat-loop guard (kmet-specific): settings.edn :loop-guard
                ;; block — enabled gates threshold to 0 (off)
                :loop-guard-enabled (:enabled (cfg/get-loop-guard-settings config))
                :loop-guard-threshold (:threshold (cfg/get-loop-guard-settings config))
                :thinking-loop-guard-enabled (get config :thinking-loop-guard-enabled true)
                ;; LLM transport deadlines from settings.edn — a one-shot print run
                ;; honors the same user timeout settings interactive mode passes.
                ;; get-with-default (not plain :kw): an absent key must keep
                ;; make-agent-state's default, and an explicit nil stays
                ;; meaningful (nil total follows the idle timeout). Without this,
                ;; the agent's 120s/0 defaults silently won over the user's
                ;; settings.
                :http-idle-timeout-ms (get config :http-idle-timeout-ms 120000)
                :http-total-timeout-ms (get config :http-total-timeout-ms 0)
                ;; pi: images.blockImages — stripped per request in call-llm (pi
                ;; applies it at the SDK level, so --print honors it too)
                :block-images (cfg/get-block-images config))
            _ (agent/init-scoped-models! ag config)
            result-promise (promise)
            ;; pi: session.prompt expands skill commands + prompt templates
            message (-> (str/join " " messages)
                        (skills/expand-skill-command)
                        (prompts/expand-prompt-template (prompts/get-prompt-templates)))]
        ;; Extension runtime wiring (pi: runner.bindCore binds the print-mode
        ;; session and the live context getters like interactive): the session
        ;; facades and the ctx :model/:scoped-models/:thinking-level read the
        ;; running agent for the duration of the one-shot run.
        (extensions/set-session! session)
        (extensions/set-context-sink! (fn [msg] (agent/add-context-message! ag msg)))
        (extensions/set-ui-registry!
         {:build-context
          (fn []
            {:model (models/get-model @(:provider ag) @(:model ag))
             :scoped-models (resolver/scoped-models-for-context
                             @(:scoped-models ag) @(:provider ag))
             :thinking-level @(:thinking ag)})})
        (try
          (agent/run-agent-turn ag
                                {:message message
                                 :on-text (fn [t] (print t) (flush))
                                 :on-done (fn [text] (println) (deliver result-promise text))
                                 :on-error (fn [e] (binding [*out* *err*] (println "Error:" e))
                                             (deliver result-promise nil))})
          @result-promise
          (finally
            ;; the run owns the globals it installed (one-shot mode)
            (extensions/set-session! nil)
            (extensions/set-context-sink! nil)
            (extensions/clear-ui-registry!)))))))
