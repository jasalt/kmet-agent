# kmet Cells — implementation plan

Transitional: the build document for `modules/cells`. Design, contracts and
rationale live in `modules/cells/src/kmet/cells/cells.md`; this file carries the
phases still ahead (P7, deferred), the landed phases' review follow-ups (§3)
and their sequencing (§4). It shrinks as they land: the mechanics came out with
P0, the worked shape with P2 (the deleted §1, as cells.md §9 requires), and a
landed phase's row leaves the phase table.

## 2. Staged plan

**P0–P5c have landed** — the store, `spec`, the seven facade fns, the mount
table with its `:id` diff, the snapshot and `explain`, the toy tests and the
module wiring (P0/P1); then `kmet.agent.runtime`, the app root's store and
every registry it now carries (P2); then the prompt state machine's
`attempt-routes` table in `kmet.agent.loop` (P3); then the loop's stages as
`:kmet/agent-loop`'s inline units — the guard and the retry policy, whose
budget the loop reads from the mounted stage — and the session, extension and
front-end surfaces as address slots whose installs own their process caches
(P4); then the `:ext` backend: an extension publishes units and their entries
during `init(api)`, `remount!` assembles the defaults with what the loaded
extensions published, and unload drops both before `shutdown` (P5a); then the
manifest's declared units — `extension.edn` `:units` validated with malli
before any code evaluates, the `:handle` binding when `init(api)` publishes the
name, and a declared name nothing binds failing the load (P5b); then resource
ownership (P5c): every extension registration lands on
`[:app :extensions <name>]`, so unload is one LIFO dispose plus the fns recorded
for what lives outside the tree, and the skills, prompts and themes a
`:resources-discover` contribution loads are owned the same way — retiring the
applied-path set that used to stand in for it. Their rows left
this table (the git log is the record). Each remaining row is landable on its
own — the notes name its prerequisites — and gated by the changed-file tasks (`bb test-changed`, `bb lint-changed`,
`bb format-check-changed`) plus `test-self-contained` / `test_modules_manifest`
/ `test_module_deps` and the agent guards (`test-layer-guard`,
`test-headless-guard`). **Framework first, agent second** was the build order;
the agent's `deps.edn` dep on cells landed with P2, its first consumer.

| phase | side | what lands | notes |
|---|---|---|---|
| **P7** (deferred) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cells.md §5.6) | `mount! {:parent}` and `runtime/unit-handle` have landed; still missing — the loop group's `:handle` (the adapter that builds the child's agent state from its slots), a child session unit (`:kmet/sub-session` has no builtin), and the tool-subset rule (the child state's `:default-tools`/active set: the registry reads the app root, so a child does not isolate it); pi's `durable` stays *open* in `pi-alignment.md`. Deferred until F3 and F13 land (§4): child nodes without node-threaded registrations and event scope are isolation in name only |

With P4 and P5c landed, cells.md §1.3's mount mechanisms are absorbed: the
loop's composition, bootstrap's stage order, the session/UI surfaces, and the
extension settings the `:ext` backend and the owned registrations carry. The
`:extensions`/`:packages` settings keys stay the enable/disable surface rather
than becoming entries — cells.md §6.1 keeps them as public configuration —
because what they enable now lands either as registrations (owned by the
registry they join) or as the units an extension publishes. Extension entries
arrive with each load, so `remount!` is the assembly point — the defaults plus
the loaded extensions' entries, re-diffed by `:id` (cells.md §6.2).
`:default-tools` stays a run-option — tools and commands
are registrations, not units (cells.md §5.1) — and a per-session config-derived
entry still waits on the engine running on its own node: the app tree is
shared, so such an entry would be last-wins (cells.md §8's host rules).

Landing a phase also deletes the scaffolding that phase makes redundant
(cells.md §9) — P5a's landing retired §8.2's `:ext` sketch, leaving the sandbox
one — and a landed phase's row leaves this table.

## 3. Landed-phase follow-ups

The P0–P5c review left four follow-ups in landed work. Each is a defect, a
contract gap or a hygiene item in something already built and stands alone. A
row leaves this table when it lands, its notes carry the evidence and any
prerequisite, and §4 orders the landing. The ids are
`F`-prefixed because `P6` was retired (presets had no design section and no
consumer); the sides match §2's: *framework* is `modules/cells`, *agent* is the
host.

| item | side | what lands | notes |
|---|---|---|---|
| **F4** | agent | the attempt's tool map is captured once and passed to `execute-tool`'s `:tools` (the sandbox override exists); or §5.4's claim is deleted | the loop builds `:tools (active-tools agent)` at request time and `execute-tool` resolves per call, so a tool dropped between `tool_use` and execution still settles as "Unknown tool" — the race §5.4 and §12 Q1 say P2 closed. The command table has the same shape |
| **F5** | agent | `kmet.agent.runtime/explain` reads `scope/owner-registrations` for owners under a slot path — or adds an `/extensions` section — as §7 specifies | a registration under `[:app :extensions "my-ext"]` leaves `/units` unchanged; P5c landed every extension registration on that owner, so the view shows nothing for exactly the registrations it migrated |
| **F8** | docs | §5.1's `*owner*` sentence goes (no such binding exists); §5.4's snapshot claim follows F4; §8.1's subscriber-is-a-unit example is rewritten to §12 Q3's process-level bus (or Q3 is reopened); §2.2 stops calling `core`'s seven fns the whole surface (consumers require `kmet.cells.scope` at 48 sites across six namespaces — promote the reads to `core` or state the split); `rt`'s contract names the singleton/quiescent-boundary and descriptor-stability sharp edges | after F4 and F10, so the amended text matches the code. §6.4 states descriptor stability as a rule, not as a failure mode with no diagnostic; §8's host rules and the process globals deserve the same |
| **F13** | agent | settle child-node event scope before P7: `emit-event!` has no node/owner dimension, so a child loop's events reach every process subscriber and an extension cannot subscribe to one instance | `:owner` is read only by `emit-event-collect!`; the engine's `:subscribers` is the only scoped list and it is not cells-owned. Either the bus gains an owner filter or child runs carry their own subscriber list — a design answer, landed with F3; after F10 the registry is process-level and ordered, so that pass also decides whether it moves onto a store node (owner-tagged registrations, `/units` visibility) or keeps its own structure with a read-time scope filter |

## 4. Sequencing

The remaining follow-ups land in two batches; each batch is one change, gated
by the changed-file tasks and the guards §2 names, and starts after the batch
its prerequisites sit in. The mount correction (F1, F2, F6), the store's
follow-ups (F7, F9), the bus's (F10, F11, F12) and the host contracts' node
threading (F3) landed first. The order is dependency and risk, not id order:
F13 is P7's other prerequisite, and F8 edits contracts F4 and F10 settle.

1. **Child-node event scope (F13).** F3 threaded the node through the host's
   registration helpers; F13 decides the bus's answer to the same question —
   an owner filter on the process registry, a per-run subscriber list, or the
   registry moving onto a store node — because child-node isolation is not
   real until a child's events stop reaching every process subscriber.
2. **Agent promises and docs (F4, F5, F8).** F4 closes the tool race or deletes
   §5.4's claim, F5 makes extension registrations visible in `/units`, and F8
   comes last because it rewrites the text F4 and F10 settle.

**P7 is deferred** until batch 1 lands; its design (cells.md §5.6) and its
missing pieces stay recorded in §2.
