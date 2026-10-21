# kmet2 — a Cordis-shaped kmet on shared modules

Temporary note at the project root (the `loop-plan.md` convention). Fold into
[`modules/srce/src/kmet/srce/srce.md`](modules/srce/src/kmet/srce/srce.md)
once the kernel lands, and into
[`modules/agent/src/kmet/README.md`](modules/agent/src/kmet/README.md) /
[`docs/building.md`](docs/building.md) once kmet2 boots.

kmet2 is a second application on kmet's library modules. It replaces
`kmet.agent.*`'s registration model — a process-global store node, sixteen
mutable process globals, and a per-extension capability map — with one Cordis-style
context: services keyed by name, dependencies declared with `inject`,
registrations reversible by construction, and plugins as the only unit of
assembly. Everything below `kmet.agent` is reused unchanged.

The kernel module is **`srce`** — heart (South Slavic). Cordis is Latin for the
same thing (`cor`, `cordis`), which is what the kernel is to the system: the
one thing every other part is wired through. The name also stays clear of
`kmet.kernel`, `--list-kernels` and prose about OS kernels.

## Decisions (locked)

| # | Question | Decision | Consequence |
|---|---|---|---|
| 1 | Where does kmet2 live? | **Same repo**, shared modules | One copy of ~45k library lines; `kmet` and `kmet2` are two entries over one workspace |
| 2 | Does kmet1 keep shipping? | **Yes** | `kmet.agent.*` is untouched; kmet1 is the regression oracle for every kmet2 landing |
| 3 | Server mode scope | **Remote, multi-session, phase 1** | Tokens, per-session ownership, bounded fan-out and idle reaping are phase-1 work, not follow-ups |
| 4 | Hosts | **bb, Jolt, JVM — all portable** | No host-private concurrency or FFI in `kmet.srce.*`, `kmet.app.*`, `kmet.mode.*`; threads/queues go through `kmet.libs.concurrent` and the kernel's awaitables |
| 5 | Second UI provider | **`ui-tk`**, web later | Proves the `:ui` capability contract against an existing backend (`kmet.tk`, Jolt-only) |
| 6 | Tenancy | **Single principal in phase 1** | One bearer token per server, many clients; `:permissions` scoping is user-level control, not isolation. Multi-tenant later = token → principal → child ctx, one level above agents |
| 7 | The kernel's name | **`srce`** — heart; Cordis is Latin for the same | One prefix, `srce/` alias. `kernel`, `kkernel`, `zrno`, `zrnce` rejected |
| 8 | Plugin ABI | **A validated value**, no `IPlugin` protocol | Cordis's Base fields as a map (`:name :inject :config :provide :intercept :apply`; `:reusable?` only if the target Cordis has it) + malli; loading polymorphism stays on `kmet.loader.core/Loader`, and no host class identity crosses the SCI boundary |
| 9 | Shared layer rank | **`app` at 2** (`srce` 1, `ui`/`mode` 3, `agent` 4) | kmet1's rank-4 adapters can call the lifted registries and the new UI; the legacy-above-new inversion is explicit and ends with `agent` |
| 10 | Namespaces on reuse | **Move the file, keep the namespace** | 170 `with-redefs` sites keep intercepting; the final rename is one `s/agent/app/` at retirement |
| 11 | Service keys | **One key per seam**, one implementation per scope | `inject [:tools]`, never `[:registries]`; per-agent overrides stay per-seam |
| 12 | Live agents vs the log | **`:agents`** (handles) + **`:sessions`** (append-only log) | No global "current session" key; an agent is a child context plus a handle |
| 13 | Where the loop lives | **`:agents` registry + `:agentLoop` driver** (dsh's split) | `:agents` is the live registry; the driver provides `:agentLoop`; a preset swaps the loop without touching the registry |
| 14 | Order of reuse | **bus → registry → tools → resources → session → loop → modes** | Each step produces what the next consumes; the first two landings leave kmet1 untouched |
| 15 | Shipping and the entry point | **One artifact, two apps; `--app new` selects kmet2** | `dist` needs no parameterization; the flag is the migration lever, and kmet1 stays the default until it is deleted |
| 16 | Kernel scope | **Full Cordis core port** — context, reflect/services, fiber, registry, events, logger, internal events | Clojure semantics, not JS syntax; the loader/HMR/console packages stay kmet layers; the Cordis conformance suite is the definition of done |

"Remote" in phase 1 means **transport and concurrency, not tenancy**: one
principal per process, many clients, per-agent approval scoping (see
"Permissions and approvals"). Multi-tenant is additive because the seam is
already the shape of everything else — the token resolves to a principal, and
each principal gets a child context under the root, exactly as an agent does.

## What "Cordis architecture" means here

Cordis is the Koishi meta-framework. kmet2 ports its **kernel** — the context,
reflection/service layer, fiber lifecycle, plugin registry, event bus, logger
and internal events — to Clojure with the same observable semantics. The
`@cordisjs/plugin-loader`, `plugin-hmr` and `plugin-logger-console` packages
are separate Cordis packages, not the kernel; kmet's profiles/bundles/patches
are the loader equivalent, HMR is deferred until the kernel settles, and the
logger is a small service.

| Mechanism | Cordis | srce |
|---|---|---|
| Context | side-effect scope; one `ctx` per plugin fiber; `extend`/`isolate`/`intercept` create children | **port** — a `kmet.srce.ctx` value over `kmet.libs.scope`; `extend`/`isolate`/`intercept` are the same operations without the JS prototype |
| Services | name-keyed values resolved through the context chain; `get`/`set`/`provide`/`accessor`/`mixin`; availability `check`; isolation labels | **port** — one registration per seam and isolation label; all of `get`/`set`/`provide`/`accessor`/`mixin`, plus `check` |
| `inject` | array = required services; object = per-service intercept config; pending until satisfied; a changed service rolls dependents back and re-applies | **port** — same; the optional form is a documented kmet2 extension, not a Cordis claim |
| Reversible effects | every `ctx.effect`/`ctx.on`/`ctx.plugin` registration is tracked; LIFO; async disposers awaited; effect metadata tree | **port** — `kmet.libs.scope` is the effect tree; srce adds the per-fiber owner, effect labels and the awaitable wrapper |
| Fiber lifecycle | `PENDING`/`LOADING`/`ACTIVE`/`FAILED`/`UNLOADING`/`DISPOSED`; `inertia`; `await`; `dispose` settles asynchronously | **port** — the same states over a portable Clojure awaitable; sync applies settle immediately, async applies when their awaitable resolves |
| Event dispatch | `emit`/`parallel`/`serial`/`bail`/`waterfall`; `on`/`once`; `prepend`/`global`; context filters | **port** — all five modes, both registration options, and hook-level context filtering; the app bus keeps its node scoping on top |
| Logger | `ctx.logger` service | **port** — a minimal `:logger` service; `kmet.agent.debug` is one sink |
| Internal events | `internal/plugin`, `internal/status`, `internal/service`, `internal/get`, `internal/set`, `internal/update`, `internal/listener`, `internal/dispatch` | **port** — the kernel's observability surface |
| Config | StandardSchema validator, or `{schema: true, Config: fn}` transform | **port** — malli validator, or `{:schema ... :transform fn}` |
| Plugin identity | runtime keyed by the plugin callback; one runtime per callback; fibers per load; `provide`/`intercept` metadata | **port** — runtime keyed by the resolved plugin source; one runtime per source, many fibers per `plugin!`; `dispose-plugin!` ends them all |
| `Service` base class, `@Inject`, TS declaration merging | JS/TS ergonomics | **map to Clojure** — `defservice`, records/protocols, `:inject` metadata; property mixins become `ctx` lookup keys plus the `srce` functions |
| Plugin market | Koishi publishing | **not ported** — `extension.edn` and the extension dirs already do it |

The definition of "full" is Cordis's **core contract**: every public operation
in `cordis`'s context/reflect/fiber/registry/events/logger surface has a
Clojure equivalent with the same observable behavior. It does not mean the
JavaScript syntax or the separate optional packages.

### Prior art: DSH

DeepSeek Harness (`dsh`, MIT, developer preview) is the reference harness built
on Cordis, and five of its decisions are folded in below: the
`:agents`/`:sessions` split (live handles vs the append-only log), the
loop-as-driver that provides `:agentLoop` so modes wait for it,
`--dump-config` (the composed tree with each plugin's state) instead of a flat
list, `setup(child-ctx, agent)` composing an agent's scoped world
before it is published, and the **profile/bundle/patch** composition model —
ordered rows, patches by row id, explicit plugin sources — with vars and
extension ids in place of npm package names, instead of a keyword plugin
registry. What is not taken: web-first (TUI, then `ui-tk`, web
later) and npm as the plugin boundary — kmet's isolated SCI contexts with a
per-extension `deps.edn` are the stronger isolation story, and they already
exist (`modules/agent/src/kmet/agent/extensions/context.cljc`).

### What Cordis and dsh settle

These are no longer open questions. What remains is genuinely kmet-specific.

| Question | Answer |
|---|---|
| `inject` meaning | `inject!` mounts with dependencies; `get` reads. Optional is a kmet2 extension, not Cordis semantics. |
| Event modes | Cordis's five — `emit`, `parallel`, `serial`, `bail`, `waterfall`; `boundary!` is the app's threaded merge. |
| Effect ordering and async | One fiber-owned effect list, LIFO across every effect kind; async disposers awaited. `scope` is the synchronous registration tree. |
| Fiber failures | `:failed` state + `:plugin/failed`; dependents stay pending. |
| Multiple mounts | One runtime per source, many fibers; `dispose-plugin!` ends all. |
| `:agents` vs the loop | `:agents` is the live registry; the loop driver provides `:agentLoop` (dsh's `core/agent` / `core/agent-loop`). |
| Agent creation / teardown | `create!` is serialized and rolls back a failed `setup`; `dispose!` is a loop-coordinated awaitable (dsh's `agent/created` + teardown ordering). |
| Reload boundary | The turn boundary (`turn/end` / `agent/turn-stopping`), exposed as `:agent-settled`. |
| Per-agent capability set | An agent preset (a row set on the child ctx) plus an `isolate` realm for seams that must not inherit. |
| Approvals | `:permissions` policy + `tools/pre-execute` waterfall + one `:approvals` delivery seam. |
| Execution seams | `:workspace`/`:fs`/`:shell`/`:subprocess`/`:sandbox`/`:permissions`, not one `:bash` bundle. |
| Profiles / HMR | Profiles stack bundles and patch by row id; HMR is a loader/profile feature, not the kernel. |
| Plugin sources | Explicit sources in rows; the generated catalog is for validation and docs only. |

Adopted later, when those features land: dsh's `ctx.systemPrompt`
(prompt-section and tool-schema assembly) and `ctx.sessionProjections`
(incremental derived state over the session log).

Still kmet-specific: the `:ui` capability contract, tenancy, the wire format,
EDNL sessions, the extension ABI/SCI boundary, and the kmet1 migration.

## Why the three-way split has to go

kmet today holds plugin state in three disjoint mechanisms, and each of the new
requirements is blocked by a different one:

| Mechanism | Where | Size | Blocks |
|---|---|---|---|
| the store node | `kmet.libs.scope`, resolved through `kmet.agent.runtime/node` | 26 `runtime/node` calls in 9 source files; ~217 `node` mentions across the 11 that require it | multi-session: every session resolves **the one** app node |
| process-global atoms | `modules/agent/src/kmet/agent/extensions/core.cljc` (`extension-binding`, `extensions`, `session-atom`, `context-sink-atom`, `entry-sink-atom`, `hook-seq`, `cli-flag-values`), `bash-executor` (`shell-options`), `tools/read` (`skill-content-resolver`), `keybindings` (`theme-fns-atom`), `run_code` (`bridge-queue`), `runtime` (`app-node`) | 18 `defonce` vars, 16 of them mutable cells | pluggable UI/modes: the UI port and the mode are process state, not per-context state |
| the extension capability map | `kmet.agent.extension` (461 lines) + `modules/agent/src/kmet/agent/extensions/core.cljc` (1,585 lines) built once per `init` | 60 functions | every new service today is another key on that map |

The kernel does not add a fourth mechanism; it replaces all three with one
context, and `kmet.libs.scope` — 288 lines of owner paths, LIFO disposal,
children-first teardown and closed-store guards, already tested — becomes the
effect half of it.

## Layer map

| Rank | Module | kmet2 role |
|---|---|---|
| 0 | `reakt`, `libs`, `loader` | unchanged: reactivity, utilities + the effect tree, plugin-source resolution |
| 1 | `hikap`, `ai` | unchanged: hiccup engine + components; providers/auth/models |
| 1 | **`srce`** | **new**: context, services, plugins, events, config |
| 2 | `tui`, `tk` | unchanged: component backends; become `:ui` providers |
| 2 | **`app`** | **new**: shared services (tools, commands, prompts, skills, events, models, permissions, workspace), the session layer (session, sessions), and the extension host (ABI, loaders, api construction) |
| 3 | **`ui`** | **new**: `kmet.ui.tui`, `kmet.ui.tk`, `kmet.ui.headless` |
| 3 | **`mode`** | **new**: `kmet.mode.tui`, `kmet.mode.print`, `kmet.mode.rpc`, `kmet.mode.server` |
| 4 | `agent` | legacy: kmet1's app; may require `app`/`ui`/`mode`; deleted in one landing |
| — | — | the `kmet2` command (`kmet.app.core/-main`, shipped from `app`): parse args, build the root context, load plugins, run the selected mode |

Ranks are `modules.edn`'s `:rank` guard: **a module may depend only on a
strictly lower rank** (`kmet.test-module-deps`). `srce` at 1 may require
`libs`, `reakt` and `loader`, and must not require `hikap`, `ai`, `tui`, `tk`
or anything above. `ui` and `mode` at 3 may require `app` (2) and the backends
(2), never each other — they meet at the `:ui` service key, which is a keyword,
not a require.

`app` sits at **2** so the lifted registries, tool table, bus, extension host
and session log are below everything that consumes them. `agent` moves to
**4**: it is the legacy app, above `app` (2) and the new `ui`/`mode` (3) only
for the transition, and it is the one module deleted at retirement. That
inversion is deliberate — it is exactly what lets landing 5 re-point kmet1's
dispatch at `kmet.ui.tui` instead of forking 5,292 lines — and it ends with the
module. The cost of `app` at 2 is that rank 2 mixes "UI backends" with
"shared services"; if the shared/version-specific cut turns out to be real once
sessions land, split `app` into `services` (2) and `app` (3) — a subtree move
and one manifest row, made with data instead of a guess.

Two consequences of sharing rather than duplicating: `kmet.app.registry` is
kmet1's registry too, so the contract tests (one set of assertions, two
backends) are mandatory from the first landing; and the shared layer must not
rename the store keys kmet1's `/units` prints — namespaces move, addresses stay
(`[:app :tools]`, `[:app :commands]`, `[:kmet/tool name]`).

New modules are not `kmet.agent` namespaces: kmet1 keeps `kmet.agent.*`,
kmet2 claims `kmet.srce.*`, `kmet.app.*`, `kmet.ui.*`, `kmet.mode.*`. For new
code the prefix is the version boundary, so a kmet1 and a kmet2 namespace can
never shadow each other on one classpath; a lifted file keeps its `kmet.agent.*`
name and moves modules instead (see "Two axes").

Wiring a module touches four manifests that the guard tests hold in step:
`modules.edn`, `bb.edn` `:paths`, `deps.edn` `:jolt/build :embed`, and
`modules/<name>/deps.edn` — plus the `:deps`/`:local/root` edge in the module
that depends on it.

## The kernel

```
modules/srce/src/kmet/
├── srce.clj          — the public API: root/extend/child/isolate/intercept/get/set-service!/provide!/
│                        accessor/mixin/inject!/plugin!/effect/on/once/emit!/parallel!/
│                        serial!/bail!/waterfall!/fiber-await/dispose!/dispose-plugin!
└── srce/
    ├── srce.md       — design doc: the context model, the lifecycle, the Cordis mapping, the rules
    ├── ctx.clj       — context values: parent, children, isolate/intercept maps, config, fiber
    ├── reflect.clj   — get/set/provide/accessor/mixin, the service store, notify
    ├── service.clj   — Service semantics: check, filter, invoke, resolve-config, defservice
    ├── fiber.clj     — lifecycle states, effect tracking, inertia, await, dispose/update
    ├── registry.clj  — plugin runtimes, inject normalization, plugin!/inject!, provide/intercept metadata
    ├── event.clj     — emit/parallel/serial/bail/waterfall, on/once, prepend/global, filters
    ├── logger.clj    — the logger service
    ├── awaitable.clj — the portable awaitable (promise/future/IDeref) used by fibers and disposers
    └── config.clj    — malli validation/transform, layered config
```

There is no `effect.clj`: `kmet.libs.scope` is the synchronous registration
tree (owner paths, LIFO, closed-store guards), used as-is. Moving it up would
break libs' self-containment rule (`kmet.libs.*` may require only
`kmet.libs.*`), and a re-export shim would be a copy of a name rather than of
the behavior. `kmet.srce.ctx` wraps `scope/node` for the store; the fiber adds
the per-fiber effect list, effect labels and the awaitable wrapper on top, and
`scope` is never asked to schedule async work.

Consumers require exactly one namespace — the kernel's surface is Cordis's,
and a second alias in every app file buys nothing:

```clojure
(ns kmet.app.session
  (:require [kmet.srce :as srce]))

(srce/root {:config {:cwd (fs/cwd)}})          ; the process root; kmet1's app-node
(srce/extend ctx {:name :agent/7f3a})          ; a child with metadata
(srce/isolate ctx #{:tools :llm})              ; seams that stop at this child
(srce/intercept ctx :llm {:model "anthropic/claude"})
(srce/get ctx :tools)                          ; or (:tools ctx) through ILookup
(srce/set-service! ctx :tools new-table)      ; only the providing fiber
(srce/provide! ctx :tools table {:check ready?})
(srce/inject! ctx [:tools] handler)            ; Cordis's ctx.inject sugar → fiber
(srce/plugin! ctx web-search-plugin {:max-results 5}) ; → fiber
(srce/effect ctx f)                            ; → disposer
(srce/on ctx :turn-end handler {:prepend true})
(srce/emit! ctx :turn-end ev)                  ; sync, ignores results
(srce/parallel! ctx :turn-end ev)              ; awaits all
(srce/serial! ctx :turn-end ev)                ; awaits until bail
(srce/bail! ctx :tool-call ev)                 ; sync until bail
(srce/waterfall! ctx :tool-call ev next)       ; next-chain
(srce/fiber-await fiber)                       ; settle
(srce/dispose! fiber)                          ; awaitable; runs effects LIFO
(srce/dispose-plugin! web-search-plugin)       ; every fiber of one runtime
```

The alias is derived from the name (`srce/`), as the repo's other requires
are (`scope/`, `models/`, `protocols/`). The subnamespaces are srce
internals; nothing above the kernel requires them directly. This is a
deliberate single public surface, not the "delegating facade" the loop split
forbids: the kernel is one subsystem with one contract, not a set of modules
whose callers each pick their own.

### Rules

- **Full fiber lifecycle, portable awaitables.** Cordis's `inertia`, epochs and
  awaited transitions are ported, not dropped: `apply` may return a disposer,
  an awaitable, a seq of disposers, or nil; a disposer may return an
  awaitable; `fiber-await`/`dispose!` settle when the transition finishes. The
  scheduler is still a drain to fixpoint — `provide!`/`release!` mutates the
  service map and enqueues a pass — but a pass starts each fiber transition
  and awaits it, and a per-fiber epoch keeps a stale apply from landing after
  a newer transition. The awaitable is `kmet.srce.awaitable` (a
  `promise`/future/`IDeref` with a timeout, or a one-slot queue on a host
  without promises), not a JS promise.
- **Quiesce at the turn boundary.** Scope-level disposal never waits
  (`scope.md` §3, §7); the *fiber* disposal is an awaitable, and the loop
  awaits the kernel's queue at the turn's closing boundary — dsh's `turn/end`
  / `agent/turn-stopping` (serial, no `next`), which kmet exposes as
  `:agent-settled`. A tool swapped between turns reaches the next attempt and
  never the one in flight — the rule `scope.md` §4 states for loop stages,
  applied to services. One owner per concern: `:agentLoop` owns the turn and
  the queued reload (`request-reload!`), `:agents` answers idle
  (`:agents/idle?`), and `/reload` with no agent mid-turn drains immediately.
- **No global node.** `kmet.agent.runtime/node`'s job is done by an explicit
  `ctx` argument. Every `kmet.app.*` function takes `ctx` (or a bare node for
  store-only calls); no arity resolves the root. A guard test bans
  `runtime/node` in `kmet.srce.*`, `kmet.app.*`, `kmet.ui.*`, `kmet.mode.*`;
  the explicit `node` threading migrates in landings 2–4. kmet1's call sites
  pass `(runtime/node)`; once its boot creates the root ctx, that value *is*
  the root ctx, so the call sites do not change again.
- **One implementation per facility.** The effect tree is `kmet.libs.scope`,
  used as-is — not copied, not re-exported (AGENTS.md "Shared layers are
  extended, not copied"). The kernel adds the per-fiber owner, effect labels
  and the awaitable wrapper; it never reimplements owner paths or LIFO
  disposal.
- **A plugin's effects are its return value.** `apply` returns a disposer, an
  awaitable, a seq of disposers, or nil; a `shutdown` fn is accepted sugar.
  Effect bodies may be sync or awaitable; the fiber runs registrations LIFO,
  then the awaitable teardowns, and reports failures through `:logger`.
- **Failures are states, not silence.** A throwing `apply` or effect marks the
  fiber `:failed` with `{:error e}`, emits `:plugin/failed`, leaves dependents
  pending, and is reported through `:logger`; `scope/*error-reporter*` keeps
  its semantics.
- **Cordis parity is the contract.** `srce.md` maps every public Cordis
  operation to its srce equivalent, and the conformance suite mirrors Cordis's
  tests: lifecycle transitions, inject (array/object/optional), service
  `check`/`set`/`isolate`/`intercept`, effect LIFO with async disposers, all
  five event modes with `prepend`/`once`, and the internal events.

## Plugin ABI

**No `IPlugin` protocol.** Cordis's `IPlugin` is a TypeScript union
(`Function | Constructor | Object`) that exists because a JS class must be
loadable as a plugin. A Clojure function is a value, a record is constructed
*by* `apply`, so the union collapses to one shape; a protocol would add a
vtable with one implementation, and would put host class identity on the SCI
boundary the extension loader exists to keep clean. The plugin is a **validated
value**:

```clojure
(def plugin-schema
  [:map
   [:name      keyword?]
   [:inject    {:optional true} [:maybe inject-schema]]   ; [:tools] or {:tools intercept-config}
   [:config    {:optional true} [:maybe :any]]             ; a malli schema, or {:schema ... :transform fn}
   [:provide   {:optional true} [:maybe [:set keyword?]]]
   [:intercept {:optional true} [:maybe :map]]
   [:apply     fn?]
   [:reusable? {:optional true} boolean?]])                ; only if the target Cordis has it
```

| Cordis | kmet2 |
|---|---|
| `Plugin.Function (ctx, config)` | `:apply (fn [ctx cfg] …)` |
| `Plugin.Object { apply }` | the plugin map |
| `Plugin.Constructor` (`Service` subclass) | `(defrecord S …)` built inside `:apply` — never the plugin |
| `Plugin.Base` (`name`/`Config`/`inject`/`provide`/`intercept`; `reusable` only if the target version has it) | the map's keys; `:config` is a malli schema or transform |
| `IPlugin` type | `plugin-schema`, checked once at load (`{:type ::bad-plugin :problems …}`) |
| plugin identity / registry | runtime keyed by the resolved plugin source; one runtime per source, one fiber per `plugin!`; `dispose-plugin!` ends every fiber of a runtime |
| plugin *source* polymorphism | `kmet.loader.core/Loader` protocol — reused as-is (SCI / Jolt native / classpath) |

`defservice` is the Clojure `Service` base: a record/map registered from its
constructor with `:name`, `:check`, `:invoke`, `:filter` and `:resolve-config`;
it is sugar over `defrecord` + `provide!`, not a class hierarchy.

`defplugin` is sugar over the map, not an interface — the same trade
`kmet.tui.macros/defcomponent` makes, and the shape the extension docs already
teach:

```clojure
(defplugin web-search
  {:name    :tool/web-search
   :inject  {:tools {:required true}}
   :config  [:map [:max-results [:int {:min 1}]]]
   :apply   (fn [ctx {:keys [max-results]}] …)})
```

It gives a named var (`web-search`, the source), a compile-time shape check,
`:name` metadata, and a card in `kmet2 --dump-config` — the composed plugin
tree with each plugin's state and resolved config, which is the drain made
visible, and the first thing to read when a plugin sits in `:pending`.

**Plugin sources are explicit; ids are for identity, not resolution.**
`defplugin` defines the plugin var (the source) and records its metadata
(`:name` as the id, `:inject`, the config schema). A profile row names the
source — `kmet.app.tools.bash/plugin` for a built-in, `:ext/grep-tool` for an
extension the host resolves — and the boot resolves that source, never a
keyword. A **generated catalog** lists the known built-in ids for validation,
docs and the `--dump-config` cards; it is a committed manifest checked the way
the bundled-extensions manifest (`kmet/bundled-extensions.edn`) is, not a
mutable registry. This is dsh's `cordis.yml` row model: `:id` identifies the
row, the source names what to mount. An unknown source fails at load with
`{:type ::unknown-plugin :source …}`.

**One runtime, many fibers.** A plugin source is mounted any number of times:
`plugin!` creates a fresh fiber each call — its own config, inject resolution,
ctx, effects and lifecycle — and records it on that source's runtime, exactly
as Cordis does. `dispose!` ends one fiber; `dispose-plugin!` ends every fiber of
a source and drops the runtime. “Multiple places” means different parent
contexts: a root mount and an agent-child mount share the runtime but see
different services, and a profile's `:per-agent true` extension is the concrete
case. A plugin that *provides* a service cannot be mounted twice in the same
isolation scope — the second `provide` fails, as in Cordis; use `isolate` for a
second service instance. `:reusable?` exists only if the target Cordis version
has it; the vendored DSH copy does not, so multiple mounts are allowed by
default.

**Extensions become plugins additively.** `extension.edn` gains an optional
`:inject` (the same schema); the extension host wraps the extension as

```clojure
{:name   (keyword ext-name)
 :inject (:inject manifest)
 :apply  (fn [ctx cfg] (init (api-for ctx)))}
```

Without `:inject` an extension behaves exactly as today — no waiting, no
reload-on-change — so no shipped or installed extension needs a source change,
because `api` was always a map. With it, the extension gets pending/activate/
reload semantics for free, and its owner path still disposes on unload.

**The host moves with the ABI.** The plugin host is not just
`kmet.agent.extension`; it is `kmet.agent.extensions.core` (1,585) +
`kmet.agent.extensions.context` (1,567), and `create-extension-api` closes over
the app node and ~40 registrations. Both host files and the zero-dependency ABI
move into module `app` at rank 2 **keeping their namespaces** (the "Two axes"
rule):

- `kmet.agent.extension` has no `kmet.*` requires, so the file move is
  mechanical; installed extensions keep `(require [kmet.agent.extension …])`,
  and the loader's name whitelist (`kmet.agent.extension` + `kmet.libs.*` +
  `kmet.tui.*`) is unchanged.
- The host takes `ctx` instead of `(app-runtime/node)` and builds the api for
  the root or an agent context. kmet1 (`agent`, rank 4) may require the host
  (rank 2), and an extension's own `:require` resolves by name through the
  loader.
- A **key-set contract test** asserts every `(:key api)` the ABI wrappers call
  is present in kmet2's api map; a shipped directory extension and a
  single-file extension load on both hosts. `grep-tool` alone only proves the
  single-file path.
- `shutdown` composes into the disposer: the host calls `shutdown` (as
  `unload-extension!` does today) and then disposes the fiber's registrations.
  A plugin `:apply` returning a disposer does not replace `shutdown`.

This is landing-4 work, after the registries, tools and session log it builds
the api from have been lifted.

## Service catalog

This table is the application — the reason the architecture is worth the
rewrite.

| Service | Contents | Today |
|---|---|---|
| `:llm` `:models` | provider call, model registry, auth status | `kmet.ai.*` |
| `:tools` `:commands` `:prompts` `:skills` `:modes` | registries: name → value, per-context | `agent.registry` + 4 namespaces |
| `:agents` | live handles: `create!` / `resume!` / `get` / `list` / `roots`; a handle owns one agent, its session and its child context | `session/runtime.clj`'s `Engine` — already a handle |
| `:agentLoop` | the driver: run a turn, abort, teardown ordering | the loop driver plugin (`loop*`) |
| `:sessions` | the append-only session log and its persistence; storage backends sit behind it | `session/core.clj` (1.7k) |
| `:events` `:hooks` | three event domains (session/agent/capability) + 5 dispatch modes + hook chains | `event-bus.clj`, `apply-input-hooks` |
| `:ui` | capability map: render, input, dialogs, notify, widget set | `extension-binding :ui`, lifted out |
| `:approvals` | the one delivery seam for `:permissions :ask`: dialog, wire request/response, or headless default | new |
| `:workspace` `:fs` `:shell` `:subprocess` `:sandbox` `:permissions` | cwd/project identity, filesystem, shell, process groups, sandbox policy, approval policy | `tools/bash`, `bash-executor`, `libs/path`, `tools/read/write/edit` |
| `:config` `:auth` | layered settings, credentials | `config.clj`, `ai/auth.clj` |

**One key per seam, one implementation per scope.** Plugins write
`inject [:tools]`, never `[:registries]`. The registries share one machine
(`kmet.app.registry`: tag + name, 78 lines) but stay separate services, because
their consumers differ — the model sees `:tools`, the slash parser `:commands`,
the prompt builder `:prompts` — and because a per-agent override should be
per-seam rather than all-or-nothing. Cordis's `ctx.tools` / `ctx.llm` /
`ctx.sandbox` is the precedent.

**`:agents` is the registry; the loop is a driver.** dsh's split, adopted:
`core/agent` owns `:agents` (live handles + `agent/*` events) and
`core/agent-loop` owns `:agentLoop` (the driver implementing the interface).
`:agents` is always present; the loop plugin provides `:agentLoop`, so
`inject [:agentLoop]` holds mode/creation callers pending until a driver is
mounted, and a preset that swaps the loop swaps one plugin. `check` covers a
service that becomes unready later.

**No runtime schemas on service values.** A service value is mostly a map of
fns, so a malli schema can only assert `fn?`; the catalog entry plus one
contract test per service is the check that pays. Plugin *config* is still
schema-validated at load.

## Permissions and approvals

Approvals are a **policy plus one delivery seam**, not two request APIs (dsh's
sandbox/approval split). `:permissions` decides; the front end that can ask
provides `:approvals`; the loop's blocking point is the `tools/pre-execute`
waterfall (kmet1's `before-tool-call` chain, ported), whose result is already
`{:block true :reason …}`:

```clojure
(srce/provide! ctx :permissions
  {:policy    (fn [ctx tool-call] :allow | :deny | :ask)  ; pure, per-agent
   :remember! (fn [ctx tool-call scope] …)})             ; :once | :agent | :workspace

(srce/provide! ctx :approvals
  {:ask! (fn [ctx request] …)})                          ; one delivery seam
```

- `:policy` is synchronous and per-agent (`setup` can shadow it), so the common
  case costs nothing.
- `:ask` goes to `:approvals/ask!`, which blocks with a timeout and is abortable
  through the run's `:signal`. The TUI provides `:approvals` with a dialog, the
  server answers later over the wire (the `:request`/`:response` pair), and
  headless answers with its configured default. `:ui/request!` is for generic
  dialogs, not a second approvals channel.
- `:remember!` records a decision at one of three scopes — that is what makes
  remote work without a prompt per call.
- A `:deny` becomes the waterfall result the loop already understands, so **no
  tool signature changes** anywhere.

## Pluggable UI

`:ui` is a **capability service**, not an inherited widget set. Apps declare
what they need; providers supply what they can. Required of every provider
(headless included): `:kind`, `:render-tree`, `:request!`, `:capabilities`;
everything else is optional and declared in `:capabilities`:

```clojure
{:name :ui/terminal
 :apply (fn [ctx _]
          (srce/provide! ctx :ui
            {:kind        :tui
             :render-tree (fn [tree] …)             ; hikap tree → screen
             :widget-set  {:text … :input … :select-list … :markdown …}
             :input!      (fn [ctx ev] …)
             :request!    (fn [ctx req] …)           ; generic dialogs; approvals use :approvals
             :notify      …
             :capabilities #{:chat-history :footer :status :images}}))}
```

Approvals are not a UI capability: the mode (or headless) provides
`:approvals`, and `:request!` is for generic dialogs.

**The widget set is where portability lives.** App UI builds structure from
`:widget-set` constructors, each backend implementing them with its own
toolkit; anything that needs a widget only one toolkit has stays inside that
provider's namespace. That is the rule that keeps landing 5 a move rather than
a rewrite — `kmet.ui.tui` may keep TUI-only chrome, and only the structural
parts are extracted into shared code.

Today's providers, and the one that proves the contract is worth having:

1. `:ui/headless` — the capability map with headless defaults (already exists:
   `kmet.agent.extensions.core/headless-ui-port`).
2. `:ui/terminal` — components over `kmet.libs.hikap.core`, which already
   takes its backend map as an argument.
3. `:ui/tk` — the same contract, second backend (Jolt).
4. `:ui/web` — later; a third backend, no app change.

App plugins declare `:inject {:ui {:optional true}}` (or use
`(srce/inject! ctx {:ui {:optional true}} …)`) and upgrade themselves when a
richer UI attaches — the optional form is a kmet2 extension of Cordis's
`inject`. The distinction to document once, on the first line of
each namespace: **`kmet.tui` renders components; `kmet.ui` decides which
backend renders.**

The cost is honest: `modes/interactive/*` is 5,292 lines (`layout.clj` 918,
`ui_registry.clj` 708) written against `kmet.tui`. Landing 5 moves it wholesale
into `kmet.ui.tui` and extracts capabilities incrementally; it does not rewrite
it as view-model + renderer.

## Pluggable modes

```clojure
{:name :mode/rpc
 :inject [:agents]
 :apply (fn [ctx cfg] (serve-stdio ctx cfg))}     ; returns the disposer
```

- A mode is a profile: kmet2's flag is `--profile` (`tui`, `print`, `rpc`,
  `server`), and the profile's rows include the mode plugin, so its `apply`
  starts serving only when that profile boots. `--mode <name>` is the kmet1
  compatibility alias (`--mode rpc` → `--profile rpc`) for one release; it is
  not a `require` in `kmet.agent.cli` (which hard-requires all three today).
- Third-party modes ship as extensions: the loader already isolates them, the
  contract already exists, and a profile row resolves their `:ext/…` source.
  `:modes` is the listing registry the loaded mode plugins fill, not the
  selector.
- `mode-tui` = interactive, `mode-print` = one-shot, `mode-rpc` = stdio,
  `mode-server` = socket — all four drive the same `:agents` service; they
  already share `bootstrap`.
- `test_headless_guard`'s rule (print/rpc reach no interactive/TUI/Tk
  namespace) becomes a plugin-graph assertion: a headless mode's `inject` set
  contains no `:ui` provider.

## Multi-session server mode

**An agent is a child context + a handle.** The `Engine` record already holds
per-agent state (`agent-state`, `session`, `subscribers`, `:cwd` atom,
`turn-running?`); what is missing is that its *registrations* are global. In
kmet2 each agent is a child ctx, so its model, permission profile, tool set,
skills and extension set are service overrides — no new mechanism. Two keys,
borrowed from DSH: `:agents` is the live registry, `:sessions` the append-only
log.

The `:agents` service value:

```clojure
(agents/create! ctx {:cwd … :model … :session-id …
                     :setup (fn [agent-ctx agent] …)})  ; → handle
(agents/resume! ctx id)                                ; rebuild from the log
(agents/list ctx)   (agents/get ctx id)   (agents/roots ctx)
(agents/dispose! handle)                               ; loop-coordinated awaitable
```

`setup` runs before the handle is published: it composes the agent's scoped
world (its tools, prompt sections, listeners) on its own context, so nothing
observes a half-built agent — dsh's `setup(agentCtx, agent)` contract. Creation
is serialized (dsh's `agent/created`): a failed `setup` rolls the child ctx
back and leaves no handle. The loop driver provides `:agentLoop`; `:agents` is
the registry and exists independently.

**Teardown is loop-coordinated.** `dispose!` aborts the live turn, drains the
loop's queue at the turn boundary, then releases the child context; it returns
an awaitable, and no caller observes a half-disposed agent. The loop owns the
ordering (dsh: "unload retains resources through Agent teardown"); the scope
does only the final LIFO release. Idle reaping uses the same path.

**Agent scope: inherit by default, mutate explicitly.** `:agents/create!` loads
no plugins; the agent's child ctx inherits root services through the chain, and
per-agent composition happens in `setup` (a shadowed `:tools`, `:llm`,
`:permissions`), by applying an **agent preset** (a row set on the child ctx),
or by loading a plugin a profile marks `:per-agent true`; a seam that must not
inherit uses an `isolate` realm. Root plugins are mounted once and inherited by
the child ctx; a `:per-agent true` plugin is mounted again on each agent ctx —
a second fiber of the same source — which is the multi-mount case. Isolation is
by context, not by process, so two agents hold different tool tables for free.

`:sessions` is a facade now and a seam by declaration: its value is
`{:append! :read :list :delete :header}` over the existing EDNL files, so a
second backend is a plugin that shadows `:sessions` — no protocol, no
interface.

**Transport is a plugin; `rpc` is a degenerate `server`.** `docs/rpc.md`
already defines line-delimited `{:type … :id …}` EDN records with explicit
correlation. The correlation key becomes **`:agent-id`** (`:session-id` is
accepted as an alias for one release), the multi-agent surface adds
`:agent-create` / `:agent-list` / `:agent-attach` / `:agent-dispose`, and one
`:request`/`:response` pair answers approvals and dialogs (the channel from
"Permissions and approvals"). `--profile rpc` is `mode-server` with one
implicit agent on stdio (`--mode rpc` is the kmet1 alias); transports are a
unix socket where the host has one, TCP loopback otherwise. One protocol, one
implementation, no drift.

**Fan-out backpressure is the new engineering.** `event_bus` runs subscribers
on the emitting thread and requires them not to block; a stalled socket client
would stall the agent. Each client gets a bounded queue, **`:lag` by default**:
drop oldest, emit `{:type :stream-gap :dropped n}`, and let the client resync
with `:get-state`. Dropping the newest would corrupt message assembly silently,
and "the event stream is the API" means a gap has to be visible. The attach
record may override the policy per client.

**Process-state audit before claiming multi-session** — these are the
difference between "two sessions" and "two isolated sessions":

| State | Where | Fix |
|---|---|---|
| `shell-options` | `bash_executor.clj` `defonce` | per-context `:shell`/`:subprocess` service value |
| `System/getProperty "user.dir"` fallback | `libs/path.clj` | fall back to the context's `:workspace` cwd |
| `cli-flag-values`, `extension-binding`, `extensions` | `modules/agent/src/kmet/agent/extensions/core.cljc` | registrations on the ctx; the atoms become the no-node read cache and then die |
| `bridge-queue` | `tools/run_code.cljc` | per-context |
| `skill-content-resolver`, `theme-fns-atom` | `tools/read`, `keybindings` | per-context services |

Already per-session and safe: `Engine/:cwd`, tool `:cwd` resolution,
`subscribe!` delivery, session files (EDNL on disk).

**Phase-1 remote scope:** token auth (bearer, one principal), a unix socket
where the host has one and TCP loopback otherwise, `--max-sessions`,
per-client queue depth, idle reaping on
`kmet.libs.concurrent/monotonic-ms`, and resume-by-id (the session store
already supports it). Durability: sessions are already EDN files; the server
adds no new store.

## Reusing `kmet.agent`

### The measurement

`kmet.agent` is 104 source files and 39,802 lines. Exactly 20 touch process
state; the other 84 are already pure functions of their arguments.

| Bucket | Pattern | Files | Lines | Members |
|---|---|---|---|---|
| needs nothing | lift as-is | 84 | 28,178 | tool bodies (`write`/`edit`), `session/core.clj` (1.7k), the `event-types` vocabulary |
| one argument | parameterize | 7 | 4,076 | `registry` (3 `runtime/node` reads), `commands/builtins` (6), `modes/interactive/commands` (6), `session/runtime` (3), `loop/state` (2), `core` (1), `modes/interactive` (1) |
| registry-wired | re-point | 4 | 1,305 | `tools/registry`, `skills`, `prompts`, `commands` |
| owns state | re-host | 9 | 6,243 | `kmet.agent.extensions.core` (7 `defonce`s), `debug` (3), `kmet.agent.extensions.context` (2), `bash_executor`, `event_bus`, `keybindings`, `runtime`, `tools/read`, `tools/run_code` (1 each) |

The fourth row is the only real re-hosting: 6,243 of 39,802 lines (~16%), plus
another ~14% in the one-argument and registry-wired rows. It is the part that
owns state — precisely what the context replaces. The bus is the template:
roughly two-thirds of `event-bus.clj` is the `event-types` vocabulary (pure
data, lifts as-is), and the mechanism is one atom plus the three emitters
(`emit`, collect, boundary).

### Order

**bus → registry → tools → commands/prompts/skills → session → extension
host → loop → modes/UI.** Each step produces what the next consumes:

- the bus is one atom whose subscriptions carry an owner and a scope but no
  disposal ownership, and `emit-event!` keeps its signature, so kmet1 cannot
  notice; it also unblocks the rest, because `srce/on` is how a plugin
  registers anything;
- `registry` (78 lines) decides the ctx-addressed lookup shape for all four
  registries at once;
- `tools` is the first real service, and its shape is copied by the next three
  — their handlers already take a ctx-shaped map `{:runtime :ui :frontend}`, so
  that step is mostly renaming keys;
- `Engine` is already a record with operations, so `session` is a lift rather
  than a fork; only its singletons are re-hosted;
- the extension host moves with the session lift: it consumes the registries,
  the tool table, the session log and the config/debug services, so it lands
  with `Engine` (landing 4), not before;
- loop and modes last, once the kernel has absorbed everything else.

A landing is done when kmet2 uses the new mechanism, kmet1 still uses the old
one, and one test holds both to the same assertions.

### Two axes: the module move and the namespace

Move the file; keep the namespace.

| Axis | Affects | When |
|---|---|---|
| module / classpath root | ranks, embed, packaging | at lift time |
| namespace name | call sites, `with-redefs` interception, the extension ABI | once, at retirement |

The repo already separates them: `reakt` holds `kmet.libs.reakt.*` and `hikap`
holds `kmet.libs.hikap.*`. A module is a classpath root; the namespace is a
name. The one rule is that the path below the root matches the namespace, so
`modules/app/src/kmet/agent/registry.clj` is a `kmet.agent.registry` that
simply lives in the `app` module. Tests move the same way and keep their
namespace (`kmet.tasks.modules/ns-symbol` derives it from the path).

Renaming at lift time is the expensive option: `modules/agent/test` holds 354
`with-redefs` sites, **170 of them on `kmet.agent.config`** — the first clean
file anyone would lift. A rename owes a wrapper at the old name exposing
*every* var kmet1 uses, and that wrapper still cannot intercept calls made
*inside* the lifted code. Keeping the namespace keeps every interception point
valid (`loop-plan.md`'s rule, read backwards), and it keeps the endgame
mechanical: at retirement, `s/kmet\.agent\./kmet.app./` over a known list that
excludes `kmet.agent.extension` (the ABI), the deleted `kmet.agent.runtime`,
and the names re-hosted under `kmet.ui.*`/`kmet.mode.*`.

**Three exceptions.** `kmet.agent.extension` keeps its name forever — it is
the extension ABI, and installed extensions write it in their `:require`; if
kmet2 ever wants `kmet.extension`, add it as an alias and keep the old name
for a release. The file moves into `app` with the extension host; the
namespace does not change. Namespaces whose concept dies are deleted, not moved
(`kmet.agent.runtime` has no successor). `loop*`, `session/runtime` and
`modes/interactive/*` are re-hosted under new names; with `agent` at 4 kmet1
re-points at them instead of keeping a copy — `modes/interactive/*` becomes
`kmet.ui.tui` at landing 5 behind the `--mode` shim. New kmet2-only code uses
the new prefixes from day one.

| Now | At retirement |
|---|---|
| `kmet.agent.registry`, `.tools.registry`, `.event-bus`, `.config`, `.skills`, `.prompts`, `.commands` | `kmet.app.*` — one prefix substitution |
| `kmet.agent.extension` | unchanged — the ABI (the file moves to `app`, the name stays) |
| `kmet.agent.runtime` | deleted |
| `kmet.agent.loop*`, `session/runtime`, `modes/interactive/*` | new `kmet.app.*` / `kmet.ui.tui` |

### Per-lift checklist

1. **Remove the upward require.** `registry.clj` requires
   `kmet.agent.runtime` for `node`; while that edge exists the file cannot
   live at rank 2. Parameterize it to take `ctx`/`node` and drop any arity that
   resolves the root — *that commit is the extraction*. kmet1's call sites pass
   `(runtime/node)`; a root-resolving wrapper, if one is needed, lives at rank
   3, not in `app`.
2. Lift in reverse intra-module require order; compute it once with
   `kmet.tasks.changed/read-ns-form` + `ns-requires` — it is the only valid
   sequence.
3. `git mv` the file and its test; touch nothing else.
4. Wire the module into the four manifests (`modules.edn`, `bb.edn` `:paths`,
   `deps.edn` `:jolt/build :embed`, and `modules/<name>/deps.edn`), the
   `:deps`/`:local/root` edge in the parent module's `deps.edn`, and the three
   extension `bb.edn`s — `kmet.test-modules-manifest` names whichever you
   missed.
5. Run the changed-file gates and the module-based boundary guard.
6. Add the ledger line.

### Instruments

- **Contract tests, parameterized over both backends** — one assertion set run
  against the store-backed registry and the ctx-backed one, against
  `emit-event!` and `srce/emit!`. Mandatory, because `kmet.app.registry` is
  kmet1's registry too; it is what makes "kmet1 stays green while the
  mechanism is replaced" a claim rather than a hope.
- **A reuse ledger**, per landing: lines *lifted* / *adapted* / *re-hosted*. If
  re-hosted outgrows lifted for two landings running, stop — it usually means
  a state model is being ported instead of a mechanism.

## Profiles and presets

A composition is EDN data, not code, and it is a **row list plus patches** —
the shape dsh's `cordis.yml` + `cordis.patch.yml` uses, with Clojure forms
where Node uses package names:

```clojure
;; a bundle: reusable rows + patches (dsh: dsh-base)
{:bundle/minimal
 {:rows  [{:id :tool/bash :plugin kmet.app.tools.bash/plugin :config {}}
          {:id :tool/edit :plugin kmet.app.tools.edit/plugin :config {}}
          {:id :agent-loop :plugin kmet.app.loop/plugin
           :config {:max-turns 50}}
          {:id :permissions :plugin kmet.app.permissions/policy}]
  :patch [{:id :permissions :config {:policy :allow-all}}]}}

;; a profile: the app entry, stacking bundles (dsh: web / headless / sdk / acp)
{:profile/rpc
 {:bundles [[:bundle/minimal {}]]
  :rows    [{:id :mode/rpc :plugin kmet.mode.rpc/plugin}]}}
```

- A row's `:plugin` is the **source**: a var (`kmet.app.tools.bash/plugin`) for
  a built-in, or `:ext/grep-tool` for an extension the host resolves. `:id` is
  the row identity `--dump-config` prints and a patch targets; it defaults to
  the plugin's `:name`.
- A **bundle** is a reusable row list + patches — the analog of dsh's
  `dsh.bundle`; a profile stacks bundles in order, then its own rows and
  patches. Presets (Standard / Minimal / PTC) are bundles.
- Patches are per row: replace a row's config by `:id`, insert a row, or remove
  one. Layers: bundled default < `--profile` < the agent dir's `patch.edn`
  < `--patch <file>`; config *values* still deep-merge as `settings.edn` does,
  composition does not.
- Profiles and bundles are EDN staged beside the binary the way
  `target/kmet-bundled` is, so a project can ship its own; a profile under the
  agent dir can name out-of-tree plugins the way dsh's Harness home does.

## One artifact, two apps

`dist` keeps shipping one artifact per host (`kmet` / `kmetj` / `kmetc`), and
that artifact carries **both** applications — which it must, because landings
2–4 make kmet1 call the lifted `kmet.app.*` code. The entry point selects:

```sh
kmet --app new                  # kmet2 (development)
kmet --app new --profile print "…"
bb start --app new              # the same switch from a checkout
kmet                            # kmet1, the default, unchanged
```

- The artifact's entry stays `kmet.agent.core` (`main-class` is unchanged in
  `build.cljc` and `build_jvm.clj`), but that namespace becomes a **bootstrap**
  with no app requires; kmet1's `-main` moves to `kmet.agent.cli`. The
  bootstrap parses `--app` (`new` | `old`, `old` the default), strips the flag
  from the argv, and hands off dynamically — never a static require — to
  `(requiring-resolve 'kmet.agent.cli/-main)` or
  `(requiring-resolve 'kmet.app.core/-main)`. Neither app pays the other's load
  time, and a missing `kmet.app.core` (a `--dev` build, an older artifact)
  answers "this artifact carries only the kmet app" instead of a null
  dereference.
- `bb start` stays the only start task and keeps requiring `kmet.agent.core`
  (now the bootstrap); a `start2` would be a second entry point to keep in
  sync. If the typing grates, it is one line:
  `:task (apply core/-main "--app" "new" *command-line-args*)`.
- `bb check` requires both app entries (`[kmet.agent.cli]`,
  `[kmet.app.core]`) in its task body, because the dispatch is dynamic and the
  load is the assertion; its `:requires` names the bootstrap only.
- `dist` needs no parameterization: `main-class` stays `kmet.agent.core`, the
  embed list is already every module's `src`, and the artifact names describe
  the host, not the app.
- The one smoke test runs **both** entries (`--app old --version`,
  `--app new --version`), because the dynamic hand-off is the part packaging
  can break. It is still one test, not a per-app test.

What this deletes: `--prefix`, a second `:jolt/build :embed` list, a
`kmet2j`/`kmet2c` naming scheme, and a second smoke test (the one test covers
both entries). What it keeps: one version string, one artifact, one PATH
entry. When kmet1 is retired, `--app new` becomes the default and the flag is
dropped in the same release — the flag *is* the migration lever.

## Landings

Each is independently shippable and gated.

| # | Landing | Ships |
|---|---|---|
| 0 | `modules/srce`: the full Cordis core — `ctx`/`reflect`/`service`/`fiber`/`registry`/`event`/`logger`/`awaitable`/`config` | srce unit tests + the Cordis conformance suite; no `kmet.agent.*` require |
| 1 | module wiring + guards (module-based require, no-global-node, drain), `--app new` dispatch (bootstrap split: `kmet.agent.core` + `kmet.agent.cli`) + `check` requiring both entries, `defplugin` + the generated catalog, `--dump-config` | `kmet.test-module-deps` / `test-modules-manifest` green; each guard fails on a deliberate violation |
| 2 | the bus: listener table into the runtime; `emit-event!` keeps its signature | contract test over both delivery paths; kmet1 green |
| 3 | `registry` parameterized then lifted; `:tools` service; commands/prompts/skills re-pointed | contract tests over both backends; kmet1 green |
| 4 | `Engine` → `:agents` (+ `:permissions` policy/request!/remember!) and one `:sessions` facade; the extension host + ABI moved into `app` (key-set contract test); `extension.edn :inject` + `grep-tool` ported as a plugin; the remaining `runtime/node` reads; the globals re-hosted | kmet2 runs `mode-print`; a shipped directory extension and `grep-tool` green; kmet1 untouched |
| 5 | `:ui` service (required/optional capabilities, widget set) + `ui-headless` + `ui-terminal` + `ui-tk`; modes as plugins | print/rpc/tui parity via `scripts/capture/`; tk provider on Jolt |
| 6 | `:agents` registry (create/resume/list) + `mode-server` (rpc = one-agent server); `:agent-id` and the request/response pair on the wire; approvals end to end | two agents concurrently, one stalling client, an approval answered by a client |
| 7 | loop re-hosted as a driver plugin; profiles as row+patch layers; presets = bundles | pi-alignment green; a tool swaps mid-session at the turn boundary (`:agent-settled`); a profile stacking a preset bundle boots |

Landings 0–1 need no kmet1 behavior change: landing 1 splits `kmet.agent.core`
into a bootstrap and `kmet.agent.cli` without touching the old app's logic. 2–4
lift kmet1 code without renaming it, so kmet1 stays green throughout. 5 is the
first landing that moves kmet1 code *and* renames it (interactive →
`kmet.ui.tui`). `agent` at 4 may require `ui` at 3, so kmet1's dispatch
re-points at `kmet.ui.tui` behind a `--mode` compat shim: the old flag keeps
working and the capture diff gates the switch.

## Gates

- `bb test`, `bb test-ext`, `bb lint` (zero findings), `bb format-check`,
  `bb check` — unchanged, plus `bb test-changed` / `bb lint-changed` /
  `bb format-check-changed` while iterating.
- `kmet.test-module-deps` — ranks stay strictly downward.
- `kmet.test-modules-manifest` — `modules.edn`, `bb.edn`, `deps.edn` and the
  embed list stay in step.
- New: srce self-containment (`kmet.srce.*` may require only `kmet.libs.*`,
  `kmet.loader.*` and third-party code), the no-global-node guard, the
  **module-based require guard** (a namespace may require only a strictly
  lower-ranked module's file — built on `kmet.test-seam-requires`'s ns-form
  reader and `kmet.test-module-deps`'s ranks), and the headless-mode
  plugin-graph assertion.
- New: a profile test — layers patch by row id, `--dump-config` resolves to
  the same rows the boot uses, and an unknown plugin source fails at load.
- New: the two-direction load test — `kmet --app old --version` leaves
  `kmet.app.core` unloaded, `kmet --app new --version` loads kmet2 without any
  kmet1 *runtime* namespace (`kmet.agent.cli`, `kmet.agent.session.runtime`,
  `kmet.agent.loop`) appearing, and `kmet.agent.core`'s ns form requires
  neither app. It is what keeps someone from turning the dispatch into a
  static `:require`.
- New: the Cordis conformance suite — lifecycle transitions, inject
  (array/object/optional), service `check`/`set`/`isolate`/`intercept`, effect
  LIFO with async disposers, all five event modes with `prepend`/`once`, and
  the internal events.
- New: a drain test that cycles a 3-plugin graph through provide/release/reload
  to fixpoint, sync and async variants, and asserts no leaked registrations
  (`scope/ordered-registrations` empty after disposal).

## Not doing

- Forking the repo, or rewriting `kmet.libs.*` / `kmet.ai.*` / `kmet.tui.*` /
  `kmet.tk.*` / `kmet.loader.*`.
- Porting JavaScript syntax rather than semantics: TS declaration merging,
  decorators, prototype property mixins. The Clojure equivalents (`defservice`,
  records/protocols, `:inject` metadata, `ctx` ILookup keys) are in scope.
- Porting the separate `@cordisjs/plugin-loader` / `plugin-hmr` /
  `plugin-logger-console` packages: profiles/bundles/patches are kmet's loader,
  HMR is deferred until the kernel settles, and the logger is a small service.
- The Koishi plugin market / `package.json` service manifest.
- Multi-user accounts, quotas, or an ACL model in phase 1.
- Renaming `kmet.agent.*` namespaces at lift time, or inventing intermediate
  prefixes (`kmet.shared.*`, `kmet.services.*`): every rename is paid once more
  at retirement, and the last one is meant to be a `sed`.
- A web UI before `ui-tk` has proven the capability contract.
- Deleting kmet1's `kmet.agent.*`; it stays until kmet2 passes the full gate
  set and the pi-alignment suite, then it is retired in one landing.
