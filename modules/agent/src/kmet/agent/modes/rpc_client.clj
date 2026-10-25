(ns kmet.agent.modes.rpc-client
  "Thin client for `--mode rpc` (pi:
   modes/rpc/rpc-client.ts). `start` spawns a kmet RPC process (`bb start` /
   `jolt start`, or a dist binary through :command) and gives a script four
   primitives:

   - `send!` writes one command, assigning an :id when it has none
   - `request!` sends and blocks for the matching response
   - `on-event!` subscribes to the stream (events and :ui-request records)
   - `wait-idle!` blocks until the engine is idle and returns the settle

   stdout is the protocol: every line is one EDN record. A line that does
   not parse is kept in `unparsed-lines` (a stray println is a protocol bug,
   not something to read past silently); stderr is accumulated and available
   through `stderr`. Correlate responses by `request!`/`send!`'s :id rather
   than by order — events and responses interleave freely."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.app.debug :as debug]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.host :as host]
            [kmet.libs.process :as libs-process]))

(def ^:private default-request-timeout-ms 30000)
(def ^:private default-idle-timeout-ms 60000)
(def ^:private default-stop-timeout-ms 5000)

(defrecord RpcClient
           [process in out err pending listeners closed unparsed stderr counter exited
            request-timeout-ms idle-timeout-ms stop-timeout-ms on-stderr])

;; ─── Accessors ─────────────────────────────────────────────────────────────

(defn stderr
  "Every stderr line the process has written, joined by newlines. The
   child's diagnostics (warnings, a startup failure) live here, never on the
   protocol stream."
  [client]
  (str/join "\n" @(:stderr client)))

(defn unparsed-lines
  "stdout lines that were not a single EDN record — protocol pollution a
   consumer should treat as a bug. Empty for a healthy process."
  [client]
  @(:unparsed client))

;; ─── Writing ───────────────────────────────────────────────────────────────

(defn- exited-error
  "The failure every request reports once the child process is gone."
  [client]
  (ex-info "kmet RPC process has exited"
           {:type :rpc-exited :stderr (stderr client)}))

(defn- ensure-open!
  [client]
  (when @(:closed client)
    (throw (exited-error client))))

(defn- next-id!
  [client]
  (str "req-" (swap! (:counter client) inc)))

(defn- assign-id
  [client command]
  (if (some? (:id command))
    command
    (assoc command :id (next-id! client))))

(defn- write-line!
  [client line]
  ;; the lock object is bound first: clj-kondo's suspicious-lock check
  ;; rejects a keyword lookup directly inside locking
  (let [w (:in client)]
    (locking w
      (doto w (.write (str line "\n")) (.flush)))))

(defn send!
  "Write COMMAND as one protocol record and return it with its :id (assigned
   when absent). Fire-and-forget: no response is awaited."
  [client command]
  (ensure-open! client)
  (let [command (assign-id client command)]
    (write-line! client (pr-str command))
    command))

(defn send-line!
  "Write LINE verbatim as one protocol record. The escape hatch for a
   malformed-input test or a record the map API cannot express."
  [client line]
  (ensure-open! client)
  (write-line! client line))

;; ─── Reading ───────────────────────────────────────────────────────────────

(defn- note-unparsed!
  [client line]
  (swap! (:unparsed client) conj line)
  (debug/log "rpc-client: ignoring non-EDN stdout line: " line))

(defn- dispatch-event!
  [client record]
  ;; snapshot: a listener may unsubscribe itself while it runs
  (doseq [f @(:listeners client)]
    (try (f record)
         (catch Exception e
           (debug/log-error "rpc-client: event listener failed: " e)))))

(defn- dispatch-record!
  "A response correlated to a pending request resolves it; everything else
   (events, :ui-request records, responses that arrived after their
   request's timeout) reaches the listeners."
  [client record]
  (let [p (when (= :response (:type record))
            (get @(:pending client) (:id record)))]
    (if p
      (do (swap! (:pending client) dissoc (:id record))
          (deliver p record))
      (dispatch-event! client record))))

(defn- process-exit-error
  [client]
  (let [code (try (:exit (deref (:process client) 2000 nil))
                  (catch Exception _ nil))
        err (stderr client)]
    (ex-info (str "kmet RPC process exited"
                  (when (some? code) (str " (exit " code ")"))
                  (when-not (str/blank? err) (str ". stderr: " err)))
             {:type :rpc-exited :exit code :stderr err})))

(defn- fail-pending!
  "Close the client for new requests and fail every request still waiting:
   the process exited, so no response is coming."
  [client]
  (let [pending (:pending client)
        [old _] (locking pending
                  (reset! (:closed client) true)
                  (reset-vals! pending {}))]
    (when (seq old)
      ;; build the exit report once: it derefs the process (bounded), so it
      ;; must not run per waiting promise
      (let [err (process-exit-error client)]
        (doseq [p (vals old)]
          (deliver p err))))
    (deliver (:exited client) true)))

(defn- read-lines!
  "Call F on every line R produces, then close R. A reader failure is
   logged, never thrown: the reader thread's job is to end, not to crash."
  [label r f]
  (try
    (with-open [r r]
      (loop []
        (when-let [line (.readLine r)]
          (f line)
          (recur))))
    (catch Exception e
      (debug/log-error (str "rpc-client: " label " reader failed: ") e))))

(defn- read-stdout!
  [client]
  (try
    (read-lines! "stdout" (:out client)
                 (fn [line]
                   (let [line (str/trim line)]
                     (when (seq line)
                       (try (dispatch-record! client (edn/read-string line))
                            (catch Exception _ (note-unparsed! client line)))))))
    (finally
      (fail-pending! client))))

(defn- read-stderr!
  [client]
  (read-lines! "stderr" (:err client)
               (fn [line]
                 (swap! (:stderr client) conj line)
                 (when-let [f (:on-stderr client)] (f line)))))

;; ─── Requests ──────────────────────────────────────────────────────────────

(defn request!
  "Send COMMAND and block for the response carrying its :id — the full
   {:type :response ...} map, success or failure. Throws on timeout and when
   the process exits while the request is outstanding. TIMEOUT-MS defaults
   to the client's :request-timeout-ms (30 s)."
  ([client command] (request! client command nil))
  ([client command timeout-ms]
   (ensure-open! client)
   (let [command (assign-id client command)
         id (:id command)
         pending (:pending client)
         p (promise)]
     (locking pending
       (when @(:closed client)
         (throw (exited-error client)))
       (swap! pending assoc id p))
     (try
       (write-line! client (pr-str command))
       (catch Exception e
         (swap! pending dissoc id)
         (throw e)))
     (let [response (deref p (long (or timeout-ms (:request-timeout-ms client))) ::timeout)]
       (cond
         (= ::timeout response)
         (do (swap! pending dissoc id)
             (throw (ex-info (str "Timeout waiting for the " (:type command) " response")
                             {:type :rpc-timeout :command (:type command) :id id
                              :stderr (stderr client)})))
         (instance? Throwable response) (throw response)
         :else response)))))

;; ─── Events ────────────────────────────────────────────────────────────────

(defn on-event!
  "Subscribe to the stream: F is called with every non-correlated record
   (events and :ui-request records) on the reader thread — keep it fast.
   Returns the unsubscribe function."
  [client f]
  (swap! (:listeners client) conj f)
  (fn unsubscribe []
    (swap! (:listeners client) (fn [fs] (filterv #(not (identical? % f)) fs)))
    nil))

(defn on-settle!
  "Arm a waiter for the next :agent-settled event. Returns {:settle promise
   :cancel fn}; the subscription is installed synchronously, so arming
   before a prompt cannot miss its settle. The waiter unsubscribes itself
   once the settle arrives; :cancel is still safe to call."
  [client]
  (let [settle (promise)
        cancel-ref (atom nil)
        cancel (on-event! client
                          (fn [record]
                            (when (= :agent-settled (:type record))
                              (deliver settle record)
                              (when-let [c @cancel-ref] (c)))))]
    (reset! cancel-ref cancel)
    {:settle settle :cancel cancel}))

(defn- probe-state
  "The engine's get-state data, or nil when the probe times out (a dead
   process still throws :rpc-exited)."
  [client timeout-ms]
  (try (:data (request! client {:type :get-state} timeout-ms))
       (catch Exception e
         (if (= :rpc-timeout (:type (ex-data e)))
           nil
           (throw e)))))

(defn wait-idle!
  "Block until the engine is idle, returning its settle report
   ({:type :agent-settled :outcome ...}) or nil when TIMEOUT-MS elapses with
   a run still active. A get-state probe answers the already-settled case,
   so a prompt that finished before this call is reported instead of
   waiting for the next one; an active run is awaited through its settle
   event. The probe and the wait share TIMEOUT-MS — a get-state that does
   not answer within it returns nil rather than doubling the budget. Queued
   messages do not count as activity (only a running turn produces a
   settle)."
  ([client] (wait-idle! client (:idle-timeout-ms client)))
  ([client timeout-ms]
   (let [{:keys [settle cancel]} (on-settle! client)
         deadline (+ (concurrent/monotonic-ms) timeout-ms)]
     (try
       (let [state (probe-state client timeout-ms)]
         (if (and (some? state) (not (:turn-running? state)))
           {:type :agent-settled :outcome (:last-outcome state)}
           (deref settle (max 0 (- deadline (concurrent/monotonic-ms))) nil)))
       (finally (cancel))))))

;; ─── Lifecycle ─────────────────────────────────────────────────────────────

(defn stop!
  "Send EOF (close stdin) and wait for the process to exit, returning its
   exit code — or nil when it had to be killed after :stop-timeout-ms. The
   engine cancels a run still in flight and streams its terminal
   :agent-settled (:aborted) before disposing, so a client that stops
   mid-run sees the settle when the process exits within the timeout. After
   the return the client is fully closed: a later request fails with
   :rpc-exited rather than a broken-pipe error."
  [client]
  (libs-process/close-stdin! client)
  (let [result (try (deref (:process client) (:stop-timeout-ms client) ::timeout)
                    (catch Exception _ ::timeout))]
    (if (= ::timeout result)
      (do (process/destroy-tree (:process client)) nil)
      (do (deref (:exited client) 1000 nil)
          (:exit result)))))

(defn- launcher
  "This host's runtime launcher: `jolt` on Jolt, `bb` on babashka."
  []
  (if (host/jolt?) "jolt" "bb"))

(defn start
  "Spawn a kmet RPC process and return the client. opts:

   :command            argv prefix (default [\"bb\"/\"jolt\" \"start\"]; a
                       dist binary is [\"/path/to/kmet\"]) — `--mode rpc` is
                       appended automatically
   :args               extra CLI args (--model, --provider, --session-dir, ...)
   :cwd                working directory for the process
   :env                extra environment variables (merged over the inherited
                       environment; e.g. KMET_CODING_AGENT_DIR for isolation)
   :on-event           initial on-event! subscriber
   :on-stderr          called with each stderr line as it arrives
   :request-timeout-ms default 30 s; :idle-timeout-ms default 60 s;
   :stop-timeout-ms    default 5 s

   Throws when the launcher cannot be spawned."
  ([] (start nil))
  ([opts]
   (let [opts (or opts {})
         command (vec (or (:command opts) [(launcher) "start"]))
         argv (into (conj command "--mode" "rpc") (:args opts))
         p (process/process argv
                            (cond-> {:in :pipe :out :pipe :err :pipe}
                              (:cwd opts) (assoc :dir (:cwd opts))
                              (:env opts) (assoc :extra-env (:env opts))))
         ;; the reader side is a plain Reader; the writer wraps the process
         ;; stdin so write-line!'s flush reaches the child
         client (map->RpcClient
                 {:process p
                  :in (io/writer (:in p) :encoding "UTF-8")
                  :out (io/reader (:out p))
                  :err (io/reader (:err p))
                  :pending (atom {})
                  :listeners (atom [])
                  :closed (atom false)
                  :unparsed (atom [])
                  :stderr (atom [])
                  :counter (atom 0)
                  :exited (promise)
                  :request-timeout-ms (long (or (:request-timeout-ms opts)
                                                default-request-timeout-ms))
                  :idle-timeout-ms (long (or (:idle-timeout-ms opts)
                                             default-idle-timeout-ms))
                  :stop-timeout-ms (long (or (:stop-timeout-ms opts)
                                             default-stop-timeout-ms))
                  :on-stderr (:on-stderr opts)})]
     (when-let [f (:on-event opts)] (on-event! client f))
     (future (read-stdout! client))
     (future (read-stderr! client))
     client)))
