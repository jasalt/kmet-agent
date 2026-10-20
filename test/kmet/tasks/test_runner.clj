(ns kmet.tasks.test-runner
  "The runner's own gate behavior: a namespace that could not load fails the
   run — it is reported and skipped per namespace, but its tests never ran, so
   a green result would silently shrink the gate."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tasks.runner :as runner]))

(defn- summarize!
  "Run run-and-summarize with a synthetic SELECTION, capturing its stdout and
   intercepting the exit (the real one would kill the test process).
   Returns {:exit code :out captured-stdout}."
  [selection mark-validated?]
  (let [code (atom nil)
        out (java.io.StringWriter.)]
    (with-redefs-fn {(var runner/exit!) (fn [c] (reset! code c))}
      #(binding [*out* out]
         (#'runner/run-and-summarize selection mark-validated?)))
    {:exit @code :out (str out)}))

(defn- selection [unloaded]
  {:vars [] :unloaded unloaded :filters []})

(t/deftest unloadable-namespace-fails-the-run
  ;; Regression: an unloadable namespace was printed and skipped while the run
  ;; still exited 0 — a missing require silently dropped a namespace's tests
  ;; from the gate.
  (let [{:keys [exit out]} (summarize! (selection [['fixtures.broken-ns "boom"]]) false)]
    (t/is (= 1 exit))
    (t/is (str/includes? out "could not load") out)
    (t/is (str/includes? out "fixtures.broken-ns") out)
    (t/is (str/includes? out "1 namespace failed to load") out)))

(t/deftest clean-run-exits-zero
  (let [{:keys [exit out]} (summarize! (selection []) false)]
    (t/is (= 0 exit))
    (t/is (str/includes? out "0 passed") out)
    (t/is (not (str/includes? out "failed to load")) out)))

(t/deftest ns-hosts-marker-gates-the-whole-namespace
  ;; a file whose requires cannot load on this host declares the hosts that
  ;; can run it (the Windows clipboard test needs a host FFI); the runner
  ;; reads the marker from the ns form, because a namespace that fails to load
  ;; cannot report its own metadata
  (fs/create-dirs "target")
  (let [file (str (fs/path "target" (str "ns-hosts-" (System/nanoTime) ".clj")))]
    (try
      (spit file "(ns ^{:hosts #{:nowhere}} fixture.hosted
  (:require [clojure.test :as t]))
(deftest a-test (t/is true))
")
      (t/is (= #{:nowhere} (#'runner/ns-hosts file)))
      (t/is (false? (#'runner/supported-file? {:file file}))
            "a host outside the marker's set does not run the namespace")
      (finally (fs/delete-if-exists file))))
  (t/testing "no marker means every host runs it"
    (let [file (str (fs/path "target" (str "ns-nohosts-" (System/nanoTime) ".clj")))]
      (try
        (spit file "(ns fixture.plain)
")
        (t/is (nil? (#'runner/ns-hosts file)))
        (t/is (true? (#'runner/supported-file? {:file file})))
        (finally (fs/delete-if-exists file))))))

(t/deftest ^:bb-only unloadable-namespace-does-not-mark-the-baseline
  ;; the changed-files baseline is only recorded for a complete green run
  ;; (bb-only: kmet.tasks.changed is a bb task, and the runner's mark gate is
  ;; itself bb-gated)
  (let [marked (atom false)
        mark-validated! (requiring-resolve 'kmet.tasks.changed/mark-validated!)]
    (with-redefs-fn {mark-validated! (fn [] (reset! marked true))}
      #(summarize! (selection [['fixtures.broken-ns "boom"]]) true))
    (t/is (false? @marked))))
