(ns kmet.app.modes.test-server
  "Server mode end to end: `--profile server` boots the same composition as rpc
   mode, opens a socket, and gives **each connection its own agent**. The
   command language is rpc's; what these tests add is what the socket changes —
   two agents at once, a client that stalls holding nothing but its own parked
   thread, a closed connection releasing its agent, and every record badged with
   the id of the agent it belongs to.

   The model is stubbed and the session log lives under target/
   (`kmet.app.test-support`), as for every mode test."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.app.test-support :as support :refer [with-mode-run]]
            [kmet.libs.concurrent :as concurrent]))

;; ── the client ────────────────────────────────────────────────────────────

(defn- last-user-text
  "The text of the last user message the stub was asked to complete."
  [opts]
  (some->> (:messages opts)
           (filter #(= :user (:role %)))
           last
           :content
           (filter #(= :text (:type %)))
           (map :text)
           (str/join " ")))

(defn- replying
  "A `send-message` stub that streams back what it was asked, except for a prompt
   containing \"stall\": that one parks until the test releases RELEASE, which is
   what a run in flight looks like from the outside."
  [release]
  (fn [opts]
    (let [text (last-user-text opts)]
      (future
        (if (str/includes? (str text) "stall")
          (do (deref release 30000 :timeout)
              (when-let [on-done (:on-done opts)] (on-done :stop)))
          (do (when-let [on-text (:on-text opts)] (on-text (str "echo: " text)))
              (when-let [on-done (:on-done opts)] (on-done :stop))))
        :done))))

(defn- connect
  "A client socket to PORT: a reader thread collecting records, `:send!` writing
   one, and `:close!`. Exactly what a client of the protocol is."
  [port]
  (let [socket (java.net.Socket. "127.0.0.1" (int port))
        writer (io/writer (.getOutputStream socket))
        seen (atom [])]
    (concurrent/spawn
     (fn []
       (try
         (with-open [rdr (io/reader (.getInputStream socket))]
           (loop []
             (when-let [line (.readLine rdr)]
               (swap! seen conj (edn/read-string line))
               (recur))))
         (catch Throwable _))))
    {:socket socket
     :send! (fn [record]
              (locking writer
                (.write writer (str (pr-str record) \newline))
                (.flush writer)))
     :records (fn [] @seen)
     :of-type (fn [type] (filter #(= type (:type %)) @seen))
     :agent-id (fn [] (:agent-id (first (filter #(= :ready (:type %)) @seen))))
     :close! (fn [] (.close socket))}))

(defn- start!
  "Boot `--profile server` and return `{:port :records :stop!}`. The mode's stdin
   is a loopback socket, so the test owns the supervisor signal: `:stop!` closes
   it and answers the mode's exit code. `:records` are the mode's own: the
   `:listening` announcement, since the protocol goes on the socket."
  []
  (let [signal (java.net.ServerSocket. 0)
        accepted (future (.accept signal))
        stdin-client (java.net.Socket. "127.0.0.1" (.getLocalPort signal))
        stdin-peer @accepted
        out (java.io.StringWriter.)
        result (future (binding [*in* (io/reader (.getInputStream stdin-peer))]
                         (support/run-main-into ["--profile" "server"] out)))]
    (when-not (support/wait-until #(str/includes? (str out) ":listening"))
      (throw (ex-info "the server never announced a port" {:out (str out)})))
    (let [announced (some #(when (str/includes? % ":listening") (edn/read-string %))
                          (str/split-lines (str out)))]
      {:port (:port announced)
       :records (fn [] (mapv edn/read-string (remove str/blank? (str/split-lines (str out)))))
       :stop! (fn []
                (.close stdin-client)
                (:code (deref result 60000 {:code ::timeout})))
       :result result})))

(defn- with-server
  "Run F with a served socket and the stubbed model. F gets `{:port :conn!
   :stop!}`, and every connection it opened with `conn!` is closed on the way
   out, even when F throws — a test never has to close what it opened, which is
   also what keeps its body one nesting deep."
  [f]
  (with-mode-run
    (replying (promise))
    (fn []
      (let [server (start!)
            opened (atom [])
            conn! (fn [] (let [c (connect (:port server))] (swap! opened conj c) c))]
        (try
          (f (assoc server :conn! conn!))
          (finally
            (doseq [c @opened] ((:close! c)))
            ((:stop! server))))))))

(defn- ready
  "Wait until CONN's `:ready` record has arrived."
  [conn]
  (support/wait-until #(some? ((:agent-id conn))))
  (first ((:of-type conn) :ready)))

(defn- answered?
  "Has CONN received the response for request ID?"
  [conn id]
  (support/wait-until #(some? (first (filter (fn [r] (= id (:id r))) ((:records conn)))))))

;; ── tests ─────────────────────────────────────────────────────────────────

(deftest each-connection-is-an-agent
  ;; The socket is the boundary: two clients, two agents, and no record a client
  ;; reads can be mistaken for its neighbour's.
  (with-server
    (fn [{:keys [conn!]}]
      (let [a (conn!)
            b (conn!)]
        (ready a)
        (ready b)
        (is (not= ((:agent-id a)) ((:agent-id b)))
            "two connections hold two agents")
        ((:send! a) {:type :get-state :id "a1"})
        ((:send! b) {:type :get-state :id "b1"})
        (is (answered? a "a1"))
        (is (answered? b "b1"))
        (let [state-a (first (filter #(= "a1" (:id %)) ((:records a))))
              state-b (first (filter #(= "b1" (:id %)) ((:records b))))]
          (is (= ((:agent-id a)) (:agent-id state-a))
              "the answer carries the id of the agent that answered it")
          (is (= ((:agent-id b)) (:agent-id state-b)))
          (is (not= (get-in state-a [:data :session :id])
                    (get-in state-b [:data :session :id]))
              "and the two agents have sessions of their own"))
        (is (every? #(= ((:agent-id a)) (:agent-id %)) ((:records a)))
            "every record on a connection is badged with its agent's id")
        (is (every? #(= ((:agent-id b)) (:agent-id %)) ((:records b))))))))

(deftest a-client-that-never-finishes-a-line-holds-nothing
  ;; The reader thread parks in `readLine`, so a half-written record costs one
  ;; parked thread and nothing else: the server thread is not reading sockets,
  ;; it is serving the queue.
  (with-server
    (fn [{:keys [conn!]}]
      (let [stalled (conn!)
            working (conn!)]
        (ready working)
        (let [w (io/writer (.getOutputStream (:socket stalled)))]
          (.write w "{:type :get-state")
          (.flush w))
        ((:send! working) {:type :get-state :id "w1"})
        (is (answered? working "w1")
            "the other client is served while the first is mid-record")
        (is (= [:ready] (mapv :type ((:records stalled))))
            "and the stalled client was told nothing but its own agent's id, because
             it asked nothing else")))))

(deftest a-stalled-run-does-not-hold-the-other-agent
  ;; The gate's shape: one agent mid-run, another answering. A run's events reach
  ;; only the connection that owns it, which is what makes the two wires
  ;; independent.
  (with-server
    (fn [{:keys [conn!]}]
      (let [slow (conn!)
            fast (conn!)]
        (ready slow)
        (ready fast)
        ((:send! slow) {:type :prompt :id "s1" :message "stall please"})
        (is (answered? slow "s1")
            "the stalling prompt is answered when it is accepted")
        ((:send! fast) {:type :prompt :id "f1" :message "hello"})
        (is (support/wait-until #(seq ((:of-type fast) :agent-settled)) 10000)
            "the second agent runs to its end while the first is still running")
        (is (empty? ((:of-type slow) :agent-settled))
            "and the first agent's run has not settled")
        (is (not (contains? (set (map :agent-id ((:records fast))))
                            ((:agent-id slow))))
            "no event of the stalled run reached the other wire")))))

(deftest closing-a-connection-leaves-the-server-serving
  ;; A closed socket is one client fewer, not an outage: the connections that are
  ;; still there keep being served, and a later client gets an agent of its own.
  ;; That the closed connection's agent is *disposed* is the registry's own
  ;; contract (`test-agents`), exercised here through the only thing a wire
  ;; client can see: the server still answering.
  (with-server
    (fn [{:keys [conn!]}]
      (let [a (conn!)
            b (conn!)]
        (ready a)
        (ready b)
        ((:close! a))
        ((:send! b) {:type :get-state :id "b1"})
        (is (answered? b "b1")
            "the surviving connection still answers")
        (let [c (conn!)]
          (ready c)
          (is (not= ((:agent-id b)) ((:agent-id c)))
              "and a later connection gets an agent of its own"))))))

(deftest server-mode-is-inert-under-a-diagnostic-mount
  ;; `--dump-state` mounts every row of the profile, this one included: a
  ;; diagnostic mount hands the mode no `:on-result`, so it stays inert and the
  ;; dump proves the row composes without opening a socket.
  (let [{:keys [code out]} (support/run-main ["--profile" "server" "--dump-state"])]
    (is (= 0 code))
    (is (some #(and (str/includes? % ":mode-server") (str/includes? % ":active"))
              (str/split-lines out))
        "the mode row mounts and activates in a diagnostic boot")
    (is (not (str/includes? out ":listening"))
        "and nothing listened")))

(deftest a-port-that-is-not-a-number-is-a-usage-error
  (let [{:keys [code err]} (support/run-main ["--profile" "server" "--port" "abc"])]
    (is (= 1 code))
    (is (str/includes? err "--port needs a number"))))
