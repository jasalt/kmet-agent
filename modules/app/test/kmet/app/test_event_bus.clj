(ns kmet.app.test-event-bus
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.events :as events]
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

;; ─── Scope: one table, resolved up from the emitting ctx ──────────────────

(t/deftest test-a-listener-hears-its-own-subtree
  ;; The kernel's rule: a listener hears emissions made at its ctx or below it,
  ;; and the host/root registration hears every agent under it — which is what
  ;; the extension ABI relies on.
  (let [root (srce/root)
        child (srce/extend root {})
        grandchild (srce/extend child {})
        sibling (srce/extend root {})
        scoped (atom [])
        at-root (atom [])]
    (events/on! child :turn-start (fn [e] (swap! scoped conj (:from e))))
    (events/on! root :turn-start (fn [e] (swap! at-root conj (:from e))))
    (events/emit! child {:type :turn-start :from :child})
    (events/emit! grandchild {:type :turn-start :from :grandchild})
    (t/is (= [:child :grandchild] @scoped)
          "the listener hears its own ctx and its descendants")
    (t/is (= [:child :grandchild] @at-root)
          "an ancestor's listener hears the same emissions (pi: emit)")
    (events/emit! root {:type :turn-start :from :root})
    (events/emit! sibling {:type :turn-start :from :sibling})
    (t/is (= [:child :grandchild] @scoped) "the parent and a sibling stay out")
    (t/is (= [:child :grandchild :root :sibling] @at-root)
          "the root listener hears every ctx in the tree")))

(t/deftest test-an-emission-with-no-ctx-is-a-caller-error
  (t/is (thrown? Exception
                 (events/emit! nil {:type :turn-start}))))

;; ─── Reporting ────────────────────────────────────────────────────────────

(t/deftest test-the-wire-projection-drops-a-control-object
  ;; `:signal` is an abort atom an in-process event may carry; the wire is EDN,
  ;; so it never crosses.
  (t/is (not (contains? (event-bus/wire-event {:type :turn-end :signal (atom false)})
                        :signal)))
  (t/is (not (contains? (event-bus/wire-event {:type :message-update
                                               :message {:role :assistant}
                                               :signal (atom false)})
                        :signal))))

(t/deftest test-an-unknown-event-type-is-reported
  ;; A typo'd :turn-ended registers anyway (the vocabulary is documented, not a
  ;; gate) and is reported through the ctx's :logger service (F12).
  (let [ctx (srce/root)
        reported (atom [])]
    (srce/provide! ctx :logger (fn [level & args] (swap! reported conj [level (apply str args)])))
    (events/on! ctx :turn-ended identity)
    (t/is (seq @reported) "the unknown type is reported")
    (t/is (str/includes? (str (first @reported)) "turn-ended"))
    (t/is (some #{:turn-ended} (srce/event-names ctx))
          "and the registration still lands")))
