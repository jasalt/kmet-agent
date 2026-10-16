(ns kmet.agent.test-config-schema
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.agent.config-schema :as cs]))

(deftest default-provider-config-validates
  (testing "minimal merged config validates"
    (is (cs/validate-config? {:provider :opencode-go
                              :theme "dark"
                              :loop-guard {:enabled true :threshold 3}
                              :images {:block-images false}
                              :http-idle-timeout-ms 120000
                              :http-total-timeout-ms 0}))))

(deftest closed-under-debug-still-open-at-boundary
  (testing "unknown keys survive (forward compatible)"
    (is (cs/validate-config? {:provider :opencode-go :future-key 1}))))
