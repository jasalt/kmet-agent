(ns kmet.agent.cli
  "kmet1's entry point — the application `kmet.agent.core` dispatches to for
   `--app old` (the default). CLI argument parsing and mode dispatch. The
   interactive TUI lives in kmet.ui.tui.interactive; print mode in
   kmet.agent.modes.print; RPC mode in kmet.agent.modes.rpc.
   pi: dist/cli.js."
  (:require [kmet.agent.app-runtime :as app-runtime]
            [kmet.ui.tui.config-command :as config-command]
            [kmet.ui.tui.interactive :as interactive]
            [kmet.agent.modes.print :as print-mode]
            [kmet.agent.modes.rpc :as rpc-mode]
            [kmet.agent.packages-cli :as package-manager]
            [kmet.ai.image-models :as image-models]
            [kmet.ai.image-model-gen :as image-gen]
            [kmet.ai.model-gen :as model-gen]
            [kmet.ai.models :as models]
            [kmet.app.world :as world]
            [kmet.agent.model-resolver :as resolver]
            [kmet.agent.debug :as debug]
            [kmet.agent.version :as version]
            [kmet.tui.fuzzy :as fuzzy]
            [clojure.string :as str]))

;; ─── CLI argument parsing ──────────────────────────────────────────────────

(def ^:private cli-option-handlers
  {"-d" (fn [opts _ remaining] [(assoc opts :debug true) remaining])
   "--debug" (fn [opts _ remaining] [(assoc opts :debug true) remaining])
   "-p" (fn [opts _ remaining] [(assoc opts :print true) remaining])
   "--print" (fn [opts _ remaining] [(assoc opts :print true) remaining])
   "-c" (fn [opts _ remaining] [(assoc opts :continue true) remaining])
   "--continue" (fn [opts _ remaining] [(assoc opts :continue true) remaining])
   "-r" (fn [opts _ remaining] [(assoc opts :resume true) remaining])
   "--resume" (fn [opts _ remaining] [(assoc opts :resume true) remaining])
   "--generate-models" (fn [opts _ remaining] [(assoc opts :generate-models true) remaining])
   "-h" (fn [opts _ _] [(assoc opts :help true) '()])
   "--help" (fn [opts _ _] [(assoc opts :help true) '()])
   "--version" (fn [opts _ _] [(assoc opts :version true) '()])
   "--mode" (fn [opts _ remaining]
              (if (seq remaining)
                [(assoc opts :mode (keyword (first remaining))) (rest remaining)]
                [(assoc opts :mode nil) remaining]))
   "-t" (fn [opts _ remaining]
          (if (seq remaining)
            (let [level (keyword (first remaining))]
              [(assoc opts :thinking level) (rest remaining)])
            [opts remaining]))
   "--thinking" (fn [opts _ remaining]
                  (if (seq remaining)
                    (let [level (keyword (first remaining))]
                      [(assoc opts :thinking level) (rest remaining)])
                    [opts remaining]))
   "--session" (fn [opts _ remaining]
                 (if (seq remaining)
                   [(assoc opts :session (first remaining)) (rest remaining)]
                   [opts remaining]))
   "--session-dir" (fn [opts _ remaining]
                     (if (seq remaining)
                       [(assoc opts :session-dir (first remaining)) (rest remaining)]
                       [opts remaining]))
   "--model" (fn [opts _ remaining]
               (if (seq remaining)
                 [(assoc opts :model (first remaining)) (rest remaining)]
                 [opts remaining]))
   "--system-prompt" (fn [opts _ remaining]
                       (if (seq remaining)
                         [(assoc opts :system-prompt (first remaining)) (rest remaining)]
                         [opts remaining]))
   "--append-system-prompt" (fn [opts _ remaining]
                              (if (seq remaining)
                                [(update opts :append-system-prompt (fnil conj []) (first remaining)) (rest remaining)]
                                [opts remaining]))
   "--provider" (fn [opts _ remaining]
                  (if (seq remaining)
                    (let [p (keyword (first remaining))]
                      [(assoc opts :provider p) (rest remaining)])
                    [opts remaining]))
   "--models" (fn [opts _ remaining]
                (if (seq remaining)
                  [(assoc opts :models (mapv str/trim (str/split (first remaining) #","))) (rest remaining)]
                  [opts remaining]))
   "--list-models" (fn [opts _ remaining]
                     (if (and (seq remaining)
                              (not (str/starts-with? (first remaining) "-"))
                              (not (str/starts-with? (first remaining) "@")))
                       [(assoc opts :list-models (first remaining)) (rest remaining)]
                       [(assoc opts :list-models true) remaining]))})

(defn parse-args [args]
  (loop [args args
         opts {:provider nil
               :model nil
               :print false
               :continue false
               :resume false
               :debug false
               :session nil
               :session-dir nil
               :messages []
               :ext-flags {}}]
    (if (empty? args)
      opts
      (let [arg (first args)
            rest-args (rest args)]
        (cond
          (str/starts-with? arg "@")
          (let [file-path (subs arg 1)]
            (if (seq file-path)
              (let [file-content (try (slurp file-path)
                                      (catch Exception _
                                        (binding [*out* *err*]
                                          (println "Warning: could not read" file-path))
                                        ""))]
                (recur rest-args (update opts :messages conj file-content)))
              (do (binding [*out* *err*] (println "Warning: empty path after @"))
                  (recur rest-args opts))))

          (str/starts-with? arg "--")
          (let [eq (str/index-of arg "=")
                [name value] (if eq
                               [(subs arg 2 eq) (subs arg (inc eq))]
                               [(subs arg 2) nil])]
            (cond
              (contains? cli-option-handlers arg)
              (let [[opts' rest'] ((get cli-option-handlers arg) opts arg rest-args)]
                (recur rest' opts'))

              (some? value)
              (recur rest-args (assoc-in opts [:ext-flags name] value))

              (and (seq rest-args)
                   (not (str/starts-with? (first rest-args) "-")))
              (recur (rest rest-args)
                     (assoc-in opts [:ext-flags name] (first rest-args)))

              :else
              (recur rest-args (assoc-in opts [:ext-flags name] true))))

          (contains? cli-option-handlers arg)
          (let [[opts' rest'] ((get cli-option-handlers arg) opts arg rest-args)]
            (recur rest' opts'))

          :else
          (recur rest-args (update opts :messages conj arg)))))))

(defn- print-usage []
  (println "Usage: kmet [options] [@files...] [messages...]")
  (println)
  (println "Options:")
  (println "  -d, --debug           Log to debug.log")
  (println "  -p, --print           Print response and exit (non-interactive)")
  (println "  --mode <mode>         Non-interactive mode: rpc (EDN stdio protocol), or
                        print output text (default)/edn")
  (println "  -c, --continue        Continue most recent session")
  (println "  -r, --resume          Browse sessions")
  (println "  --session <id|path>   Resume session by id/prefix or file path")
  (println "  --session-dir <dir>   Session storage directory")
  (println "  --model <id>          Model to use (pattern: provider/model[:thinking])")
  (println "  --provider <name>     Provider (opencode-go, opencode, deepseek,\n                        github-copilot, openai, xai, openai-codex,\n                        azure-openai-responses, anthropic, google, groq,\n                        cerebras, huggingface, moonshotai, xiaomi, qwen-token-plan,\n                        minimax, nvidia, openrouter, fireworks, ...)")
  (println "  --models <patterns>   Comma-separated model patterns for Ctrl+P cycling")
  (println "  --list-models [search] List available models (with optional fuzzy search)")
  (println "  --generate-models     Fetch current provider + image model catalogs")
  (println "                        (models.dev + live sources) into ~/.kmet/agent/ and exit;")
  (println "                        used at startup when newer than the built-in data")
  (println "  --system-prompt <txt> Replace the system prompt (or path to a file)")
  (println "  --append-system-prompt <txt> Append to the system prompt (repeatable)")
  (println "  -t, --thinking <level> Thinking level (off, minimal, low, medium, high, xhigh, max)")
  (println "  --version             Print the version and exit")
  (println "  -h, --help            Show this help")
  (println)
  (println "Package subcommands:")
  (println "  install <source>     Add a package (local dir/file or git: repo) to settings")
  (println "  remove <source>      Remove a package (alias: uninstall)")
  (println "  update [source]      Update installed git packages (all, or one source)")
  (println "  list                 List installed packages")
  (println "  config               Enable/disable resources (TUI)")
  (println)
  (println "Examples:")
  (println "  kmet                    Start interactive TUI")
  (println "  kmet -p \"list files\"    Print response and exit")
  (println "  kmet --mode rpc         Serve the EDN stdin/stdout protocol")
  (println "  kmet --model deepseek-v4-flash  Start with a specific model")
  (println "  kmet @tasks.md         Start with file content")
  (println "  kmet install ./extensions/my-ext   Install a package"))

;; ─── Main ──────────────────────────────────────────────────────────────────

(defn- format-token-count
  "pi formatTokenCount — 200000 → \"200K\", 1000000 → \"1M\", 1500000 →
   \"1.5M\", else the plain number."
  [n]
  (let [fmt (fn [div]
              (let [s (format "%.1f" (/ n div))]
                (if (str/ends-with? s ".0")
                  (subs s 0 (- (count s) 2))
                  s)))]
    (cond
      (>= n 1000000) (str (fmt 1000000.0) "M")
      (>= n 1000) (str (fmt 1000.0) "K")
      :else (str n))))

(defn- list-models
  "pi cli/list-models.ts — print available models (provider | model | context
   | max-out | thinking | images), optional fuzzy search over provider + id."
  [search-pattern]
  (when-let [err (models/get-model-config-error)]
    (binding [*out* *err*]
      (println "Warning: errors loading models.edn:" err)))
  (let [available (models/get-available)]
    (if (empty? available)
      (println "No models available. Use /login to configure a provider.")
      (let [filtered (fuzzy/fuzzy-filter available (or search-pattern "")
                                         #(str (name (:provider %)) " " (:id %)))]
        (if (empty? filtered)
          (println (str "No models matching \"" search-pattern "\""))
          (let [rows (mapv (fn [m]
                             {:provider (name (:provider m))
                              :model (:id m)
                              :context (format-token-count (or (:context-window m) 0))
                              :max-out (format-token-count (or (:max-tokens m) 0))
                              :thinking (if (:reasoning m) "yes" "no")
                              :images (if (some #{:image} (:input m)) "yes" "no")})
                           (sort-by (juxt :provider :id) filtered))
                headers {:provider "provider" :model "model" :context "context"
                         :max-out "max-out" :thinking "thinking" :images "images"}
                cols (keys headers)
                widths (into {}
                             (for [k cols]
                               [k (reduce max (count (get headers k))
                                          (map (comp count k) rows))]))
                line (fn [row]
                       (str/join "  " (for [k cols]
                                        (str (get row k)
                                             (apply str (repeat (max 0 (- (get widths k)
                                                                          (count (get row k))))
                                                                \space))))))]
            (println (line headers))
            (doseq [r rows] (println (line r)))))))))

(defn- resolve-cli-model-opts
  "Resolve CLI --model/--models against the catalog registry (pi
   resolveCliModel wired at dispatch): patterns resolve with provider
   inference and :thinking suffixes; failures exit 1; warnings print to
   stderr."
  [opts]
  (let [opts (if-let [cli-model (:model opts)]
               (let [result (resolver/resolve-cli-model (:provider opts) cli-model
                                                        (models/get-models))]
                 (if (:error result)
                   (do (binding [*out* *err*] (println "Error:" (:error result)))
                       (System/exit 1))
                   (do (when (:warning result)
                         (binding [*out* *err*] (println "Warning:" (:warning result))))
                       (cond-> opts
                         (:provider result) (assoc :provider (:provider result))
                         (:model-id result) (assoc :model (:model-id result))
                         (and (:thinking-level result) (nil? (:thinking opts)))
                         (assoc :thinking (:thinking-level result))))))
               opts)
        opts (if-let [patterns (:models opts)]
               (let [{:keys [models warnings]}
                     (resolver/resolve-model-scope patterns (models/get-models))]
                 (doseq [w warnings]
                   (binding [*out* *err*] (println "Warning:" w)))
                 (assoc opts :models models))
               opts)]
    opts))

(defn- run-non-interactive-mode!
  "Validate OPTS' --mode and run the selected non-interactive mode, never
   returning: :rpc starts the EDN stdio protocol, :text/:edn are print mode
   (pi's text/json), anything else exits 1. A valid --mode without -p still
   runs non-interactively (pi: --mode selects the mode)."
  [opts]
  (let [mode (:mode opts :text)]
    (case mode
      :rpc (System/exit (if (nil? (rpc-mode/run opts)) 1 0))
      (:text :edn)
      (let [msg (str/join " " (:messages opts))]
        (if (empty? msg)
          (do (println "No message provided. Usage: kmet -p \"your message\"")
              (System/exit 1))
          (let [result (print-mode/run (assoc opts :messages [msg] :mode mode))]
            ;; exit 1 when the run errored (nil result = no response text)
            (System/exit (if (nil? result) 1 0)))))
      (do (binding [*out* *err*]
            (println "--mode requires text, edn, or rpc"))
          (System/exit 1)))))

(defn -main
  "Entry point. Parses CLI args and dispatches to the requested mode."
  [& args]
  (let [opts (parse-args args)]

    ;; Package subcommands (pi: package-manager-cli dispatch in main.ts —
    ;; checked before generic arg parsing, since
    ;; install/remove/update/list/config are not flags).
    (when (package-manager/package-command? args)
      (System/exit (package-manager/run-package-command
                    args {:run-config! config-command/run-config!})))

    (when (:help opts)
      (print-usage)
      (System/exit 0))

    (when (:version opts)
      (println (str "kmet " (version/kmet-version)))
      (System/exit 0))

    ;; --generate-models runs the bb generate-models pipeline into the
    ;; user-level caches (provider catalogs into ~/.kmet/agent/models-cache,
    ;; image models into ~/.kmet/agent/image-models-cache;
    ;; KMET_CODING_AGENT_DIR-aware) and exits — no registry or config needed.
    ;; load-catalogs! / load-image-catalogs! prefer each cache over the
    ;; built-in data whenever it is strictly newer.
    (when (:generate-models opts)
      (let [run (fn [generate target]
                  (try (:ok (generate target))
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "Error:" (ex-message e)))
                         false)))
            models-ok (run model-gen/generate-and-write!
                           (or models/*models-cache-dir*
                               (models/default-models-cache-dir)))
            images-ok (run image-gen/generate-and-write!
                           (or image-models/*image-models-cache-dir*
                               (image-models/default-image-models-cache-dir)))]
        (System/exit (if (and models-ok images-ok) 0 1))))

    ;; --debug turns on debug.log before any startup work loads, so the
    ;; catalog/extension load trail is in the log too (the extension loader
    ;; logs each successful load through it)
    (when (:debug opts)
      (debug/enable!)
      (debug/log "kmet started with --debug"))

    (let [config (world/load! opts)]

      ;; --list-models prints the available table and exits (pi: main.ts
      ;; listModels — after catalogs, models.edn, and extensions load so
      ;; custom providers appear)
      (when (:list-models opts)
        (list-models (when (string? (:list-models opts)) (:list-models opts)))
        (System/exit 0))

      ;; CLI --model/--models patterns resolve against the registry (pi
      ;; resolveCliModel at dispatch) before mode dispatch
      (let [opts (resolve-cli-model-opts opts)]
        (when (or (:print opts) (contains? opts :mode))
          (run-non-interactive-mode! opts))

        (println "Starting kmet...")

        (try
          (interactive/run config opts (app-runtime/ctx))
          (catch Exception e
            (debug/log-error "unhandled exception: " e)
            (binding [*out* *err*]
              (println "Error:" (ex-message e))
              (.printStackTrace e))
            (System/exit 1))
          (finally
            ;; Jolt waits for non-daemon threads before exiting, as the JVM
            ;; does: the agent pool behind `future` (render loop, input
            ;; reader, startup futures) would otherwise hold the process open
            ;; after /quit — the user's shell needs one more ctrl+c. Draining
            ;; the pool lets in-flight work finish; bb exits on its own and
            ;; shutdown-agents is harmless there.
            (shutdown-agents)))
        ;; Belt and braces after a normal quit: the pool is down, but a
        ;; thread stuck outside it (a dependency or extension non-daemon
        ;; thread) would still hold Jolt open. The session is over — exit
        ;; explicitly. Both hosts run the terminal backend's shutdown hook;
        ;; Jolt has already run the finally above, and neither host reaches
        ;; here after the catch's exit.
        (System/exit 0)))))
