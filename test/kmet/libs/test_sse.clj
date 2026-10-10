(ns kmet.libs.test-sse
  (:require [clojure.test :as t]
            [kmet.libs.sse :as sse]))

;; ─── Event parsing ─────────────────────────────────────────────────────────

(t/deftest test-parse-sse-line
  (t/is (= [nil "hi"] (sse/parse-sse-line "data: hi")))
  (t/is (= [nil "hi"] (sse/parse-sse-line "data:hi")))
  (t/is (= ["event" nil] (sse/parse-sse-line "event: event")))
  (t/is (= [nil nil] (sse/parse-sse-line "")))
  (t/is (= [nil "  hi"] (sse/parse-sse-line "data:   hi"))
        "only the one space after the colon is stripped")
  (t/is (= [nil "hi "] (sse/parse-sse-line "data: hi "))
        "trailing whitespace is part of the value")
  (t/is (= [nil nil] (sse/parse-sse-line ":comment"))))

(t/deftest test-append-data
  (t/is (= "a" (sse/append-data "" "a")))
  (t/is (= "a\nb" (sse/append-data "a" "b")))
  (t/is (= "a\n" (sse/append-data "a" "")))
  (t/is (= "" (sse/append-data "" ""))))

;; ─── Chunked idle reader ───────────────────────────────────────────────────

(defn- drain-reader
  "All ints from READ until EOF/-1, as a string."
  [read]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (read)]
        (if (neg? c)
          (str sb)
          (do (.append sb (char c)) (recur)))))))

(t/deftest test-char-idle-reader-chunks
  ;; A body larger than one 8 KiB chunk must come back whole, in order, with
  ;; EOF after the last unit — the chunked handoff's contract.
  (let [text (apply str (repeat 2000 "abcdefghij"))
        [read stop thread] (sse/make-char-idle-reader (java.io.StringReader. text) 120000 nil)]
    (t/is (= text (drain-reader read)))
    (stop)
    (.join thread 2000)))

(t/deftest test-char-idle-reader-surfaces-read-error
  (let [rdr (proxy [java.io.Reader] []
              (read
                ([] (throw (java.io.IOException. "RST_STREAM")))
                ([_ _ _] (throw (java.io.IOException. "RST_STREAM")))))
        [read stop thread] (sse/make-char-idle-reader rdr 120000 nil)
        v (read)]
    (t/is (instance? java.io.IOException v))
    (t/is (= "RST_STREAM" (ex-message v)))
    (stop)
    (.join thread 2000)))

(t/deftest test-char-idle-reader-times-out
  ;; A reader that blocks rather than answering: the consumer's read returns
  ;; :timeout at the idle deadline instead of blocking forever.
  (let [rdr (proxy [java.io.Reader] []
              (read
                ([] (Thread/sleep 5000) -1)
                ([_ _ _] (Thread/sleep 5000) -1)))
        [read stop thread] (sse/make-char-idle-reader rdr 100 nil)
        t0 (System/nanoTime)
        v (read)]
    (t/is (= :timeout v))
    (t/is (>= (/ (- (System/nanoTime) t0) 1e6) 50))
    (stop)
    (.join thread 2000)))
