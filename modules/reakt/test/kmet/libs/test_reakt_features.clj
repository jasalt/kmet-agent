(ns kmet.libs.test-reakt-features
  "Additional behavior-oriented tests for kmet.libs.reakt.core."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.reakt.core :as r]))

(deftest test-in-reaction-predicate
  (let [a (atom 1)
        inside (atom nil)
        rx (r/make-reaction
            (fn []
              (reset! inside (r/in-reaction?))
              (r/tracked-deref a)))]
    (is (not (r/in-reaction?)))
    (is (= 1 @rx))
    (is @inside)))

(deftest test-reaction-macro
  (let [a (atom 7)
        rx (r/reaction (+ 1 (r/tracked-deref a)))]
    (is (= 8 @rx))
    (swap! a inc)
    (r/flush!)
    (is (= 9 @rx))))

(deftest test-make-reaction-options
  (testing "auto-run callback"
    (let [a (atom 1)
          calls (atom [])
          rx (r/make-reaction (fn [] (r/tracked-deref a))
                              {:auto-run? (fn [react] (swap! calls conj react))})]
      (is (= 1 @rx))
      (swap! a inc)
      (is (= [rx] @calls))
      (r/flush!)
      (is (= 2 @rx))
      (r/dispose! rx)))
  (testing "invalidate!"
    (let [a (atom 1)
          runs (atom 0)
          rx (r/make-reaction (fn [] (swap! runs inc) (r/tracked-deref a)))]
      (is (= 1 @rx))
      (is (= 1 @runs))
      (r/invalidate! rx)
      (is (= :dirty (get (r/reaction-state rx) :state)))
      (is (= 1 @rx))
      (is (= 2 @runs))
      (r/dispose! rx)))
  (testing "add-on-dispose!"
    (let [a (atom 1)
          calls (atom [])
          rx (r/make-reaction (fn [] (r/tracked-deref a)))]
      (r/add-on-dispose! rx (fn [react] (swap! calls conj react)))
      @rx
      (r/dispose! rx)
      (is (= [rx] @calls)))))

(deftest test-rerun-without-deps-option
  (testing "depless body re-runs"
    (let [runs (atom 0)
          rx (r/make-reaction (fn [] (swap! runs inc) 5)
                              {:rerun-without-deps? true})]
      (is (= 5 @rx))
      (is (= 1 @runs))
      (is (= 5 @rx))
      (is (= 2 @runs))
      (r/dispose! rx)))
  (testing "implicit-deps are still accepted by make-reaction"
    (let [a (atom 1)
          rx (r/make-reaction
              (fn [] (r/tracked-deref a))
              {:rerun-without-deps? true
               :implicit-deps [a]})]
      (is (= 1 @rx))
      (r/dispose! rx))))

(deftest test-watcher-error-isolation
  (let [a (atom 1)
        order (atom [])
        rx (r/make-reaction (fn [] (r/tracked-deref a)))]
    @rx
    (r/watch-ref rx :first (fn [& _] (swap! order conj :first) (throw (ex-info "first" {}))))
    (r/watch-ref rx :second (fn [& _] (swap! order conj :second)))
    (swap! a inc)
    (r/flush!)
    (is (= [:first :second] @order))
    (is (= 2 @rx))
    (r/dispose! rx)))

(deftest test-equal-output-does-not-fire-watcher
  (let [a (atom 2)
        fired (atom 0)
        rx (r/make-reaction (fn [] (if (odd? (r/tracked-deref a)) :other 0)))]
    @rx
    (r/watch-ref rx :w (fn [& _] (swap! fired inc)))
    (swap! a inc)
    (swap! a dec)
    (r/flush!)
    (is (zero? @fired))
    (r/dispose! rx)))

(deftest test-changed-output-fires-watcher-once
  (let [a (atom 2)
        fired (atom 0)
        rx (r/make-reaction (fn [] (if (odd? (r/tracked-deref a)) :other 0)))]
    @rx
    (r/watch-ref rx :w (fn [& _] (swap! fired inc)))
    (reset! a 1)
    (r/flush!)
    (is (= 1 @fired))
    (r/dispose! rx)))

(deftest test-unwatch-ref
  (let [a (atom 1)
        rx (r/make-reaction (fn [] (r/tracked-deref a)))
        first (atom 0)
        second (atom 0)]
    @rx
    (r/watch-ref rx :first (fn [& _] (swap! first inc)))
    (r/watch-ref rx :second (fn [& _] (swap! second inc)))
    (r/unwatch-ref rx :first)
    (swap! a inc)
    (r/flush!)
    (is (zero? @first))
    (is (= 1 @second))
    (r/dispose! rx)))