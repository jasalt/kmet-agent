# srce — a light Cordis kernel in Clojure

Implementation note for the `kmet.srce` module, beside its code. kmet2's
application architecture — services, agents, sessions, profiles, landings — is
in [`new-app.md`](../../../../../new-app.md); this file is the kernel only: its
ctx model, the lifecycle, the plugin contract, the contracts the tests pin, and
what was deliberately left out.

The name is **`srce`** — heart (South Slavic). Cordis is Latin for the same
thing (`cor`, `cordis`), which is what the kernel is to the system: the one
thing every other part is wired through. It stays clear of `kmet.kernel`,
`--list-kernels` and prose about OS kernels.

## What "light" means

Port the **concepts** that make Cordis a meta-framework, not the vendored
member list. A mechanism is in the kernel only if all three hold:

1. [`new-app.md`](../../../../../new-app.md) needs it (a service, an agent, a profile, a UI
   provider),
2. it is what separates a kernel from a service locator, and
3. it cannot be added later without changing every caller.

Those three conditions are Cordis's own **five ideas** — the vendored tree's
own introduction ([Cordis Primer](https://deepseek-harness.github.io/deepseek-harness/en/reference/cordis-primer),
`docs/cordis-primer.md` in the harness) — minus what kmet already owns. That
mapping is the light kernel's whole justification:

| # | Cordis idea (primer) | the light kernel | § |
|---|---|---|---|
| 1 | a plugin is an object that implements `Service` — a function with optional `inject` and `apply(ctx)` | a **validated map** with `:inject`/`:apply`; the Service-subclass half is cut (§10) | §6 |
| 2 | a context is a repository of services; a service claims a stable `ctx.x` key, others find it by key instead of importing an implementation | `provide!`/`get` by keyword over the ctx tree, realms via `isolate` | §2–§3 |
| 3 | declare dependencies via `inject`; a plugin waits until those services exist, so load order is requirements, not boot sequencing | required + `:optional` inject, the refresh pass — **boot order stops mattering** | §3 |
| 4 | typed events; dispatch as `emit`/`waterfall`/`parallel`/`serial`/`bail` | **one listener table** plus a resolved-list seam: `emit` in the kernel, the other four modes in kmet1's bus; the declaration half is `event-bus/event-types` + the generated catalog (which lives above the kernel, so the three `:srce/*` names are documented, not catalogued) | §5 |
| 5 | registrations are reversible **effects**, so reload and teardown unwind predictably | the fiber's effect list, LIFO, owning the `provide!` and the listener | §4 |

Idea 3 is the load-bearing one for kmet2: extensions, profile rows and UI
providers mount in arbitrary order (a preset bundle, an extension directory, a
`--mode` flag), and "the plugin waits for its services" is what makes that
safe. Idea 5 is what makes the wait reversible: every registration a fiber made
comes back when its provider goes away.

Everything else is **deferred, not dropped**: §10 records each mechanism
with the Cordis member it came from and the seam that makes it additive, so
re-adding one is a lookup rather than a re-read of `vendor/cordis/src/*.ts`.
The Parity matrix survives as the appendix, one row per Cordis member that has a
decision, tagged `keep` / `deferred` / `never`.

| | full port (retired) | light |
|---|---|---|
| namespaces | 10 (`ctx` `reflect` `service` `fiber` `registry` `event` `logger` `awaitable` `utils` `config`) | 4 (`ctx` `fiber` `event` + the public `srce`) |
| kernel lines | ~4k | ~930 |
| conformance | 74 tests generated from 82 matrix rows | 41 contract tests (164 assertions) + the 3 guard tests (§12) |
| definition of done | Cordis parity | the contracts in §3–§8 |
| async | awaitables, `inertia`, epochs, drain owner | synchronous kernel, thread rule (§8) |

The cut is ordered so the light surface is a **subset** of the full one:
`isolate` labels, `intercept`, the logger service, internal events and config
transforms all attach to an existing seam. The one exception is §13's
synchronous `apply`.

## 1. The kernel

```
modules/srce/
├── deps.edn           — rank 1: third-party code today; add the kmet.libs.* /
│                        kmet.loader.* :local/root edges when a namespace needs one
├── test/kmet/srce/    — the contract tests (§12)
└── src/kmet/
    ├── srce.clj       — the public API (below); the one require app code needs
    └── srce/
        ├── srce.md    — this file
        ├── ctx.clj    — the ctx value, the tree, the service store, isolate, the effect list
        ├── fiber.clj  — mount/unmount, states, the refresh pass, plugin runtimes
        └── event.clj  — on/once/emit!, the listener table, the resolved list
```

`kmet.srce` is rank 1 — it may require rank-0 code (`kmet.libs.*`, and
`kmet.loader.*` when it resolves sources), nothing at its own rank or above;
the subnamespaces are internal and nothing above the kernel requires them.
The module's `modules.edn` row — `:name "srce"`, the source/test paths,
`:rank 1` — is also its classpath and embed entry: the manifest is the classpath
order *and* the rank source `test/kmet/test_module_deps.clj` reads, and the
three extension `bb.edn`s list the module sources too.
Consumers require exactly one namespace — the kernel's surface is one contract,
not a set of modules whose callers each pick their own:

```clojure
(ns kmet.app.session
  (:require [kmet.srce :as srce]))

(srce/root)                              ; a root ctx — boot holds one, tests build their own
(srce/root {:base-url "…"})              ; same, with ctx keys merged in (nothing is read by the kernel)
(srce/extend ctx {:name :agent/7f3a})    ; a child ctx with metadata
(srce/isolate ctx :tools :llm)           ; a child ctx that is a realm for those names
(srce/inside? child ctx)                 ; is child in ctx's subtree? (inclusive)
(srce/get ctx :tools)                    ; the provider value, or nil
(srce/provider ctx :tools)               ; {:name :fiber :value :check}, or nil
(srce/services ctx)                      ; {name {:name :fiber :value :check}}, realm-aware
(srce/children ctx)                      ; this ctx's child ctxs, creation order
(srce/provide! ctx :tools table)         ; → disposer; a second visible provider throws
(srce/on ctx :srce/status f)             ; → disposer
(srce/once ctx :srce/status f)
(srce/emit! ctx :my/event ev {:filter f}); sync, filtered, results ignored
(srce/listeners ctx :my/event {:filter f}) ; [{:ctx c :f f} …] resolved up the chain, dispatch order
(srce/plugin-source src resolver)        ; a var as-is, or the resolver's value for a keyword
(srce/plugin! ctx #'web-search {:max-results 5})   ; a var, a keyword, or a plugin map → fiber
(srce/inject! ctx :ui/terminal {:ui :optional} h) ; plugin! sugar; the name is required
(srce/normalize-inject deps)             ; → {name nil|:optional}; throws :srce/bad-inject
(srce/runtime ctx web-search)            ; the runtime, or nil
(srce/runtime? ctx web-search)           ; is a runtime mounted for this source?
(srce/runtimes ctx)                      ; [runtime …], mount order
(srce/dispose! fiber)                    ; effects LIFO; sync → nil
(srce/restart! fiber)                    ; dispose then mount again → a new fiber
(srce/dispose-plugin! web-search)        ; every fiber of one source
(srce/dispose-subtree! ctx)              ; every fiber under ctx, children first
(srce/effect ctx f)                      ; → disposer (single-shot, sync)
(srce/effects fiber)                     ; the effect labels, creation order
(srce/log! ctx :info "…")                ; through the :logger service; silent when absent
(srce/settle! root)                      ; run any pending change to a fixpoint
```

`defplugin` and `ctx?` round it out. There is no `defservice`, `accessor`,
`mixin`, `set-service!`, `notify!`, `intercept`, `update!`, `fiber-await` or
`drain-await` — see §10.

`root` builds a root ctx; it is a value, not a process singleton. Boot holds one
and passes it down, a test builds its own, and the root-owned state (runtimes,
mounted fibers, the uid counter, the pass flag, the change set) lives in one
shared `:kernel` map that every child inherits — the atoms are references, so
two roots never see each other, no kernel state is process-global, and no ctx
needs a pointer back to the root. An optional map is merged into the ctx as-is,
which is how boot sets `:base-url` and `:resolver` (§2); the kernel reads the
resolver only when a plugin source is a keyword (§6).

Two public names shadow `clojure.core` — `get` and `extend` — so the kernel's
own namespaces carry `(:refer-clojure :exclude [get extend])` rather than
spelling `clojure.core/get` at every call site.

## 2. The ctx value

A ctx is a value: a map with fixed keys, no proxy, no service methods on it.
`(srce/ctx? x)` is a marker key, replacing Cordis's `Context.is` brand.

| key | meaning |
|---|---|
| `:kernel` | the shared root state: `{:runtimes :mounted :counter :pass :changes}` — one map, inherited by every child, read through `(:kernel ctx)` |
| `:parent` `:children` | the tree (`:children` is an atom); a child comes from `extend`/`isolate` |
| `:name` | this ctx's own name — a plugin mount names its ctx from the plugin's `:name`, and the unit listing derives a path from the chain |
| `:fiber` | the **atom** holding the fiber that owns this ctx (the root fiber has `:uid 0`); `extend`/`isolate` inherit the parent's, a plugin mount installs its own |
| `:isolate` | the set of names this ctx declares a realm for (not inherited) |
| `:store` | this ctx's own `{name → {:name :fiber :value :check}}` atom |
| `:listeners` | this ctx's `{event-name → [listener …]}` atom |
| `:base-url` | resolution base for a source resolver; the kernel carries it and never reads it — what it means, and whether the nearest non-nil value wins or boot passes one attribute, is the extension host's call; nil until `root`/`extend` supplies it |
| `:resolver` | inherited key: resolves a keyword plugin source (§6). The kernel only calls it, and only when the source is a keyword |
| `:runtimes` `:mounted` `:counter` `:pass` `:changes` | **inside `:kernel`**: plugin runtimes, mounted fibers in mount order, the uid allocator, the optimistic pass flag and its change set |

**Three accessors answer the whole-tree questions**: `(children ctx)`,
`(services ctx)` — the realm-aware merge of what the ctx can see, which is what
an inspection view needs — and `(listeners ctx name opts)` — the resolved
listener list, which is what the app's other four dispatch modes walk (§5).
`services` is the accessor a caller would otherwise re-derive per name with
`provider`; `listeners` is the accessor that keeps the app's dispatchers from
needing a second registry.

**The kernel owns its tree.** `kmet.srce.*` must not require
`kmet.libs.scope`: the kernel needs a parent/child walk and a per-fiber effect
list, not a keyed multi-owner store with four views. `kmet.libs.scope` is a
*cell* dependency — it stays until the last registry moves (§9). A guard test
enforces the require ban.

`extend` and `isolate` copy: a parent is never mutated (beyond the `:children`
entry), extra keys are merged (the kernel reads `:name` directly and
`:base-url`/`:resolver` as inherited keys — nearest non-nil up the chain, no
realm stop), and `isolate` names become this ctx's `:isolate` set, which is the
names *declared here*: a child does not inherit the set, so the lookup walk
passes through the realm and stops at the ctx that declared it. A second
`isolate` for the same name is a deeper boundary, not a redefinition: the inner
subtree sees only its own provider for that name and the outer subtree is
unaffected.

**`get` resolves up the tree, stopping at a realm.** Walk from the reading ctx:

- this ctx's store has the name → that provider;
- this ctx isolates the name → nil (the realm boundary blocks the outside);
- else the parent.

A provider whose fiber is not `:active` reads as `nil` — the light kernel has
one `get`, no `strict` flag. There is no `fiber.store` snapshot: inspection is
`(srce/provider ctx :name)`, which says who provides it now.

## 3. Services and refresh

Cordis's reflect layer, minus everything the light kernel doesn't need.

- **One provider per name per realm path.** The store is per ctx (§2), so the
  guard is *visibility*, not a global name table: `provide!` throws
  `{:type :srce/duplicate-service}` when the name is already held on the way up —
  this ctx's store, or an ancestor's before any realm boundary. The check is on
  the entry, not on `get`'s `:active` filter, so a pending provider still
  occupies its name. A nested `extend` therefore cannot shadow an outer
  provider — that is the Cordis rule — while a second instance needs a second
  realm, which is `isolate`, which is why `isolate` is in the light set and
  labels are not. Two *siblings* under one realm may each provide the name: the
  nearest visible provider wins per subtree, the listing (which walks the ctxs)
  shows both, and `services` from above shows neither. A root-level `{realm-key name → impl}` store is Cordis's shape and would
  make that a throw instead; the per-ctx atom is chosen because the realm then
  *is* the ctx — no symbol table, no labels (§10).
- **`provide!` is an effect.** It registers into the current ctx's store and
  pushes a disposer onto the fiber's effect list; disposer order is the fiber's
  order. A provider's disposer deletes its entry and records the name as a
  change, so the outer `settle!` remounts the dependents.
- **`check` is a predicate on the value.** `(provide! ctx :ui v {:check ready?})`
  — false or a throw reads as "unavailable" for `get` and for inject. It is
  evaluated on every read and at every pass, and a pass always re-evaluates
  `:pending` fibers (even with an empty change set), so a check that turns true
  is noticed at the next `settle!`. A check that turns **false** on an active
  provider is not a revocation: dispose or re-provide to revoke.
- **`provider` is a read**, not state: it returns the
  `{:name :fiber :value :check}` record, including one whose fiber is not yet
  active.

### inject

```clojure
[:tools]                          ; required
{:tools nil :llm nil}             ; required
{:ui :optional}                   ; optional: never holds the fiber pending
```

The object form's values are **reserved**. In Cordis a non-nil value *is*
intercept config merged into the service; the light kernel cut `intercept`, so
anything but `nil` or `:optional` throws `ex-info` — `{:type :srce/bad-inject}`
plus `:problems` — rather than silently configuring. That keeps the door
open: intercept comes back as the meaning of those values, not as a new
spelling. One shape, thrown — `normalize-inject` is not total, and
`plugin-schema` (§6) lets the throw surface as the plugin's own
`:srce/bad-plugin`.

An optional dependency participates in the **provider set** like a required
one: absent contributes `:srce/absent`, present contributes the provider fiber's
`uid`. The set is the epoch, and the epoch is what makes `{:ui :optional}`
"upgrade when a richer UI attaches" ([`new-app.md`](../../../../../new-app.md), "Pluggable
UI").

### refresh: a fixpoint pass, not an epoch race

The full port needed the epoch to *drop* a stale `_reload` that lost a race on
another thread. The light kernel has one owner thread and one pass flag (§8), so
a provider change runs one synchronous pass that a second thread cannot split:

1. a change (provide, provider dispose, provider state crossing `:active`)
   appends its names to the pass's change set;
2. for each mounted fiber that injects one of those names, compute its provider
   set; unchanged → skip, unsatisfiable → `:pending`, changed → a *reload*
   (unload and re-activate the same fiber, so the uid stays);
3. a fiber is remounted **at most once per round** (a second provider change
   in a later round reloads it again — that is the fixpoint); the pass repeats
   until the change set is empty, with a budget of 8 rounds after which it warns
   (`:srce/churn`) and clears the set. Clearing is safe because round 1
   recomputes provider sets from the store, not incrementally: nothing is
   forgotten, only the remaining remounts wait for the next change to one of
   the names. A `provide!` issued from a plugin's own `apply` joins the pass's
   change set and is processed after that `apply` returns — a fiber never
   remounts itself. That is true unconditionally because **the pass never runs
   reentrantly**: `plugin!`, `restart!`, `dispose!`, `dispose-plugin!` and
   `dispose-subtree!` hold the pass flag for their whole body and drain once at
   the end, so a mount's own `provide!` only records. A fiber is never a
   candidate for its own pass: it is either not yet mounted or no longer
   `:active`. Remounting replaces the fiber's `:mounted` entry in place, so the
   listing's "fibers in mount order" is stable; only a fresh `plugin!` appends.

That replaces `notify!`, the epoch and the drain queue with one loop. The
observable rule `new-app.md` depends on survives: a dependent sees the new
provider, and never a half-provided one.

**The pass owns the pass.** §8 says mutations run on the owner thread, but
kmet1 does not hold that rule today: the TUI input handler and tool threads
call `scope/register!` while the loop runs, and the store's atom makes each call
safe per operation. A fixpoint pass is not per-operation safe — two threads
entering one would remount a fiber twice. So the kernel's single concurrency
primitive is an optimistic pass flag:

```clojure
(defn settle!
  [ctx]
  (let [{:keys [pass changes]} (:kernel ctx)]
    (loop []
      (when (compare-and-set! pass nil :running)
        (try (run-pass! ctx)
             (finally (reset! pass nil))))
      ;; a change recorded while we owned the flag, or between rounds, is ours
      (when (and (seq @changes) (nil? @pass))
        (recur)))))
```

`provide!`, `dispose!` and the rest record into `:changes` and then call
`settle!`. A caller that loses the flag returns immediately — its change is in
the set, and the owner's last round or its retry picks it up. The trailing
`when` closes the window the six-line version left open: a change recorded
between the last round's snapshot and the flag reset would otherwise sit until
some *other* `settle!`, so `settle!` returning could not mean "nothing pending"
and `new-app.md`'s "the boundary is also the drain" would be false. The
invariant now is: **when `settle!` returns and no other thread owns the pass,
`:changes` is empty.** A change that lands while another thread owns the flag
is that owner's, which is the "late, never corrupting" rule below.
`compare-and-set!` on an atom is available on both hosts (checked on bb and
Jolt), so the primitive is portable, and it is still eight lines rather than a
queue and an awaitable.

## 4. Fibers and effects

A fiber is one mount of one plugin source. States: **`:pending`** (waiting for
inject), **`:active`**, **`:failed`** (a throwing `apply` or config), and
`:disposed` (the record stays for inspection; the uid stays too — nothing
depends on uid reuse).

The record is an **atom holding a plain map**, and `@fiber` is the inspection
surface (`new-app.md`, "Inspection") — one `swap!` per transition, so a reader
never sees a half-updated record. There is no `fiber.store`; the map's own keys
are the accessor:

| key | meaning |
|---|---|
| `:uid` `:name` `:config` `:state` `:parent` `:runtime` | identity, raw config, lifecycle (the cordis fields, kept) |
| `:ctx` | the child ctx it mounted |
| `:inject` | the **normalized** deps (each name maps to `nil` or `:optional`) — what the listing prints as `inject=[…]` |
| `:provides` | the names this fiber has provided and not released — the listing's `provides=[…]`, maintained by `provide!`'s disposer, so a shadowed realm's provider is still attributable |
| `:waiting-for` | the names of `:inject` the provider set cannot satisfy, recomputed by the pass — the listing's `waiting-for=[…]`; empty when `:active` |
| `:error` | the thrown value when `:failed`, nil otherwise |

- **The root fiber exists**, `:uid 0`, `:active`. It is the owner of anything
  the app registers before any plugin (root tools, scratch test registrations),
  which is why no registration in the light kernel can be fiber-less.
- `plugin!` creates the fiber, its child ctx (named from the plugin's `:name`,
  which is what gives the unit listing a readable path), registers it on the
  source's runtime, adds it to `:mounted`, then activates: unsatisfied →
  `:pending`; satisfied → `:active` and `apply` runs with `(ctx config)`.
- **`apply` is synchronous** and its return value is its first effect: a
  disposer, a seq of them, or nil (see §13 — the one cut that is not
  additive). It is registered last, so it unwinds first.
- **Effects.** `(effect ctx f)` requires an `:active` fiber (`:srce/inactive-effect`
  otherwise) and returns a single-shot disposer. Within an effect the returned
  disposers unwind LIFO; across the fiber's effects, unwinding is LIFO by
  creation order — one effect at a time, last created first, so a service that
  must release before another effect does is expressible by effect order. A
  lazy seq is collected as produced. `(effects fiber)` returns the labels in
  creation order (the reverse of unwinding), and the label is an **inspection
  contract** (`:tool/read`, `:command/units`): it is what the booted listing
  renders, so a vague label is a line that says nothing.
- **Disposal order is part of the contract.** `dispose!` is: flip the state to
  `:disposed` (so `get` already reads `nil`), record its `:provides` names as a
  change, unwind the effects LIFO (where the store entries are deleted), then
  `settle!` once at the end. Flipping **before** unwinding is what makes the
  closed-store guard real without a sixth state: a registration from inside a
  disposer (an effect, a `provide!`, an `on`) throws `:srce/inactive-effect` instead
  of arming cleanup that will never run, and dependents are never remounted
  against a half-torn-down service. `:disposing` stays internal to those four
  steps; it is never a value of `:state`.
- **Disposal runs everything and never skips.** A throwing disposer is reported
  through `:logger` at `:error` and the remaining disposers still run.
- **A failed fiber is a state, not an exception.** `apply` throws → the fiber is
  marked `:failed` with `:error e` **first**, so an effect or `provide!` from
  inside its own disposers throws `:srce/inactive-effect`; then its partial effects
  unwind LIFO (same logging as `dispose!`), its store entries are deleted so
  the names are free again, `:srce/failure` is emitted, dependents stay
  `:pending`, and the plugin is reported through `:logger`. A failed fiber is
  not retried by the pass (its provider set is unchanged), so `restart!` is the
  retry: a config/shape error keeps failing, a transient one recovers.
- **`unload!`/`activate!` are the primitives; `dispose!`/`restart!` are the
  public pairs.** A pass *reloads*: it sets `:pending`, unwinds (a disposer can
  no longer register), and activates again on the same fiber — same uid, same
  cfg re-validated. `dispose!` is the terminal one (state `:disposed`, that uid
  never returns); `restart!` is `dispose!` + `plugin!`, so it allocates a new
  uid. `:pending` doubles as the unloading state, because only `:active` may
  register.
- **`:mounted` in reverse is children-first.** Mount order is topological (a
  child always mounts after its parent), so `dispose-subtree!` is the reverse
  mount list filtered by `inside?` — no tree sort.
- **Keep related registrations in one effect.** The primer's own practical
  rule, and what makes disposal order meaningful at all: a tool and its prompt
  section, a provider and its listener, belong to the same effect, so teardown
  follows registration order. Two effects are two LIFO runs with no guarantee
  between them.
- **`restart!` is `dispose!` + `plugin!`** with the same source and config,
  returning the new fiber. There is no `update!`: a config change is a restart,
  which is what `/reload` does in the app.

The disposal axes stay distinct, as in the full port: the **fiber**
(`dispose!`), the **effect** (its disposer), the **ctx subtree**
(`dispose-subtree!` — every fiber under a ctx, children first, an agent's
release), and the **plugin** (`dispose-plugin!`, every fiber of one source).
The root fiber's disposal is an ordinary disposal: it unwinds every root effect,
so the app tree is gone and the app remounts the tree to restart. It never tears
down the process, and there is no implicit restart — `dispose!` returns nil.

## 5. Events

The kernel needs a notification path, not an event framework:

- `(on ctx name f)`, `(once ctx name f)`, `(emit! ctx name ev {:filter f})`.
  A listener is `(fn [event])`; its own ctx is visible to the `:filter` and in
  the resolved list, never as a call argument — one shape for all five modes.
- A ctx's listeners run for emits on that ctx **and its ancestors**, so a
  subtree scopes naturally; `:filter` receives each listener's own ctx (a
  listener cannot opt out), which is the shape the node-scoped bus needs.
- Registration order, `once` disposes before invoking, and the disposer reports
  whether it removed something.
- Listener results are ignored; a throwing listener is reported and does not
  stop the rest.

**One listener table, five dispatchers.** Registration is `on`/`once` on the
ctx and nothing else; the *modes* are different walks over that one table —
which is how idea 4 survives the trim with its mechanism intact. The seam
between the two layers is the resolved list, not the table itself: Cordis does
the same split in `events.ts` (`dispatch` returns the filtered, bound list and
each mode walks it), and it is why the app needs no second registry.

| mode | where | why there |
|---|---|---|
| `emit` | `srce/emit!` | no return value, no await: ~15 lines |
| `bail` | kmet1's bus, over `srce/listeners` | a plain walk needing no awaitable (~8 lines) — deferred because it *is* additive (§10 criterion 3), not because the bus has it. It does not: `emit-event!` (event_bus.clj 479-495) runs **every** listener and keeps the last non-nil, which is neither `bail` (first bail value, short-circuit) nor `waterfall`. If a landing needs a policy veto, `bail!` is the kernel's cheapest addition and moves into this row. |
| `waterfall` | kmet1's bus, over `srce/listeners` | around-middleware: a listener receives `next` and not calling it vetoes. Today's closest shape is `emit-boundary!` (lines 516-534), which threads an accumulated map into each handler — the right data protocol, wrong call protocol (no `next`), so landing 2 adds `next` in the service's wrapper and leaves the table alone. |
| `parallel` / `serial` | kmet1's bus, over `srce/listeners` | awaited, so they need the app's awaitable — the kernel has none (§8); landing 2 adds them, and `emit-event-collect!` (lines 497-514) is the existing collect-all shape. |

Landing 2 lifts `kmet.agent.event-bus` on top of `(srce/listeners ctx name opts)`:
the `:events` service owns the five-mode wrappers and the node-scoped
`:filter`, so the bus is a dispatcher, not a second registry. Duplicating the
modes in the kernel would be the copy AGENTS.md forbids; moving the awaited two
in would drag an awaitable back in. Make the lift a *replacement*: landing 2
renames the three existing shapes to the modes they are (`emit-event!` →
collect-last, `emit-event-collect!` → collect-all, `emit-boundary!` → boundary)
and adds `bail` — it must not claim `emit-event!` was already a bail.

**Declaration lives above the kernel.** The kernel's own event names are three —
`:srce/status` (fiber state transition, `{:fiber f :from s :to s}`, emitted on
the mounting ctx), `:srce/service` (a provider installed/removed,
`{:name n :value v}`, emitted on the providing ctx), `:srce/failure` (`apply`
threw, `{:fiber f :error e}`) — Cordis's nine `internal/*` events collapsed to
the three with a consumer. `event-bus/event-types` is rank 3, so it can never
list `:srce/*`: the kernel's vocabulary is documented here and pinned by its
emit sites, while the app's events get the catalog. That asymmetry is the price
of the rank cut, and it is cheaper than a rank-3 require in the kernel.

## 6. Plugins

**No `IPlugin` protocol.** A plugin is a validated value — Cordis's Base fields
as a map, shape-checked at compile time by `defplugin` and once at mount by
`plugin!`:

```clojure
{:name    :tool/web-search     ; required — the runtime name and the mount's ctx name
 :inject  {:tools nil}         ; optional, default {} — required deps, nil values
 :config  (fn [cfg] …)         ; optional, default identity — validator, cfg or throw
 :provide #{:web-search}       ; optional, default #{} — declared, checked after apply
 :apply   (fn [ctx cfg] …)}    ; required — the effect body
```

`:name` and `:apply` are the only required keys. `plugin-schema` runs once, in
`plugin!` (and at compile time in `defplugin`); a bad map throws `ex-info`
`{:type :srce/bad-plugin :problems […]}` — a caller bug, not a `:failed` fiber,
which means the plugin *ran* and threw. `:config` runs on **every** mount and
remount, from the raw config, and its return is what `apply` receives;
`(:config fiber)` stays raw, so a restart always revalidates. `:provide` is a
claim, checked after a successful `apply` against the fiber's `:provides`
(§4): a mismatch in either direction is logged at `:warn` through `:logger`
(a plugin may provide conditionally, so it is not a failure), which is what
keeps the `--dump-config` card honest instead of aspirational.

| Cordis | srce |
|---|---|
| `Plugin.Function (ctx, config)` | — (one map shape; the body is `:apply`) |
| `Plugin.Object { apply }` | the plugin map |
| `Plugin.Constructor` | not ported — a record is built inside `:apply` |
| `Plugin.Base` (`name`/`Config`/`inject`/`provide`) | the map's keys |
| `Plugin.Transform` (`{schema, Config}`) | `:config` is a validator fn, so `(m/compiled …)` is just a fn |
| `IPlugin` type | `plugin-schema`, checked once (`{:type :srce/bad-plugin :problems …}`) |
| `registry.resolve` | `srce/plugin-source` — a var as-is; a keyword looked up by the caller's resolver |
| `registry.get`/`.has`/`.delete` | `srce/runtime` / `srce/runtime?` / `srce/dispose-plugin!` |
| `registry.keys`/`.size` | `srce/runtimes` (the inspection surface `--dump-config` reads) |
| `registry.counter` | the root's uid allocator, internal |
| `Inject.resolve` | `srce/normalize-inject` (`:optional` sentinel) |
| plugin *source* polymorphism | the caller's resolver — `kmet.loader.core/Loader` (SCI / Jolt native / classpath) is the one kmet2 passes |

**Source resolution is a seam, not a dependency.** `plugin-source` normalizes
what the caller hands `plugin!`: a var passes through, a keyword is looked up by
the resolver, a miss is `{:type :srce/bad-source}`. `srce.*` may require
`kmet.loader.*` (rank 0, below the kernel) and `kmet.loader.core/Loader` is the
resolver kmet2 passes, but the kernel never requires it and never decides what
a keyword means — how an extension source is resolved is the extension host's
implementation detail, not a kernel contract.

`defplugin` is sugar over the map: it gives the source var, `:name` metadata, a
compile-time shape check, and the card `--dump-config` prints. A profile row
names the **source**, not a keyword; the generated catalog is a committed
manifest for validation and docs, not a mutable registry:
`modules/app/src/kmet/app/plugin-catalog.edn`, written by `bb
generate-plugins` (`kmet.tasks.plugins`) from the top-level `defplugin`
declarations in the module sources, so `--dump-config` can render a built-in
row's card without loading the plugin's namespace. A source missing from it
composes anyway — the card is then read from the loaded var.

**One runtime, many fibers.** `plugin!` creates a fresh fiber per call — its own
config, ctx, effects and lifecycle — and records it on the source's runtime.
`inject!` is `plugin!` with `{:name name :inject deps :apply cb}`, and it takes the name
first — `(inject! ctx :ui/terminal deps cb)` — because a Clojure fn carries no
reliable name to derive one from (Cordis uses the callback's), and an unnamed
mount has no ctx path, no runtime name and an anonymous listing line. A runtime
is keyed by the resolved source, so two keywords resolving to the same var
share one runtime; the runtime's `:name` always comes from the plugin map, not
from the keyword. A runtime is dropped when its last fiber unloads, so a later
`plugin!` recreates it.

## 7. `:logger` is a service, not a subsystem

The kernel writes nothing to stdout. `(srce/log! ctx level & args)` resolves
`:logger` and calls it; absent means silent. kmet2 provides
`(srce/provide! ctx :logger kmet.agent.debug/log)` at boot.

`kmet.srce.*` may require only rank-0 code — in practice `kmet.libs.*` and, for
source resolution, `kmet.loader.*` — plus third-party code, so the kernel
cannot call `kmet.agent.debug` directly — which is also why the full port's
logger service (exporters, formatters, ANSI palette, ring buffer, `:logger`
intercept config) is deferred in whole: kmet already has `kmet.agent.debug`,
and a second logger is a copy.

## 8. Scheduling: one thread, no queue

Cordis runs every transition inline on the caller's stack, which is safe in JS
because there is one thread and a microtask queue. kmet2 has neither: a
transition can be entered from the TUI thread, a `spawn`ed tool thread, the
loop, or a socket reader. The full port answered with a drain-owner thread and
an awaitable queue. The light kernel answers with a rule:

- **The kernel has no lock; the pass has a flag.** Every mutation runs on the
  thread that owns the root ctx. The exception is the class of mutations that
  only *append*: `provide!`, `on`/`once` and `effect` on an already-active
  fiber touch an atom or the effect vector, so a foreign-thread call is *safe
  but late*, never corrupting — it records and returns, and the owner's next
  `settle!` (or the turn boundary) applies it. Everything that runs plugin code
  — `plugin!`, `restart!`, `dispose!`, `dispose-plugin!`, `dispose-subtree!` —
  stays on the owner thread: a disposer may re-enter the kernel, and a
  concurrent pass could otherwise remount a dependent mid-teardown. That is the
  whole distinction; "safe but late" is not a licence for `dispose!`.
- **The app marshals.** Plugin work, reloads and approvals are submitted to the
  loop; a tool thread that must mutate the kernel asks the loop to do it. This
  is app policy, recorded in [`new-app.md`](../../../../../new-app.md), and a guard test
  asserts `kmet.app.*` submits rather than mutating from a tool thread.
- **No awaitable, no `drain-await`.** `dispose!` and `plugin!` are synchronous,
  so there is nothing in flight to drain. The turn-boundary quiesce is the
  loop's own awaitable (`:agents`' `when-idle`), not a kernel primitive.
- **Async teardown is the plugin's own promise**, awaited by the app at the
  boundary. The kernel never awaits a disposer.

Alternatives, and why not:

| Alternative | Why not |
|---|---|
| drain-owner thread + queue (the full port) | correct, but ~250 lines of kernel, an awaitable module and a drain test to serialize something the app already serializes: every plugin mutates the kernel from `apply`, on the loop |
| one global lock around inline passes | deadlocks when a transition waits on another thread's `provide!`; cannot express "this fiber is mid-unload" |
| one thread per fiber | `apply` may block, so fiber-per-thread makes every plugin author a concurrency author |

## 9. Deleting `kmet.libs.scope`

`kmet.libs.scope` is kmet1's hand-rolled fiber: with no lifecycle object, it
invented owner paths and a keyed store to stand in for one. The light kernel
supplies the lifecycle, so the store loses its reason to exist — but only after
the registries that read it have moved. The kernel therefore **must not depend
on it** (a guard test), and its 35 source call sites in 9 files go with their
landings (counted from `(scope/…` call sites, not from the requires):

| File | Sites | Go at |
|---|---|---|
| `agent/event_bus.clj` (`in-subtree?`) | 1 | landing 2 — the bus is lifted |
| `agent/registry.clj` | 5 | landing 3 — the registry is parameterized then lifted |
| `agent/tools/registry.clj` | 4 | landing 3 |
| `agent/commands.clj` | 2 | landing 3 |
| `agent/prompts.clj` | 2 | landing 3 |
| `agent/skills.clj` | 2 | landing 3 |
| `agent/extensions/core.cljc` | 12 | landing 4 — the extension host and ABI |
| `agent/app_runtime.clj` (app node, `*error-reporter*`, `/units` walk) | 5 | landing 4 — the `*error-reporter*` rebinding goes with the logger service; the `owners`/`owner-registrations` pair (lines 115-117) is the store listing and goes with the tree rewrite |
| `agent/session/runtime.clj` (`child-node`) | 2 | landing 4 |

Two more places name it without calling it: `extensions/context.cljc`'s
extension-API allowlist (drop the entry with the ABI at landing 4) and
`test/kmet/test_utils.clj`'s `dispose-owner!` in `clear-app-registries!` (one
call; it moves with whichever registry's clear replaces it).

Each registry becomes a service with its own table, and each contribution is an
`srce/effect` on the contributing fiber — so unloading an extension is
`dispose!` on its fiber and the entries come back LIFO. Deleting the store
before that would rewrite those 35 sites into private cleanup lists, which is
the copy AGENTS.md forbids.

**All of it in one change, at the end of landing 4:** delete
`modules/libs/src/kmet/libs/{scope.clj,scope.md}`,
`modules/libs/test/kmet/libs/test_scope{,_self_contained}.clj`, the
`app_runtime.clj` `*error-reporter*` rebinding (cleanup errors go to the
`:logger` service instead — boot provides it before the first plugin, and §7's
silence applies after a logger teardown), the `scope` rows in
`modules/agent/src/kmet/README.md` (the `kmet.libs.scope` bullet under "Layer
boundaries" and the store paragraph under "Architecture and portability"),
`extension.md`'s two `[:app :extensions <name>]` sentences (the extension host
and `resources_discover`), the `theme.clj` cross-reference docstring, the
`test_changed.clj` path→ns fixture, `test/kmet/test_utils.clj`'s store usage,
and the twelve test namespaces whose fixtures build store nodes
(`modules/agent/test/kmet/agent/{test_{commands,event_bus,prompts,runtime,session_runtime,skills,tools,ui_port},extensions/test_{libs,lifecycle,resources,session}}.clj`)
— they are re-pointed at the new factories in the same change, not after it.
Gate: `bb test` green, `kmet --app old` still loads, and no namespace outside
`kmet.agent.*` ever required it (true today — `kmet.libs.*` and `kmet.tui.*`
have no consumer; `theme.clj` names it only in a docstring).

What the app loses is `ordered-registrations` (each registry owns its load
order) and the stale-deregister caveat (a fiber's `:active` check replaces the
value guard). `/units` is not lost: it is re-rendered from the ctx tree and the
runtimes — one flat line per unit, plus the two facts the store could never
show, a fiber's `state` and what it is `waiting-for` — by `kmet.app.inspect`, so
no listing code lives in the kernel ([`new-app.md`](../../../../../new-app.md), "Inspection").

## 10. Deferred

Recorded so that re-adding one is a lookup. "Additive" means the light surface
grows a keyword or a key, not a new shape.

| Mechanism | Cordis member | Why out | How it comes back |
|---|---|---|---|
| Logger service (facades, exporters, formatters, palette, buffer) | `logger.ts` | kmet already has `kmet.agent.debug`; a second logger is a copy | `:logger` is a service — provide a richer one |
| `intercept` chain | `ctx.intercept`, `[Service.resolveConfig]` | unused by `new-app.md`; labels and realms carry the isolation it was needed for | inject values are reserved: they become intercept config |
| `Service` base / `defservice` | `service.ts` (`:check :invoke :extend :resolve-config`) | `:check` is a `provide!` option; `:invoke`/`:extend`/`:resolveConfig` are class ergonomics (the last needs `intercept`, which is out too) | a map with `:check` already exists on `provide!`; `:extend` is a second `provide!` |
| `accessor`, `mixin`, `set-service!` | `reflect.ts` | no proxy to feed; a service re-provides, which is one effect | new keys on `provide!` |
| isolate **labels** | `ctx.isolate(name, label)` | siblings sharing one realm nest under a common isolating ctx | a label argument on `isolate` |
| 9 `internal/*` events, `internal/listener` claiming | `events.ts` | 3 have a consumer (§5); the rest are debug/inspection plumbing with none | new names on the existing emit |
| `bail` and `waterfall` | `events.ts` | both are walks over the resolved listener list (`srce/listeners`), so neither needs a kernel change — and `bail` needs no awaitable either. Deferred by criterion 3, not by capability. | `bail`: ~8 lines (`bail!`) in the kernel; `waterfall`: the `:events` service, whose wrapper supplies `next` |
| `parallel` / `serial` | `events.ts` | awaited, so they need the app's awaitable — the kernel has none (§8) | the `:events` service over `srce/listeners` |
| Lazy config resolution, `{:schema :transform}` | `config.ts` | `apply` validates its own map; a validator fn composes | `:config` accepting a schema map |
| Fiber lifecycle beyond 4 states (`:loading` `:unloading`, `inertia`, epoch drop-rule) | `fiber.ts` | one thread makes the race unrepresentable | states on `:state`; the epoch is the provider set |
| `update!` (config waterfall, no-save hint) | `fiber.update` | `restart!` covers `/reload`; a vetoing waterfall is app policy | an `:update` hook on the plugin map |
| `fiber.store` snapshot, `fiber.await`, `assert-active!` | `fiber.ts` | sync: there is nothing to await; the per-fiber facts the listing needs are `:provides`/`:inject`/`:waiting-for` on the record (§4), not a store snapshot | derived from `:state` and the record's own keys |
| awaitable, drain owner, `drain-await` | (kmet2 addition) | a queue and a thread to serialize what the app already serializes | the optimistic pass flag + `settle!` (§3) are the replacement; an awaitable, if ever needed, is the app's |
| `DisposableList`, `EffectMeta` labels, `utils.ts` traceable | `utils.ts` | a vector and a map cover it | `:label` on `effect` is already accepted |
| JS syntax: `Proxy`, decorators, `instanceof`, prototype splicing | — | not semantics | never |

## 11. Parity matrix

Every public member of the vendored tree that has a decision, with the light
kernel's state (`EventsService.dispatch`/`register`/`unregister`,
`ReflectService.props`, and the JS inspect hooks are implementation detail, not
rows). `keep` = ported in the light kernel, `deferred` = §10, `never` = JS-only.
The conformance suite is written from §3–§8 and this table's `keep` rows.

### context.ts

| Cordis | srce | light |
|---|---|---|
| `new Context()` | `srce/root` | keep |
| `ctx.extend(meta)` | `srce/extend` | keep |
| `ctx.isolate(name, label?)` | `srce/isolate` (no label) | keep |
| `ctx.intercept(name, config)` | — | deferred |
| `ctx.root` | `:kernel` (shared state), no root ctx pointer | keep (shape differs) |
| `ctx.baseUrl` | `:base-url` | keep |
| `ctx.events` / `.logger` / `.reflect` / `.registry` | `:listeners` / `:logger` service / `provider` / `:runtimes` | keep |
| `ctx.fiber` | `:fiber` | keep |
| `Context.is` | `srce/ctx?` | keep |
| `Context.effect` / `filter` / `isolate` symbols | `:srce/effect`, `:filter`, `:isolate` | keep |
| `Context.intercept` symbol | — | deferred |
| get / set / has traps | `srce/get`, `provide!`, `srce/provider` | keep |
| reserve names (`isSpecialProperty`) | — | never |
| `nodejs.util.inspect.custom` | `print-method` | deferred |

### reflect.ts

| Cordis | srce | light |
|---|---|---|
| `ctx.get(name, strict?)` | `srce/get` (no strict flag) | keep |
| `ctx.set(name, value)` | — (`provide!` again) | deferred |
| `ctx.provide(name, value?, check?)` | `srce/provide!` | keep |
| `ctx.accessor(name, {get, set})` | — | deferred |
| `ctx.mixin(source, keys)` | — | deferred |
| `reflect.notify(names, filter?)` | the refresh pass (§3); no `filter` argument — the provider-set recompute subsumes Cordis's isolate filter | keep (internal) |
| `reflect.props` | — | deferred |
| `reflect.store` | the ctx's `:store` atom | keep (shape differs) |
| `Impl {name, fiber, value, check}` | `{:name :fiber :value :check}` | keep |
| `reflect.trace` / `reflect.bind` | — (explicit `ctx`) | never |
| `internal/get` / `internal/set` waterfalls | — | deferred |

### fiber.ts

| Cordis | srce | light |
|---|---|---|
| `FiberState` (6 states) | `:pending :active :failed :disposed` | keep (reduced) |
| `fiber.uid` / `.name` / `.config` / `.state` / `.error` | same, plus `:inject` / `:provides` / `:waiting-for` (§4) | keep |
| `fiber.store` / `.inertia` | `:provides` (§4) / — | deferred (the listing fields are on the record instead) |
| `fiber.ctx` / `.parent` / `.runtime` | `:ctx` / `:parent` / `:runtime` | keep |
| `fiber.effect(body, label)` | `srce/effect` | keep |
| `fiber.getEffects()` | `srce/effects` | keep |
| `fiber.await()` | — | deferred |
| `fiber.restart()` | `srce/restart!` (dispose + mount) | keep |
| `fiber.update(config, noSave)` | — (`restart!`) | deferred |
| `fiber.dispose()` | `srce/dispose!` (sync) | keep |
| `fiber.assertActive()` | `:srce/inactive-effect` on `effect` | keep |
| `{effect: …}` metadata on a disposer | `:label` on `srce/effect` | keep |
| `rootFiber.dispose()` (restarts) | `dispose!` on the root fiber; the app remounts, no implicit restart | keep (differs) |
| `ValidationError` (config/plugin shape) | the plugin's `:config` throw, or `:srce/bad-plugin` for the map itself | keep (shape differs) |
| `CordisError/INACTIVE_EFFECT` | `{:type :srce/inactive-effect}` | keep |
| `resolveConfig(runtime, config)` | the plugin's `:config` fn | keep |

### registry.ts

| Cordis | srce | light |
|---|---|---|
| `ctx.plugin(plugin, config?)` | `srce/plugin!` | keep |
| `ctx.inject(deps, callback)` | `srce/inject!` | keep |
| `registry.resolve(plugin)` | `srce/plugin-source` | keep |
| `registry.get` / `.has` / `.delete` | `srce/runtime` / `srce/runtime?` / `srce/dispose-plugin!` | keep |
| `registry.keys` / `.values` / `.size` | `srce/runtimes` | keep |
| `registry.entries` / `.forEach` | `srce/runtimes` (fiber → runtime via `:runtime`) | keep (folded) |
| `registry.counter` | the root's uid allocator | keep (internal) |
| `Plugin.Runtime {name, fibers, callback, Config}` | `{:name :fibers :source :config}` | keep |
| `Inject.resolve` | `srce/normalize-inject` | keep |
| `@Inject` (class) | `:inject` metadata | keep |
| `@Inject` (method) | — | never |
| `Plugin.Function` (the plugin *is* a fn) | — (one map shape; the body is `:apply`) | deferred |
| `Plugin.Object` | the plugin map | keep |
| `Plugin.Constructor` | — | never |
| `Plugin.Transform` | `:config` as a validator fn | keep |

### events.ts

| Cordis | srce | light |
|---|---|---|
| `ctx.emit` | `srce/emit!` | keep |
| `EventsService.dispatch` (resolve + filter) | `srce/listeners` | keep |
| `ctx.parallel` / `.serial` / `.bail` / `.waterfall` | — (`bail` is the kernel's cheapest addition; all four are the `:events` service) | deferred |
| `ctx.on` / `.once` | `srce/on` / `srce/once` | keep |
| `{prepend, global}` | — | deferred |
| dispatch `thisArg` filter | `{:filter (fn [listener-ctx] …)}` | keep |
| disposer returning removal status | same | keep |
| `isBailed` | — (bus) | deferred |
| `AggregateError` from `parallel` | — | deferred |
| `internal/listener` claiming a registration | — | deferred |
| nine `internal/*` events | three `:srce/*` names | keep (reduced) |

### service.ts, logger.ts, utils.ts

| Cordis | srce | light |
|---|---|---|
| `class Service` + constructor registration | — | deferred |
| `[Service.check]` | `{:check fn}` on `provide!` | keep |
| `[Service.filter]` (realm comparison) | the realm walk in `get` / the refresh pass | keep (internal) |
| `[Service.invoke]` (callable) | — | deferred |
| `[Service.extend]` | — | deferred |
| `[Service.resolveConfig]` | — | deferred |
| `logger.ts` (`ctx.logger`, exporters, formatters, palette, buffer) | `srce/log!` → the `:logger` service | deferred |
| `DisposableList` / `EffectMeta` labels | a vector and a map | keep (internal) |
| `isBailed` / `isObject` | `(not (nil? v))` at call sites / `map?` | deferred |
| `getTraceable` / `Tracker` / `withProps` / `createCallable` | — (explicit `ctx`) | never |
| `symbols`, `isConstructor`, `getPropertyDescriptor`, `composeError` | keyword keys, `:srce/*` meta, `ex-info` | never |

## 12. Gates

- 41 contract tests (164 assertions) — one per bullet in §2–§8 that can fail,
  one per `keep` row of §11, table-driven where the rows are uniform. The behaviors: realm
  resolution and the boundary, including two **siblings** under one realm
  providing the same name (nearest visible wins, no throw — §3); duplicate
  provider in one realm path; `check`; inject required/optional and the
  `:srce/bad-inject` refusal; a bad plugin map throwing `:srce/bad-plugin` while a
  throwing `apply` is a `:failed` fiber; `inject!` with and without a name; the
  refresh fixpoint (2-plugin chain, provider swap, absent→present upgrade,
  churn budget clears the set, a `provide!` inside `apply` does not remount its
  own fiber); effect LIFO within and across effects; a throwing disposer still
  runs the rest; `:srce/inactive-effect`, including a registration from **inside a
  disposer**; `restart!` replaces the uid and is the retry for a failed fiber;
  `dispose-plugin!` ends every fiber and drops the runtime; `:failed` on a
  throwing `apply` unwinds the effects it registered and frees the names it
  provided, with dependents `:pending`; listener order, `once`, `:filter`, a
  throwing listener, and `listeners` returning the resolved list the `:events`
  service walks; `inside?`; `log!` with no `:logger`; the fiber record's
  `:inject`/`:provides`/`:waiting-for` matching the store after a mount, a
  provide and a dispose; the pass flag — two threads entering `provide!` at
  once run one pass owner, both changes land, and `settle!` returning with no
  other owner means `:changes` empty.
- srce self-containment: `kmet.srce.*` may require only rank-0 code
  (`kmet.libs.*`, `kmet.loader.*`, `kmet.reakt.*`) and third-party code — no
  `kmet.agent.*`, no `tui`/`tk`, nothing at its own rank or above.
- **No `kmet.libs.scope` require in `kmet.srce.*`** (§9), and after landing 4 no
  `kmet.agent.*` require either — the deletion gate.
- No leak: `scope`-free equivalent — a test disposes a 3-plugin tree and
  asserts `(srce/runtimes ctx)` empty, every `:children` empty, and the service
  store empty.

## 13. Open decisions

| Question | Recommendation |
|---|---|
| **Synchronous `apply`** — the one cut that is not additive | yes. Reversing it changes `plugin!`'s return from a fiber to an awaitable, so every caller. Async work is the plugin's own promise |
| `get` on a name provided by a non-active fiber | nil, one `get`, no `strict` flag |
| `(:tools ctx)` through `ILookup` | not ported. `srce/get` is explicit; destructuring and `contains?` stay honest about nil |
| One provider per name: realm-level or visibility-level | visibility (per ctx store + the walk). A realm-level store is Cordis's shape: it needs a realm-key symbol table (the labels §10 defers) and makes a sibling provider throw mid-tree. Two siblings may provide the same name — `isolate` is how a subtree gets its own |
| `bail` in the kernel or in the `:events` service | the service, for now — it is additive (§10 criterion 3). If a landing needs an in-kernel veto, `bail!` is ~8 lines; nothing else moves |
| Whether the thread rule needs an assertion in the kernel | no — a portable thread identity does not exist on all three hosts; the app's guard test asserts the submission instead. §8 distinguishes append-only mutations (late-safe) from ones that run plugin code |
| `:config` validator: fn, or a schema map | fn. malli compiles to a fn; the profile layer composes. It runs per mount, raw config in, resolved config out |
| `:name` on a plugin: keyword or symbol | keyword, with the var path as `:source`. Required — there is no fn name to fall back to |
| What `dispose!` on the root fiber does | disposes (unwinds every root effect); the app remounts. No implicit restart — a restart after dispose is `plugin!` again |
| Does a pass reload reuse the fiber? | yes — `unload!` + `activate!` on the same fiber and uid. `restart!` is the new-uid operation. Otherwise a provider swap would churn uids and dependents would remount twice |
| `:resolver`: ctx key or service? | inherited ctx key (`(root {:resolver f})`), not a service: it is policy carried to the loader seam, and a service would need provide/dispose for something the kernel only forwards |

## 14. Not doing

- Porting JavaScript syntax rather than semantics: TS declaration merging,
  decorators, prototype property mixins, the `Proxy`-based context,
  `Symbol.hasInstance`.
- Porting the separate `@cordisjs/plugin-loader` / `plugin-hmr` /
  `plugin-logger-console` packages: profiles/bundles/patches are kmet's loader,
  HMR is deferred until the kernel settles, and the console exporter is
  `kmet.agent.debug`.
- The Koishi plugin market / `package.json` service manifest.
- Re-deriving a mechanism kmet already owns: the event modes (`agent/event-bus`
  is the *dispatcher layer* over `srce/listeners`, not a second registry), the
  logger (`agent/debug`), the loader (`loader/core`). The kernel composes them,
  it does not reimplement them.
- Chasing parity with the vendored tree. §11 is the record; §10 is the
  justification; `keep` rows are the contract.

## 15. Kernel sketch

The shapes the four namespaces are built from. Not a full listing — §3–§8 are
the contracts and the tests pin them — but every load-bearing mechanism, with
the numbers the "light" claim rests on.

| namespace | lines | what it owns |
|---|---|---|
| `ctx.clj` | 240 | ctx tree, store, isolate, `get`/`services`, the effect list, `log!` |
| `event.clj` | 58 | listener table, `listeners`, `on`/`once`, `emit!` |
| `fiber.clj` | 474 | inject, the refresh pass, mount/unmount, dispose axes, runtimes |
| `srce.clj` | 161 | the public aliases and `defplugin` |

`ctx.clj` requires no sibling; `event.clj` requires `ctx`, `fiber.clj` requires
both, `kmet.srce` aliases the three. The effect primitive lives in `ctx.clj`,
not `fiber.clj`, because a plugin mount registers listeners (`event` → `effect`)
and a listener must be reversible: with `effect` in `fiber`, those three form a
require cycle.

```clojure
;; a ctx is a plain map; root state is shared, never pointed back at
{:srce/ctx true
 :parent ctx :children (atom []) :name nil
 :isolate #{:tools}                          ; realm for these names
 :store (atom {name {:name :fiber :value :check}})
 :listeners (atom {event-name [f …]})
 :fiber (atom {…})                           ; one atom per fiber, one swap! per transition
 :kernel {:runtimes (atom []) :mounted (atom []) :counter (atom 0)
          :pass (atom nil) :changes (atom [])}}
```

```clojure
;; 2 — resolve up the tree, stop at a realm
(defn provider [ctx name]
  (loop [c ctx]
    (when c
      (or (clojure.core/get @(:store c) name)
          (when-not (contains? (:isolate c) name) (recur (:parent c)))))))

;; 5 — one effect = one entry; LIFO; single-shot; the state gate is the
;; closed-store guard
(defn effect [ctx f & [label]]
  (let [fiber (:fiber ctx) state (:state @fiber)]
    (when-not (= :active state)
      (throw (ex-info "inactive fiber" {:type :srce/inactive-effect :state state})))
    (let [undos (atom []) live? (atom true)
          undo (fn []
                 (when (compare-and-set! live? true false)
                   (reduce (fn [_ d]
                             (try (d) (catch Throwable t (log! ctx :error "disposer threw" t))))
                           nil (rseq @undos))))]
      (swap! fiber update :effects conj {:label (or label :effect) :undo undo})
      (let [r (f)]
        (swap! undos into (cond (nil? r) [] (sequential? r) r :else [r])))
      undo)))

;; 1+3 — the epoch is the provider set; a change reloads the same fiber.
;; An unchanged set is a no-op, so a failed fiber is never retried by the pass.
(defn- refresh! [fiber]
  (let [{:keys [ctx inject state epoch]} @fiber]
    (when-not (= :disposed state)
      (let [next-epoch (providers ctx inject)]
        (when (not= next-epoch epoch)
          (reload! fiber next-epoch))))))

;; 3 — one pass flag; the pass never runs reentrantly
(defn settle! [ctx]
  (let [{:keys [pass changes]} (:kernel ctx)]
    (loop []
      (when (compare-and-set! pass nil :running)
        (try (run-pass! ctx) (finally (reset! pass nil))))
      (when (and (seq @changes) (nil? @pass)) (recur)))))

;; 4 — one table, resolved once; the modes are walks over this list
(defn listeners [ctx name {:keys [filter]}]
  (loop [c ctx acc []]
    (if-not c acc
      (recur (:parent c)
             (into acc (for [f (get @(:listeners c) name)
                             :when (or (nil? filter) (filter c))]
                         {:ctx c :f f}))))))
```

- A **listener** is `(fn [event])`; the listener's own ctx is visible only to
  the `:filter`, which is what keeps the table one shape for all five modes.
- **`:srce/*` keywords are explicit** (`:srce/inactive-effect`,
  `:srce/duplicate-service`, `:srce/bad-plugin`, `:srce/bad-inject`,
  `:srce/bad-source`, `:srce/churn`) — a public error keyword should not move
  when a namespace does.
- **`provide!`** is a `provider` duplicate check plus one effect; the effect's
  body inserts the entry and adds to the fiber's `:provides`, its disposer
  removes both and records the change. `:check` is stored with the entry.
- **`activate!`** sets `:active` *before* `apply` runs (so `apply` may register),
  and a throw sets `:failed` + `:error` *before* unwinding the partial effects,
  then emits `:srce/failure`.
- **`dispose!`** flips to `:disposed` first, unwinds, detaches from `:mounted`,
  drops the runtime when its last fiber is gone, and settles.
- **No protocols, records, multimethods or dynamic vars.** One macro
  (`defplugin`), three `compare-and-set!`s (the pass flag, each effect's
  single-shot flag, the change-set drain), no Java interop, no awaitable, no
  clock, no queue.
- **Not built**: a `:root` pointer, a `:disposing` state, a realm-key table,
  `bail!`, a schema library, a logger, HMR. Each is a §10 row, not an omission.
