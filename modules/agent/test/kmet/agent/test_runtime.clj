(ns kmet.agent.test-runtime
  "The composition runtime: the builtin units, the resolution backend, and
   the app node's default entries."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kmet.agent.runtime :as runtime]
            [kmet.cells.core :as cells]))

(deftest an-empty-runtime-has-an-empty-root
  (let [rt (runtime/runtime)
        node (:root rt)]
    (is (= [] (cells/check rt [])))
    (is (nil? (cells/composition node)) "no composition is mounted yet")
    (is (identical? node (cells/mount! rt []))
        "mount! targets the node rt carries, so repeated calls diff")
    (is (= {} (cells/composition node))
        "the root composition now exists, empty")))

(deftest a-miss-names-the-builtin-backend
  (let [[error] (cells/check (runtime/runtime) [{:id :nope :name :kmet/nope}])]
    (is (= :name (:key error)))
    (is (str/includes? (:error error) ":builtin"))))

(deftest a-builtin-unit-resolves-mounts-and-is-readable
  (with-redefs [runtime/builtin-units
                {:kmet/probe {:config [:map [:n :int]]
                              :handle (fn [_node n] (* 2 n))}}]
    (let [rt (runtime/runtime)
          entries [{:id :probe :name :kmet/probe :config {:n 21}}]
          node (cells/mount! rt entries)]
      (is (= [] (cells/check rt entries)))
      (is (= 42 ((runtime/unit-handle node :probe) node 21)))
      (is (= [:app :probe] (:owner (:probe (cells/composition node)))))
      (is (= {:n 21} (:config (:probe (cells/composition node)))))
      (is (nil? (runtime/unit-handle node :absent))))))

(deftest the-app-node-mounts-the-default-entries
  (let [node (runtime/node)
        snapshot (runtime/snapshot node)]
    (is (= [] (cells/check (runtime/runtime) runtime/default-entries)))
    (is (= [:loop-guard] (keys snapshot)))
    (is (= [:app :loop-guard] (:owner (:loop-guard snapshot))))
    (is (fn? (runtime/handle snapshot :loop-guard)))
    (is (identical? node (runtime/node)) "one app tree per process")))
