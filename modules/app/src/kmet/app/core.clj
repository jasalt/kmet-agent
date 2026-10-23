(ns kmet.app.core
  "kmet2's entry point: `kmet --app new` (kmet.agent.core does the dispatch).

   The app resolves a profile's rows, patches them by row id and prints the
   result (`--dump-config`); a run without `--dump-config` composes the same
   rows and **boots** them (`kmet.app.runtime`), which is what proves a profile
   mounts. `--dump-state` is the other half of an inspection: it boots the same
   rows and prints the ctx tree the boot produced (`kmet.app.inspect`), which is
   the one question a composition listing cannot answer — what is `:pending` and
   on what, what failed and with which error. No mode is wired yet, so a run
   neither dumps nor boots reports what it mounted, disposes it, and exits —
   landings 4a–4d mount the services a mode drives and landing 5 adds the modes,
   not this contract.

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
   "--patch" :patch})

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

(defn parse-args
  "Parse kmet2's argv → `{:profile kw :patch [path …] :args [rest …] :version?
   :help? :dump-config? :dump-state?}`. `:profile` defaults to
   `profile/default-profile`; the two dumps are different views of one
   composition, so asking for both is a usage error rather than a silent pick."
  [args]
  (loop [in (seq args) opts {:patch [] :args []}]
    (if-not in
      (let [opts (update opts :profile #(or % profile/default-profile))]
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
            (recur in (if (= :patch k)
                        (update opts :patch conj v)
                        (assoc opts k (keyword v)))))

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
  (println "  --patch <file>     Apply a composition layer from a file (repeatable)")
  (println "  --dump-config      Print the composed plugin rows and exit")
  (println "  --dump-state       Boot the composition, print the ctx tree, and exit")
  (println "  --version          Print the version and exit")
  (println "  -h, --help         Show this help")
  (println)
  (println "kmet2 is the migration target: --app old runs the current app, whose")
  (println "options kmet --app old --help prints."))

(defn- not-wired [args]
  (binding [*out* *err*]
    (println "kmet2 has no runnable mode yet — landings 4a–4d mount the session")
    (println "log, the extension host and the agents; landing 5 adds the modes.")
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

(defn- run-composed
  "Boot the composition a run would mount, report what it mounted, dispose it,
   and say that no mode is wired yet. The boot is the point: it is what proves
   a profile's rows actually mount, without a turn to run."
  [opts args]
  (let [booted (runtime/boot (compose-config opts))]
    (try
      (binding [*out* *err*] (println (boot-summary booted)) (flush))
      (finally
        (runtime/shutdown! booted)))
    (not-wired args)))

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
