(ns kmet.libs.test-log
  "The lower layers' logger seam: silent by default, one installed sink, and
   a diagnostic that can never break its caller."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.log :as log]))

(t/use-fixtures :each
  (fn [f]
    (log/clear-sink!)
    (try (f) (finally (log/clear-sink!)))))

(deftest silent-without-a-sink
  (testing "no sink installed means the default library behaviour"
    (is (nil? (log/log! "ignored")))))

(deftest an-installed-sink-receives-every-part
  (let [seen (atom [])]
    (log/set-sink! (fn [& parts] (swap! seen conj (apply str parts))))
    (log/log! "retrying " :write " after " 25 "ms")
    (is (= ["retrying :write after 25ms"] @seen))))

(deftest a-throwing-sink-never-reaches-the-caller
  (log/set-sink! (fn [& _] (throw (ex-info "the sink failed" {}))))
  (is (nil? (log/log! "still safe"))))
