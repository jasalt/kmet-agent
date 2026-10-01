(ns kmet.libs.mcp.test-transport-stdio
  "Stdio transport argv composition (the process-level cases stay in the
   extension's scripts/validate-client.bb and e2e.bb)."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.mcp.transport.stdio :as stdio]))

(def ^:private stdio-argv @#'stdio/stdio-argv)

(deftest argv-forms
  (testing "string command + args"
    (is (= ["npx" "-y" "server"] (stdio-argv {:command "npx" :args ["-y" "server"]})))
    (is (= ["solo"] (stdio-argv {:command "solo" :args []}))))
  (testing "vector command is the full argv (no shell), merged with args"
    (is (= ["bb" "script.clj" "--flag"] (stdio-argv {:command ["bb" "script.clj"] :args ["--flag"]}))))
  (testing "args only are preserved as sent"
    (is (= ["x" "a b" "c"] (stdio-argv {:command "x" :args ["a b" "c"]})))))
