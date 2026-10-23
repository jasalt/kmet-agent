(ns kmet.app.modes.print
  "Print mode as a row: one prompt, one run, its text on stdout, and the exit
   code the entry point returns.

   pi: modes/print-mode.ts. It is the first client of the `:agents` factory
   rather than kmet1's engine — create a handle, send the message, and listen
   on the app's event layer (new-app.md, 7b-i) for the streaming deltas and for
   the settle that ends the run. One agent runs per print mode, so the listeners
   take the whole app scope; a mode that holds several agents scopes per agent
   instead (landing 6).

   The row's config carries the invocation's input — `:prompt` and the
   `:on-result` callback — because a mode's job is what the *command line*
   asked for, not something a profile can know (`kmet.app.core` merges it in).
   The mode prints; the entry point owns the process and prints its own errors."
  (:require [clojure.string :as str]
            [kmet.app.events :as events]
            [kmet.srce.core :as srce]))

(defn- text-delta
  "The text a streaming delta adds, or nil — thinking and tool-call deltas
   render nothing in print mode."
  [event]
  (let [delta (:delta event)]
    (when (= :text (:type delta))
      (:content delta))))

(defn- report!
  "One line on stderr: the mode's only error output, while the exit code
   carries the verdict (pi's runPrintMode prints the same way)."
  [message]
  (binding [*out* *err*]
    (println "Error:" message)
    (flush)))

(defn- exit-code
  "The run's verdict: 0 for a completed prompt, 1 for anything else. A shutdown
   (`:disposed`) is not a failure to report — the process is going away — but it
   is not success either."
  [outcome error]
  (if (= :completed outcome)
    0
    (do (when (not= :disposed outcome)
          (report! (or error
                       (case outcome
                         :aborted "Request aborted"
                         :error "Request failed"
                         "Request ended without an outcome"))))
        1)))

(defn- serve
  "The `:mode-print` row's apply: listen, then run on a thread of its own — the
   mount must finish so the composition can settle before the run starts.

   A mode runs only when the entry point hands it somewhere to report
   (`:on-result`): `--dump-state` mounts this row without one, and a diagnostic
   mount must prove the row composes without running a prompt."
  [ctx config]
  (let [scope (:parent ctx)
        agents (srce/get ctx :agents)
        {:keys [prompt on-result]} config]
    (if-not on-result
      ;; nothing to dispose: the diagnostic mount started nothing
      nil
      (do
        ;; the guard comes before any listener: a mount that throws leaves its
        ;; fiber failed and nothing disposes what it registered
        (when (str/blank? (str prompt))
          (throw (ex-info (str "print mode needs a prompt: "
                               "kmet --app new --profile print \"your message\"")
                          {:type :kmet.app/no-prompt})))
        (let [error (atom nil)
              settled (promise)
              dispose-deltas (events/on! scope :message-update
                                         (fn [event]
                                           (when-let [text (text-delta event)]
                                             (print text)
                                             (flush))))
              dispose-errors (events/on! scope :error
                                         (fn [event] (reset! error (:message event))))
              dispose-settle (events/on! scope :agent-settled
                                         (fn [event] (deliver settled event)))]
          (future
            (let [exit (try
                         (let [handle ((:create! agents) scope {})]
                           (try
                             ((:send! agents) handle prompt {:target :next-turn})
                             (exit-code (:outcome (deref settled)) @error)
                             (finally
                               ;; the mode owns the agent it made: the run is
                               ;; over, so the handle's teardown is a no-op and
                               ;; the ctx is released for the tree
                               ((:dispose! agents) handle))))
                         (catch Throwable t
                           (report! (or (ex-message t) (str t)))
                           1))]
              (on-result {:exit exit})))
          (fn []
            ;; a shutdown before the run ends must wake it: the settle promise
            ;; is the only thing it blocks on, and an event that never comes
            ;; would leave the thread parked
            (deliver settled {:outcome :disposed})
            (dispose-deltas)
            (dispose-errors)
            (dispose-settle)))))))

(srce/defplugin plugin
  {:name :mode-print
   :inject [:agents]
   :apply serve})
