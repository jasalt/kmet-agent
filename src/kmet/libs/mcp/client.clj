(ns kmet.libs.mcp.client
  "Transport-neutral MCP client: the request core, the initialize
   handshake, capability-gated catalog discovery and URI-template
   expansion. Transports live in kmet.libs.mcp.transport.{stdio,http,sse};
   protocol constants in kmet.libs.mcp.protocol.

   Public protocol: request! / notify! / close! / alive? / last-used.
   request! throws ex-info with :type :mcp-error on a JSON-RPC error,
   timeout, or transport death (§7.7 message patterns). Notifications
   received mid-request are dispatched — notifications/progress goes to
   the request's :on-notification callback (streaming tool-call
   progress), everything else to the conn-level handler. Stale responses
   (non-matching :id) are dropped and the wait continues. Every
   request!/notify! touches :last-used so the idle reaper can disconnect
   unused servers."
  (:require [clojure.string :as str]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.mcp.transport.http :as http]
            [kmet.libs.mcp.transport.sse :as sse]
            [kmet.libs.mcp.transport.stdio :as stdio]))

;; ─── request!/notify!/close!/alive? (§7.1) ────────────────────────────────

(defn last-used
  "The last activity timestamp (ms) for a connection — the idle reaper
   disconnects servers whose conn has been idle past the configured
   :idle-timeout."
  [conn]
  (transport/last-used conn))

(defn request!
  "Send a JSON-RPC request and return its :result. OPTS:
   {:timeout-ms n (default 120000) :on-notification (fn [notification])
   — receives notifications/progress events arriving mid-request
   (streaming tool-call progress)}. Throws ex-info on JSON-RPC error,
   timeout, or transport death (§7.7)."
  [conn method params & [{:keys [timeout-ms on-notification]}]]
  (let [id (swap! (:id-counter conn) inc)
        timeout (or timeout-ms protocol/default-request-timeout-ms)
        dispatch (fn []
                   (case (:transport conn)
                     :stdio (stdio/request! conn method params id timeout on-notification)
                     :streamable-http (http/request! conn method params id timeout on-notification)
                     :sse (sse/request! conn method params id timeout on-notification)))]
    ;; one request at a time per stdio/sse conn: both transports match
    ;; responses on one shared channel, so two waiters would consume (and
    ;; drop) each other's responses. A background list_changed resync now
    ;; queues behind an in-flight tool call instead of racing it
    ;; (streamable-http answers each request on its own response body and
    ;; stays concurrent).
    (if (= :streamable-http (:transport conn))
      (dispatch)
      ;; clj-kondo flags the map lookup as locally created; the lock object
      ;; is created once per conn (stdio/http/sse) and shared by
      ;; every caller of that conn
      #_{:clj-kondo/ignore [:locking-suspicious-lock]}
      (locking (:req-lock conn) (dispatch)))))

(defn- send-async!
  "Deliver a message that expects no answer (a notification)."
  [conn msg]
  (case (:transport conn)
    :stdio (stdio/send-async! conn msg)
    (:streamable-http :sse) (http/send-async! conn msg)))

(defn notify!
  "Send a JSON-RPC notification (no response expected)."
  [conn method params]
  (send-async! conn {:jsonrpc "2.0" :method method :params params}))

(defn close!
  "Close a connection: kill the stdio process tree, abort the active SSE
   stream (releases the blocked reader + reaps the transport), or DELETE
   a streamable-HTTP session (sent from a background thread, so the
   caller never waits on the server). OPTS: :terminate-http-session?
   false skips the DELETE entirely — teardown paths (session shutdown,
   a retry after a request timeout) rely on the session expiring on its
   own. Idempotent."
  ([conn] (close! conn {}))
  ([conn opts]
   (case (:transport conn)
     :stdio (stdio/close! conn)
     :streamable-http (http/close! conn opts)
     :sse (sse/close! conn))
   nil))

(defn alive?
  "True when the connection is still usable."
  [conn]
  (case (:transport conn)
    :stdio (stdio/alive? conn)
    :streamable-http (http/alive? conn)
    :sse (sse/alive? conn)))

;; ─── Handshake + discovery (§7.5) ─────────────────────────────────────────

(defn initialize!
  "Run the MCP handshake: initialize (60s timeout) → notifications/
   initialized. The revision the server selects is validated against
   kmet.libs.mcp.protocol/supported-protocol-versions (an answer outside
   that list is an error) and recorded in the conn :protocol-version atom,
   so streamable-HTTP requests after the handshake carry the negotiated
   MCP-Protocol-Version header. Returns {:protocol-version str
   :server-info map :capabilities map}."
  [conn]
  (let [result (request! conn "initialize"
                         {:protocolVersion protocol/protocol-version
                          :capabilities {}
                          :clientInfo protocol/client-info}
                         {:timeout-ms protocol/initialize-timeout-ms})
        version (or (:protocolVersion result) protocol/protocol-version)]
    (when-not (some #{version} protocol/supported-protocol-versions)
      (throw (protocol/mcp-error (str "MCP server selected unsupported protocol version "
                                      version)
                                 {:protocol-version version
                                  :supported protocol/supported-protocol-versions})))
    (when-let [pv (:protocol-version conn)]
      (reset! pv version))
    (notify! conn "notifications/initialized" {})
    {:protocol-version version
     :server-info (:serverInfo result)
     :capabilities (or (:capabilities result) {})}))

(defn list-all-tools
  "tools/list with cursor pagination (nextCursor loop, 30s per page)."
  [conn]
  (loop [cursor nil tools []]
    (let [result (request! conn "tools/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms protocol/list-page-timeout-ms})
          tools (into tools (:tools result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor tools)
        tools))))

(defn list-all-prompts
  "prompts/list with cursor pagination (30s per page)."
  [conn]
  (loop [cursor nil prompts []]
    (let [result (request! conn "prompts/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms protocol/list-page-timeout-ms})
          prompts (into prompts (:prompts result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor prompts)
        prompts))))

(defn get-prompt
  "prompts/get — result contains :messages; :arguments is a string map
   (omitted when empty)."
  [conn name arguments & [{:keys [timeout-ms]}]]
  (request! conn "prompts/get"
            (cond-> {:name name}
              (seq arguments) (assoc :arguments arguments))
            {:timeout-ms (or timeout-ms protocol/default-request-timeout-ms)}))

(defn list-all-resources
  "resources/list with cursor pagination (30s per page)."
  [conn]
  (loop [cursor nil resources []]
    (let [result (request! conn "resources/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms protocol/list-page-timeout-ms})
          resources (into resources (:resources result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor resources)
        resources))))

(defn list-all-resource-templates
  "resources/templates/list with cursor pagination (30s per page) —
   parameterized resources (file:///{path} and friends). A server whose
   resources are all templates exposes nothing through resources/list.
   Tolerates a server that advertises the resources capability but
   answers -32601 (templates are a sub-feature of resource discovery —
   failing the whole connect or resync over them would cost us the plain
   resources/tools too). Any other error propagates."
  [conn]
  (try
    (loop [cursor nil templates []]
      (let [result (request! conn "resources/templates/list"
                             (if cursor {:cursor cursor} {})
                             {:timeout-ms protocol/list-page-timeout-ms})
            templates (into templates (:resourceTemplates result))]
        (if-let [next-cursor (:nextCursor result)]
          (recur next-cursor templates)
          templates)))
    (catch Exception e
      (when-not (= -32601 (:code (ex-data e))) (throw e))
      [])))

(defn read-resource
  "resources/read — result contains :contents (text or blob blocks)."
  [conn uri & [{:keys [timeout-ms]}]]
  (request! conn "resources/read" {:uri uri}
            {:timeout-ms (or timeout-ms protocol/default-request-timeout-ms)}))

(defn- uri-escape
  "Percent-escape a template variable's value: everything outside the
   RFC 3986 unreserved / sub-delims / ':@/' set, plus control
   characters — so a space, ?, # or % cannot change the URI's meaning.
   Deliberately minimal: '/' survives inside a {path} variable (servers
   expect the raw path), and non-ASCII is left as-is rather than
   percent-encoded per byte."
  [s]
  (apply str
         (map (fn [c]
                (if (or (> (int c) 126)
                        (re-matches #"[A-Za-z0-9\-._~!$&'()*+,;=:@/]" (str c)))
                  (str c)
                  (format "%%%02X" (int c))))
              (str s))))

(defn expand-uri-template
  "Expand a level-1 URI template (RFC 6570 {var} placeholders) with
   ARGS, turning a resources/templates/list entry into a concrete
   resources/read URI. Argument keys may be strings or keywords. A
   variable with no matching argument is left in place, so the server
   answers with its own error instead of us guessing a value."
  [uri-template args]
  (let [args (or args {})]
    (str/replace (str uri-template)
                 #"\{([^{}]+)\}"
                 (fn [match]
                   (let [var-name (second match)
                         value (get args var-name (get args (keyword var-name)))]
                     (if (some? value)
                       (uri-escape (str value))
                       (first match)))))))

;; ─── Connect ──────────────────────────────────────────────────────────────

(defn establish!
  "Handshake + capability-gated catalog discovery on an existing CONN.
   prompts/resources are only queried when the server advertises the
   capability (an unadvertised method errors with -32601). Returns
   {:protocol-version :server-info :capabilities :tools :prompts
   :resources :resource-templates} — no :conn."
  [conn]
  (let [{:keys [protocol-version server-info capabilities]} (initialize! conn)
        capabilities (or capabilities {})]
    {:protocol-version protocol-version
     :server-info server-info
     :capabilities capabilities
     :tools (if (:tools capabilities) (list-all-tools conn) [])
     :prompts (if (:prompts capabilities) (list-all-prompts conn) [])
     :resources (if (:resources capabilities) (list-all-resources conn) [])
     :resource-templates (if (:resources capabilities)
                           (list-all-resource-templates conn)
                           [])}))

(defn connect!
  "Full connect for a DEFINITION: build the transport, handshake,
   catalog discovery. OPTS: :auth-headers / :on-401 (HTTP transports,
   §7.8 — :on-401 is called as (fn [response]) with the 401 response so
   it can read the WWW-Authenticate challenge, and returns fresh
   headers) / :on-notification (fn [conn msg] — server->client
   notifications other than progress, e.g. list_changed).
   Returns {:conn conn :tools [..] :prompts [..] :resources [..]
   :resource-templates [..] :protocol-version str :server-info map}.
   On any failure the transport is closed and the ex-info rethrown."
  [definition opts]
  (let [url (:url definition)
        conn (if url
               (case (:http-transport definition)
                 :sse (sse/connect! url (assoc opts :reconnect-fn initialize!))
                 (http/connect! url opts))
               (stdio/connect! definition opts))]
    (try
      (when (and url (= :sse (:transport conn)))
        (sse/open-stream! conn))
      (let [established (establish! conn)]
        (assoc established
               :conn (assoc conn :capabilities (:capabilities established))))
      (catch Exception e
        (close! conn)
        (throw e)))))
