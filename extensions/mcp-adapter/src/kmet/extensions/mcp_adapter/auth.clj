(ns kmet.extensions.mcp-adapter.auth
  "OAuth adapter for HTTP MCP servers (§7.8 of the design contract — pi:
   mcp-auth.ts / mcp-oauth-provider.ts / oauth-handler.ts /
   mcp-auth-flow.ts / mcp-callback-server.ts, adapted onto the generic
   machinery in kmet.libs.oauth).

   Thin adapter: server config → lib calls, token-store wiring (the host
   path for the plaintext fallback; the :file/:keyring backends live in
   the lib), the browser/callback-server interaction for the interactive
   flows, and status text. The extension cannot require kmet.ai.*, so the
   generic machinery lives in kmet.libs.oauth (RFC 8414 discovery,
   RFC 7591 DCR, PKCE loopback + RFC 8628 device flows, token
   exchange/refresh).

   The MCP-specific policy lives in kmet.libs.mcp.auth (mcp.md Phase 2):
   resource canonicalization, the WWW-Authenticate challenge record,
   scope/resource selection, the credential store, discovery, the token
   lifecycle (bearer/machine grants/refresh + make-auth-fns) and the
   request-auth header provider are there; the interactive flows and the
   2026 hardening remain here.

   Flow (per server, §7.8):
     1. token lookup — expired → refresh; missing/refresh-failed → flow
     2. discovery (RFC 9728 protected-resource metadata for the AS
        location — the 401 WWW-Authenticate `resource_metadata` when we
        have seen one, else the well-known probes — then RFC 8414 / OIDC
        authorization-server metadata; :authorization-server-url skips
        discovery)
     3. client registration (RFC 7591) unless :oauth {:client-id ...}
     4. authorization — PKCE loopback on an OS-assigned port (default), or
        the RFC 8628 device flow (forced via :flow :device, or auto when
        the metadata exposes a device endpoint and the host is headless)
     5. tokens stored; requests attach Authorization: Bearer; 401 with a
        stored refresh token → refresh once + retry once"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.libs.mcp.auth :as mcp-auth]
            [kmet.libs.oauth :as oauth-lib]))

;; ─── kmet.libs.mcp.auth re-exports ────────────────────────────────────────
;; The policy, discovery, request-auth and store machinery lives in the lib
;; (mcp.md Phase 2). These aliases keep the validation scripts' call surface
;; (auth/<name>) stable; the extension calls the lib directly where a name
;; is not part of that surface.

(def canonical-resource-uri mcp-auth/canonical-resource-uri)
(def parse-www-authenticate mcp-auth/parse-www-authenticate)
(def discover-meta mcp-auth/discover-meta)
(def make-auth-fns mcp-auth/make-auth-fns)
(def machine-token-cached? mcp-auth/machine-token-cached?)

;; ─── Token store (kmet.libs.mcp.auth) ─────────────────────────────────────
;; The :file / :keyring / :auto backends, their path/permission handling
;; and logout live in the lib (mcp.md Phase 2). The extension keeps what is
;; host policy — where the plaintext store file sits — plus the names
;; core.clj and the validation scripts call.

(defn store-path
  "The plaintext OAuth token store file (<agent-dir>/mcp-oauth.edn; the
   :file backend — keyring mode stores per-server secrets instead)."
  []
  (str (fs/path (config/agent-dir) "mcp-oauth.edn")))

(defn configure-storage!
  "Set the token-storage mode from the merged SETTINGS (:token-storage;
   default :auto) and the host store path. The MCP_TOKEN_STORAGE env var
   wins when set (testing / headless hosts). Call at init and after
   /mcp refresh."
  [settings]
  (let [env (System/getenv "MCP_TOKEN_STORAGE")
        mode (cond
               (and env (seq (str/trim env))) (keyword (str/trim env))
               (contains? (or settings {}) :token-storage) (:token-storage settings)
               :else :auto)]
    (mcp-auth/configure-storage! {:mode mode :path (store-path)})))

(def server-entry mcp-auth/server-entry)
(def store-server! mcp-auth/store-server!)

(def logout! mcp-auth/logout!)

;; ─── Discovery (kmet.libs.mcp.auth) ──────────────────────────────────────
;; RFC 9728 protected-resource probing, RFC 8414 / OIDC authorization-server
;; discovery and the required-endpoint check live in the lib; only the
;; flow-level PKCE gate stays here (run-flow! consults it).

(defn- verify-pkce-support!
  "OAuth 2.1 requires PKCE for the authorization-code flow and says a
   client MUST verify the authorization server supports it before
   authorizing: an absent `code_challenge_methods_supported` means no
   PKCE support, and we must refuse rather than send a challenge the
   server will ignore. Only the PKCE flow is checked — the RFC 8628
   device flow sends no challenge. Config :skip-pkce-verification
   overrides for broken servers."
  [name definition metadata]
  (let [cfg (:oauth definition)]
    (when (and (= :authorization-code (or (:grant cfg) :authorization-code))
               (not (true? (:skip-pkce-verification cfg)))
               (:authorization_endpoint metadata)
               (nil? (:code_challenge_methods_supported metadata)))
      (throw (ex-info (str "MCP auth failed: " name " does not advertise PKCE support "
                           "(no code_challenge_methods_supported in its authorization "
                           "server metadata) — refusing to authorize. Set "
                           ":oauth {:skip-pkce-verification true} to override.")
                      {:type :oauth-no-pkce})))))

;; ─── Callback server (process-wide, OS-assigned port) ─────────────────────

(defonce ^:private callback-state (atom nil))
(defonce ^:private current-flow (atom nil))

(defn- redirect-uri-for
  "The loopback redirect URI for the callback server (config
   :redirect-uri wins; must be an http loopback URI with an explicit
   port, pi parseOAuthRedirectUri). Returns [uri bind-port] — the bind
   port (the configured URI's port, or 0 for OS-assigned) feeds
   ensure-callback-server! so the browser callback reaches the server."
  [cfg default-port default-path]
  (if-let [configured (:redirect-uri cfg)]
    (let [uri (try (java.net.URI. configured) (catch Exception _ nil))
          host (some-> uri .getHost str/lower-case)
          port (some-> uri .getPort)]
      (when-not (and uri (= "http" (.getScheme uri))
                     (contains? #{"localhost" "127.0.0.1" "::1"} host)
                     (pos? port))
        (throw (ex-info (str "MCP auth failed: :redirect-uri must be an http:// "
                             "loopback URI with an explicit port")
                        {:type :oauth-invalid-config})))
      [configured port])
    [(str "http://" (oauth-lib/callback-host) ":" default-port default-path)
     default-port]))

(defn- ensure-callback-server!
  "Start the process-wide loopback callback server once. PORT is the bind
   port: a configured :redirect-uri supplies its explicit port (the
   browser callback must reach the server); 0 → OS assigns. PATH is the
   callback path (default /callback; a configured :redirect-uri's path is
   served instead). Every later flow reuses the bound port, so a DCR'd
   client's redirect URI stays valid. Returns {:port n :path str}."
  [& [port path]]
  (or @callback-state
      (locking callback-state
        (or @callback-state
            (let [callback-path (or path "/callback")
                  server (oauth-lib/start-callback-server
                          (or port 0)
                          (fn [{:keys [path query-params]}]
                            (let [flow @current-flow]
                              (cond
                                (not= path callback-path)
                                {:status 404
                                 :body (oauth-lib/oauth-error-html
                                        "Callback route not found.")}

                                (nil? flow)
                                {:status 400
                                 :body (oauth-lib/oauth-error-html
                                        "No OAuth flow is in progress.")}

                                (not= (:state query-params) (:state flow))
                                {:status 400
                                 :body (oauth-lib/oauth-error-html "State mismatch.")}

                                (nil? (:code query-params))
                                {:status 400
                                 :body (oauth-lib/oauth-error-html
                                        "Missing authorization code.")}

                                :else
                                (do (deliver (:code-p flow)
                                             {:code (:code query-params)})
                                    {:status 200
                                     :body (oauth-lib/oauth-success-html
                                            "MCP authentication completed. You can close this window.")})))))]
              (reset! callback-state {:server server
                                      :port (:port server)
                                      :path callback-path})
              @callback-state)))))

(defn- ensure-callback-redirect!
  "Ensure the process-wide callback server and return the redirect URI to
   use for this flow. The server binds ONCE: the first flow's
   :redirect-uri config (explicit port + path) wins; later flows reuse the
   bound server and derive the URI from it, so the authorize URL always
   matches the server the browser hits (a DCR'd client's registered URI
   stays valid)."
  [cfg]
  (if-let [configured (:redirect-uri cfg)]
    (let [[uri bind-port] (redirect-uri-for cfg 0 "/callback")
          uri-obj (try (java.net.URI. configured) (catch Exception _ nil))]
      (ensure-callback-server! bind-port (some-> uri-obj .getPath))
      uri)
    (let [{:keys [port path]} (ensure-callback-server!)]
      (str "http://" (oauth-lib/callback-host) ":" port path))))

(defn shutdown!
  "Close the callback server and drop machine-token caches (extension
   unload). Idempotent."
  []
  (mcp-auth/clear-machine-tokens!)
  (mcp-auth/clear-challenges!)
  (when-let [{:keys [server]} @callback-state]
    (try ((:close server)) (catch Exception _ nil))
    (reset! callback-state nil)
    (reset! current-flow nil)))

;; ─── Client info (config pre-registered or RFC 7591 DCR) ──────────────────

(defn- stored-client-id
  [name]
  (get-in (server-entry name) [:client-info :client-id]))

(defn- resolve-client-id!
  "The client id for a server: config :oauth {:client-id ...} wins; else
   the stored (DCR'd) client; else RFC 7591 dynamic registration against
   the metadata's registration_endpoint (registered once, persisted with
   the entry)."
  [name definition metadata]
  (let [cfg (:oauth definition)]
    (or (:client-id cfg)
        (stored-client-id name)
        (let [registration-endpoint (get metadata :registration_endpoint)]
          (when-not registration-endpoint
            (throw (ex-info (str "MCP auth failed: " name " has no OAuth client id and the "
                                 "authorization server does not support dynamic client "
                                 "registration. Set :oauth {:client-id ...} in mcp.edn.")
                            {:type :oauth-no-registration})))
          (let [redirect-uri (ensure-callback-redirect! cfg)
                client (oauth-lib/register-client
                        registration-endpoint
                        {:redirect-uris [redirect-uri]
                         :client-name "kmet"
                         :scope (mcp-auth/scopes-string cfg)})]
            (store-server! name (assoc (or (server-entry name) {})
                                       :client-info {:client-id (:client_id client)
                                                     :redirect-uris [redirect-uri]}))
            (:client_id client))))))

;; ─── Full flow (/mcp auth, §7.8.4) ────────────────────────────────────────

(defn- resolve-flow
  "Flow selection: config :flow (:pkce | :device | :auto default). :auto →
   PKCE loopback; the device flow is auto-selected when the metadata
   exposes a device endpoint and the host is headless (no UI to paste a
   redirect URL)."
  [cfg metadata interaction]
  (let [forced (:flow cfg)
        device-endpoint? (contains? metadata :device_authorization_endpoint)]
    (case forced
      :pkce :pkce
      :device :device
      :auto (if (and device-endpoint? (not (:has-ui interaction))) :device :pkce)
      nil (if (and device-endpoint? (not (:has-ui interaction))) :device :pkce)
      (throw (ex-info (str "MCP auth failed: unknown OAuth flow " forced
                           " (expected :auto, :pkce or :device)")
                      {:type :oauth-invalid-config})))))

(defn- authorize-url
  "The authorization endpoint URL with the PKCE challenge, state, scope,
   redirect URI and the RFC 8707 resource indicator (mandatory in
   authorization requests as well as token requests)."
  [authorize-endpoint client-id redirect-uri challenge state scope resource]
  (str authorize-endpoint
       "?response_type=code"
       "&client_id=" (oauth-lib/url-encode client-id)
       "&redirect_uri=" (oauth-lib/url-encode redirect-uri)
       "&code_challenge=" (oauth-lib/url-encode challenge)
       "&code_challenge_method=S256"
       "&state=" (oauth-lib/url-encode state)
       (when (seq scope)
         (str "&scope=" (oauth-lib/url-encode scope)))
       (when (seq resource)
         (str "&resource=" (oauth-lib/url-encode resource)))))

(defn- run-pkce-flow
  [name definition metadata interaction]
  (let [cfg (:oauth definition)
        {:keys [verifier challenge]} (oauth-lib/generate-pkce)
        state (oauth-lib/random-hex 16)
        redirect-uri (ensure-callback-redirect! cfg)
        client-id (resolve-client-id! name definition metadata)
        scope (mcp-auth/effective-scopes name definition (::mcp-auth/prm metadata))
        resource (mcp-auth/effective-resource definition)
        code-p (promise)
        authorize-endpoint (mcp-auth/required-endpoint metadata :authorization_endpoint name)]
    (reset! current-flow {:state state :code-p code-p})
    (try
      (let [url (authorize-url authorize-endpoint client-id redirect-uri
                               challenge state scope resource)]
        ((:notify interaction)
         {:type :auth-url :url url
          :instructions "Open the URL in your browser to authorize MCP access."})
        (try ((:open-url interaction) url) (catch Exception _ nil))
        (let [result (oauth-lib/wait-for-callback-or-manual
                      interaction code-p
                      {:type :manual-code
                       :message (str "Complete login in your browser, or paste the "
                                     "authorization code / redirect URL here:")}
                      600000)
              code (case (:source result)
                     :callback (:code (:value result))
                     :manual (let [parsed (oauth-lib/parse-authorization-input
                                           (:value result))]
                               (when (and (:state parsed)
                                          (not= (:state parsed) state))
                                 (throw (ex-info "OAuth state mismatch"
                                                 {:type :oauth-state-mismatch})))
                               (:code parsed))
                     :cancelled (throw (ex-info "Login cancelled"
                                                {:type :login-cancelled}))
                     :timeout (throw (ex-info "OAuth login timed out"
                                              {:type :oauth-timeout}))
                     :error (throw (:error result)))]
          (when-not code
            (throw (ex-info "Missing authorization code"
                            {:type :oauth-missing-code})))
          (let [tokens (oauth-lib/exchange-authorization-code
                        (mcp-auth/required-endpoint metadata :token_endpoint name)
                        {:client-id client-id
                         :code code
                         :code-verifier verifier
                         :redirect-uri redirect-uri
                         :scope scope
                         :resource resource})]
            (mcp-auth/store-tokens! name tokens)
            (store-server! name (assoc (server-entry name)
                                       :client-info {:client-id client-id
                                                     :redirect-uris [redirect-uri]}))
            :logged-in)))
      (finally
        ;; pi: manualAbort.abort — dismiss the pending manual-paste dialog
        ;; and unblock its prompt when the callback won (no-op when the
        ;; user already dismissed it)
        (when-let [abort-prompt! (:abort-prompt! interaction)]
          (abort-prompt!))
        (reset! current-flow nil)))))

(defn- run-device-flow
  [name definition metadata interaction]
  (let [client-id (resolve-client-id! name definition metadata)
        scope (mcp-auth/effective-scopes name definition (::mcp-auth/prm metadata))
        resource (mcp-auth/effective-resource definition)
        device (oauth-lib/start-device-authorization
                (mcp-auth/required-endpoint metadata :device_authorization_endpoint name)
                {:client-id client-id :scope scope :resource resource})
        token-endpoint (mcp-auth/required-endpoint metadata :token_endpoint name)]
    ((:notify interaction)
     {:type :device-code
      :user-code (:user-code device)
      :verification-uri (:verification-uri device)
      :expires-in-seconds (:expires-in device)})
    (try ((:open-url interaction) (:verification-uri device))
         (catch Exception _ nil))
    (let [tokens (oauth-lib/poll-oauth-device-code-flow
                  {:interval-seconds (:interval device)
                   :expires-in-seconds (:expires-in device)
                   :wait-before-first-poll true
                   :signal (:signal interaction)
                   :poll
                   (fn []
                     (let [raw (:body (oauth-lib/fetch-json
                                       token-endpoint
                                       {:method :post
                                        :headers {"Content-Type"
                                                  "application/x-www-form-urlencoded"
                                                  "Accept" "application/json"}
                                        :body (str "grant_type=urn:ietf:params:oauth:"
                                                   "grant-type:device_code"
                                                   "&device_code="
                                                   (oauth-lib/url-encode (:device-code device))
                                                   "&client_id="
                                                   (oauth-lib/url-encode client-id)
                                                   (when (seq scope)
                                                     (str "&scope="
                                                          (oauth-lib/url-encode scope)))
                                                   (when (seq resource)
                                                     (str "&resource="
                                                          (oauth-lib/url-encode resource))))
                                        :timeout 15000}))]
                       (cond
                         (string? (:access_token raw))
                         {:status :complete :value raw}

                         (string? (:error raw))
                         (case (:error raw)
                           "authorization_pending" {:status :pending}
                           "slow_down" {:status :slow_down
                                        :interval-seconds (:interval raw)}
                           {:status :failed
                            :message (str "Device flow failed: " (:error raw)
                                          (when (:error_description raw)
                                            (str ": " (:error_description raw))))})

                         :else
                         {:status :failed
                          :message "Invalid device token response"})))})]
      (mcp-auth/store-tokens! name {:access (:access_token tokens)
                                    :expires-in (:expires_in tokens)
                                    :refresh (:refresh_token tokens)
                                    :scope (:scope tokens)})
      (store-server! name (assoc (server-entry name)
                                 :client-info {:client-id client-id}))
      :logged-in)))

(defn run-flow!
  "Run the auth flow for a server (§7.8) — fresh login, replaces stored
   tokens. Machine grants (client-credentials / jwt-bearer) just fetch +
   cache a token, validating the config. INTERACTION: {:signal
   cancel-atom :has-ui bool :notify (fn [event-map]) :prompt (fn
   [prompt-map] → string) :open-url (fn [url])}. Returns :logged-in;
   throws on failure/cancel."
  [name definition interaction]
  (if (mcp-auth/machine-grant? definition)
    (do (mcp-auth/fetch-machine-token! name definition) :logged-in)
    (let [metadata (mcp-auth/discover-meta name definition)
          flow (resolve-flow (:oauth definition) metadata interaction)]
      ;; only the PKCE flow sends a code challenge — a device-only server
      ;; must not be refused for missing PKCE metadata
      (when (= :pkce flow)
        (verify-pkce-support! name definition metadata))
      (case flow
        :pkce (run-pkce-flow name definition metadata interaction)
        :device (run-device-flow name definition metadata interaction)))))

;; ─── Status (§9.5) ────────────────────────────────────────────────────────

(defn auth-status
  "Auth state for a server: nil (not configured) | :bearer | :logged-in |
   :expired | :none (oauth configured, no tokens) | :client-credentials |
   :jwt-bearer (machine grants — always available, tokens fetched on
   demand)."
  [name definition]
  (when (and (:url definition) (:auth definition))
    (case (:auth definition)
      :bearer (if (seq (mcp-auth/bearer-token definition)) :bearer :none)
      :oauth (cond
               (mcp-auth/machine-grant? definition) (mcp-auth/grant-of definition)
               (nil? (server-entry name)) :none
               (mcp-auth/token-expired? (server-entry name)) :expired
               :else :logged-in)
      nil)))
