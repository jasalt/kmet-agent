# kmet Cells — unit mounting and lifetime (design + implementation plan)

`kmet.cells.*` is a generic composition runtime. It turns declarative **unit
descriptors** into a mounted tree of live components, gives every registration
a lifetime, and checks the mount before any of it runs. It knows nothing about
agents, sessions, events, LLM providers, tools or extensions: the host injects
only its name-resolution backends (§8).

**It is not a workflow engine, and not an event framework.** Control flow —
iteration, routing, the order in which a unit calls its own children — lives
inside a unit's handle. Events belong to the host's bus. What the framework
owns is what exists, how it is configured, how it is wired, and how long it
lives.

Status: **design locked; P0 landed.** The plan — phases and worked shape — is
[`cells-plan.md`](../../../../../cells-plan.md) at the repository root: a
transitional document that shrinks as the phases land (§9). P0 landed the store
(`kmet.cells.scope`), `spec`, the facade's four fns, the toy tests and the
`modules.edn` / `deps.edn` / `bb.edn` wiring; nothing in the tree consumes the
module yet.

## Contents

1. [Why this exists](#1-why-this-exists)
2. [What it is](#2-what-it-is)
3. [Descriptors](#3-descriptors)
4. [The checks](#4-the-checks)
5. [Mounting and lifetime](#5-mounting-and-lifetime)
6. [The manifest](#6-the-manifest)
7. [Visibility](#7-visibility)
8. [The host contract](#8-the-host-contract)
9. [Document set and shelf life](#9-document-set-and-shelf-life)
10. [Staged plan](#10-staged-plan)
11. [Design non-goals and rationale](#11-design-non-goals-and-rationale)
12. [Open questions](#12-open-questions)
A. [Measurements and the cut record](#a-measurements-and-the-cut-record)

---

## 1. Why this exists

Three structural problems in the agent module, each already paid for
repeatedly. The measurements behind them are in §A.

### 1.1 Registries have no owner

Extension-reachable features are process-global atoms with a hand-written
"pending vs bound" duality: 15 `current-*-registry` dispatchers that fall back
from the engine's copy to a `pending-*` buffer, 13 buffers, 15 registry copies
and 13 resets inside `bootstrap`, `dispose!` mirroring the other half, and 22
`default-engine` call sites. The duality is load-bearing, not dead code:
`core/-main` loads extensions *before* mode dispatch, hence before `bootstrap`,
so the pending buffers are the normal path for extension-provided tools,
commands, skills and hooks. They exist because two things are conflated —
**where a registration lives** (a global) and **which engine owns it**
(per-engine copies of the same global). Those are the same atoms; §5 makes them
the same atoms.

### 1.2 Lifetime is enumerated by hand, and wrong at the edges

`unload-extension!` runs `:deregister-fns` in **registration order, not
reverse**, and swallows each cleanup's failure — against every established
model (Effect-TS `Scope` and Effection both specify reverse order, because a
later registration may hold what an earlier one provided).

The tree already contains three register-and-dispose implementations:
`hikap`'s store (`new-store` / `fetch-local` / `register-cleanup!` /
`destroy-store!`, once-initialized locals plus LIFO cleanups, already tested),
`reakt`'s `add-on-dispose!` / `dispose!`, and `Extension/:deregister-fns`.
**A fourth container is not the missing piece** — the store is ~50 lines and
one already exists. What is missing is the layer above it: composition, and the
check that a mount is well formed. `kmet.cells.scope` does implement the store
once more, but only because rank 0 forbids requiring `kmet.libs.hikap` (§9): it
is the module's smallest part, and composition is the point.

### 1.3 Mounting is four mechanisms

`:extensions [...]` in settings, `:packages` entries with per-type filters,
`:default-tools` with hand-rolled `+name`/`-name` modifiers, and `bootstrap`'s
hard-coded stage order — each with its own config handling, its own way of being
disabled, and its own way of being reloaded. The loop's own composition (its
stages, in order, inside a 260-line function whose docstring carries the
architecture) is a fifth, invisible one.

### 1.4 What the module deletes, and what it does not replace

Deletes: the pending/bound duality and its two bootstrap halves; the 15
dispatchers; the load-order `require`/`resolve` hack; the `applied-resource-paths`
reset; FIFO-and-silent extension teardown; four ad-hoc mount mechanisms.

Does *not* replace, and must not grow into: `kmet.loader` (code-loading
backends), `kmet.libs.hikap.component` (UI component runtime), `kmet.libs.reakt`
(UI reactivity), `kmet.agent.config` (settings loading and merging),
`kmet.agent.event-bus` (the host's event vocabulary), or the extension system
(its ABI, isolation and artifact discovery stay in `kmet.agent.extensions.*`,
which becomes one *backend* here).

---

## 2. What it is

### 2.1 Vocabulary

| term | meaning |
|---|---|
| **unit** | a **handle**, a **`:units`** map of slots, or both — the loop is both |
| **slot** | a unit's name within its group; the map key *is* the id |
| **composition** | a group mounted as a named cross-section (`:app`, `:agent-loop`) |
| **node** | a mounted tree plus its scope; what `mount!` returns, `dispose!` ends, and a handle receives as its first argument. A node may have a parent (§5.6) |
| **scope** | a node's store of registries plus the owners that filled them |
| **owner** | the path a registration belongs to — a unit's slot path, or an extension's key (`[:app :extensions <name>]`); disposal is per-owner |
| **registration** | a value a unit or extension installs under a host-shaped key (a listener, a tool, a command, a timer), with its deregister fn collected automatically (§5.1) |
| **snapshot** | the flat `{slot → resolved unit}` map a caller takes **once per attempt** (§5.4) |
| **entry** | a manifest record from configuration: `{:id :name :config :disabled :into}` (§6) |
| **backend** | a name → implementation resolver supplied by the host (§6.4) |
| **runtime** (`rt`) | the host's resolution backends, validated once — a value, so several may coexist (tests, sandboxes) |

Two of these are values with different lifetimes, and the distinction answers
the question the vocabulary invites: **`rt` is immutable resolution** (several
may coexist — a test's, a sandbox's), while **a node is a mounted lifetime**
(a root per engine or test, a child per subagent, §5.6). Domains are neither —
the loop, the tool registry, the session engine and the extensions are slots in
one node, not one runtime each (§5.6).

**The one rule: a group declares exactly the same fields as a leaf.** Both are
a map with `:handle` *or* `:units`, plus an optional `:config` schema, plus
whatever else the host wants to carry. Nothing outside can tell a group from a
leaf, which is what lets a group occupy a slot and takes presets, diffing and
disposal off the list of things to design separately.

### 2.2 Public API

Seven functions; they are the module's whole surface.

| fn | signature | mutates? | notes |
|---|---|---|---|
| `runtime` | `(runtime host-map)` → `rt` | no | one host key — the resolution backends (§8); `rt` carries a fresh root node, so repeated `mount!` calls diff against it (§6.2) |
| `check` | `(check rt entries)` → `[] \| [err …]` | no | pure pre-flight (§4). No scopes, no code, no effects |
| `mount!` | `(mount! rt entries)` / `(mount! rt entries {:parent node})` → `node` | yes | `check` first, then mount. **Diff-applying and idempotent** (§6.3); the `:parent` form creates a child node (§5.6) |
| `dispose!` | `(dispose! node)` / `(dispose! node owner)` | yes | LIFO per owner (§5.2) |
| `register!` | `(register! node owner k v)` → `deregister-fn` | yes | what a `:start` hook installs with; the fn is auto-collected onto `owner` |
| `composition` | `(composition node)` / `(composition node id)` → snapshot | no | the flat slot map; taken **once per attempt** (§5.4) |
| `explain` | `(explain snapshot)` → string | no | renders what `composition` returns (what `/units` prints, §7) |

### 2.3 Naming conventions

- **`!` marks mutation**, matching `kmet.libs.reakt` (`dispose!`),
  `kmet.agent.extensions` (`register-command!`) and
  `kmet.agent.session.runtime` (`switch-session!`). `composition` and
  `explain` read state but do not change it, so they carry no bang.
- **`mount!`, not `init` or `start`.** There is no activation state: a mounted
  unit is wired, checked and live, in one state. `init` in the
  Integrant/`component` sense builds an unstarted system that a second call
  (`start`) activates — a distinction this design deliberately does not have —
  and `init` is already the extension ABI's entry fn (`(defn init [api] …)`).
- **`check`, not `validate` or `prepare`.** `validate` under-describes (the
  call also resolves names and builds the mount plan) and over-promises (§4
  proves a mount is well formed, not that it behaves). `prepare` implies a
  caller-sequenced two-step and a `plan` value that can go stale between the
  steps; `mount!` taking entries removes that state entirely. `check` is also
  the repo's existing word for pre-flight verification (`bb check`).
- **`dispose!`, not `unmount!`.** Disposal is per-owner, and that is what the
  store models; removing units is `mount!` with a smaller entry list (§6.3).
  Cordis draws the same line between a fiber's `dispose()` and a context's.
- **`kmet.cells.core`** for the facade, matching every other module's
  `<module>.core`.

---

## 3. Descriptors

Descriptors are **plain data** — printable, diffable, comparable in tests, and
serializable if a future host needs to replicate them (§11.1). A handle fn is
the only non-data value.

```clojure
;; a leaf
{:handle (fn [node input] …)
 :config [:map [:threshold :int]]}

;; a subsystem: a handle plus its own slots (this is the loop)
{:handle run-agent-turn
 :units  {:inner  {:handle run-inner}
          :settle {:handle run-settle}}
 :config agent-loop-schema}

;; a leaf that subscribes to the bus when it is mounted
{:start (fn [node owner] (register! node owner {:on-event :turn-end} on-turn-end))
 :handle (fn [node input] …)}

;; any extra keys are host metadata: preserved, printed, never interpreted
{:handle … :effect :pure :address "grep" :execution-mode :parallel}
```

Four keys are interpreted: `:handle`, `:units`, `:start`, `:config`.
**Everything else is preserved untouched** and printed verbatim by `explain` —
the same rule `extension.edn`'s manifest parse already follows (it keeps
unknown `:loader` kinds for forward compatibility). That one rule disposes of
a whole class of design question: purity, addressing, execution mode, tool
schemas and per-front-end tags are host conventions carried in the descriptor,
not framework fields to argue about.

**The mount hook.** `:start` runs once when the unit is admitted, with the
node and the unit's own owner path (`[:app :agent-loop :budget]` — the path its
registrations are collected onto), and is where a unit installs anything it
owns — an event listener, a tool, a command, a timer — through `register!`,
whose deregister fns are collected onto the unit itself. It returns nil, or a
teardown fn that runs *after* everything its body registered (the unit's own
resource unwinds last). A truthy return that is not callable is a mount error,
not a teardown. There is no `:stop`: one hook plus auto-collected
disposers is the whole teardown model, and a unit that needs another order can
do its work in the returned fn.

This is what makes the event bus a first-class participant without teaching the
framework anything about events: **a subscriber is a unit whose `:start`
registers a listener.** The bus, its dispatch modes (emit / collect /
waterfall) and its vocabulary stay in `kmet.agent.event-bus` (§8).

There is **no `:id` field**: a unit's id is the key it occupies in its group's
`:units` map, which makes uniqueness structural rather than checked. There is
**no `:kind`**: `:handle` and `:units` already say what a unit is — and a unit
with both *runs* and owns slots, which is the ordinary case for a subsystem.

### 3.1 What a group is, and is not

A group is a **namespace of slots** — a naming, lookup and lifetime boundary.
It is **not** an execution order: the framework never invokes a group's
children. The owning handle decides what to call, in what order, how many
times, and under what conditions; it reads slots from the snapshot (§5.4) and
calls them. `run-agent-turn` in `cells-plan.md` §1 is the worked example.

A slot holds a group just as happily as a leaf, so a slot that means "a bag of
policies" is `{:units {:a … :b …}}` and the caller iterates it — the same shape
at every level. The cost of this honesty is that **the framework cannot invent
an extension point**: an extension can replace a slot, or fill a list-valued
slot the host consults, and a host that wants a new insertion point must
declare one. That limitation is real and belongs to the host.

### 3.2 The shape, end to end

The worked shape — the module's surface, the host's side, what comes back — is
[`cells-plan.md`](../../../../../cells-plan.md) §1: scaffolding for the build, and it
comes out when P2 lands (§9). The contracts it illustrates are here: §2.2's seven
functions, §3.1's rule, §4's checks.

---

## 4. The checks

`check` runs these over the whole tree, and `mount!` runs them again
internally before it touches anything. Both are decidable; a failure produces
errors naming the entry and the failing key, never a bare boolean.

1. **Shape** — every unit has at least one of `:handle` / `:units`; every
   `:name` resolves through a backend (§6.4); every `:into` names a
   composition that exists.
2. **Config** — each entry's `:config` validates against the `:config` schema
   of the unit it targets (malli). An entry with no `:config` is not an error
   when the unit has a schema (the unit keeps its own defaults), and a unit
   with no `:config` schema accepts any `:config` unchanged — a descriptor
   without a schema has no contract to violate.

**What the checks cannot prove.** That a mount is *well formed*, and nothing
else. There is no verification of behavior, purity or wiring beyond
resolvability, and deliberately no dependency checking at all: units do not
declare dependencies, so "this unit waits for something nobody provides" is
not expressible and therefore cannot be silently wrong. The DSH failure mode
this replaces (§6.4) is prevented by not having the declaration, not by
checking it.

Subscriptions are not checked either, and do not need to be: a subscriber
carries its handler (§3), so it cannot dangle the way a declaration can.
Whether an event *name* exists is the host's bus business — the framework does
not know the vocabulary. A host that wants that check walks `composition` and
validates its own metadata, which is preserved for exactly this reason.

---

## 5. Mounting and lifetime

### 5.1 Scope and owners

A scope is one store of registries plus the owners that filled them. The node
wraps the store, and `cells/register!` / `cells/dispose!` are the operations
below, seen from outside:

```clojure
(scope/register! node owner :tools/hooks hook)  ; → deregister fn, auto-collected onto OWNER
(scope/dispose!  node owner)                    ; LIFO over that owner's registrations
(scope/dispose!  node)                          ; every owner, then the store
```

Registration returns a deregister fn, as it does today. The difference is that
the caller no longer *has to* keep it: `register!` attaches it to the owner
(via a bound `*owner*` in the extension case), which is the part of Cordis's
`this.caller.collect` worth copying.

**Owners are slot paths.** A unit's registrations are owned by the unit's slot
path — the composition and slot ids that reach it, `[:app :agent-loop :budget]`,
which `:start` receives as its second argument — so replacing its slot disposes
whatever it installed: the `:start` +
`register!` path. An extension's own registrations (anything `init(api)`
installs outside a unit) are owned by `[:app :extensions <extension-name>]`.
Both are flat keys in one store: a unit's registrations and an extension's
dispose independently without a scope tree, and a group disposes its children's
owners before its own. Node nesting is a separate axis (§5.6).

### 5.2 Disposal is LIFO, isolated and logged

Reverse of registration order, each cleanup wrapped so one failure cannot skip
the rest, and a failing cleanup reported through
`kmet.cells.scope/*error-reporter*` — stderr by default, which the agent
(`kmet.agent.runtime`) rebinds to `kmet.agent.debug/log-error`. The reporter is
a dynamic var because the module is rank 0 and cannot require `kmet.*` (§9).
This is a bug fix, not a refactor: today's `(catch Exception _)` also misses
`Throwable`, and the order is forwards.

### 5.3 The module has no clock

`dispose!` never waits. Quiescence is the caller's decision — for the agent
that is the `:agent-settled` boundary, where the runtime already knows no turn
is streaming. A framework that slept on its own would be a second scheduler
next to `kmet.libs.concurrent`, and the two would disagree.

### 5.4 Snapshot discipline

`(composition node id)` returns an immutable, **flat** `{slot → resolved unit}`
map — flat because a handle looks up the units it may call, and nothing deeper.
Callers take it **once per attempt** rather than per dispatch:

- a mid-run manifest change affects the *next* attempt, never the streaming one
  in flight;
- an in-flight invocation already holds its closure, so nothing dangles;
- capability tables (tools, commands) are read from the same snapshot, which
  closes the existing race where a tool unloads between the model's `tool_use`
  and its execution and execution fails with "unknown tool".

The map is the ABI (§7, §12 Q4); one composition's snapshot looks like:

```clojure
{:budget {:handle budget-spend               ; the unit's resolved handle
          :owner  [:app :agent-loop :budget] ; where its registrations are owned
          :config {:usd 5.00}                ; only when the entry supplied one
          :keys   #{[:on-event :turn-end]}   ; only what :start registered
          :effect :pure}                     ; the descriptor's other keys, verbatim
 :inner  {:handle run-inner :owner [:app :agent-loop :inner]}}
```

`(composition node)` is the `:app` composition and `(composition node :agent-loop)`
the group's own; an unknown id is nil. Owner paths carry the tree position, so
one level plus its owners is enough to read where a unit sits.

### 5.5 State belongs to the scope

A unit's state belongs in the scope it was mounted in, and disposal kills it.
Atoms created at namespace level by a unit are a bug: they outlive the mount,
which is exactly the failure mode §1.1 documents. `explain` prints the slot
tree with config provenance and owners so this is reviewable.

### 5.6 Nodes nest; domains do not

A node has an optional parent, and disposing a node disposes its children
first. That is the whole nesting mechanism (~15 lines), and it exists for one
purpose: **per-instance lifetimes** — a subagent, a sandboxed run, a test.

Domains are **not** nodes. The loop, the tool registry, the session engine and
the extension surface are slots and owner-scoped registrations inside one node:

```
:app  {:agent-loop {…}  :tools {…}  :session {…}  :extensions {…}  :ui {…}}
```

Splitting them into separate nodes would recreate exactly the cross-boundary
question this design removes — "which node owns the tool registry?" — and would
make one node's handle unable to call another's snapshot. The axes are:
**domains → slots**, **lifetime → owners**, **per-instance isolation → child
nodes**. An extension is never its own node either: it contributes entries into
the host's node and registers under its own owner, which is what "extensions are
a backend, not a parallel system" means (§11.3).

A unit does not know which node it is in — the node is its first argument (§3),
so the same `:kmet/agent-loop` descriptor mounts as the root loop and as a
subagent's loop, each with its own slots, registrations and disposal:

```clojure
(defn unit-handle [node id] (:handle (get (cells/composition node) id)))  ; host helper

(let [child (cells/mount! rt
                         [{:id :session    :name :kmet/sub-session :config {:model model}}
                          {:id :agent-loop :name :kmet/agent-loop  :config {:max-turns 20}}]
                         {:parent node})]           ; the only new arity
  (try ((unit-handle child :agent-loop) child prompt)
       (finally (cells/dispose! child))))            ; child's registrations only
```

The child may be mounted with a *different* `rt` — a sandbox whose only
resolvable names are extension units, say — because `rt` is a value. Shared
machinery (auth, the provider HTTP layer, session files) is namespace code, not
node state, so it is shared by construction; what a child node isolates is
composition and registrations, which is what a subagent's own model, tool
subset and hooks need (pi's `durable`, recorded *open* in `pi-alignment.md`).

Child nodes hang off the node, not off the slot whose unit mounted them:
replacing or dropping a slot disposes that unit's owners (§5.1) but does not
end children it created — the run that mounted one disposes it (`finally`
above), and whole-node `dispose!` is what ends children someone forgot. That is
why §8 gates unit removal on a quiescent boundary: a child outliving the unit
that started it is a host bug, not a lifetime the store can infer.

---

## 6. The manifest

### 6.1 Entries

```clojure
{:id       :retry-policy   ; the slot id in the target composition (required)
 :name     :kmet/retry     ; what resolves it (§6.4)
 :config   {:max-retries 3}
 :disabled false
 :into     :agent-loop}    ; the group's slot id; defaults to :app
```

One entry shape replaces the four current mechanisms. The old keys stay as
**aliases** during migration — `:extensions`/`:packages` entries and
`:default-tools` `+name`/`-name` merging are public configuration; they are
translated to entries at load, not removed (`apply-default-tools!` semantics
preserved exactly). There is no `:from` backend hint: backends are tried in the
host's declared order and the first that resolves wins, so the common case
needs no field and a miss reports every backend attempted.

`:disabled true` makes an entry inert: `check` skips it (a parked entry may
name a backend that is gone) and `mount!` never mounts it. `:app` is the root
composition and not a valid `:id`. Composition ids — the ids of entries whose
units carry `:units` — are unique across the whole entry list; slot ids are
unique within one composition, and a leaf id may repeat under different groups.
A unit's inline `:units` are defaults the same composition mounts: an entry
whose `:id` matches one replaces it, an entry with a new id adds a slot.
`:into` names entry-declared compositions only — to mount children under a
group, declare the group as an entry; inline groups (`:units` inside `:units`)
are private to their unit. Inline group ids still occupy the composition space,
and `mount!` refuses a mount that uses one twice.

### 6.2 Diffing by `:id`

`mount!` diffs the entry list by `:id`: an id the target composition does not
have is an **addition**; an id it has is a **replacement** (config or name
change remounts that subtree, disposing what it displaced); an entry dropped
from the list is **disposed**. An entry with a generated id is
removed-plus-added on every read and therefore remounts — the reason `:id` is
required. Removal is not a separate operation: unmounting is `mount!` with a
smaller list, which is what makes P5's reload a single call.

A slot is kept when its owner path, resolved unit, `:name` and `:config` are
all unchanged; the path is compared because a composition id can be reached
under a new parent after a rename, and its registrations must move with it.
Disposing one owner's registrations (`dispose!` with a path, §5.2) is not an
unmount: the slot stays in the snapshot, and an identical re-mount does not
re-run its `:start`.

### 6.3 `check` / `mount!`

```
(cells/check  rt entries)  ; → [] | [err …]   pure: no scopes, no code, no side effects
(cells/mount! rt entries)  ; → node           check first, then scopes + resolution + mount
```

`check` is the transactional hinge, and `mount!` is the only way in. **`check`
does build the plan** — it must resolve every name and validate every `:config`
to answer at all — and then **discards it**: what comes back is the error
vector, empty when the entries would mount. `mount!` re-plans rather than
accepting one, because a plan is an artifact of a moment: between building it
and mounting it an extension can load or unload and a backend can change, so a
caller-held plan is either a stale hazard or — if `mount!` re-validates it
anyway — a cache with no purpose. Re-planning costs a map lookup apiece plus
malli on a small map, once per mount.

Both calls resolve names **without loading anything** and run §4; `mount!`
throws `ex-info` with the same errors instead of returning them. What a caller
wants *instead of* a plan — "show me what would mount" — is a description
rather than a mountable artifact, and the answer today is to mount and
`explain`: mounting is cheap and reversible, which is what diffing by `:id`
buys (§6.2).

Today an extension that throws inside `init` leaves whatever it registered
before the throw in place, and the 13 pending resets in `bootstrap` are the
compensating hack; with per-owner scopes, a throwing `init` disposes its own
scope and the process is back where it started.

`mount!` is likewise not transactional — `:start` runs as the walk reaches each
slot, so a later `:start` that throws leaves earlier slots mounted. What it
does not do is leave a node the snapshot cannot describe: a throwing `:start`
unwinds its own owner before the error propagates, and the mount table is
written as each slot is admitted, so `composition` names exactly what is
mounted and a retry diffs against the truth. Structural errors are caught
earlier still: composition ids are validated over the whole effective tree
before anything is touched, so a duplicate id leaves the node exactly as it
was (§6.1).

### 6.4 Resolution and backends

`:name` resolves through a backend the **host** supplies:

| backend | resolves | evaluates code? |
|---|---|---|
| `:builtin` | a preloaded unit table (a var registry) | no |
| `:ext` | a unit handle in an extension the host has loaded | no — the host's loader already did |

The host supplies them as an ordered vector of `[kind resolve-fn]` pairs;
there must be at least one, they are tried in order, and a miss reports every
kind attempted. A backend should return the *same* descriptor value for a name
while it is loaded: the diff compares resolved units by value, so one that
rebuilds an equal-but-fresh descriptor (a new closure per call) remounts on
every pass.

**The framework never loads code, and never sees a classpath.** Dynamic
loading, isolation and artifact discovery stay in `kmet.loader` and
`kmet.agent.extensions.*`; this module resolves names to handles the loader
already produced. That boundary keeps the module dependency-free (§9) and
means it cannot become a second, weaker loader by accident.

**No `inject`, no `PENDING`.** Cordis suspends a plugin until its declared
services exist, with a documented failure mode: a dependency nobody provides
waits forever, silently — DSH's own docs call PENDING a "legitimate state", so
there is no error to find. A unit here declares no dependencies: its group's
handle calls it, or it is reached from the snapshot by slot id. Nothing can
wait, so nothing can wait forever.

**Handles may come from anywhere.** The framework only ever calls a handle,
`(handle node input)`, and never inspects it — so it may be a plain var, a
function created inside an extension's loader context and shared by reference,
or a function evaluated in `run_code`'s SCI sandbox. It cannot tell them apart,
which is the point: **the host supplies one backend per isolation mechanism it
has** (§8.2), and `kmet.loader` stays where it is. One invariant comes with it:
**a handle is valid only while its owner is live**, so a host drops the entry
and disposes the owner together, at a quiescent boundary (§5.4).

Because `:name` resolves to a *descriptor*, a backend supplies the `:config`
schema too — and a malli schema is data, so an isolated context can author its
own config contract. That is Cordis's `Config` export working across an
isolation boundary, for free.

---

## 7. Visibility

`(composition node)` returns the mounted data: per slot its resolved handle,
config, the unit's own host metadata, and the registry keys its `:start`
installed (§8.1; the exact map is §5.4's). `(explain snapshot)` renders that
value for a human, and is
what `/units` prints.

The split is deliberate: the data form is what tests assert and what a diff
shows; the string form is cosmetic and free to change (§12, question 4).

This is not a debugging nicety; it is the payoff that makes §1.3's trade worth
it. Today the architecture of the loop lives in a docstring and is read by
opening a 260-line function. After the change the slot tree *is* the
architecture, and it must be readable — reviewed in diffs, printed on demand,
asserted in tests. A composition nobody can print is a composition nobody can
maintain.

The **entry list** — plus each entry's config provenance — is the module's one
replicable artifact: a future multi-process host (pi's `chord`, recorded as
*open* in `modules/agent/src/kmet/agent/development/pi-alignment.md`) would ship
entries and resolve names in the target process. Never the snapshot: it holds
handles, which are fns and live-only (§6.4).

---

## 8. The host contract

The host supplies one thing: **the resolution backends** —
`{:resolve [[:builtin f] [:ext g]]}`, built and used in §3.2. Nothing else
crosses the boundary: no event vocabulary (a subscriber reaches the bus through
`register!`, §8.1), no config schema registry, no classpath. An entry's
`:config` validates against the malli schema on its unit's `:config` field
(§9), and payload validation stays host-side, at the event bus.

### 8.1 Subscribers are units

The agent's event bus is the host's, and a subscriber is a unit whose `:start`
registers a listener:

```clojure
;; kmet.agent.runtime — the host's subscriber convention
(defn subscribe! [node owner evt handler]
  (cells/register! node owner {:on-event evt} handler))

;; an extension's unit
{:config [:map [:threshold :int]]
 :start  (fn [node owner] (subscribe! node owner :turn-end on-turn-end))
 :handle (fn [node input] …)}
```

`explain` prints each unit's registry keys, so "which units listen to
`:turn-end`" is answerable from the node instead of by grep — and because the
keys are host-shaped, the host's view can group them however it likes. The
same path covers the other listener-shaped capabilities (tool-call and
before-agent-start hooks, entry and message renderers, markdown transformers,
tool and command registrations): they are registrations made in `:start`,
owned by the unit, and disposed with it.

### 8.2 Isolated nodes

Isolation is the host's, one backend per mechanism:

```clojure
;; kmet.agent.runtime

(defn ext-backend []                  ; kmet.loader: SCI or native Jolt
  (fn [nm] (some-> (extension-unit nm) :unit)))

(defn sandbox-backend [run]           ; run_code's SCI sandbox
  (fn [nm]
    (when-let [src (script-for run nm)]
      {:handle (eval-handle-in-sandbox! run src)      ; fn created in-context
       :config (declared-config-schema run nm)})))    ; malli data, authored there
```

**Extensions.** `load-extension!` runs `init(api)` as it does today; the
descriptors it publishes resolve through `:ext`, and its registrations land on
`[:app :extensions <name>]`. The host mounts its root node *before* loading any
extension, so `init(api)` always has a scope to register into — that ordering
is what retires the pending buffers. An extension's units are named by keyword
in the extension's namespace (`:budget/spend`), which is how an entry names one
without a backend hint. An extension's units may also be declared in
`extension.edn` as data — descriptors with their `:config` schemas but no
`:handle` — and the host validates those with malli *before evaluating the
extension's code*; the handle binds when the extension loads, and `check`
covers the entries whose units are implementable. Unloading is three steps in
order: `mount!` with the extension's entries removed (drops them from the
snapshot and disposes their owners), `shutdown(api)`, `loader/unload!` — after
which every handle that came from that context is dead and nothing refers to it.

**Codemode.** A model-authored entry list is mounted into the run's **own node**
with its **own `rt`** (§5.6), so it can resolve only the names the host
whitelisted, and it is disposed with the run that created it. Whether a run may
contribute entries at all, and to which slots, is a capability decision — the
`rt` is the gate (§12, question 5).

### Host rules

- **A few substitutable seams, not all code.** Only these become units: the
  agent loop, compaction, the retry policy, the provider/model resolver, the
  session store, the tool registry, the UI port. Ordinary code keeps calling
  namespaces directly (`session/append-entry`, not `(svc :session)`), because
  `kmet.agent.test-layer-guard`, `kmet.agent.test-headless-guard` and
  `kmet.libs.test-self-contained` prove layer discipline *statically*, and a
  dynamic lookup table cannot be checked by any of them.
- **Capabilities unload any time; units only at a boundary.** Capability tables
  are order-independent (barring shadowing), so tool/command registration and
  removal stay as cheap as they are today. A unit may be mid-invocation, so its
  removal is gated on a quiescent boundary — one rule, both halves (§5.4).
- **The loop's control flow stays in the loop.** Its stage functions become
  slots the loop's handle calls, and its routing table becomes data the loop's
  own namespace owns and tests (P3). The framework gains no vocabulary for
  iteration, routing or order.
- **Declare the insertion points you want to offer.** The framework cannot
  invent one (§3.1); a list-valued slot (`{:units {…}}`) is how a host offers
  "extensions may add a policy here".
- **No new formats.** Session EDNL, provider wire schemas (`:parameters`),
  extension ABI keys: unchanged. `:units`/`:config` are additive to
  `extension.edn`, whose manifest parse is already whitelist-extract.

---

## 9. Document set and shelf life

Design, contracts and rationale live in this file. What the code will make
obvious — the worked shape and the phases — lives in
[`cells-plan.md`](../../../../../cells-plan.md) at the repository root, and
is deleted as the phases land: two copies of a shape drift, and the document is
always the stale one.

**Rank 0 is design**, not mechanics: the module requires no `kmet.*` namespace
outside its own `kmet.cells.*` and no third-party library (malli is a
root-level dependency, not one of ours), so `test_self_contained` forbids a
foreign `kmet.*` require and a second consumer could take the module as-is.

| scaffolding | deleted when | what remains |
|---|---|---|
| `cells-plan.md` §1 (the worked shape) | P2 lands: `kmet.cells.core` and `kmet.agent.runtime` *are* the shape | §2.2's API table and §3.1's rule |
| §8.1/§8.2's host sketches | P5 lands: both backends exist in `kmet.agent.runtime` | "one backend per isolation mechanism", the handle-lifetime invariant (§6.4) |
| §5.6's child-mount snippet | the first child node ships (P7) | "nodes nest; domains do not" |
| `cells-plan.md` §2 (the phase table) | continuously — a landed phase's row comes out; the git log is its record | the phases still ahead |

§A.1 is not scaffolding: it is a dated snapshot of the tree *before* the change,
and it says so. It stays, as does §A.2.

---

## 10. Staged plan

The phases — P0-P7, framework first and agent second — are laid out in
`cells-plan.md` §2, together with the scaffolding each landing deletes. The
invariant they serve is here: every phase is landable on its own, gated by the
changed-file tasks and by `test-layer-guard` / `test-headless-guard` /
`test-self-contained`.

---

## 11. Design non-goals and rationale

Recorded so the analysis is not redone. Nothing here changes without an
explicit request.

### 11.1 Deliberately not adopted

- **Cordis** (the design source) — ~50 lines of mechanism, already implemented
  three times here (§1.2); `inject`/`PENDING` is for late-arriving services
  (§6.4), and it is TypeScript.
- **Effect-TS `Scope` / Effection** — confirms the primitive (a bag of
  finalizers, closed once, run in reverse) and adds nothing above it;
  structured concurrency stays the submission chain's business.
- **Integrant / component / clip / donut.system / mount** — they solve bootstrap
  manifests and dependency order, not scattered registries (§1.1); their
  `init`/`start` split is not adopted (§2.3).
- **Mycelium / Maestro** — adopted: hierarchical composition (a group occupies a
  slot like a leaf). Not adopted: the pure-value pipeline (§4), FSM routing
  (§A.2).
- **Aero / any reader-tag config** — tags buy syntax where the need is
  semantics; `{:kmet/env "X"}` / `{:kmet/ref [:providers :deepseek]}` as data
  work on both hosts (jolt `:readers` unverified) and keep a tag syntax out of
  the extension ABI, which shares the reader.
- **pi's `chord` (multi-process plugin host)** — out of scope; §7's entry list
  makes it a later, optional layer (recorded *open* in `pi-alignment.md`).
- **A fourth store in `kmet.libs`** — the rejected first proposal: the
  primitive already exists three times over (§1.2). Composition was missing,
  not storage.
- **re-frame / a single app-db** — `AgentState` deliberately uses per-field
  atoms over one application-database atom; `reakt` + `track!` already supply
  the UI dataflow.

### 11.2 Deliberately not done

- **Service-ifying ordinary code.** No dynamic lookup at ordinary call sites
  (§8 host rules).
- **Units for everything.** A unit is a sealed, substitutable seam with a
  config contract; wrapping a function that is only ever called by its
  neighbour adds indirection, a descriptor, and a place for the layer guards to
  stop working.
- **One node per domain, or per session.** The loop, tools, extensions and the
  session engine are slots in one node (§5.6); a session is a slot, and
  switching sessions stays an in-place swap. Nodes nest only for per-instance
  lifetimes.
- **`inject` / `PENDING` / silent-wait** in the descriptor model (§6.4).
- **Loading code in the framework.** No loader, no classpath, no sandbox — the
  module stays rank 0 (§6.4), and each isolation mechanism is a host backend
  (§8.2).
- **A caller-held plan.** `check` builds the plan and discards it; `mount!`
  takes entries and re-plans (§6.3). A plan is an artifact of a moment, and a
  stale one is a hazard the design does not need to carry.
- **The framework being generic-first.** It is written for kmet's two
  consumers (the agent runtime and the extension system) with resolution
  injected, not for an imagined third. `modules/loader/`'s "promotable to a
  standalone library afterwards" is the right ambition and the wrong first
  step.

### 11.3 Recorded decisions

- **The store lands before anything is mounted** (P0 → P1), and the registries
  are consolidated onto it before the surfaces become entries (P2 → P4) —
  without per-owner lifetime, every unit becomes another namespace-level global.
- **Extensions are a backend, not a parallel system** — same descriptors, same
  manifest, same unload path.
- **The loop converts inward-out** — `run-agent-turn` stays the public entry
  through P3 so RPC, print mode and tests keep working unchanged.

---

## 12. Open questions

1. **Capability snapshot timing.** §5.4's per-attempt snapshot of capability
   tables can land with the store (P0), the manifest (P1) or the agent's runtime
   (P2); **settled as P2** (`cells-plan.md` §2), where the tool-unload race it
   closes lives.
2. **List-valued slots as the extension pattern.** §3.1/§8 make "extensions may
   add a policy here" a host-declared list slot, but the shape a host should
   use (`{:units {…}}` iterated by the caller vs a slot per phase) is not yet
   fixed by a real consumer — it should be settled at P5, not now.
3. **Declarative `:on` sugar.** §8.1 makes subscriptions imperative
   `register!` calls in `:start`. A declarative `{:on {:turn-end f}}` host
   metadata key would be printable and checkable against the bus's declared
   vocabulary. Whether it earns its place should be decided at P5 with the
   extensions that would use it, not invented now.
4. **`composition` (data) as an ABI.** **Settled with P1**: §5.4's entry shape
   is the compatibility surface — `explain`'s string form stays cosmetic and
   free to change. Changing the shape is now a deliberate, documented act,
   which is what a future host that replicates entries needs.
5. **Policy for sandbox-authored entries.** §8.2 makes a model-authored entry
   list mechanically possible; *whether* a run may contribute one, and to which
   slots, is a capability decision belonging to `run_code` and the host's `rt` —
   settle it when the feature is wanted, not now.

---

## A. Measurements and the cut record

### A.1 Measurements

Taken from the tree when this document was written; the counts drive §1. Sites
are named rather than line-numbered, since lines drift and names do not.

| symptom | count |
|---|---|
| `current-*-registry` dispatchers (`(or (<engine field>) pending-atom)`) | 15 |
| `defonce pending-*` buffers | 15, of which 13 are drained by `bootstrap` |
| registry copies + pending resets inside `bootstrap` | 15 in, 13 out |
| `default-engine` call sites | 22 |
| `register-*!` fns — of which document returning a deregister fn | 39, 14 |

- **`kmet.agent.session.runtime/bootstrap`** — copies 15 registries into the
  engine record, then resets 13 pending buffers; **`dispose!`** mirrors the
  teardown. Adding one extension point is four edits: a pending atom, a
  `current-*-registry`, a register fn, and both halves of
  bootstrap/dispose.
- **`current-applied-resource-path-registry`** — reaches the engine through a
  call-time `(require 'kmet.agent.session.runtime)` plus `resolve`, to dodge a
  load-order cycle.
- **`clear-extensions!`** — resets `pending-applied-resource-paths` by hand,
  because that registry outlives the extensions that filled it.
- **`kmet.agent.extensions.core`** — `track-deregister!` fills
  `:deregister-fns` with `conj`; `unload-extension!` runs them with `doseq`
  inside `(try (f) (catch Exception _))`.
- **`kmet.agent.core/-main`** — `packages/load-extensions!` runs before mode
  dispatch, which is why the pending buffers are the normal path rather than a
  fallback.
- **`pending-input-hooks` and `pending-before-agent-start-hooks`** — copied into
  the engine but *never* reset, unlike the other 13: process-lifetime hooks, or
  a leak that outlives the extensions that filled it? P2 decides per buffer.
- **`kmet.agent.loop/run-agent-turn`** — ~260 lines carrying the loop's
  architecture in its docstring. Its stages are already named functions
  (`resolve-endpoint`, `call-llm`, `execute-tool-calls!`, `turn-context`,
  `prepare-next-turn!`, `finish-turn!`, `complete-turn!`, `turn-outcome`), and
  `turn-outcome` already returns routing data (`:aborted | :error |
  :completed`) — but the routing is a `case` with `(recur)` transitions and a
  `(throw (ex-info "Unknown attempt outcome" …))` for anything unhandled: a
  dispatch table written as control flow (P3).

### A.2 Cut in review

What earlier drafts carried and dropped, recorded so it is not re-added by
reflex — each cut made against the inventory of units the phases actually need.

| cut | why |
|---|---|
| `:kind` and four of its five values | `:handle`/`:units` already says what a unit is, and `:pipe`/`:fan`/`:loop` only mean something if the framework executes children in order — it does not |
| `:dispatches`, the total-dispatch check, P3's risk | routing is how a unit is implemented; keeping the loop's table in the loop's namespace preserves its retry/overflow/abort ordering (P3) |
| `:in`/`:out`, the event-closure check, the `:events` vocabulary, the grain rule | superseded by the mount hook + `register!` (§3, §8.1): a subscriber carries its handler, so it cannot dangle, unlike a declaration. Names, dispatch modes and the vocabulary stay the bus's |
| `:effect` (purity) | the only mechanical consequence was a nil scope, and purity is not verifiable; it rides as host metadata like `:address` (§3) |
| `:id` in descriptors, the uniqueness check | the group's `:units` key *is* the id, so uniqueness is structural |
| `:address`, `:from` | addressing is the host's registry key; the backend hint is inferred by trying backends in order (§6.1) |
| `unmount!`, a separate `tree` | `mount!` is diff-applying, so removal is a smaller entry list; `composition` *is* the mounted data, so `tree` was a second name for it (§6.3) |
| nested *owners* | owners stay flat; what nests is nodes, for per-instance lifetimes only (§5.6) |
| a config/payload schema registry on the runtime | an entry validates against its unit's own `:config`; payloads are the bus's business (§8) |

**The invariant that made the cuts safe:** a group declares the same fields as
a leaf, so nothing outside can tell them apart. Every mechanism above
disposal, config validation, checking, diffing, presets — was built on that
rule and survived the cuts untouched.
