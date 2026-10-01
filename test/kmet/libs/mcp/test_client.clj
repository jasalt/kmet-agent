(ns kmet.libs.mcp.test-client
  "Client-level helpers that need no transport: URI-template expansion and
   the capability-gated establish! flow (request!/notify! redefined)."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.mcp.client :as mcp]))

;; ─── URI templates ────────────────────────────────────────────────────────

(deftest expand-uri-template-basics
  (testing "string and keyword keys"
    (is (= "file:///a/b" (mcp/expand-uri-template "file:///{path}" {:path "a/b"})))
    (is (= "file:///a/b" (mcp/expand-uri-template "file:///{path}" {"path" "a/b"}))))
  (testing "multiple variables"
    (is (= "db://t/users/42"
           (mcp/expand-uri-template "db://{table}/users/{id}" {:table "t" :id 42}))))
  (testing "a missing variable is left in place (the server answers)"
    (is (= "file:///{path}" (mcp/expand-uri-template "file:///{path}" {})))
    (is (= "file:///x/{y}" (mcp/expand-uri-template "file:///{x}/{y}" {:x "x"}))))
  (testing "values are escaped so they cannot change the URI"
    (is (= "x/a%20b" (mcp/expand-uri-template "x/{v}" {:v "a b"})))
    (is (= "x/a%3Fb" (mcp/expand-uri-template "x/{v}" {:v "a?b"})))
    (is (= "x/a%23b" (mcp/expand-uri-template "x/{v}" {:v "a#b"})))
    (is (= "x/100%25" (mcp/expand-uri-template "x/{v}" {:v "100%"}))))
  (testing "no placeholders, nil args"
    (is (= "plain" (mcp/expand-uri-template "plain" nil)))))

;; ─── establish! capability gating ─────────────────────────────────────────

(defn- fake-conn [methods result-for]
  (fn [_conn method _params & _opts]
    (swap! methods conj method)
    (result-for method)))

(deftest establish!-queries-only-advertised-capabilities
  (let [methods (atom [])
        notified (atom [])]
    (with-redefs [mcp/request!
                  (fake-conn methods
                             (fn [method]
                               (case method
                                 "initialize" {:protocolVersion "2025-11-25"
                                               :serverInfo {:name "fake" :version "1"}
                                               :capabilities {:tools {} :prompts {} :resources {}}}
                                 "tools/list" {:tools [{:name "t"}]}
                                 "prompts/list" {:prompts [{:name "p"}]}
                                 "resources/list" {:resources [{:name "r" :uri "u"}]}
                                 "resources/templates/list"
                                 {:resourceTemplates [{:name "rt" :uriTemplate "u/{x}"}]}
                                 (throw (ex-info "unexpected method" {:method method})))))
                  mcp/notify! (fn [_conn method _params] (swap! notified conj method))]
      (let [est (mcp/establish! :conn)]
        (is (= "2025-11-25" (:protocol-version est)))
        (is (= {:name "fake" :version "1"} (:server-info est)))
        (is (= [{:name "t"}] (:tools est)))
        (is (= [{:name "p"}] (:prompts est)))
        (is (= [{:name "r" :uri "u"}] (:resources est)))
        (is (= [{:name "rt" :uriTemplate "u/{x}"}] (:resource-templates est)))
        (is (= ["notifications/initialized"] @notified))
        (is (= #{"initialize" "tools/list" "prompts/list"
                 "resources/list" "resources/templates/list"}
               (set @methods)))))))

(deftest establish!-skips-unadvertised-capabilities
  (let [methods (atom [])]
    (with-redefs [mcp/request!
                  (fake-conn methods
                             (fn [method]
                               (case method
                                 "initialize" {:protocolVersion "2025-11-25"
                                               :capabilities {}}
                                 (throw (ex-info "unexpected method" {:method method})))))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [est (mcp/establish! :conn)]
        (is (= [] (:tools est)))
        (is (= [] (:prompts est)))
        (is (= [] (:resources est)))
        (is (= [] (:resource-templates est)))
        (is (= ["initialize"] @methods)
            "only the handshake runs without advertised capabilities")))))

(deftest establish!-rejects-unsupported-revisions
  (with-redefs [mcp/request! (fn [_ _ _ & _] {:protocolVersion "1999-01-01"})
                mcp/notify! (fn [_ _ _] nil)]
    (let [e (try (mcp/establish! :conn) nil (catch Exception e e))]
      (is (some? e))
      (is (= :mcp-error (:type (ex-data e))))
      (is (re-find #"unsupported protocol version" (ex-message e))))))

(deftest establish!-tolerates-missing-template-support
  (let [methods (atom [])]
    (with-redefs [mcp/request!
                  (fake-conn methods
                             (fn [method]
                               (case method
                                 "initialize" {:protocolVersion "2025-11-25"
                                               :capabilities {:resources {}}}
                                 "resources/list" {:resources [{:name "r" :uri "u"}]}
                                 "resources/templates/list"
                                 (throw (ex-info "Method not found" {:code -32601}))
                                 (throw (ex-info "unexpected method" {:method method})))))
                  mcp/notify! (fn [_ _ _] nil)]
      (let [est (mcp/establish! :conn)]
        (is (= [{:name "r" :uri "u"}] (:resources est)))
        (is (= [] (:resource-templates est)))))))

(deftest establish!-propagates-other-resource-errors
  (with-redefs [mcp/request!
                (fn [_ method _ & _]
                  (case method
                    "initialize" {:protocolVersion "2025-11-25"
                                  :capabilities {:resources {}}}
                    "resources/list" {:resources []}
                    "resources/templates/list"
                    (throw (ex-info "boom" {:code -32000}))
                    nil))
                mcp/notify! (fn [_ _ _] nil)]
    (is (thrown? Exception (mcp/establish! :conn)))))
