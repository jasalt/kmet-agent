(ns kmet.test-app-dispatch
  "Guard: the `--app` bootstrap (kmet.agent.core).

   Three properties, one per test: the bootstrap requires neither app (so a
   load cannot pull both), the flag selects exactly one entry and is stripped
   before the app parses, and — in a fresh process, because a test process has
   already loaded whatever it loaded — `--app old` leaves kmet2 unloaded and
   `--app new` leaves kmet1's runtime unloaded. The last one is slow: it starts
   two real processes and reads their output."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.agent.core :as bootstrap]
            [kmet.tasks.changed :as changed]
            [kmet.tasks.source-reader :as reader]))

(def ^:private bootstrap-file "modules/agent/src/kmet/agent/core.clj")

(deftest the-bootstrap-requires-neither-app
  (let [ns-form (reader/read-ns-form bootstrap-file "app dispatch guard")]
    (is (some? ns-form) "the bootstrap's ns form is readable")
    (is (empty? (changed/ns-requires ns-form))
        "kmet.agent.core requires no kmet.* namespace — both apps load through
         requiring-resolve, so an artifact never pays the other app's load")))

(defn- dispatch-calls
  "The sequence dispatch! produced: the resolved entry symbol, then the args
   it was called with."
  [args]
  (let [calls (atom [])]
    (with-redefs [requiring-resolve
                  (fn [sym]
                    (swap! calls conj sym)
                    (fn [& app-args]
                      (swap! calls conj (vec app-args))
                      ::ran))]
      (is (= ::ran (bootstrap/dispatch! args)))
      @calls)))

(deftest dispatch-selects-one-app-and-strips-the-flag
  (testing "the default is kmet1"
    (is (= ['kmet.agent.cli/-main ["--version"]]
           (dispatch-calls ["--version"]))))
  (testing "--app new selects kmet2"
    (is (= ['kmet.app.core/-main ["--version"]]
           (dispatch-calls ["--app" "new" "--version"]))))
  (testing "the --app=value form and argument order"
    (is (= ['kmet.agent.cli/-main ["-p" "hi"]]
           (dispatch-calls ["-p" "hi" "--app=old"]))))
  (testing "app arguments that only look like the flag are left alone"
    (is (= ['kmet.agent.cli/-main ["-p" "--app is a flag"]]
           (dispatch-calls ["-p" "--app is a flag"])))))

(defn- parse-error
  "The :type parse-app throws for ARGS, or nil."
  [args]
  (try (bootstrap/parse-app args) nil
       (catch Exception e (:type (ex-data e)))))

(deftest bad-app-flags-are-reported
  (doseq [args [["--app" "neither"]
                ["--app"]
                ["--app" "old" "--app" "new"]
                ["--app="]]]
    (is (= :kmet.agent/bad-app (parse-error args))
        (str "args " (pr-str args)))))

(deftest a-missing-app-is-one-sentence
  (with-redefs [bootstrap/app-available? (constantly false)]
    (let [err (java.io.StringWriter.)
          result (binding [*err* err] (bootstrap/dispatch! ["--app" "new"]))]
      (is (= ::bootstrap/missing-app result)
          "the bootstrap reports instead of exiting (the process entry exits)")
      (is (str/includes? (str err) "carries only the kmet app"))
      (is (str/includes? (str err) "--app new")))))

;; ─── the two-direction load test ──────────────────────────────────────────

(defn- child-form
  "The code a fresh process runs: dispatch ARGS through kmet.agent.core, and
   print which app namespace the hand-off loaded before the app prints/exits.
   Built as data and printed with pr-str — a hand-written string is a reader
   error waiting to happen."
  [args]
  (list 'do
        '(require '[kmet.agent.core :as core])
        (list 'let
              '[orig clojure.core/requiring-resolve]
              (list 'with-redefs
                    '[requiring-resolve
                      (fn [sym]
                        (let [v (orig sym)]
                          (println "dispatched" sym
                                   "app-ns" (some? (find-ns 'kmet.app.core))
                                   "cli-ns" (some? (find-ns 'kmet.agent.cli)))
                          v))]
                    (list 'apply 'core/-main (vec args))))))

(defn- run-child [args]
  (p/shell {:out :string :err :string :continue true}
           "bb" "-e" (pr-str (child-form args))))

(defn- version-line [out]
  (first (filter #(str/starts-with? % "kmet ") (str/split-lines out))))

(deftest ^:slow each-app-loads-alone
  (let [{old-out :out old-err :err old-code :exit} (run-child ["--app" "old" "--version"])
        {new-out :out new-err :err new-code :exit} (run-child ["--app" "new" "--version"])]
    (is (= 0 old-code) (str "kmet --app old --version failed:\n" old-err))
    (is (= 0 new-code) (str "kmet --app new --version failed:\n" new-err))
    (testing "--app old loads kmet1 and leaves kmet2 unloaded"
      (is (str/includes? old-out "dispatched kmet.agent.cli/-main app-ns false cli-ns true")
          old-out))
    (testing "--app new loads kmet2 and leaves kmet1's runtime unloaded"
      (is (str/includes? new-out "dispatched kmet.app.core/-main app-ns true cli-ns false")
          new-out))
    (testing "one version string"
      (is (some? (version-line old-out)))
      (is (= (version-line old-out) (version-line new-out))))))
