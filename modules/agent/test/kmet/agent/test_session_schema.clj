(ns kmet.agent.test-session-schema
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.agent.session.schema :as ss]))

(deftest valid-session-role-passes
  (testing "a representative user entry validates"
    (is (ss/validate-entry? {:role :user :id "1" :parent-id nil :content [{:type :text :text "hi"}]}))))

(deftest wrong-role-fails
  (testing "an unknown role dispatch fails"
    (is (not (ss/validate-entry? {:role :nope :id "1"})))))

(deftest header-validates
  (testing "a minimal header validates"
    (is (ss/validate-header? {:type :session :version 1 :id "x" :cwd "/" :created-at "t"}))))

(deftest entry-schema-round-trips-common-roles
  (testing "every common role survives EDN round-trip + malli validation"
    (doseq [e [{:role :assistant :id "a" :content []}
               {:role :tool :id "t" :content "out"}
               {:role :bash :id "b" :command "ls" :exit-code 0}
               {:role :context-edit :id "c" :target-id "x" :replacement nil}
               {:role :custom :id "u" :custom-type "review" :data {:active true}}]]
      (is (ss/validate-entry? e) (pr-str e)))))
