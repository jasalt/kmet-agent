(ns kmet.app.modes.test-print
  "Print mode end to end: `--profile print \"…\"` boots the real composition, runs
   one prompt through the `:agents` factory, streams the text and returns an
   exit code — the first mode that drives agents instead of an engine.

   The model is stubbed and the session log is redirected under target/: an
   entry-point test must not write to the agent dir a developer's kmet reads."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.agent.config :as cfg]
            [kmet.ai.llm :as llm]
            [kmet.app.core :as core]))

(def ^:private sessions-dir "target/test-print-mode")

(defn- run-main
  "The `{:code :out :err}` of kmet2's entry on ARGS (the same capture
   `kmet.app.test-core` uses: `main` returns the code and prints)."
  [args]
  (let [code (atom nil)
        err (java.io.StringWriter.)
        out (binding [*err* err]
              (with-out-str (reset! code (core/main args))))]
    {:code @code :out out :err (str err)}))

(defn- with-stubbed-model
  "Run F with the model answering TEXT, the API key stubbed, and the session
   log under target/."
  [text f]
  (fs/create-dirs sessions-dir)
  (try
    ;; a realistic settings map: a provider and model that resolve, the log
    ;; under target/. Print mode reads its config the way the app does
    (with-redefs [cfg/load-config (fn [& _] {:session-dir sessions-dir
                                             :provider :opencode-go
                                             :model "deepseek-v4-flash"})
                  cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-text (:on-text opts)] (on-text text))
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      (f))
    (finally (fs/delete-tree sessions-dir))))

(deftest print-mode-runs-a-prompt-and-exits-zero
  (with-stubbed-model
    "hello from the model"
    (fn []
      (let [{:keys [code out]} (run-main ["--profile" "print" "hello"])]
        (is (= 0 code) "a completed prompt exits 0")
        (is (= "hello from the model" out)
            "the run's text is printed from the app's event layer")
        (is (seq (fs/glob sessions-dir "**/*.ednl"))
            "and the run left its session log behind: a mode drives the same
             durable session the rest of the app reads")))))

(deftest print-mode-without-a-prompt-says-what-it-needs
  (let [{:keys [code err]} (run-main ["--profile" "print"])]
    (is (= 1 code))
    (is (str/includes? err "No message provided")
        "the usage names the flag and the message, before anything boots")))

(deftest a-profile-without-a-mode-still-boots-and-reports
  (let [{:keys [code err]} (run-main [])]
    (is (= 1 code))
    (is (str/includes? err "booted 13 plugin rows")
        "the default profile still proves its rows mount")
    (is (str/includes? err "no runnable mode yet")
        "and says which profiles do run one")))

(deftest a-diagnostic-mount-does-not-run-the-prompt
  ;; `--dump-state` mounts every row of the profile, this one included — a
  ;; diagnostic mount hands the mode no `:on-result`, so it stays inert and the
  ;; dump proves the row composes without printing anything or blocking.
  (let [{:keys [code out]} (run-main ["--profile" "print" "--dump-state"])]
    (is (= 0 code))
    (is (some #(and (str/includes? % ":mode-print") (str/includes? % ":active"))
              (str/split-lines out))
        "the mode row mounts and activates in a diagnostic boot")))
