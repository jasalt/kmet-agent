(ns kmet.app.test-support
  "Shared helpers for the app's entry-point tests."
  (:require [babashka.fs :as fs]
            [kmet.agent.config :as cfg]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.app.core :as core]))

(defn run-main-into
  "`main` on ARGS with stdout going to OUT, a writer the caller keeps. A mode
   that streams while it runs (rpc) is read *during* the run this way: the
   test watches OUT for the record it is waiting for and only then does what it
   was holding back (usually: closing stdin). Returns `{:code :err}`."
  [args out]
  (let [code (atom nil)
        err (java.io.StringWriter.)]
    (binding [*out* out
              *err* err]
      (reset! code (core/main args)))
    {:code @code :err (str err)}))

(defn run-main
  "The `{:code :out :err}` of kmet2's entry on ARGS — `main` returns the exit
   code and prints, so a command is read without forking a process."
  [args]
  (let [out (java.io.StringWriter.)
        {:keys [code err]} (run-main-into args out)]
    {:code code :out (str out) :err err}))

(defn wait-until
  "Poll PRED every 5ms until it is true or TIMEOUT-MS (default 5s) pass, then
   answer whether it became true. For a test waiting on another thread's work:
   a bounded wait for the actual condition, not a sleep hoping it happened."
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/nanoTime) (* 1000000 (long timeout-ms)))]
     (loop []
       (cond
         (pred) true
         (< (System/nanoTime) deadline) (do (Thread/sleep 5) (recur))
         :else false)))))

;; ── mode runs ─────────────────────────────────────────────────────────────
;;
;; A mode test boots the real composition and drives one mode through `main`,
;; so the helpers here are the ones every mode needs: a settable model, a
;; stubbed provider, and a session log under target/ (an entry-point test must
;; not write to the agent dir a developer's kmet reads).

(def sessions-dir
  "Where a mode test's session log goes."
  "target/test-modes")

(defn settings
  "What the driver loads for a run: a provider and model that resolve."
  []
  {:session-dir sessions-dir
   :provider :opencode-go
   :model "deepseek-v4-flash"})

(defn with-mode-run
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

(defn answering
  "A `send-message` stub that streams TEXT and stops."
  [text]
  (fn [opts]
    (future
      (when-let [on-text (:on-text opts)] (on-text text))
      (when-let [on-done (:on-done opts)] (on-done :stop))
      :done)))

(defn with-model-catalog
  "Run F with the catalog a `--model` pattern resolves against: one reasoning
   model."
  [f]
  (with-redefs [models/get-provider (fn [p] {:id p})
                models/get-models (fn [& _] [{:provider :opencode-go
                                              :id "deepseek-v4-flash"
                                              :reasoning true}])]
    (f)))
