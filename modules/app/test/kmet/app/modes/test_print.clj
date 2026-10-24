(ns kmet.app.modes.test-print
  "Print mode end to end: `--profile print \"…\"` boots the real composition, runs
   one prompt through the `:agents` factory, streams the text and returns an
   exit code — the first mode that drives agents instead of an engine.

   The model is stubbed and the session log is redirected under target/: an
   entry-point test must not write to the agent dir a developer's kmet reads."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.agent.config :as cfg]
            [kmet.ai.llm :as llm]
            [kmet.agent.extensions.core :as extensions]
            [kmet.ai.models :as models]
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

(deftest print-mode-can-carry-the-event-stream
  ;; `--mode edn` is kmet1's machine-readable variant: the session header, then
  ;; every event as EDN, so a consumer reads a run from the wire instead of
  ;; watching text.
  (with-mode-run
    (answering "hello from the model")
    (fn []
      (let [{:keys [code out]} (support/run-main ["--mode" "edn" "hello"])
            events (mapv edn/read-string (str/split-lines out))]
        (is (= 0 code) "a completed prompt exits 0")
        (is (= :session (:type (first events)))
            "the header comes first: the id the events belong to")
        (is (some #(and (= :message-update (:type %))
                        (= "hello from the model" (get-in % [:delta :content])))
                  events)
            "the answer arrives as deltas")
        (is (not-any? #(contains? % :message)
                      (filter (comp #{:message-update} :type) events))
            "and a delta carries the delta, not the cumulative snapshot")
        (is (some #{:agent-settled} (drop-while #(not= :message-end %)
                                                (map :type events)))
            "the settle follows the message that ended, so a consumer can stop")
        (is (not (str/includes? out "hello from the model\nhello from the model"))
            "the text is not printed twice: edn mode prints events, not the answer")))))

(defn- with-model-catalog
  "The catalog a `--model` pattern resolves against: one reasoning model."
  [f]
  (with-redefs [models/get-provider (fn [p] {:id p})
                models/get-models (fn [& _] [{:provider :opencode-go
                                              :id "deepseek-v4-flash"
                                              :reasoning true}])]
    (f)))

(deftest a-model-override-reaches-the-run
  ;; `--model` is kmet1's pattern — provider/model[:thinking], the provider
  ;; inferred when absent — resolved against the catalog before the run. The
  ;; agent's model is an option of the agent, not the process's, so a script can
  ;; name one without editing settings.
  (let [seen (atom nil)]
    (with-model-catalog
      (fn []
        (with-mode-run
          (fn [opts]
            (reset! seen (select-keys opts [:model :provider :thinking]))
            (future
              (when-let [on-text (:on-text opts)] (on-text "ok"))
              (when-let [on-done (:on-done opts)] (on-done :stop))
              :done))
          (fn []
            (let [{:keys [code]} (support/run-main ["-p" "--model"
                                                    "opencode-go/deepseek-v4-flash:high"
                                                    "hello"])]
              (is (= 0 code))
              (is (= {:model "deepseek-v4-flash" :provider :opencode-go
                      :thinking :high}
                     @seen)
                  "the run asked for the pattern's model, provider and thinking
                   level, not the settings'"))))))))

(deftest a-model-the-catalog-does-not-have-is-named
  ;; A pattern nothing matches is a named failure before the run, as in kmet1
  ;; (which prints the same error and exits 1) — not a request that dies later
  ;; with nothing to say.
  (let [stub (fn [opts]
               (future (when-let [on-error (:on-error opts)] (on-error "boom")) :done))]
    (with-redefs [models/get-provider (fn [p] {:id p})
                  models/get-models (fn [& _] [])]
      (with-mode-run stub
        (fn []
          (let [{:keys [code out err]} (support/run-main ["-p" "--provider" "deepseek"
                                                          "--model" "no-such-model" "hi"])]
            (is (= 1 code))
            (is (= "" out) "nothing runs, so nothing is printed")
            (is (str/includes? err "Model \"no-such-model\" not found")
                "and the failure names the model that was asked for")))))))

(deftest edn-mode-carries-the-failure-on-the-wire
  ;; In edn mode the failure is an event, so it is printed like every other and
  ;; the mode adds no error line of its own: the exit code is the verdict, and a
  ;; consumer that wanted the reason already has it.
  (with-mode-run
    (fn [opts]
      (future
        (when-let [on-error (:on-error opts)] (on-error "boom"))
        :done))
    (fn []
      (let [{:keys [code out err]} (support/run-main ["--mode" "edn" "hello"])
            events (mapv edn/read-string (str/split-lines out))]
        (is (= 1 code) "a failed run exits 1")
        (is (= [{:type :error :message "boom"}]
               (filter (comp #{:error} :type) events))
            "the error is on the wire, once")
        (is (not (str/includes? err "Error:"))
            "and not repeated on stderr, where edn consumers do not look")))))

(deftest the-run-tells-the-extension-host-it-is-print-mode
  ;; kmet1's print mode goes through its session runtime, which binds the
  ;; front-end surface: kind :print, no UI, and the headless port — so an
  ;; extension that branches on ctx.mode, or asks a question, gets kmet1's answer
  ;; under either app. The binding is the run's: what was there before it comes
  ;; back, which is not the same as the dispatcher's default.
  (let [seen (atom nil)
        outer (fn [& _])]
    ;; a front end installed before the run — an embedder's, another mode's
    (extensions/bind-extensions! {:mode :interactive :has-ui true
                                  :ui {:notify outer}})
    (with-mode-run
      (fn [opts]
        ;; read from inside the run, where the binding is live
        (reset! seen {:mode (extensions/extension-mode)
                      :has-ui (extensions/extension-has-ui?)
                      :set-theme (some? (extensions/ui-capability :set-theme))
                      :notify (some? (extensions/ui-capability :notify))
                      :confirm (extensions/ui-call :request {:kind :confirm})})
        (future
          (when-let [on-text (:on-text opts)] (on-text "ok"))
          (when-let [on-done (:on-done opts)] (on-done :stop))
          :done))
      (fn []
        (is (= 0 (:code (support/run-main ["-p" "hello"]))))
        (is (= :print (:mode @seen)) "an extension sees the front end it runs under")
        (is (false? (:has-ui @seen)) "and that there is no UI to draw on")
        (is (true? (:set-theme @seen))
            "the headless port answers where it must rather than staying inert")
        (is (false? (:notify @seen)) "and stays inert where a no-op is right")
        (is (false? (:confirm @seen))
            "a confirm question is answered — never with yes, never with nil")))
    (is (= :interactive (extensions/extension-mode))
        "the surface that was there is back")
    (is (true? (extensions/extension-has-ui?)))
    (is (identical? outer (extensions/ui-capability :notify)))
    (extensions/unbind-extensions!)
    (is (nil? (extensions/ui-capability :set-theme))
        "and a process with no front end of its own is back to inert")))
