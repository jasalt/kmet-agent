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

(deftest a-miss-names-every-backend
  (let [[error] (cells/check (runtime/runtime) [{:id :nope :name :kmet/nope}])]
    (is (= :name (:key error)))
    (is (str/includes? (:error error) ":builtin"))
    (is (str/includes? (:error error) ":ext")
        "a miss reports every backend tried, in the host's order")))

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
    (is (= [:agent-loop :commands :extensions :prompts :session :skills :tools :ui]
           (sort (keys snapshot))))
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
    (is (= [:app :session] (:owner (:session snapshot)))
        "the session surface's address, so its registrations report under it")
    (is (= [:app :extensions] (:owner (:extensions snapshot)))
        "the extension surface's address; extensions register under it")
    (is (= [:app :ui] (:owner (:ui snapshot)))
        "the front-end surface's address, so its keys report under it")
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

(deftest an-extension-unit-resolves-and-mounts
  ;; P5a: a publication is {:name :entry :unit} — the name resolves through
  ;; the `:ext` backend, and the entry mounts the unit in the app root.
  (let [node (runtime/node)
        probe {:handle (fn [_ n] (* 2 n)) :config [:map [:n :int]]}
        dereg (runtime/publish-extension-unit!
               "probe-ext" {:name :probe-ext/double
                            :unit probe
                            :entry {:id :probe-ext :config {:n 21}}})]
    (try
      (let [snapshot (runtime/snapshot node)]
        (is (= {:unit probe :extension "probe-ext"}
               (runtime/extension-unit :probe-ext/double)))
        (is (= 42 ((runtime/unit-handle node :probe-ext) node 21)))
        (is (= {:n 21} (runtime/config snapshot :probe-ext)))
        (is (= [:app :probe-ext] (:owner (:probe-ext snapshot)))))
      (is (str/includes? (runtime/explain node) "[:app :probe-ext]")
          "the published slot shows in /units")
      (is (true? (dereg)) "the deregister fn drops the publication")
      (is (nil? (runtime/unit-handle node :probe-ext)))
      (is (nil? (runtime/extension-unit :probe-ext/double)))
      (finally
        (dereg)))))

(deftest the-ext-backend-is-tried-after-builtin
  ;; Backends are tried in the host's declared order: a publication cannot
  ;; shadow a builtin name, so the entry gets kmet's own retry unit.
  (let [node (runtime/node)
        shadow (fn [& _] :shadow)
        dereg (runtime/publish-extension-unit!
               "shadow-ext" {:name :kmet/retry
                             :unit {:handle shadow}
                             :entry {:id :shadow-retry :into :agent-loop
                                     :config {:max-retries 1}}})]
    (try
      (is (= shadow (:handle (:unit (runtime/extension-unit :kmet/retry))))
          "published under the builtin name")
      (is (not (identical? shadow
                           (runtime/handle (runtime/snapshot node :agent-loop) :retry-policy)))
          "the mounted stage is the builtin's descriptor")
      (is (true? (dereg)))
      (finally
        (dereg)))))

(deftest re-publishing-a-name-replaces-the-earlier-unit
  (let [node (runtime/node)
        starts (atom 0)
        publish (fn [tag]
                  (runtime/publish-extension-unit!
                   "re-ext" {:name :re-ext/unit
                             :unit {:start (fn [_ _] (swap! starts inc) nil)
                                    :handle (fn [_ _] tag)}
                             :entry {:id :re-ext}}))
        stale (publish :one)
        dereg (publish :two)]
    (try
      (is (= :two ((runtime/unit-handle node :re-ext) node nil)))
      (is (= 2 @starts) "the re-publication remounted the slot")
      (stale)
      (is (= :two ((runtime/unit-handle node :re-ext) node nil))
          "a stale deregister is a no-op")
      (is (true? (dereg)))
      (is (nil? (runtime/unit-handle node :re-ext)))
      (finally
        (stale)
        (dereg)))))

(deftest a-refused-mount-drops-the-publication
  (let [node (runtime/node)
        before (runtime/snapshot node)]
    (is (thrown? Exception
                 (runtime/publish-extension-unit!
                  "bad-ext" {:name :bad-ext/unit
                             :unit {:handle identity :config [:map [:n :int]]}
                             :entry {:id :bad-ext :config {:n "not-an-int"}}})))
    (is (nil? (runtime/extension-unit :bad-ext/unit))
        "the publication did not survive the refused mount")
    (is (= (set (keys before)) (set (keys (runtime/snapshot node))))
        "and the tree is unchanged")))

(deftest a-malformed-publication-is-refused
  (is (= :kmet.agent.runtime/bad-unit-publication
         (:type (ex-data (try (runtime/publish-extension-unit!
                               "x" {:name "not-a-keyword" :entry {} :unit {}})
                              (catch Exception e e))))))
  (is (nil? (runtime/extension-unit "not-a-keyword"))))

(deftest a-published-name-lives-in-its-extensions-namespace
  (let [stale (runtime/publish-extension-unit!
               "owner-ext" {:name :owner-ext/unit
                            :unit {:handle identity}
                            :entry {:id :owner-ext}})]
    (try
      (is (= :kmet.agent.runtime/duplicate-unit-name
             (:type (ex-data (try (runtime/publish-extension-unit!
                                   "impostor-ext"
                                   {:name :owner-ext/unit
                                    :unit {:handle identity}
                                    :entry {:id :impostor-ext}})
                                  (catch Exception e e))))))
      (is (= "owner-ext" (:extension (runtime/extension-unit :owner-ext/unit))))
      (is (nil? (runtime/unit-handle (runtime/node) :impostor-ext)))
      (finally
        (stale)))))

(deftest unpublishing-one-extension-leaves-anothers
  (let [node (runtime/node)
        unit {:handle (fn [_ _] :ok)}
        stale-a (runtime/publish-extension-unit!
                 "ext-a" {:name :ext-a/unit :unit unit :entry {:id :ext-a}})
        stale-b (runtime/publish-extension-unit!
                 "ext-b" {:name :ext-b/unit :unit unit :entry {:id :ext-b}})]
    (try
      (is (true? (runtime/unpublish-extension! "ext-a")))
      (is (nil? (runtime/unit-handle node :ext-a)))
      (is (some? (runtime/unit-handle node :ext-b)))
      (is (not (runtime/unpublish-extension! "ext-a")) "nothing left to drop")
      (is (true? (runtime/unpublish-extension! "ext-b")))
      (is (nil? (runtime/unit-handle node :ext-b)))
      (finally
        (stale-a)
        (stale-b)))))

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
