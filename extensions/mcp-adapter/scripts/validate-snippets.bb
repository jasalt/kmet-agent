#!/usr/bin/env bb
;; Prompt-snippet validation for the mcp-adapter (§10.5): a direct tool's
;; :prompt-snippet is what the system prompt's "Available tools" list shows,
;; and kmet.app.system-prompt joins snippets with newlines — one tool per
;; line. MCP descriptions are arbitrary server prose (often multi-line and
;; indented), so the adapter must flatten a description before truncating it.
;; Pure — no servers, no state.
;;
;; Usage: bb -cp src:../../modules/libs/src scripts/validate-snippets.bb
(require '[clojure.string :as str]
         '[kmet.extensions.mcp-adapter.catalog :as catalog])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(defn snippet [description] (catalog/prompt-snippet description 100))

(println "── description flattening ──")
(check "single-line description passes through"
       (= "Echo the message back" (snippet "Echo the message back")))
(check "multi-line description collapses onto one line"
       (= "Fetch a page. Returns clean markdown."
          (snippet "Fetch a page.\n\n   Returns clean markdown.")))
(check "no newline survives a CRLF + tab description"
       (not (str/includes? (snippet "a\r\nb\tc  d") "\n")))
(check "newlines, tabs and repeated spaces become one space"
       (= "a b c d e" (snippet "a\n\n\t b\r\n  c\td  e")))
(check "leading and trailing whitespace trimmed"
       (= "trimmed" (snippet "\n\n  trimmed  \n")))
(check "already-flattened text keeps single spaces"
       (= "one two three" (snippet "one two three")))

(println "\n── truncation ──")
(check "text at the limit is not truncated"
       (= "12345" (catalog/prompt-snippet "12345" 5)))
(check "long text is cut to N chars plus an ellipsis"
       (= "12345…" (catalog/prompt-snippet "1234567890" 5)))
(check "the cut is applied to the flattened text"
       (let [s (snippet (apply str (repeat 30 "word ")))]
         (and (= 101 (count s))
              (str/ends-with? s "…")
              (not (str/includes? s "\n")))))
(check "a description that only overflows with its newlines still flattens"
       (= "alpha beta" (catalog/prompt-snippet "alpha\n\nbeta" 100)))

(println "\n── missing descriptions ──")
(check "nil becomes empty, not \"nil\"" (= "" (snippet nil)))
(check "empty description stays empty" (= "" (snippet "")))
(check "whitespace-only description stays empty" (= "" (snippet " \n\t ")))

(println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
(System/exit (if (zero? @failures) 0 1))
