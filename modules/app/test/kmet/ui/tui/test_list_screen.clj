(ns kmet.ui.tui.test-list-screen
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.ui.tui.list-screen :as ls]))

(deftest window-centered
  (testing "centers the selection when there is room"
    (is (= [0 5] (ls/window 2 5 10)))
    (is (= [2 7] (ls/window 4 5 10)))
    (is (= [5 10] (ls/window 8 5 10)))))

(deftest window-clamped-at-ends
  (testing "clamps at the top and bottom"
    (is (= [0 5] (ls/window 0 5 10)))
    (is (= [5 10] (ls/window 9 5 10)))))

(deftest window-visible-larger-than-count
  (testing "visible larger than n uses n"
    (is (= [0 3] (ls/window 1 10 3)))))

(deftest window-empty
  (testing "empty list"
    (is (= [0 0] (ls/window 0 5 0)))))

(deftest scroll-indicator-clipped
  (testing "shown only when clipped"
    (is (= "  (3/10)" (ls/scroll-indicator 0 5 2 10)))
    (is (nil? (ls/scroll-indicator 0 10 2 10)))
    (is (nil? (ls/scroll-indicator 0 0 0 0)))))

(deftest clamp-basic
  (is (= 5 (ls/clamp 10 0 5)))
  (is (= 0 (ls/clamp -1 0 5)))
  (is (= 3 (ls/clamp 3 0 5))))