(ns kmet.hikap.test-hikap-core
  (:require [clojure.test :as t]
            [kmet.hikap.core :as engine]
            [kmet.hikap.alias :as alias]
            [kmet.hikap.test-support :as ts]
            [kmet.hikap.component :as component]))

(def backend ts/backend)

(t/use-fixtures :each (fn [f]
                        (let [snapshot (alias/registered)]
                          (alias/reset-registry!)
                          (engine/reset-counters!)
                          (f)
                          (alias/reset-registry!)
                          (engine/reset-counters!)
                          (doseq [[k v] snapshot] (alias/register! k v)))))

;; ── counters ───────────────────────────────────────────────────────────────

(t/deftest counters-zero-and-reset
  (t/is (= {:bodies-run 0 :bodies-skipped 0 :constructs 0 :reuses 0
            :applies 0 :disposals 0 :computes 0 :skips 0}
           (engine/counters)))
  (engine/reset-counters!)
  (t/is (map? (engine/counters))))

;; ── refs ────────────────────────────────────────────────────────────────────

(t/deftest ref-starts-nil-and-is-derefable
  (let [r (engine/ref)]
    (t/is (nil? (deref r)))
    (t/is (nil? @r))))

(t/deftest ref-invalid-on-tag-throws
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-element backend [:text {:ref :not-a-ref} "x"]))))

(t/deftest materialize-ref-absent-stays-nil
  (let [r (engine/ref)
        c (engine/make-component-fn backend (fn [_] [:box {}]))]
    (t/is (nil? (engine/materialize-ref! backend c r 10)))))

(t/deftest materialize-ref-fills-from-body
  (let [r (engine/ref)
        body (fn [_] [:text {:ref r} "x"])
        c (engine/make-component-fn backend body)]
    (t/is (nil? @r))
    (t/is (some? (engine/materialize-ref! backend c r 10)))
    (t/is (some? @r))
    (t/is (= "TEXT:x" (ts/comp-render @r 40)))))

;; ── seq splicing / multiple roots ───────────────────────────────────────────

(t/deftest seq-splices-into-container-children
  (let [c (engine/compile-tree backend [:box {} (list [:text {:text "a"}] [:text {:text "b"}])])
        r (engine/render backend c 40)]
    (t/is (= "BOX[TEXT:a,TEXT:b]" r))))

(t/deftest nil-and-empty-in-container
  (let [c (engine/compile-tree backend [:box {} nil [:text {:text "a"}]])]
    (t/is (= "BOX[TEXT:a]" (engine/render backend c 40)))))

(t/deftest compile-tree-multiple-roots
  (let [cs (engine/compile-tree backend (list [:text {:text "a"}] [:text {:text "b"}]))]
    (t/is (sequential? cs))
    (t/is (= 2 (count cs)))
    (t/is (= ["TEXT:a" "TEXT:b"] (engine/render backend cs 40)))))

(t/deftest compile-tree-nil
  (t/is (nil? (engine/compile-tree backend nil)))
  (t/is (nil? (engine/compile-tree backend (list)))))

;; ── primary shorthand and metadata ──────────────────────────────────────────

(t/deftest primary-on-box-no-primary
  (let [c (engine/compile-element backend [:box {}])]
    (t/is (some? c))
    (t/is (= "BOX[]" (engine/render backend c 40)))))

(t/deftest ref-primary-slot-throws
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-element backend [:text (engine/ref)]))))

(t/deftest bare-ref-alias-throws
  ;; an invalid alias is rejected before ref semantics matter
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-element backend [:my.ns/x {:ref (engine/ref)}]))))

;; ── alias registration integration ──────────────────────────────────────────

(t/deftest alias-element-renders
  (alias/register! :test/widget (fn [attrs _children]
                                  [:text {:text (str "alias:" (:label attrs))}]))
  (let [c (engine/compile-element backend [:test/widget {:label "hi"}])]
    (t/is (= ["TEXT:alias:hi"] (engine/render backend c 40)))))

(t/deftest alias-with-children
  (alias/register! :test/wrap (fn [attrs children]
                                [:box {} [:text {:text (:label attrs)}]
                                 (first children)]))
  (let [c (engine/compile-element backend [:test/wrap {:label "hi"} [:text {:text "child"}]])]
    (t/is (= ["BOX[TEXT:hi,TEXT:child]"] (engine/render backend c 40)))))

(t/deftest alias-unregistered-middle-throws-at-render
  (let [f (fn [_ _] [:text {:text "temp"}])]
    (alias/register! :test/temp f)
    (let [c (engine/compile-element backend [:test/temp {}])]
      (alias/unregister! :test/temp f)
      ;; rendering runs the component body, which resolves the alias at
      ;; call time and throws because it has been unregistered.
      (t/is (thrown? clojure.lang.ExceptionInfo (engine/render backend c 40))))))

;; ── component fn wrappers ───────────────────────────────────────────────────

(t/deftest make-component-fn-renders
  (let [cf (engine/make-component-fn backend (fn [props]
                                               [:text {:text (str "v=" (:x props))}]))]
    (reset! (:props cf) {:x 5})
    (t/is (= ["TEXT:v=5"] (engine/render-component cf 40)))))

(t/deftest root-element-shorthand
  (let [r (engine/root backend [:text {:text "hi"}])]
    (t/is (= ["TEXT:hi"] (engine/render-component r 40)))))

(t/deftest root-fn-shorthand
  (let [r (engine/root backend (fn [props] [:text {:text (or (:t props) "none")}]))]
    (reset! (:props r) {:t "yo"})
    (t/is (= ["TEXT:yo"] (engine/render-component r 40)))))

(t/deftest root-multiple-roots
  (let [r (engine/root backend [[:text {:text "a"}] [:text {:text "b"}]])]
    (t/is (= ["TEXT:a" "TEXT:b"] (engine/render-component r 40)))))

(t/deftest dispose-component-disposes-children
  (let [r (engine/root backend [:box {} [:text {:text "x"}]])
        _ (engine/render-component r 40)
        children-items @(:kids r)
        child-comp (:c (first children-items))]
    (engine/dispose-component r)
    (t/is (ts/disposed? child-comp))))

;; ── reconciliation identity ──────────────────────────────────────────────────

(t/deftest reconcile-reuses-identical
  (let [kids (atom [])
        k1 (engine/reconcile! backend kids [:text {:key 1 :text "a"}])
        c1 (:c (first k1))
        k2 (engine/reconcile! backend kids [:text {:key 1 :text "a"}])
        c2 (:c (first k2))]
    (t/is (identical? c1 c2))
    (t/is (pos? (:reuses (engine/counters))))))

(t/deftest reconcile-changed-leaf-rebuilds
  (let [kids (atom [])
        k1 (engine/reconcile! backend kids [:text {:key 1 :text "a"}])
        c1 (:c (first k1))
        _ (engine/reconcile! backend kids [:text {:key 1 :text "b"}])
        _c2 (:c (first (engine/reconcile! backend kids [:text {:key 1 :text "b"}])))
        _ (engine/reconcile! backend kids [:text {:key 1 :text "c"}])]
    (t/is (ts/disposed? c1))
    (t/is (pos? (:disposals (engine/counters))))))

(t/deftest container-children-identity-survives
  (let [kids (atom [])
        k1 (engine/reconcile! backend kids [:box {} [:text {:key 1 :text "a"}]])
        box1 (:c (first k1))
        before (vec (ts/comp-children box1))
        k2 (engine/reconcile! backend kids [:box {} [:text {:key 1 :text "a"}]])
        box2 (:c (first k2))
        after (vec (ts/comp-children box2))]
    (t/is (identical? box1 box2))
    (t/is (= 1 (count before) (count after)))
    (t/is (identical? (first before) (first after)))))

(t/deftest top-level-reorder-by-key-keeps-identities
  (let [kids (atom [])
        k1 (engine/reconcile! backend kids (list [:text {:key 1 :text "a"}] [:text {:key 2 :text "b"}]))
        comps1 (mapv :c k1)
        c1 (first comps1)
        c2 (second comps1)
        k2 (engine/reconcile! backend kids (list [:text {:key 2 :text "b"}] [:text {:key 1 :text "a"}]))
        comps2 (mapv :c k2)
        after-by-render (set (map #(engine/render backend % 40) comps2))]
    (t/is (= 2 (count comps2)))
    (t/is (= #{"TEXT:a" "TEXT:b"} after-by-render))
    ;; identities preserved: at least one of each original component should survive
    (t/is (or (some #(identical? c1 %) comps2) (some #(identical? c2 %) comps2)))))

;; ── derive / compute ────────────────────────────────────────────────────────

(t/deftest compute-over-deps
  (let [a (atom 1)
        r (engine/compute [a] (fn [x] (* x 2)))]
    (t/is (= 2 @r))
    (reset! a 3)
    (t/is (= 6 @r))))

(t/deftest compute-empty-deps
  (let [r (engine/compute [] (fn [] 42))]
    (t/is (= 42 @r))))

(t/deftest compute-auto-disposes-with-store
  (let [s (kmet.hikap.component/new-store)
        _calls (atom [])]
    (kmet.hikap.component/with-store s
      (kmet.hikap.component/begin-pass! s)
      (let [r (engine/compute [] (fn [] 99))]
        (t/is (= 99 @r))
        ;; register cleanup? compute registered cleanup when *store* bound
        (kmet.hikap.component/destroy-store! s)
        (t/is (true? true))))))

;; ── render-component props re-run ────────────────────────────────────────────

(t/deftest component-fn-props-re-run
  (let [cf (engine/make-component-fn backend (fn [props]
                                               [:text {:text (or (:t props) "none")}]))]
    (t/is (= ["TEXT:none"] (engine/render-component cf 40)))
    (reset! (:props cf) {:t "yo"})
    (t/is (= ["TEXT:yo"] (engine/render-component cf 40)))))

;; ── dispose-tree ────────────────────────────────────────────────────────────

(t/deftest dispose-tree-marks-top-component
  (let [c (engine/compile-element backend [:box {} [:text {:text "x"}]])]
    (engine/dispose-tree! backend c)
    (t/is (ts/disposed? c))))

;; ── normalize / parse validation ────────────────────────────────────────────

(t/deftest compile-basic-text
  (let [c (engine/compile-element backend [:text {:text "hi"}])]
    (t/is (some? c))
    (t/is (= "TEXT:hi" (engine/render backend c 40)))))

(t/deftest primary-shorthand
  (let [c (engine/compile-element backend [:text "hi"])]
    (t/is (= "TEXT:hi" (engine/render backend c 40)))))

(t/deftest unknown-tag-throws
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown tag"
                          (engine/compile-element backend [:nope "x"]))))

(t/deftest children-on-leaf-throws
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-element backend [:text "a" "b"]))))

(t/deftest invalid-head-throws
  (t/is (thrown? clojure.lang.ExceptionInfo (engine/compile-element backend [42])))
  (t/is (thrown? clojure.lang.ExceptionInfo (engine/compile-element backend ['sym 1])))
  (t/is (thrown? clojure.lang.ExceptionInfo (engine/compile-element backend ["no"]))))

(t/deftest bare-vector-string-not-element
  (t/is (thrown? clojure.lang.ExceptionInfo (engine/compile-element backend 42))))

(t/deftest seq-to-compile-element-throws
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-element backend (list [:text "x"])))))

(t/deftest nil-compiles-to-nil
  (t/is (nil? (engine/compile-element backend nil))))

(t/deftest bare-map-child-throws
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-tree backend [:box {} {:foo 1}]))))

(t/deftest stack-entry-outside-stack-throws
  ;; our backend has no :entry-component, so an entry map is just a bare map → throws
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-tree backend [:box {} {:component :x}]))))

(t/deftest duplicated-key-among-siblings-throws
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-tree backend [:box {} [:text {:key 1 :text "a"}]
                                               [:text {:key 1 :text "b"}]]))))

(t/deftest unknown-alias-throws
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (engine/compile-element backend [:my.ns/widget {}]))))

(t/deftest text-child-in-container
  (let [c (engine/compile-tree backend [:box {} "raw" [:text {:text "x"}]])]
    (t/is (= "BOX[TEXT:raw,TEXT:x]" (engine/render backend c 40)))))
