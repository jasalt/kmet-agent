(ns kmet.app.core
  "kmet2's entry point: `kmet --app new` (kmet.agent.core does the dispatch).

   The app resolves a profile's rows, patches them by row id and prints the
   result (`--dump-config`); a run without `--dump-config` composes the same
   rows and **boots** them (`kmet.app.runtime`), which is what proves a profile
   mounts. `--dump-state` is the other half of an inspection: it boots the same
   rows and prints the ctx tree the boot produced (`kmet.app.inspect`), which is
   the one question a composition listing cannot answer — what is `:pending` and
   on what, what failed and with which error.

   A profile with a **mode row** runs it: the row is marked `:mode true`, and
   the entry point merges the invocation's input into that row's config before
   booting (the prompt, and where the mode reports its exit). The mode owns its
   output and runs on its own thread; `main` waits for the exit and disposes the
   composition it booted. Print mode is the first (`kmet.app.modes.print`);
   a profile without a mode row still boots, reports what it mounted, and
   exits, which is what proves the rest of the rows mount.

   Output and exit codes are separable on purpose: `main` returns the exit
   code and `-main` is the only form that calls `System/exit`, so tests read a
   command's output and status without forking a process."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.agent.version :as version]
            [kmet.app.catalog :as catalog]
            [kmet.app.inspect :as inspect]
            [kmet.app.profile :as profile]
            [kmet.app.runtime :as runtime]))

;; ── argument parsing ──────────────────────────────────────────────────────

(def ^:private bool-flags
  {"--version" :version?
   "--help" :help?
   "-h" :help?
   "--dump-config" :dump-config?
   "--dump-state" :dump-state?})

(def ^:private value-flags
  {"--profile" :profile
   "--mode" :mode
   "--patch" :patch
   "--model" :model
   "--provider" :provider})

(def ^:private print-flags
  "kmet1's one-shot spelling: `-p`/`--print` run the print profile, as `kmet -p
   \"hi\"` does."
  #{"-p" "--print"})

(def ^:private print-formats
  "kmet1's print-mode output values: text (the default) streams the answer, edn
   prints the session header and every event as EDN for a machine to read."
  #{"text" "edn"})

(defn- bad-args [message]
  (ex-info message {:type :kmet.app/bad-args}))

(defn- split-flag
  "ARG split into `[flag value]`; value is ::missing when the form carries no
   `=`."
  [arg]
  (if-let [i (and (str/starts-with? arg "--") (str/index-of arg "="))]
    [(subs arg 0 i) (subs arg (inc i))]
    [arg ::missing]))

(defn- flag-value
  "The value of value flag FLAG from VALUE (the `=` form's) or the next argument
   in IN → `[value remaining]`. Throws :kmet.app/bad-args when the value is
   missing, empty, or looks like the next flag."
  [flag value in]
  (let [[value in] (if (= ::missing value)
                     (let [v (second in)]
                       (when (or (nil? v) (str/starts-with? v "-"))
                         (throw (bad-args (str flag " needs a value"))))
                       [v (nnext in)])
                     [value (next in)])]
    (when (str/blank? value)
      (throw (bad-args (str flag " needs a value"))))
    [value in]))

(defn- resolve-mode
  "OPTS with kmet1's `--mode` applied: `text` and `edn` are print mode's output
   formats, so they select the print profile with that `:format`; every other
   value names a profile (`--mode rpc` is `--profile rpc`). `--mode` and
   `--profile` choose the same thing, so asking for both is a usage error rather
   than a silent pick."
  [opts]
  (if-let [mode (:mode opts)]
    (let [opts (dissoc opts :mode)]
      (when (:profile opts)
        (throw (bad-args "--mode (or -p) and --profile choose the same thing; pass one")))
      (if (contains? print-formats mode)
        (assoc opts :profile :print :format (keyword mode))
        (assoc opts :profile (keyword mode))))
    opts))

(defn parse-args
  "Parse kmet2's argv → `{:profile kw :patch [path …] :args [rest …] :version?
   :help? :dump-config? :dump-state? :format kw :model str :provider kw}`.
   `:profile` defaults to `profile/default-profile`; `--mode` is kmet1's spelling
   of it (`resolve-mode`), `--model`/`--provider` are the run overrides a mode
   hands the agent it creates; the two dumps are different views of one
   composition, so asking for both is a usage error rather than a silent pick."
  [args]
  (loop [in (seq args) opts {:patch [] :args []}]
    (if-not in
      (let [opts (-> (resolve-mode opts)
                     (update :profile #(or % profile/default-profile)))]
        (when (and (:dump-config? opts) (:dump-state? opts))
          (throw (bad-args "--dump-config and --dump-state are separate dumps; pass one")))
        opts)
      (let [arg (first in)
            [flag value] (split-flag arg)]
        (cond
          (contains? bool-flags flag)
          (if (= ::missing value)
            (recur (next in) (assoc opts (bool-flags flag) true))
            (throw (bad-args (str flag " takes no value"))))

          (contains? value-flags flag)
          (let [k (value-flags flag)
                [v in] (flag-value flag value in)]
            (recur in (case k
                        :patch (update opts :patch conj v)
                        (:profile :provider) (assoc opts k (keyword v))
                        (assoc opts k v))))

          (contains? print-flags arg)
          (recur (next in) (assoc opts :mode "text"))

          (str/starts-with? arg "@")
          ;; kmet1 reads a file into the message here. Expansion is the modes'
          ;; (skills, prompt templates, @files) and has not moved yet, so the
          ;; arg is refused by name: sending "@tasks.md" as the prompt would be
          ;; an answer to a question nobody asked
          (throw (ex-info (str arg " reads the message from a file, which the modes "
                               "do not expand yet — pass the text (--app old -p "
                               arg " still reads files)")
                          {:type :kmet.app/unsupported-op :op :at-file :path arg}))

          (str/starts-with? arg "-")
          (throw (bad-args (str "unknown option " arg)))

          :else (recur (next in) (update opts :args conj arg)))))))

;; ── --dump-config and --dump-state ───────────────────────────────────────

(defn- read-layer
  "Read a `--patch FILE` composition layer."
  [path]
  (let [layer (try
                (edn/read-string (slurp path))
                (catch Exception e
                  (throw (ex-info (str "cannot read patch file " path ": " (ex-message e))
                                  {:type :kmet.app/bad-patch-file :path path} e))))]
    (if (and (map? layer) (every? #{:rows :patch} (keys layer)))
      layer
      (throw (ex-info (str "a patch file holds {:rows […] :patch […]}: " path)
                      {:type :kmet.app/bad-patch-file :path path :content layer})))))

(defn compose-config
  "Compose OPTS' profile plus its `--patch` layers, describing every row
   through the catalog on the way. Returns `{:profile kw :rows … :patches n}`;
   composition errors are `:kmet.app/*` ex-infos."
  [opts]
  (let [chosen (:profile opts)
        table (profile/builtin)
        layers (concat (profile/layers table chosen)
                       (map read-layer (:patch opts)))
        composed (profile/compose (map catalog/describe-layer layers))]
    (assoc composed :profile chosen)))

(defn- dump-config
  "The `--dump-config` text: the composed rows the boot would mount, without
   booting anything."
  [opts]
  (let [{:keys [rows patches profile]} (compose-config opts)]
    (profile/render profile {:rows rows :patches patches})))

(defn- dump-state
  "The `--dump-state` text: the booted root's unit tree. The booted view needs a
   boot, so this is the one flag that mounts the composition — and disposes it
   again, so a dump leaves nothing behind. The boot is a diagnostic one: a row
   that stays `:pending` or ends `:failed` is a line in the tree (with what it
   waits for, or why it failed) instead of a boot error, because that tree is
   the answer to the question the flag asks."
  [opts]
  (let [booted (runtime/boot (compose-config opts) {:diagnose? true})]
    (try
      (str (inspect/units (:ctx booted)) "\n")
      (finally
        (runtime/shutdown! booted)))))

;; ── entry ─────────────────────────────────────────────────────────────────

(defn- report [message]
  (binding [*out* *err*] (println "Error:" message) (flush)))

(defn- print-usage []
  (println "Usage: kmet --app new [options] [messages...]")
  (println)
  (println "Options:")
  (println "  --profile <name>   Composition profile: tui (default), print, rpc, server")
  (println "  -p, --print        Run one prompt and exit (kmet1's spelling of --mode text)")
  (println "  --mode <name>      kmet1's spelling of --profile; text|edn run the print")
  (println "                     profile and choose its output")
  (println "  --patch <file>     Apply a composition layer from a file (repeatable)")
  (println "  --model <id>       Model the run's agent uses, over its settings")
  (println "  --provider <name>  Provider the run's agent uses, over its settings")
  (println "  --dump-config      Print the composed plugin rows and exit")
  (println "  --dump-state       Boot the composition, print the ctx tree, and exit")
  (println "  --version          Print the version and exit")
  (println "  -h, --help         Show this help")
  (println)
  (println)
  (println "  kmet --app new --profile print \"list the files\"   run one prompt and exit")
  (println)
  (println "kmet2 is the migration target: --app old runs the current app, whose")
  (println "options kmet --app old --help prints."))

(defn- not-wired [args]
  (binding [*out* *err*]
    (println "this profile has no runnable mode yet — landing 5 adds the")
    (println "interactive, rpc and server modes. --profile print runs one:")
    (println "  kmet --app new --dump-config   print the composed rows")
    (println "  kmet --app new --dump-state    print the booted ctx tree")
    (println "  kmet --app new --version       print the version")
    (when (seq args) (println "Arguments:" (pr-str (vec args))))
    (flush))
  1)

(defn- boot-summary
  "One line naming what the boot mounted: the rows, their fibers, and the
   service names they provide."
  [{:keys [fibers rows]}]
  (let [services (->> fibers (mapcat (comp :provides deref)) distinct sort (map name))]
    (str "booted " rows " plugin row" (when (not= 1 rows) "s")
         " (" (count fibers) " fiber" (when (not= 1 (count fibers)) "s")
         ", services: " (if (seq services) (str/join " " services) "none") ")")))

(defn- mode-row
  "The composed row a profile marked as its mode, or nil."
  [rows]
  (some #(when (:mode %) %) rows))

(defn- with-mode-input
  "COMPOSED with INPUT merged into MODE's config — that row only, so a profile
   that stacks two mode rows cannot start two runs. Matching by id is what
   `mode-row` found the row by."
  [composed mode input]
  (update composed :rows
          (fn [rows]
            (mapv (fn [row]
                    (if (= (:id row) (:id mode))
                      (update row :config merge input)
                      row))
                  rows))))

(defn- mode-input
  "What the command line asks a mode for: the prompt and where to report, plus
   what the mode passes on to its run — the output `:format` and the agent's
   `:model`/`:provider` overrides. A value the invocation did not give stays
   absent, so a mode sees what was asked for rather than a map of every option
   it could have been told."
  [opts prompt on-result]
  (cond-> {:prompt prompt :on-result on-result}
    (:format opts) (assoc :format (:format opts))
    (:model opts) (assoc :model (:model opts))
    (:provider opts) (assoc :provider (:provider opts))))

(defn- run-mode
  "Boot COMPOSED and drive MODE, the row `mode-row` found: give it the
   invocation's input, wait for it to report, and return its exit code. The
   mode owns what it prints; the entry point owns the process, the boot and the
   teardown."
  [composed mode opts prompt]
  (let [result (promise)
        booted (runtime/boot (with-mode-input composed mode
                               (mode-input opts prompt
                                           #(deliver result %))))]
    (try
      (:exit (deref result) 1)
      (finally
        (runtime/shutdown! booted)))))

(defn- no-prompt []
  (binding [*out* *err*]
    (println "No message provided. Usage: kmet --app new --profile print \"your message\"")
    (flush))
  1)

(defn- boot-and-report
  "The path a profile without a mode row takes: boot the composition a run would
   mount, report what it mounted, dispose it, and say that this profile has no
   runnable mode yet. The boot is the point — it is what proves a profile's rows
   actually mount, without a turn to run."
  [composed args]
  (let [booted (runtime/boot composed)]
    (try
      (binding [*out* *err*] (println (boot-summary booted)) (flush))
      (finally
        (runtime/shutdown! booted)))
    (not-wired args)))

(defn- run-composed
  "A run with neither `--dump-…` flag: the profile's mode, when it has one, else
   the boot report."
  [opts args]
  (let [composed (compose-config opts)]
    (if-let [mode (mode-row (:rows composed))]
      (let [prompt (str/join " " args)]
        (if (str/blank? prompt)
          (no-prompt)
          (run-mode composed mode opts prompt)))
      (boot-and-report composed args))))

(defn- configuration-error? [e]
  (str/starts-with? (str (:type (ex-data e))) ":kmet."))

(defn main
  "Run kmet2 with ARGS and return the process exit code."
  [args]
  (try
    (let [{version? :version? help? :help? dump-config? :dump-config?
           dump-state? :dump-state? leftover :args :as opts} (parse-args args)]
      (cond
        version? (do (println (str "kmet " (version/kmet-version))) (flush) 0)
        help? (do (print-usage) (flush) 0)
        dump-config? (do (print (dump-config opts)) (flush) 0)
        dump-state? (do (print (dump-state opts)) (flush) 0)
        :else (run-composed opts leftover)))
    (catch Exception e
      (cond
        (= :kmet.app/bad-args (:type (ex-data e)))
        (do (report (ex-message e)) (print-usage) 1)

        (configuration-error? e)
        (do (report (ex-message e)) 1)

        :else (throw e)))))

(defn -main
  "Process entry: kmet.app.core through the bootstrap's `--app new`."
  [& args]
  (System/exit (main args)))
