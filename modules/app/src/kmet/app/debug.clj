(ns kmet.app.debug
  "Diagnostics logging: `--debug` lifecycle lines in kmet-debug.log and
   unhandled-error lines in kmet-error.log.

   Both files live in the diagnostics directory — the `kmet.log.dir` system
   property (the test runner's isolation), `KMET_LOG_DIR` when set, else
   `logs/` under the process cwd — and are size-capped: when the next line
   would pass `max-log-bytes`, the file is rotated to `.1` (one previous
   file is kept) instead of growing without bound. A failed write is reported
   once on stderr, until a write succeeds again; a broken log path can neither
   spam the terminal nor fail the program.

   The switch means *all* diagnostics, not just the log file: it also turns
   on the session loader's wrong-shape reporting (`kmet.app.session.store`)
   and installs this namespace's `log` as the `kmet.libs` sink
   (`kmet.libs.log`), so a retry or a failed native probe in a lower layer
   reaches the same file. One switch owned in one place, rather than every
   `--debug` consumer remembering to flip its own."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.session.store :as session-store]
            [kmet.libs.log :as libs-log]
            [kmet.srce.core :as srce]))

(def max-log-bytes
  "Rotate a diagnostics file when the next line would push it past this
   size. One previous file (`.1`) is kept."
  (* 5 1024 1024))

(defonce ^:private debug-enabled (atom false))

(defonce ^:private write-lock
  ;; Every write takes this lock: the logs are written from the loop's
  ;; thread, tool threads and the TUI, and a line must never interleave with
  ;; a rotation.
  (Object.))

(defonce ^:private write-failure-reported?
  ;; The one-shot guard behind the stderr report: a full disk must not turn
  ;; every log call into another error line.
  (atom false))

(defn logs-dir
  "The directory the diagnostics logs live in: the `kmet.log.dir` system
   property (a programmatic override, used by the test runner), else
   `KMET_LOG_DIR`, else `logs/` under the process cwd."
  []
  (or (some-> (System/getProperty "kmet.log.dir") str/trim not-empty)
      (some-> (System/getenv "KMET_LOG_DIR") str/trim not-empty)
      (str (fs/path (fs/cwd) "logs"))))

(defn- debug-file [] (str (fs/path (logs-dir) "kmet-debug.log")))
(defn- error-file [] (str (fs/path (logs-dir) "kmet-error.log")))

(defn- timestamp []
  (str (java.time.LocalDateTime/now)))

(defn- rotate!
  "Move PATH to PATH.1, replacing an older .1. A missing PATH is not an
   error (the first write after a clean start)."
  [path]
  (when (fs/exists? path)
    (fs/move path (str path ".1") {:replace-existing true})))

(defn- report-write-failure!
  "Report the first write failure on stderr; later failures stay quiet.
   Never throws: a log path problem must not break the caller."
  [path e]
  (when (compare-and-set! write-failure-reported? false true)
    (try
      (binding [*out* *err*]
        (println "kmet: cannot write diagnostics log" path "-"
                 (or (ex-message e) (str e)))
        (flush))
      (catch Throwable _ nil))))

(defn- write-line
  "Append LINE to PATH under the write lock, creating the directory and
   rotating first when the file is at its cap. A failed write is reported
   once and otherwise ignored."
  [path line]
  (locking write-lock
    (try
      (when-let [dir (fs/parent path)]
        (fs/create-dirs dir))
      (let [size (if (fs/exists? path) (fs/size path) 0)]
        ;; `count` is characters, not bytes, so a non-ASCII line can push the
        ;; file slightly past the cap — acceptable for a diagnostics file.
        (when (>= (+ size (count line)) max-log-bytes)
          (rotate! path)))
      (spit path line :append true)
      (reset! write-failure-reported? false)
      (catch Exception e
        (report-write-failure! path e)))))

(defn- exception-str
  "Format an exception with class, message, and full stack trace."
  [e]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw)]
    (.printStackTrace e pw)
    (.flush pw)
    (str (.getName (class e)) ": " (ex-message e) "\n" (.toString sw))))

(defn log
  "Write a timestamped line to kmet-debug.log if logging is enabled.
   If the last argument is an Exception, formats it with full stack trace."
  [& parts]
  (when @debug-enabled
    (let [parts-str (apply str
                           (map (fn [p] (if (instance? Exception p) (exception-str p) (str p)))
                                parts))]
      (write-line (debug-file) (str "[" (timestamp) "] " parts-str "\n")))))

(defn log-error
  "Write a timestamped error to kmet-error.log. Always writes, regardless of
   debug mode.
   If the last argument is an Exception, formats it with full stack trace."
  [& parts]
  (let [parts-str (apply str
                         (map (fn [p] (if (instance? Exception p) (exception-str p) (str p)))
                              parts))
        line (str "[" (timestamp) "] ERROR: " parts-str "\n")]
    (write-line (error-file) line)
    ;; Also log to kmet-debug.log if enabled
    (when @debug-enabled
      (write-line (debug-file) line))))

(defn logger
  "The kernel's `:logger` service (`srce/log!`): `(logger level & args)`. An
   `:error` is always written (log-error); every other level follows --debug,
   with the level as the line's prefix so one file stays readable."
  [level & args]
  (if (= level :error)
    (apply log-error args)
    (apply log (str (name level) ": ") args)))

(defn enable!
  "Enable debug logging, the diagnostics it implies, and the `kmet.libs`
   logger sink."
  []
  (reset! debug-enabled true)
  (session-store/report-wrong-shapes! true)
  ;; A thunk, not the fn value: a reload redefines `log`, and the installed
  ;; sink must reach the current definition.
  (libs-log/set-sink! (fn [& parts] (apply log parts)))
  (log "─── debug logging enabled ───"))

(defn disable!
  "Disable debug logging, the diagnostics it implies, and the `kmet.libs`
   logger sink."
  []
  (log "─── debug logging disabled ───")
  (libs-log/clear-sink!)
  (reset! debug-enabled false)
  (session-store/report-wrong-shapes! false))

(defn enabled?
  "Returns true if debug logging is currently enabled."
  []
  @debug-enabled)

;; The kernel's `:logger` service (`srce/log!`), provided into the mount site's
;; scope: a listener failure or a refused registration is reported through it,
;; and absent means silent. A row rather than a boot-time provision, so it
;; unwinds with the composition like every other service.
(srce/defplugin plugin
  {:name :logger
   :apply (fn [ctx _config]
            (srce/provide! ctx :logger logger {:in (:parent ctx)}))})
