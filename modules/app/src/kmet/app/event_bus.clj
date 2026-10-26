(ns kmet.app.event-bus
  "Agent loop event vocabulary + extension event bus.

   Defines the canonical set of event types emitted by the agent loop
   (kmet.app.loop/run-agent-turn) and routed to the UI (:on-event callback)
   and the extension system (emit-event!).

   Mirrors pi's extension event types — see `docs/pi-alignment.md`, Appendix:
   Event Type Vocabulary — and pi's core/event-bus.js.

   The extension bus itself is a process-level registry: a subscription lives
   from an extension's load to its unload, in registration order, and no
   engine owns, copies or clears it. A subscription may carry a scope — the
   ctx it listens to — so a listener in a child ctx hears its own subtree's
   events and a sibling's listeners do not (new-app.md, landing 4c-ii-b).

   The **vocabulary is the app's**: `kmet.app.events` holds `event-types` and
   the wire projections, and this namespace re-exports both (`wire-event`,
   `wire-error` are transport, which belongs with the transport that
   serializes, not with the bus). The subscriptions are this table's: the
   extension ABI and the front ends register here, with an owner for unload and
   an optional scope for the ctx tree.

   The kernel's `:events` service is the other delivery path, on the ctx tree,
   and the loop feeds both (new-app.md, 7b-i): its events go to the kernel from
   the run's own ctx and to this table. They hold different listeners, so
   nothing hears an event twice; a boundary threads one decision through both
   (`emit-boundary!`'s seed). Converging the two is the extension ABI moving
   onto the kernel — retirement's work, not a boundary drain's — and until then
   this table is the one the ABI registers in."
  (:require [kmet.app.debug :as debug]
            [kmet.app.events :as events]
            [kmet.srce.core :as srce]))

;; ── the vocabulary (kmet.app.events) ──────────────────────────────────────
;; Re-exports, not copies: the names, their documentation and the wire
;; projections have one home, and the contract test over both delivery paths
;; (kmet.app.events and this file) asserts the same behaviors on each.

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
;; Transport, not the bus: they belong with the consumer that serializes
;; (kmet1's print and RPC modes here; kmet.mode.print/kmet.mode.rpc once the
;; app's transports land — new-app.md, landing 5). Landing 2 lifted the vocabulary
;; and the modes, not these.

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
;; The process listener registry. Extensions register callbacks with
;; on-event; the loop and the front ends route events through emit-event!.
;; One structure, process-level: extensions subscribe at load, before any
;; engine exists, and their lifetime is load → unload, so an engine neither
;; adopts nor wipes it.

(defonce ^:private listeners
  ;; {event-type [{:callback f :owner path :scope ctx} …]} — one vector per
  ;; type, so iteration is registration order. A deregister removes its own
  ;; entry by identity; an entry already gone is a no-op (a reload deregisters
  ;; what a previous load installed).
  (atom {}))

(defn- register-listener!
  "Add ENTRY to EVENT-TYPE's registrations and return its deregister fn."
  [event-type entry]
  (swap! listeners update event-type (fnil conj []) entry)
  (fn deregister []
    (swap! listeners
           (fn [ls]
             (if-let [entries (get ls event-type)]
               (assoc ls event-type (into [] (remove #(identical? % entry)) entries))
               ls)))))

(defn- check-ctx!
  "Throw unless X is a ctx or nil. A map that carries `:parent` *looks* like a
   ctx to the subtree test, so nothing else would catch the mix-up, and a
   listener scoped to the wrong tree silently matches the wrong subtree."
  [what x]
  (when (and (some? x) (not (srce/ctx? x)))
    (throw (ex-info (str "event-bus: " what " must be a ctx (or nil for "
                         "process-wide)")
                    {:type :kmet.agent/bad-scope}))))

(defn- visible
  "The entries an emission from CTX reaches: CTX nil (a process-wide
   emission) reaches every entry, a listener registered without a scope is
   process-wide and always sees the event, and a scoped listener sees CTX when
   the emitting ctx is its own scope or a descendant of it (new-app.md,
   landing 4c-ii-b)."
  [entries ctx]
  (if (nil? ctx)
    entries
    (filterv (fn [entry]
               (let [scope (:scope entry)]
                 (or (nil? scope) (srce/inside? ctx scope))))
             entries)))

(defn on-event
  "Register a callback for an event type.
   event-type — keyword from kmet.app.event-bus/event-types
                (e.g. :agent-start, :turn-start, :message-update,
                 :tool-execution-start, :user-bash, :status)
   owner      — optional: the path the subscription belongs to (an
                extension's `[:app :extensions <name>]`). The collect
                emitter reports it beside the handler's result, so a
                contribution can be applied under the owner that made it
                (resource discovery; the owner is the registry address).
   callback   — (fn [event-map])
   scope      — optional: the ctx this listener listens to; it sees events
                emitted from that ctx and its descendants. nil (the
                default) is process-wide, which is what an extension wants;
                a run passes its own ctx to hear only its subtree.
   Returns a deregister function. A type outside the vocabulary is reported
   and registered anyway: the vocabulary is documented, not a gate."
  ([event-type callback] (on-event event-type nil callback nil))
  ([event-type owner callback] (on-event event-type owner callback nil))
  ([event-type owner callback scope]
   (check-ctx! "a subscription's scope" scope)
   (when-not (known-event-type? event-type)
     (debug/log-error "event-bus: " (pr-str event-type)
                      " is not a documented event type — registered anyway "
                      "(custom types are allowed; typos are the usual cause: "
                      "kmet.app.event-bus/event-types)"))
   (register-listener! event-type {:callback callback :owner owner :scope scope})))

(defn clear-event-listeners!
  "Remove all event listeners (for testing)."
  []
  (reset! listeners {}))

(defn- invoke-handler
  "Call CB with EVENT, returning nil and reporting a throw — a broken
   extension never breaks the bus (pi: the runner reports the handler error
   and carries on). Throwable, not Exception: an Error from one handler must
   not stop the bus or skip the handlers after it."
  [cb event]
  (try
    (cb event)
    (catch Throwable e
      (debug/log-error "extension event handler error: " e)
      nil)))

(defn emit-event!
  "Emit an event to all registered listeners, in registration order.
   event — map with :type keyword and any additional data.
   ctx   — the emitting ctx when the event belongs to one subtree (a child
           loop passes its ctx); nil (or absent) is a process-wide emission.
   Returns the last non-nil handler result (pi: runner.emit — the tree
   navigation reads :session-before-tree results: :cancel, :summary, ...)."
  ([event] (emit-event! event nil))
  ([event ctx]
   (check-ctx! "an emission's ctx" ctx)
   (let [entries (visible (get @listeners (:type event)) ctx)]
     (when (seq entries)
       (reduce (fn [acc {:keys [callback]}]
                 (let [result (invoke-handler callback event)]
                   (if (some? result) result acc)))
               nil
               entries)))))

(defn emit-event-collect!
  "Emit an event and return EVERY non-nil handler result as [owner result]
   pairs, in registration order (pi: emitResourcesDiscover collects each
   handler's contribution — unlike emit-event!, results are not reduced to
   the last non-nil). OWNER is
   what `on-event` was registered with, nil for a host registration, so a
   callers can apply a contribution under the owner that made it.
   CTX scopes the emission like `emit-event!`.
   Throwing handlers are skipped and reported, same as emit-event!."
  ([event] (emit-event-collect! event nil))
  ([event ctx]
   (check-ctx! "an emission's ctx" ctx)
   (let [entries (visible (get @listeners (:type event)) ctx)]
     (when (seq entries)
       (into []
             (keep (fn [{:keys [owner callback]}]
                     (when-let [result (invoke-handler callback event)]
                       [owner result])))
             entries)))))

(defn emit-boundary!
  "Emit an actionable boundary event (a :turn-end or :agent-before-settle
   boundary), threading each handler's result into the next handler's event
   (pi: runner.emitBoundary): a handler receives the event merged with the
   accumulated result so far and returns a map ({:entries [...] :continue
   bool}) whose keys replace the accumulated ones; nil (or a non-map) keeps
   them. Handlers run in registration order. CTX scopes the emission like
   `emit-event!`. Returns the accumulated map — nil when no handler returned
   one.

   ACC, the third arity, is a decision another layer already reached; it seeds
   the accumulation, so a boundary this table hears second continues one chain
   rather than starting a second (the loop hands over the kernel's, so a front
   end's :continue is visible to every extension handler that follows it —
   new-app.md, 7b-i).
   Throwing handlers are skipped and reported, same as emit-event!."
  ([event] (emit-boundary! event nil))
  ([event ctx] (emit-boundary! event ctx nil))
  ([event ctx acc]
   (check-ctx! "an emission's ctx" ctx)
   (let [entries (visible (get @listeners (:type event)) ctx)]
     (reduce (fn [acc {:keys [callback]}]
               (let [result (invoke-handler callback (if acc (merge event acc) event))]
                 (if (map? result) (merge acc result) acc)))
             acc
             entries))))

(defn get-event-types
  "List all registered event types."
  []
  (keys @listeners))
