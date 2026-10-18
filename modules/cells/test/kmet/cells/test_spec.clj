(ns kmet.cells.test-spec
  "The two checks (cells.md §4): shape and config, plus resolution and the
   `:into` tree building."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.cells.spec :as spec]))

(def ^:private units
  {:kmet/loop      {:handle (fn [_ _]) :units {:inner {:handle (fn [_ _])}}}
   :kmet/stage     {:handle (fn [_ _])}
   :kmet/retry     {:config [:map [:max-retries :int]] :handle (fn [_ _])}
   :kmet/raw       {}
   :kmet/bad-units {:units "not a map"}
   :kmet/bad-child {:handle (fn [_ _]) :units {:oops 1}}
   :kmet/nil-handle {:handle nil}
   :kmet/bad-handle {:handle 42}
   :kmet/bad-start {:handle (fn [_ _]) :start 42}
   :kmet/map-start {:handle (fn [_ _]) :start {}}
   :kmet/bad-schema {:config 42 :handle (fn [_ _])}
   :kmet/nested-group {:handle (fn [_ _])
                       :units {:inner {:handle (fn [_ _])
                                       :units {:x {:handle (fn [_ _])}}}}}})

(def ^:private rt
  (spec/runtime {:resolve [[:builtin (fn [nm] (get units nm))]
                           [:ext (fn [nm] (when (= nm :ext/thing) {:handle (fn [_ _])}))]]}))

(deftest runtime-validates-its-host
  (is (= 2 (count (:resolve rt))))
  (doseq [host [{} {:resolve nil} {:resolve []} {:resolve [[:builtin "not a fn"]]}]]
    (is (thrown? Exception (spec/runtime host)) (pr-str host))))

(deftest empty-entries-need-nothing
  (is (= [] (spec/check rt []))))

(deftest names-resolve-through-backends-in-order
  (is (= [] (spec/check rt [{:id :loop :name :kmet/loop}])))
  (is (= [] (spec/check rt [{:id :thing :name :ext/thing}]))))

(deftest a-miss-names-every-backend
  (let [[e] (spec/check rt [{:id :nope :name :kmet/nope}])]
    (is (= [:app :nope] (:path e)))
    (is (= :nope (:entry e)))
    (is (= :name (:key e)))
    (is (str/includes? (:error e) "(tried :builtin, :ext)"))))

(deftest a-throwing-backend-is-reported
  (let [rt (spec/runtime {:resolve [[:builtin (fn [_] (throw (ex-info "kaput" {})))]]})
        [e] (spec/check rt [{:id :x :name :kmet/x}])]
    (is (= :name (:key e)))
    (is (str/includes? (:error e) "kaput"))))

(deftest configs-validate-against-the-unit-schema
  (is (= [] (spec/check rt [{:id :r :name :kmet/retry :config {:max-retries 3}}])))
  (is (= [] (spec/check rt [{:id :r :name :kmet/retry}])) "no config is fine")
  (is (= [] (spec/check rt [{:id :s :name :kmet/stage :config {:anything 1}}]))
      "a unit without a schema accepts any config")
  (let [[e] (spec/check rt [{:id :r :name :kmet/retry :config {:max-retries "3"}}])]
    (is (= :config (:key e)))
    (is (= [:app :r] (:path e)))))

(deftest unit-shape-is-checked-recursively
  (let [errors (spec/check rt [{:id :raw :name :kmet/raw}
                               {:id :bad :name :kmet/bad-units}
                               {:id :child :name :kmet/bad-child}])]
    (is (= 3 (count errors)))
    (is (= [:descriptor :units :descriptor] (mapv :key errors)))
    (is (= [:app :child :oops] (:path (nth errors 2))))))

(deftest into-names-an-existing-composition
  (is (= [] (spec/check rt [{:id :loop :name :kmet/loop}
                            {:id :inner :name :kmet/stage :into :loop}])))
  (is (= [] (spec/check rt [{:id :loop :name :kmet/loop}
                            {:id :child :name :kmet/loop :into :loop}]))))

(deftest paths-follow-the-into-chain
  (let [[e] (spec/check rt [{:id :loop :name :kmet/loop}
                            {:id :inner :name :kmet/nope :into :loop}])]
    (is (= [:app :loop :inner] (:path e)))))

(deftest into-errors-are-named
  (let [[e] (spec/check rt [{:id :x :name :kmet/stage :into :nope}])]
    (is (= :into (:key e)))
    (is (str/includes? (:error e) "no composition")))
  (let [[e] (spec/check rt [{:id :s :name :kmet/stage}
                            {:id :x :name :kmet/stage :into :s}])]
    (is (= :into (:key e)))
    (is (str/includes? (:error e) "not a composition")))
  (let [errors (spec/check rt [{:id :a :name :kmet/loop :into :b}
                               {:id :b :name :kmet/loop :into :a}])]
    (is (some #(str/includes? (:error %) "cycle") errors))))

(deftest duplicate-and-reserved-ids
  (let [errors (spec/check rt [{:id :x :name :kmet/stage}
                               {:id :x :name :kmet/stage}])]
    (is (= 1 (count errors)))
    (is (= :id (:key (first errors)))))
  (let [[e] (spec/check rt [{:id :app :name :kmet/stage}])]
    (is (= :id (:key e)))))

(deftest disabled-entries-are-inert
  (is (= [] (spec/check rt [{:id :x :name :kmet/nope :disabled true}]))))

(deftest structural-entry-errors
  (is (= :entry (:key (first (spec/check rt [42])))))
  (is (= :id (:key (first (spec/check rt [{:name :kmet/stage}])))))
  (is (= :name (:key (first (spec/check rt [{:id :x}]))))))

(deftest inline-groups-are-not-entry-compositions
  (let [[e] (spec/check rt [{:id :g :name :kmet/nested-group}
                            {:id :child :into :inner :name :kmet/stage}])]
    (is (= :into (:key e)))
    (is (= [:app :child] (:path e)))
    (is (str/includes? (:error e) "no composition :inner"))))

(deftest nil-handles-are-rejected
  (let [[e] (spec/check rt [{:id :x :name :kmet/nil-handle}])]
    (is (= :descriptor (:key e)))
    (is (= [:app :x] (:path e)))))

(deftest handles-must-be-callable-and-starts-a-fn-or-var
  (let [[e] (spec/check rt [{:id :h :name :kmet/bad-handle}])]
    (is (= :handle (:key e)))
    (is (= [:app :h] (:path e))))
  (doseq [[id nm] {:s :kmet/bad-start :m :kmet/map-start}]
    (let [[e] (spec/check rt [{:id id :name nm}])]
      (is (= :start (:key e)) (pr-str nm))
      (is (= [:app id] (:path e))))))

(deftest duplicate-inline-composition-ids-are-refused-by-check
  (let [twice {:handle (fn [_ _])
               :units {:inner {:handle (fn [_ _])
                               :units {:x {:handle (fn [_ _])}}}}}
        rt (spec/runtime {:resolve [[:builtin (fn [_] twice)]]})
        [e] (spec/check rt [{:id :a :name :k/twice}
                            {:id :b :name :k/twice}])]
    (is (= :id (:key e)))
    (is (= [:app :b :inner] (:path e)))
    (is (str/includes? (:error e) "must be unique"))))

(deftest an-unusable-config-schema-is-reported
  (let [[e] (spec/check rt [{:id :bs :name :kmet/bad-schema :config {}}])]
    (is (= :config (:key e)))
    (is (str/includes? (:error e) "malli schema"))))

(deftest a-broken-into-chain-is-not-a-cycle
  (let [errors (spec/check rt [{:id :a :name :kmet/loop :into :nope}
                               {:id :c :name :kmet/stage :into :a}])]
    (is (= 2 (count errors)))
    (is (str/includes? (:error (first errors)) "no composition :nope"))
    (is (str/includes? (:error (second errors)) "not reachable"))))

(deftest nested-into-paths
  (let [errors (spec/check rt [{:id :outer :name :kmet/loop}
                               {:id :inner :name :kmet/loop :into :outer}
                               {:id :x :name :kmet/nope :into :inner}])]
    (is (= 1 (count errors)))
    (is (= [:app :outer :inner :x] (:path (first errors))))))

(deftest slot-ids-may-repeat-across-compositions
  (is (= [] (spec/check rt [{:id :a :name :kmet/loop}
                            {:id :b :name :kmet/loop}
                            {:id :inner :name :kmet/stage :into :a}
                            {:id :inner :name :kmet/stage :into :b}]))))

(deftest composition-ids-must-be-unique
  (let [errors (spec/check rt [{:id :g :name :kmet/loop}
                               {:id :h :name :kmet/loop}
                               {:id :g :name :kmet/loop :into :h}])]
    (is (= 1 (count errors)))
    (is (str/includes? (:error (first errors)) "must be unique"))))

(deftest plan-groups-entries-by-target-composition
  (let [p (spec/plan rt [{:id :loop :name :kmet/loop}
                         {:id :top :name :kmet/stage}
                         {:id :inner :name :kmet/stage :into :loop}])]
    (is (= [] (:errors p)))
    (is (= #{:app :loop} (set (keys (:compositions p)))))
    (is (= [:loop :top] (mapv :id (get (:compositions p) :app))))
    (is (= [:inner] (mapv :id (get (:compositions p) :loop))))))
