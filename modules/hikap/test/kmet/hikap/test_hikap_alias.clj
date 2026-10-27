(ns kmet.hikap.test-hikap-alias
  (:require [clojure.test :as t]
            [kmet.hikap.alias :as alias]))

(t/use-fixtures :each (fn [f]
                        (let [snapshot (alias/registered)]
                          (alias/reset-registry!)
                          (f)
                          (alias/reset-registry!)
                          (doseq [[k v] snapshot] (alias/register! k v)))))

(t/deftest registered-empty-initially
  (t/is (map? (alias/registered)))
  (t/is (empty? (alias/registered))))

(t/deftest register-and-lookup
  (let [f (fn [attrs _children] attrs)
        dereg (alias/register! :myns/widget f)]
    (t/is (fn? dereg))
    (t/is (= f (alias/lookup :myns/widget)))
    (t/is (contains? (alias/registered) :myns/widget))))

(t/deftest unregister-only-if-same-function
  (let [f1 (fn [a _] a)
        f2 (fn [_ c] c)]
    (alias/register! :myns/widget f1)
    (alias/unregister! :myns/widget f2)
    (t/is (= f1 (alias/lookup :myns/widget)))
    (alias/unregister! :myns/widget f1)
    (t/is (nil? (alias/lookup :myns/widget)))))

(t/deftest deregister-fn-identity-guard
  (let [f1 (fn [a _] a)
        f2 (fn [_ c] c)
        dereg1 (alias/register! :myns/widget f1)
        dereg2 (alias/register! :myns/widget f2)]
    ;; older owner's deregister is a no-op because the registry now maps f2
    (dereg1)
    (t/is (= f2 (alias/lookup :myns/widget)) "older owner cannot wipe newer")
    (dereg2)
    (t/is (nil? (alias/lookup :myns/widget)) "newest owner's deregister removes it")))

(t/deftest last-wins-registration
  (let [f1 (fn [a _] a)
        f2 (fn [_ c] c)]
    (alias/register! :myns/widget f1)
    (alias/register! :myns/widget f2)
    (t/is (= f2 (alias/lookup :myns/widget)))))

(t/deftest invalid-registration-throws
  (t/is (thrown? clojure.lang.ExceptionInfo (alias/register! :unqualified (fn [a _] a))))
  (t/is (thrown? clojure.lang.ExceptionInfo (alias/register! :myns/widget 42)))
  (t/is (empty? (alias/registered))))

(t/deftest head-dispatches-to-registration
  (let [f (fn [attrs children] {:attrs attrs :children children})
        _ (alias/register! :myns/widget f)
        h (alias/head :myns/widget)]
    (t/is (= {:attrs {:x 1} :children [:y]} (h {:x 1} [:y])))
    (t/is (= {:attrs {:x 1} :children []} (h {:x 1} nil)))))