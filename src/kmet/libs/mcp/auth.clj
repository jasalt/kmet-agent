(ns kmet.libs.mcp.auth
  "MCP authorization policy (§7.8) for HTTP servers.

   Phase 2 home for what the extension's auth namespace used to hold: the
   generic mechanics (RFC 8414 discovery, RFC 7591 DCR, PKCE loopback and
   RFC 8628 device flows, token exchange/refresh) live in kmet.libs.oauth;
   this namespace owns the MCP-specific policy built on them. The pieces
   that are pure policy land first — RFC 8707 resource canonicalization,
   the RFC 9728 `WWW-Authenticate` challenge record, and scope/resource
   selection. The credential store (file/keyring backends), the request
   auth header provider and the 2026 hardening (issuer binding, `iss`
   validation) follow in the rest of Phase 2.

   Hosts: the namespace is loaded by the extension (loader [:jolt :sci]),
   so it stays plain maps and functions — no protocols, records or Java
   interop."
  (:require [clojure.string :as str]))

;; ─── RFC 8707 resource indicator + RFC 9728 scope challenge ───────────────

(defn canonical-resource-uri
  "The RFC 8707 §2 canonical URI of the MCP server a token is for: the
   server url lowercased in scheme and host, without query or fragment,
   and without a trailing slash unless the path is only \"/\". Sent as
   the `resource` parameter of authorization and token requests — the
   authorization spec makes it mandatory in both, whatever the
   authorization server supports."
  [url]
  (when (string? url)
    (when-let [[_ scheme authority path] (re-find #"^(?i)(https?)://([^/?#]+)([^?#]*)" url)]
      (let [p (or path "")]
        (str (str/lower-case scheme) "://"
             (str/lower-case authority)
             (if (or (str/blank? p) (= p "/")) "" (str/replace p #"/+$" "")))))))

(defn parse-www-authenticate
  "The auth-param map of a `WWW-Authenticate: Bearer ...` challenge:
   {:resource-metadata \"...\" :scope \"a b\" :error \"insufficient_scope\"}.
   RFC 9728 §5.1 resource_metadata points at the protected-resource
   document; the scope parameter is the server's authoritative minimum
   for this request. nil when the header is absent or not a Bearer
   challenge."
  [header]
  (when (and (string? header) (str/starts-with? (str/trim header) "Bearer"))
    (let [params (str/split (subs (str/trim header) 6) #",")]
      (not-empty
       (into {}
             (keep (fn [param]
                     (when-let [[_ k v] (re-matches #"\s*([A-Za-z_-]+)\s*=\s*\"([^\"]*)\"\s*"
                                                    param)]
                       ;; resource_metadata → :resource-metadata, so both
                       ;; the RFC 9728 spelling and a hyphenated one land
                       ;; on the same key
                       [(keyword (str/replace (str/lower-case k) "_" "-")) v])))
             params)))))

;; the last WWW-Authenticate challenge seen per server (the transport
;; records it on every 401), so discovery and scope selection can use
;; what the server actually asked for
(defonce ^:private challenges (atom {}))

(defn challenge
  "The last recorded challenge params for a server, or nil."
  [name]
  (get @challenges name))

(defn record-challenge!
  "Remember a server's latest Bearer challenge. A non-Bearer or absent
   header leaves a previously recorded challenge in place."
  [name header]
  (when-let [params (parse-www-authenticate header)]
    (swap! challenges assoc name params))
  nil)

(defn clear-challenges!
  "Forget the recorded challenge for a server; with no argument, forget
   every recorded challenge (extension unload)."
  ([]
   (reset! challenges {})
   nil)
  ([name]
   (swap! challenges dissoc name)
   nil))

(defn scopes-string
  "Config :scopes — a string or a vector, joined with spaces."
  [cfg]
  (let [scopes (:scopes cfg)]
    (cond
      (nil? scopes) nil
      (string? scopes) scopes
      (sequential? scopes) (str/join " " scopes)
      :else (str scopes))))

(defn effective-scopes
  "The scopes to request for a server: config :scopes wins; else the
   scope the server challenged with (authoritative for the current
   request); else `scopes_supported` from the protected-resource
   metadata (spec, scope selection strategy)."
  [name definition prm]
  (or (scopes-string (:oauth definition))
      (:scope (challenge name))
      (let [supported (:scopes_supported prm)]
        (when (seq supported)
          (str/join " " supported)))))

(defn effective-resource
  "The RFC 8707 resource indicator for a server: its url, canonicalized.
   Config :resource overrides (a server fronted by a gateway)."
  [definition]
  (or (get-in definition [:oauth :resource])
      (canonical-resource-uri (:url definition))))
