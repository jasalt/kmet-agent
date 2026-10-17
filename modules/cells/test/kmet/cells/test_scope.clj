(ns kmet.cells.test-scope
  "The store: ownership, replacement, LIFO disposal, failure isolation.
   Registration cleanups are the deregister fn itself, so their mutual order
   is structural; what is observable — teardown LIFO, registrations before
   teardowns, per-owner isolation — is tested here."
  (:require [clojure.test :refer [deftest is]]
            [kmet.cells.scope :as scope]))

(deftest registrations-are-keyed-and-owned
  (let [n (scope/node)]
    (scope/register! n [:app :a] [:tools :grep] {:name "grep"})
    (scope/register! n [:app :b] [:tools :find] {:name "find"})
    (is (= {:name "grep"} (get (scope/for-key n [:tools :grep]) [:app :a])))
    (is (= {:name "find"} (get (scope/for-key n [:tools :find]) [:app :b])))
    (is (= #{[:tools :grep]} (scope/owner-keys n [:app :a])))))

(deftest deregister-is-idempotent-and-guards-replacement
  (let [n (scope/node)
        dereg (scope/register! n :owner [:k] :v1)]
    (is (= {:owner :v1} (scope/for-key n [:k])))
    (scope/register! n :owner [:k] :v2)
    (dereg)
    (is (= {:owner :v2} (scope/for-key n [:k])) "a stale deregister fn is a no-op")
    (scope/register! n :other [:k] :v3)
    (is (= {:owner :v2 :other :v3} (scope/for-key n [:k])))))

(deftest dispose-owner-is-lifo-and-per-owner
  (let [n (scope/node)
        order (atom [])]
    (scope/add-teardown! n :a (fn [] (swap! order conj :a1)))
    (scope/add-teardown! n :a (fn [] (swap! order conj :a2)))
    (scope/add-teardown! n :b (fn [] (swap! order conj :b1)))
    (scope/register! n :a [:k] :v)
    (scope/dispose! n :a)
    (is (= [:a2 :a1] @order) "teardowns are LIFO")
    (is (= {} (scope/for-key n [:k])) "a's registrations are gone")
    (scope/dispose! n :b)
    (is (= [:a2 :a1 :b1] @order) "b was not touched by a's disposal")
    (is (= {} (scope/registrations n)))))

(deftest whole-node-disposal-is-reverse-owner-order
  (let [n (scope/node)
        order (atom [])]
    (scope/add-teardown! n :a (fn [] (swap! order conj :a)))
    (scope/add-teardown! n :b (fn [] (swap! order conj :b)))
    (scope/register! n :a [:k] :v)
    (scope/dispose! n)
    (is (= [:b :a] @order))
    (is (= {} (scope/registrations n)))))

(deftest a-failing-cleanup-does-not-skip-the-rest
  (let [n (scope/node)
        ran (atom [])
        reported (atom [])]
    (scope/add-teardown! n :a (fn [] (swap! ran conj :first)))
    (scope/add-teardown! n :a (fn [] (throw (ex-info "boom" {}))))
    (scope/add-teardown! n :a (fn [] (swap! ran conj :last)))
    (binding [scope/*error-reporter* (fn [t] (swap! reported conj (ex-message t)))]
      (scope/dispose! n :a))
    (is (= [:last :first] @ran) "LIFO, and the throw did not skip :first")
    (is (= ["boom"] @reported))))

(deftest registrations-are-disposed-before-teardowns
  (let [n (scope/node)
        seen (atom ::unset)]
    (scope/register! n :a [:k] :v)
    (scope/add-teardown! n :a (fn [] (reset! seen (scope/for-key n [:k]))))
    (scope/dispose! n :a)
    (is (= {} @seen) "the registration is gone before the teardown runs")))

(deftest deregister-targets-one-owner-and-guards-replacement
  (let [n (scope/node)]
    (scope/register! n :a [:k] :v1)
    (scope/register! n :b [:k] :v2)
    (scope/deregister! n :a [:k] :v1)
    (is (= {:b :v2} (scope/for-key n [:k])) "the other owner keeps its value")
    (scope/register! n :a [:k] :v3)
    (scope/deregister! n :a [:k] :v1)
    (is (= {:a :v3 :b :v2} (scope/for-key n [:k]))
        "a stale value cannot remove a newer registration")))

(deftest owner-registrations-keep-registration-order
  (let [n (scope/node)]
    (scope/register! n :a [:first] 1)
    (scope/register! n :a [:second] 2)
    (scope/register! n :b [:other] 3)
    (is (= [[[:first] 1] [[:second] 2]] (scope/owner-registrations n :a)))
    (scope/register! n :a [:first] 10)
    (is (= [[[:second] 2] [[:first] 10]] (scope/owner-registrations n :a))
        "replacing a registration moves it to the end")
    (is (= [] (scope/owner-registrations n :absent)))))

(deftest nil-registrations-are-rejected
  (is (thrown? Exception (scope/register! (scope/node) :o [:k] nil))))
