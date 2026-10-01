(ns kmet.libs.mcp.test-protocol
  "Pure protocol helpers: constants, error construction and tools/call
   result formatting."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [kmet.libs.mcp.protocol :as protocol]))

(deftest constants
  (testing "revisions and identity"
    (is (string? protocol/protocol-version))
    (is (some #{protocol/protocol-version} protocol/supported-protocol-versions)
        "the requested revision is supported")
    (is (apply distinct? protocol/supported-protocol-versions))
    (is (= protocol/protocol-version (first protocol/supported-protocol-versions))
        "newest first — a server picking an older revision is still valid")
    (is (every? string? protocol/supported-protocol-versions)))
  (testing "timeouts"
    (is (pos? protocol/default-request-timeout-ms))
    (is (pos? protocol/initialize-timeout-ms))
    (is (pos? protocol/list-page-timeout-ms)))
  (testing "clientInfo"
    (is (string? (:name protocol/client-info)))
    (is (string? (:version protocol/client-info)))))

(deftest progress-tokens-are-unique
  (let [tokens (repeatedly 100 protocol/progress-token)]
    (is (every? string? tokens))
    (is (apply distinct? tokens))))

(deftest mcp-error-shape
  (let [e (protocol/mcp-error "boom")]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= "boom" (ex-message e)))
    (is (= :mcp-error (:type (ex-data e)))))
  (let [e (protocol/mcp-error "timeout" {:timeout-ms 5 :method "tools/call"})]
    (is (= :mcp-error (:type (ex-data e))))
    (is (= 5 (:timeout-ms (ex-data e))))
    (is (= "tools/call" (:method (ex-data e))))))

(deftest format-result-cases
  (testing "text blocks are joined"
    (is (= {:text "a\nb" :is-error false}
           (protocol/format-result
            {:content [{:type "text" :text "a"}
                       {:type "text" :text "b"}]}))))
  (testing "isError is surfaced"
    (is (= {:text "nope" :is-error true}
           (protocol/format-result
            {:content [{:type "text" :text "nope"}] :isError true}))))
  (testing "image blocks are summarized, not rendered"
    (let [r (protocol/format-result
             {:content [{:type "image" :mimeType "image/png" :data "abcd"}]})]
      (is (str/includes? (:text r) "image/png"))
      (is (str/includes? (:text r) "4 bytes"))
      (is (false? (:is-error r)))))
  (testing "structuredContent is pretty JSON when there is no text"
    (let [r (protocol/format-result
             {:content [] :structuredContent {:a [1 2]}})]
      (is (str/includes? (:text r) "\"a\""))
      (is (str/includes? (:text r) "1"))))
  (testing "empty content falls back"
    (is (= {:text "(no text content)" :is-error false}
           (protocol/format-result {:content []})))
    (is (= "(no text content)" (:text (protocol/format-result {})))))
  (testing "text wins over structuredContent"
    (is (= "hi" (:text (protocol/format-result
                        {:content [{:type "text" :text "hi"}]
                         :structuredContent {:a 1}}))))))
