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
| 4 | Hosts | **bb, Jolt, JVM — all portable** | No JVM-only threads/locks/FFI in `kmet.srce.*`, `kmet.app.*`, `kmet.mode.*` |
| 5 | Second UI provider | **`ui-tk`**, web later | Proves the `:ui` capability contract against an existing backend (`kmet.tk`, Jolt-only) |
| 6 | Tenancy | **Single principal in phase 1** | One bearer token per server, many clients; `:permissions` scoping is user-level control, not isolation. Multi-tenant later = token → principal → child ctx, one level above agents |
| 7 | The kernel's name | **`srce`** — heart; Cordis is Latin for the same | One prefix, `srce/` alias. `kernel`, `kkernel`, `zrno`, `zrnce` rejected |
| 8 | Plugin ABI | **A validated value**, no `IPlugin` protocol | `{:name :inject :config :apply :reusable?}` + malli; loading polymorphism stays on `kmet.loader.core/Loader`, and no host class identity crosses the SCI boundary |
| 9 | Shared layer rank | **`app` at 2** (`srce` 1, `ui`/`mode` 3, `agent` 3) | kmet1's rank-3 adapters can call the lifted registries; kmet1 is never above kmet2 |
| 10 | Namespaces on reuse | **Move the file, keep the namespace** | 170 `with-redefs` sites keep intercepting; the final rename is one `s/agent/app/` at retirement |
| 11 | Service keys | **One key per seam**, one implementation behind them | `inject [:tools]`, never `[:registries]`; per-agent overrides stay per-seam |
| 12 | Live agents vs the log | **`:agents`** (handles) + **`:sessions`** (append-only log) | No global "current session" key; an agent is a child context plus a handle |
| 13 | Where the loop lives | **A driver plugin** that registers a factory into `:agents` | No load-order coupling; a preset swaps the loop by swapping one plugin |
| 14 | Order of reuse | **bus → registry → tools → resources → session → loop → modes** | Each step produces what the next consumes; the first two landings leave kmet1 untouched |
| 15 | Shipping and the entry point | **One artifact, two apps; `--app new` selects kmet2** | `dist` needs no parameterization; the flag is the migration lever, and kmet1 stays the default until it is deleted |

"Remote" in phase 1 means **transport and concurrency, not tenancy**: one
principal per process, many clients, per-agent approval scoping (see
"Permissions and approvals"). Multi-tenant is additive because the seam is
already the shape of everything else — the token resolves to a principal, and
each principal gets a child context under the root, exactly as an agent does.

## What "Cordis architecture" means here

Cordis is the Koishi meta-framework. Its five mechanisms all transfer; what
does not is the JavaScript machinery wrapped around them:

| Mechanism | Cordis | kmet2 |
|---|---|---|
| Context | explicit side-effect scope; one `ctx` per plugin; children nest | **take** — `kmet.srce.ctx` over a `kmet.libs.scope` node |
| Services | name-keyed values on the context; consumers reach the key, never an implementation | **take** — `:tools`, `:agents`, `:ui`, … |
| `inject` | declared dependencies (array, or `{required, optional}`); Cordis holds a plugin pending until they exist; a changed service rolls dependents back and re-applies | **take** — same semantics, synchronous |
| Reversible effects | every `ctx.effect`/`ctx.on`/`ctx.plugin` registration is tracked and undone on unload | **take** — `kmet.libs.scope` already does the tracking |
| Event dispatch modes | `emit` / `parallel` / `serial` / `waterfall` | **take** — `kmet.agent.event-bus` has emit + two proto-collect paths |
| Fiber epochs, `inertia`, awaited transitions | async bookkeeping for JS promise lifetimes | **drop** — see "Synchronous drain" below |
| `Service` base class, `@Inject` decorators, TS declaration merging | JS/TS ergonomics | **drop** — data + protocols; `(service ctx :k)` |
| Plugin market (`package.json` service manifest) | publishing | **drop** — `extension.edn` + the extension dirs already do it |

### Prior art: DSH

DeepSeek Harness (`dsh`, MIT, developer preview) is the reference harness built
on Cordis, and four of its decisions are folded in below: the
`:agents`/`:sessions` split (live handles vs the append-only log), the
loop-as-driver that registers a factory so `:agents` stays inert until it
loads, `--dump-config` (the composed tree with each plugin's state) instead of
a flat list, and `setup(child-ctx, agent)` composing an agent's scoped world
before it is published. What is not taken: web-first (TUI, then `ui-tk`, web
later) and npm as the plugin boundary — kmet's isolated SCI contexts with a
per-extension `deps.edn` are the stronger isolation story, and they already
exist (`extensions/context.cljc`).

## Why the three-way split has to go

kmet today holds plugin state in three disjoint mechanisms, and each of the new
requirements is blocked by a different one:

| Mechanism | Where | Size | Blocks |
|---|---|---|---|
| the store node | `kmet.libs.scope`, resolved through `kmet.agent.runtime/node` | 216 call sites in 17 files | multi-session: every session resolves **the one** app node |
| process-global atoms | `extensions/core.cljc` (`extension-binding`, `extensions`, `session-atom`, `context-sink-atom`, `hook-seq`, `cli-flag-values`), `bash-executor` (`shell-options`), `tools/read` (`skill-content-resolver`), `keybindings` (`theme-fns-atom`), `run_code` (`bridge-queue`), `runtime` (`app-node`) | 18 `defonce` vars, 16 of them mutable cells | pluggable UI/modes: the UI port and the mode are process state, not per-context state |
| the extension capability map | `kmet.agent.extension` (461 lines) + `extensions/core.cljc` (1,585 lines) built once per `init` | 60 functions | every new service today is another key on that map |

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
| 2 | **`app`** | **new**: shared services (tools, commands, prompts, skills, events, models, permissions, workspace) and the session layer (session, sessions) |
| 3 | **`ui`** | **new**: `kmet.ui.tui`, `kmet.ui.tk`, `kmet.ui.headless` |
| 3 | **`mode`** | **new**: `kmet.mode.tui`, `kmet.mode.print`, `kmet.mode.rpc`, `kmet.mode.server` |
| — | — | the `kmet2` command (`kmet.app.core/-main`, shipped from `app`): parse args, build the root context, load plugins, run the selected mode |

Ranks are `modules.edn`'s `:rank` guard: **a module may depend only on a
strictly lower rank** (`kmet.test-module-deps`). `srce` at 1 may require
`libs`, `reakt` and `loader`, and must not require `hikap`, `ai`, `tui`, `tk`
or anything above. `ui` and `mode` at 3 may require `app` (2) and the backends
(2), never each other — they meet at the `:ui` service key, which is a keyword,
not a require.

`app` sits at **2**, not 3, for one reason: kmet1 must keep calling the shared
mechanisms while kmet2 grows. `kmet.agent.*` is rank 3, so anything it may
require has to be strictly lower; `app` at 2 lets kmet1's adapters call the
lifted registries, tool table and bus for the length of the transition.
Bumping `agent` to 4 would also work, but it encodes a falsehood (kmet2 above
kmet1) that outlives the transition. The cost of `app` at 2 is that rank 2
mixes "UI backends" with "shared services"; if the shared/version-specific
cut turns out to be real once sessions land, split `app` into `services` (2)
and `app` (3) — a subtree move and one manifest row, made with data instead of
a guess.

Two consequences of sharing rather than duplicating: `kmet.app.registry` is
kmet1's registry too, so the contract tests (one set of assertions, two
backends) are mandatory from the first landing; and the shared layer must not
rename the store keys kmet1's `/units` prints — namespaces move, addresses stay
(`[:app :tools]`, `[:app :commands]`, `[:kmet/tool name]`).

New modules are not `kmet.agent` namespaces: kmet1 keeps `kmet.agent.*`,
kmet2 claims `kmet.srce.*`, `kmet.app.*`, `kmet.ui.*`, `kmet.mode.*`. The
prefix is the version boundary, so a kmet1 and a kmet2 namespace can never
shadow each other on one classpath.

Wiring a module touches four literals that the manifest guard holds in step:
`modules.edn`, `bb.edn` `:paths`, `deps.edn` `:paths` + `:jolt/build :embed`,
and `modules/<name>/deps.edn`.

## The kernel

```
modules/srce/src/kmet/
├── srce.clj         — the public API: root/child/service/provide!/plugin!/inject/effect/on/emit/waterfall
└── srce/
    ├── srce.md      — design doc (scope.md's sibling): the context model, the drain, the rules
    ├── ctx.clj      — the context value: node, parent, children, config, effect sink, service lookup
    ├── service.clj  — provide!/release!, change notification, interception
    ├── plugin.clj   — plugin spec + malli schema, inject normalization, fibers, drain, epochs
    ├── event.clj    — emit / parallel / serial / waterfall; ctx-scoped subscriptions
    └── config.clj   — malli validation at load time, layered config resolution
```

There is no `effect.clj`: the effect tree **is** `kmet.libs.scope`, used
as-is. Moving it up would break libs' self-containment rule (`kmet.libs.*`
may require only `kmet.libs.*`), and a re-export shim would be a copy of a
name rather than of the behavior. `kmet.srce.ctx` wraps `scope/node` and
exposes `effect`/`on` as teardowns on the context's own node.

Consumers require exactly one namespace — the kernel's surface is about ten
functions, and a second alias in every app file buys nothing:

```clojure
(ns kmet.app.session
  (:require [kmet.srce :as srce]))

(srce/root {:config {:cwd (fs/cwd)}})      ; the process root; kmet1's app-node
(srce/child ctx {:name :agent/7f3a :config {:model "anthropic/claude"}})
(srce/service ctx :tools)                  ; value or nil — never a global
(srce/provide! ctx :tools tool-table)      ; → disposer
(srce/plugin! ctx web-search-plugin {:max-results 5})  ; → fiber
(srce/effect ctx f)                        ; → disposer
(srce/on ctx :turn-end handler)            ; → disposer
(srce/waterfall! ctx :tool-call payload)   ; 4 modes, all ctx-scoped
```

The alias is derived from the name (`srce/`), as the repo's other requires
are (`scope/`, `models/`, `protocols/`). The subnamespaces are srce
internals; nothing above the kernel requires them directly. This is a
deliberate single public surface, not the "delegating facade" the loop split
forbids: the kernel is one subsystem with one contract, not a set of modules
whose callers each pick their own.

### Rules

- **Synchronous drain, not async fibers.** Cordis's `inertia`/epoch/await
  machinery exists because JS plugin bodies are async and interleave. In
  Clojure the scheduler is a drain to fixpoint: `provide!`/`release!` mutates
  the service map and enqueues a pass; the pass activates every pending plugin
  whose `inject` is satisfied and disposes every active one whose dependency
  vanished, repeating until nothing changes. A reload is therefore
  deterministic, unit-testable, and has no promise surface.
- **Quiesce at `:agent-settled`.** Disposal never waits (`scope.md` §3, §7).
  The epoch queue drains at the settle boundary the loop already has, so a
  tool swapped between turns reaches the next attempt and never the one in
  flight — the rule `scope.md` §4 states for loop stages, applied to
  services. One owner per concern: `:agents` owns the queue
  (`request-reload!`), the loop driver owns the boundary (`settle!` in its
  `finally`), and `/reload` with no agent mid-turn drains immediately through
  `:agents/idle?`.
- **No global node.** `kmet.agent.runtime/node`'s job is done by an explicit
  `ctx` argument. A guard test bans `runtime/node` in `kmet.srce.*`,
  `kmet.app.*`, `kmet.ui.*`, `kmet.mode.*`; the 216 call sites migrate in
  landings 2–4.
- **One implementation per facility.** The effect tree is `kmet.libs.scope`,
  used as-is — not copied, not re-exported (AGENTS.md "Shared layers are
  extended, not copied"). The kernel adds services, plugins and events on top
  of it; it never reimplements owner paths or LIFO disposal.
- **A plugin's `apply` returns its disposer** (or nil). Everything else is
  registered through `srce/effect`; nothing needs a `shutdown` function, though
  one is accepted as sugar.
- **Failures are reported, never swallowed.** `scope/*error-reporter*` keeps
  its semantics; the kernel binds it to the app's error log at the root.

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
   [:inject    {:optional true} [:maybe inject-schema]]
   [:config    {:optional true} [:maybe :map]]        ; a malli schema
   [:apply     fn?]
   [:reusable? {:optional true} boolean?]])           ; default false
```

| Cordis | kmet2 |
|---|---|
| `Plugin.Function (ctx, config)` | `:apply (fn [ctx cfg] …)` |
| `Plugin.Object { apply }` | the plugin map |
| `Plugin.Constructor` (`Service` subclass) | `(defrecord S …)` built inside `:apply` — never the plugin |
| `Plugin.Base` (`name`/`inject`/`Config`/`reusable`) | the map's keys; `:config` is a malli schema |
| `IPlugin` type | `plugin-schema`, checked once at load (`{:type ::bad-plugin :problems …}`) |
| plugin *source* polymorphism | `kmet.loader.core/Loader` protocol — reused as-is (SCI / Jolt native / classpath) |

`defplugin` is sugar over the map, not an interface — the same trade
`kmet.tui.macros/defcomponent` makes, and the shape the extension docs already
teach:

```clojure
(defplugin tool/web-search
  {:inject {:tools {:required true}}
   :config  [:map [:max-results [:int {:min 1}]]]
   :apply   (fn [ctx {:keys [max-results]}] …)})
```

It gives a named var, a compile-time shape check, and a card in
`kmet2 --dump-config` — the composed plugin tree with each plugin's state and
resolved config, which is the drain made visible, and the first thing to read
when a plugin sits in `:pending`.

`:reusable?` mostly disappears. Cordis needs it because one plugin may load
into several contexts; kmet2 sessions are **child contexts that inherit** the
root's plugins rather than re-loading them, so only genuinely stateless
registries should be loadable twice.

**Extensions become plugins additively.** `extension.edn` gains an optional
`:inject` (the same schema); the loader wraps the extension as

```clojure
{:name   (keyword ext-name)
 :inject (:inject manifest)
 :apply  (fn [ctx cfg] (init (bridge/api-for ctx)))}
```

Without `:inject` an extension behaves exactly as today — no waiting, no
reload-on-change — so no shipped or installed extension needs a source change,
because `api` was always a map. With it, the extension gets pending/activate/
reload semantics for free, and its owner path still disposes on unload.

## Service catalog

This table is the application — the reason the architecture is worth the
rewrite.

| Service | Contents | Today |
|---|---|---|
| `:llm` `:models` | provider call, model registry, auth status | `kmet.ai.*` |
| `:tools` `:commands` `:prompts` `:skills` `:modes` | registries: name → value, per-context | `agent.registry` + 4 namespaces |
| `:agents` | live handles: `create!` / `resume!` / `get` / `list` / `roots`; a handle owns one agent, its session and its child context | `session/runtime.clj`'s `Engine` — already a handle |
| `:sessions` | the append-only session log and its persistence; storage backends sit behind it | `session/core.clj` (1.7k) |
| `:events` `:hooks` | bus + 4 dispatch modes + hook chains | `event-bus.clj`, `apply-input-hooks` |
| `:ui` | capability map: render, input, dialogs, notify, widget set | `extension-binding :ui`, lifted out |
| `:workspace` `:bash` `:permissions` | cwd, shell, sandbox policy | `tools/bash`, `bash-executor`, `libs/path` |
| `:config` `:auth` | layered settings, credentials | `config.clj`, `ai/auth.clj` |

**One key per seam, one implementation behind them.** Plugins write
`inject [:tools]`, never `[:registries]`. The registries share one machine
(`kmet.app.registry`: tag + name, 78 lines) but stay separate services, because
their consumers differ — the model sees `:tools`, the slash parser `:commands`,
the prompt builder `:prompts` — and because a per-agent override should be
per-seam rather than all-or-nothing. Cordis's `ctx.tools` / `ctx.llm` /
`ctx.sandbox` is the precedent.

**The loop is a driver, not a service.** `:agents` is inert until a loop driver
registers a factory; a driver is a plugin like any other. That is the answer to
the loop-vs-runtime cycle: neither waits on the other's load order, and a
preset that swaps the loop swaps one plugin.

**No runtime schemas on service values.** A service value is mostly a map of
fns, so a malli schema can only assert `fn?`; the catalog entry plus one
contract test per service is the check that pays. Plugin *config* is still
schema-validated at load.

## Permissions and approvals

`:permissions` splits the decision from the question. The loop's existing
blocking point stays exactly as it is — the `before-tool-call` hook chain,
whose result is already `{:block true :reason …}`:

```clojure
(srce/provide! ctx :permissions
  {:policy   (fn [ctx tool-call] :allow | :deny | :ask)   ; pure, per-agent
   :request! (fn [ctx request] …)                         ; may block; abortable
   :remember! (fn [ctx tool-call scope] …)})              ; :once | :agent | :workspace
```

- `:policy` is synchronous and per-agent (`setup` can shadow it), so the
  common case costs nothing.
- `:ask` goes to `:request!`, which blocks with a timeout and is abortable
  through the run's `:signal`. The TUI answers with a dialog, a server answers
  later over the wire (the `:request`/`:response` pair), and headless answers
  with its configured default.
- `:remember!` records a decision at one of three scopes — that is what makes
  remote work without a prompt per call.
- A `:deny` becomes the hook result the loop already understands, so **no tool
  signature changes** anywhere.

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
             :request!    (fn [ctx req] …)           ; the approvals channel
             :notify      …
             :capabilities #{:chat-history :footer :status :images}}))}
```

**The widget set is where portability lives.** App UI builds structure from
`:widget-set` constructors, each backend implementing them with its own
toolkit; anything that needs a widget only one toolkit has stays inside that
provider's namespace. That is the rule that keeps landing 5 a move rather than
a rewrite — `kmet.ui.tui` may keep TUI-only chrome, and only the structural
parts are extracted into shared code.

Today's providers, and the one that proves the contract is worth having:

1. `:ui/headless` — the capability map with headless defaults (already exists:
   `extensions/headless-ui-port`).
2. `:ui/terminal` — components over `kmet.libs.hikap.core`, which already
   takes its backend map as an argument.
3. `:ui/tk` — the same contract, second backend (Jolt).
4. `:ui/web` — later; a third backend, no app change.

App plugins use `(srce/inject ctx {:ui {:optional true}} …)` and upgrade
themselves when a richer UI attaches: Cordis's optional-inject, used for what
it was designed for. The distinction to document once, on the first line of
each namespace: **`kmet.tui` renders components; `kmet.ui` decides which
backend renders.**

The cost is honest: `modes/interactive/*` is 5,292 lines (`layout.clj` 918,
`ui_registry.clj` 708) written against `kmet.tui`. Landing 4 moves it wholesale
into `kmet.ui.tui` and extracts capabilities incrementally; it does not rewrite
it as view-model + renderer.

## Pluggable modes

```clojure
{:name :mode/rpc
 :inject [:agents]
 :apply (fn [ctx cfg] (serve-stdio ctx cfg))}     ; returns the disposer
```

- `--mode` is a lookup in the `:modes` registry, not a `require` in
  `kmet.agent.core` (which hard-requires all three today).
- Third-party modes ship as extensions: the loader already isolates them and
  the contract already exists.
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
(agents/create! root {:cwd … :model … :session-id …
                      :setup (fn [agent-ctx agent] …)})  ; → handle
(agents/resume! root id)                                 ; rebuild from the log
(agents/list ctx)   (agents/get ctx id)   (agents/roots ctx)
(agents/dispose! handle)                                 ; unwinds the scope
```

`setup` runs before the handle is published: it composes the agent's scoped
world (its tools, prompt sections, listeners) on its own context, so nothing
observes a half-built agent — DSH's `setup(agentCtx, agent)` contract. The
service is inert until a loop driver registers a factory, which keeps
`:agents` and the loop from depending on each other's load order.

**Agent scope: inherit by default, mutate explicitly.** `:agents/create!` loads
no plugins; the agent's child ctx inherits root services through the chain, and
per-agent composition happens in `setup` (a shadowed `:tools`, `:llm`,
`:permissions`) or by loading a plugin a profile marks `:per-agent true`. Root
plugins are never re-loaded per agent — that is why `:reusable?` stays unused —
and isolation is by context, not by process, so two agents hold different tool
tables for free.

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
"Permissions and approvals"). `--mode rpc` is `mode-server` with one implicit
agent on stdio; transports are a unix socket where the host has one, TCP
loopback otherwise. One protocol, one implementation, no drift.

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
| `shell-options` | `bash_executor.clj` `defonce` | per-context `:bash` service value |
| `System/getProperty "user.dir"` fallback | `libs/path.clj` | fall back to the context's `:workspace` cwd |
| `cli-flag-values`, `extension-binding`, `extensions` | `extensions/core.cljc` | registrations on the ctx; the atoms become the no-node read cache and then die |
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
| needs nothing | lift as-is | 84 | 28,178 | tool bodies (`read`/`write`/`edit`/`bash`), `session/core.clj` (1.7k), the `event-types` vocabulary |
| one argument | parameterize | 7 | 4,076 | `registry` (3 `runtime/node` reads), `commands/builtins` (6), `modes/interactive/commands` (6), `session/runtime` (3), `loop/state` (2), `core` (1), `modes/interactive` (1) |
| registry-wired | re-point | 4 | 1,305 | `tools/registry`, `skills`, `prompts`, `commands` |
| owns state | re-host | 9 | 6,243 | `extensions/core` (7 `defonce`s), `debug` (3), `extensions/context` (2), `bash_executor`, `event_bus`, `keybindings`, `runtime`, `tools/read`, `tools/run_code` (1 each) |

The fourth row is the only real porting: ~20% of the module, and it is the
half that owns state — precisely what the context replaces. The bus is the
template: 90% of `event-bus.clj` is the `event-types` vocabulary (pure data,
lifts as-is) and the mechanism is one atom plus two functions.

### Order

**bus → registry → tools → commands/prompts/skills → session → loop →
modes/UI.** Each step produces what the next consumes:

- the bus is one atom with no ownership semantics, and `emit-event!` keeps its
  signature, so kmet1 cannot notice; it also unblocks the rest, because
  `srce/on` is how a plugin registers anything;
- `registry` (78 lines) decides the ctx-addressed lookup shape for all four
  registries at once;
- `tools` is the first real service, and its shape is copied by the next three
  — their handlers already take a ctx-shaped map `{:runtime :ui :frontend}`, so
  that step is mostly renaming keys;
- `Engine` is already a record with operations, so `session` is a lift rather
  than a fork; only its singletons are re-hosted;
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
mechanical: at retirement, `s/kmet\.agent\./kmet.app./` over a known list.

**Three exceptions.** `kmet.agent.extension` keeps its name forever — it is
the extension ABI, and installed extensions write it in their `:require`; if
kmet2 ever wants `kmet.extension`, add it as an alias and keep the old name
for a release. Namespaces whose concept dies are deleted, not moved
(`kmet.agent.runtime` has no successor; `loop*`, `session/runtime` and
`modes/interactive/*` are re-hosted under new names while kmet1 keeps its
copies). New kmet2-only code uses the new prefixes from day one.

| Now | At retirement |
|---|---|
| `kmet.agent.registry`, `.tools.registry`, `.event-bus`, `.config`, `.skills`, `.prompts`, `.commands` | `kmet.app.*` — one prefix substitution |
| `kmet.agent.extension` | unchanged — the ABI |
| `kmet.agent.runtime` | deleted |
| `kmet.agent.loop*`, `session/runtime`, `modes/interactive/*` | new `kmet.app.*` / `kmet.ui.tui` |

### Per-lift checklist

1. **Remove the upward require.** `registry.clj` requires
   `kmet.agent.runtime` for `node`; while that edge exists the file cannot
   live at rank 2. Parameterize it to take `ctx`/`node` (keeping a 1-arg arity
   that resolves the root) — *that commit is the extraction*.
2. Lift in reverse intra-module require order; compute it once with
   `kmet.tasks.changed/read-ns-form` + `ns-requires` — it is the only valid
   sequence.
3. `git mv` the file and its test; touch nothing else.
4. Wire the module into the four literals (`modules.edn`, `bb.edn` `:paths`,
   `deps.edn` `:paths`/`:deps`, `:jolt/build :embed`) and the three extension
   `bb.edn`s — `kmet.test-modules-manifest` names whichever you missed.
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

A profile is EDN data, not code — the layering `settings.edn` already uses
(maps merge, everything else replaces):

```clojure
{:profile/minimal
 {:plugins [[:tool/bash {}]
            [:tool/edit {}]
            [:loop/basic {:max-turns 50}]]
  :config  {:permissions {:policy :allow-all}}}}
```

- Plugin ids are keywords that `defplugin` registers — that is what lets
  `--dump-config` print the resolved tree instead of a wish list.
- Layers: bundled default < `--profile` < the user's `settings.edn` overlay.
- Presets (Standard / Minimal / PTC) ship as embedded EDN staged beside the
  binary the way `target/kmet-bundled` is, so a project can ship its own.

## One artifact, two apps

`dist` keeps shipping one artifact per host (`kmet` / `kmetj` / `kmetc`), and
that artifact carries **both** applications — which it must, because landings
2–4 make kmet1 call the lifted `kmet.app.*` code. The entry point selects:

```sh
kmet --app new                  # kmet2 (development)
kmet --app new --mode print "…"
bb start --app new              # the same switch from a checkout
kmet                            # kmet1, the default, unchanged
```

- `--app new` (values `new` | `old`, `old` the default) is parsed in
  `kmet.agent.core` **before** anything else, then stripped from the argv
  forwarded to the selected `-main`.
- The hand-off is dynamic — `(requiring-resolve 'kmet.app.core/-main)` — never a
  static require: neither app pays the other's load time, and a missing
  `kmet.app.core` (a `--dev` build, an older artifact) answers "this artifact
  carries only the kmet app" instead of a null dereference.
- `bb start` stays the only start task; a `start2` would be a second entry point
  to keep in sync. If the typing grates, it is one line:
  `:task (apply core/-main "--app" "new" *command-line-args*)`.
- `bb check` must require both entry namespaces (`[kmet.agent.core]`,
  `[kmet.app.core]`), because the dispatch is dynamic and the load is the
  assertion.
- `dist` needs no parameterization: `main-class` stays `kmet.agent.core`, the
  embed list is already every module's `src`, and the artifact names describe
  the host, not the app.

What this deletes: `--prefix`, a second `:jolt/build :embed` list, a
`kmet2j`/`kmet2c` naming scheme, and a second smoke test. What it keeps: one
version string, one artifact, one PATH entry. When kmet1 is retired, `--app
new` becomes the default and the flag is dropped in the same release — the flag
*is* the migration lever.

## Landings

Each is independently shippable and gated.

| # | Landing | Ships |
|---|---|---|
| 0 | `modules/srce`: `ctx` on `kmet.libs.scope`, `service`, `plugin` drain, `event` modes, `config` | srce unit tests; no `kmet.agent.*` require |
| 1 | module wiring + guards (module-based require, no-global-node, drain), `--app new` dispatch + `check` requiring both entries, `defplugin`, `--dump-config` | `kmet.test-module-deps` / `test-modules-manifest` green; each guard fails on a deliberate violation |
| 2 | the bus: listener table into the runtime; `emit-event!` keeps its signature | contract test over both delivery paths; kmet1 green |
| 3 | `registry` parameterized then lifted; `:tools` service; commands/prompts/skills re-pointed; `extension.edn :inject` + `grep-tool` ported as a plugin | contract tests over both backends; the ported extension green in its own project |
| 4 | `Engine` → `:agents` (+ `:permissions` policy/request!/remember!) and one `:sessions` facade; the remaining `runtime/node` reads; the globals re-hosted | kmet2 runs `mode-print`; kmet1 untouched |
| 5 | `:ui` service (required/optional capabilities, widget set) + `ui-headless` + `ui-terminal` + `ui-tk`; modes as plugins | print/rpc/tui parity via `scripts/capture/`; tk provider on Jolt |
| 6 | `:agents` registry (create/resume/list) + `mode-server` (rpc = one-agent server); `:agent-id` and the request/response pair on the wire; approvals end to end | two agents concurrently, one stalling client, an approval answered by a client |
| 7 | loop re-hosted as a driver plugin; profiles as EDN layers; presets = plugin sets | pi-alignment green; a tool swaps mid-session at `:agent-settled`; `--profile minimal` boots |

Landings 0–1 need no kmet1 change. 2–4 lift kmet1 code without renaming it, so
kmet1 stays green throughout. 5 is the first landing that moves kmet1 code
*and* renames it (interactive → `kmet.ui.tui`), so it ships with a `--mode`
compat shim: `kmet` keeps its current dispatch until kmet2 passes the capture
diff.

## Gates

- `bb test`, `bb test-ext`, `bb lint` (zero findings), `bb format-check`,
  `bb check` — unchanged, plus `bb test-changed` / `bb lint-changed` /
  `bb format-check-changed` while iterating.
- `kmet.test-module-deps` — ranks stay strictly downward.
- `kmet.test-modules-manifest` — `modules.edn`, `bb.edn`, `deps.edn` and the
  embed list stay in step.
- New: srce self-containment (`kmet.srce.*` may require only `kmet.libs.*`,
  `kmet.loader.*` and third-party code), the no-global-node guard, the
  **module-based require guard** (a namespace may require only a same- or
  lower-ranked module's file — built on `kmet.test-seam-requires`'s ns-form
  reader and `kmet.test-module-deps`'s ranks), and the headless-mode
  plugin-graph assertion.
- New: a profile test — layers merge, `--dump-config` resolves to the same
  plugin list the boot uses, and an unknown plugin id fails at load.
- New: the two-direction load test — `kmet --app old --version` leaves
  `kmet.app.core` unloaded, and `kmet --app new --version` loads kmet2 without
  any kmet1 *runtime* namespace (`kmet.agent.session.runtime`,
  `kmet.agent.loop`) appearing. It is what keeps someone from turning the
  dispatch into a static `:require`.
- New: a drain test that cycles a 3-plugin graph through provide/release/reload
  to fixpoint and asserts no leaked registrations (`scope/ordered-registrations`
  empty after disposal).

## Not doing

- Forking the repo, or rewriting `kmet.libs.*` / `kmet.ai.*` / `kmet.tui.*` /
  `kmet.tk.*` / `kmet.loader.*`.
- Porting Cordis's TS ergonomics: declaration merging, `Service` base class,
  `@Inject` decorators, plugin market.
- Porting its async fiber bookkeeping (`inertia`, epochs with awaits,
  `PromiseLike<Fiber>`).
- Multi-user accounts, quotas, or an ACL model in phase 1.
- Renaming `kmet.agent.*` namespaces at lift time, or inventing intermediate
  prefixes (`kmet.shared.*`, `kmet.services.*`): every rename is paid once more
  at retirement, and the last one is meant to be a `sed`.
- A web UI before `ui-tk` has proven the capability contract.
- Deleting kmet1's `kmet.agent.*`; it stays until kmet2 passes the full gate
  set and the pi-alignment suite, then it is retired in one landing.
