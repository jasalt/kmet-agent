(ns kmet.agent.test-event-bus
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.agent.debug :as debug]
            [kmet.agent.event-bus :as event-bus]
            [kmet.srce.core :as srce]))

;; ─── Event vocabulary ─────────────────────────────────────────────────────

(t/deftest test-events-vocabulary
  (t/is (map? event-bus/event-types))
  (t/is (pos? (count event-bus/event-types)))
  (t/is (every? keyword? (keys event-bus/event-types)))
  (t/is (every? string? (vals event-bus/event-types))))

(t/deftest test-events-core-types-known
  (doseq [t [:agent-start :agent-end :agent-settled :turn-start :turn-end
             :agent-before-settle
             :message-start :message-update :message-end
             :tool-execution-start :tool-execution-update :tool-execution-end
             :status :error :user-bash]]
    (t/is (event-bus/known-event-type? t) (str t " should be a known event type"))))

(t/deftest test-events-unknown-types
  (t/is (not (event-bus/known-event-type? :bogus)))
  (t/is (not (event-bus/known-event-type? nil)))
  (t/is (not (event-bus/known-event-type? "agent-start"))))

(t/deftest test-session-shutdown-event
  (t/is (event-bus/known-event-type? :session-shutdown)
        "session_shutdown is part of the vocabulary (pi: SessionShutdownEvent)")
  (t/is (contains? event-bus/app-event-types :session-shutdown)
        "emitted outside the loop by the interactive mode, like :session-start"))

(t/deftest test-resources-discover-event
  (t/is (event-bus/known-event-type? :resources-discover)
        "resources_discover is part of the vocabulary (pi: ResourcesDiscoverEvent)")
  (t/is (contains? event-bus/app-event-types :resources-discover)
        "emitted outside the loop (extensions/discover-resources!), not consumed by the UI"))

;; ─── Event bus ────────────────────────────────────────────────────────────

(t/deftest test-event-register-and-emit
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :turn-start (fn [e] (swap! log conj e)))
    (event-bus/emit-event! {:type :turn-start :data "hello"})
    (t/is (= 1 (count @log)))
    (t/is (= :turn-start (:type (first @log))))
    (t/is (= "hello" (:data (first @log))))))

(t/deftest test-event-multiple-listeners
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :message-start (fn [e] (swap! log conj (str "a:" (:val e)))))
    (event-bus/on-event :message-start (fn [e] (swap! log conj (str "b:" (:val e)))))
    (event-bus/emit-event! {:type :message-start :val 42})
    (t/is (= 2 (count @log)))
    (t/is (str/includes? (first @log) "42"))))

(t/deftest test-emit-collect-pairs-each-result-with-its-owner
  ;; Resource discovery applies a contribution under the owner that made it,
  ;; so the collect emitter reports the pair (P5c).
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :resources-discover [:app :extensions "a"]
                      (fn [_] {:skill-paths ["/a"]}))
  (event-bus/on-event :resources-discover (fn [_] {:prompt-paths ["/host"]}))
  (event-bus/on-event :resources-discover [:app :extensions "b"] (fn [_] nil))
  (t/is (= {[:app :extensions "a"] {:skill-paths ["/a"]}
            nil {:prompt-paths ["/host"]}}
           (into {} (event-bus/emit-event-collect! {:type :resources-discover})))
        "the nil result is skipped, the host's registration carries no owner"))

(t/deftest test-event-no-listeners
  (t/testing "Emitting with no listeners should not throw"
    (event-bus/clear-event-listeners!)
    (event-bus/emit-event! {:type :unregistered :data "test"})))

(t/deftest test-event-deregister
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (let [dereg (event-bus/on-event :message-end (fn [e] (swap! log conj e)))]
      (event-bus/emit-event! {:type :message-end :data "first"})
      (dereg)
      (event-bus/emit-event! {:type :message-end :data "second"})
      (t/is (= 1 (count @log)))
      (t/is (= "first" (:data (first @log)))))))

(t/deftest test-event-types-distinct
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :agent-start identity)
  (event-bus/on-event :agent-end identity)
  (let [types (event-bus/get-event-types)]
    (t/is (contains? (set types) :agent-start))
    (t/is (contains? (set types) :agent-end))))

(t/deftest test-event-clear
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :status identity)
  (event-bus/clear-event-listeners!)
  (t/is (empty? (event-bus/get-event-types))))

(t/deftest test-event-listener-error-handling
  (let [log (atom [])
        reported (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :error (fn [_] (throw (Exception. "boom"))))
    (event-bus/on-event :error (fn [e] (swap! log conj (:data e))))
    (with-redefs [debug/log-error (fn [& parts] (swap! reported conj parts))]
      (event-bus/emit-event! {:type :error :data "ok"}))
    (t/is (= 1 (count @log)))
    (t/is (= "ok" (first @log)))
    (t/is (seq @reported) "the handler error reaches the agent's error reporter")))

(t/deftest test-event-returns-last-non-nil-result
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :session-before-tree (fn [_] {:summary "ext summary"}))
  (event-bus/on-event :session-before-tree (fn [_] nil))
  (t/is (= {:summary "ext summary"}
           (event-bus/emit-event! {:type :session-before-tree :x 1}))
        "emit returns the last non-nil handler result (pi: runner.emit)")
  (t/is (nil? (event-bus/emit-event! {:type :unregistered :x 1}))
        "no listeners → nil"))

(t/deftest test-event-handler-error-swallowed
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :message-update (fn [_] (throw (ex-info "boom" {}))))
  (event-bus/on-event :message-update (fn [_] {:ok true}))
  (with-redefs [debug/log-error (fn [& _])]
    (t/is (= {:ok true} (event-bus/emit-event! {:type :message-update}))
          "an errored handler doesn't prevent later handlers or throw")))

(t/deftest test-listeners-dispatch-in-registration-order
  ;; Deterministic order (F11): one vector per type, so ten listeners fire in
  ;; the order they registered, and a removed one stays gone — the UUID-keyed
  ;; map shuffled beyond eight entries and a /reload could reshuffle it.
  (let [seen (atom [])]
    (event-bus/clear-event-listeners!)
    (let [deregs (mapv (fn [i] (event-bus/on-event :turn-end (fn [_] (swap! seen conj i))))
                       (range 10))]
      (event-bus/emit-event! {:type :turn-end})
      (t/is (= (vec (range 10)) @seen)
            "ten listeners fire in registration order")
      ((deregs 1))
      ((deregs 3))
      (event-bus/on-event :turn-end (fn [_] (swap! seen conj 99)))
      (reset! seen [])
      (event-bus/emit-event! {:type :turn-end})
      (t/is (= [0 2 4 5 6 7 8 9 99] @seen)
            "a removed listener stays gone; a later registration lands last"))))

(t/deftest test-collect-and-boundary-inherit-registration-order
  ;; F11 reads the same vector for all three emitters, so collect's
  ;; [owner result] pairs and the boundary fold are ordered too — not just
  ;; emit's last-non-nil.
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :resources-discover [:app :extensions "a"]
                      (fn [_] {:skill-paths ["/a"]}))
  (event-bus/on-event :resources-discover (fn [_] {:prompt-paths ["/h"]}))
  (event-bus/on-event :resources-discover [:app :extensions "b"]
                      (fn [_] {:skills ["/b"]}))
  (t/is (= [[:app :extensions "a"] nil [:app :extensions "b"]]
           (mapv first (event-bus/emit-event-collect! {:type :resources-discover})))
        "collect pairs follow registration order across owners")
  (let [order (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :turn-end (fn [_] (swap! order conj :first) {:entries [:a]}))
    (event-bus/on-event :turn-end (fn [_] (swap! order conj :second) {:entries [:b]}))
    (t/is (= {:entries [:b]} (event-bus/emit-boundary! {:type :turn-end :entries []}))
          "the later handler's keys win, as documented")
    (t/is (= [:first :second] @order) "handlers run in registration order")
    ;; the third arity seeds the accumulation with a decision another layer
    ;; already reached (the loop hands over the kernel's): every handler sees
    ;; it, and the chain continues rather than starting a second one
    (let [seen (atom [])]
      (event-bus/clear-event-listeners!)
      (event-bus/on-event :turn-end (fn [ev] (swap! seen conj (:continue ev)) {:entries [:t]}))
      (t/is (= {:entries [:t] :continue true}
               (event-bus/emit-boundary! {:type :turn-end} nil {:continue true}))
            "the seeded decision survives the chain")
      (t/is (= [true] @seen) "and the handler sees it merged into its event")
      (t/is (= {:continue true :entries [:t]}
               (event-bus/emit-boundary! {:type :turn-end} nil {:continue true}))
            "and a key a handler does not return survives the merge"))))

(t/deftest test-an-unknown-event-type-is-reported
  ;; A typo'd :turn-ended used to register silently; the vocabulary is
  ;; documented, not a gate, so the registration lands and the typo is
  ;; reported through the agent's error reporter (F12).
  (let [reported (atom [])]
    (event-bus/clear-event-listeners!)
    (with-redefs [debug/log-error (fn [& parts] (swap! reported conj (apply str parts)))]
      (event-bus/on-event :turn-ended identity))
    (t/is (seq @reported) "the unknown type is reported")
    (t/is (str/includes? (first @reported) "turn-ended"))
    (t/is (contains? (set (event-bus/get-event-types)) :turn-ended)
          "and the registration still lands")))

(t/deftest test-a-throwable-does-not-stop-the-bus
  ;; Throwable, not Exception: an Error from one handler must not skip the
  ;; handlers after it.
  (let [log (atom [])
        reported (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :message-update (fn [_] (throw (AssertionError. "boom"))))
    (event-bus/on-event :message-update (fn [e] (swap! log conj (:data e))))
    (with-redefs [debug/log-error (fn [& parts] (swap! reported conj parts))]
      (event-bus/emit-event! {:type :message-update :data "after"}))
    (t/is (= ["after"] @log) "the handlers after the Error still run")
    (t/is (seq @reported) "and the Error is reported like any handler failure")))

;; ─── Boundary dispatch (pi: runner.emitBoundary) ──────────────────────────

(t/deftest test-boundary-threads-handler-results
  (let [seen (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :turn-end (fn [e] (swap! seen conj e) {:entries [:a]}))
    (event-bus/on-event :turn-end (fn [e] (swap! seen conj e) {:continue true}))
    (t/is (= {:entries [:a] :continue true}
             (event-bus/emit-boundary! {:type :turn-end :entries [] :continue false}))
          "each handler's keys replace the accumulated ones")
    (t/is (= [] (:entries (first @seen))) "the first handler sees the event as given")
    (t/is (= [:a] (:entries (second @seen)))
          "the second handler sees the first handler's entries (pi: emitBoundary)")
    (t/is (false? (:continue (first @seen))))
    (t/is (nil? (event-bus/emit-boundary! {:type :unregistered}))
          "no listeners → nil")))

(t/deftest test-boundary-nil-and-non-map-results-keep-the-state
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :agent-settled (fn [_] {:entries [:a]}))
  (event-bus/on-event :agent-settled (fn [_] nil))
  (event-bus/on-event :agent-settled (fn [_] :bogus))
  (t/is (= {:entries [:a]}
           (event-bus/emit-boundary! {:type :agent-settled :entries []}))
        "a nil or non-map result leaves the accumulated state alone"))

(t/deftest test-boundary-handler-error-swallowed
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :agent-before-settle (fn [_] (throw (ex-info "boom" {}))))
  (event-bus/on-event :agent-before-settle (fn [_] {:continue true}))
  (with-redefs [debug/log-error (fn [& _])]
    (t/is (= {:continue true} (event-bus/emit-boundary! {:type :agent-before-settle}))
          "an errored boundary handler is skipped, later handlers still run")))

;; ─── Scoped subscriptions (F13) ───────────────────────────────────────────

(t/deftest test-a-scope-must-be-a-ctx
  ;; A store node walks :parent too, so the subtree test would "work" and match
  ;; the wrong tree. Registration is where the mistake is made, so it is where
  ;; it is reported.
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"subscription's scope must be a ctx"
                          (event-bus/on-event :turn-start nil (fn [_]) {:parent nil})))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"emission's ctx must be a ctx"
                          (event-bus/emit-event! {:type :turn-start} {:parent nil})))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"emission's ctx must be a ctx"
                          (event-bus/emit-boundary! {:type :turn-end} {:parent nil})))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"emission's ctx must be a ctx"
                          (event-bus/emit-event-collect! {:type :resources-discover} {:parent nil}))))

(t/deftest test-a-scoped-listener-hears-its-own-subtree
  ;; A run subscribes with its own ctx: child events reach it, a parent's or
  ;; sibling's do not, and an unscoped listener (an extension) keeps hearing
  ;; everything (new-app.md, landing 4c-ii-b).
  (let [root (srce/root)
        child (srce/extend root {})
        grandchild (srce/extend child {})
        sibling (srce/extend root {})
        scoped (atom [])
        unscoped (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :turn-start nil (fn [e] (swap! scoped conj (:from e))) child)
    (event-bus/on-event :turn-start (fn [e] (swap! unscoped conj (:from e))))
    (event-bus/emit-event! {:type :turn-start :from :child} child)
    (event-bus/emit-event! {:type :turn-start :from :grandchild} grandchild)
    (t/is (= [:child :grandchild] @scoped)
          "the listener hears its own ctx and its descendants")
    (event-bus/emit-event! {:type :turn-start :from :root} root)
    (event-bus/emit-event! {:type :turn-start :from :sibling} sibling)
    (t/is (= [:child :grandchild] @scoped) "the parent and a sibling stay out")
    (event-bus/emit-event! {:type :turn-start :from :app-wide})
    (t/is (= [:child :grandchild :app-wide] @scoped)
          "a process-wide emission is app-level and reaches it")
    (t/is (= [:child :grandchild :root :sibling :app-wide] @unscoped)
          "the unscoped listener keeps hearing every emission")))

(t/deftest test-collect-and-boundary-respect-scope
  (let [root (srce/root)
        child (srce/extend root {})
        sibling (srce/extend root {})]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :resources-discover [:app :extensions "wide"]
                        (fn [_] {:skill-paths ["/wide"]}))
    (event-bus/on-event :resources-discover [:app :extensions "child"]
                        (fn [_] {:skill-paths ["/child"]}) child)
    (t/is (= [[:app :extensions "wide"] [:app :extensions "child"]]
             (mapv first (event-bus/emit-event-collect! {:type :resources-discover} child)))
          "collect takes the process-wide and the in-subtree contribution")
    (t/is (= [[:app :extensions "wide"]]
             (mapv first (event-bus/emit-event-collect! {:type :resources-discover} sibling)))
          "a sibling's contribution is not collected")
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :turn-end (fn [_] {:entries [:wide]}))
    (event-bus/on-event :turn-end nil (fn [_] {:entries [:child]}) child)
    (t/is (= {:entries [:wide]} (event-bus/emit-boundary! {:type :turn-end} sibling)))
    (t/is (= {:entries [:child]} (event-bus/emit-boundary! {:type :turn-end} child))
          "the in-subtree handler runs after the process-wide one")))

(t/deftest test-event-vocabulary-partition
  (t/is (every? #(contains? event-bus/event-types %) event-bus/app-event-types)
        "every app event type is documented in event-types")
  (t/is (not-any? #(contains? event-bus/loop-event-types %) event-bus/app-event-types)
        "app and loop vocabularies do not overlap")
  (t/is (= (set (keys event-bus/event-types))
           (into (set event-bus/app-event-types) (keys event-bus/loop-event-types)))
        "app-event-types and loop-event-types partition event-types"))
