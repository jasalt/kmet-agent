(ns kmet.app.test-skill-block
  "The `<skill>` wrapper's reader: what a stored skill invocation parses to, and
   what is not one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.skill-block :as skill-block]))

(def ^:private block
  "<skill name=\"demo-skill\" location=\"demo:SKILL.md\">\nReferences are relative to /skills/demo.\n\n# Steps\n\n1. do a thing\n2. do another\n</skill>")

(deftest parse-reads-the-wrapper
  (testing "a block without a trailing message"
    (let [p (skill-block/parse block)]
      (is (= "demo-skill" (:name p)))
      (is (= "demo:SKILL.md" (:location p)))
      (is (str/includes? (:content p) "# Steps"))
      (is (str/includes? (:content p) "References are relative to"))
      (is (nil? (:user-message p)))))
  (testing "a block with a trailing message"
    (let [p (skill-block/parse (str block "\n\ndo the thing"))]
      (is (= "do the thing" (:user-message p)))))
  (testing "the trailing message is trimmed"
    (let [p (skill-block/parse (str block "\n\n  padded  "))]
      (is (= "padded" (:user-message p)))))
  (testing "an empty trailing message is no message"
    (is (nil? (:user-message (skill-block/parse (str block "\n\n")))))))

(deftest parse-rejects-non-blocks
  (testing "plain text, empty text, non-strings"
    (is (nil? (skill-block/parse "just a message")))
    (is (nil? (skill-block/parse "")))
    (is (nil? (skill-block/parse nil)))
    (is (nil? (skill-block/parse 42))))
  (testing "the block must span the whole message"
    (is (nil? (skill-block/parse (str "prefix " block))))
    (is (nil? (skill-block/parse (str block " suffix"))))))
