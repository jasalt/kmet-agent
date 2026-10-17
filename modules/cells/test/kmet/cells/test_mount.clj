(ns kmet.cells.test-mount
  "mount! and the :id diff (cells.md §6.2–§6.3), the snapshot (cells.md §5.4)
   and node nesting (cells.md §5.6)."
  (:require [clojure.test :refer [deftest is]]
            [kmet.cells.core :as cells]
            [kmet.cells.scope :as scope]))

(defn- rt-with
  "A runtime whose :builtin backend resolves the given unit table."
  [units]
  (cells/runtime {:resolve [[:builtin #(get units %)]]}))

(deftest mounting-a-leaf-publishes-the-snapshot
  (let [rt (rt-with {:k/leaf {:handle :h/leaf}})
        node (cells/mount! rt [{:id :leaf :name :k/leaf :config {:depth 1}}])]
    (is (= {:leaf {:handle :h/leaf :owner [:app :leaf] :config {:depth 1}}}
           (cells/composition node)))
    (is (nil? (cells/composition node :nope)))
    (is (identical? (:root rt) node))))

(deftest inline-units-are-defaults-and-entries-replace-them
  (let [rt (rt-with {:k/group {:handle :h/group
                               :units {:inner {:handle :h/inner}
                                       :policy {:handle :h/default}}}
                     :k/policy {:handle :h/replaced}})
        node (cells/mount! rt [{:id :group :name :k/group}
                               {:id :policy :into :group :name :k/policy
                                :config {:depth 3}}
                               {:id :extra :into :group :name :k/policy}])]
    (is (= #{:inner :policy :extra}
           (set (keys (cells/composition node :group)))))
    (is (= :h/inner (get-in (cells/composition node :group) [:inner :handle])))
    (is (= :h/replaced (get-in (cells/composition node :group) [:policy :handle]))
        "an entry replaces the inline default")
    (is (= {:depth 3} (get-in (cells/composition node :group) [:policy :config])))
    (is (= [:app :group :policy]
           (get-in (cells/composition node :group) [:policy :owner])))))

(deftest start-registrations-are-owned-by-the-slot
  (let [order (atom [])
        rt (rt-with {:k/sub {:handle :h/sub
                             :start (fn [node owner]
                                      (cells/register! node owner
                                                       [:on-event :turn-end] :tail)
                                      (fn [] (swap! order conj :teardown)))}})
        node (cells/mount! rt [{:id :sub :name :k/sub}])]
    (is (= {[:app :sub] :tail} (scope/for-key node [:on-event :turn-end])))
    (is (= #{[:on-event :turn-end]} (get-in (cells/composition node) [:sub :keys])))
    (cells/dispose! node [:app :sub])
    (is (= {} (scope/registrations node)))
    (is (= [:teardown] @order) "the teardown unwinds after the registrations")))

(deftest re-mounting-identical-entries-keeps-the-slot
  (let [starts (atom 0)
        rt (rt-with {:k/l {:handle :h/l :start (fn [_ _] (swap! starts inc) nil)}})
        entries [{:id :x :name :k/l :config {:n 1}}]
        node (cells/mount! rt entries)]
    (cells/mount! rt entries)
    (is (= 1 @starts) "no remount when unit, :name and :config are unchanged")
    (is (= {:n 1} (get-in (cells/composition node) [:x :config])))))

(deftest a-config-or-name-change-remounts-that-slot
  (let [starts (atom [])
        start (fn [_ owner] (swap! starts conj owner) nil)
        rt (rt-with {:k/l {:handle :h/l :start start}
                     :k/m {:handle :h/l :start start}})
        node (cells/mount! rt [{:id :x :name :k/l :config {:n 1}}])]
    (cells/mount! rt [{:id :x :name :k/l :config {:n 2}}])
    (cells/mount! rt [{:id :x :name :k/m :config {:n 2}}])
    (is (= [[:app :x] [:app :x] [:app :x]] @starts)
        "config, then :name, each remount even though the units are equal")
    (is (= :h/l (get-in (cells/composition node) [:x :handle])))))

(deftest a-dropped-entry-is-disposed
  (let [order (atom [])
        rt (rt-with {:k/l {:handle :h/l
                           :start (fn [node owner]
                                    (cells/register! node owner [:k] :v)
                                    (fn [] (swap! order conj :teardown)))}})
        node (cells/mount! rt [{:id :x :name :k/l}])]
    (cells/mount! rt [])
    (is (= {} (scope/registrations node)))
    (is (empty? (cells/composition node)))
    (is (= [:teardown] @order))))

(deftest dropping-an-entry-child-disposes-its-owner
  (let [rt (rt-with {:k/group {:handle :h/g :units {}}
                     :k/child {:handle :h/c
                               :start (fn [node owner]
                                        (cells/register! node owner [:k] :child))}})
        entries [{:id :g :name :k/group}
                 {:id :c :into :g :name :k/child}]
        node (cells/mount! rt entries)]
    (is (= {[:k] {[:app :g :c] :child}} (scope/registrations node)))
    (cells/mount! rt [{:id :g :name :k/group}])
    (is (= {} (scope/registrations node)))
    (is (empty? (cells/composition node :g)))))

(deftest replacing-a-group-disposes-its-subtree
  (let [rt (rt-with {:k/group {:handle :h/g
                               :units {:inner
                                       {:handle :h/i
                                        :start (fn [node owner]
                                                 (cells/register! node owner
                                                                  [:k] :inner))}}}
                     :k/leaf {:handle :h/l}})
        node (cells/mount! rt [{:id :g :name :k/group}])]
    (is (= {[:k] {[:app :g :inner] :inner}} (scope/registrations node)))
    (cells/mount! rt [{:id :g :name :k/leaf}])
    (is (= {} (scope/registrations node))
        "the child owner was disposed with the group")
    (is (empty? (cells/composition node :g)))
    (is (= :h/l (get-in (cells/composition node) [:g :handle])))))

(deftest a-kept-group-is-not-remounted-when-a-child-arrives
  (let [starts (atom [])
        group {:handle :h/g :units {} :start (fn [_ owner] (swap! starts conj owner) nil)}
        rt (rt-with {:k/group group
                     :k/child {:handle :h/c}})
        node (cells/mount! rt [{:id :g :name :k/group}])]
    (cells/mount! rt [{:id :g :name :k/group}
                      {:id :c :into :g :name :k/child}])
    (is (= [[:app :g]] @starts))
    (is (= #{:c} (set (keys (cells/composition node :g)))))))

(deftest a-child-node-is-isolated
  (let [rt (rt-with {:k/l {:handle :h/l}})
        node (cells/mount! rt [{:id :x :name :k/l}])
        child (cells/mount! rt [{:id :y :name :k/l}] {:parent node})]
    (is (= #{:x} (set (keys (cells/composition node)))))
    (is (= #{:y} (set (keys (cells/composition child)))))
    (is (= [:app :y] (get-in (cells/composition child) [:y :owner])))
    (is (= 1 (count @(:children node))))
    (cells/dispose! child)
    (is (empty? @(:children node))
        "a disposed child leaves its parent's list")))

(deftest dispose-ends-the-node-and-its-children
  (let [rt (rt-with {:k/l {:handle :h/l}})
        node (cells/mount! rt [{:id :x :name :k/l}])
        child (cells/mount! rt [{:id :y :name :k/l}] {:parent node})]
    (cells/dispose! node)
    (is (empty? (cells/composition node)))
    (is (empty? (cells/composition child)))
    (is (empty? @(:children node)))))

(deftest a-failed-check-throws-with-the-errors
  (let [rt (rt-with {})
        e (try (cells/mount! rt [{:id :x :name :missing}])
               nil
               (catch Exception e e))]
    (is (some? e))
    (is (= :kmet.cells.mount/check-failed (:type (ex-data e))))
    (is (= 1 (count (:errors (ex-data e)))))
    (is (re-find #"no backend resolved" (ex-message e)))))

(deftest a-duplicate-composition-id-leaves-the-node-untouched
  (let [leaf {:handle :h/x
              :start (fn [node owner] (cells/register! node owner [:k] :x) nil)}
        twice {:handle :h :units {:inner {:handle :h/i :units {:x leaf}}}}
        rt (rt-with {:k/twice twice})
        node (cells/mount! rt [{:id :ok :name :k/twice}])
        before (cells/composition node)
        e (try (cells/mount! rt [{:id :a :name :k/twice}
                                 {:id :b :name :k/twice}])
               nil
               (catch Exception e e))]
    (is (= :kmet.cells.mount/duplicate-composition (:type (ex-data e))))
    (is (= {[:k] {[:app :ok :inner :x] :x}} (scope/registrations node))
        "the pre-pass refuses before any owner is touched")
    (is (= before (cells/composition node)))
    (is (= [[:app :ok :inner :x]] (scope/owners node)))))

(deftest disposing-an-owner-does-not-unmount-its-slot
  (let [starts (atom 0)
        unit {:handle :h/l
              :start (fn [node owner]
                       (cells/register! node owner [:k] :v)
                       (swap! starts inc)
                       nil)}
        rt (rt-with {:k/l unit})
        node (cells/mount! rt [{:id :x :name :k/l}])]
    (cells/dispose! node [:app :x])
    (is (= {} (scope/registrations node)))
    (cells/mount! rt [{:id :x :name :k/l}])
    (is (= {} (scope/registrations node))
        "the slot is unchanged, so an identical mount does not re-run :start")
    (is (= 1 @starts))))

(deftest disabled-entries-are-inert
  (let [rt (rt-with {})]
    (is (empty? (cells/composition
                 (cells/mount! rt [{:id :x :name :missing :disabled true}]))))))

(deftest re-parenting-a-group-moves-its-childrens-registrations
  (let [leaf {:handle :h/x
              :start (fn [node owner] (cells/register! node owner [:k] :x) nil)}
        group {:handle :h/g :units {:inner {:handle :h/i :units {:x leaf}}}}
        rt (rt-with {:k/group group})
        node (cells/mount! rt [{:id :ok :name :k/group}])]
    (is (= {[:k] {[:app :ok :inner :x] :x}} (scope/registrations node)))
    (cells/mount! rt [{:id :a :name :k/group}])
    (is (= {[:k] {[:app :a :inner :x] :x}} (scope/registrations node))
        "the child re-registers at its new path")
    (is (= [:app :a :inner :x] (get-in (cells/composition node :inner) [:x :owner]))
        "and the snapshot reports the new path")))

(deftest a-snapshot-is-a-value
  (let [rt (rt-with {:k/l {:handle :h/l}})
        node (cells/mount! rt [{:id :x :name :k/l :config {:n 1}}])
        snap (cells/composition node)]
    (cells/mount! rt [{:id :x :name :k/l :config {:n 2}}])
    (is (= {:n 1} (get-in snap [:x :config])) "an old snapshot never changes")
    (is (= {:n 2} (get-in (cells/composition node) [:x :config])))))

(deftest a-disabled-entry-unmounts-its-slot
  (let [rt (rt-with {:k/l {:handle :h/l}})
        node (cells/mount! rt [{:id :x :name :k/l}])]
    (cells/mount! rt [{:id :x :name :k/l :disabled true}])
    (is (empty? (cells/composition node)))))

(deftest children-are-disposed-before-their-parent
  (let [order (atom [])
        group {:handle :h/g
               :start (fn [_ _] (fn [] (swap! order conj :parent)))
               :units {:inner {:handle :h/i
                               :start (fn [_ _] (fn [] (swap! order conj :child)))}}}
        rt (rt-with {:k/group group})
        node (cells/mount! rt [{:id :g :name :k/group}])]
    (cells/dispose! node)
    (is (= [:child :parent] @order))))

(deftest bad-mount-opts-are-refused
  (let [rt (rt-with {:k/l {:handle :h/l}})
        e (try (cells/mount! rt [{:id :x :name :k/l}] [:not :opts])
               nil
               (catch Exception e e))]
    (is (= :kmet.cells.mount/bad-opts (:type (ex-data e))))))

(deftest a-non-callable-start-return-is-refused
  (let [rt (rt-with {:k/l {:handle :h/l :start (fn [_ _] 42)}})
        e (try (cells/mount! rt [{:id :x :name :k/l}])
               nil
               (catch Exception e e))]
    (is (= :kmet.cells.mount/bad-teardown (:type (ex-data e))))))

(deftest a-throwing-start-unwinds-its-own-slot
  (let [rt (rt-with {:k/bad {:handle :h/b
                             :start (fn [node owner]
                                      (cells/register! node owner [:k] :partial)
                                      (throw (ex-info "boom" {})))}})
        e (try (cells/mount! rt [{:id :bad :name :k/bad}])
               nil
               (catch Exception e e))
        node (:root rt)]
    (is (some? e))
    (is (= {} (scope/registrations node))
        "the slot's registrations were unwound")
    (is (empty? (cells/composition node))
        "and it never reached the table")))

(deftest a-failed-mount-leaves-earlier-slots-recorded
  (let [rt (rt-with {:k/good {:handle :h/g
                              :start (fn [node owner]
                                       (cells/register! node owner [:k] :good)
                                       nil)}
                     :k/bad {:handle :h/b
                             :start (fn [_ _] (throw (ex-info "boom" {})))}})
        e (try (cells/mount! rt [{:id :good :name :k/good}
                                 {:id :bad :name :k/bad}])
               nil
               (catch Exception e e))
        node (:root rt)]
    (is (some? e))
    (is (= #{:good} (set (keys (cells/composition node))))
        "the table names what the failed walk did mount")
    (is (= {[:k] {[:app :good] :good}} (scope/registrations node)))
    (cells/mount! rt [])
    (is (= {} (scope/registrations node)) "so a retry can dispose it")
    (is (empty? (cells/composition node)))))
