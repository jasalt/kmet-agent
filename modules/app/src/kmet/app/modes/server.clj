(ns kmet.app.modes.server
  "Server mode as a row: the rpc protocol over a socket, where **a connection is
   an agent**.

   Accepting a socket creates an agent, and every record that connection reads
   carries its `:agent-id`, so two clients cannot be confused about whose answer
   they hold. Closing the socket disposes the agent it was. The command language
   is `kmet.app.modes.rpc`'s — the same records, the same answers, the same named
   refusals — with one state per connection instead of one stream per process.

   **One waiter, many readers.** Each connection has a reader thread parked in
   `readLine`, and that thread may not touch the kernel: it hands the line to the
   server's `kmet.libs.concurrent` queue and goes back to reading. One server
   thread takes from that queue, so it is the only thread that creates, serves or
   disposes an agent. The queue's single consumer is the contract, not a
   courtesy — two takers would race its handed-out slot — and the cost of the
   design is one parked thread per connection, which is what a client that stalls
   (a half-written line, a frozen peer) is allowed to hold and nothing else.

   **An event belongs to the agent that emitted it.** The listener registers on
   the agent's own ctx, so the kernel's subtree scoping routes a run's events to
   its connection: the loop emits from the agent's ctx, and a second agent's
   events never reach the first wire. This is what per-agent event scoping means
   in practice, and it is why the registry hands out `:ctx` on the handle.

   **A server is supervised.** It serves until its stdin closes — how a parent
   process stops one — and then disposes the connections it still holds and
   exits 0. `--port 0` (the default) asks the OS for a free port, which the mode
   announces as a `:listening` record before it accepts anything; a test reads
   that record, a client reads that record, and neither has to guess.

   What this row does not have yet is a per-connection UI port: a run's extension
   UI calls still answer the host's defaults (approvals are the wire's, 6c), and
   a per-connection port needs landing 8's per-agent surface — the host's
   front-end binding is one process atom."
  (:require [clojure.java.io :as io]
            [kmet.agent.event-bus :as event-bus]
            [kmet.app.events :as events]
            [kmet.app.invocation :as invocation]
            [kmet.app.modes.out :as out]
            [kmet.app.modes.rpc :as rpc]
            [kmet.app.permissions :as permissions]
            [kmet.libs.concurrent :as concurrent]
            [kmet.srce.core :as srce]))

;; ── the wire ──────────────────────────────────────────────────────────────

(defn- send!
  "RECORD on CONN's wire, badged with the connection's `:agent-id`. Every record
   a client reads says whose it is: the server's other agents are writing to
   their own wires at the same time, and a log that collects both must be able
   to tell them apart.

   A write to a client that has gone away is **dropped, not raised**. A run's
   events are written from the run's own thread, so a dead socket that threw
   would fail a turn for a reason that is not the run's; on the server thread it
   would end the server, which accepts connections and then serves none. The
   connection's reader announces the close that releases it, so marking it dead
   here is all this has to do."
  [conn record]
  (when-not @(:dead? conn)
    (try
      (out/record! (:writer conn) (assoc record :agent-id (:agent-id conn)))
      (catch Throwable _ (reset! (:dead? conn) true))))
  nil)

(defn- state-for
  "The command state `kmet.app.modes.rpc/handle-line` serves CONN through: the
   registry and log, the app scope, and the one handle this connection is."
  [conn {:keys [agents sessions scope]}]
  {:agents agents :sessions sessions :scope scope :handle (:handle conn)})

;; ── dialogs ───────────────────────────────────────────────────────────────

(defn- settle-dialog!
  "Deliver VERDICT to the run waiting on dialog ID. True when a run was waiting:
   an id nobody holds is a client answering twice, or answering after its run
   went away, and it changed nothing."
  [server id verdict]
  (let [pending (get @(:pending server) id)]
    (swap! (:pending server) dissoc id)
    (when pending (deliver (:answer pending) verdict))
    (some? pending)))

(defn- answer-dialog!
  "ANSWER as a client's reply to a request the server made. A reply that reached
   no run is a protocol bug worth a line in the log: the client believes it
   answered something."
  [server answer]
  (or (settle-dialog! server (:id answer)
                      (select-keys (or (:data answer) answer) [:decision :reason :scope]))
      (srce/log! (:scope server) :warn "a dialog answer named no open request"
                 {:id (:id answer)}))
  nil)

(defn- deny-dialogs!
  "Answer every dialog CONN still owes with a deny: a client that leaves a
   question open must not leave the run that asked it parked forever."
  [server conn-id]
  (doseq [[id pending] @(:pending server)
          :when (= conn-id (:conn-id pending))]
    (settle-dialog! server id {:decision :deny
                               :reason "the client that was asked has gone away"}))
  nil)

(defn- ask-over-the-wire
  "Ask the client that owns CTX and wait for its answer. This runs on the run's
   own thread — the one thread that may wait for a human, exactly as
   `kmet.app.permissions` says an `:ask` does — and the gate imposes no deadline:
   an unanswered question blocks the call it is about, which is what a question
   means. A connection that closes answers its outstanding questions with a
   deny."
  [server ctx request]
  (if-let [conn (first (filter #(srce/inside? ctx (:ctx (:handle %)))
                               (vals @(:conns server))))]
    (let [id (str "req-" (random-uuid))
          answer (promise)
          ;; the run's cancel flag (an atom, `concurrent/or-signal`'s view): a
          ;; run that is aborted must not stay parked on a question nobody will
          ;; answer, so the wait watches it as well as the answer
          signal (:signal request)]
      (swap! (:pending server) assoc id {:conn-id (:id conn) :answer answer})
      (send! conn (cond-> {:type :request
                           :id id
                           :method :ask
                           :tool-name (:tool-name request)
                           :args (:args request)
                           :tool-call-id (:tool-call-id request)}
                    (:reason request) (assoc :reason (:reason request))))
      (let [verdict (loop []
                      (cond
                        (realized? answer) @answer
                        (and signal @signal) {:decision :deny
                                              :reason "the run was cancelled"}
                        :else (do (Thread/sleep 20) (recur))))]
        (swap! (:pending server) dissoc id)
        verdict))
    ;; no connection owns this ctx — a run nobody can be asked about, which is
    ;; the headless deny, not a hang
    (permissions/no-approver ctx request)))

;; The router the receiving run installed: CTX to the client that may answer for
;; it. Process-wide because the `:approvals` row it serves is — `serve` installs
;; it for its run and puts back what was there when the run ends, the same
;; install/restore pair `kmet.app.front-end` uses for the extension surface.
(defonce ^:private ask-route (atom nil))

(defn ask!
  "The `:approvals` seam the server profile mounts: a run's `:ask` becomes a
   `:request` on the connection that owns its agent, and the client's
   `:response` is the answer. Falls back to the headless deny when no run is
   serving."
  [ctx request]
  (if-let [route @ask-route]
    (route ctx request)
    (permissions/no-approver ctx request)))

(defn- serve-approvals
  "The `:mode-server-approvals` row — what `--profile server` patches into the
   bundle's `:approvals` row. The bundle's default denies because nobody can be
   asked; on a server somebody can."
  [ctx _config]
  (srce/provide! ctx :approvals (permissions/approver ask!) {:in (:parent ctx)}))

(srce/defplugin approvals
  {:name :mode-server-approvals
   :provide #{:approvals}
   :apply serve-approvals})

;; ── a connection ──────────────────────────────────────────────────────────
(defn- read-lines!
  "Hand CONN's lines to the server's queue until the socket closes or the hand-off
   refuses them. This is the thread the queue exists for: it parks in `readLine`
   and never touches the kernel. A refusal means the server stopped, and then
   the socket is done too — the close that follows is still announced, so the
   server thread releases whatever it had already created."
  [q conn]
  (try
    (with-open [rdr (io/reader (.getInputStream ^java.net.Socket (:socket conn)))]
      (loop []
        (when-let [line (.readLine rdr)]
          (when (concurrent/put! q {:op :line :conn conn :line line})
            (recur)))))
    (catch Throwable _))
  (concurrent/put! q {:op :close :conn conn})
  nil)

(defn- open-connection!
  "CONN's agent: create it, listen to its ctx, and tell the client which agent it
   serves. The listener registers on the agent's ctx, so it hears that run's
   events and no other connection's."
  [conn {:keys [agents scope config]}]
  (let [handle ((:create! agents) scope (invocation/create-opts config))
        conn (assoc conn :handle handle :agent-id (:id handle))
        dispose (events/on-every (:ctx handle)
                                 (fn [event]
                                   (send! conn (event-bus/wire-event event))))]
    (send! conn {:type :ready})
    (assoc conn :dispose dispose)))

(defn- close-connection!
  "Release CONN: deny the dialogs it leaves open (a run must not stay parked on a
   question the client can no longer answer), stop listening (so teardown events
   are not written to a socket that is going away), dispose the agent, close the
   socket. A failure is reported on the agent's own terms — the registry logs a
   failed teardown and withdraws the capability anyway — so this releases what it
   can and returns."
  [conn server]
  (deny-dialogs! server (:id conn))
  (when-let [dispose (:dispose conn)] (dispose))
  (reset! (:dead? conn) true)
  (when-let [handle (:handle conn)] ((:dispose! (:agents server)) handle))
  (try (.close ^java.net.Socket (:socket conn)) (catch Throwable _))
  nil)

;; ── the server thread ─────────────────────────────────────────────────────

(defn- serve-item!
  "One work item, answered. A connection whose creation failed is answered with
   the failure and forgotten: the server lives on, which is the one thing a
   server must do."
  [server {:keys [op conn line]}]
  (let [conns @(:conns server)
        id (:id conn)
        known (get conns id)]
    (case op
      :open (try
              (swap! (:conns server) assoc id (open-connection! conn server))
              (catch Throwable t
                (send! conn {:type :error
                             :error (str "cannot create an agent for this connection: "
                                         (or (ex-message t) (str t)))})
                (swap! (:conns server) dissoc id)))

      ;; a line from a connection the server already released is dropped: its
      ;; reader thread can still be parked when the socket goes away. A
      ;; `:response` here is a client's answer to a request the server made, so
      ;; it is delivered rather than answered.
      :line (when known
              (let [{:keys [command error]} (rpc/parse-line line)]
                (if (= :response (:type command))
                  (answer-dialog! server command)
                  (send! known (if error
                                 (rpc/parse-failure error)
                                 (rpc/handle-record (state-for known server) command))))))

      :close (do (when known (close-connection! known server))
                 (swap! (:conns server) dissoc id)))
    nil))

(defn- serve-connections!
  "The server thread: the queue's one consumer, and the only thread that touches
   the kernel on a connection's behalf. Runs until the queue closes, then
   disposes what it still holds. A failing item is reported and the loop
   continues: one broken connection is not the end of the server."
  [server]
  (loop []
    (let [item (concurrent/take! (:q server))]
      (if (= concurrent/closed item)
        (doseq [conn (vals @(:conns server))] (close-connection! conn server))
        (do (try
              (serve-item! server item)
              (catch Throwable t
                (srce/log! (:scope server) :error "server connection work failed" t)))
            (recur))))))

;; ── the accept loop ───────────────────────────────────────────────────────

(defn- accept-connections!
  "Accept until the socket closes. Each connection is announced to the server
   thread and given a reader thread of its own; accepting never waits for an
   agent to be built. A refused announcement means the server is shutting down:
   the socket is closed here rather than left for a reader thread nothing will
   serve."
  [socket q]
  (loop []
    (when-let [client (try (.accept ^java.net.ServerSocket socket) (catch Throwable _ nil))]
      (let [conn {:id (str (random-uuid))
                  :socket client
                  :dead? (atom false)
                  :writer (io/writer (.getOutputStream ^java.net.Socket client))}]
        (if (concurrent/put! q {:op :open :conn conn})
          (do (concurrent/spawn (fn [] (read-lines! q conn)))
              (recur))
          (try (.close ^java.net.Socket client) (catch Throwable _)))))))

;; ── supervision ───────────────────────────────────────────────────────────

(defn- supervise
  "Serve until IN closes — how a parent process stops a server — then end the
   queue (the server thread releases the connections it still holds) and close
   the socket. Returns the exit code."
  [in socket q server-thread]
  (try
    (with-open [rdr (io/reader in)]
      (loop [] (when (.readLine rdr) (recur))))
    (catch Throwable _))
  (concurrent/close! q)
  (try (.close ^java.net.ServerSocket socket) (catch Throwable _))
  ;; the server thread's releases touch the kernel, so the run does not end
  ;; while it is still doing them
  (try (.join ^Thread server-thread 10000) (catch Throwable _))
  0)

;; ── the row ───────────────────────────────────────────────────────────────

(defn- serve
  "The `:mode-server` row's apply: open the socket, announce its port, install
   the approvals route, start the server and accept threads, then serve until
   stdin closes.

   A mode runs only when the entry point hands it somewhere to report
   (`:on-result`): `--dump-state` mounts this row without one, and a diagnostic
   mount must prove the row composes without opening a socket. The port comes
   from the invocation (`--port`); 0 asks the OS for a free one."
  [ctx config]
  (let [scope (:parent ctx)
        agents (srce/get ctx :agents)
        sessions (srce/get ctx :sessions)
        {:keys [on-result port]} config]
    (when on-result
      (let [q (concurrent/queue)
            server {:q q
                    :agents agents
                    :sessions sessions
                    :scope scope
                    :config config
                    ;; the connection table and the dialogs it owes live here,
                    ;; not on the server thread's stack: the approvals route
                    ;; runs on a *run's* thread and must read the same table
                    :conns (atom {})
                    :pending (atom {})}
            socket (java.net.ServerSocket. (int (or port 0)))
            server-thread (concurrent/spawn (fn [] (serve-connections! server)))
            previous-route @ask-route
            ;; read here, inside the entry point's bindings: the supervisor runs
            ;; on another thread, where `*in*` is not this one
            in *in*]
        (out/record! *out* {:type :listening :port (.getLocalPort socket)})
        (reset! ask-route (fn [ctx request] (ask-over-the-wire server ctx request)))
        (concurrent/spawn (fn [] (accept-connections! socket q)))
        (future
          (on-result {:exit (supervise in socket q server-thread)}))
        (fn []
          (reset! ask-route previous-route)
          (concurrent/close! q)
          (try (.close socket) (catch Throwable _))
          nil)))))

(srce/defplugin plugin
  {:name :mode-server
   :inject [:agents :sessions]
   :apply serve})
