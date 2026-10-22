# kmet2 — a Cordis-shaped kmet on shared modules

Temporary note at the project root, beside its package documentation. Kernel and
application are separate notes: [`srce.md`](modules/srce/src/kmet/srce/srce.md)
is the kernel design, beside its code, and this file is the application side,
which folds into
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
| 4 | Hosts | **bb, Jolt, JVM — all portable** | No host-private concurrency or FFI in `kmet.srce.*`, `kmet.app.*`, `kmet.mode.*`; threads/queues go through `kmet.libs.concurrent`, and the kernel is single-threaded with no awaitable of its own |
| 5 | Second UI provider | **`ui-tk`**, web later | Proves the `:ui` capability contract against an existing backend (`kmet.tk` is cross-host — Tcl through `babashka.ffi` on bb and `jolt.ffi` on Jolt, so the provider covers a second toolkit on two hosts and is absent on JVM) |
| 6 | Tenancy | **Single principal in phase 1** | One bearer token per server, many clients; `:permissions` scoping is user-level control, not isolation. Multi-tenant later = token → principal → child ctx, one level above agents |
| 7 | The kernel's name | **`srce`** — heart; Cordis is Latin for the same | One prefix, `srce/` alias. `kernel`, `kkernel`, `zrno`, `zrnce` rejected |
| 8 | Plugin ABI | **A validated value**, no `IPlugin` protocol | Cordis's Base fields as a map (`:name :inject :config :provide :apply`), checked once at mount; loading polymorphism stays on `kmet.loader.core/Loader`, and no host class identity crosses the SCI boundary |
| 9 | Shared layer rank | **`app` at 2** (`srce` 1, `ui`/`mode` 3, `agent` 4) | kmet1's rank-4 adapters can call the lifted registries and the new UI; the legacy-above-new inversion is explicit and ends with `agent` |
| 10 | Namespaces on reuse | **Move the file, keep the namespace** | 354 `with-redefs` sites keep intercepting; the final rename is one `s/agent/app/` at retirement |
| 11 | Service keys | **One key per seam**, one implementation per scope | `inject [:tools]`, never `[:registries]`; per-agent overrides stay per-seam |
| 12 | Live agents vs the log | **`:agents`** (handles) + **`:sessions`** (append-only log) | No global "current session" key; an agent is a child context plus a handle |
| 13 | Where the loop lives | **`:agents` registry + `:agentLoop` driver** (dsh's split) | `:agents` is the live registry; the driver provides `:agentLoop`; a preset swaps the loop without touching the registry |
| 14 | Order of reuse | **bus → registry → tools → resources → session → loop → modes** | Each step produces what the next consumes; the first two landings leave kmet1 untouched |
| 15 | Shipping and the entry point | **One artifact, two apps; `--app new` selects kmet2** | `dist` needs no parameterization; the flag is the migration lever, and kmet1 stays the default until it is deleted |
| 16 | Kernel scope | **Light Cordis core** — ctx tree, services + inject + refresh, fiber, effects, events, plugin registry | Only the concepts this file needs; the deferred list and the parity appendix are in [`srce.md`](modules/srce/src/kmet/srce/srce.md), and `kmet.libs.scope` is deleted at 4e, once the last `(scope/…)` site is gone |
| 17 | Kernel concurrency | **One owner thread + an optimistic pass flag** | `srce/settle!` at the boundary applies whatever crossed threads; a foreign-thread `provide!` is late, never corrupting ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §3) |

"Remote" in phase 1 means **transport and concurrency, not tenancy**: one
principal per process, many clients, per-agent approval scoping (see
"Permissions and approvals"). Multi-tenant is additive because the seam is
already the shape of everything else — the token resolves to a principal, and
each principal gets a child context under the root, exactly as an agent does.

Kernel and application are separate notes: the kernel's ctx model, refresh
pass, thread rule and plugin ABI are [`srce.md`](modules/srce/src/kmet/srce/srce.md); this file is the
application side — services, agents, sessions, profiles, the artifact, and the
landings.

## Prior art: DSH

DeepSeek Harness (`dsh`, MIT, developer preview) is the reference harness built
on Cordis, and five of its decisions are folded in below: the
`:agents`/`:sessions` split (live handles vs the append-only log), the
loop-as-driver that provides `:agentLoop` so modes wait for it,
`--dump-config` (the composed row tree, assembled by the same patch algorithm
the boot uses — no boot, no plugin state, `!!js` left unevaluated; the booted
diagnostic is ours) instead of a flat list, `setup(child-ctx, agent)` composing
an agent's scoped world before it is published, and the
**profile/bundle/patch** composition model — ordered rows, patches by row id,
explicit plugin sources — with vars and extension ids in place of npm package
names, instead of a keyword plugin registry. What is not taken: web-first
(TUI, then `ui-tk`, web later) and npm as the plugin boundary — kmet's isolated
SCI contexts with a per-extension `deps.edn` are the stronger isolation story,
and they already exist
(`modules/agent/src/kmet/agent/extensions/context.cljc`).

## Inspection: `/units` and `--dump-state`

`--dump-config` answers "what is composed" without booting. The other half is
the booted view — kmet1's `/units`, which prints one flat `<owner> keys=[…]`
line per store owner, read from a session's own tree when it has one and from
the app root otherwise (`app_runtime/owner-listing`; two command tests, three
listing tests).

kmet2 renders the **tree** instead of the store, keeping the two properties
that made `/units` usable: one unit per line with no indentation (the path is
in the line), and a stable order (parents before children, fibers in mount
order).

```
[:app]                             ctx      children=3
[:app :commands]                   fiber#4  :active   effects=[command units, listener :turn-end]
[:app :sessions]                   fiber#2  :active   provides=[sessions]
[:agent/7f3a]                      ctx      children=2
[:agent/7f3a :tools]               fiber#7  :active   provides=[tools]   effects=[tool read]
[:agent/7f3a :tool/web-search]     fiber#12 :active   inject=[tools]    effects=[tool web-search]
[:agent/7f3a :ui/terminal]         fiber#9  :pending  inject=[ui]       waiting-for=[ui]
[:agent/7f3a :ui/tk]               fiber#14 :failed   inject=[ui]       error="tcl not found"
```

- A **ctx** line is a path and its child count; a **fiber** line is its state,
  its `inject`, what it `provides`, and its effect labels.
- **`:pending` and `:failed` are why the command exists at all.** The store
  could only report absence ("nothing registered under that key"); a fiber says
  *waiting for `[:ui]`* or *failed with this error* — which is the question
  someone typing `/units` actually has, and the one `--dump-state` answers for
  the booted process.
- **One renderer, two entry points:** `kmet.app.inspect/units` renders the
  subtree of the ctx the command was invoked in (the agent's, when run inside
  one) and `--dump-state` renders the root's. Same argument as
  `--dump-config`: a command and a flag that compute the listing separately
  drift.
- **Effect labels are the inspection contract** ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §4).
  `(srce/effects fiber)` is what the listing renders, so `:tool/read` and
  `:command/units` are load-bearing; a catch-all label makes the line useless.
- The accessors are already the kernel's — `srce/children`, `srce/services`
  (the realm-aware merge) and `srce/effects` — and the fiber record carries the
  rest: `:state`, `:error`, the normalized `:inject`, the names it `:provides`
  and what it is `:waiting-for` ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §4), so the renderer is a
  flag, not a kernel change. No listing code below rank 3.

Landings: this is **4d**. 1–2 leave `/units` alone. 3 was to add the
fiber/service section **beside** the store section and did not. 4c-ii-a made
`/units` **half store, half ctx** — a per-registry-service line beside the owner
walk — so the command stays useful while the registries live in the tree and the
store still holds what has no seam yet. That is transitional on purpose: 4d
deletes both halves for one tree renderer. The store section cannot be deleted before kmet1 registers in
the tree instead of the store and resolves a root ctx — 4c — and the handler
re-points at the invocation ctx only once `runtime/agent-node` is gone, so the
renderer ships after the cutover even though it builds on nothing 4a–4c added:
if the sub-landing in flight needs the observation sooner, `--dump-state` moves
up and the store section stays beside it for that landing. `agent-node` went at
4c-ii-b, so `/units` now renders the **app root's** store plus the registry
lines — an engine no longer has a tree of its own — and that is the pair 4d
replaces.

## What the seams settle

Settled by the harness this port follows and by kmet1's own seam tests. The
kernel rows (`inject`, event modes, effect ordering, fiber failures, multiple
mounts) are in [`srce.md`](modules/srce/src/kmet/srce/srce.md).

| Question | Answer |
|---|---|
| `:agents` vs the loop | `:agents` is the live registry; the loop driver provides `:agentLoop` (dsh's `core/agent` / `core/agent-loop`). |
| Agent creation / teardown | `create!` is serialized and rolls back a failed `setup`; `dispose!` is a loop-coordinated awaitable (dsh's `agent/created` + teardown ordering). |
| Reload boundary | The turn boundary, exposed as `:agent-settled`; the kernel's `restart!` is synchronous and `settle!` applies anything that crossed threads, so the app decides when to call either (see "Reload and the turn boundary"). |
| Per-agent capability set | An agent preset (a row set on the child ctx) plus an `isolate` realm for seams that must not inherit. |
| Approvals | `:permissions` policy + `tools/pre-execute` waterfall + one `:approvals` delivery seam. |
| Execution seams | `:workspace`/`:fs`/`:shell`/`:subprocess`/`:sandbox`/`:permissions`, not one `:bash` bundle. |
| Profiles / HMR | Profiles stack bundles and patch by row id; HMR is a loader/profile feature, not the kernel. |
| Plugin sources | Explicit sources in rows; a row's card is read from the plugin map, never from a manifest beside it. |

Adopted from the start, because landings 4–6 cannot compose agents without
them: `:systemPrompt` (per-agent sections are what `setup` registers, and a
tool's prompt snippet belongs to the tool, not to one string builder) and the
session projections behind `:projections` (the inbox and the turn boundary are
derived state over the log). Deferred: dsh's session-format migration family,
the browser transport, and telemetry.

Still kmet-specific: the `:ui` capability contract, tenancy, the wire format,
EDNL sessions, the extension ABI/SCI boundary, and the kmet1 migration.

## Reload and the turn boundary

The kernel is synchronous: `restart!` is dispose-and-mount, and a provider
change runs the refresh pass to a fixpoint before the call returns — unless
another thread already holds the pass, in which case the change is that
owner's (and `settle!` returning with no owner means `:changes` is empty,
[`srce.md`](modules/srce/src/kmet/srce/srce.md) §3) — so *when the app allows a reload at all* is this
document's rule, a kmet rule rather than a borrowed one. Cordis reloads
immediately on an epoch change, and dsh serializes reloads through its HMR
queue (`ctx.hmr.runExclusive`), not through an agent boundary.

- The loop calls `restart!` only at the turn's closing boundary, so a service
  swapped between turns reaches the next attempt and never the one in flight —
  the snapshot rule kmet1's loop stages already follow (`scope.md` §4), applied
  to services.
- The boundary is `:agent-settled`: kmet1 already emits it once per prompt from
  the loop's `finally`, and `session/runtime.clj` already treats it as terminal.
  It is synchronous fire-and-forget today, so making it the quiesce point gives
  the bus a serial/awaited path — landing 2 cannot both claim `emit-event!` is
  untouched and hand landing 7 an awaited boundary.
- One owner per concern: `:agentLoop` owns the turn and the queued reload
  (`request-reload!`), `:agents` answers `when-idle`, and `/reload` with no
  agent mid-turn reloads immediately.
- **The boundary is also the drain.** `srce/settle!` runs any provider change
  that landed while another thread held the pass, so `:agent-settled` means
  both "no turn in flight" and "no pending refresh". A tool thread that must
  mount or provide submits instead of mutating: its change is queued and
  applied at the boundary.
- **A remounted dependent is stateless.** `{:ui :optional}`'s upgrade is
  dispose + mount ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §3), so a plugin's `apply` closure does
  not survive a provider swap. State lives in a service handle (`:agents`,
  `:sessions`); a consumer that needs history reads it from there.

## Why the three-way split has to go

kmet today holds plugin state in three disjoint mechanisms, and each of the new
requirements is blocked by a different one:

| Mechanism | Where | Size | Blocks |
|---|---|---|---|
| the store node | `kmet.libs.scope`, resolved through `kmet.agent.app_runtime/node` | 19 `app-runtime/node` calls in 13 source files; 43 files require the namespace | multi-session: every session resolves **the one** app node |
| process-global atoms | `modules/agent/src/kmet/agent/extensions/core.cljc` (`extension-binding`, `extensions`, `session-atom`, `context-sink-atom`, `entry-sink-atom`, `hook-seq`, `cli-flag-values`), `bash-executor` (`shell-options`), `tools/read` (`skill-content-resolver`), `keybindings` (`theme-fns-atom`), `run_code` (`bridge-queue`), `runtime` (`app-node`) | 18 `defonce` vars, 16 of them mutable cells | pluggable UI/modes: the UI port and the mode are process state, not per-context state |
| the extension capability map | `kmet.extension` (461 lines) + `modules/agent/src/kmet/agent/extensions/core.cljc` (1,586 lines) built once per `init` | 42 api keys | every new service today is another key on that map |

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
dispatch at `kmet.ui.tui` instead of forking 16,813 lines of TUI — and it ends with the
module. The cost of `app` at 2 is that rank 2 mixes "UI backends" with
"shared services"; if the shared/version-specific cut turns out to be real once
sessions land, split `app` into `services` (2) and `app` (3) — a subtree move
and one manifest row, made with data instead of a guess.

Two consequences of sharing rather than duplicating: `kmet.app.registry` is
kmet1's registry too, so the contract tests (one set of assertions, two
backends) are mandatory from the first landing; and the shared layer must not
rename the addresses kmet1's `/units` prints — namespaces move, addresses stay:
`[:kmet/tool "read"]` and `[:kmet/command "units"]` are the keys a registry
owns, and `[:app :tools]` / `[:app :commands]` are the paths the mount keeps
when the store becomes a ctx tree.

New modules are not `kmet.agent` namespaces: kmet1 keeps `kmet.agent.*`,
kmet2 claims `kmet.srce.*`, `kmet.app.*`, `kmet.ui.*`, `kmet.mode.*`. For new
code the prefix is the version boundary, so a kmet1 and a kmet2 namespace can
never shadow each other on one classpath; a lifted file keeps its `kmet.agent.*`
name and moves modules instead (see "Two axes").

Wiring a module touches four manifests that the guard tests hold in step:
`modules.edn`, `bb.edn` `:paths`, `deps.edn` `:jolt/build :embed`, and
`modules/<name>/deps.edn` — plus the `:deps`/`:local/root` edge in the module
that depends on it.

## The extension host

An extension is a plugin whose source is resolved by the extension loader; this
section is how the two models meet.

**Extensions become plugins additively.** `extension.edn` gains an optional
`:inject` (the kernel's schema, [`srce.md`](modules/srce/src/kmet/srce/srce.md)); the extension host wraps the
extension as

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
`kmet.extension`; it is `kmet.agent.extensions.core` (1,586) +
`kmet.agent.extensions.context` (1,568), and `create-extension-api` closes over
the app node and 42 api keys. All three files move into module `app` at rank 2
(landing 4b): the ABI keeps its `kmet.extension` name, and both host files keep
their `kmet.agent.*` names (the "Two axes" rule):

- `kmet.extension` has no `kmet.*` requires, so its file move is mechanical;
  installed extensions keep `(require [kmet.extension …])`. The loader's name
  whitelist is **not** just `kmet.extension` + `kmet.libs.*` + `kmet.tui.*`: it
  also admits exactly two `kmet.agent.*` namespaces,
  `kmet.agent.ui.tui.tool-renderers` and `kmet.agent.keybindings`
  (`extensions/context.cljc`). Both are ABI, both are required by shipped
  directory extensions, and both therefore constrain the UI move: a namespace
  that extensions may require cannot be renamed with a `sed`.
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
| `:agents` | live handles: `create!` / `resume!` / `dispose!` / `get` / `list` / `roots`, plus the control surface a front end drives — `send!` / `followup!` / `steer!` / `inject!` / `cancel!` / `when-idle` / `status`. A handle owns one agent, its session and its child context, and it is the **capability** that may tear it down; `get` returns the bare agent | `session/runtime.clj`'s `Engine` — already a handle, already 55 operations |
| `:agentLoop` | the driver: run a turn, abort, teardown ordering; it also registers the creation factory on `:agents` (dsh's `setFactory`), so `:agents` has no `create!` until a loop is mounted | the loop driver plugin (`kmet.agent.loop`) |
| `:sessions` | the append-only session log and its in-memory index, one log per agent | `session/core.clj` (1.7k) |
| `:sessionPersistence` | the storage backend under `:sessions` (EDNL files first; a second backend shadows this key, not the log) | the file half of `session/core.clj` |
| `:projections` | incremental derived state over the log — the inbox, the turn boundaries, the token meter — with a cache behind it | new |
| `:systemPrompt` | ordered sections, dynamic contexts, prompt variables and tool schemas, assembled by a waterfall before each request; per-agent composition lands here | `skills.clj/build-system-prompt` (one fn) + `context.clj` + each tool's `:prompt-snippet` |
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
`:agents` exists as soon as the registry is mounted, but it has no `create!`
until a loop registers the creation factory (dsh's `setFactory`), so
`inject [:agentLoop]` holds mode/creation callers pending until a driver is
mounted, and a preset that swaps the loop swaps one plugin — the teardown
ordering lives with the factory that implemented it. `check` covers a service
that becomes unready later.

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
what they need; providers supply what they can. Required of every provider:
`:kind`, `:render-tree`, `:request!`, `:capabilities`;
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

1. `:ui/terminal` — components over `kmet.libs.hikap.core`, which already
   takes its backend map as an argument.
2. `:ui/tk` — the same contract, second toolkit (Tk through
   `babashka.ffi`/`jolt.ffi`: bb and Jolt, not JVM), starting from the existing
   `kmet.gui-sample` widget skeleton (in `modules/tk`) rather than a blank page.
3. `:ui/web` — later; a third backend, no app change.

App plugins declare `:inject {:ui :optional}` and upgrade themselves when a
richer UI attaches; `(srce/inject! ctx :ui/terminal {:ui :optional} …)` is the
same mount without a plugin map, and its name is required because the listing's
path is built from it. The optional form (see [`srce.md`](modules/srce/src/kmet/srce/srce.md)) puts the
service in the epoch without holding the fiber pending. The upgrade is a
dispose-and-remount, so a UI consumer must be stateless: its state is the
session log, the agent handle and the widget tree, never its own `apply`
closure. The distinction to document once, on the first line of
each namespace: **`kmet.tui` renders components; `kmet.ui` decides which
backend renders.**

The cost is honest and larger than it looks: `modes/interactive/*` is 5,052
lines (`layout.clj` 918, `ui_registry.clj` 708), but the component library it
is written against — `kmet.agent.ui/` — is another 11,126 lines in 37 files (36
of them under `ui/tui/`), and that library is the part the rank guard actually
constrains. The Tk side starts from a working backend plus a widget skeleton
(`modules/tk/src/kmet/gui_sample/`, `kmet.gui-sample.*`: the `:ui/transcript`
scroll container over the extra-tag seam, no app content), so the second
provider needs a transcript, not a backend. Landing 5a moves `kmet.agent.ui.*` and
`modes/interactive/*` into `kmet.ui.tui` and extracts capabilities
incrementally; it does not rewrite them as view-model + renderer. Landing 5b
builds `kmet.ui.tk` on that skeleton.

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
(agents/send! handle msg {:target :next-turn})         ; queue; :next-step steers
(agents/steer! handle msg)   (agents/follow-up! handle msg)
(agents/inject! handle msg)                            ; out-of-band context
(agents/cancel! handle {:kind :user})
(agents/when-idle handle)                              ; the idle awaitable
(agents/status handle)                                 ; :idle | :running
(agents/dispose! handle)                               ; loop-coordinated awaitable
```

The handle is the capability: `create!` resolves with it, `get` returns the
bare agent, and a consumer that did not create the agent cannot dispose it. The
two `send!` targets are the durable pending lists (`:next-turn` / `:next-step`)
reconstructed from the log by `:projections`, not a second in-memory queue.

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

`kmet.agent` is 104 source files and 39,765 lines. Exactly 20 touch process
state; the other 84 are already pure functions of their arguments.

| Bucket | Pattern | Files | Lines | Members |
|---|---|---|---|---|
| needs nothing | lift as-is | 84 | 28,178 | tool bodies (`write`/`edit`), the `event-types` vocabulary, and the session layer (2,500 lines across `store`/`listing`/`tree`/`stats`/`context`) — it needs no `ctx`, and its host interop (`ReentrantLock`, `InputStreamReader`, `Instant`) runs unchanged on bb, Jolt and JVM, so it is a file move rather than a re-host |
| one argument | parameterize | 7 | 4,076 | `registry` (3 `runtime/node` reads), `commands/builtins` (6), `modes/interactive/commands` (6), `session/runtime` (3), `loop/state` (2), `core` (1), `modes/interactive` (1) |
| registry-wired | re-point | 4 | 1,305 | `tools/registry`, `skills`, `prompts`, `commands` |
| owns state | re-host | 9 | 6,243 | `kmet.agent.extensions.core` (7 `defonce`s), `debug` (3), `kmet.agent.extensions.context` (2), `bash_executor`, `event_bus`, `keybindings`, `runtime`, `tools/read`, `tools/run_code` (1 each) |

The fourth row is the only real re-hosting: 6,243 of 39,765 lines (~16%), plus
another ~14% in the one-argument and registry-wired rows. The session layer is
not among them: it is a lift (its `ReentrantLock`, `InputStreamReader` and
`Instant` calls need no porting — verified by running create/append/load under
Jolt, and kmet1 has always run it on all three hosts), and its one non-mechanical
edge is the `kmet.agent.debug` require the schema warnings add. It is the part that
owns state — precisely what the context replaces. The bus is the template:
roughly three-fifths of `event-bus.clj` is the `event-types` vocabulary (pure
data, lifts as-is), and the mechanism is one atom plus the three emitters
(`emit`, collect, boundary).

### Order

**bus → registry → tools → commands/prompts/skills → session → extension
host → loop → modes/UI.** Each step produces what the next consumes:

- the bus is one listener table plus the strategies and protocols over it; it
  unblocks the rest, because `srce/on` is how a plugin registers anything and
  `:events` is the first service the composition provides (landing 2: the
  vocabulary and the result protocols are `kmet.app.events`, the table and the
  three dispatch strategies are the kernel's, and kmet1's copy re-exports the
  names until the loop re-points);
- `registry` (78 lines) decides the ctx-addressed lookup shape for all four
  registries at once;
- `tools` is the first real service, and its shape is copied by the next three
  — their handlers already take a ctx-shaped map `{:runtime :ui :frontend}`, so
  that step is mostly renaming keys;
- `Engine` is already a record with operations, so `session` is a lift rather
  than a fork; only its singletons are re-hosted, and the log is a service
  before anything reads it (4a);
- the extension host moves after the session lift and before `Engine` (4a → 4b
  → 4c): it consumes the registries, the tool table, the session log and the
  config/debug services, and it is the landing's one *move*, so it separates
  cleanly from the re-host that follows it;
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
`with-redefs` sites, and 14 test files reference `kmet.agent.config` — the first clean
file anyone would lift. A rename owes a wrapper at the old name exposing
*every* var kmet1 uses, and that wrapper still cannot intercept calls made
*inside* the lifted code. Keeping the namespace keeps every interception point
valid (the move-by-name rule, read backwards: keep the name, never re-point
it), and it keeps the endgame mechanical: at retirement,
`s/kmet\.agent\./kmet.app./` over a known list that excludes
`kmet.extension` (the ABI), the deleted `kmet.agent.runtime`, and the
names re-hosted under `kmet.ui.*`/`kmet.mode.*`.

**Three exceptions.** The extension ABI keeps its name forever — installed
extensions write it in their `:require`, and it already reads `kmet.extension`
(it was renamed off `kmet.agent.extension` before kmet2 existed; nothing in the
repo names the old one now). So the host move is a file move with no ABI
change at all: the file lands in `app` and the namespace does not move with it.
Namespaces whose concept dies are deleted, not moved (`kmet.agent.runtime` has
no successor; its `node`/`*error-reporter*`/`/units` walk live on in
`kmet.agent.app_runtime` until 4c–4d). `kmet.agent.loop` (and `kmet.agent.loop.*`), `session/runtime` and
`modes/interactive/*` are re-hosted under new names; with `agent` at 4 kmet1
re-points at them instead of keeping a copy — `modes/interactive/*` becomes
`kmet.ui.tui` at landing 5 behind the `--mode` shim. New kmet2-only code uses
the new prefixes from day one.

| Now | At retirement |
|---|---|
| `kmet.agent.registry`, `.tools.registry`, `.event-bus`, `.config`, `.skills`, `.prompts`, `.commands` | `kmet.app.*` — one prefix substitution |
| `kmet.extension` | unchanged — the ABI (the file moves to `app`, the name is already the final one) |
| `kmet.agent.runtime` | deleted (folded into `kmet.agent.app_runtime`) |
| `kmet.agent.loop` (and subnamespaces), `session/runtime`, `modes/interactive/*` | new `kmet.app.*` / `kmet.ui.tui` |

### Per-lift checklist

1. **Remove the upward require.** `registry.clj` requires
   `kmet.agent.runtime` for `node`; while that edge exists the file cannot
   live at rank 2. Parameterize it to take `ctx` and drop any arity that
   resolves the root — *that commit is the extraction*. One signature defect
   to settle in the same commit: `clear!` already takes a node, as its **last**
   argument (`(clear! tag node)`), while `entry`/`entries`/`by-owner` take
   none — normalize to `ctx` first and update its call sites, or the module
   ships two conventions. kmet1's call sites pass `(runtime/node)`; a
   root-resolving wrapper, if one is needed, lives at rank 3, not in `app`.
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
- Patches are per row: `{:id … :config {…}}` deep-merges the row's config,
  `{:id … :remove true}` drops it, and a patch naming an unknown `:id` with a
  `:plugin` inserts a row (positioned by `:after`/`:before`, else appended).
  A patch that names an unknown row without a `:plugin` is an error; so is a
  duplicate id across stacked bundles. Layers: bundled default < `--profile`
  < the agent dir's `patch.edn` < `--patch <file>`; config *values* still
  deep-merge as `settings.edn` does, composition does not. (Landing 1 ships the
  composition and `--dump-config`; the file layers beyond `--patch` arrive with
  the loader.)
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

- The entry stays `kmet.agent.core`: `build.cljc`'s `main-class` and
  `build_jolt.clj`'s `entry-ns` name it directly, while the JVM uberjar keeps
  its own `main-class` — `kmet.agent.jvm-main`, which `build_jvm.clj` AOT
  `:gen-class`es — and that class's `-main` must keep delegating to
  `core/-main`. That namespace becomes a **bootstrap**
  with no app requires; kmet1's `-main` moves to `kmet.agent.cli`. The
  bootstrap parses `--app` (`new` | `old`, `old` the default), strips the flag
  from the argv, and hands off dynamically — never a static require — to
  `(requiring-resolve 'kmet.agent.cli/-main)` or
  `(requiring-resolve 'kmet.app.core/-main)`. Neither app pays the other
  *entry's* load time — kmet1 does require the shared `app` module it has run on
  since 4c-ii-a, but not kmet2's boot or its modes — and a missing `kmet.app.core` (a `--dev` build, an older artifact)
  answers "this artifact carries only the kmet app" instead of a null
  dereference — the check is the app's source resource, and `dispatch!` reports
  it (`::missing-app`) while `-main` owns the exit code, so the message is a
  test, not a fork.
- `bb start` stays the only start task and keeps requiring `kmet.agent.core`
  (now the bootstrap); a `start2` would be a second entry point to keep in
  sync. If the typing grates, it is one line:
  `:task (apply core/-main "--app" "new" *command-line-args*)`.
- `bb check` requires both app entries (`[kmet.agent.cli]`,
  `[kmet.app.core]`) in its task body, because the dispatch is dynamic and the
  load is the assertion; its `:requires` names the bootstrap only.
- `dist` needs no parameterization: the two entry constants stay put
  (`kmet.agent.core` for the bb/jolt builds, `kmet.agent.jvm-main` for the JVM
  uberjar), the embed list is already every module's `src`, and the artifact
  names describe the host, not the app.
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
| 0 | **landed** (`9c0b70ed`) — `modules/srce`: the light kernel — `ctx`/`fiber`/`event` behind the public `srce` | the contract + guard tests ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §12, 44 + 3 tests); no `kmet.agent.*` require, no `kmet.libs.scope` require |
| 1 | **landed** (`367efd49`) — module wiring + guards (module-based require, no-global-node), `--app new` dispatch (bootstrap split: `kmet.agent.core` + `kmet.agent.cli`) + `check` requiring both entries, `--dump-config` over the composed rows | `kmet.test-module-deps` / `test-modules-manifest` green; each guard fails on a deliberate violation |
| 2 | **landed** (`14ca4f0d`) — the bus: the six non-awaiting dispatch modes (`emit!`, `bail!`, `waterfall!`, `collect-last!`, `collect-all!`, `boundary!`) over the kernel's listener table, with `:data` on a registration and `{:in scope}` on `provide!`; `kmet.app.events` keeps the vocabulary (declared in the `:events` plugin's `:events` map), the owner, and the `:events` service; **the submission point** — `kmet.app.runtime`: `submit!` from any thread, `boundary!` drains and settles, `boot` mounts the composed rows | one contract test over both delivery paths (kmet1's bus and the app's), the app's own mode/service/declaration suite, and kmet1 green; a hook registered from another thread lands at the boundary |
| 3 | **landed** — `registry` parameterized then lifted (`kmet.agent.registry` over a `Registry` handle); the `:tools` service row; commands/prompts/skills/tools/entry-renderers re-pointed at the handle | the contract test runs one set of registry assertions against both backends (atom and store); the `:tools` service is installed by one `provide!` and mutated through its handle (no re-provide); kmet1 green |
| 4a | **landed** (`c2861df0`) — the sessions seam: `:sessionPersistence` (seven ops: `create!` `load` `paths` `append!` `entries` `delete!` `header`), the `:sessions` log over it (`inject [:sessionPersistence]`: the same ops plus an id index), an in-process `memory-backend`, and kmet1's store as the first real backend (`kmet.agent.session.backend`, a 34-line projection — `app` at rank 2 cannot name it yet) | the contract test runs one set of assertions against both backends, then composes the two halves as a profile does (`(service (ednl))`: 8 tests, 60 assertions); the row is `:pending` with `waiting-for=#{:sessionPersistence}` and activates when its backend mounts; kmet1's sessions and their files are byte-for-byte untouched; no composition row at 4a — `:sessions` joined the profile with the layer move (4c-i) |
| 4b | **The extension host**, in two halves. **4b-i landed** (`d01a0f44`) — `kmet.extension` (461 lines, zero requires) moved into `app` with its contract doc, and the **key-set contract test** landed: every api path an ABI wrapper reads (`(:notify (ui api))` reads `[:ui :notify]`) is asserted against the api the host actually builds for a loaded extension, 62 of them, and the test fails when one is renamed away. **4b-ii** splits by axis (§ "Landing 4b-ii: what the require graph says"): **4b-ii-a** is the behavior with the files still in `agent` — `extension.edn` gains an optional `:inject` and the host wraps an extension as the plugin `{:name :inject :apply}` (with `grep-tool` demonstrating it), the host takes a ctx instead of `app_runtime/node`, builds the api for the root or an agent ctx, and composes `shutdown` into the disposer; **4b-ii-b** is the move of the host files into `app` keeping their namespaces, after the tool layer and `config` lift. The four leaf lifts landed (`debug`, `event-bus`, `config-schema`, `tools/util`) | 4b-i: the key-set test is green, its own violation check fails by design, and kmet1's extension surface is unchanged (376 assertions across the extension suites). 4b-ii: an extension declaring `:inject` goes pending then active, nothing in the host reads the store node, and the loader whitelist has no `kmet.libs.scope` entry |
| 4c | **The root ctx, the session layer, then `:agents`** — four steps that gate separately, in this order. **4c-i landed** (`aa72deef`, `88513298`) — the session layer moved into `app`: nine files, namespaces kept, nothing re-hosted; the `kmet.agent.debug` require behind the schema warnings became a diagnostics flag the host's `--debug` sets; `:sessions` + `:sessionPersistence` joined `:bundle/minimal`, so a bare `kmet --app new` boots four rows. `:result-tokens` stamping stays in `store.clj` — see the 4c-i notes. **4c-ii landed in two halves.** **4c-ii-a** (`f1a446af`, `e38c5e05`) — kmet1 boots the app root ctx and its registrations live in the services: `app-runtime/registry` is one façade handle routing each tag to its service, the writers *and* readers moved together, the `node` parameter left their public fns (~200 call sites), and unload is one `deregister-owner!` per tag. **4c-ii-b landed** — the Engine, the modes and the bus take a ctx: `AgentState`'s `:node` is `:ctx` (the app root by default), `agent-node` is `agent-ctx`, `event_bus/visible` is `srce/inside?` over ctx scopes, and `child-node` is `child-ctx` — the loop's stages ride the root ctx's `:loop-stages` atom (boot's `:ctx-meta`), so `loop-stages`/`refresh-stages!` walk the chain and a child ctx carries its own. The store sites that stay (`discover-resources!` ×3, the `install-*`/`dispose-owner!` set, `/units`' walk) are on the app root node: the host's installs land there until 4b-ii, and the listing is 4d's. The 15 remaining mutable `defonce` cells split the same way: nine are the host's (4b-ii), three are the `:shell`/`:fs`/`:sandbox` seams the catalog names (landing 6), and `event_bus`'s table stays until the loop re-points (7). `registry_store.clj` and `kmet.libs.scope` do **not** die here: the host holds nine `scope/…` sites (hooks, themes, the UI port), the app node five, the Engine's two move with the host at 4b-ii, and 4d takes the last two (`owner-listing`), so the store retires at **4e**. **4c-iii**, `Engine` → `:agents` with `:permissions`/`:approvals` and — unlanded prerequisites — `:projections` and `:systemPrompt` | i: every session test green from `modules/app`, `kmet --app new` boots a session over EDNL files. ii: the Engine and the modes resolve a ctx, a session's tree is a child ctx, and the bus scopes by ancestry. iii: two agents in one process hold different tool tables, and an extension declaring `:inject {:sessions …}` goes pending then active |
| 4d | **Inspection from the tree** — `kmet.app.inspect/units` renders the invocation ctx's subtree, `--dump-state` the root's, `/units` re-points at it and the store section is deleted | `/units` and `--dump-state` show a `:pending` fiber with its `waiting-for` and a `:failed` one with its error; the five listing tests rewritten against the tree |
| 4e | **Retire the store** — delete `kmet.libs.scope` (with its own tests), `kmet.agent.registry-store` and the landing-3 store backend test, and the `app_runtime/…` disposal/error plumbing that goes with them. Nothing may still read it: 4b-ii took the host's nine sites and the Engine's two, 4d the `owner-listing` walk. The docs that describe it go in the same change (`modules/agent/src/kmet/README.md`'s two store rows, `extension.md`'s two owner-path sentences, `srce.md` §9) | `grep` finds no `(scope/` under `kmet.agent.*`, no `kmet.libs.scope` require outside the deleted files, and no `registry-store` require; the full suite is green and kmet1 boots with no store at all |
| 5 | `:ui` service (required/optional capabilities, widget set) + `ui-headless` + `ui-terminal`; modes as plugins; then `ui-tk` from the existing `kmet.gui-sample` | print/rpc/tui parity via `scripts/capture/`; tk provider green on bb and Jolt |
| 6 | `:agents` registry (create/resume/list) + `mode-server` (rpc = one-agent server); `:agent-id` and the request/response pair on the wire; approvals end to end; the **queue primitive** in `kmet.libs.concurrent` for the socket reader | two agents concurrently, one stalling client, an approval answered by a client |
| 7 | loop re-hosted as a driver plugin; profiles as row+patch layers; presets = bundles | pi-alignment green; a tool swaps mid-session at the turn boundary (`:agent-settled`); a profile stacking a preset bundle boots |

**The order is not the table's order.** 4b-ii cannot precede 4c-ii: the host
required eleven rank-4 namespaces — `commands`, `event-bus`, `prompts`,
`registry-store`, `app-runtime`, `session.core`, `skills`, `tools.core`,
`tools.bash`, `config`, `debug`, plus `extensions/context`, `extensions/libs`
and `kmet.libs.scope` — and it cannot sit at rank 2 until those names are ctx
services. That is the measured correction behind the split: the doc had claimed
the host move was mechanical, and it is mechanical for `kmet.extension` only.
4c-i has since removed the `session.core` edge and `debug`, and 4c-ii-a turned
four of the rest into services.

The practical order is **4a → 4c-i → 4b-i → 4c-ii-a → 4c-ii-b → 4b-ii-a →
4c-iii → 4b-ii-b → 4d → 4e**, and the reasons are dependencies, not preferences: 4c-i
removed the host's session edge and gave the composition a real log, 4b-i moved
the ABI and pinned the api contract, 4c-ii-b gives the host a ctx to take,
4b-ii-a puts the host on it, 4c-iii gives the api the agent surface it exposes,
4b-ii-b is the file move once the tool layer is below `app`, and 4e can only run
once the last `(scope/…)` site is gone — which 4d's `owner-listing` walk is.

**4b-ii split again, by measurement** (§ "Landing 4b-ii: what the require
graph says"): the host files can only move into `app` once the ten
`kmet.agent.*` namespaces they reach have left `agent` — the whole tool layer
among them — so the landing's *behavior* (`:inject`, the ctx-shaped api,
`shutdown` in the disposer) ships as **4b-ii-a** with the files still in
`agent`, and the **move** becomes 4b-ii-b, after the tool layer and `config`
lift. Two of the host's ten already moved (`debug`, `event-bus`), and two more
(`tools/util`, `config-schema`) moved out from under `config`, so its own lift
is one call away (`bash-exec/set-shell-options!`) and eight host edges remain.

Two prerequisites have no row of their own and must not be forgotten inside
4c-iii: `:projections` (the inbox and turn boundaries as derived state over the
log, so `send!`'s `:next-turn`/`:next-step` lists exist) and `:systemPrompt`
(per-agent sections, which is what an agent's `setup` registers). Neither exists
in `modules/app` yet.
`:inject` moved from 4b-i to 4b-ii for the same reason it was split: a
declared dependency nothing honors is a lie in a manifest.

**Landing 4 is four landings** (4a–4d), split for two reasons. Size and reach:
as one it moved 3,615 lines of extension host with a 42-key api rewired, and
carried a 1,390-line `Engine` lift, 18 `defonce` cells (16 mutable) and 19
`app-runtime/node` readers in 13 files — against 474 net lines at landing 3,
and as the first landing that changes how *kmet1* resolves its world rather than
adding beside it. Composition: it was a lift (`:sessions`), a move (the host), a
re-host (`:agents`) and a renderer in one row, so the reuse ledger — the doc's
own stop rule — could not tell a ported mechanism from a ported state model, and
one extension-ABI failure would have blocked the session log and `/units` for no
reason. Separated, each keeps the one-mechanism-plus-one-contract-test shape and
retires its own `kmet.libs.scope` readers ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §9).

Two consequences worth stating, because both were wrong in the single-landing
version: **no mode ships in 4** — `kmet --app new` still reports `not-wired`
until landing 5 mounts the modes, so 4c-iii's observable is two differently-scoped
agents, not a prompt; and **4c is three landings in one row**, named as 4c-i,
4c-ii and 4c-iii in the table because each gates on its own — the session layer
moving into `app` needs the interop re-host but not the root ctx, the root ctx
cutover needs neither the session move nor a driver, and `Engine` → `:agents`
needs both. If i and ii turn out to move together, they merge then — with data
rather than a guess.

### Landing 4b-ii: what the require graph says

4b-ii is written as one move — the host files into `app`, keeping their
namespaces. The module guard reads *where the file lives*, so the move is legal
only when every module above `app` has left the host's requires. Measured on
`extensions/core.cljc` (the host's 1,586 lines), the upward edges were:

| Upward edge | Uses | What it needs first |
|---|---|---|
| `kmet.agent.debug` | 1 | nothing — **lifted** |
| `kmet.agent.event-bus` | 8 | nothing (debug + `kmet.app.events` + `srce`) — **lifted** |
| `kmet.agent.config-schema`, `.tools.util` | — | nothing — **lifted** (not the host's edges: they blocked `config`) |
| `kmet.agent.app-runtime` | 11 | the node and the registry façade must be reachable from a ctx, not from an `agent` namespace |
| `kmet.agent.commands` | 3 | the same — its only upward edge is `app-runtime` |
| `kmet.agent.prompts` | 2 | the same |
| `kmet.agent.skills` | 3 | `app-runtime`, `tools.read`, `tools.registry` |
| `kmet.agent.tools.core` | 5 | `tools.registry` + `tools.invoke` |
| `kmet.agent.tools.bash` | 1 | the shell seam (landing 6): `bash-executor` is a `defonce` of process state |
| `kmet.agent.config` | 1 | one call: `bash-exec/set-shell-options!` — the `:shell` seam in one line |
| `kmet.agent.registry-store` | 1 | a decision: `app` must not require the store it outlives (landing 3's note), so the theme key it builds has to become a registry address |

Two consequences, and they change the order rather than the goal:

- **The move is behind the tool layer.** `tools.registry` requires every tool
  body (read, write, edit, bash, run-code) plus `app-runtime`, so lifting it to
  `app` means lifting the tool layer's shell/filesystem seams first — i.e.
  most of landing 6. Waiting for that would hold `:inject` hostage to the
  shell services for no reason.
- **So the landing splits by axis, not by size.** 4b-ii-a ships the *behavior*
  — the host takes a ctx, `extension.edn` gains `:inject` and an extension
  mounts as the plugin `{:name :inject :apply}`, `shutdown` composes into the
  disposer — with the files still in `modules/agent`. 4b-ii-b is the *move*,
  after the tool layer and `config` lift. The landing rule still holds at each
  half: kmet2 uses the new mechanism, kmet1 keeps working, one test holds both.

The four leaf lifts are the ones with no upward edge at all — `debug` (required
by the host, the bus, `config`, `skills`, `bash-executor`), `event-bus` (the bus
is the host's biggest non-tool dependency), `config-schema` and `tools/util`
(`config`'s two other clean edges). They keep their namespaces, so no call site
changed; only the guard's answer did.

### Landing 4c-ii-b settled

- **The stage map needed no new `app` API.** `kmet.app.runtime/boot` already took
  `{:ctx-meta …}`, so the root ctx is booted with `:loop-stages` and nothing at
  rank 2 learns what a loop stage is. The alternative — `srce/extend`ing the
  booted root inside `app-runtime` — would have put a second, phantom child under
  the root whose only purpose was to hold an atom, and 4d's tree would have had
  to explain it.
- **One mutable cell moves rather than dies, deliberately.** A ctx's meta is
  immutable, so a `refresh-stages!` that re-seats a reloaded guard needs an atom:
  the atom lives in the root ctx's meta and `srce/attr` finds the nearest one,
  which is exactly the inheritance a child ctx should get. Replacing the ctx
  instead would invalidate the value the mounted fibers already hold. 4e still
  deletes the store's mutable cells; this one belongs to the ctx.
- **The doc counted the wrong call sites.** `app_runtime/node` had 7 src readers,
  but the *accessor* `agent-node` had five more (`interactive/builtins`,
  `session_admin` ×2, `turn`, `ui_registry`) — a sweep that greps the resolved
  node misses every caller that asks the engine. What the sweep actually touched:
  12 src files and 8 test files (6 with the landing, 2 with the drive-by below).
- **The store-facing sites are not convertible, so they went to the app root.**
  `extensions/discover-resources!` and the `install-*`/`dispose-owner!` pair are
  store registrations until 4b-ii; handing them the engine's ctx would have
  silently dropped an engine's resource contributions. Before this landing the
  default engine already installed on the app root — only a hypothetical
  `:node`-passed child tree differed, and no src or test caller passed one — so
  the interim is honest: the engine's installs are app-root, and 4b-ii makes them
  the host's effects.
- **Two scoping tests were asserting the dead mechanism.** `test_event_bus`'s
  subtree and collect/boundary tests built `scope/node` trees; they build ctxs
  now, so "a scoped listener hears its own subtree" is asserted against
  `srce/inside?`, which is what ships. No listener in src registers a scope yet,
  which is why the switch is behavior-preserving for kmet1.
- **`loop-stages` inherits; `refresh-stages!` names its target.** The two read
  and write different things and the first pass had them both walking the chain:
  a refresh aimed at a ctx with no stage atom of its own would have re-seated an
  arbitrary ancestor's map from a child call. A refresh now re-seats the ctx's
  own atom when it has one, the app root's otherwise, and a test says an
  isolated tree survives it.
- **A scope is checked, because nothing else would catch it.** A store node
  walks `:parent` too, so `srce/inside?` would "work" on one and silently match
  the wrong tree. `on-event` and the three emitters reject a non-ctx scope with
  `:kmet.agent/bad-scope` — the check is where the mistake is made, not on the
  read.
- **`ctx` stopped meaning two things in the loop.** The attempt map the step
  functions thread through (`:agent`, `:stages`, `:text-buf`, …) was also named
  `ctx`, beside `(:ctx agent)` for the kernel ctx; it is `attempt` now, so the
  next landing can move agent handles through this code without a misread. The
  tool-call ctx the before/after hooks receive keeps its name — that one *is* a
  ctx in the extension API's sense.
- **`/units` lost its per-session variation.** `handle-units` walks the app root
  node by definition now; a session can no longer point it at its own tree. That
  is the cutover 4d was waiting for, and the test asserts the rendering, not the
  identity of the tree.
- **A drive-by the changed-file lint surfaced:** `test_packages` and
  `test_run_code` required `kmet.agent.app-runtime` without using it — left
  behind by 4c-ii-a and invisible to a lint that only reads its own changed
  files. Removed here.
- **The ledger.** Nothing re-hosted: 12 src files, 8 test files, and `(scope/…`
  sites 26 in 5 files → 25 in 4. What stays is the host's nine, the app node's
  five and the Engine's two.

### Landing 4c-ii-a settled

- **Writers and readers had to move in one change, and did.** A registry
  registration is one call and a read is another; moving only the reads (or
  only the writes) leaves a turn where a command is registered into the store
  and looked up in a service. So the four namespaces moved both, and their
  `node` parameters went with them — a parameter that no longer selects
  anything is the copy AGENTS.md forbids, and lint says so (`unused binding
  node`) at all nineteen sites.
- **The sweep was ~200 call sites, mostly tests**, and the full suite is what
  caught the ones a regex cannot: the package loaders silently passed the node
  as the *paths* argument, the interactive-mode reload tests stubbed
  `clear-skills!` at its old arity so `/reload` aborted mid-way (and the abort
  looked like a settings bug), and `run_code`'s tests registered through
  `tools.registry` under an alias the sweep did not know. Each was a real
  symptom of a real arity change, not a flake.
- **Owner-scoped removal is a registry operation, not a store trick.** The
  protocol had `clear!` (one tag, every owner) and per-name `deregister!`, so
  "everything this owner registered" was only expressible through the store —
  which is why the host relied on owner-path disposal and why resource-loaded
  skills survived unload once the registries left the store. `deregister-owner!`
  is the missing operation; unload is now one call per tag.
- **`/units` keeps both halves.** The store walk shows what still lives there;
  a registry line per service (`:commands keys=[…]`) shows what moved — read
  from the handles, which is the same surface from the other side and the
  starting point of 4d's renderer.
- **The ledger.** 4 commits this landing (the four registry services, the
  façade, and two test-side corrections folded into the ctx commit). Nothing was
  re-hosted: the registries moved from the store to services with their call
  sites, and the store kept only what has no seam yet.

### Landing 4b-i settled

- **The ABI moved, and it cost nothing.** `kmet.extension` had no `:require`,
  so the file and its 461-line contract doc moved into `app` unchanged; the
  twelve documents that named the old path were updated with it. Nothing else
  in the layer needed to move, because agent (4) may require app (2), and the
  loader serves the namespace by name from the classpath — verified by loading
  a real extension under bb and resolving the ABI under Jolt.
- **The api contract is now a test, not a hope.** The ABI is a function table
  over a map, so a wrapper reading a key the host stopped providing fails
  inside somebody's extension at the moment they use it — the one failure mode
  no unit test on either side catches. The test reads all 62 paths out of
  `kmet/extension.clj` (top-level reads and `(:key (accessor api))` reads,
  whose parent is the accessor's own name), loads the single-file fixture, and
  compares against the api the host built. Its own violation check was run: a
  renamed capability and a renamed top-level key both fail it, and the file
  restored green.
- **`:inject` waits for 4b-ii.** It was scoped to 4b-i on the grounds that it
  needs no ctx, but *honoring* it does: without a ctx there is no service to be
  pending on, so the manifest key would be a declaration nothing reads. It
  lands with the wrapper and grep-tool's demonstration, where pending →
  active means something.
- **A host accessor came out of it.** `extensions/extension-api` returns the
  api a loaded extension was handed. It exists because the api is the only
  thing that can be checked against the ABI, and because "why is my `:notify`
  nil" is a real question; it is documented as a diagnostic, not a surface.

### Landing 4c-i settled

- **A lift, not a re-host, and the docs say so now.** The session layer's
  `ReentrantLock`, `InputStreamReader` chunked reads and `Instant` calls run
  unchanged on bb, Jolt and JVM (verified by running create/append/load under
  Jolt before touching anything), so nine files and their tests moved with
  their namespaces intact — 2,370 source lines and 2,432 test lines — and the
  only code change the move required was the `debug` edge. `modules/app` gained
  its library edges (`libs`, `ai`, `babashka/fs`, `malli`), the session tests
  moved with the code, and the two host-side tests (`--debug` owns the strict
  pass) stayed in `agent`, where they die with kmet1.
- **The move exposed a real `--dump-config` bug.** The first row to declare the
  documented *vector* `:inject` form (`:inject [:sessionPersistence]`) broke the
  dump — `render` did `(into {} inject)` and a vector of keywords is not a map
  entry. The card now normalizes through `srce/normalize-inject`, so a card
  reads like a fiber record (`{name nil}`), a malformed declaration fails at
  compose time rather than at mount, and a regression test pins it. Worth
  remembering: every row so far had `:inject {}`, so nothing had exercised the
  vector form since landing 1.
- **`:result-tokens` stamping stays where it is.** Moving it into `:sessions`
  was in the plan; the contract test does not assert on tool entries, so the
  move buys nothing until `:projections` lands, and `:sessions` is where that
  derived state belongs. Recorded rather than done.
- **The ledger.** 9 source files and 10 test files moved (net +84 lines: the new
  row, the `:inject` normalization and its test), and 4 commits: the `debug`
  edge, the order correction, the move + rows + dump fix. Nothing was
  re-hosted, which is the point of calling this a lift.
- **4c-ii cannot delete the store, and that is a finding rather than a slip.**
  The cutover was to end with `kmet.libs.scope` gone — but the extension host
  holds nine `(scope/…)` sites (owner paths for hooks, the UI port, themes) that only become fiber effects when the host itself moves at
  4b-ii, and 4b-ii needs the root ctx this landing creates. So the store retires
  at **4e**, after 4b-ii puts the host on fibers, 4c-ii-b moves the Engine, and
  4d replaces the last two sites (`owner-listing`'s walk).
  [`srce.md`](modules/srce/src/kmet/srce/srce.md) §9's "all of it at the end of 4c" was
  wrong for the same reason.
- **The foundation landed first, and it is not speculative.** `:commands`,
  `:prompts` and `:skills` joined `:tools` as rows (`kmet.app.registries`: one
  shape, four declarations, `kmet.app.tools` delegating to it), so kmet2's
  composition provides the four registries the catalog lists. Nothing reads them
  yet — that is 4c-ii proper — but the boot and the dump do, and the rows are
  what kmet2 needs regardless of kmet1.

### Landing 4a settled

- **The seam is seven ops, not five.** `:sessions` needs a handle and a way to get one, so `create!`/`load` are part of the storage contract and `paths` replaces `list` (it lists paths, and the app never learns a filename convention). The doc's five names (`append!` `read` `list` `delete` `header`) were a sketch of the *log's* surface; `entries` reads better than `read` in Clojure and the id index adds `get`.
- **The backend is values, not a protocol.** A backend is a map of fns, checked once by `service` — a missing op throws `:kmet.app/incomplete-backend` naming it at mount, instead of `nil is not a function` halfway through a turn. The ops take no ctx: a backend is stateless (the handle carries the state) and per-context concerns arrive through the row's config.
- **Two rules are the contract.** An append is the write (it stamps `:id`/`:parent-id`/`:timestamp`, chains onto the leaf, and never renumbers), and nothing is stored before the first assistant entry (a session that never got an answer leaves no file). Both are observable through `paths`, so both backends have to answer them and the contract test says so.
- **One delta, recorded not discovered.** The EDNL backend stamps `:result-tokens` on tool entries (`session/store.clj`'s append policy). The contract does not assert on tool entries, so the backends agree where the contract speaks; the stamping moves into `:sessions` with the layer itself (4c-i), where derived state over the log belongs.
- **The session layer's interop needed no porting, and got none.** `ReentrantLock`, `InputStreamReader` with `char-array` chunks, `(String. buf 0 n)` and `Instant` all run on bb, Jolt and JVM — kmet1 has been running this layer on all three, and a Jolt run of create/append/load was the check before touching any of it. So 4c-i is a file move plus one require to remove (the `debug` edge), and the seam met the layer where it stood: `kmet.libs.concurrent` grew nothing, and `kmet.app.sessions` writes its one timestamp the way the store does (`(str (java.time.Instant/now))`). The speculative `lock`/`iso-now` helpers first written here were deleted again before the landing closed: a primitive with no consumer, for a portability problem that does not exist, is not a facility.
- **The ledger: 429 lines added, 4 changed, nothing lifted.** 190 for the service, 34 for the EDNL projection, 205 for the two test suites. A landing that settles a contract looks like this from the outside — it changes nothing a user can see, and the reuse it enables is 4c-i's. The rule still holds: if *re-hosted* outgrows *lifted* for two landings running, stop.

### Landing 3 settled

- **The mechanics take a handle, the rules stay in one place.** `kmet.agent.registry`
  declares a five-method `Registry` protocol (`register!`, `deregister!`,
  `by-owner`, `ordered`, `clear!`) and the readings every backend must answer
  identically — `entry` (first in display order wins, so the owner that took a
  name keeps it), `entries`, `effective-entries` (one per name, so listing and
  lookup agree), `store-key`/`key?`. A backend cannot drift from the rules
  because there is nothing else to implement them with. `kmet.agent.registry`
  did not exist as a per-backend copy: the file moved to `modules/app` and its
  scope-store half became `kmet.agent.registry-store` in `modules/agent`, so
  the app module never requires the store that 4e deletes.
- **Two backends, one contract.** `atom-registry` (an atom of
  `{:tag :name :owner :value}`, registration order is display order, a disposer
  removes exactly the entry it installed) is kmet2's; `store-registry` wraps
  kmet1's scope node. `kmet.agent.test-registry` runs the same assertions
  against both — register/entry/dispose, replace within an owner, first owner
  wins, deregister by owner and by tag, `clear!` across owners — so the store
  and its replacement answer one contract until the store is gone.
- **The `:tools` service is installation once, mutation by handle.** The row's
  `apply` provides an empty `atom-registry` into the app scope (`{:in …}`, like
  the events row); adding a tool is
  `(registry/register! handle :kmet/tool name tool owner)`, so a plugin adding a
  tool never re-provides, and the disposer it gets back is what a fiber effect
  runs on dispose. The tool *semantics* — built-in map, schema normalization,
  selection — stay in `kmet.agent.tools.registry` (rank 4), whose reads gained
  an explicit-argument arity (`(get-all-tools reg)`, `(get-tool reg name)`,
  `(get-contributed-tools reg)`); the zero-argument forms ask the app root, so
  every kmet1 call site was unchanged until 4c-ii-a threaded the ctx.
- **One mutation path.** `register-tool!`/`unregister-tool!`, their source
  counterparts, `register-command!`/`unregister-command!`, the prompt-template
  loaders and `register-skill!` all go through the handle now (`tool-key`,
  `tool-source-key`, `command-key`, `prompt-key` and `skill-key` are gone), so
  kmet1 and kmet2 write registrations the same way, the tag/name addressing
  lives in the registry rather than in each caller, and those five namespaces
  no longer require `kmet.libs.scope` at all.

### Landing 2 settled

- **The vocabulary moved, the mechanism did not (yet).** `event-types`,
  `app-event-types`, `loop-event-types` are now `kmet.app.events`' — three
  fifths of `event-bus.clj`, pure data — and `kmet.agent.event-bus` re-exports
  them, so
  every kmet1 call site and test is unchanged. kmet1 keeps its process-level
  table, its owner/scope and its three emitters until the loop re-points
  (landing 7): that is the landing rule ("kmet2 uses the new mechanism, kmet1
  still uses the old one"), and delegating now would have taught the app's bus
  kmet1's store-node scoping — a value that dies with the store at 4e.
  A contract test runs one set of delivery assertions against both backends, so
  the pair cannot drift while both exist. The wire projections stayed with
  kmet1's transport: they are serialization, not the bus.
- **All six non-awaiting modes are the kernel's, and they are the same shape.**
  `emit!` (nobody's answer), `bail!` (the first non-nil, short-circuiting),
  `waterfall!` (`(f event next)`; not calling `next` vetoes), `collect-last!`
  (the last non-nil), `collect-all!` (every non-nil result as `[data result]`)
  and `boundary!` (each listener sees the accumulated map merged into its event
  and returns the keys to replace). They live in `srce` because after
  `{:data …}` on a registration none of them needs an app concept: the owner a
  caller attaches is *opaque kernel data*, and the merge protocol is data
  handling. What stays above is the vocabulary and the owner *value* — a kernel
  may not name an app event — plus the one exception rule's home
  (`srce/invoke!`, which every mode calls) and `names`.
  `parallel`/`serial` stay absent, and not for lack of availability: `future`,
  `promise`, `deref` timeouts and `core.async` (including `go`) run on all three
  hosts. Their content *is* the await, so a sync kernel collapses them onto
  `collect-all!` and `bail!`; and awaiting by deref blocks the owner thread,
  while awaiting in a `go` block colors every caller. Async fan-out is the app's
  queue (`submit!`/`boundary!`), which is Cordis's `ctx.submit`; the kernel
  grows a mode only when an emitter needs *concurrent blocking* listeners
  (`srce.md` §5).
- **The registration is the record; there is no side table.** `on` takes
  `{:data …}`, the table stores `{:f :data}`, and `listeners` hands back
  `{:ctx :f :data}` — so `collect-all!` reports a contribution beside the data
  its registration attached, `once` carries it to its one delivery, and kmet1's
  `[owner result]` shape is that pair with the owner as the data. Scope needs no
  field at all: a listener hears emits from the ctx it registered on and that
  ctx's subtree — kmet1's node rule expressed by the tree instead of a stored
  node.
- **A plugin declares its own events, and the declaration is what is read.**
  `defplugin` grew `:events` (`{event {:mode :doc :domain}}`); `plugin!` checks
  the shape and puts it on the fiber record, so a mounted plugin's declarations
  are inspection data, a name re-declared by two mounted plugins must agree, and
  `kmet.app.events` derives `event-types`/`app-event-types`/`loop-event-types`/
  `event-modes` from the **declaration in code** — no projection in the path, and
  nothing to go stale. That is the harness's per-package declaration
  (`declare module … interface Events`) with kmet's declaration form.
  No projected catalog is in the path (landing 1's generated file was deleted;
  a row's card comes from its plugin map, and `--dump-config` resolves the
  sources it prints). The **wire projections** (`wire-event`,
  `wire-error`) stayed with kmet1's transport rather than moving with the bus:
  they are serialization, and `kmet.mode.print`/`kmet.mode.rpc` take them when
  the app's transports land (5). `srce/dispatch-modes` is the authority on which
  modes a declaration may name. Today the `:events` row declares all 43
  — it owns the vocabulary until the loop, session and mode plugins claim
  theirs — and the guards are: a declared mode must be one the kernel can
  dispatch (`:serial`/`:parallel` may not be declared while there is no
  awaitable), and every event carries a docstring.
- **`{:in ancestor}` is the one kernel addition the composition needed.**
  `provide!` registers into the *current* ctx's store, so a plugin's service is
  visible to its own subtree and to nothing else — right for a per-agent
  capability, wrong for the app's flat row model, where every row mounts as a
  sibling under the root. `(provide! ctx name value {:in scope})` puts the entry
  in the mount site's store while the effect and the provider record stay the
  providing fiber's: the `:events` row shares its service with the app and dies
  with its own fiber. The duplicate check reads from the target scope.
- **`kmet.app.runtime` is the boot and the submission point.** `boot` mounts a
  composed row list under one root ctx (a var symbol resolved off the
  classpath, a var, or a plugin map; an `:ext/…` id is refused until the
  extension host lands) and throws `:kmet.app/boot-failed` naming every row
  whose fiber is not `:active` — its state, its error, and what it waits for.
  `submit!` appends a thunk to the root's queue from any thread; `boundary!`
  drains it (to a fixpoint, eight rounds) and settles the kernel, so a mount or
  a registration made on a tool thread lands at the boundary and never
  mid-pass. The queue is a ctx key, not a service: it has no plugin, no
  lifetime and nothing to inject.
- **The first row is in the composition.** `:bundle/minimal` carries
  `:events`; `--dump-config` prints it, and a bare `kmet --app new` boots it,
  reports what mounted, disposes it and still exits 1 ("no runnable mode yet").
  The boot is the point: it is what proves a profile's rows mount without a
  turn to run, and it is where every later landing's rows get their first
  end-to-end check.
- **A ctx is cyclic, so nothing prints one.** `ctx → :kernel → :runtimes →
  fiber → :ctx` closes the loop and no host's printer detects it — `pr-str` of a
  ctx, or of a value holding one, is a stack overflow. Nothing may carry a ctx
  in a service value (`kmet.app.events/service` binds it in a closure), and a
  report names states, counts and service names, never the value.

### Landing 1 settled

- **`modules/app` is rank 2 and `agent` moves to rank 4** in the manifest, from
  the first landing: the inversion is explicit before there is anything above
  it. The module carries `kmet.app.profile`, `kmet.app.catalog`,
  `kmet.app.core`, and `kmet.agent.version` — the version file moved so both
  entries print one string, and its namespace did not (the two-axes rule). The
  three `kmet.agent.version` assembly functions (`baked-version`,
  `source-run?`, `kmet-version`) moved with it out of `core.clj`; the marker
  names `kmet/agent/core.clj`, the bootstrap every artifact carries.
- **The bootstrap is one namespace with no `kmet.*` require.** `parse-app`
  strips `--app`/`--app=` before either app parses (kmet1 would read it as an
  extension flag); `dispatch!` resolves the entry through `requiring-resolve`
  only after `app-available?` finds the app's source resource, and returns
  `::missing-app` instead of exiting — `-main` owns the exit code. The
  two-direction load test runs real processes for the reason a test process
  cannot answer it: `bb test` has already loaded namespaces.
- **A row's card is the plugin map, and there is no manifest beside it.** A
  generated `kmet/app/plugin-catalog.edn` was landed here (and a
  `bb generate-plugins` scanner behind it) so `--dump-config` could print a
  built-in row's card without loading its namespace. It was deleted in landing
  2: dereferencing a `defplugin` var mounts nothing, so the card is read from
  the declaration itself, and the only thing the manifest bought — describing a
  row whose namespace *cannot load on this host* — has no row yet. When it does
  (a host-gated `:ui/tk` row), the decision belongs where the information is:
  the profile row carries its own label (profiles are data), or `describe`
  learns an "unresolvable here" marker. Until then a source that does not
  resolve is `:kmet.app/unknown-plugin` in `--dump-config` as well as in boot.
- **Rows arrive described.** `kmet.app.catalog/describe` turns a row into a
  card (`:id :name :inject :provide`) without mounting anything: a var source
  is resolved and read from its plugin map, an `:ext/…` keyword is external
  (landing 4b's host supplies its card at load), and `:id` defaults to the
  plugin's `:name`. Composition is then pure data: bundle layers in order,
  config deep-merged by row id, `:remove`, and an insert — a patch naming an
  unknown id that carries a `:plugin` — positioned by `:after`/`:before`.
  A patch entry's allowed keys are checked, so a typo is an error and a patch
  that changes nothing is one too.
- **`--dump-config` prints the rows the boot would mount**, one line each, with
  the profile and the row/patch counts; `--profile` selects the profile and
  `--patch FILE` stacks a layer (repeatable). The built-in table ships the four
  profiles (`tui`, `print`, `rpc`, `server`) with an empty
  `:bundle/minimal`; each plugin landing adds its rows.
- **Guards**: `kmet.test-module-deps` gained the namespace-level require check
  built on the ns-form reader (a require may name only a strictly lower-ranked
  module's file — its own module is fine), `kmet.test-no-global-node` bans the
  store namespaces (`kmet.agent.app-runtime`, `kmet.agent.runtime`,
  `kmet.libs.scope`) and the `runtime/node` symbol from `srce`/`app`/`ui`/`mode`
  sources, and `kmet.test-app-dispatch` holds the bootstrap's static shape and
  the two load directions. Each was verified by injecting the violation it
  guards.

Landing 0 landed the kernel without touching kmet1, and landing 1 keeps that
property: it splits `kmet.agent.core` into a bootstrap and `kmet.agent.cli`
without touching the old app's logic. 2–4 lift kmet1 code without renaming it,
so kmet1 stays green throughout. 5 is the first landing that moves kmet1 code
*and* renames it (interactive → `kmet.ui.tui`). `agent` at 4 may require `ui`
at 3, so kmet1's dispatch re-points at `kmet.ui.tui` behind a `--mode` compat
shim: the old flag keeps working and the capture diff gates the switch.

**The one reversal that is not additive** is a provider that must *await* during
`apply` (a socket bind, an MCP handshake at boot) or must mutate the kernel from
a callback that cannot be marshalled (a Tcl/Tk callback that has to return
synchronously). Landings 5 and 6 are where it would show. Until it does: such a
plugin holds its own promise and the app awaits it at the boundary, and a
synchronous callback queues an intent that the next `settle!` applies. If that
proves insufficient, the kernel grows async `apply` — that is a landing of its
own, not a tweak.

## Gates

- `bb test`, `bb test-ext`, `bb lint` (zero findings), `bb format-check`,
  `bb check` — unchanged, plus `bb test-changed` / `bb lint-changed` /
  `bb format-check-changed` while iterating.
- `kmet.test-module-deps` — ranks stay strictly downward.
- `kmet.test-modules-manifest` — `modules.edn`, `bb.edn`, `deps.edn` and the
  embed list stay in step.
- New: the **module-based require guard** (a namespace may require only a
  strictly lower-ranked module's file — built on `kmet.test-seam-requires`'s
  ns-form reader and `kmet.test-module-deps`'s ranks), the no-global-node guard
  (`runtime/node` banned in `kmet.srce.*`, `kmet.app.*`, `kmet.ui.*`,
  `kmet.mode.*`), and the headless-mode plugin-graph assertion.
- New: the **submission point** (landing 2) — a change made on another thread
  reaches the kernel only through `kmet.app.runtime/submit!` and is applied by
  `boundary!`, asserted end to end (a listener registered and a plugin mounted
  from a `future`, invisible before the boundary, live after it), with the
  drain's order and its throw isolation. The **submit guard** proper — the
  static assertion that `kmet.app.*` mutates only through that fn, plus a
  concurrent-mount test that one pass runs and `settle!` applies the loser's
  change — lands with the loop (landing 7), which is when the app first has a
  thread other than its own to guard against.
- The kernel's own gates — the contract tests, srce self-containment, and the
  no-`scope` require ban — are in [`srce.md`](modules/srce/src/kmet/srce/srce.md).
- New: a profile test — layers patch by row id, `--dump-config` resolves to
  the same rows the boot uses, and an unknown plugin source fails at load.
- New: the two-direction load test — `kmet --app new --version` loads kmet2
  without any kmet1 *runtime* namespace (`kmet.agent.cli`,
  `kmet.agent.session.runtime`, `kmet.agent.loop`) appearing, `kmet --app old
  --version` loads kmet1's runtime (and `kmet.app.*`, which kmet1 runs on since
  4c-ii-a — the old direction asserted that module stayed unloaded, which
  stopped being true then and the slow guard went red un-noticed), and
  `kmet.agent.core`'s ns form requires neither app. It is what keeps someone
  from turning the dispatch into a static `:require`.

## Not doing

- Forking the repo, or rewriting `kmet.libs.*` / `kmet.ai.*` / `kmet.tui.*` /
  `kmet.tk.*` / `kmet.loader.*`.
- Multi-user accounts, quotas, or an ACL model in phase 1.
- Renaming `kmet.agent.*` namespaces at lift time, or inventing intermediate
  prefixes (`kmet.shared.*`, `kmet.services.*`): every rename is paid once more
  at retirement, and the last one is meant to be a `sed`.
- A web UI before `ui-tk` has proven the capability contract.
- Deleting kmet1's `kmet.agent.*`; it stays until kmet2 passes the full gate
  set and the pi-alignment suite, then it is retired in one landing.
- The kernel's own exclusions (JS syntax, the loader/HMR/console packages, the
  plugin market, async stack splicing) are listed in [`srce.md`](modules/srce/src/kmet/srce/srce.md).
