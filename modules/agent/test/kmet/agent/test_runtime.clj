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
    (is (= [:loop-guard :commands] (keys snapshot)))
    (is (= [:app :loop-guard] (:owner (:loop-guard snapshot))))
    (is (fn? (runtime/handle snapshot :loop-guard)))
    (is (= [:app :commands] (:owner (:commands snapshot)))
        "the commands registry's address, so its keys report under it")
    (is (identical? node (runtime/node)) "one app tree per process")))

(deftest a-remount-reaches-a-changed-unit
  ;; A mounted slot holds the handle value it was mounted with, so a reloaded
  ;; unit needs a remount: the diff keeps unchanged slots and remounts the
  ;; changed one (cells.md §6.2, §6.4).
  (let [changed (fn [& _] :changed)
        node (runtime/node)]
    (try
      (with-redefs [runtime/builtin-units
                    (assoc runtime/builtin-units :kmet/loop-guard {:handle changed})]
        (runtime/remount!)
        (is (identical? changed
                        (runtime/handle (runtime/snapshot node) :loop-guard)))
        (is (identical? node (runtime/node)) "into the same tree"))
      (finally
        (runtime/remount!)))
    (is (not (identical? changed
                         (runtime/handle (runtime/snapshot node) :loop-guard)))
        "the real unit is back")))

(deftest a-missing-slot-says-which-and-where
  (let [mine (cells/mount! (runtime/runtime) [{:id :probe :name :kmet/loop-guard}])
        snapshot (runtime/snapshot mine)]
    (is (nil? (runtime/handle snapshot :absent)) "handle stays the soft read")
    (is (fn? (runtime/require-handle snapshot :probe)))
    (let [e (try (runtime/require-handle snapshot :loop-guard)
                 nil
                 (catch Exception e e))]
      (is (= :kmet.agent.runtime/missing-unit (:type (ex-data e))))
      (is (= :loop-guard (:slot (ex-data e))))
      (is (= [:probe] (:mounted (ex-data e))))
      (is (str/includes? (ex-message e) ":loop-guard")))))

(deftest entry-config-is-readable-from-the-snapshot
  (let [mine (cells/mount! (runtime/runtime)
                           [{:id :loop-guard :name :kmet/loop-guard
                             :config {:threshold 7}}])
        snapshot (runtime/snapshot mine)]
    (is (= {:threshold 7} (runtime/config snapshot :loop-guard)))
    (is (nil? (runtime/config snapshot :absent)))))
