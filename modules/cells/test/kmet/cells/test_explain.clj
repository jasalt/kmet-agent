(ns kmet.cells.test-explain
  "explain renders a snapshot for a human (cells.md §7)."
  (:require [clojure.test :refer [deftest is]]
            [kmet.cells.explain :as explain]))

(deftest nothing-prints-nothing
  (is (= "" (explain/explain {})))
  (is (= "" (explain/explain nil))))

(deftest a-slot-renders-its-parts-in-order
  (is (= "[:app :a] :a handle=:h/a config={:n 1} keys=#{[:on-event :turn-end]} :effect=:pure"
         (explain/explain {:a {:handle :h/a
                               :owner [:app :a]
                               :config {:n 1}
                               :keys #{[:on-event :turn-end]}
                               :effect :pure}}))))

(deftest lines-sort-by-owner-path
  (is (= "[:app :a] :a handle=:h/a\n[:app :b] :b handle=:h/b"
         (explain/explain {:b {:handle :h/b :owner [:app :b]}
                           :a {:handle :h/a :owner [:app :a]}}))))

(deftest a-group-without-a-handle-omits-it
  (is (= "[:app :g] :g :policy-fn=:ex"
         (explain/explain {:g {:owner [:app :g] :policy-fn :ex}}))))
