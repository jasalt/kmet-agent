(ns kmet.agent.core
  "The process entry point: a bootstrap that selects the app and nothing else.

   One artifact carries two applications (`new-app.md`). `--app old` — the
   default until kmet1 is retired — hands the argv to kmet1
   (`kmet.agent.cli`); `--app new` hands it to kmet2 (`kmet.app.core`). The
   flag is stripped before the selected app sees the argv, so neither app's
   parser has to know about it (kmet1 would read an unknown `--flag` as an
   extension flag).

   The hand-off is dynamic on purpose: this namespace requires neither app.
   A static require would load both — the very thing the split exists to
   avoid — and a missing kmet2 (a `--dev` build, an older artifact) would read
   as a null dereference instead of one sentence.

   Both packagers and the version marker name this namespace: bb and jolt
   build with `-m kmet.agent.core`, and the JVM uberjar AOT-compiles
   kmet.agent.jvm-main, whose -main delegates here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private apps
  "The selectable applications: the `--app` value → its dynamic entry and the
   classpath resource that proves the artifact carries it."
  {:old {:entry 'kmet.agent.cli/-main :resource "kmet/agent/cli.clj"}
   :new {:entry 'kmet.app.core/-main :resource "kmet/app/core.clj"}})

(def default-app
  "The app a bare `kmet` runs: kmet1, until it is retired."
  :old)

(def ^:private app-values
  {"old" :old "new" :new})

(defn parse-app
  "Split ARGS into `{:app kw :args […]}`: the `--app old|new` (or
   `--app=old`) flag removed, every other argument in its original order.

   An unknown or missing app value, and a second `--app`, throw
   `:kmet.agent/bad-app` — the flag is read before either app's parser runs,
   so the bootstrap is the only place that can report it."
  [args]
  (loop [in (seq args)
         out []
         app default-app
         seen? false]
    (if-not in
      {:app app :args out}
      (let [arg (first in)
            [value remaining] (cond
                                (= "--app" arg)
                                (let [v (second in)]
                                  (when-not v
                                    (throw (ex-info "--app needs a value (old or new)"
                                                    {:type :kmet.agent/bad-app})))
                                  [v (nnext in)])
                                (str/starts-with? arg "--app=")
                                [(subs arg (count "--app=")) (next in)]
                                :else [::absent in])]
        (if (= ::absent value)
          (recur (next in) (conj out arg) app seen?)
          (do
            (when seen?
              (throw (ex-info "--app given twice" {:type :kmet.agent/bad-app :value value})))
            (if-let [selected (get app-values value)]
              (recur remaining out selected true)
              (throw (ex-info (str "unknown app: " value " (expected old or new)")
                              {:type :kmet.agent/bad-app :value value})))))))))

(defn app-available?
  "Does this artifact carry ENTRY's app? The classpath resource is the proof —
   a `--dev` build may stage only one app."
  [entry]
  (some? (io/resource (:resource entry))))

(defn report-missing
  "Say that this artifact carries no such app; returns ::missing-app. Never
   exits — the process entry owns the exit code."
  [{:keys [entry]}]
  (binding [*out* *err*]
    (println (str "kmet: this artifact carries only the kmet app — no "
                  entry " here."
                  " A `--dev` build, or an artifact older than the kmet2"
                  " landing, has nothing to run for --app new.")))
  ::missing-app)

(defn dispatch!
  "Resolve and run the selected app on ARGS. Returns the app's -main value, or
   ::missing-app when the artifact does not carry it."
  [args]
  (let [{:keys [app args]} (parse-app args)
        entry (apps app)]
    (if-not (app-available? entry)
      (report-missing entry)
      (apply (requiring-resolve (:entry entry)) args))))

(defn- print-usage []
  (binding [*out* *err*]
    (println "Usage: kmet [--app old|new] [options] [@files...] [messages...]")
    (println)
    (println "  --app old   kmet1, the current app (default)")
    (println "  --app new   kmet2, the migration target")
    (println)
    (println "The selected app prints its own options: kmet --help")))

(defn -main
  "Process entry: select the app, then hand over."
  [& args]
  (try
    (when (= ::missing-app (dispatch! args))
      (System/exit 1))
    (catch Exception e
      (if (= :kmet.agent/bad-app (:type (ex-data e)))
        (do (binding [*out* *err*] (println "Error:" (ex-message e)))
            (print-usage)
            (System/exit 1))
        (throw e)))))
