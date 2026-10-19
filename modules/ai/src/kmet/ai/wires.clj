(ns kmet.ai.wires
  "The api-type → request-builder table.

   This namespace is the one place that names the per-wire builders under
   kmet.ai.api.*, so kmet.ai.llm dispatches through the table instead of an
   arms list, and adding a wire is one row here plus one file under
   kmet.ai.api — dispatch never changes shape. (pi goes further: each
   provider's stream implementation imports its own api/* module. kmet's
   model catalog carries the api type as data, so the table has to exist;
   what it keeps is that the dispatcher names no wire and this table is a
   leaf no other namespace requires.)"
  (:require
   [kmet.ai.api.anthropic-messages :as anthropic]
   [kmet.ai.api.azure-openai-responses :as azure]
   [kmet.ai.api.bedrock-converse-stream :as bedrock]
   [kmet.ai.api.google-generative-ai :as google]
   [kmet.ai.api.google-vertex :as vertex]
   [kmet.ai.api.mistral-conversations :as mistral]
   [kmet.ai.api.openai-codex-responses :as codex]
   [kmet.ai.api.openai-completions :as openai]
   [kmet.ai.api.openai-responses :as responses]))

(def wires
  "Wire api-type → the var holding `(fn [opts])`, the per-wire request
   builder. The table holds vars, not function values, so a `with-redefs` of
   a builder reaches dispatch — how the tests mock a wire — and a REPL can
   swap one. OPTS carries :model-record, :provider-record, :effort, :api-key
   and the caller's own keys (see kmet.ai.llm/send-message)."
  {:openai-completions #'openai/openai-request
   :openai-responses #'responses/responses-request
   :openai-codex-responses #'codex/codex-request
   :azure-openai-responses #'azure/azure-request
   :anthropic-messages #'anthropic/anthropic-request
   :google-generative-ai #'google/google-request
   :mistral-conversations #'mistral/mistral-request
   :google-vertex #'vertex/vertex-request
   :bedrock-converse-stream #'bedrock/bedrock-request})

(defn request-fn
  "The request builder API currently names, or a throwing lookup naming the
   known set."
  [api]
  (if-let [builder (get wires api)]
    @builder
    (throw (ex-info (str "Unknown api-type: " (pr-str api)
                         " — known: " (pr-str (sort (keys wires))))
                    {:cause ::unknown-api-type
                     :api api
                     :known (set (keys wires))}))))
