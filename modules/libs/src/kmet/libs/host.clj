(ns kmet.libs.host
  "Which runtime hosts this kmet process, plus the portable runtime predicates
   that paper over host differences.

   kmet runs on babashka, on Jolt, and on the Clojure JVM (`bb
   start-jvm`): Jolt defines the clojure.core/*jolt-version* var and
   babashka sets the babashka.version system property; a process with
   neither marker is the JVM.
   UI surfaces that name or badge the host (the welcome header logo, the
   footer's К mark) share this one detection instead of repeating it, and
   platform-specific branches (the terminal backend pick, app seams) share
   windows?. finite? is the same kind of shim: Double/isFinite is unshimmed
   on Jolt, so the isNaN/isInfinite statics compose it portably; stdout-tty?
   papers over Jolt's permanently-nil System/console with the native OS
   check."
  (:require [babashka.fs :as fs]))

(defn jolt?
  "True on the Jolt host: jolt defines clojure.core/*jolt-version*;
   babashka does not."
  []
  (boolean (find-var 'clojure.core/*jolt-version*)))

(defn babashka?
  "True on the Babashka host: bb sets the \"babashka.version\" system
   property; the JVM does not, and Jolt defines *jolt-version* instead."
  []
  (some? (System/getProperty "babashka.version")))

(defn jvm?
  "True on the Clojure JVM host — neither Babashka nor Jolt (`bb start-jvm`
   runs the app there)."
  []
  (and (not (jolt?)) (not (babashka?))))

(defn windows?
  "True when the OS is Windows — the predicate the tree's platform branches
   share (babashka.fs/windows?, which answers from `os.name` on both hosts).
   macOS/Linux/Android are the false case, so a Unix-only branch can test
   `(not (windows?))`."
  []
  (fs/windows?))

(defn stdout-tty?
  "True when standard output is attached to a terminal (pi:
   process.stdout.isTTY) — the condition behind the exit resume hint, so a
   redirected stdout never collects it. bb/JVM answers from System/console
   (non-nil exactly when stdin and stdout are attached). Jolt answers nil
   from System/console by design (host-static.ss marks it unimplemented), so
   the native shim asks the OS instead: libc isatty(1) on POSIX, the
   kernel32 console mode of STD_OUTPUT_HANDLE on Windows. The shim is a
   .jolt source — the runtime's own extension — resolved at call time, so
   neither host loads the other's FFI surface. A shim that cannot load logs
   and answers false: a missing hint must not break the exit path."
  []
  (if (jolt?)
    (boolean
     (try
       ((requiring-resolve 'kmet.libs.host-native/stdout-tty?))
       (catch Exception e
         (try
           ((requiring-resolve 'kmet.agent.debug/log)
            "host: stdout-tty? native check failed: " (ex-message e))
           (catch Throwable _ nil))
         false)))
    (some? (System/console))))

(defn runtime-name
  "Name of the hosting runtime: \"jolt\", \"babashka\", or \"clojure\" (the
   JVM host that `bb start-jvm` starts)."
  []
  (cond
    (jolt?) "jolt"
    (babashka?) "babashka"
    :else "clojure"))

(defn mark
  "Single-letter runtime mark for compact UI badges (the footer's Кb/Кj/Кc):
   the runtime name's initial — \"b\" on babashka, \"j\" on Jolt, \"c\" on
   the Clojure JVM."
  []
  (subs (runtime-name) 0 1))

(defn finite?
  "True when V is a finite number (portable replacement for
   Double/isFinite — composes the isNaN/isInfinite statics)."
  [v]
  (and (number? v)
       (let [d (double v)]
         (not (or (Double/isNaN d) (Double/isInfinite d))))))

