(ns kmet.libs.mcp.test-auth
  "Tests for the MCP authorization policy in kmet.libs.mcp.auth —
   resource canonicalization, the WWW-Authenticate challenge record and
   scope/resource selection. Store, flow and header provider follow in
   the rest of Phase 2."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.libs.mcp.auth :as auth]))

(deftest canonical-resource-uri-normalizes
  (testing "scheme and host are lowercased, path case is kept"
    (is (= "https://mcp.example.com/mcp"
           (auth/canonical-resource-uri "HTTPS://MCP.Example.com/mcp/")))
    (is (= "https://mcp.example.com:8443/MCP/V1"
           (auth/canonical-resource-uri "https://mcp.example.com:8443/MCP/V1///"))))
  (testing "query and fragment are dropped"
    (is (= "https://mcp.example.com/mcp"
           (auth/canonical-resource-uri "https://mcp.example.com/mcp?key=1")))
    (is (= "https://mcp.example.com/mcp"
           (auth/canonical-resource-uri "https://mcp.example.com/mcp#frag")))
    (is (= "https://mcp.example.com"
           (auth/canonical-resource-uri "https://mcp.example.com?q=1"))))
  (testing "a root path loses its trailing slash, an absent path stays absent"
    (is (= "https://mcp.example.com" (auth/canonical-resource-uri "https://mcp.example.com")))
    (is (= "https://mcp.example.com" (auth/canonical-resource-uri "https://mcp.example.com/")))
    (is (= "http://mcp.example.com" (auth/canonical-resource-uri "http://mcp.example.com///"))))
  (testing "only http(s) urls are resource indicators"
    (is (nil? (auth/canonical-resource-uri "ftp://mcp.example.com")))
    (is (nil? (auth/canonical-resource-uri "mcp.example.com/mcp")))
    (is (nil? (auth/canonical-resource-uri nil)))
    (is (nil? (auth/canonical-resource-uri 42)))))

(deftest parse-www-authenticate-reads-bearer-params
  (is (= {:resource-metadata "https://as.example.com/.well-known/oauth-protected-resource"
          :scope "read write"
          :error "insufficient_scope"}
         (auth/parse-www-authenticate
          (str "Bearer resource_metadata=\"https://as.example.com/.well-known/oauth-protected-resource\""
               ", scope=\"read write\", error=\"insufficient_scope\""))))
  (testing "param names are case-insensitive and land hyphenated"
    (is (= {:resource-metadata "https://prm"}
           (auth/parse-www-authenticate "Bearer RESOURCE_METADATA=\"https://prm\""))))
  (testing "a non-Bearer or absent header is nil"
    (is (nil? (auth/parse-www-authenticate "Basic realm=\"x\"")))
    (is (nil? (auth/parse-www-authenticate nil)))
    (is (nil? (auth/parse-www-authenticate ""))))
  (testing "unquoted params, and a Bearer challenge without any, are nil"
    (is (nil? (auth/parse-www-authenticate "Bearer realm=example")))
    (is (nil? (auth/parse-www-authenticate "Bearer"))))
  (testing "whitespace around the scheme and a leading param is tolerated"
    (is (= {:scope "a"} (auth/parse-www-authenticate "  Bearer   scope=\"a\""))))
  (testing "OWS around the auth-param separators is tolerated (RFC 9110)"
    (is (= {:scope "a" :error "b"}
           (auth/parse-www-authenticate "Bearer scope=\"a\" , error=\"b\"")))))

(deftest challenge-record-and-selection
  (let [s "test-auth-server-1"
        other "test-auth-server-2"]
    (try
      (is (nil? (auth/challenge s)))
      (auth/record-challenge! s "Bearer scope=\"chal\", resource_metadata=\"https://prm\"")
      (is (= {:scope "chal" :resource-metadata "https://prm"} (auth/challenge s)))
      (testing "a non-Bearer 401 does not overwrite the recorded challenge"
        (auth/record-challenge! s "Basic realm=\"x\"")
        (is (= {:scope "chal" :resource-metadata "https://prm"} (auth/challenge s))))
      (testing "scope selection: config wins, then the challenge, then the metadata"
        (is (= "cfg" (auth/effective-scopes s {:oauth {:scopes "cfg"}} nil)))
        (is (= "a b" (auth/effective-scopes s {:oauth {:scopes ["a" "b"]}} nil)))
        (is (= "chal" (auth/effective-scopes s {:oauth {}} nil)))
        (is (= "p q" (auth/effective-scopes other {} {:scopes_supported ["p" "q"]})))
        (is (nil? (auth/effective-scopes other {} {}))))
      (testing "resource selection: config :resource overrides the canonical url"
        (is (= "https://mcp.example.com/mcp"
               (auth/effective-resource {:url "HTTPS://MCP.Example.com/mcp/"})))
        (is (= "https://gateway.example.com"
               (auth/effective-resource {:url "https://mcp.example.com"
                                         :oauth {:resource "https://gateway.example.com"}})))
        (is (nil? (auth/effective-resource {:oauth {}}))))
      (finally
        (auth/clear-challenges! s)
        (auth/clear-challenges! other)))))

(deftest clear-challenges-forgets-everything
  (try
    (auth/record-challenge! "test-auth-a" "Bearer scope=\"a\"")
    (auth/record-challenge! "test-auth-b" "Bearer scope=\"b\"")
    (auth/clear-challenges!)
    (is (nil? (auth/challenge "test-auth-a")))
    (is (nil? (auth/challenge "test-auth-b")))
    (finally
      (auth/clear-challenges!))))
