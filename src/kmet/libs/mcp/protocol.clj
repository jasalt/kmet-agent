(ns kmet.libs.mcp.protocol
  "MCP wire constants and pure protocol helpers: the revisions this client
   speaks, the client identity, error construction and result formatting.

   Shared by the transports (kmet.libs.mcp.transport.*) and the client
   (kmet.libs.mcp.client). No I/O."
  (:require [clojure.string :as str]
            [kmet.libs.json :as json]))

(def default-request-timeout-ms
  "Per-request timeout when the caller passes none."
  120000)

(def initialize-timeout-ms
  "Timeout for the initialize handshake (60s — servers may be cold-started)."
  60000)

(def list-page-timeout-ms
  "Timeout for each catalog list page (tools/prompts/resources/templates)."
  30000)

(def protocol-version
  "The revision this client requests in initialize."
  "2025-11-25")

(def supported-protocol-versions
  "Protocol revisions this client speaks. A server that answers
   `initialize` with a version outside this list is rejected — servers on
   older SDKs answer with their own latest revision, so all handshake-based
   revisions stay usable (2026-07-28 and later drop the handshake and are
   not reachable from here)."
  ["2025-11-25" "2025-06-18" "2025-03-26" "2024-11-05"])

(def client-info
  "The clientInfo sent in the initialize handshake."
  {:name "kmet-mcp-adapter" :version "0.1.0"})

(def ^:private progress-counter (atom 0))

(defn progress-token
  "A correlation id for the _meta.progressToken of one request.
   Progress is opt-in per request — a server only emits
   notifications/progress for requests carrying the token, so every call
   the client wants streamed must send one (spec, progress). Monotonic:
   the id only has to be unique within the session."
  []
  (str "kmet-" (swap! progress-counter inc)))

(defn mcp-error
  "ex-info with the §7.7 message patterns (extensions match on :mcp-error,
   the data map carries :status/:code/:timeout-ms where applicable)."
  ([message] (ex-info message {:type :mcp-error}))
  ([message data] (ex-info message (assoc data :type :mcp-error))))

;; ─── Result formatting (§7.6) ─────────────────────────────────────────────

(defn format-result
  "Flatten a tools/call result into text (§7.6): text/error content blocks
   joined with newlines; image → '[image: <mimeType>, <n> bytes — not
   rendered]'; empty content with structuredContent → pretty JSON; fallback
   '(no text content)'. Returns {:text str :is-error bool}."
  [result]
  (let [blocks (or (:content result) [])
        texts (keep (fn [b]
                      (case (:type b)
                        "text" (:text b)
                        "error" (:text b)
                        "image" (str "[image: " (or (:mimeType b) "?") ", "
                                     (count (or (:data b) ""))
                                     " bytes — not rendered]")
                        nil))
                    blocks)
        text (cond
               (seq texts) (str/join "\n" texts)
               (seq (:structuredContent result))
               (json/generate-string (:structuredContent result) {:pretty true})
               :else "(no text content)")]
    {:text text :is-error (true? (:isError result))}))
