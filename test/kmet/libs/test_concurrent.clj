(ns kmet.libs.test-concurrent
  "kmet.libs.concurrent — the extension-safe spawn/or-signal primitives and
   monotonic-ms, the clock every timeout, backoff and idle reaper measures
   with."
  (:require [clojure.test :refer [deftest is]]
            [kmet.libs.concurrent :as concurrent]))

(deftest monotonic-ms-measures-elapsed-time
  (let [t0 (concurrent/monotonic-ms)]
    (Thread/sleep 10)
    (let [elapsed (- (concurrent/monotonic-ms) t0)]
      (is (<= 5 elapsed) "the clock advances with real time")
      (is (< elapsed 10000) "and by about the time that actually passed"))))

(deftest spawn-runs-f-on-a-daemon-thread
  (let [p (promise)
        t (concurrent/spawn #(deliver p :done))]
    (is (= :done (deref p 2000 :timeout)))
    (is (.isDaemon t))))

(deftest or-signal-is-true-when-any-signal-fires
  (let [a (atom false)
        b (atom false)]
    (is (false? @(concurrent/or-signal a b)))
    (reset! a true)
    (is (true? @(concurrent/or-signal a b)))
    (reset! a false)
    (reset! b true)
    (is (true? @(concurrent/or-signal a b)))
    (is (false? @(concurrent/or-signal a nil)) "a nil signal never fires")))
