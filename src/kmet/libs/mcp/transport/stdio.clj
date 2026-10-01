(ns kmet.libs.mcp.transport.stdio
  "MCP stdio transport (§7.2): spawn the server (babashka.process),
   line-delimited JSON over stdout/stdin, core.async response channel,
   bounded stderr tail for diagnostics, host process tracking
   (kmet.libs.process) and process-tree kill on close.

   Conn keys: :proc :pid :ch :stderr-tail :out-lock, plus the common
   contract keys documented in kmet.libs.mcp.transport.

   Deliberately not built on kmet.libs.jsonrpc yet: adopting it needs
   per-request progress callbacks, best-effort notifications, host process
   tracking and the pid/stderr-tail surface that jsonrpc does not expose
   (mcp.md Phase 1.0 time-box)."
  (:require [babashka.process :as proc]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.json :as json]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.process :as process]))

(def ^:private stderr-tail-lines 20)
(def ^:private spawn concurrent/spawn)

;; ─── Process + readers ────────────────────────────────────────────────────

(defn- stdio-argv
  "Full argv for a stdio server: :command may be a string or a vector
   (vector = full argv, merged with :args)."
  [definition]
  (let [{:keys [command args]} definition]
    (if (vector? command)
      (into (vec command) args)
      (into [(str command)] args))))

(defn- drain-stdout
  "Background reader: stdout lines → JSON parse → response channel.
   Non-JSON lines (banners on stdout) are dropped. ::eof + close on EOF."
  [out ch]
  (try
    (with-open [rdr (io/reader out)]
      (doseq [line (line-seq rdr)]
        (when (seq (str/trim line))
          (try
            (let [parsed (json/parse-string line true)]
              (when (map? parsed)
                (async/put! ch parsed)))
            (catch Exception _ nil)))))
    (catch Exception _ nil)
    (finally
      (async/put! ch transport/eof-marker)
      (async/close! ch))))

(defn- drain-stderr
  "Background reader: keep the last 20 stderr lines for diagnostics."
  [err tail]
  (try
    (with-open [rdr (io/reader err)]
      (doseq [line (line-seq rdr)]
        (swap! tail (fn [lines]
                      (vec (take-last stderr-tail-lines
                                      (conj (vec lines) line)))))))
    (catch Exception _ nil)))

(defn connect!
  "Spawn a stdio server process. Returns the conn map."
  [definition opts]
  (let [argv (stdio-argv definition)
        env (merge (into {} (System/getenv)) (:env definition))
        ;; the vector form: babashka.process's variadic form silently drops
        ;; the opts map, so `(apply proc/process argv opts)` never passed
        ;; :env (per-server env was broken)
        p (proc/process (vec argv)
                        {:in :stream :out :stream :err :stream
                         :dir (:cwd definition)
                         :env env})
        pid (process/process-pid p)
        ch (async/chan 128)
        tail (atom [])]
    (when pid (process/track-pid! pid))
    (spawn #(drain-stdout (:out p) ch))
    (spawn #(drain-stderr (:err p) tail))
    {:transport :stdio
     :proc p
     :pid pid
     :ch ch
     :stderr-tail tail
     :out-lock (Object.)
     :req-lock (Object.)
     :id-counter (atom 0)
     :last-used (atom (System/currentTimeMillis))
     :on-notification (:on-notification opts)}))

;; ─── Messaging ────────────────────────────────────────────────────────────

(defn- write-msg!
  "Write one JSON-RPC line to the server, serialized on the conn's write
   lock: a background request (a list_changed resync) and a foreground
   tool call can share one conn, and two io/copy calls on the same
   stream would interleave into a corrupt line."
  [conn msg]
  ;; io/copy instead of .write — direct stream methods are not callable
  ;; from the extension sci context
  (let [line (str (json/generate-string msg) "\n")
        write! (fn [] (io/copy line (:in (:proc conn))))]
    (if-let [lock (:out-lock conn)]
      (locking lock (write!))
      (write!)))
  nil)

(defn send-async!
  "Deliver a JSON-RPC message that expects no answer — a notification, or
   a response to a server->client request."
  [conn msg]
  (transport/touch! conn)
  (write-msg! conn msg))

(defn- dead-message
  "Diagnostic message for a dead stdio process (stderr tail appended)."
  [conn method]
  (str "MCP connect failed: process exited"
       (when method (str " while waiting for " method))
       (let [tail (str/join " — " @(:stderr-tail conn))]
         (when (seq tail) (str " (stderr: " tail ")")))))

(defn request!
  "Send one request and wait for the response with :id = ID."
  [conn method params id timeout-ms on-notification]
  (transport/touch! conn)
  (when-not (proc/alive? (:proc conn))
    (throw (protocol/mcp-error (dead-message conn nil) {:transport :stdio})))
  (write-msg! conn {:jsonrpc "2.0" :id id :method method :params params})
  (let [response (transport/wait-for-response conn (:ch conn) id method timeout-ms
                                              on-notification send-async!)]
    (if (= transport/eof-marker response)
      (throw (protocol/mcp-error (dead-message conn method) {:transport :stdio}))
      (:result response))))

(defn close!
  "Kill the server's process tree and close the response channel."
  [conn]
  (when (:pid conn) (process/kill-process-tree! (:pid conn)))
  (try (async/close! (:ch conn)) (catch Exception _ nil))
  nil)

(defn alive?
  "True while the child process is running."
  [conn]
  (boolean (and (:proc conn) (proc/alive? (:proc conn)))))
