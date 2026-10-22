(ns kmet.app.test-events
  "kmet.app.events — the app's bus: the vocabulary and its wire projections, the
   owner a registration carries, and the contract asserted over **both delivery
   paths**.

   All six non-awaiting modes are the kernel's (`kmet.srce.core`, `srce.md` §5);
   what this namespace adds is what a kernel must not know — the event names,
   and the owner kmet attaches to a registration (`{:data owner}`, reported by
   `srce/collect-all!`).

   kmet2 dispatches over the kernel's listener table; kmet1 keeps its
   process-level table until the loop re-points (`new-app.md`, landing 2). One
   set of assertions over two backends is what keeps the pair in step while
   both exist: a behavior added to one path and not the other fails here."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.agent.event-bus :as bus]
            [kmet.app.events :as events]
            [kmet.srce.core :as srce]))

;; ── the contract, over one backend ────────────────────────────────────────

(defn- contract
  "Assert one backend's delivery behaviors. A backend is
   `{:name :register :emit-last :emit-all :boundary :clear :types}`, where
   `:register` takes `[type owner f]` and returns a deregister fn."
  [{:keys [name register emit-last emit-all boundary clear types]}]
  (testing (str "delivery contract: " name)
    (clear)
    (try
      (testing "registration order, and every listener runs"
        (let [seen (atom [])]
          (register :turn-start nil (fn [e] (swap! seen conj [:a (:n e)])))
          (register :turn-start nil (fn [e] (swap! seen conj [:b (:n e)])))
          (emit-last :turn-start {:type :turn-start :n 1})
          (is (= [[:a 1] [:b 1]] @seen))))

      (testing "the last non-nil result wins"
        (clear)
        (register :session-tree nil (constantly nil))
        (register :session-tree nil (constantly {:summary "one"}))
        (register :session-tree nil (constantly nil))
        (register :session-tree nil (constantly {:summary "two"}))
        (is (= {:summary "two"} (emit-last :session-tree {:type :session-tree}))))

      (testing "a throwing listener is reported and does not stop the rest"
        (clear)
        (register :session-before-tree nil (fn [_] (throw (ex-info "boom" {}))))
        (let [seen (atom [])]
          (register :session-before-tree nil (fn [_]
                                               (swap! seen conj :after)
                                               :after))
          (is (= :after (emit-last :session-before-tree {:type :session-before-tree}))
              "the throw contributed nothing; the next listener answered")
          (is (= [:after] @seen))))

      (testing "collect-all reports every non-nil result beside its owner"
        (clear)
        (register :resources-discover [:app :extensions "a"]
                  (constantly {:prompt-paths ["/a"]}))
        (register :resources-discover [:app :extensions "b"] (constantly nil))
        (register :resources-discover nil (constantly {:prompt-paths ["/host"]}))
        (is (= [[[:app :extensions "a"] {:prompt-paths ["/a"]}]
                [nil {:prompt-paths ["/host"]}]]
               (emit-all :resources-discover {:type :resources-discover}))))

      (testing "boundary threads the accumulated map into each listener"
        (clear)
        (register :turn-end nil (fn [e] (is (= 1 (:n e)))
                                  {:entries [:a]}))
        (register :turn-end nil (fn [e]
                                  (is (= [:a] (:entries e))
                                      "the next listener sees what was accumulated")
                                  {:continue false}))
        (register :turn-end nil (constantly nil))
        (is (= {:entries [:a] :continue false}
               (boundary :turn-end {:type :turn-end :n 1}))))

      (testing "nothing listening collects nothing"
        (clear)
        (is (empty? (emit-all :turn-start {:type :turn-start})))
        (is (nil? (emit-last :turn-start {:type :turn-start})))
        (is (nil? (boundary :turn-start {:type :turn-start}))))

      (testing "a deregistered listener stops hearing, and types lists the rest"
        (clear)
        (let [seen (atom [])
              dereg (register :message-end nil (fn [e] (swap! seen conj (:n e))))]
          (register :message-end nil (constantly nil))
          (emit-last :message-end {:type :message-end :n 1})
          (dereg)
          (emit-last :message-end {:type :message-end :n 2})
          (is (= [1] @seen))
          (is (= [:message-end] (vec (types)))
              "the surviving registration is the only name left")))
      (finally (clear)))))

(defn- bus-backend
  "kmet1's process-level table, through its public API."
  []
  {:name "kmet.agent.event-bus"
   :register (fn [type owner f] (bus/on-event type owner f))
   ;; kmet1's emitters take the event only (its table is per type)
   :emit-last (fn [_type event] (bus/emit-event! event))
   :emit-all (fn [_type event] (bus/emit-event-collect! event))
   :boundary (fn [_type event] (bus/emit-boundary! event))
   :clear bus/clear-event-listeners!
   :types bus/get-event-types})

(defn- app-backend
  "kmet2's ctx table. A fresh root per `:clear` — a root ctx is a value, so
   clearing is dropping one, and the root fiber is already active, which is
   what a registration needs."
  []
  (let [ctx (atom (srce/root))
        at (fn [] @ctx)]
    {:name "kmet.app.events"
     :register (fn [type owner f] (events/on! (at) type (when owner {:owner owner}) f))
     :emit-last (fn [type event] (srce/collect-last! (at) type event))
     :emit-all (fn [type event] (srce/collect-all! (at) type event))
     :boundary (fn [type event] (srce/boundary! (at) type event))
     :clear (fn [] (reset! ctx (srce/root)))
     :types (fn [] (srce/event-names (at)))}))

(deftest the-bus-satisfies-the-delivery-contract
  (contract (bus-backend)))

(deftest the-app-bus-satisfies-the-delivery-contract
  (contract (app-backend)))

;; ── kmet2's own modes ─────────────────────────────────────────────────────

(deftest a-subtree-hears-its-own-events-and-an-ancestor-hears-all
  (let [root (srce/root)
        agent (srce/extend root {})
        other (srce/extend root {})
        root-heard (atom [])
        agent-heard (atom [])
        other-heard (atom [])]
    (events/on! root :turn-start (fn [e] (swap! root-heard conj (:from e))))
    (events/on! agent :turn-start (fn [e] (swap! agent-heard conj (:from e))))
    (events/on! other :turn-start (fn [e] (swap! other-heard conj (:from e))))
    (srce/emit! agent :turn-start {:type :turn-start :from :agent})
    (is (= [:agent] @root-heard) "an ancestor hears the subtree")
    (is (= [:agent] @agent-heard) "the emitter hears itself")
    (is (= [] @other-heard) "a sibling does not")
    (srce/emit! root :turn-start {:type :turn-start :from :root})
    (is (= [:agent :root] @root-heard))
    (is (= [] @other-heard))))

(deftest the-owner-is-registration-data
  (let [root (srce/root)
        agent (srce/extend root {})]
    (events/on! root :turn-start {:owner :loop} (constantly :loop-answer))
    (events/on! agent :turn-start {:owner :agent} (constantly nil))
    (is (= [:agent :loop]
           (mapv :data (srce/listeners agent :turn-start nil)))
        "the owner rides the kernel's resolved list, the emitter's ctx first")
    (is (= [[:loop :loop-answer]] (srce/collect-all! agent :turn-start {}))
        "a contribution comes back beside the owner that made it")
    (is (= [:agent]
           (mapv :data (srce/listeners agent :turn-start
                                       {:filter #(identical? % agent)})))
        "the kernel's :filter narrows by listener ctx")))

(deftest once-fires-once-and-keeps-the-owner
  (let [ctx (srce/root)
        seen (atom [])]
    (events/once! ctx :session-start {:owner :host} (fn [_]
                                                      (swap! seen conj :once)
                                                      :once))
    (is (= [[:host :once]]
           (srce/collect-all! ctx :session-start {:type :session-start})))
    (is (empty? (srce/collect-all! ctx :session-start {:type :session-start})))
    (is (= [:once] @seen) "disposed before it ran, so only one delivery")))

(deftest an-unknown-event-type-is-registered-anyway
  (let [ctx (srce/root)
        seen (atom [])]
    (events/on! ctx :custom/extension-event (fn [e] (swap! seen conj (:type e))))
    (is (contains? (set (srce/event-names ctx)) :custom/extension-event)
        "the vocabulary is documented, not a gate")
    (srce/emit! ctx :custom/extension-event {:type :custom/extension-event})
    (is (= [:custom/extension-event] @seen))))

(deftest the-service-binds-the-emission-to-its-ctx
  (let [root (srce/root)
        heard (atom [])]
    (srce/provide! root :events (events/service root))
    (events/on! root :session-start (fn [e] (swap! heard conj (:n e))))
    (let [actor (srce/extend root {})
          service (srce/get actor :events)]
      ((:emit! service) :session-start {:type :session-start :n 1})
      (is (= [1] @heard) "the service emits from the ctx it serves")
      (is (= [:session-start] ((:types service))))
      (is (not (contains? service :ctx))
          "the binding is a closure: a service value never carries the ctx"))))

;; ── the vocabulary ────────────────────────────────────────────────────────

(deftest the-vocabulary-comes-from-the-declaration-in-code
  (let [declared (events/declared-events)]
    (is (= 43 (count declared)))
    (is (every? (comp string? :doc) declared) "every declared event is documented")
    (is (every? (comp keyword? :mode) declared))
    (is (every? (comp events/domains :domain) declared))
    (is (= (set (keys events/event-types)) (set (map :name declared)))
        "the accessors read the declaration, not a projection")
    (is (= [:collect-last :boundary]
           (mapv events/mode-of [:before-provider-request :turn-end]))
        "the declaration carries the dispatch mode")
    (is (= #{:loop} (into #{} (comp (map :domain) (remove #{:app})) declared))
        "the domain split is declared per event")))

(deftest every-declared-mode-is-dispatchable
  (let [dispatch {:emit srce/emit!
                  :bail srce/bail!
                  :waterfall srce/waterfall!
                  :collect-last srce/collect-last!
                  :collect-all srce/collect-all!
                  :boundary srce/boundary!}]
    (is (= (set (keys dispatch)) srce/dispatch-modes)
        "the kernel's capability list is exactly the modes it has fns for")
    (is (every? srce/dispatch-modes (map :mode (events/declared-events)))
        "every declared mode is one the kernel dispatches — a `:serial`/`:parallel`
         declaration would fail here: the kernel has no awaitable (srce.md §8)")))

(deftest a-name-declared-twice-must-agree
  (let [by-name (->> (events/declared-events) (group-by :name))
        conflicts (for [[name decls] by-name
                        :when (> (count decls) 1)
                        :when (> (count (set (map (juxt :mode :domain :doc) decls))) 1)]
                    name)]
    (is (empty? conflicts)
        "two plugins may declare the same event only with the same mode, domain and doc")))

(deftest the-vocabulary-is-documented-data
  (is (map? events/event-types))
  (is (pos? (count events/event-types)))
  (is (every? keyword? (keys events/event-types)))
  (is (every? string? (vals events/event-types)))
  (is (every? events/known-event-type? (keys events/event-types)))
  (is (not (events/known-event-type? :srce/status))
      "the kernel's own names are documented in srce.md §5, never here"))

(deftest app-and-loop-event-types-partition-the-vocabulary
  (is (every? #(contains? events/event-types %) events/app-event-types))
  (is (not-any? #(contains? events/loop-event-types %) events/app-event-types))
  (is (= (set (keys events/event-types))
         (into (set events/app-event-types) (keys events/loop-event-types)))))
