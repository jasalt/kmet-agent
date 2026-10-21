# srce — a light Cordis kernel in Clojure

Temporary note at the project root (the `loop-plan.md` convention). Folds into
[`modules/srce/src/kmet/srce/srce.md`](modules/srce/src/kmet/srce/srce.md) when
the module lands. kmet2's application architecture — services, agents,
sessions, profiles, landings — is in [`new-app.md`](new-app.md); this file is
the kernel only: its ctx model, the lifecycle, the plugin contract, and what was
deliberately left out.

The name is **`srce`** — heart (South Slavic). Cordis is Latin for the same
thing (`cor`, `cordis`), which is what the kernel is to the system: the one
thing every other part is wired through. It stays clear of `kmet.kernel`,
`--list-kernels` and prose about OS kernels.

## What "light" means

Port the **concepts** that make Cordis a meta-framework, not the vendored
member list. A mechanism is in the kernel only if all three hold:

1. [`new-app.md`](new-app.md) needs it (a service, an agent, a profile, a UI
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
| 4 | typed events; dispatch as `emit`/`waterfall`/`parallel`/`serial`/`bail` | **one listener table**, one dispatcher per mode: `emit` in the kernel, the other four in kmet1's bus; the declaration half is `event-bus/event-types` + the generated catalog, not TS merging | §5 |
| 5 | registrations are reversible **effects**, so reload and teardown unwind predictably | the fiber's effect list, LIFO, owning the `provide!` and the listener | §4 |

Idea 3 is the load-bearing one for kmet2: extensions, profile rows and UI
providers mount in arbitrary order (a preset bundle, an extension directory, a
`--mode` flag), and "the plugin waits for its services" is what makes that
safe. Idea 5 is what makes the wait reversible: every registration a fiber made
comes back when its provider goes away.

Everything else is **deferred, not dropped**: §10 records each mechanism
with the Cordis member it came from and the seam that makes it additive, so
re-adding one is a lookup rather than a re-read of `vendor/cordis/src/*.ts`.
The Parity matrix survives as the appendix, one row per Cordis member, tagged
`keep` / `deferred` / `never`.

| | full port (retired) | light |
|---|---|---|
| namespaces | 10 (`ctx` `reflect` `service` `fiber` `registry` `event` `logger` `awaitable` `utils` `config`) | 4 (`ctx` `fiber` `event` + the public `srce`) |
| kernel lines | ~4k | ~700 |
| conformance | 74 tests generated from 82 matrix rows | ~45 contract tests, one per contract (§12) |
| definition of done | Cordis parity | the contracts in §3–§7 |
| async | awaitables, `inertia`, epochs, drain owner | synchronous kernel, thread rule (§8) |

The cut is ordered so the light surface is a **subset** of the full one:
`isolate` labels, `intercept`, the logger service, internal events and config
transforms all attach to an existing seam. The one exception is §13's
synchronous `apply`.

## 1. The kernel

```
modules/srce/
├── deps.edn           — rank 1: kmet.libs.*, kmet.loader.*, third-party code
├── test/kmet/srce/    — the contract tests (§12)
└── src/kmet/
    ├── srce.clj       — the public API (below); the one require app code needs
    └── srce/
        ├── srce.md    — this file
        ├── ctx.clj    — the ctx value, the tree, the service store, isolate, refresh
        ├── fiber.clj  — mount/unmount, states, the effect list, plugin runtimes
        └── event.clj  — on/once/emit!, the listener table, dispatch filters
```

`kmet.srce` is rank 1 — it may require rank-0 code (`kmet.libs.*`, and
`kmet.loader.*` when it resolves sources), nothing at its own rank or above;
the subnamespaces are internal and nothing above the kernel requires them.
Consumers require exactly one namespace — the kernel's surface is one contract,
not a set of modules whose callers each pick their own:

```clojure
(ns kmet.app.session
  (:require [kmet.srce :as srce]))

(srce/root)                              ; a root ctx — boot holds one, tests build their own
(srce/extend ctx {:name :agent/7f3a})    ; a child ctx with metadata
(srce/isolate ctx :tools :llm)           ; a child ctx that is a realm for those names
(srce/inside? child ctx)                 ; is child in ctx's subtree? (inclusive)
(srce/get ctx :tools)                    ; the provider value, or nil
(srce/provider ctx :tools)               ; {:name :fiber :value :check}, or nil
(srce/services ctx)                      ; {name {:name :fiber :value :check}}, realm-aware
(srce/children ctx)                      ; this ctx's child ctxs, creation order
(srce/provide! ctx :tools table)         ; → disposer; a second provider in the realm throws
(srce/on ctx :srce/status f)             ; → disposer
(srce/once ctx :srce/status f)
(srce/emit! ctx :my/event ev {:filter f}); sync, filtered, results ignored
(srce/plugin-source src resolver)        ; a var as-is, or the resolver's value for a keyword
(srce/plugin! ctx web-search {:max-results 5})   ; → fiber
(srce/inject! ctx {:ui :optional} handler)       ; plugin! sugar
(srce/normalize-inject deps)             ; required/optional deps, or ::bad-inject
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
and passes it down, a test builds its own, and everything root-owned (runtimes,
mounted fibers, the uid counter, the pass flag) lives on that value — so two
roots never see each other and no kernel state is process-global.

Three public names shadow `clojure.core` — `get`, `extend`, `once` — so the
kernel's own namespaces carry `(:refer-clojure :exclude [get extend once])`
rather than spelling `clojure.core/get` at every call site.

## 2. The ctx value

A ctx is a value: a map with fixed keys, no proxy, no service methods on it.
`(srce/ctx? x)` is a marker key, replacing Cordis's `Context.is` brand.

| key | meaning |
|---|---|
| `:root` | the root ctx — every child shares it |
| `:parent` `:children` | the tree (`:children` is an atom); a child comes from `extend`/`isolate` |
| `:name` | this ctx's own name — a plugin mount names its ctx from the plugin's `:name`, and the unit listing derives a path from the chain |
| `:fiber` | the fiber that owns this ctx (the root fiber has `:uid 0`); `extend`/`isolate` inherit the parent's, a plugin mount installs its own |
| `:isolate` | the set of names this ctx is a realm for |
| `:store` | this ctx's own `{name → {:name :fiber :value :check}}` atom |
| `:listeners` | this ctx's `{event-name → [listener …]}` atom |
| `:base-url` | resolution base for a source resolver; the kernel carries it and never reads it — what it means is the extension host's call; nil until set |
| `:runtimes` `:mounted` `:counter` | **root only**: plugin runtimes, mounted fibers in mount order, the uid allocator — `extend` drops them and they are read through `:root` |

Two accessors read the tree without touching the atoms: `(children ctx)` and
`(services ctx)` — the latter is the realm-aware merge of what the ctx can see,
which is what an inspection view needs and what a caller would otherwise
re-derive per name with `provider`.

**The kernel owns its tree.** `kmet.srce.*` must not require
`kmet.libs.scope`: the kernel needs a parent/child walk and a per-fiber effect
list, not a keyed multi-owner store with four views. `kmet.libs.scope` is a
*cell* dependency — it stays until the last registry moves (§9). A guard test
enforces the require ban.

`extend` and `isolate` copy: a parent is never mutated (beyond the `:children`
entry), extra keys are merged (the kernel reads only `:name`), the root-only
keys are dropped, and `isolate` names become this ctx's `:isolate` set. A second
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

- **One provider per name per realm.** `provide!` under a name that already has
  a live provider in the same realm throws `{:type ::duplicate-service}`. A
  second instance is a second realm (`isolate`), which is why `isolate` is in
  the light set and labels are not.
- **`provide!` is an effect.** It registers into the current ctx's store and
  pushes a disposer onto the fiber's effect list; disposer order is the fiber's
  order. A provider's disposer deletes its entry and then refreshes dependents.
- **`check` is a predicate on the value.** `(provide! ctx :ui v {:check ready?})`
  — false or a throw reads as "unavailable" for `get` and for inject, and the
  check is re-evaluated whenever the provider fiber crosses into `:active`.
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
anything but `nil` or `:optional` is `{:type ::bad-inject}` rather than a
silent config. That keeps the door open: intercept comes back as the meaning of
those values, not as a new spelling.

An optional dependency participates in the **provider set** like a required
one: absent contributes `::absent`, present contributes the provider fiber's
`uid`. The set is the epoch, and the epoch is what makes `{:ui :optional}`
"upgrade when a richer UI attaches" ([`new-app.md`](new-app.md), "Pluggable
UI").

### refresh: a fixpoint pass, not an epoch race

The full port needed the epoch to *drop* a stale `_reload` that lost a race on
another thread. The light kernel has one owner thread and one pass flag (§8), so
a provider change runs one synchronous pass that a second thread cannot split:

1. a change (provide, provider dispose, provider state crossing `:active`)
   appends its names to the pass's change set;
2. for each mounted fiber that injects one of those names, compute its provider
   set; unchanged → skip, unsatisfiable → `:pending`, changed → `dispose!` and
   mount again;
3. a fiber is remounted **at most once per pass**; the pass repeats until the
   change set is empty, with a budget of 8 rounds after which it warns and stops
   (`:srce/churn`). A `provide!` issued from a plugin's own `apply` joins the
   pass's change set and is processed after that `apply` returns — a fiber never
   remounts itself.

That is `notify!`, the epoch and the drain queue collapsed into one loop. The
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
  [root]
  (when (compare-and-set! (:pass root) nil :running)
    (try (run-pass! root)
         (finally (reset! (:pass root) nil)))))
```

`provide!`, `dispose!` and the rest record into `:changes` and then call
`settle!`. A caller that loses the flag returns immediately — its change is in
the set, and the winner's next round picks it up. `settle!` is idempotent, so
the app calls it at the turn boundary and after any cross-thread submission:
that is the whole of "drain", in six lines instead of a queue and an awaitable.
`compare-and-set!` on an atom is available on both hosts (checked on bb and
Jolt), so the primitive is portable. A change that lands after the winner's
last round waits for the next `settle!` — which the boundary always performs.

## 4. Fibers and effects

A fiber is one mount of one plugin source. States: **`:pending`** (waiting for
inject), **`:active`**, **`:failed`** (a throwing `apply` or config), and
`:disposed` (the record stays for inspection; the uid stays too — nothing
depends on uid reuse).

- **The root fiber exists**, `:uid 0`, `:active`. It is the owner of anything
  the app registers before any plugin (root tools, scratch test registrations),
  which is why no registration in the light kernel can be fiber-less.
- `plugin!` creates the fiber, its child ctx (named from the plugin's `:name`,
  which is what gives the unit listing a readable path), registers it on the
  source's runtime, adds it to `:mounted`, then activates: unsatisfied →
  `:pending`; satisfied → `:active` and `apply` runs with `(ctx config)`.
- **`apply` is synchronous** and its return value is its first effect: a
  disposer, a seq of them, or nil (see §13 — the one cut that is not
  additive).
- **Effects.** `(effect ctx f)` requires an `:active` fiber (`::inactive-effect`
  otherwise) and returns a single-shot disposer. Within an effect the returned
  disposers unwind LIFO; across the fiber's effects, unwinding is sequential
  LIFO — one effect at a time, and a service that must release before another
  effect does is expressible by effect order. A lazy seq is collected as
  produced. `(effects fiber)` returns the labels in creation order, and the
  label is an **inspection contract** (`:tool/read`, `:command/units`): it is
  what the booted listing renders, so a vague label is a line that says
  nothing.
- **Disposal runs everything and never skips.** A throwing disposer is reported
  through `:logger` at `:error` and the remaining disposers still run.
- **No `:disposing` public state.** A registration under a fiber that is
  already `:disposed` throws `::inactive-effect`; that is the light version of
  the closed-store guard, and it costs one `case`.
- **A failed fiber is a state, not an exception.** `apply` throws → `:failed`
  with `{:error e}`, `:srce/failure` is emitted, dependents stay `:pending`, and
  the plugin is reported through `:logger`.
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
The root fiber's disposal restarts the app tree; it never tears down the
process.

## 5. Events

The kernel needs a notification path, not an event framework:

- `(on ctx name f)`, `(once ctx name f)`, `(emit! ctx name ev {:filter f})`.
- A ctx's listeners run for emits on that ctx **and its ancestors**, so a
  subtree scopes naturally; `:filter` receives each listener's own ctx (a
  listener cannot opt out), which is the shape the node-scoped bus needs.
- Registration order, `once` disposes before invoking, and the disposer reports
  whether it removed something.
- Listener results are ignored; a throwing listener is reported and does not
  stop the rest.

**One listener table, five dispatchers.** Registration is `on`/`once` on the
ctx and nothing else; the *modes* are different walks over that one table —
which is how idea 4 survives the trim with its mechanism intact:

| mode | where | why there |
|---|---|---|
| `emit` | `srce/emit!` | no return value, no await: ~15 lines |
| `bail` / `waterfall` | `kmet.agent.event-bus` | today's `emit-event!` (last non-nil result) and the loop's boundary already have both shapes; landing 2 names them |
| `parallel` / `serial` | `kmet.agent.event-bus` | awaited, so they need the app's awaitable — the kernel has none (§8); landing 2 adds them over the lifted table |

Landing 2 lifts `kmet.agent.event-bus` on top of this table: the `:events`
service owns the five-mode wrappers and the node-scoped `:filter`, so the bus is
a dispatcher, not a second registry. Duplicating the modes in the kernel would
be the copy AGENTS.md forbids; moving the awaited two in would drag an
awaitable back in. The kernel's own event names are three: `:srce/status`,
`:srce/service`, `:srce/failure` — Cordis's nine `internal/*` events collapsed
to the three with a consumer.

## 6. Plugins

**No `IPlugin` protocol.** A plugin is a validated value — Cordis's Base fields
as a map, checked once at mount:

```clojure
{:name    :tool/web-search     ; the runtime name and the mount's ctx name
 :inject  {:tools nil}         ; required, no intercept config
 :config  (fn [cfg] …)         ; optional validator: returns cfg or throws
 :provide #{:web-search}       ; declared, for --dump-config
 :apply   (fn [ctx cfg] …)}    ; the effect body
```

| Cordis | srce |
|---|---|
| `Plugin.Function (ctx, config)` | `:apply (fn [ctx cfg] …)` |
| `Plugin.Object { apply }` | the plugin map |
| `Plugin.Constructor` | not ported — a record is built inside `:apply` |
| `Plugin.Base` (`name`/`Config`/`inject`/`provide`) | the map's keys |
| `Plugin.Transform` (`{schema, Config}`) | `:config` is a validator fn, so `(m/compiled …)` is just a fn |
| `IPlugin` type | `plugin-schema`, checked once (`{:type ::bad-plugin :problems …}`) |
| `registry.resolve` | `srce/plugin-source` — a var as-is; a keyword looked up by the caller's resolver |
| `registry.get`/`.has`/`.delete` | `srce/runtime` / `srce/runtime?` / `srce/dispose-plugin!` |
| `registry.keys`/`.size` | `srce/runtimes` (the inspection surface `--dump-config` reads) |
| `registry.counter` | the root's uid allocator, internal |
| `Inject.resolve` | `srce/normalize-inject` (`:optional` sentinel) |
| plugin *source* polymorphism | the caller's resolver — `kmet.loader.core/Loader` (SCI / Jolt native / classpath) is the one kmet2 passes |

**Source resolution is a seam, not a dependency.** `plugin-source` normalizes
what the caller hands `plugin!`: a var passes through, a keyword is looked up by
the resolver, a miss is `{:type ::bad-source}`. `srce.*` may require
`kmet.loader.*` (rank 0, below the kernel) and `kmet.loader.core/Loader` is the
resolver kmet2 passes, but the kernel never requires it and never decides what
a keyword means — how an extension source is resolved is the extension host's
implementation detail, not a kernel contract.

`defplugin` is sugar over the map: it gives the source var, `:name` metadata, a
compile-time shape check, and the card `--dump-config` prints. A profile row
names the **source**, not a keyword; the generated catalog is a committed
manifest for validation and docs, not a mutable registry.

**One runtime, many fibers.** `plugin!` creates a fresh fiber per call — its own
config, ctx, effects and lifecycle — and records it on the source's runtime.
`inject!` is `plugin!` with `{:inject deps :apply cb}`. A runtime is dropped
when its last fiber unloads, so a later `plugin!` recreates it.

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

- **The kernel has no lock; the pass has a flag.** Every mutation —
  `provide!`, `plugin!`, `dispose!`, `restart!`, `dispose-plugin!`, `on` — must
  run on the thread that owns the root ctx. The one exception is the pass flag
  (§3): a foreign-thread `provide!` is *safe but late*, never corrupting — it
  records and returns, and the owner's next `settle!` (or the turn boundary)
  applies it.
- **The app marshals.** Plugin work, reloads and approvals are submitted to the
  loop; a tool thread that must mutate the kernel asks the loop to do it. This
  is app policy, recorded in [`new-app.md`](new-app.md), and a guard test
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
on it** (a guard test), and its 34 call sites in 9 files go with their
landings (counted from `scope/FN` call sites, not from the requires):

| File | Sites | Go at |
|---|---|---|
| `agent/event_bus.clj` (`in-subtree?`) | 1 | landing 2 — the bus is lifted |
| `agent/registry.clj` | 5 | landing 3 — the registry is parameterized then lifted |
| `agent/tools/registry.clj` | 4 | landing 3 |
| `agent/commands.clj` | 2 | landing 3 |
| `agent/prompts.clj` | 2 | landing 3 |
| `agent/skills.clj` | 2 | landing 3 |
| `agent/extensions/core.cljc` | 12 | landing 4 — the extension host and ABI |
| `agent/app_runtime.clj` (app node, `*error-reporter*`) | 4 | landing 4 — the remaining `runtime/node` reads |
| `agent/session/runtime.clj` (`child-node`) | 2 | landing 4 |

Two more places name it without calling it: `extensions/context.cljc`'s
extension-API allowlist (drop the entry with the ABI at landing 4) and
`test/kmet/test_utils.clj`'s `dispose-owner!` in `clear-app-registries!` (one
call; it moves with whichever registry's clear replaces it).

Each registry becomes a service with its own table, and each contribution is an
`srce/effect` on the contributing fiber — so unloading an extension is
`dispose!` on its fiber and the entries come back LIFO. Deleting the store
before that would rewrite those 34 sites into private cleanup lists, which is
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
have no consumer).

What the app loses is `ordered-registrations` (each registry owns its load
order) and the stale-deregister caveat (a fiber's `:active` check replaces the
value guard). `/units` is not lost: it is re-rendered from the ctx tree and the
runtimes — one flat line per unit, plus the two facts the store could never
show, a fiber's `state` and what it is `waiting-for` — by `kmet.app.inspect`, so
no listing code lives in the kernel ([`new-app.md`](new-app.md), "Inspection").

## 10. Deferred

Recorded so that re-adding one is a lookup. "Additive" means the light surface
grows a keyword or a key, not a new shape.

| Mechanism | Cordis member | Why out | How it comes back |
|---|---|---|---|
| Logger service (facades, exporters, formatters, palette, buffer) | `logger.ts` | kmet already has `kmet.agent.debug`; a second logger is a copy | `:logger` is a service — provide a richer one |
| `intercept` chain | `ctx.intercept`, `[Service.resolveConfig]` | unused by `new-app.md`; labels and realms carry the isolation it was needed for | inject values are reserved: they become intercept config |
| `Service` base / `defservice` | `service.ts` (`:check :invoke :extend :resolve-config`) | `:check` and `:extend` are 20 lines; class ergonomics are JS | a map with `:check` already exists on `provide!` |
| `accessor`, `mixin`, `set-service!` | `reflect.ts` | no proxy to feed; a service re-provides, which is one effect | new keys on `provide!` |
| isolate **labels** | `ctx.isolate(name, label)` | siblings sharing one realm nest under a common isolating ctx | a label argument on `isolate` |
| 9 `internal/*` events, `internal/listener` claiming | `events.ts` | 3 have a consumer (`:srce/status`, `:srce/service`, `:srce/failure`) | new names on the existing emit |
| `parallel` / `serial` / `bail` / `waterfall` | `events.ts` | they are walks over the kernel's one listener table, and kmet1's `event-bus` already has the synchronous emit/collect/boundary shapes | the `:events` service — no kernel change |
| Lazy config resolution, `{:schema :transform}` | `config.ts` | `apply` validates its own map; a validator fn composes | `:config` accepting a schema map |
| Fiber lifecycle beyond 4 states (`:loading` `:unloading`, `inertia`, epoch drop-rule) | `fiber.ts` | one thread makes the race unrepresentable | states on `:state`; the epoch is the provider set |
| `update!` (config waterfall, no-save hint) | `fiber.update` | `restart!` covers `/reload`; a vetoing waterfall is app policy | an `:update` hook on the plugin map |
| `fiber.store` snapshot, `fiber.await`, `assert-active!` | `fiber.ts` | sync: there is nothing to await; `provider` answers the inspection question | derived from `:state` |
| awaitable, drain owner, `drain-await` | (kmet2 addition) | a queue and a thread to serialize what the app already serializes | the optimistic pass flag + `settle!` (§3) are the replacement; an awaitable, if ever needed, is the app's |
| `DisposableList`, `EffectMeta` labels, `utils.ts` traceable | `utils.ts` | a vector and a map cover it | `:label` on `effect` is already accepted |
| JS syntax: `Proxy`, decorators, `instanceof`, prototype splicing | — | not semantics | never |

## 11. Parity matrix

Every public member of the vendored tree, with the light kernel's state. `keep`
= ported in the light kernel, `deferred` = §10, `never` = JS-only. The
conformance suite is written from §3–§7 and this table's `keep` rows.

### context.ts

| Cordis | srce | light |
|---|---|---|
| `new Context()` | `srce/root` | keep |
| `ctx.extend(meta)` | `srce/extend` | keep |
| `ctx.isolate(name, label?)` | `srce/isolate` (no label) | keep |
| `ctx.intercept(name, config)` | — | deferred |
| `ctx.root` | `:root` | keep |
| `ctx.baseUrl` | `:base-url` | keep |
| `ctx.events` / `.logger` / `.reflect` / `.registry` | `:listeners` / `:logger` service / `provider` / `:runtimes` | keep |
| `ctx.fiber` | `:fiber` | keep |
| `Context.is` | `srce/ctx?` | keep |
| `Context.effect` / `filter` / `isolate` / `intercept` symbols | `:srce/effect`, `:filter`, `:isolate` | keep |
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
| `reflect.notify(names, filter?)` | the refresh pass (§3); no `filter` argument | keep (internal) |
| `reflect.props` | — | deferred |
| `reflect.store` | the ctx's `:store` atom | keep (shape differs) |
| `Impl {name, fiber, value, check}` | `{:name :fiber :value :check}` | keep |
| `reflect.trace` / `reflect.bind` | — (explicit `ctx`) | never |
| `internal/get` / `internal/set` waterfalls | — | deferred |

### fiber.ts

| Cordis | srce | light |
|---|---|---|
| `FiberState` (6 states) | `:pending :active :failed :disposed` | keep (reduced) |
| `fiber.uid` / `.name` / `.config` / `.state` | same | keep |
| `fiber.store` / `.inertia` | — (`provider`) | deferred |
| `fiber.ctx` / `.parent` / `.runtime` | `:ctx` / `:parent` / `:runtime` | keep |
| `fiber.effect(body, label)` | `srce/effect` | keep |
| `fiber.getEffects()` | `srce/effects` | keep |
| `fiber.await()` | — | deferred |
| `fiber.restart()` | `srce/restart!` (dispose + mount) | keep |
| `fiber.update(config, noSave)` | — (`restart!`) | deferred |
| `fiber.dispose()` | `srce/dispose!` (sync) | keep |
| `fiber.assertActive()` | `::inactive-effect` on `effect` | keep |
| `{effect: …}` metadata on a disposer | `:label` on `srce/effect` | keep |
| `rootFiber.dispose()` (restarts) | `dispose!` on the root fiber | keep |
| `ValidationError` | `{:type ::bad-inject :problems …}` | keep |
| `CordisError/INACTIVE_EFFECT` | `{:type ::inactive-effect}` | keep |
| `resolveConfig(runtime, config)` | the plugin's `:config` fn | keep |

### registry.ts

| Cordis | srce | light |
|---|---|---|
| `ctx.plugin(plugin, config?)` | `srce/plugin!` | keep |
| `ctx.inject(deps, callback)` | `srce/inject!` | keep |
| `registry.resolve(plugin)` | `srce/plugin-source` | keep |
| `registry.get` / `.has` / `.delete` | `srce/runtime` / `srce/runtime?` / `srce/dispose-plugin!` | keep |
| `registry.keys` / `.values` / `.size` | `srce/runtimes` | keep |
| `registry.counter` | the root's uid allocator | keep (internal) |
| `Plugin.Runtime {name, fibers, callback, Config}` | `{:name :fibers :source :config}` | keep |
| `Inject.resolve` | `srce/normalize-inject` | keep |
| `@Inject` (class) | `:inject` metadata | keep |
| `@Inject` (method) | — | never |
| `Plugin.Function` / `.Object` | one map shape | keep |
| `Plugin.Constructor` | — | never |
| `Plugin.Transform` | `:config` as a validator fn | keep |

### events.ts

| Cordis | srce | light |
|---|---|---|
| `ctx.emit` | `srce/emit!` | keep |
| `ctx.parallel` / `.serial` / `.bail` / `.waterfall` | — (the `:events` service) | deferred |
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
| `[Service.invoke]` (callable) | — | deferred |
| `[Service.extend]` | — | deferred |
| `[Service.resolveConfig]` | — | deferred |
| `logger.ts` (`ctx.logger`, exporters, formatters, palette, buffer) | `srce/log!` → the `:logger` service | deferred |
| `DisposableList` / `EffectMeta` labels | a vector and a map | keep (internal) |
| `isBailed` / `isObject` | `(not (nil? v))` at call sites / `map?` | deferred |
| `getTraceable` / `Tracker` / `withProps` / `createCallable` | — (explicit `ctx`) | never |
| `symbols`, `isConstructor`, `getPropertyDescriptor`, `composeError` | keyword keys, `:srce/*` meta, `ex-info` | never |

## 12. Gates

- ~45 contract tests — one per bullet in §2–§8 that can fail, one per `keep`
  row of §11, table-driven where the rows are uniform. The behaviors: realm
  resolution and the boundary; duplicate provider; `check`; inject
  required/optional and the `::bad-inject` refusal; the refresh fixpoint
  (2-plugin chain, provider swap, absent→present upgrade, churn budget); effect
  LIFO within and across effects; a throwing disposer still runs the rest;
  `::inactive-effect`; `restart!` replaces the uid; `dispose-plugin!` ends every
  fiber and drops the runtime; `:failed` on a throwing `apply` with dependents
  `:pending`; listener order, `once`, `:filter`, and a throwing listener;
  `inside?`; `log!` with no `:logger`; two threads entering `provide!` at once
  run **one** pass and both changes land (the pass flag + `settle!`).
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
| Whether the thread rule needs an assertion in the kernel | no — a portable thread identity does not exist on all three hosts; the app's guard test asserts the submission instead |
| `:config` validator: fn, or a schema map | fn. malli compiles to a fn; the profile layer composes |
| `:name` on a plugin: keyword or symbol | keyword, with the var path as `:source` |

## 14. Not doing

- Porting JavaScript syntax rather than semantics: TS declaration merging,
  decorators, prototype property mixins, the `Proxy`-based context,
  `Symbol.hasInstance`.
- Porting the separate `@cordisjs/plugin-loader` / `plugin-hmr` /
  `plugin-logger-console` packages: profiles/bundles/patches are kmet's loader,
  HMR is deferred until the kernel settles, and the console exporter is
  `kmet.agent.debug`.
- The Koishi plugin market / `package.json` service manifest.
- Re-deriving a mechanism kmet already owns: the five event modes
  (`agent/event-bus`), the logger (`agent/debug`), the loader
  (`loader/core`). The kernel composes them, it does not reimplement them.
- Chasing parity with the vendored tree. §11 is the record; §10 is the
  justification; `keep` rows are the contract.
