# Event bus unification

Temporary note. The app's process-level listener table (`kmet.app.event-bus`)
and the composition's ctx-scoped table (the kernel, through `kmet.app.events`)
are two tables fed by one loop. This file records what is left and why the
remaining step is not a mechanical re-point.

## Current shape

- `kmet.app.events` owns the **vocabulary** (`event-types` and friends,
  declared as `:events` on its plugin map) and `on!`/`once!`, which register
  through `srce/on` on the registrant's ctx. The `:events` plugin row provides
  the dispatch modes bound to the app scope.
- `kmet.app.event-bus` owns the **app's process-level table**: a plain `def`
  atom keyed by event type, where the extension ABI registers (`ext/on-event`
  → `event-bus/on-event`), plus the wire projections (`wire-event`,
  `wire-error`).
- `kmet.app.loop.state/emit` and `kmet.app.loop/emit-boundary` feed both: the
  kernel from the run's own ctx, the app table via `emit-event!` /
  `emit-boundary!`. They hold different listeners, so one event reaches each
  listener once.

## Landed

- `kmet.app.event-bus` no longer owns a `defonce` listener atom; it is a plain
  `def` atom table (no kernel-lifetime hack, no fallback root), and the
  process-state guard's `listeners` cell is gone (22 → 21 cells).
- The conformance test no longer asserts "the two dispatch paths agree": it
  asserts that every event a live turn emits goes down the path its own
  vocabulary entry names (`test-every-emitted-event-matches-its-declared-mode`).
- Stale prose removed: `kmet.app.loop`, `kmet.app.loop.state`,
  `kmet.app.events`, `kmet.app.test-events`, `srce.md` and
  `docs/architecture.md` no longer describe a kmet1/kmet2 split that no longer
  exists.

## What blocks full retirement

Retiring the app table means registering extension subscriptions on the app
root ctx and dispatching only through the kernel. Two things have to be decided
first, and neither is a rewrite of a call site:

1. **Process-wide emission.** The kernel's `listeners` walks *up* from the
   emitting ctx, so a listener registered on a child ctx does not hear an
   emission made at (or above) the root. The app table's rule is the opposite
   for unscoped listeners: a listener registered without a scope hears every
   emission, including a process-wide one. `test-event-bus` pins both halves of
   the old rule (`test-a-scoped-listener-hears-its-own-subtree`,
   `test-collect-and-boundary-respect-scope`). Adopting the kernel rule is a
   semantic change to the ABI, so it needs a decision, not a refactor.
2. **Where an unscoped registration lands.** In production the app root is
   published (`kmet.app.world/load!` → `kmet.app.root/publish!`) and is an
   ancestor of every agent ctx, so "register at the app root" is well defined.
   In a bare process (most tests, and `kmet.test-utils/with-extension-host`,
   which binds a ctx but does not publish it) there is no published root, so a
   fallback root would be a *different tree* from the one a test's emissions
   walk — silently losing listeners. A retirement has to either publish the
   root in the fixtures or take the registration ctx from the bound host
   services (`(:ctx (services))`), which `kmet.app.event-bus` cannot require
   directly (the host requires the bus).

## Slice order, if the decision is made

1. Resolve the registration ctx: from the bound host services, or by having
   the fixtures publish.
2. Move `on-event` onto `srce/on` at that ctx, keeping `invoke-handler` as the
   registered wrapper so `kmet.app.debug/log-error` remains the error path.
3. Dispatch the three emitters through `srce/collect-last!`,
   `srce/collect-all!` and a seeded boundary reduce over `srce/listeners`.
4. Decide (1) above and update the two scope tests with the chosen rule.
5. Drop the app table, leaving the vocabulary and the wire projections in
   `kmet.app.events`.
