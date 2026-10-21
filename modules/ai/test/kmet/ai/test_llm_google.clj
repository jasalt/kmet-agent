(ns kmet.ai.test-llm-google
  "Google Generative AI and Vertex: message conversion and the streaming
   end-to-end."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.ai.api.sse :as sse]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.anthropic-messages :as anthropic]
            [kmet.ai.api.google-generative-ai :as google]
            [kmet.ai.api.google-vertex :as vertex]
            [kmet.ai.api.bedrock-converse-stream :as bedrock]))

;; ─── Google message conversion ─────────────────────────────────────────────

(t/deftest test-google-messages
  (let [model {:id "gemini-2.5-pro" :name "Gemini"}
        [contents system] (@#'google/google-messages
                           [{:role :system :content [{:type :text :text "sys"}]}
                            {:role :user :content [{:type :text :text "hi"}]}
                            {:role :assistant :content [{:type :text :text "ok"}]
                             :tool-calls [{:id "t1" :name "read" :arguments {:path "a"}}]}
                            {:role :tool :tool-name "read" :is-error false
                             :content [{:type :tool_result :tool_use_id "t1" :content "saw it"}]}]
                           model)]
    (t/is (= "sys" system))
    (t/is (= [{:role "user" :parts [{:text "hi"}]}
              {:role "model" :parts [{:text "ok"}
                                     {:functionCall {:name "read" :args {:path "a"}}}]}
              {:role "user" :parts [{:functionResponse {:name "read" :response {:output "saw it"}}}]}]
             contents)
          "gemini-2.5 does not require tool-call ids (pi requiresToolCallId)"))
  (t/testing "gemini-3 requires explicit ids on functionCall/functionResponse"
    (let [model {:id "gemini-3-pro-preview" :name "Gemini 3"}
          [contents _] (@#'google/google-messages
                        [{:role :assistant :content [{:type :text :text "ok"}]
                          :tool-calls [{:id "t1/x" :name "read" :arguments {:path "a"}}]}
                         {:role :tool :tool-name "read" :is-error false
                          :content [{:type :tool_result :tool_use_id "t1/x" :content "saw it"}]}]
                        model)]
      (t/is (= {:functionCall {:name "read" :args {:path "a"} :id "t1_x"}}
               (second (:parts (first contents))))
            "ids are echoed, sanitized to [a-zA-Z0-9_-] (pi)")
      (t/is (= {:functionResponse {:name "read" :response {:output "saw it"} :id "t1_x"}}
               (first (:parts (second contents))))))))

(t/deftest test-google-empty-messages-dropped
  ;; pi google-shared: empty text parts are dropped and a message whose parts
  ;; come out empty is skipped ("the model intermittently ends mid-task turns
  ;; with a thought-only STOP (empty completion, no tool call)") — a recorded
  ;; empty/blank assistant entry must not reach Gemini as {:text ""}.
  (let [model {:id "gemini-2.5-pro" :name "Gemini"}
        [contents _] (@#'google/google-messages
                      [{:role :assistant :content []}
                       {:role :assistant :content [{:type :text :text "   "}]}
                       {:role :user :content []}
                       {:role :assistant :content []
                        :tool-calls [{:id "t1" :name "bash" :arguments {}}]}
                       {:role :assistant :content [{:type :text :text "real"}]}
                       {:role :user :content [{:type :text :text "  "}]}]
                      model)]
    (t/is (= [{:role "model" :parts [{:functionCall {:name "bash" :args {}}}]}]
             (take 1 contents))
          "empty and blank-only assistants are dropped; a tool call keeps the message")
    (t/is (= [{:role "model" :parts [{:text "real"}]}
              {:role "user" :parts [{:text "  "}]}]
             (drop 1 contents))
          "non-blank content passes through; whitespace-only USER text is kept (pi parity)")))

(t/deftest test-anthropic-thinking-replay
  ;; pi convertMessages thinking matrix: signed same-model reasoning replays
  ;; as a leading thinking block (required before tool_use with thinking on);
  ;; unsigned or cross-provider reasoning degrades to plain text; legacy
  ;; entries without provenance degrade like unsigned ones.
  (let [msg {:role :assistant :content []
             :thinking "r" :thinking-signature "SIG"
             :provider :anthropic :model "claude"
             :tool-calls [{:id "t" :name "bash" :arguments {}}]}
        ctx {:provider :anthropic :model "claude"}]
    (let [blocks (:content (first (anthropic/anthropic-messages [msg] ctx)))]
      (t/is (= [{:type "thinking" :thinking "r" :signature "SIG"}
                {:type "tool_use" :id "t" :name "bash" :input {}}]
               blocks)
            "signed same-model replay: thinking block precedes tool_use"))
    (let [blocks (:content (first (anthropic/anthropic-messages
                                   [(dissoc msg :thinking-signature)] ctx)))]
      (t/is (= [{:type "text" :text "r"}
                {:type "tool_use" :id "t" :name "bash" :input {}}] blocks)
            "unsigned same-model reasoning degrades to plain text before tool_use"))
    (let [blocks (:content (first (anthropic/anthropic-messages
                                   [(assoc msg :provider :opencode :model "other")] ctx)))]
      (t/is (= [{:type "text" :text "r"}
                {:type "tool_use" :id "t" :name "bash" :input {}}] blocks)
            "cross-provider reasoning degrades to plain text"))
    (let [blocks (:content (first (anthropic/anthropic-messages
                                   [(assoc msg :model "other")]
                                   {:provider :anthropic :model "claude"
                                    :allow-empty-signature true})))]
      (t/is (= [{:type "thinking" :thinking "r" :signature ""}
                {:type "tool_use" :id "t" :name "bash" :input {}}] blocks)
            "allow-empty-signature compat keeps unsigned reasoning as a block"))
    (let [blocks (:content (first (anthropic/anthropic-messages
                                   [(dissoc msg :thinking-signature :provider :model)] ctx)))]
      (t/is (= [{:type "text" :text "r"}
                {:type "tool_use" :id "t" :name "bash" :input {}}] blocks)
            "legacy entries without provenance degrade like unsigned"))))

(t/deftest test-google-thinking-replay
  ;; pi google-shared: same-provider-same-model reasoning replays as a
  ;; thought part (thoughtSignature echoed when captured); cross-provider
  ;; reasoning degrades to a plain text part; empty assistants stay dropped.
  (let [model {:id "gemini-2.5-pro" :name "Gemini"}
        convert #(@#'google/google-messages % model {:provider :google})
        [contents _] (convert
                      [{:role :assistant :content []}
                       {:role :assistant :content []
                        :thinking "h" :thinking-signature "GSIG"
                        :provider :google :model "gemini-2.5-pro"}
                       {:role :assistant :content []
                        :thinking "h" :provider :google :model "gemini-2.5-pro"}
                       {:role :assistant :content []
                        :thinking "x" :provider :other :model "m"}
                       {:role :user :content [{:type :text :text "q"}]}])]
    (t/is (= [{:role "model" :parts [{:text "h" :thought true :thoughtSignature "GSIG"}]}
              {:role "model" :parts [{:text "h" :thought true}]}
              {:role "model" :parts [{:text "x"}]}
              {:role "user" :parts [{:text "q"}]}]
             contents)
          "thought parts carry the signature only for same-model messages")))

(t/deftest test-bedrock-thinking-signature-replay
  ;; pi supportsThinkingSignature: only Claude accepts the signature field —
  ;; signed same-model reasoning carries it, unsigned Claude reasoning falls
  ;; back to plain text, non-Claude models keep signature-less reasoningContent.
  (let [model {:id "anthropic.claude-sonnet-4-5-20250929-v1:0"
               :name "Claude Sonnet 4.5" :provider :amazon-bedrock
               :api :bedrock-converse-stream}
        convert (fn [m] (first (@#'bedrock/bedrock-messages [m] model :short
                                                            {:provider :amazon-bedrock})))
        msg {:role :assistant :content []
             :thinking "r" :thinking-signature "BSIG"
             :provider :amazon-bedrock :model (:id model)}
        signed (:content (first (convert msg)))
        unsigned (:content (first (convert (dissoc msg :thinking-signature))))
        nc-model {:id "amazon.nova-pro-v1" :name "Nova" :provider :amazon-bedrock}]
    (t/is (= [{:reasoningContent {:reasoningText {:text "r" :signature "BSIG"}}}] signed))
    (t/is (= [{:text "r"}] unsigned) "unsigned Claude reasoning degrades to text")
    (let [nc-msg (assoc msg :thinking-signature "X")
          nc-result (@#'bedrock/bedrock-messages
                     [nc-msg] nc-model :short {:provider :amazon-bedrock})
          nc-block (-> nc-result ffirst :content first)]
      (t/is (= {:reasoningContent {:reasoningText {:text "r"}}} nc-block)
            "non-Claude models reject signatures — omitted (pi)"))))

(t/deftest test-google-event-parsing
  (t/is (= [{:type :text :content "Hi"}]
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}")))
  (t/is (= [{:type :thinking :content "think"}]
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"think\",\"thought\":true}]}}]}")))
  (let [evts (sse/parse-google-event
              "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read\",\"args\":{\"path\":\"a\"}}}]}}]}")]
    (t/is (= :tool-call (:type (first evts))))
    (t/is (= "read" (:name (first evts))))
    (t/is (= {:path "a"} (:arguments (first evts)))))
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-google-event
            "{\"candidates\":[{\"finishReason\":\"STOP\"}]}")))
  (t/is (= :length (:stop-reason (first (sse/parse-google-event
                                         "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}")))))
  (t/testing "a chunk can carry several events (pi reads all parts per chunk)"
    (let [evts (sse/parse-google-event
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"},{\"functionCall\":{\"name\":\"read\",\"args\":{}}}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":20,\"totalTokenCount\":30}}")]
      (t/is (= [:usage :text :tool-call :done] (mapv :type evts))
            "usage, all parts, and finish are all emitted from one chunk")))
  (t/is (= [{:type :usage :usage {:input 10 :output 20 :cache-read 0 :cache-write 0
                                  :reasoning 0 :total-tokens 30}}]
           (sse/parse-google-event
            "{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":20,\"totalTokenCount\":30}}"))))

;; ─── Google Vertex ─────────────────────────────────────────────────────────

(t/deftest test-vertex-endpoint-url
  (with-redefs [shared/getenv (fn [k]
                                (case k
                                  "GOOGLE_CLOUD_PROJECT" "my-project"
                                  "GCLOUD_PROJECT" nil
                                  "GOOGLE_CLOUD_LOCATION" "us-central1"
                                  nil))]
    (t/is (= "https://us-central1-aiplatform.googleapis.com/v1/projects/my-project/locations/us-central1/publishers/google/models/gemini-3-pro:streamGenerateContent?alt=sse"
             (@#'vertex/vertex-endpoint-url "https://{location}-aiplatform.googleapis.com" "gemini-3-pro")))
    (t/testing "custom base-url used verbatim"
      (t/is (= "https://my-proxy.example/v1/projects/my-project/locations/us-central1/publishers/google/models/gemini-3-pro:streamGenerateContent?alt=sse"
               (@#'vertex/vertex-endpoint-url "https://my-proxy.example" "gemini-3-pro")))))
  (t/testing "missing project → config error"
    (with-redefs [shared/getenv (fn [k] (when (= k "GOOGLE_CLOUD_LOCATION") "us-central1"))]
      (t/is (thrown? Exception (@#'vertex/vertex-endpoint-url "" "m")))))
  (t/testing "GCLOUD_PROJECT fallback"
    (with-redefs [shared/getenv (fn [k]
                                  (case k
                                    "GCLOUD_PROJECT" "alt-project"
                                    "GOOGLE_CLOUD_LOCATION" "europe-west1"
                                    nil))]
      (t/is (str/includes? (@#'vertex/vertex-endpoint-url "" "m") "alt-project")))))

