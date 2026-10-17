(ns kmet.cells.test-core
  "The facade: runtime, check, register!, dispose! (cells.md §2.2)."
  (:require [clojure.test :refer [deftest is]]
            [kmet.cells.core :as cells]
            [kmet.cells.scope :as scope]
            [kmet.cells.spec :as spec]))

(def ^:private rt
  (cells/runtime {:resolve [[:builtin (fn [nm] (when (= nm :kmet/x) {:handle (fn [_ _])}))]]}))

(deftest runtime-freezes-the-backends
  (is (= 1 (count (:resolve rt)))))

(deftest check-is-the-spec-check
  (is (= [] (cells/check rt [{:id :x :name :kmet/x}])))
  (is (= (spec/check rt [{:id :x :name :nope}])
         (cells/check rt [{:id :x :name :nope}]))))

(deftest register-and-dispose-through-the-facade
  (let [n (scope/node)
        dereg (cells/register! n [:app :x] [:on-event :turn-end] :handler)]
    (is (= {[:app :x] :handler} (scope/for-key n [:on-event :turn-end])))
    (is (fn? dereg))
    (cells/dispose! n [:app :x])
    (is (= {} (scope/for-key n [:on-event :turn-end])))
    (cells/register! n [:app :x] [:k] :v)
    (cells/dispose! n)
    (is (= {} (scope/registrations n)))))
