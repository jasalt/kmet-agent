(ns kmet.hikap.test-support
  "Minimal fake backend for exercising kmet.hikap.core without a toolkit."
  (:require [clojure.string :as str]
            [kmet.hikap.core :as engine]))

(defrecord FakeComp [kind props children-atom disposed-atom])

(defn make-comp
  [kind props]
  (->FakeComp kind (or props {}) (atom []) (atom false)))

(defn comp-props
  [c]
  (if (and (record? c) (contains? c :dsl/meta) (some? (:dsl/meta c)))
    (or (:props @(:dsl/meta c)) (:props c))
    (:props c)))

(defn comp-children
  [c]
  (if (instance? FakeComp c)
    @(:children-atom c)
    []))

(defn component-fn?
  "Heuristic: an engine ComponentFn wrapper."
  [c]
  (and (map? c)
       (contains? c :backend)
       (contains? c :f)
       (contains? c :kids)
       (contains? c :rx)
       (contains? c :ctree)))

(defn comp-render
  [c _ctx]
  (case (:kind c)
    :text (str "TEXT:" (or (:text (comp-props c)) ""))
    :box (str "BOX[" (str/join "," (map #(comp-render % _ctx) (comp-children c))) "]")
    (str "COMP:" (pr-str (:kind c)))))

(defn disposed? [c]
  (boolean @(:disposed-atom c)))

(defn make-backend
  ([]
   (make-backend {}))
  ([extra-tags]
   (let [tags (merge {:text {:ctor (fn [props] (make-comp :text props))
                             :primary :text}
                      :box {:ctor (fn [props] (make-comp :box props))
                            :lens {:get (fn [c] (or (comp-children c) []))
                                   :put (fn [c kept] (reset! (:children-atom c) (vec (map :c kept))))}
                            :apply (fn [_c _prev _props] true)}}
                     extra-tags)]
     {:name :fake
      :tags tags
      :text-leaf (fn [text props] (make-comp :text (assoc (or props {}) :text text)))
      :component? (fn [x] (instance? FakeComp x))
      :entry-component nil
      :dispose! (fn [c]
                  (when (instance? FakeComp c)
                    (reset! (:disposed-atom c) true)))
      :render (fn [c ctx]
                (if (component-fn? c)
                  (engine/render-component c ctx)
                  (comp-render c ctx)))
      :render-all (fn [cs ctx]
                    (mapv (fn [c]
                            (if (component-fn? c)
                              (engine/render-component c ctx)
                              (comp-render c ctx)))
                          cs))
      :render-items (fn [items ctx]
                      (mapv (fn [item]
                              (let [c (:c item)]
                                (if (component-fn? c)
                                  (engine/render-component c ctx)
                                  (comp-render c ctx))))
                            items))
      :bind-context (fn [_comp _ctx thunk] (thunk))
      :did-you-mean nil})))

(def backend (make-backend {}))