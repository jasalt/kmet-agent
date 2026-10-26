# Event bus unification

Temporary note. The two delivery paths — the kernel's `:events` service and
`kmet.app.event-bus`'s process-level listener table — are slated to converge
by moving the extension ABI onto the kernel. This file records the plan and
the slice order; it is not a committed design doc.

## Why they are split

- `:events` service (`kmet.app.events` plugin row): ctx-tree scoped, the loop
  emits here from a run's own ctx, refresh-aware.
- `kmet.app.event-bus` process table: extension ABI registration point; lives
  from extension load to unload; supports owner paths and `:scope`.

The loop feeds both and they hold different listeners, so nothing hears an
event twice. `emit-boundary!` threads one decision through both. The split is
deliberate (see `kmet.app.event-bus`'s docstring): convergence is "the
extension ABI moving onto the kernel," retirement's work, not a boundary
drain's.

## Target end state

- `kmet.app.event-bus` owns no `defonce` listener table.
- Extension subscriptions register on a ctx (root for process-wide, the
  supplied `:scope` ctx otherwise) through `srce/on` / `srce/once`, carrying
  the owner as `:data`.
- Emit paths call `srce/emit!` / `collect-last!` / `collect-all!` /
  `boundary!` on the emitting ctx.
- `clear-event-listeners!` is replaced by disposing the root fiber's
  registrations (or a tracked disposer list).

## Slice order

1. Registration storage (`event_bus.clj`): introduce `registration-ctx`,
   reimplement `on-event` / `emit-event!` / `emit-event-collect!` /
   `emit-boundary!` on top of `srce/on` and the kernel dispatch fns. Keep the
   public API stable. Remove the `listeners` atom.
2. Emit paths: kernel calls use the same ctx the callers already pass, so
   scoping is preserved.
3. Extension host wiring: `kmet.app.extensions.core` `:on-event` already passes
   owner + handler; only the underlying call changes. `:emit-event!` becomes
   the new emitter.
4. Loop and session callers: signatures stay; kernel call uses the same ctx.
5. Cleanup and guards: drop the `listeners` entry from
   `kmet.app.test-process-state`'s `process-cells`; replace
   `clear-event-listeners!` with tracked disposers; update `test_event_bus.clj`.
6. Conformance: `test_event_conformance.clj` asserts one path instead of two.
   Vocabulary (`event-types`, `wire-event`, `wire-error`) stays in
   `kmet.app.events`.

## Status

Cleanup landed: `kmet.app.event-bus` is now a plain `def`-atom table (no
`defonce`, no kernel root-fiber lifetime hack, no fallback root). The
process-state guard's `listeners` cell was removed and its count updated
(22 → 21). Public API, scoping, and throw handling are unchanged;
`kmet.app.test-event-bus` (26 tests) and `kmet.app.test-process-state` pass,
and the app boots.

## Next slices

2. Emit paths already use the tracked entries; consider moving scoped
   visibility onto kernel ancestry once process-wide emission semantics are
   revisited.
3. Extension host wiring: no change needed this slice; `:on-event` already
   passes owner + handler.
4. Conformance test still asserts two paths; collapse to one when the process
   table is fully retired.