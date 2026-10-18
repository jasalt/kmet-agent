# kmet Cells — implementation plan

Transitional: the build document for `modules/cells`. Design, contracts and
rationale live in `modules/cells/src/kmet/cells/cells.md`; this file carries only
the phases still ahead. It shrinks as they land: the mechanics came out with P0,
the worked shape with P2 (the deleted §1, as cells.md §9 requires), and a landed
phase's row leaves the phase table.

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
| **P7** (unscheduled) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cells.md §5.6) | `mount! {:parent}` and `runtime/unit-handle` have landed; still missing — the loop group's `:handle` (the adapter that builds the child's agent state from its slots), a child session unit (`:kmet/sub-session` has no builtin), and the tool-subset rule (the child state's `:default-tools`/active set: the registry reads the app root, so a child does not isolate it); pi's `durable` stays *open* in `pi-alignment.md` |

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
