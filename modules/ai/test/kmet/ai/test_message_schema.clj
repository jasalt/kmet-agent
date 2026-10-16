(ns kmet.ai.test-message-schema
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.ai.message :as msg]))

(deftest text-message-validates
  (testing "a text-only user message validates"
    (is (msg/validate-message {:role :user :content [{:type :text :text "hi"}]}))))

(deftest wrong-content-block-type-fails
  (testing "an unknown content block type fails"
    (is (not (msg/validate-message {:role :assistant :content [{:type :mystery}]})))))

(deftest tool-call-message-validates
  (testing "an assistant tool-call message validates"
    (is (msg/validate-message {:role :assistant :content [] :tool-calls [{:type :tool_call :id "1" :name "bash"}]}))))
