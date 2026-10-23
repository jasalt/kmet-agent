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
            [kmet.app.test-support :as support]))

(def ^:private sessions-dir "target/test-print-mode")

(defn- settings
  "What the driver loads for a run: a provider and model that resolve, and the
   session log under target/ so a test never writes to the agent dir a
   developer's kmet reads."
  []
  {:session-dir sessions-dir
   :provider :opencode-go
   :model "deepseek-v4-flash"})

(defn- with-mode-run
  "Run F with the model stubbed by SEND-MESSAGE (a `send-message` stub) and the
   settings above."
  [send-message f]
  (fs/create-dirs sessions-dir)
  (try
    (with-redefs [cfg/load-config (fn [& _] (settings))
                  cfg/get-api-key (fn [_] "test-key")
                  llm/send-message send-message]
      (f))
    (finally (fs/delete-tree sessions-dir))))

(defn- answering
  "A `send-message` stub that streams TEXT and stops."
  [text]
  (fn [opts]
    (future
      (when-let [on-text (:on-text opts)] (on-text text))
      (when-let [on-done (:on-done opts)] (on-done :stop))
      :done)))

(deftest print-mode-runs-a-prompt-and-exits-zero
  (with-mode-run
    (answering "hello from the model")
    (fn []
      (let [{:keys [code out]} (support/run-main ["--profile" "print" "hello"])]
        (is (= 0 code) "a completed prompt exits 0")
        (is (= "hello from the model" out)
            "the run's text is printed from the app's event layer")
        (is (seq (fs/glob sessions-dir "**/*.ednl"))
            "and the run left its session log behind: a mode drives the same
             durable session the rest of the app reads")))))

(deftest print-mode-without-a-prompt-says-what-it-needs
  (let [{:keys [code err]} (support/run-main ["--profile" "print"])]
    (is (= 1 code))
    (is (str/includes? err "No message provided")
        "the usage names the flag and the message, before anything boots")))

(deftest a-diagnostic-mount-does-not-run-the-prompt
  ;; `--dump-state` mounts every row of the profile, this one included — a
  ;; diagnostic mount hands the mode no `:on-result`, so it stays inert and the
  ;; dump proves the row composes without printing anything or blocking.
  (let [{:keys [code out]} (support/run-main ["--profile" "print" "--dump-state"])]
    (is (= 0 code))
    (is (some #(and (str/includes? % ":mode-print") (str/includes? % ":active"))
              (str/split-lines out))
        "the mode row mounts and activates in a diagnostic boot")))

(deftest a-failed-run-exits-one-and-says-why
  ;; The mode's verdict is its exit code: a shell script reads it. The model
  ;; fails with a non-retryable message, the loop surfaces it as :error, and
  ;; the prompt reports it on stderr rather than printing a half answer.
  (with-mode-run
    (fn [opts]
      (future
        (when-let [on-error (:on-error opts)] (on-error "boom"))
        :done))
    (fn []
      (let [{:keys [code out err]} (support/run-main ["--profile" "print" "hello"])]
        (is (= 1 code) "a failed run exits 1")
        (is (= "" out) "and prints no text")
        (is (str/includes? err "Error: boom")
            "the failure reaches stderr, named as the model reported it")))))
