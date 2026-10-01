#!/usr/bin/env bb
;; Run every mcp-adapter validation script with the classpath a direct run
;; needs (the extension's src, the repo src, and data.json from the local
;; Maven cache — override with DATA_JSON or pass the jar as $1).
;;
;; Usage: bb scripts/validate-all.bb [data.json-path]
(require '[babashka.process :as proc])

(def data-json
  (or (first *command-line-args*)
      (System/getenv "DATA_JSON")
      (str (System/getenv "HOME")
           "/.m2/repository/org/clojure/data.json/2.4.0/data.json-2.4.0.jar")))

(def cp (str "../../src:src:" data-json))

(def runs
  [["validate-names.bb"]
   ["validate-client.bb" "scripts/fake-mcp-server.bb" "scripts/fake-http-mcp-server.bb"]
   ["validate-config.bb"]
   ["validate-oauth.bb" "scripts/fake-oauth-server.bb"]
   ["validate-panel.bb"]
   ["validate-script.bb" "scripts/fake-mcp-server.bb"]
   ["e2e.bb" "scripts/fake-mcp-server.bb"]])

(def failures (atom 0))

(doseq [[script & argv] runs]
  (println (str "\n════ " script " ════"))
  (let [{:keys [exit]} (apply proc/shell {:out :inherit :err :inherit :continue true}
                                   "bb" "-cp" cp (str "scripts/" script) argv)]
    (when-not (zero? exit)
      (swap! failures inc)
      (println (str "FAILED: " script " (exit " exit ")")))))

(println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
(System/exit (if (zero? @failures) 0 1))
