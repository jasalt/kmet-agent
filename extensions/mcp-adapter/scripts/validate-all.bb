#!/usr/bin/env bb
;; Run every mcp-adapter validation script with the classpath a direct run
;; needs. `bb -cp` replaces the config classpath, so a bare run has neither
;; the module src roots (app code lives under modules/*/src — there is no
;; repo-root `src`) nor the bundled-extension Maven deps (malli, cljfmt,
;; parinferish). Ask a bare bb, launched from the repo root, for the repo's own
;; classpath instead of hardcoding either; prepend this extension's src.
;;
;; Usage: bb scripts/validate-all.bb
(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.string :as str])

(def repo-root
  (-> *file* fs/normalize fs/parent fs/parent fs/parent fs/parent))

(def cp
  (str "src:"
       (str/trim (:out (proc/shell {:dir (str repo-root) :out :string :err :string}
                                   "bb" "-e"
                                   "(print (babashka.classpath/get-classpath))")))))

(def runs
  [["validate-names.bb"]
   ["validate-snippets.bb"]
   ["validate-client.bb" "scripts/fake-mcp-server.bb" "scripts/fake-http-mcp-server.bb"]
   ["validate-config.bb"]
   ["validate-oauth.bb" "scripts/fake-oauth-server.bb"]
   ["validate-panel.bb"]
   ["validate-script.bb" "scripts/fake-mcp-server.bb"]
   ["e2e.bb" "scripts/fake-mcp-server.bb" "scripts/fake-http-mcp-server.bb"]
   ["validate-protocol.bb" "scripts/fake-mcp-server.bb" "scripts/fake-http-mcp-server.bb"]])

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
