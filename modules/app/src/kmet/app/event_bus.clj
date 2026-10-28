(ns kmet.app.event-bus
  "Agent loop event vocabulary re-exports and the wire projections.

   The bus itself is `kmet.app.events`: one listener table (the kernel's ctx
   tree), one registration (`events/on!`), one dispatcher (`events/emit!`,
   driven by each event's declared mode). This namespace used to hold a second,
   process-level table for the extension ABI; it is gone, so a subscription's
   scope is the ctx it registers on and there is nothing to keep in step.

   What stays here is the vocabulary (`event-types` and friends, re-exported
   from `kmet.app.events`) and the **wire projections** (`wire-event`,
   `wire-error`) — transport, which belongs with the transport that serializes,
   not with the bus."
  (:require [kmet.app.events :as events]))

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
   output, rpc/server): the event as emitted, minus the cumulative assistant
   snapshot :message-update carries — the delta already names a tool call's
   id/name, and the finalized message follows as :message-end, so the snapshot
   only repeats bytes per delta (pi: toJsonEvent) — with error payloads
   stringified (see wire-error). A live control object never crosses: `:signal`
   (an abort atom a boundary or hook may carry in-process) is dropped, because
   the wire is EDN. Every other event passes through.

   Extension-only events (`:public false` in the vocabulary) never reach here:
   `kmet.app.events/on-every` does not subscribe them."
  [event]
  (dissoc (case (:type event)
            :message-update (dissoc event :message)
            :error (update-error-slot event :message)
            :auto-retry-end (update-error-slot event :final-error)
            event)
          :signal))
