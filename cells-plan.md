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

The P0–P5c review left a set of follow-ups in landed work, and all of them have
landed (F1–F13; `git log` is the record — each landed row left this table). A
new follow-up gets a row here: what lands, the evidence that it has not, and
any prerequisite; §4 orders the landing. The ids were `F`-prefixed because
`P6` was retired (presets had no design section and no consumer); the sides
matched §2's: *framework* is `modules/cells`, *agent* is the host.

| item | side | what lands | notes |
|---|---|---|---|
| **F14** | agent + docs | the empty address slots come out of `default-entries`: `runtime/explain`'s store walk renders every store owner, not only the ones a mounted slot prefixes, and cells.md §5.1's "A registry mounts its address" is rewritten to that store-read rule | raised with F5's landing, whose `store-owner-lines` (`kmet.agent.runtime`) hangs a non-slot owner under the **deepest slot path prefixing it** — an owner no slot prefixes never reaches `/units`, which is the only reason the eight `{:units {}}` entries (`:commands`, `:tools`, `:prompts`, `:skills`, `:themes`, `:session`, `:extensions`, `:ui`) are mounted: no handle, no config, no children, and the host's owners are those same hardcoded paths (`commands.clj` `commands-owner`, `tools/registry.clj` `tools-owner`, `prompts.clj` `prompts-owner`, `skills.clj` `skills-owner`, `extensions/core.cljc` `[:app :themes]`/`[:app :ui]`, `session/runtime.clj`). §5.1's second reason — "what disposes them when it goes" — is already notional for root defaults, which nothing drops (`session/runtime.clj` disposes `[:app :session]`/`[:app :ui]` by owner path). A deliberate design edit, not a cleanup: cells.md §5.1/§11 are rewritten in the same landing, and `test_commands/units-lists-the-mounted-tree` plus `test_runtime` move from slot lines to store lines, as do the five owner docstrings that name their slot (`commands.clj`, `prompts.clj`, `skills.clj`, `tools/registry.clj`, `extensions/core.cljc`). Prerequisite: F5 (landed) |

## 4. Sequencing

Every landed-phase follow-up has landed — the mount correction (F1, F2, F6),
the store's (F7, F9), the bus's (F10, F11, F12), the host contracts' node
threading (F3) and event scope (F13), and the agent promises and docs (F4, F5,
F8) — each gated by the changed-file tasks and the guards §2 names. The order
was dependency and risk, not id order: F4 before F8 (F8's §5.4 text follows
F4), and F13 as P7's other prerequisite.

**F14 is the one outstanding follow-up**, and it lands only behind its
prerequisite: F5's store walk. It removes the empty address slots and rewrites
cells.md §5.1/§11 with them, so it is a design edit rather than a fixing pass —
the changed-file tasks and the guards §2 names gate it like any other landing.

**P7 is what remains.** Its prerequisites landed with F3 and F13; its design
(cells.md §5.6) and its missing pieces stay recorded in §2.
