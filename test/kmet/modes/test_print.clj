(ns kmet.modes.test-print
  "Print-mode tests (pi: print-mode.ts) — run() sends the message through
   the agent loop and returns the final text via :on-done (nil on error)."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.modes.print :as print-mode]
            [kmet.ai.models :as m]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.extensions :as extensions]))

(defn- opts
  "Minimal run opts — :model/:provider bypass catalog resolution."
  []
  {:model "deepseek-v4-flash"
   :provider :opencode-go
   :messages ["hi"]
   :config {:provider :opencode-go :model "deepseek-v4-flash"}})

(defn- opts-with-session-dir
  "Run opts whose sessions live under DIR (test-scoped)."
  [dir]
  (update (opts) :config assoc :session-dir dir))

(defn- tmp-session-dir [prefix]
  (str "target/" prefix "-" (System/currentTimeMillis)))

(deftest test-run-delivers-final-text
  (testing "run returns the agent's final text delivered via :on-done"
    (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                         ((:on-done ag-opts) "hello world"))]
      (is (= "hello world" (print-mode/run (opts)))))))

(deftest test-run-error-returns-nil
  (testing "run returns nil when the agent reports an error"
    ;; print-mode surfaces the error to stderr — capture it in the test.
    (binding [*err* (java.io.StringWriter.)]
      (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                           ((:on-error ag-opts) (ex-info "boom" {})))]
        (is (nil? (print-mode/run (opts))))))))

(deftest test-run-passes-message
  (testing "the joined user message reaches the agent"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [_ ag-opts]
                                           (reset! seen (:message ag-opts))
                                           ((:on-done ag-opts) "ok"))]
        (print-mode/run (assoc (opts) :messages ["hello" "world"]))
        (is (= "hello world" @seen))))))

(deftest test-run-seeds-block-images
  (testing "images.blockImages reaches the print-mode agent (pi: SDK-level)"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                           (reset! seen (:block-images @(:cfg ag)))
                                           ((:on-done ag-opts) "ok"))]
        (print-mode/run (assoc (opts) :config {:provider :opencode-go
                                               :model "deepseek-v4-flash"
                                               :images {:block-images true}}))
        (is (true? @seen)))
      (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                           (reset! seen (:block-images @(:cfg ag)))
                                           ((:on-done ag-opts) "ok"))]
        (print-mode/run (opts))
        (is (false? @seen) "default off")))))

(deftest test-run-honors-http-timeout-settings
  (testing "settings.edn http timeouts reach the print-mode agent"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                           (reset! seen @(:cfg ag))
                                           ((:on-done ag-opts) "ok"))]
        (print-mode/run (assoc (opts) :config {:provider :opencode-go
                                               :model "deepseek-v4-flash"
                                               :http-idle-timeout-ms 1500
                                               :http-total-timeout-ms 2000}))
        (is (= 1500 (:http-idle-timeout-ms @seen)))
        (is (= 2000 (:http-total-timeout-ms @seen)))))
    (testing "absent settings fall back to the create-agent defaults"
      (let [seen (atom nil)]
        (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                             (reset! seen @(:cfg ag))
                                             ((:on-done ag-opts) "ok"))]
          (print-mode/run (opts))
          (is (= 120000 (:http-idle-timeout-ms @seen)))
          (is (= 0 (:http-total-timeout-ms @seen))))))))

(deftest test-run-wires-the-extension-context
  (testing "print-mode handlers see the running agent (pi: runner-bound ctx)"
    (let [model (m/map->Model {:provider :opencode-go :id "deepseek-v4-flash"})
          seen (atom nil)]
      (with-redefs [m/providers-atom (atom {:opencode-go {:models [model]}})
                    agent/run-agent-turn (fn [_ag ag-opts]
                                           (reset! seen
                                                   {:ctx (extensions/build-extension-context)
                                                    :session (extensions/get-session)})
                                           ((:on-done ag-opts) "ok"))]
        (is (= "ok" (print-mode/run
                     (assoc (opts) :config {:provider :opencode-go
                                            :model "deepseek-v4-flash"
                                            :models ["opencode-go/deepseek-v4-flash"]}))))
        (let [{:keys [ctx session]} @seen]
          (is (= :print (:mode ctx)))
          (is (identical? model (:model ctx)))
          (is (= [{:model model}] (:scoped-models ctx)))
          (is (= :off (:thinking-level ctx)))
          (is (some? session) "the run's session is bound for extensions")
          (is (string? (:id session)))))
      (is (nil? (extensions/get-session))
          "the print run tears its extension wiring down"))))

(deftest test-run-resumes-session-arg
  (testing "--session resolves an id/prefix or path and loads that session"
    (let [dir (tmp-session-dir "test-print-session")
          sess (session/create-session dir {:cwd (str (fs/cwd))})
          _ (session/append-entry sess {:role :assistant :content "prior"})]
      (try
        (doseq [arg [(:id sess) (:file sess)]]
          (let [seen (atom nil)]
            (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                                 (reset! seen (:session ag))
                                                 ((:on-done ag-opts) "ok"))]
              (is (= "ok" (print-mode/run (assoc (opts-with-session-dir dir)
                                                 :session arg))))
              (is (= (:file sess) (:file @seen)) (str "loaded via " arg))
              (is (= "prior" (:content (first @(:entries @seen))))))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-session-arg-wins-over-continue
  (testing "--session takes precedence over --continue (pi: main.ts order)"
    (let [dir (tmp-session-dir "test-print-session-wins")
          cwd (str (fs/cwd))
          cdir (session/session-dir-for-cwd dir cwd)
          older (session/create-session dir {:cwd cwd})
          _ (session/append-entry older {:role :assistant :content "older"})
          newer (session/create-session cdir {:cwd cwd})
          _ (session/append-entry newer {:role :assistant :content "newer"})
          now (System/currentTimeMillis)]
      ;; --continue alone would find NEWER (newest cwd session); the explicit
      ;; --session id must win
      (fs/set-last-modified-time (:file older) (- now 20))
      (fs/set-last-modified-time (:file newer) (- now 10))
      (try
        (let [seen (atom nil)]
          (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                               (reset! seen (:session ag))
                                               ((:on-done ag-opts) "ok"))]
            (print-mode/run (assoc (opts-with-session-dir dir)
                                   :session (:id older)
                                   :continue true))
            (is (= (:file older) (:file @seen)))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-continues-most-recent-session
  (testing "-c/--continue loads the most recent session recorded for the cwd"
    (let [dir (tmp-session-dir "test-print-continue")
          cwd (str (fs/cwd))
          cdir (session/session-dir-for-cwd dir cwd)
          older (session/create-session cdir {:cwd cwd})
          _ (session/append-entry older {:role :assistant :content "older"})
          newer (session/create-session cdir {:cwd cwd})
          _ (session/append-entry newer {:role :assistant :content "newer"})
          now (System/currentTimeMillis)]
      (fs/set-last-modified-time (:file older) (- now 20))
      (fs/set-last-modified-time (:file newer) (- now 10))
      (try
        (let [seen (atom nil)]
          (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                               (reset! seen (:session ag))
                                               ((:on-done ag-opts) "ok"))]
            (print-mode/run (assoc (opts-with-session-dir dir) :continue true))
            (is (= (:file newer) (:file @seen)))
            (is (= "newer" (:content (first @(:entries @seen)))))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-continue-without-session-starts-fresh
  (testing "-c with no session for the cwd starts a fresh one in the cwd dir"
    (let [dir (tmp-session-dir "test-print-continue-empty")]
      (try
        (let [seen (atom nil)]
          (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                               (reset! seen (:session ag))
                                               ((:on-done ag-opts) "ok"))]
            (print-mode/run (assoc (opts-with-session-dir dir) :continue true))
            (is (some? @seen))
            (is (empty? @(:entries @seen)))
            (is (= (some-> (session/session-dir-for-cwd dir (str (fs/cwd))) fs/canonicalize str)
                   (str (fs/parent (:file @seen)))))
            (is (= (str (fs/cwd)) (get-in @seen [:header :cwd])))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-default-session-lands-in-the-cwd-dir
  (testing "a plain --print run creates its session in the cwd-encoded dir"
    (let [dir (tmp-session-dir "test-print-default")]
      (try
        (let [seen (atom nil)]
          (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                               (reset! seen (:session ag))
                                               ((:on-done ag-opts) "ok"))]
            (print-mode/run (opts-with-session-dir dir))
            (is (= (some-> (session/session-dir-for-cwd dir (str (fs/cwd))) fs/canonicalize str)
                   (str (fs/parent (:file @seen)))))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-resumed-session-cwd-drives-the-prompt
  (testing "a resumed session's recorded cwd reaches the system prompt"
    (let [dir (tmp-session-dir "test-print-cwd")
          project (str dir "/project")
          _ (fs/create-dirs project)
          sess (session/create-session dir {:cwd project})
          _ (session/append-entry sess {:role :assistant :content "prior"})]
      (try
        (let [seen (atom nil)]
          (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                               (reset! seen ag)
                                               ((:on-done ag-opts) "ok"))]
            (print-mode/run (assoc (opts-with-session-dir dir) :session (:id sess)))
            (is (str/includes? @(:system @seen)
                               (str (session/session-cwd (:session @seen)))))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-session-not-found
  (testing "--session matching nothing reports to stderr and returns nil"
    (let [dir (tmp-session-dir "test-print-missing")
          err (java.io.StringWriter.)]
      (fs/create-dirs dir)
      (try
        (binding [*err* err]
          (with-redefs [agent/run-agent-turn (fn [_ _]
                                               (throw (ex-info "must not run" {})))]
            (is (nil? (print-mode/run (assoc (opts-with-session-dir dir)
                                             :session "no-such-session"))))))
        (is (str/includes? (str err) "No session found matching 'no-such-session'"))
        (finally (fs/delete-tree dir))))))
