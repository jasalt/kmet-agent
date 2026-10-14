(ns kmet.modes.test-print
  "Print-mode tests (pi: print-mode.ts) — run() drives the runtime and
   consumes its event stream: :text streams and returns the final
   assistant text (nil on a failed or aborted run), :edn prints the
   header plus every event as EDN."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.modes.print :as print-mode]
            [kmet.ai.models :as m]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.extensions :as extensions]))

(defn- opts
  "Minimal run opts — :model/:provider bypass catalog resolution;
   sessions land under target/ so test runs leave the checkout clean."
  []
  {:model "deepseek-v4-flash"
   :provider :opencode-go
   :messages ["hi"]
   :config {:provider :opencode-go :model "deepseek-v4-flash"
            :session-dir "target/test-print-sessions"}})

(defn- opts-with-session-dir
  "Run opts whose sessions live under DIR (test-scoped)."
  [dir]
  (update (opts) :config assoc :session-dir dir))

(defn- tmp-session-dir [prefix]
  (str "target/" prefix "-" (System/currentTimeMillis)))

(defn- run-edn
  "Run OPTS in :edn mode, returning [result lines]: the run's exit result
   and the parsed stdout lines (the first is the session header)."
  [opts]
  (let [out (java.io.StringWriter.)
        result (binding [*out* out] (print-mode/run opts))]
    [result (mapv edn/read-string (str/split-lines (str/trim (str out))))]))

(defn- emit!
  "Emit EVENT through the stubbed agent's own stream — the path the real
   loop uses."
  [ag event]
  ((:on-event ag) event))

(defn- settle!
  "Finish a stubbed run: when TEXT is given, its final assistant message,
   then :agent-settled with OUTCOME. Returns nil (the loop's run end)."
  ([ag] (settle! ag :completed "ok"))
  ([ag outcome text]
   (when text
     (emit! ag {:type :message-end
                :message {:role :assistant
                          :content [{:type :text :text text}]}}))
   (emit! ag {:type :agent-settled :outcome outcome})
   nil))

(deftest test-run-streams-and-returns-the-final-text
  (testing "text mode streams :message-update deltas and returns the final message text"
    (with-redefs [agent/run-agent-turn
                  (fn [ag _]
                    (emit! ag {:type :message-update
                               :delta {:type :text :content "hello "}})
                    (emit! ag {:type :message-update
                               :delta {:type :text :content "world"}})
                    (settle! ag :completed "hello world"))]
      (let [out (java.io.StringWriter.)
            result (binding [*out* out] (print-mode/run (opts)))]
        (is (= "hello world" result))
        (is (= "hello world" (str out)) "the text streamed as it arrived")))))

(deftest test-run-error-returns-nil
  (testing "a failed run reports the :error event to stderr and returns nil"
    (let [err (java.io.StringWriter.)]
      (with-redefs [agent/run-agent-turn
                    (fn [ag _]
                      (emit! ag {:type :error :message (ex-info "boom" {})})
                      (settle! ag :error nil))]
        (binding [*err* err]
          (is (nil? (print-mode/run (opts))))))
      (is (str/includes? (str err) "boom")))))

(deftest test-run-abort-returns-nil
  (testing "an aborted run reports Request aborted and returns nil"
    (let [err (java.io.StringWriter.)]
      (with-redefs [agent/run-agent-turn (fn [ag _] (settle! ag :aborted nil))]
        (binding [*err* err]
          (is (nil? (print-mode/run (opts))))))
      (is (str/includes? (str err) "Request aborted")))))

(deftest test-run-edn-mode-emits-the-header-and-every-event
  (testing "--mode edn prints the session header first, then every event as EDN"
    (let [dir (tmp-session-dir "test-print-edn")]
      (try
        (with-redefs [agent/run-agent-turn
                      (fn [ag _]
                        (emit! ag {:type :message-update
                                   :message {:role :assistant
                                             :content [{:type :text :text "hi"}]}
                                   :delta {:type :text :content "hi"}})
                        (emit! ag {:type :tool-execution-start
                                   :tool-call-id "t1" :tool-name "read" :args {}})
                        (emit! ag {:type :message-end
                                   :message {:role :assistant
                                             :content [{:type :text :text "hi"}]}})
                        (settle! ag :completed nil))]
          (let [[result lines] (run-edn (assoc (opts-with-session-dir dir) :mode :edn))]
            (is (= :completed result))
            (is (= 5 (count lines)) "header + four events")
            (is (= :session (:type (first lines)))
                "the session header line comes first")
            (let [events (subvec lines 1)]
              (is (= [:message-update :tool-execution-start :message-end :agent-settled]
                     (mapv :type events)))
              (is (not (contains? (first events) :message))
                  "the cumulative assistant snapshot is dropped from the wire event")
              (is (= {:type :text :content "hi"} (:delta (first events))))
              (is (= "t1" (:tool-call-id (second events)))))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-edn-mode-prints-a-readable-error-event
  (testing "an exception :error payload is a string on the edn wire"
    (let [dir (tmp-session-dir "test-print-edn-error")]
      (try
        (with-redefs [agent/run-agent-turn
                      (fn [ag _]
                        (emit! ag {:type :error :message (ex-info "boom" {})})
                        (settle! ag :error nil))]
          (let [[result lines] (run-edn (assoc (opts-with-session-dir dir) :mode :edn))
                error-event (first (filter #(= :error (:type %)) lines))]
            (is (nil? result) "an errored run exits 1")
            (is (= "boom" (:message error-event)))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-exits-when-stdout-fails
  (testing "a stdout failure mid-stream exits with nil instead of hanging"
    ;; The runtime isolates a throwing subscriber, so a consumer throw before
    ;; the settle delivery would leave run blocked on the result promise
    ;; forever — :edn mode writes each line before consuming the event.
    (let [flushes (atom 0)
          w (proxy [java.io.Writer] []
              (write
                ([_] nil)
                ([_ _ _] nil))
              (flush []
                (when (>= (swap! flushes inc) 3)
                  (throw (java.io.IOException. "broken pipe"))))
              (close [] nil))]
      (with-redefs [agent/run-agent-turn
                    (fn [ag _]
                      (emit! ag {:type :message-update :delta {:type :text :content "hi"}})
                      (settle! ag :completed "hi"))]
        (binding [*out* w]
          (is (nil? (print-mode/run (assoc (opts) :mode :edn)))))))))

(deftest test-run-passes-message
  (testing "the joined user message reaches the agent"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [ag ag-opts]
                                           (reset! seen (:message ag-opts))
                                           (settle! ag))]
        (print-mode/run (assoc (opts) :messages ["hello" "world"]))
        (is (= "hello world" @seen))))))

(deftest test-run-seeds-block-images
  (testing "images.blockImages reaches the print-mode agent (pi: SDK-level)"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [ag _]
                                           (reset! seen (:block-images @(:cfg ag)))
                                           (settle! ag))]
        (print-mode/run (assoc (opts) :config {:provider :opencode-go
                                               :model "deepseek-v4-flash"
                                               :session-dir "target/test-print-sessions"
                                               :images {:block-images true}}))
        (is (true? @seen)))
      (with-redefs [agent/run-agent-turn (fn [ag _]
                                           (reset! seen (:block-images @(:cfg ag)))
                                           (settle! ag))]
        (print-mode/run (opts))
        (is (false? @seen) "default off")))))

(deftest test-run-honors-http-timeout-settings
  (testing "settings.edn http timeouts reach the print-mode agent"
    (let [seen (atom nil)]
      (with-redefs [agent/run-agent-turn (fn [ag _]
                                           (reset! seen @(:cfg ag))
                                           (settle! ag))]
        (print-mode/run (assoc (opts) :config {:provider :opencode-go
                                               :model "deepseek-v4-flash"
                                               :session-dir "target/test-print-sessions"
                                               :http-idle-timeout-ms 1500
                                               :http-total-timeout-ms 2000}))
        (is (= 1500 (:http-idle-timeout-ms @seen)))
        (is (= 2000 (:http-total-timeout-ms @seen)))))
    (testing "absent settings fall back to the create-agent defaults"
      (let [seen (atom nil)]
        (with-redefs [agent/run-agent-turn (fn [ag _]
                                             (reset! seen @(:cfg ag))
                                             (settle! ag))]
          (print-mode/run (opts))
          (is (= 120000 (:http-idle-timeout-ms @seen)))
          (is (= 0 (:http-total-timeout-ms @seen))))))))

(deftest test-run-wires-the-extension-context
  (testing "print-mode handlers see the running agent (pi: runner-bound ctx)"
    (let [model (m/map->Model {:provider :opencode-go :id "deepseek-v4-flash"})
          seen (atom nil)]
      (with-redefs [m/providers-atom (atom {:opencode-go {:models [model]}})
                    agent/run-agent-turn (fn [ag _]
                                           (reset! seen
                                                   {:ctx (extensions/build-extension-context)
                                                    :session (extensions/get-session)})
                                           (settle! ag))]
        (is (= "ok" (print-mode/run
                     (assoc (opts) :config {:provider :opencode-go
                                            :model "deepseek-v4-flash"
                                            :session-dir "target/test-print-sessions"
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
            (with-redefs [agent/run-agent-turn (fn [ag _]
                                                 (reset! seen (:session ag))
                                                 (settle! ag))]
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
          (with-redefs [agent/run-agent-turn (fn [ag _]
                                               (reset! seen (:session ag))
                                               (settle! ag))]
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
          (with-redefs [agent/run-agent-turn (fn [ag _]
                                               (reset! seen (:session ag))
                                               (settle! ag))]
            (print-mode/run (assoc (opts-with-session-dir dir) :continue true))
            (is (= (:file newer) (:file @seen)))
            (is (= "newer" (:content (first @(:entries @seen)))))))
        (finally (fs/delete-tree dir))))))

(deftest test-run-continue-without-session-starts-fresh
  (testing "-c with no session for the cwd starts a fresh one in the cwd dir"
    (let [dir (tmp-session-dir "test-print-continue-empty")]
      (try
        (let [seen (atom nil)]
          (with-redefs [agent/run-agent-turn (fn [ag _]
                                               (reset! seen (:session ag))
                                               (settle! ag))]
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
          (with-redefs [agent/run-agent-turn (fn [ag _]
                                               (reset! seen (:session ag))
                                               (settle! ag))]
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
          (with-redefs [agent/run-agent-turn (fn [ag _]
                                               (reset! seen ag)
                                               (settle! ag))]
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
