(ns kmet.libs.mcp.auth
  "MCP authorization policy (§7.8) for HTTP servers.

   Phase 2 home for what the extension's auth namespace used to hold: the
   generic mechanics (RFC 8414 discovery, RFC 7591 DCR, PKCE loopback and
   RFC 8628 device flows, token exchange/refresh) live in kmet.libs.oauth;
   this namespace owns the MCP-specific policy built on them — RFC 8707
   resource canonicalization, the RFC 9728 `WWW-Authenticate` challenge
   record, scope/resource selection and the credential store (:file and
   OS-keyring backends). The request auth header provider and the 2026
   hardening (issuer binding, `iss` validation) follow in the rest of
   Phase 2.

   Hosts: the namespace is loaded by the extension (loader [:jolt :sci]),
   so it stays plain maps and functions — no protocols or records — and
   uses the same host surface as the other libs (System/getenv and
   System/getProperty, babashka.fs, babashka.process)."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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

;; ─── Credential store ─────────────────────────────────────────────────────
;; Where a server's tokens and DCR client info live across runs. Two
;; backends, selected by configure-storage!; :auto picks the best available:
;;
;;   :file    — a plaintext EDN file with 0600 perms and an atomic write, at
;;              the path the host configures (the extension passes
;;              <agent-dir>/mcp-oauth.edn). The only backend on
;;              Termux/Android and hosts without a keyring tool.
;;   :keyring — the OS credential store via platform tools: macOS `security`
;;              (generic-password), Linux `secret-tool` (libsecret), Windows
;;              Credential Manager via a PowerShell P/Invoke
;;              (CredWrite/CredRead/CredDelete). Per-server secrets, service
;;              "kmet-mcp" / account "oauth:<server>", payload = pr-str of
;;              the entry map (compact — gnome-keyring's GKeyFile backend
;;              corrupts multiline secrets, pi parity).
;;   :auto    — keyring when a platform tool is available, else the
;;              plaintext file.

(defonce ^:private storage-config
  ;; {:mode :auto | :file | :keyring, :path <plaintext store file>} — reset
  ;; by configure-storage!, read by every store operation
  (atom {:mode :auto :path nil}))

(defn configure-storage!
  "Configure the credential store for this host. OPTS:
     :mode — :auto (default) | :file | :keyring; anything else falls back
             to :auto.
     :path — the plaintext store file for the :file backend. The library
             has no default path: the host owns the location (the
             extension passes <agent-dir>/mcp-oauth.edn).
   Call at init and whenever the host config changes."
  [{:keys [mode path]}]
  (reset! storage-config
          {:mode (if (contains? #{:auto :keyring :file} mode) mode :auto)
           :path path})
  nil)

(defn- store-path
  "The configured plaintext store file for the :file backend, or nil."
  []
  (:path @storage-config))

(defn- read-text
  [path]
  (when (and path (fs/exists? path))
    (slurp path)))

(defn- write-text
  [path text]
  (spit path (str text)))

(defn- read-edn
  [path]
  (try
    (when-let [text (read-text path)]
      (let [raw (edn/read-string {:default (fn [_ _] nil)} text)]
        (when (map? raw) raw)))
    (catch Exception _ nil)))

(defn- write-file-0600
  "Atomic write (temp + rename) with 0600 perms (best-effort — Windows has
   no posix perms)."
  [path content]
  (let [tmp (str path ".tmp")]
    ;; a bare filename has no parent directory to create
    (when-let [parent (fs/parent path)]
      (fs/create-dirs parent))
    (write-text tmp content)
    (try (fs/set-posix-file-permissions tmp "rw-------") (catch Exception _ nil))
    (fs/move tmp path {:replace-existing true})
    nil))

;; ─── :file backend ────────────────────────────────────────────────────────

(def ^:private store-lock
  ;; serializes the file store's read-modify-write — two concurrent logins
  ;; would otherwise lose one of the entries; reads hold it too so a
  ;; replace-during-read (Windows) cannot surface as an empty store.
  ;; Reentrant: store-server!/clear-server! hold it across read + write.
  (Object.))

(defn- read-file-store
  []
  (locking store-lock
    (or (read-edn (store-path)) {:servers {}})))

(defn- write-file-store!
  [store]
  (locking store-lock
    (let [path (store-path)]
      (when (str/blank? (str path))
        (throw (ex-info "MCP credential store: no :file path configured"
                        {:type :mcp-store-unconfigured})))
      (write-file-0600 path (pr-str store)))))

;; ─── :keyring backend ─────────────────────────────────────────────────────

(def ^:private keyring-service "kmet-mcp")

(defn- account-for
  [name]
  (str "oauth:" name))

(defn- keyring-tool
  "The platform keyring tool as an argv vector, or nil when unavailable:
   macOS security, Linux secret-tool, Windows PowerShell (Credential
   Manager P/Invoke). Termux has none."
  []
  (cond
    (System/getenv "TERMUX_VERSION") nil
    (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac")
    (if (fs/which "security") ["security"] nil)
    (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win")
    (cond
      (fs/which "powershell.exe") ["powershell.exe" "-NoProfile" "-NonInteractive" "-Command"]
      (fs/which "pwsh") ["pwsh" "-NoProfile" "-NonInteractive" "-Command"]
      :else nil)
    :else (if (fs/which "secret-tool") ["secret-tool"] nil)))

(defn keyring-available?
  "True when the current platform has a keyring tool (the :auto backend
   picks :keyring exactly then)."
  []
  (boolean (keyring-tool)))

(defn storage-kind
  "The effective storage backend (:file | :keyring) — for status text."
  []
  (let [mode (:mode @storage-config)]
    (if (and (= mode :keyring) (not (keyring-available?)))
      ;; configured keyring but no tool: report file (reads/writes fall
      ;; back below)
      :file
      (if (= mode :auto)
        (if (keyring-available?) :keyring :file)
        mode))))

(defn- run-tool
  "Run a keyring tool argv; STDIN is the payload when given. Returns
   {:ok true :out str} or {:ok false :error str}."
  [argv & [stdin]]
  (try
    (let [p (apply proc/process argv
                   {:in (if stdin :stream :discard)
                    :out :stream :err :stream})]
      (when stdin
        (io/copy stdin (:in p))
        (try (.close (:in p)) (catch Exception _ nil)))
      (let [r (deref p 15000 nil)]
        (if (nil? r)
          (do
            ;; never leave the tool running behind a timeout
            (try (proc/destroy-tree p) (catch Exception _ nil))
            {:ok false :error "keyring tool timed out"})
          (let [out (or (some-> (:out r) slurp) "")
                err (or (some-> (:err r) slurp) "")]
            (if (zero? (:exit r))
              {:ok true :out out}
              {:ok false :error (str err " (exit " (:exit r) ")")})))))
    (catch Exception e
      {:ok false :error (ex-message e)})))

(defn- shell-quote
  "Single-quote for PowerShell argument passing (embedded quotes doubled)."
  [s]
  (str "'" (str/replace s "'" "''") "'"))

(defn- read-edn-from-string
  [s]
  (try
    (let [parsed (edn/read-string {:default (fn [_ _] nil)} s)]
      (when (map? parsed) parsed))
    (catch Exception _ nil)))

(def ^:private windows-cred-script-cache (atom nil))

(def ^:private windows-cred-body
  ;; PowerShell Credential-Manager P/Invoke (CredWrite/CredRead/
  ;; CredDelete). The script reads $op/$t/$p; missing read entries exit 0
  ;; with no output (normal — nothing stored yet). Windows-only; untested
  ;; on real Windows hosts (no way to run one here — recorded limitation).
  (str "$ErrorActionPreference='Stop'\n"
       "Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;using System.Text;"
       "public class KmetCred{"
       "[StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]"
       "public struct CRED{public uint Flags;public uint Type;public IntPtr TargetName;"
       "public IntPtr Comment;public long LastWritten;public uint BlobSize;public IntPtr Blob;"
       "public uint Persist;public uint AttrCount;public IntPtr Attrs;public IntPtr Alias;"
       "public IntPtr UserName;}"
       "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]"
       "public static extern bool CredRead(string t,uint ty,uint f,out IntPtr c);"
       "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]"
       "public static extern bool CredWrite(ref CRED c,uint f);"
       "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]"
       "public static extern bool CredDelete(string t,uint ty,uint f);"
       "[DllImport(\"advapi32.dll\")]public static extern void CredFree(IntPtr b);}'\n"
       "if($op -eq 'read'){"
       "$h=[IntPtr]::Zero;"
       "if([KmetCred]::CredRead($t,1,0,[ref]$h)){"
       "$c=[Runtime.InteropServices.Marshal]::PtrToStructure($h,[KmetCred+CRED]);"
       "$n=[int]$c.BlobSize;"
       "if($n -gt 0){$b=New-Object byte[] $n;[Runtime.InteropServices.Marshal]::Copy($c.Blob,$b,0,$n);"
       "[Console]::Out.WriteLine([Text.Encoding]::UTF8.GetString($b))}"
       "[Runtime.InteropServices.Marshal]::FreeCoTaskMem($c.TargetName);[KmetCred]::CredFree($h)}"
       "}elseif($op -eq 'write'){"
       "$c=New-Object KmetCred+CRED;$c.Type=1;$c.Persist=2;"
       "$c.TargetName=[Runtime.InteropServices.Marshal]::StringToCoTaskMemUni($t);"
       "$b=[Text.Encoding]::UTF8.GetBytes($p);$c.BlobSize=$b.Length;"
       "$c.Blob=[Runtime.InteropServices.Marshal]::AllocCoTaskMem($b.Length);"
       "[Runtime.InteropServices.Marshal]::Copy($b,0,$c.Blob,$b.Length);"
       "if(-not [KmetCred]::CredWrite([ref]$c,0)){throw 'CredWrite failed'}"
       "[Runtime.InteropServices.Marshal]::FreeCoTaskMem($c.TargetName);"
       "[Runtime.InteropServices.Marshal]::FreeCoTaskMem($c.Blob)"
       "}else{[KmetCred]::CredDelete($t,1,0)|Out-Null}"))

(defn- windows-cred-script
  "The full PowerShell -Command body for an operation (read/write/delete)
   on TARGET with optional PAYLOAD (variables inlined — PowerShell -Command
   does not pass $args reliably across versions). Cached base body."
  [op target & [payload]]
  (str "$op=" (shell-quote op) ";$t=" (shell-quote target)
       ";$p=" (if payload (shell-quote payload) "$null") ";"
       @windows-cred-script-cache))

;; initialize the cached script body at load (top-level form, after both
;; defs — sci evaluates in order)
(reset! windows-cred-script-cache windows-cred-body)

(defn- keyring-read
  "The stored entry for NAME from the OS keyring, or nil. macOS/Linux print
   the secret on stdout; Windows PowerShell prints the payload line."
  [name]
  (let [tool (keyring-tool)]
    (cond
      (nil? tool) nil
      (= "security" (first tool))
      (let [r (run-tool (conj tool "find-generic-password" "-a" (account-for name)
                              "-s" keyring-service "-w"))]
        (when (:ok r)
          (let [secret (str/trim (:out r))]
            (when (seq secret)
              (read-edn-from-string secret)))))

      (= "secret-tool" (first tool))
      (let [r (run-tool (conj tool "lookup" "service" keyring-service
                              "account" (account-for name)))]
        (when (:ok r)
          (let [secret (str/trim (:out r))]
            (when (seq secret)
              (read-edn-from-string secret)))))

      :else
      (let [r (run-tool (conj tool (windows-cred-script "read" (account-for name))))]
        (when (:ok r)
          (let [secret (str/trim (:out r))]
            (when (seq secret)
              (read-edn-from-string secret))))))))

(defn- keyring-write!
  "Store the ENTRY for NAME in the OS keyring. Returns true on success."
  [name entry]
  (let [tool (keyring-tool)
        payload (pr-str entry)]
    (cond
      (nil? tool) false
      (= "security" (first tool))
      (:ok (run-tool (conj tool "add-generic-password" "-U" "-a" (account-for name)
                           "-s" keyring-service "-w" payload)))

      (= "secret-tool" (first tool))
      (:ok (run-tool (conj tool "store" "--label=kmet-mcp" "service" keyring-service
                           "account" (account-for name))
                     payload))

      :else
      (:ok (run-tool (conj tool (windows-cred-script "write" (account-for name) payload)))))))

(defn- keyring-clear!
  "Delete the stored entry for NAME. Missing entries are not an error."
  [name]
  (let [tool (keyring-tool)]
    (when tool
      (cond
        (= "security" (first tool))
        (run-tool (conj tool "delete-generic-password" "-a" (account-for name)
                        "-s" keyring-service))

        (= "secret-tool" (first tool))
        (run-tool (conj tool "clear" "service" keyring-service
                        "account" (account-for name)))

        :else
        (run-tool (conj tool (windows-cred-script "delete" (account-for name)))))))
  nil)

;; ─── backend dispatch ─────────────────────────────────────────────────────

(defn- keyring-mode?
  []
  (= :keyring (storage-kind)))

(defn server-entry
  "The stored {:tokens .. :client-info ..} entry for a server, or nil —
   from the OS keyring in :keyring mode, else the plaintext file."
  [name]
  (if (keyring-mode?)
    (keyring-read name)
    (get-in (read-file-store) [:servers name])))

(defn store-server!
  "Persist the {:tokens .. :client-info ..} entry for a server."
  [name entry]
  (if (keyring-mode?)
    (keyring-write! name entry)
    (locking store-lock
      (write-file-store! (assoc-in (read-file-store) [:servers name] entry)))))

(defn- clear-server!
  "Forget a server's stored entry (both backends)."
  [name]
  (if (keyring-mode?)
    (keyring-clear! name)
    (locking store-lock
      (let [store (read-file-store)]
        (when (contains? (:servers store) name)
          (write-file-store! (update store :servers dissoc name)))))))

(defn logout!
  "Forget a server's stored credentials and its recorded 401 challenge."
  [name]
  (clear-challenges! name)
  (clear-server! name))
