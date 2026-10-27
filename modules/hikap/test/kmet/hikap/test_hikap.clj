(ns kmet.hikap.test-hikap
  "Smoke coverage for the hikap module so it participates in the test runs."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.hikap.core :as hikap.core]
            [kmet.hikap.component :as hikap.component]
            [kmet.hikap.alias :as hikap.alias]))

(deftest hikap-module-loads
  (testing "the hikap namespaces load from their own module"
    (is (map? (hikap.core/counters)))
    (is (number? (hikap.component/live-watch-count)))
    (is (map? (hikap.alias/registered)))))