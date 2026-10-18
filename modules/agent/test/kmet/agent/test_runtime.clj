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
    (is (= [:agent-loop :commands :tools :prompts :skills] (keys snapshot)))
    (is (= [:app :agent-loop] (:owner (:agent-loop snapshot))))
    (is (nil? (runtime/handle snapshot :agent-loop))
        "the group's stages carry the handles, not the group itself")
    (is (= [:app :commands] (:owner (:commands snapshot)))
        "the commands registry's address, so its keys report under it")
    (is (= [:app :tools] (:owner (:tools snapshot)))
        "the tool registry's address, so its keys report under it")
    (is (= [:app :prompts] (:owner (:prompts snapshot)))
        "the prompt-template registry's address, so its keys report under it")
    (is (= [:app :skills] (:owner (:skills snapshot)))
        "the skill registry's address, so its keys report under it")
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
