(ns kmet.libs.test-hikap
  "Smoke coverage for the hikap module so it participates in the test runs."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.libs.hikap.core :as hikap.core]
            [kmet.libs.hikap.component :as hikap.component]
            [kmet.libs.hikap.alias :as hikap.alias]))

(deftest hikap-module-loads
  (testing "the hikap namespaces load from their own module"
    (is (map? (hikap.core/counters)))
    (is (number? (hikap.component/live-watch-count)))
    (is (map? (hikap.alias/registered)))))