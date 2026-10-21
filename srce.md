# srce — a Cordis kernel in Clojure

Temporary note at the project root (the `loop-plan.md` convention). Folds into
[`modules/srce/src/kmet/srce/srce.md`](modules/srce/src/kmet/srce/srce.md) when
the module lands. kmet2's application architecture — services, agents,
sessions, profiles, landings — is in [`new-app.md`](new-app.md); this file is
the kernel only: its Cordis mapping, its rules, its plugin contract.

The name is **`srce`** — heart (South Slavic). Cordis is Latin for the same
thing (`cor`, `cordis`), which is what the kernel is to the system: the one
thing every other part is wired through. It stays clear of `kmet.kernel`,
`--list-kernels` and prose about OS kernels.

## What "port" means

Cordis is the Koishi meta-framework. srce ports its **kernel** — the context,
reflection/service layer, fiber lifecycle, plugin registry, event bus, logger
and internal events — to Clojure with the same observable semantics. The
`@cordisjs/plugin-loader`, `plugin-hmr` and `plugin-logger-console` packages
are separate Cordis packages, not the kernel: kmet's profiles/bundles/patches
are the loader equivalent ([`new-app.md`](new-app.md)), HMR is deferred until
the kernel settles, and the console exporter is `kmet.agent.debug`. The logger
*service* (`ctx.logger`, exporters, formatters, intercept config) is kernel and
is ported.

| Mechanism | Cordis | srce |
|---|---|---|
| Context | side-effect scope; one `ctx` per plugin fiber; `extend`/`isolate(name, label?)`/`intercept` create children | **port** — a `kmet.srce.ctx` value over `kmet.libs.scope`; `extend`/`isolate`/`intercept` are the same operations without the JS prototype, and `isolate` keeps its **label**: two children given one label share that service's scope, which is the realm a preset provider and its consumers need |
| Context surface | a `Proxy` plus four mixin groups put service methods on `ctx` (`ctx.on`, `ctx.get`, `ctx.plugin`, `ctx.effect`, `ctx.runtime`) | **map to Clojure** — the ctx is a map with fixed keys, and the cable is one namespace of functions (`srce/on`, `srce/get`, …); see "The ctx value" |
| Services | name-keyed values resolved through the context chain; `get`/`set`/`provide`/`accessor`/`mixin` (mixed from `reflect`); isolation labels; a `check` predicate given to `provide`; one `props` declaration per name shared by services and accessors | **port** — one registration per seam and isolation label; all six operations, `check` as `provide!`'s third argument, `get`'s `strict` flag, and a declared-name registry that makes a service/accessor collision an error; see "Services" |
| `inject` | array = required services; object = per-service **intercept config** (any non-nil value is merged into that service's config); pending until satisfied; a changed provider epoch unloads and re-applies the dependent, **within the same isolation label** | **port** — same, so the object form reserves its values for config; the optional dependency is a kmet2 extension with its own non-map spelling (`:optional`), never a config-shaped map (see "Plugins") |
| Reversible effects | one effect is one LIFO chain (`disposables.splice(0).reverse()`); across a fiber's effects, unload starts them in reverse but settles them **concurrently** (`Promise.all`); a `provide` disposer awaits every dependent fiber before dropping its own store entry; effect metadata tree (`EffectMeta`) | **port** — `kmet.libs.scope` is the registration tree, and srce owns the one fiber effect list, its label tree and the awaitable wrapper. Two Cordis details become decided rules: unwinding across a fiber's effects is **sequential** LIFO, and a released service **awaits its dependents** — a requirement, not an optimization |
| Fiber lifecycle | `PENDING`/`LOADING`/`ACTIVE`/`FAILED`/`DISPOSED`/`UNLOADING` (that enum order); `inertia`; `await`; `update(config, noSave)` behind the `internal/update` waterfall, `restart` outside it; `dispose` settles asynchronously; a disposed root restarts rather than dying | **port** — the same states over a portable Clojure awaitable; sync applies settle immediately, async applies when their awaitable resolves; `update!`/`restart!`/`fiber-await`; see "Fibers and effects" |
| Event dispatch | `emit`/`parallel`/`serial`/`bail`/`waterfall` with defined results and bail rules; `on`/`once`; `prepend`/`global`; a dispatch-level `thisArg` filter | **port** — all five modes with their return contracts, both registration options, and a `:filter` on every emit; the app bus keeps its node scoping on top of it |
| Logger | `ctx.logger`: a callable service, per-fiber named facades, exporters with per-name levels, formatters, colours and max length, a bounded message buffer, and `logger` intercept config | **port** — the same surface with the same defaults; `kmet.agent.debug` becomes one exporter, not the whole service; see "The logger" |
| Internal events | `internal/plugin`, `internal/status`, `internal/config`, `internal/service`, `internal/get`, `internal/set`, `internal/update`, `internal/listener`, `internal/dispatch` | **port** — the kernel's observability surface, with the same firing rules; see "Events" |
| Config | a StandardSchema validator, or a `{schema: true, Config: fn}` transform; resolved lazily through `internal/config` once injections are active (vendored mod 15); **validation is synchronous** | **port** — a malli validator, or `{:schema ... :transform fn}`, resolved by `srce/config` at activation; an async validator is a load error, as in Cordis |
| Plugin identity | runtime keyed by the plugin callback; one runtime per callback (dropped when its last fiber unloads); fibers per load; `provide`/`intercept` metadata; `fiber.name` inherited from the nearest named ancestor | **port** — runtime keyed by the resolved plugin source; one runtime per source, many fibers per `plugin!`; `dispose-plugin!` ends them all; the same name inheritance drives logger names |
| `Service` base class, `@Inject`, TS declaration merging | JS/TS ergonomics | **map to Clojure** — `defservice` (`:name`/`:check`/`:invoke`/`:extend`/`:resolve-config`), records/protocols, `:inject` metadata; class plugins and method decorators are not ported |
| Utilities | `utils.ts`: `DisposableList`, `EffectMeta` labels, `getTraceable`/`withProps`/`createCallable`, `composeError`/`buildOuterStack`, the `Context.is` brand | **port** — `srce/utils.clj` for `DisposableList`, the label tree and `isBailed`; the ctx marker replaces `Context.is`; the traceable and stack-splicing half is not ported (see "Not ported") |
| Plugin market | Koishi publishing | **not ported** — `extension.edn` and the extension dirs already do it |

The definition of "full" is the **vendored kernel's** contract, not upstream's:
`@deepseek-ai/cordis` 4.0.5 — upstream `4.0.0-rc.7` (`cordiverse/cordis`
commit `56b3d4f7`) plus the 24 local modifications logged in
`~/src/cvstree/deepseek-harness/vendor/README.md`. Several of those change
observable semantics (mod 6 reentrant disposal, 15 lazy config resolution, 20
fiber identity, 21 exporter disposal, 22 volatile config), so "Cordis parity"
means parity with that tree. Two things follow. The port is reviewed against
`docs/cordis-api/{context,events,fiber,registry,service}.md` and
`docs/cordis-primer.md`, the only complete written surface of that version, and
the **Parity matrix** at the end is the audit: one row per public member, each
naming its srce equivalent and its state. And because the vendored package
ships **no `tests/`** (`vendor/cordis/` is `src/` only), the conformance suite
is ours to write: it asserts the pinned tree, one test per matrix row that is
not marked "not ported". "Full" does not mean the JavaScript syntax or the
separate optional packages.

## The ctx value

A context is a value with a service store, an isolate map, an intercept map and
the fiber that owns it. Cordis's ctx is a `Proxy`: reads go through the
reflection layer, and four mixin groups put service methods on the object
(`reflect.ts:213-220`). srce has no proxy, so the shape is fixed:

| key | meaning |
|---|---|
| `:root` | the root ctx — every child shares it |
| `:parent` `:children` | the scope tree (the `kmet.libs.scope` node) |
| `:fiber` | the fiber that owns this ctx (the root fiber for the root ctx) |
| `:isolate` `:intercept` | this ctx's own copies — never the parent's map |
| `:store` | this ctx's `kmet.libs.scope` node |
| `:base-url` | resolution base for loader and extension sources; `nil` until set |
| `:events` `:logger` `:reflect` `:registry` | the four core service values |

`extend` merges extra keys (Cordis's own-property copy); the kernel reads none
of them, `:name` is convention (`new-app.md` uses it for agent contexts), and
`isolate`/`intercept` are copy-on-write children so a parent is never mutated.
`(srce/ctx? x)` replaces `Context.is` — a marker key rather than a global
symbol brand, because a Clojure value has no realm to cross.

The four Cordis mixin groups collapse into ordinary calls: `(srce/on ctx …)`
for `ctx.on`, `(srce/get ctx …)` for `ctx.get`, `(srce/plugin! ctx …)` for
`ctx.plugin`, `(srce/effect ctx …)` for `ctx.effect`, `(srce/runtime ctx)` for
`ctx.runtime`. Nothing is added to the ctx map for them, so a service name can
never collide with a kernel method — the reserve-name machinery
(`isSpecialProperty`: symbols, `prototype`, `then`, numeric strings, `_`
prefixes) has no counterpart and is not ported.

Reading a service has exactly two paths, and they differ on purpose:

- `(srce/get ctx :tools)` — the direct read (`ctx.reflect.get`): no hooks, no
  inject requirement, `nil` when absent.
- `(:tools ctx)` — `ILookup`, the property-read equivalent: it runs the
  `:internal/get` waterfall and walks the fiber chain for a resolved provider
  (`reflect.ts:136-170`). It raises when the name is one an ancestor fiber
  *requires* but has not resolved (inactive), and — in Cordis — also when the
  name is unknown; the unknown-key case is the open decision below. Writing
  through `(assoc ctx :tools v)` runs `:internal/set` the same way;
  `srce/set-service!` is the direct write, like `ctx.reflect.set`.

That split is deliberate and is what makes interception possible without a
proxy: a plugin that wants to observe reads hooks `:internal/get`; everything
else calls `srce/get`.

## Services

The reflect layer is `get / set / provide / accessor / mixin / notify`, plus
the declared-name registry (`props`, `reflect.ts:211`). The contract:

- **One declaration per name, shared by services and accessors.** `provide!` on
  a name declared as an accessor throws, `accessor` on a service name throws,
  and `mixin` installs accessors — so a mixin name cannot later become a
  service (`reflect.ts:279-287`, `345-355`, `364-395`).
- **`provide!` is an effect.** It registers `{:name :fiber :value :check}`
  under the current isolation label, puts it in `fiber.store`, assigns the
  name's **default label on the root** on first use
  (`ctx.root[isolate][name] ??= Symbol(name)`, `reflect.ts:285`), and throws if
  the label already has a provider. Its disposer deletes the entry, notifies,
  **awaits every dependent fiber**, and only then drops its own store entry
  (`reflect.ts:277-310`).
- **`set-service!` belongs to the providing fiber.** Any other fiber setting
  the value throws (`reflect.ts:254-270`); it is the way a service swaps its
  own implementation.
- **`get` is strict by default.** A non-strict read sees an implementation
  whose providing fiber is not `ACTIVE` yet; a strict one returns `nil`. That
  is `ctx.get(name, false)` and is how a boot-time probe works.
- **`check` is a predicate on the value, not on the fiber.** It runs before a
  dependent activates, a throw is logged and treated as "unavailable"
  (`fiber.ts:597-610`), and it is re-evaluated whenever the provider's state
  crosses `ACTIVE` (`fiber.ts:581-596`).
- **`notify!` refreshes dependents, scoped by isolation.** For each changed
  name it walks every runtime and fiber, refreshes only fibers that declare the
  name *and* sit in the same isolation label (`reflect.ts:314`), and emits
  `:internal/service` once per name on a dispatch filtered to that label. The
  scope rule is the reason agent A's `:tools` swap never reloads agent B's
  plugins; without it, "one implementation per scope" would hold for storage
  and leak through notification.
- **`fiber.store` is the resolved snapshot.** While a fiber is loaded, its
  `:store` holds the `{:name :fiber :value :check}` record it resolved for each
  injected name (`fiber.ts:198`, refreshed in `_reload`); it is the answer to
  "which provider did this fiber actually get", and it is what an inspector
  reads.
- **`accessor` and `mixin` are effects.** An accessor is a computed ctx key
  (`{:get f :set g}`, removal on unload); a mixin is sugar that installs
  accessors forwarding to a service value, which is how Cordis spells
  `ctx.on`. srce exposes the same two operations for parity, though app code
  calls the functions directly.

`trace` and `bind` (`reflect.ts:398-418`) are the JS mechanism that rebinds
`this` to the reading context. srce does not port them: a service fn takes
`ctx` as its first argument, so there is nothing to rebind, and the
diagnostics they carry are effect labels plus `ex-data`.

## Fibers and effects

One fiber per `plugin!`; the mounting ctx's child; `:uid` allocated by the
registry; disposed fibers keep the record but drop `:uid` to `nil`.

**The root fiber exists.** The root ctx owns a fiber with `:uid 0`, state
`:active`, disposables reset at construction (`context.ts:82`), and
`dispose` = `restart` (`fiber.ts:321-331`). Disposing the root therefore
restarts it; it never tears the process tree down. `dispose-node!` (scope) is
the tree teardown, and it is not the root fiber's disposer.

**Lifecycle.** `:pending` → `:loading` → `:active`; a throwing apply or config
→ `:failed`; unload → `:unloading` → `:pending`/`:disposed`. Every transition
is an awaitable in `:inertia`, `:internal/status` fires with
`(fiber, old-state)` on each change, and crossing `ACTIVE` in either direction
re-notifies the services this fiber provides (`fiber.ts:581-596`).

**`fiber-await` settles and rethrows.** After the transition chain drains, the
awaitable resolves with the fiber, or rejects with the config/startup error
(`fiber.ts:704-716`). `create!` in the app depends on the rejection; "settle"
alone is not the contract.

**`update!` and `restart!` differ.** `(srce/update! fiber config)` resolves the
config (`:internal/config` waterfall → validator), runs the `:internal/update`
waterfall — a listener that does not call `next` vetoes the reload — and then
restarts; `(srce/update! fiber config {:no-save true})` passes the persistence
hint through. When the fiber is **not active**, `update!` stores the raw config,
clears the error, sets the epoch inactive and refreshes, deferring resolution
until the fiber can inject (`fiber.ts:736-752`). `restart!` does none of that:
it sets the epoch inactive, refreshes and awaits (`fiber.ts:718-724`).

**The effect contract.**

- Creation requires an active fiber; a `:disposed` or `:unloading` fiber
  throws `{:type ::inactive-effect}` (`fiber.ts:351`, `418-422`). `:pending`
  and `:loading` are legal — that is the vendored hardening, and it is what
  lets a plugin register into its own `apply`.
- The body may return a **disposer**, an **awaitable of a disposer**, a **seq**
  of either (a lazy seq is Cordis's sync generator: each element is collected
  as produced), a **producer fn** receiving `register!` for the async-generator
  case, or `nil`.
- The return value is a disposer that is **single-shot**, **awaitable**
  (`wrapper.then`, `fiber.ts:555`), and carries the label tree as
  `:srce/effect` metadata (`fiber.ts:514`, read back by `(srce/effects fiber)`).
- Within one effect the disposers unwind in a strict LIFO chain
  (`fiber.ts:431`) — that is `dispose-owner!`'s order, one scope owner per
  effect. Across the fiber's effects, srce unwinds sequentially in reverse
  order, where Cordis starts them in reverse and settles them concurrently
  (`fiber.ts:676`). Sequential is the kmet rule: a service may depend on
  another effect having released.
- A failing teardown *inside* an effect rejects that effect's disposer; during
  unload it is logged and the remaining teardowns still run.

**`:internal/plugin` has a timing contract** (`fiber.ts:266-310`, emitted at
`302`): it fires after the parent owns the fiber's disposer and *before*
`inject` resolution, so an observer may dispose the fiber or its parent right
there, and a loader may add `inject` entries in that notification — the fiber
resolves them before it activates (vendored mods 6 and 20). Profiles and rows
depend on exactly this ordering.

## Events

Five dispatch modes with defined results:

| mode | runs | returns |
|---|---|---|
| `emit!` | every listener synchronously, in order | `nil`; listener results ignored |
| `parallel!` | every listener, awaited together | awaitable; all-settled, rejects with `{:type ::listener-failed :errors [..]}` after every listener finished |
| `serial!` | listeners in order, awaiting each | awaitable of the first bail value |
| `bail!` | listeners in order, synchronously | the first bail value |
| `waterfall!` | listeners composed around `next` | the outermost listener's value |

A value bails when it is neither `nil` nor `false` (`events.ts:13-15`) —
`(srce/bail-value? v)` is that predicate, and a listener that returns `false`
deliberately continues. A `waterfall!` listener that does
not call `next` vetoes the rest of the chain, including the built-in behaviour;
that is the loop's blocking point in `new-app.md`.

Registration: `(srce/on ctx name f {:prepend true :global true})` and
`(srce/once …)`; order is registration order, scoped listeners see only the
ctxs that pass their filter, and the returned disposer reports whether it
removed anything (identity comparison, `events.ts:254-280`). `once` disposes
*before* invoking the listener.

Every emit takes a dispatch filter, which is Cordis's `thisArg` form
(`events.ts:165-175`):

```clojure
(srce/emit! ctx :turn-end ev {:filter (fn [listener-ctx] …)})
```

The filter is applied to each listener's own ctx, not to the event, and it is
the only mechanism a node-scoped bus can use — a subscription cannot opt out of
it (`:global` skips it).

`:internal/listener` is a **bail** hook that may claim a registration: a
listener that returns a disposer from it replaces the registration, which is
how the events service itself keeps a per-fiber `:internal/update` chain
(`events.ts:140-160`). `:internal/dispatch` fires for public names only —
never for an `internal/` name.

## The Service base

`defservice` is the Clojure `Service` (`service.ts`): a value registered from
its constructor, with these keys.

| key | Cordis | meaning |
|---|---|---|
| `:name` | `constructor.provide` fallback | the service name; defaults from the `:provide` metadata |
| `:check` | `[Service.check]` | availability predicate, passed to `provide!` |
| `:invoke` | `[Service.invoke]` | makes the value **callable** (`(logs)`); the logger is the canonical case |
| `:extend` | `[Service.extend]` | derives a new instance with extra keys, for per-caller facades |
| `:resolve-config` | `[Service.resolveConfig]` | merges intercept config |

The merge rule is part of the contract, not an implementation detail:
intercept entries are collected walking the intercept chain **ancestor-first**,
an optional `base` is prepended and an optional `head` appended, and the merge
is `:merge` if the service declares one, else a shallow merge
(`service.ts:80-100`). That is how `(srce/intercept ctx :llm {:model …})`
reaches a plugin's config without the plugin doing anything.

`Symbol.hasInstance` (cross-copy `instanceof`) has no counterpart; a record's
`instance?` is enough.

## The logger

Ported in full, because `logger.ts` is kernel:

- `(srce/logger ctx)` and `(srce/logger ctx name)` return a **facade**; the
  default name is the fiber's name, hyphenated (`hyphenate(fiber.name)`), and
  the facade carries `{:fiber … :name … :level …}` metadata. Four methods —
  `error`, `info`, `warn`, `debug`. The service value also carries those four
  directly, routed through the current fiber's facade
  (`logger.ts:135-140`, `264-270`), so `(srce/log! ctx :info "…")` is the
  mixed-in form and `(srce/logger ctx)` the named one.
- **Exporters** are effects: `{:colors :max-length :levels :formatters
  :export}`. `levels` is a per-name map with a `:default`; `formatters`
  overrides the printf verbs (`%s %d %i %f %o %O %c %C` are the defaults);
  `:max-length` truncates each line (10240 by default).
- The **buffer** is a bounded ring (1000 messages) kept by the built-in
  buffer exporter, so a UI can show the tail without registering an exporter.
- An `error` argument that carries a `:cause` is logged through its cause, and
  an aggregate error is flattened into its members (`logger.ts:141-150`).
- `logger` **intercept config** is `{:name :level}`, resolved through the
  intercept chain — that is how a per-agent log name or verbosity is set.
- `Logger.format`/`color`/`code` (the ANSI palette hash, `c16`/`c256`) are
  public statics and are ported as `srce/log-format`, `srce/log-color` and
  `srce/log-code`, next to `srce/add-exporter!`.

## Plugins

**No `IPlugin` protocol.** Cordis's `IPlugin` is a TypeScript union
(`Function | Constructor | Object`) that exists because a JS class must be
loadable as a plugin. A Clojure function is a value and a record is constructed
*by* `apply`, so the union collapses to one shape; a protocol would add a
vtable with one implementation, and would put host class identity on the SCI
boundary the extension loader exists to keep clean. The plugin is a **validated
value**:

```clojure
(def plugin-schema
  [:map
   [:name      keyword?]
   [:inject    {:optional true} [:maybe inject-schema]]   ; [:tools] | {:tools nil | intercept-config | :optional}
   [:config    {:optional true} [:maybe :any]]             ; a malli schema, or {:schema ... :transform fn}
   [:provide   {:optional true} [:maybe [:set keyword?]]]
   [:intercept {:optional true} [:maybe :map]]
   [:apply     fn?]]
```

| Cordis | kmet2 |
|---|---|
| `Plugin.Function (ctx, config)` | `:apply (fn [ctx cfg] …)` |
| `Plugin.Object { apply }` | the plugin map |
| `Plugin.Constructor` (`Service` subclass) | `(defrecord S …)` built inside `:apply` — never the plugin |
| `Plugin.Base` (`name`/`Config`/`inject`/`provide`/`intercept`) | the map's keys; `:config` is a malli schema or transform |
| `Plugin.Transform` (`{schema: true, Config}`) | `{:schema true :transform fn}` |
| `IPlugin` type | `plugin-schema`, checked once at load (`{:type ::bad-plugin :problems …}`) |
| `registry.resolve` | `srce/plugin-source` — a var, or a keyword an extension loader resolves |
| `registry.get`/`has`/`delete` | `srce/runtime` / `srce/runtime?` / `srce/dispose-plugin!` |
| `registry.keys`/`values`/`entries`/`forEach`/`size` | `srce/runtimes` / `srce/runtime-count` (the inspection surface `--dump-config` reads) |
| `registry.counter` | the `:uid` allocator, internal |
| `Inject.resolve` | `srce/normalize-inject` — array, object, or the `:optional` sentinel |
| `@Inject` on a class | `:inject` metadata (and `defplugin`'s `:inject`) |
| `@Inject` on a method (delayed call) | not ported — `srce/inject!` is the readable form |
| plugin *source* polymorphism | `kmet.loader.core/Loader` protocol — reused as-is (SCI / Jolt native / classpath) |

`defplugin` is sugar over the map, not an interface — the same trade
`kmet.tui.macros/defcomponent` makes, and the shape the extension docs already
teach:

```clojure
(defplugin web-search
  {:name    :tool/web-search
   :inject  {:tools nil}                                  ; required, no intercept config
   :config  [:map [:max-results [:int {:min 1}]]]
   :apply   (fn [ctx {:keys [max-results]}] …)})
```

It gives a named var (`web-search`, the source), a compile-time shape check,
`:name` metadata, and a card in `kmet2 --dump-config` — the composed row, its
source and its config, printed through the same patch algorithm the boot
applies, so a dump cannot drift from a boot. (What a *pending* fiber is
waiting for is runtime state, not composition: that is the booted view,
`--dump-state`, a kmet addition rather than a dsh borrowing.)

**`inject` values are intercept config, so optionality gets a sentinel.** In
Cordis the object form means `{service intercept-config}`: any non-nil value
*is* merged into that service's config and handed to `resolve-config`. That is
why `{:tools {:required true}}` — a shape dsh's own test fixtures use — does
not mean "optional" there; it silently becomes `:tools` config. kmet2 reserves
the map values and spells the kmet2-only optional dependency as the keyword
`:optional`, which no config map can be confused with:

```clojure
[:tools]                         ; required, no intercept config
{:tools nil :llm {:model "x"}}   ; both required; :llm sees intercept {:model "x"}
{:ui :optional}                  ; optional: never holds the fiber pending
```

An optional dependency is not merely skipped when absent: it participates in
the epoch. Absent contributes a stable marker, present contributes the provider
fiber's uid, so the fiber loads either way and **unloads and re-applies when
the service appears or is replaced**. That is the "upgrade when a richer `:ui`
attaches" behavior, and it costs nothing when no UI is mounted. (Cordis has no
such form; it is a kmet2 extension, and the epoch rule is what makes it work.)

**Plugin sources are explicit; ids are for identity, not resolution.**
`defplugin` defines the plugin var (the source) and records its metadata
(`:name` as the id, `:inject`, the config schema). A profile row names the
source ([`new-app.md`](new-app.md)) — `kmet.app.tools.bash/plugin` for a
built-in, `:ext/grep-tool` for an extension the host resolves — and the boot
resolves that source, never a keyword. A **generated catalog** lists the known
built-in ids for validation, docs and the `--dump-config` cards; it is a
committed manifest checked the way the bundled-extensions manifest
(`kmet/bundled-extensions.edn`) is, not a mutable registry. This is dsh's
`cordis.yml` row model: `:id` identifies the row, the source names what to
mount. An unknown source fails at load with
`{:type ::unknown-plugin :source …}`, and mounting from a disposed fiber fails
with `::inactive-effect` (`registry.ts:317`).

**One runtime, many fibers.** A plugin source is mounted any number of times:
`plugin!` creates a fresh fiber each call — its own config, inject resolution,
ctx, effects and lifecycle — and records it on that source's runtime, exactly
as Cordis does. `inject!` is `plugin!` with `{:inject deps :apply cb}` and the
callback's name as the runtime name (`registry.ts:301-303`). `dispose!` ends
one fiber; `dispose-plugin!` ends every fiber of a source and drops the
runtime. **The runtime also goes away when its last fiber unloads**
(`fiber.ts:266-280`, `273`), so `runtime` reads `nil` in between and a later `plugin!`
re-creates it — a test that inspects a runtime must hold a live fiber.

**Names come from the runtime and walk up.** The runtime name is the plugin's
`:name` (`'apply'` → none, `registry.ts:325-327`); `fiber.name` is the nearest
named ancestor's name, else `:root` (`fiber.ts:336-347`); the logger's default
name is that name hyphenated. A plugin with no name is invisible to both, which
is why `defplugin` records one.

"Multiple places" means different parent contexts: a root mount and an
agent-child mount share the runtime but see different services, and a profile's
`:per-agent true` extension is the concrete case. A plugin that *provides* a
service cannot be mounted twice in the same isolation scope — the second
`provide!` fails, as in Cordis; use `isolate` for a second service instance.
Multiple mounts are allowed by default.

## Settled kernel questions

| Question | Answer |
|---|---|
| `inject` meaning | `inject!` mounts with dependencies; `get` reads. Optional is a kmet2 extension, not Cordis semantics. |
| Event modes | Cordis's five — `emit`, `parallel`, `serial`, `bail`, `waterfall`. The app's threaded boundary merge (`boundary!`) sits on top of `serial`. |
| Effect ordering and async | One fiber-owned effect list, unwound sequentially and LIFO across every effect kind; async disposers awaited; a released service awaits its dependents before it disappears. `scope` is the synchronous registration tree. |
| Fiber failures | `:failed` state + `:plugin/failed`; dependents stay pending. |
| Multiple mounts | One runtime per source, many fibers; `dispose-plugin!` ends all. |
| Reload boundary | Kernel: `drain-await` and per-fiber `update!`. Policy — *when* the app waits — is [`new-app.md`](new-app.md)'s. |

## The kernel

```
modules/srce/src/kmet/
├── srce.clj          — the public API: root/extend/ctx?/isolate/intercept/get/
│                        set-service!/provide!/accessor/mixin/notify!/declared/
│                        service?/defservice/defplugin/plugin-source/plugin!/
│                        inject!/runtime/runtime?/runtimes/runtime-count/
│                        dispose-plugin!/effect/effects/assert-active!/update!/
│                        restart!/fiber-await/drain-await/dispose!/on/once/
│                        emit!/parallel!/serial!/bail!/waterfall!/bail-value?/
│                        logger/log!/add-exporter!/log-format/log-color/log-code/
│                        normalize-inject/resolve-config
└── srce/
    ├── srce.md       — design doc: the context model, the lifecycle, the Cordis mapping, the rules
    ├── ctx.clj       — context values: parent, children, isolate/intercept maps, base-url, fiber
    ├── reflect.clj   — get/set/provide/accessor/mixin/notify, the service store and the declared names
    ├── service.clj   — Service semantics: check, invoke, extend, resolve-config, defservice
    ├── fiber.clj     — lifecycle states, effect tracking, inertia, await, dispose/update/restart
    ├── registry.clj  — plugin runtimes, inject normalization, plugin!/inject!, provide/intercept metadata
    ├── event.clj     — emit/parallel/serial/bail/waterfall, on/once, prepend/global, filters
    ├── logger.clj    — the logger service: facades, exporters, formatters, buffer
    ├── awaitable.clj — the portable awaitable (promise/future/IDeref) used by fibers and disposers
    ├── utils.clj     — DisposableList (identity-keyed, LIFO clear), EffectMeta labels, the ctx marker
    └── config.clj    — malli validation/transform, layered config
```

There is no `effect.clj`: `kmet.libs.scope` is the synchronous registration
tree (owner paths, LIFO, closed-store guards), used as-is. Moving it up would
break libs' self-containment rule (`kmet.libs.*` may require only
`kmet.libs.*`), and a re-export shim would be a copy of a name rather than of
the behavior. `kmet.srce.ctx` wraps `scope/node` — the ctx `:store` and its
`:parent`/`:children` *are* the node's tree — and `scope` is never asked to
schedule async work.

The division is exact: **scope owns who registered what and in which order it
is taken back; srce owns which context, which fiber, which isolation label, and
when the taking-back has finished.** Scope holds the registrations the app
contributes (tools, commands, skills, listener sets) and the ownership record of
every effect. Four structures are deliberately *not* scope's, because they are
keyed differently or need notification: the service store
(`{isolation label → {:name :fiber :value :check}}`), the declared names
(`props`), the per-fiber resolved snapshot, and the epoch — all plain srce
atoms.

**One scope owner per effect.** A fiber is not one owner: `srce/effect`
allocates its own owner (the fiber's uid plus the effect's sequence or label),
registers into it, and pushes the effect's disposer onto the fiber's list. That
makes the fiber list the *only* global order and `dispose-owner!` the
*per-effect* order:

```
unwind a fiber = for each effect in reverse:
                   await the effect's awaitable part, then dispose-owner! it
```

A single owner per fiber would instead collapse `provide!`, listeners and async
teardowns into one non-awaiting `dispose-owner!` pass, which cannot express "a
released service awaits its dependents". For the same reason srce never uses
`add-teardown!` for async work — scope cannot await; that key stays what kmet1
uses it for, cleanups for things outside the store (process, file and host
handles).

The four disposal axes stay distinct: the **fiber** (`dispose!`; on the root it
restarts), the **effect** (`dispose-owner!`), the **context tree**
(`dispose-node!`, children first — an agent's release), and the **plugin
runtime** (`dispose-plugin!`, every fiber of one source). scope's atom is also
not a concurrency boundary: `register!` checks-and-writes and `dispose-owner!`
snapshots-then-acts, both safe per operation but not as a mutex — under srce the
drain owner is the serializer (see *Scheduling*).

Consumers require exactly one namespace — the kernel's surface is Cordis's,
and a second alias in every app file buys nothing:

```clojure
(ns kmet.app.session
  (:require [kmet.srce :as srce]))

(srce/root)                                     ; the process root; kmet1's app-node
(srce/extend ctx {:name :agent/7f3a})          ; a child with metadata
(srce/isolate ctx :tools)                      ; its own scope for :tools below here
(srce/isolate ctx :llm ::agent/7f3a)           ; children sharing a label share the scope
(srce/intercept ctx :llm {:model "anthropic/claude"})
(srce/get ctx :tools)                          ; direct read; or (:tools ctx) through ILookup
(srce/get ctx :tools {:strict false})          ; include a not-yet-active provider
(srce/declared ctx :tools)                     ; the {name {:type …}} declaration
(srce/set-service! ctx :tools new-table)      ; only the providing fiber
(srce/provide! ctx :tools table {:check ready?})
(srce/accessor ctx :models {:get (fn [ctx] …)})
(srce/notify! ctx [:tools])                    ; normally the provide! disposer does this
(srce/inject! ctx [:tools] handler)            ; Cordis's ctx.inject sugar → fiber
(srce/plugin! ctx web-search-plugin {:max-results 5}) ; → fiber, awaitable
(srce/runtime ctx web-search-plugin)           ; the runtime record, or nil
(srce/runtimes ctx)                            ; the inspection surface
(srce/update! fiber {:max-results 10})         ; resolve config, waterfall, restart
(srce/restart! fiber)                          ; unload + reload, same config
(srce/effect ctx f)                            ; → disposer (single-shot, awaitable)
(srce/effects fiber)                           ; the EffectMeta tree
(srce/on ctx :turn-end handler {:prepend true})
(srce/emit! ctx :turn-end ev)                  ; sync, ignores results
(srce/emit! ctx :turn-end ev {:filter f})      ; dispatch filter (Cordis's thisArg)
(srce/parallel! ctx :turn-end ev)              ; awaits all
(srce/serial! ctx :turn-end ev)                ; awaits until bail
(srce/bail! ctx :tool-call ev)                 ; sync until bail
(srce/waterfall! ctx :tool-call ev next)       ; next-chain
(srce/fiber-await fiber)                       ; settle, rethrowing startup errors
(srce/drain-await ctx)                         ; the queue to empty
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
  awaited transitions are ported, not dropped: an effect body is any of the
  shapes listed under "Fibers and effects"; a disposer may return an
  awaitable and is itself awaitable; `fiber-await`/`dispose!` settle when the
  transition finishes. A pass starts each fiber transition and awaits it, and a
  per-fiber epoch keeps a stale apply from landing after a newer transition:
  the epoch is the join of
  the resolved providers' `uid`s (`_refresh`, `fiber.ts:611-624`), and a
  `_reload` whose epoch moved is dropped (`fiber.ts:646-672`). Who runs a pass,
  and on which thread, is *Scheduling* below. The awaitable is
  `kmet.srce.awaitable` (a `promise`/future/`IDeref` with a timeout, or a
  one-slot queue on a host without promises), not a JS promise.
- **Quiescence is an awaitable, not a scope property.** Scope-level disposal
  never waits (`scope.md` §3, §7); the *fiber* disposal is an awaitable, and
  the kernel exposes `drain-await` (the queue to empty) next to it so the app
  has one primitive to wait on. When to wait is application policy —
  [`new-app.md`](new-app.md) places it at the turn boundary.
- **No global node.** `kmet.agent.runtime/node`'s job is done by an explicit
  `ctx` argument: every `kmet.app.*` function takes `ctx`, and no arity
  resolves the root. A guard test bans `runtime/node` in `kmet.srce.*`,
  `kmet.app.*`, `kmet.ui.*` and `kmet.mode.*`; the migration of kmet1's call
  sites belongs to the landings in [`new-app.md`](new-app.md).
- **One implementation per facility.** The effect tree is `kmet.libs.scope`,
  used as-is — not copied, not re-exported (AGENTS.md "Shared layers are
  extended, not copied"). The kernel adds the per-fiber owner, effect labels
  and the awaitable wrapper; it never reimplements owner paths or LIFO
  disposal.
- **A plugin's effects are its return value.** `apply` returns any effect
  body (the shapes in "Fibers and effects"); a `shutdown` fn is accepted
  sugar. Effect bodies may be sync or awaitable; the fiber runs registrations
  LIFO, then the awaitable teardowns, and reports failures through `:logger`.
- **Failures are states, not silence.** A throwing `apply` or effect marks the
  fiber `:failed` with `{:error e}`, emits `:plugin/failed`, leaves dependents
  pending, and is reported through `:logger`; `scope/*error-reporter*` keeps
  its semantics. A *registration* on a dead fiber is the one case that throws
  rather than logging, because the caller must not believe it registered
  anything (`::inactive-effect`).
- **Cordis parity is the contract.** The Parity matrix at the end of this file
  lists every public Cordis member with its srce equivalent; a rule cites a
  `vendor/cordis/src/*.ts` line or says it is a kmet decision, and every matrix
  row that is not "not ported" has a conformance test.

### Scheduling: the drain owner

Cordis has no scheduler to port — a transition runs **inline on the caller's
stack**: `provide` walks every runtime in the registry (`reflect.ts:314`),
re-checks each dependent (`_checkImpl`), recomputes its epoch (`_refresh`) and
starts `_reload()`, all from the mutating call. That is correct in JS because
there is one thread and a microtask queue.

kmet2 has neither. A transition can be entered from the TUI thread, from a
`kmet.libs.concurrent/spawn`ed tool thread, from the loop, and in phase 1 from
a socket reader, so an inline `provide!` would run one agent's `apply` on
another agent's thread — concurrently with that agent's turn, and with the
registry being mutated underneath. The kernel therefore needs a rule the port
cannot inherit:

- **One drain owner per root context.** A root ctx owns a serialized,
  re-entrant executor. `provide!` (and its disposer), `update!` and
  `dispose-plugin!` mutate the service map and *submit* a pass; they never run
  one on the caller's thread.
- **Nested submissions are legal and ordered.** A plugin calling `provide!`
  from its own `apply` runs on the drain owner's thread, in the same pass —
  the re-entrancy Cordis gets for free and the port must reproduce explicitly.
- **Fibers are logical, not thread-affine.** A transition may run on the drain
  owner's thread; only the fiber's own `apply` observes it as synchronous.
- **Callers await through the awaitable.** `fiber-await`, `dispose!` and
  `update!` submit their transition and await its completion, and `drain-await`
  awaits the queue to empty. The app's turn-boundary quiesce is then "drain to
  empty", not a second mechanism.

Alternatives, and why not:

| Alternative | Why not |
|---|---|
| inline on the caller (a literal port) | arbitrary threads execute other agents' plugin code; a blocking `apply` (a dialog, a subprocess handshake) blocks a socket reader; `notify` walks a registry another thread is mutating |
| one global lock around inline passes | same semantics as the queue for the common case, but a transition that waits on another thread's `provide!` deadlocks, and a lock cannot express "this fiber is mid-unload" |
| one thread per fiber | `apply` must be allowed to block; fiber-per-thread makes every plugin author a concurrency author |

## Parity matrix

The audit for "full". Every public member of the vendored tree is listed; a row
is either **port** (same semantics, renamed), **map** (same intent, different
shape), or **—** (not ported, see the next section). The conformance suite is
generated from this table: one test per row that is not `—`.

### context.ts

| Cordis | srce | |
|---|---|---|
| `new Context()` | `srce/root` | port |
| `ctx.extend(meta)` | `srce/extend` | port |
| `ctx.isolate(name, label?)` | `srce/isolate` | port |
| `ctx.intercept(name, config)` | `srce/intercept` | port |
| `ctx.root` | `:root` | port |
| `ctx.baseUrl` | `:base-url` | port |
| `ctx.events` / `.logger` / `.reflect` / `.registry` | `:events` / `:logger` / `:reflect` / `:registry` | port |
| `ctx.fiber` | `:fiber` | port |
| `Context.is` | `srce/ctx?` | map |
| `Context.effect` / `filter` / `isolate` / `intercept` symbols | `:srce/effect`, `:filter` option, `:isolate`, `:intercept` keys | map |
| get / set / has traps | `srce/get`, `assoc`/`srce/set-service!`, `srce/service?` | map |
| reserve names (`isSpecialProperty`) | — | — |
| `nodejs.util.inspect.custom` | `print-method` / `pr-str` | map |

### reflect.ts

| Cordis | srce | |
|---|---|---|
| `ctx.get(name, strict?)` | `srce/get` (`{:strict false}`) | port |
| `ctx.set(name, value)` | `srce/set-service!` | port |
| `ctx.provide(name, value?, check?)` | `srce/provide!` | port |
| `ctx.accessor(name, {get, set})` | `srce/accessor` | port |
| `ctx.mixin(source, keys)` | `srce/mixin` | port |
| `reflect.notify(names, filter?)` | `srce/notify!` | port |
| `reflect.props` | `srce/declared` | port |
| `reflect.store` | the ctx's `:store` node | map |
| `Impl {name, fiber, value, check}` | `{:name :fiber :value :check}` | port |
| `reflect.trace` / `reflect.bind` | — (explicit `ctx`; labels + `ex-data` for diagnostics) | — |
| `internal/get` / `internal/set` waterfalls | `:internal/get` / `:internal/set` on the property path only | port |

### fiber.ts

| Cordis | srce | |
|---|---|---|
| `FiberState` | `:pending` `:loading` `:active` `:failed` `:disposed` `:unloading` | port |
| `fiber.uid` / `.name` / `.config` / `.state` / `.store` / `.inertia` | `:uid` / `:name` / `:config` / `:state` / `:store` / `:inertia` | port |
| `fiber.ctx` / `.parent` / `.runtime` | `:ctx` / `:parent` / `:runtime` | port |
| `fiber.effect(body, label)` | `srce/effect` | port |
| `fiber.getEffects()` | `srce/effects` | port |
| `fiber.await()` | `srce/fiber-await` | port |
| `fiber.restart()` | `srce/restart!` | port |
| `fiber.update(config, noSave)` | `srce/update!` | port |
| `fiber.dispose()` | `srce/dispose!` | port |
| `fiber.assertActive()` | `srce/assert-active!` | port |
| `{effect: …}` metadata on a disposer | `:srce/effect` meta | port |
| `rootFiber.dispose()` (restarts) | `srce/dispose!` on `:root` restarts it | port |
| `ValidationError` | `{:type ::invalid-config :problems […]}` | map |
| `CordisError/INACTIVE_EFFECT` | `{:type ::inactive-effect}` | map |
| `resolveConfig(runtime, config)` | `srce/resolve-config` | port |

### registry.ts

| Cordis | srce | |
|---|---|---|
| `ctx.plugin(plugin, config?)` | `srce/plugin!` | port |
| `ctx.inject(deps, callback)` | `srce/inject!` | port |
| `registry.resolve(plugin)` | `srce/plugin-source` | map |
| `registry.get` / `.has` / `.delete` | `srce/runtime` / `srce/runtime?` / `srce/dispose-plugin!` | port |
| `registry.keys` / `.values` / `.entries` / `.forEach` / `.size` | `srce/runtimes` / `srce/runtime-count` | map |
| `registry.counter` | internal uid allocation | port |
| `Plugin.Runtime {name, fibers, callback, Config}` | `{:name :fibers :source :config-schema}` | port |
| `Inject.resolve` | `srce/normalize-inject` (`:optional`) | port |
| `@Inject` (class) | `:inject` metadata | map |
| `@Inject` (method) | — (`srce/inject!`) | — |
| `Plugin.Function` / `.Object` | one map shape | map |
| `Plugin.Constructor` | — | — |
| `Plugin.Transform` | `{:schema true :transform fn}` | port |

### events.ts

| Cordis | srce | |
|---|---|---|
| `ctx.emit` / `.parallel` / `.serial` / `.bail` / `.waterfall` | `srce/emit!` / `parallel!` / `serial!` / `bail!` / `waterfall!` | port |
| `ctx.on` / `.once` | `srce/on` / `srce/once` | port |
| `{prepend, global}` | `{:prepend :global}` | port |
| dispatch `thisArg` filter | `{:filter (fn [listener-ctx] …)}` | map |
| disposer returning removal status | same | port |
| `isBailed` | `srce/bail-value?` | port |
| `AggregateError` from `parallel` | `{:type ::listener-failed :errors […]}` | map |
| `internal/listener` claiming a registration | same | port |
| nine `internal/*` events | same, with the same firing rules | port |

### service.ts

| Cordis | srce | |
|---|---|---|
| `class Service` + constructor registration | `srce/defservice` | map |
| `[Service.check]` | `:check` | port |
| `[Service.invoke]` (callable) | `:invoke` | port |
| `[Service.extend]` | `:extend` | port |
| `[Service.resolveConfig]` | `:resolve-config` (ancestor-first merge, `:merge` or shallow) | port |
| `static provide` (name fallback) | `:provide` metadata | port |
| `[Service.init]` | `:init` | port |
| `Symbol.hasInstance` | — | — |
| `[Service.config]` phantom type | `:intercept` metadata | map |

### logger.ts

| Cordis | srce | |
|---|---|---|
| `ctx.logger(name?)` | `srce/logger` | port |
| `error` / `info` / `warn` / `debug` | same | port |
| `logger.exporter` | `srce/add-exporter!` | port |
| `Exporter {colors, maxLength, levels, formatters, export}` | same | port |
| `defaultFormatters` | same | port |
| `Logger.format` / `.color` / `.code` | `srce/log-format` / `log-color` / `log-code` | port |
| `buffer` / `bufferSize` | same (ring, 1000) | port |
| `logger: {name, level}` intercept | same | port |
| `Message {sn, ts, name, type, level, args, fiber}` | same | port |

### utils.ts

| Cordis | srce | |
|---|---|---|
| `DisposableList` | `srce.utils/disposable-list` (internal) | port |
| `symbols` | keyword keys and `:srce/*` meta | map |
| `isBailed` | `srce/bail-value?` | port |
| `isObject` | `map?` / `ifn?` | map |
| `isConstructor` / `joinPrototype` | — | — |
| `getPropertyDescriptor` | — | — |
| `getTraceable` / `Tracker` / `withProps` / `createCallable` | — (explicit `ctx`) | — |
| `composeError` / `buildOuterStack` | — (`ex-info` `:cause` + effect labels) | — |

## Not ported

Every `—` above, with the reason and the replacement. This list is a decision,
not an omission; adding a row here is cheaper than silently diverging.

| Cordis | Why not | What srce does instead |
|---|---|---|
| `Proxy` traps, `isSpecialProperty`, reserve names | no proxy in Clojure | fixed ctx keys; kernel fns are `srce/*`, never ctx keys |
| `getTraceable` / `Tracker` / `shadow` / `receiver` / `withProps` | there is no `this` to rebind | every service fn takes `ctx`; a value carries no context |
| `createCallable` / `joinPrototype` | prototype splicing | `:invoke` makes the value a fn |
| `Plugin.Constructor`, `isConstructor` | no class plugins | a record built inside `:apply` |
| `@Inject` on methods, `initHooks` | decorator ergonomics | `srce/inject!` |
| `Symbol.hasInstance`, `Context.is`'s `Symbol.toPrimitive` | `instanceof` and realm tricks | a marker key, `srce/ctx?` |
| `composeError` / `buildOuterStack` | no async stack splicing | `ex-info` `:cause` plus effect labels |
| `reflect.trace` / `reflect.bind` | the traceable mechanism | the same, replaced by explicit `ctx` |
| `Service[Symbol.config]` phantom type | TS-only | `:intercept` metadata |
| `Volatile` / `VolatileSnapshot` | loader/config feature (vendored mod 22) | the loader layer ([`new-app.md`](new-app.md)) |

## Gates

- The conformance suite **for the pinned kernel** — `@deepseek-ai/cordis` 4.0.5
  (upstream rc.7 plus the 24 vendored modifications), one test per Parity
  matrix row that is not `—`, and every assertion citing a
  `vendor/cordis/src/*.ts` or `docs/cordis-api/*` line. The vendored package
  ships no tests to port; the matrix is the case list.
- One test per contract paragraph in "Services", "Fibers and effects",
  "Events", "The Service base", "The logger" and "Plugins" that the matrix
  cannot express as a row: notify scope, default label, `fiber.store`,
  `update!`-while-pending, `internal/plugin` timing, dispatch filtering.
- A drain test that cycles a 3-plugin graph through provide/release/reload to
  fixpoint, sync and async variants, asserts no leaked registrations
  (`scope/ordered-registrations` empty after disposal), and asserts that a
  transition submitted from a foreign thread ran on the drain owner.
- srce self-containment: `kmet.srce.*` may require only `kmet.libs.*`,
  `kmet.reakt.*`, `kmet.loader.*` and third-party code — no `hikap`, no `ai`,
  no `tui`/`tk`, nothing above rank 1.

## Open decisions

Each is cheap now and expensive after landing 0.

| Question | Recommendation |
|---|---|
| `(:tools ctx)` through `ILookup`: raise like Cordis's property read, or return nil? | keep raising for a *declared-but-inactive* service (parity), return nil for an unknown key (divergence: Clojure destructuring and `contains?` rely on nil, and Cordis's throw there is a by-product of the proxy walk) |
| `:name` on a plugin: keyword or namespaced symbol? | keyword, with the var path as `:source`. Cordis's string name exists for `hyphenate`d logger names, which the logger derives itself |
| Is `promise` available on all three hosts for the awaitable? | verify on Jolt before landing 0; the fallback is the one-slot queue. `kmet.libs.concurrent` has no queue primitive yet, so that primitive lands in `libs` (rank 0) first |
| The drain owner's threading model: one dedicated thread, or a thread pool of one with a mailbox? | a dedicated thread per root ctx — no shared pool to starve, and a blocked `apply` stalls only its own tree |

## Not doing

- Porting JavaScript syntax rather than semantics: TS declaration merging,
  decorators, prototype property mixins, the `Proxy`-based context. The
  Clojure equivalents (`defservice`, records/protocols, `:inject` metadata,
  explicit `ctx` arguments) are in scope.
- Porting the separate `@cordisjs/plugin-loader` / `plugin-hmr` /
  `plugin-logger-console` packages: profiles/bundles/patches are kmet's loader,
  HMR is deferred until the kernel settles, and the console exporter is
  `kmet.agent.debug` rather than a ported package.
- The Koishi plugin market / `package.json` service manifest.
- Reinventing a replacement for a JS-only mechanism: every `—` in the Parity
  matrix has its replacement named under "Not ported", and that table is the
  single place to change one.
