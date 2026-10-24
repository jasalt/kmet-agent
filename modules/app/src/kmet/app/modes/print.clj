(ns kmet.app.modes.print
  "Print mode as a row: one prompt, one run, its text on stdout, and the exit
   code the entry point returns.

   pi: modes/print-mode.ts. It is the first client of the `:agents` factory
   rather than kmet1's engine — create a handle, send the message, and listen
   on the app's event layer (new-app.md, 7b-i) for the streaming deltas and for
   the settle that ends the run. One agent runs per print mode, so the listeners
   take the whole app scope; a mode that holds several agents scopes per agent
   instead (landing 6).

   The row's config carries the invocation's input — `:prompt`, the
   `:on-result` callback and the output `:format` — because a mode's job is what
   the *command line* asked for, not something a profile can know
   (`kmet.app.core` merges it in). `:model`/`:provider` ride the same map and
   become the created agent's options, so an override is the run's, not the
   process's.

   **The run tells the extension host what kind of front end is running**
   (`bind-front-end!`): kmet1's print mode goes through its session runtime,
   which binds `:mode :print`, `has-ui` false and the headless capability port,
   so an extension that branches on `ctx.mode` or asks a question gets the same
   answer under either app. A process that binds nothing keeps the host's own
   headless defaults, which are more inert than kmet1's per-session port.

   Two formats, kmet1's two: `:text` (the default) streams the answer and
   reports a failure on stderr; `:edn` prints the session header and every event
   as EDN for a machine to read, so the event stream carries the failure and the
   exit code is the only thing left to say it happened. The mode prints; the
   entry point owns the process and prints its own errors."
  (:require [clojure.string :as str]
            [kmet.agent.event-bus :as event-bus]
            [kmet.agent.extensions.core :as extensions]
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
   is not success either. Only text mode reports a failure itself: edn mode has
   already printed the `:error` event that carried it."
  [format outcome error]
  (if (= :completed outcome)
    0
    (do (when (and (= :text format) (not= :disposed outcome))
          (report! (or error
                       (case outcome
                         :aborted "Request aborted"
                         :error "Request failed"
                         "Request ended without an outcome"))))
        1)))

(defn- run-prompt!
  "One prompt: create, send, wait for the settle, report. Returns the exit
   code. The mode owns the agent it made, so the handle is disposed whatever
   the run did — and a failure to create or send is one line on stderr and
   exit 1, never a thrown thread.

   CREATE-OPTS are the agent's own options (the invocation's model overrides);
   the driver applies them to the settings it loads. In edn mode the session
   header goes out first, before the run produces any event: a consumer reads
   the header to learn the id the events belong to."
  [scope agents {:keys [prompt format] :as config} settled error]
  (try
    (let [handle ((:create! agents) scope (select-keys config [:model :provider]))]
      (try
        (when (= :edn format)
          (prn (:header ((:session handle))))
          (flush))
        ((:send! agents) handle prompt {:target :next-turn})
        (exit-code format (:outcome (deref settled)) @error)
        (finally
          ((:dispose! agents) handle))))
    (catch Throwable t
      (report! (or (ex-message t) (str t)))
      1)))

(defn- dispose-all
  "One disposer for several: a run's listeners go together."
  [disposers]
  (fn [] (doseq [dispose disposers] (dispose))))

(defn- subscriptions
  "SCOPE's listeners for a run of FORMAT, as one disposer — text mode takes three
   kinds (deltas, the error, the settle), edn mode takes every event the
   vocabulary declares and prints it. Both deliver SETTLED once: a settle that
   never arrives would leave the run's thread parked, so the disposer delivers
   `:disposed`."
  [scope format settled error]
  (if (= :edn format)
    (dispose-all
     (mapv (fn [event]
             (events/on! scope (:name event)
                         (fn [e]
                           (prn (event-bus/wire-event e))
                           (flush)
                           (when (= :agent-settled (:type e))
                             (deliver settled e)))))
           (events/declared-events)))
    (dispose-all
     [(events/on! scope :message-update
                  (fn [event]
                    (when-let [text (text-delta event)]
                      (print text)
                      (flush))))
      (events/on! scope :error (fn [event] (reset! error (:message event))))
      (events/on! scope :agent-settled (fn [event] (deliver settled event)))])))

(defn- serve
  "The `:mode-print` row's apply: wire the stream, then run on a thread of its
   own — the mount must finish so the composition can settle before the run
   starts.

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
        (let [format (:format config :text)
              error (atom nil)
              settled (promise)
              dispose (subscriptions scope format settled error)]
          ;; the front end's surface goes down with the run: the host's default
          ;; is what a process with no front end reports, and kmet1's print mode
          ;; binds exactly this
          (extensions/bind-extensions! {:mode :print
                                        :has-ui false
                                        :ui (extensions/headless-ui-port)})
          (future
            (on-result {:exit (run-prompt! scope agents (assoc config :format format)
                                           settled error)}))
          (fn []
            ;; a shutdown before the run ends must wake it: the settle promise
            ;; is the only thing it blocks on, and an event that never comes
            ;; would leave the thread parked
            (deliver settled {:outcome :disposed})
            (dispose)
            (extensions/unbind-extensions!)))))))

(srce/defplugin plugin
  {:name :mode-print
   :inject [:agents]
   :apply serve})
