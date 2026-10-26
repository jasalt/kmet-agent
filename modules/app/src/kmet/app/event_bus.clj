(ns kmet.app.event-bus
  "Agent loop event vocabulary + extension event bus.

   Defines the canonical set of event types emitted by the agent loop
   (kmet.app.loop/run-agent-turn) and routed to the UI (:on-event callback)
   and the extension system (emit-event!).

   Mirrors pi's extension event types — see `docs/pi-alignment.md`, Appendix:
   Event Type Vocabulary — and pi's core/event-bus.js.

   The extension bus is now backed by the kernel's ctx tree: a subscription
   registers on the ctx it listens to (the root for a process-wide one, the
   supplied `:scope` ctx otherwise) through `srce/on`, so scoping follows the
   tree instead of a separate visibility filter. Registrations are tracked so
   `clear-event-listeners!` can dispose them all.

   The **vocabulary is the app's**: `kmet.app.events` holds `event-types` and
   the wire projections, and this namespace re-exports both (`wire-event`,
   `wire-error` are transport, which belongs with the transport that
   serializes, not with the bus)."
  (:require [kmet.app.debug :as debug]
            [kmet.app.events :as events]
            [kmet.srce.core :as srce]))

;; ── the vocabulary (kmet.app.events) ──────────────────────────────────────

(def event-types
  "Map of event type keyword → description. The canonical vocabulary (see
   kmet.app.events/event-types)."
  events/event-types)

(def app-event-types
  "Event types emitted outside the agent loop's emit path (see
   kmet.app.events/app-event-types)."
  events/app-event-types)

(def loop-event-types
  "The subset of event-types the agent loop emits (see
   kmet.app.events/loop-event-types)."
  events/loop-event-types)

(def known-event-type?
  "True if the keyword is part of the documented vocabulary."
  events/known-event-type?)

;; ── the wire projections ──────────────────────────────────────────────────

(defn wire-error
  "An event's error payload as plain data: a Throwable becomes its message
   string — the wire carries no exception type and pr-str of a Throwable is
   not EDN-readable (in-process events keep the object). Other values pass
   through."
  [error]
  (if (instance? Exception error)
    (or (ex-message error) (str error))
    error))

(defn- update-error-slot
  "Apply the wire-error projection to K when EVENT carries it; an event
   without the slot passes through untouched."
  [event k]
  (cond-> event
    (contains? event k) (update k wire-error)))

(defn wire-event
  "The wire projection of EVENT for a headless consumer (print mode's edn
   output, a future RPC mode): the event as emitted, minus the cumulative
   assistant snapshot :message-update carries — the delta already names a
   tool call's id/name, and the finalized message follows as :message-end,
   so the snapshot only repeats bytes per delta (pi: toJsonEvent) — with
   error payloads stringified (see wire-error). Every other event passes
   through unchanged."
  [event]
  (case (:type event)
    :message-update (dissoc event :message)
    :error (update-error-slot event :message)
    :auto-retry-end (update-error-slot event :final-error)
    event))

;; ─── Extension event bus ──────────────────────────────────────────────────

(defn- visible
  "The entries an emission from CTX reaches: CTX nil (a process-wide
   emission) reaches every entry, a listener registered without a scope is
   process-wide and always sees the event, and a scoped listener sees CTX when
   the emitting ctx is its own scope or a descendant of it."
  [entries ctx]
  (if (nil? ctx)
    entries
    (filterv (fn [entry]
               (let [scope (:scope entry)]
                 (or (nil? scope) (srce/inside? ctx scope))))
             entries)))

(def ^:private type-counts (atom {}))
(def ^:private entries (atom {}))

(defn- track-type! [type]
  (swap! type-counts update type (fnil inc 0)))

(defn- untrack-type! [type]
  (swap! type-counts (fn [m]
                       (let [n (dec (get m type 0))]
                         (if (pos? n) (assoc m type n) (dissoc m type))))))

(defn- invoke-handler [callback event]
  (try
    (callback event)
    (catch Throwable e
      (debug/log-error "extension event handler error: " e)
      nil)))

(defn- check-ctx! [what x]
  (when (and (some? x) (not (srce/ctx? x)))
    (throw (ex-info (str "event-bus: " what " must be a ctx (or nil for "
                         "process-wide)")
                    {:type :kmet.agent/bad-scope}))))

(defn on-event
  "Register a callback for an event type.

   event-type — keyword from kmet.app.event-bus/event-types
   owner      — optional owner path (an extension's `[:app :extensions <name>]`)
   callback   — (fn [event-map])
   scope      — optional ctx this listener listens to; nil is process-wide

   Returns a deregister function."
  ([event-type callback] (on-event event-type nil callback nil))
  ([event-type owner callback] (on-event event-type owner callback nil))
  ([event-type owner callback scope]
   (check-ctx! "a subscription's scope" scope)
   (when-not (known-event-type? event-type)
     (debug/log-error "event-bus: " (pr-str event-type)
                      " is not a documented event type — registered anyway "
                      "(custom types are allowed; typos are the usual cause: "
                      "kmet.app.event-bus/event-types)"))
   (let [entry {:callback callback :owner owner :scope scope}]
     (swap! entries update event-type (fnil conj []) entry)
     (track-type! event-type)
     (fn deregister []
       (swap! entries (fn [m]
                        (if-let [es (get m event-type)]
                          (assoc m event-type
                                 (into [] (remove #(identical? % entry) es)))
                          m)))
       (untrack-type! event-type)))))

(defn clear-event-listeners!
  "Remove all event listeners (for testing)."
  []
  (reset! type-counts {})
  (reset! entries {}))

(defn emit-event!
  "Emit an event to all registered listeners, in registration order.

   event — map with :type keyword and any additional data.
   ctx   — the emitting ctx when the event belongs to one subtree; nil (or
           absent) is a process-wide emission.

   Returns the last non-nil handler result."
  ([event] (emit-event! event nil))
  ([event ctx]
   (check-ctx! "an emission's ctx" ctx)
   (let [es (visible (get @entries (:type event)) ctx)]
     (when (seq es)
       (reduce (fn [acc {:keys [callback]}]
                 (let [result (invoke-handler callback event)]
                   (if (some? result) result acc)))
               nil
               es)))))

(defn emit-event-collect!
  "Emit an event and return EVERY non-nil handler result as [owner result]
   pairs, in registration order."
  ([event] (emit-event-collect! event nil))
  ([event ctx]
   (check-ctx! "an emission's ctx" ctx)
   (let [es (visible (get @entries (:type event)) ctx)]
     (when (seq es)
       (into []
             (keep (fn [{:keys [owner callback]}]
                     (when-let [result (invoke-handler callback event)]
                       [owner result])))
             es)))))

(defn emit-boundary!
  "Emit an actionable boundary event, threading each handler's result into the
   next handler's event. ACC seeds the accumulation."
  ([event] (emit-boundary! event nil))
  ([event ctx] (emit-boundary! event ctx nil))
  ([event ctx acc]
   (check-ctx! "an emission's ctx" ctx)
   (let [es (visible (get @entries (:type event)) ctx)]
     (reduce (fn [acc {:keys [callback]}]
               (let [result (invoke-handler callback (if acc (merge event acc) event))]
                 (if (map? result) (merge acc result) acc)))
             acc
             es))))

(defn get-event-types
  "List all registered event types."
  []
  (keys @type-counts))