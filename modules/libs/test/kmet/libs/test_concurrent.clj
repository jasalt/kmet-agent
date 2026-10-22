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

(deftest iso-now-is-a-sortable-wall-clock-string
  (let [a (concurrent/iso-now)
        b (concurrent/iso-now)]
    (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*Z" a)
        "an ISO-8601 instant")
    (is (<= (compare a b) 0) "strings sort as time")))

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

(deftest with-lock-serializes-a-read-modify-write
  (let [l (concurrent/lock)
        counter (atom 0)
        bump #(concurrent/with-lock l (fn [] (let [n @counter] (Thread/sleep 1) (reset! counter (inc n)))))
        threads (mapv (fn [_] (concurrent/spawn (fn [] (dotimes [_ 20] (bump))))) (range 4))]
    (doseq [t threads] (.join t))
    (is (= 80 @counter) "no increment is lost to a concurrent read")))

(deftest with-lock-is-reentrant-and-returns-fs-value
  (let [l (concurrent/lock)]
    (is (= :inner (concurrent/with-lock l (fn [] (concurrent/with-lock l (fn [] :inner)))))))
  (is (= :unguarded (concurrent/with-lock nil (fn [] :unguarded)))
      "a nil lock runs the body unguarded"))

(deftest with-lock-releases-on-throw
  (let [l (concurrent/lock)]
    (is (thrown? Exception (concurrent/with-lock l (fn [] (throw (ex-info "boom" {}))))))
    (is (= :again (concurrent/with-lock l (fn [] :again)))
        "the lock is free after a throwing body")))
