(ns kmet.agent.test-runtime
  "The composition runtime: the builtin units, the builtin backend, and
   the app node's default entries."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kmet.agent.runtime :as runtime]
            [kmet.cells.core :as cells]
            [kmet.cells.scope :as scope]))

(deftest an-empty-runtime-has-an-empty-root
  (let [rt (runtime/runtime)
        node (:root rt)]
    (is (= [] (cells/check rt [])))
    (is (nil? (cells/composition node)) "no composition is mounted yet")
    (is (identical? node (cells/mount! rt []))
        "mount! targets the node rt carries, so repeated calls diff")
    (is (= {} (cells/composition node))
        "the root composition now exists, empty")))

(deftest a-miss-names-the-backend
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
    (is (= [:agent-loop] (keys snapshot))
        "the loop group is the only entry the host mounts by default")
    (is (= [:app :agent-loop] (:owner (:agent-loop snapshot))))
    (is (nil? (runtime/handle snapshot :agent-loop))
        "the group's stages carry the handles, not the group itself")
    (is (identical? node (runtime/node)) "one app tree per process")))

(deftest the-loop-group-carries-its-stage-defaults
  (let [node (runtime/node)
        stages (runtime/snapshot node :agent-loop)]
    (is (= [:loop-guard :retry-policy] (sort (keys stages))))
    (is (= [:app :agent-loop :loop-guard] (:owner (:loop-guard stages))))
    (is (= [:app :agent-loop :retry-policy] (:owner (:retry-policy stages))))
    (is (fn? (runtime/handle stages :loop-guard)))
    (is (fn? (runtime/handle stages :retry-policy)))
    (is (nil? (runtime/config stages :loop-guard)))
    (is (nil? (runtime/config stages :retry-policy))
        "an inline stage default carries no entry config")))

(deftest an-entry-overrides-a-group-stage
  (let [rt (runtime/runtime)
        node (cells/mount! rt [{:id :agent-loop :name :kmet/agent-loop}
                               {:id :loop-guard :into :agent-loop
                                :name :kmet/loop-guard
                                :config {:threshold 7}}])
        stages (runtime/snapshot node :agent-loop)]
    (is (= {:threshold 7} (runtime/config stages :loop-guard)))
    (is (= [:app :agent-loop :loop-guard] (:owner (:loop-guard stages))))))

(deftest explain-renders-the-group-tree
  (let [text (runtime/explain (runtime/node))]
    (is (str/includes? text "[:app :agent-loop] :agent-loop"))
    (is (str/includes? text "\n  [:app :agent-loop :loop-guard] :loop-guard")
        "a group's slots indent under it")))

(deftest explain-reads-every-store-owner
  ;; F14: /units renders every store owner, not only the ones a mounted slot
  ;; prefixes. An extension's [:app :extensions <name>] — where P5c lands its
  ;; registrations — and a registry's address ([:app :commands]) have no slot
  ;; of their own, so the store is the only place to read them (cells.md §5.1,
  ;; §7).
  (let [node (cells/mount! (runtime/runtime) runtime/default-entries)]
    (scope/register! node [:app :extensions "probe-ext"]
                     [:commands :probe-cmd] {:name "probe-cmd"})
    (scope/register! node [:app :extensions "probe-ext"]
                     [:tools :probe-tool] {:name "probe-tool"})
    (scope/register! node [:app :extensions "probe-ext" :deep] :k/deep "d")
    (scope/register! node [:app :commands]
                     [:kmet/command "probe"] {:name "probe"})
    (scope/register! node [:app :agent-loop :probe] :k/probe "v")
    (let [text (runtime/explain node)]
      (is (str/includes? text (str "\n[:app :extensions \"probe-ext\"] keys="
                                   "[[:commands :probe-cmd] [:tools :probe-tool]]"))
          "the owner no slot stands for renders from the store, in registration order")
      (is (str/includes? text "\n  [:app :extensions \"probe-ext\" :deep] keys=[:k/deep]")
          "an owner under another owner nests under it")
      (is (not (str/includes? text "\n[:app :extensions \"probe-ext\" :deep]"))
          "and is not re-rendered at the tree's root")
      (is (str/includes? text "\n[:app :commands] keys=[[:kmet/command \"probe\"]]")
          "a registry's address renders as its owner path")
      (is (str/includes? text "\n  [:app :agent-loop :probe] keys=[:k/probe]")
          "an owner under a slot hangs under it"))))

(deftest explain-renders-a-root-owner
  ;; An owner registered at the root path is not a slot: it renders as a line
  ;; of its own, once, instead of being taken for a slot's owner path.
  (let [node (cells/mount! (runtime/runtime) runtime/default-entries)]
    (scope/register! node [:app] :k/root "r")
    (let [text (runtime/explain node)]
      (is (= 1 (count (re-seq #"\[:app\] keys=\[:k/root\]" text)))
          "the root owner renders once, as a line of its own"))))

(deftest explain-follows-the-owner-path-not-just-the-slot-id
  ;; Slot ids are unique per composition and may repeat across them, so a leaf
  ;; can carry an id some other subtree uses for a composition. The walk must
  ;; render each composition where it actually hangs.
  (let [builtin (fn [nm]
                  (case nm
                    :u/group {:units {:sub {:units {:deep {:handle identity}}}}}
                    :u/leaf {:handle identity}
                    nil))
        node (cells/mount! (cells/runtime {:resolve [[:builtin builtin]]})
                           [{:id :group :name :u/group}
                            {:id :sub :name :u/leaf}])
        text (runtime/explain node)]
    (is (str/includes? text "  [:app :group :sub] :sub"))
    (is (= 1 (count (re-seq #"\[:app :group :sub :deep\]" text)))
        "the nested stage renders once, under its real parent")
    (is (not (str/includes? text "\n  [:app :group :sub :deep]"))
        "the deep stage is not re-rendered under the root leaf")))

(deftest a-remount-reaches-a-changed-unit
  ;; A mounted slot holds the handle value it was mounted with, so a reloaded
  ;; unit needs a remount: the diff keeps unchanged slots and remounts the
  ;; changed one (cells.md §6.2, §6.4). The loop group's inline stage is the
  ;; unit under test: reloading kmet.agent.runtime rebuilds the descriptor.
  (let [changed (fn [& _] :changed)
        node (runtime/node)
        stage #(runtime/handle (runtime/snapshot node :agent-loop) :loop-guard)]
    (try
      (with-redefs [runtime/builtin-units
                    (assoc runtime/builtin-units :kmet/agent-loop
                           {:units {:loop-guard {:handle changed}}})]
        (runtime/remount!)
        (is (identical? changed (stage)))
        (is (identical? node (runtime/node)) "into the same tree"))
      (finally
        (runtime/remount!)))
    (is (not (identical? changed (stage))) "the real unit is back")))

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
