# kmet Cells — implementation plan

Transitional: the build document for `modules/cells`. Design, contracts and
rationale live in `modules/cells/src/kmet/cells/cells.md`; this file carries only
the phases still ahead. It shrinks as they land: the mechanics came out with P0,
the worked shape with P2 (the deleted §1, as cells.md §9 requires), and a landed
phase's row leaves the phase table.

## 2. Staged plan

**P0–P4 have landed** — the store, `spec`, the seven facade fns, the mount
table with its `:id` diff, the snapshot and `explain`, the toy tests and the
module wiring (P0/P1); then `kmet.agent.runtime`, the app root's store and
every registry it now carries (P2); then the prompt state machine's
`attempt-routes` table in `kmet.agent.loop` (P3); then the loop's stages as
`:kmet/agent-loop`'s inline units — the guard and the retry policy, whose
budget the loop reads from the mounted stage — and the session, extension and
front-end surfaces as address slots whose installs own their process caches
(P4). Their rows left this table (the git log is the record). Each remaining
row is landable on its own — the notes name its prerequisites — and gated by
the changed-file tasks (`bb test-changed`, `bb lint-changed`,
`bb format-check-changed`) plus `test-self-contained` / `test_modules_manifest`
/ `test_module_deps` and the agent guards (`test-layer-guard`,
`test-headless-guard`). **Framework first, agent second** was the build order;
the agent's `deps.edn` dep on cells landed with P2, its first consumer.

| phase | side | what lands | notes |
|---|---|---|---|
| **P5a** | agent | the `:ext` backend and the entry assembly: an extension's published units become entries, the host keeps the list (`default-entries` plus the loaded extensions'), and `remount!` becomes the assembly point; unload = remount without the extension's entries → `shutdown(api)` → `loader/unload!` | entries can only mount after the extension loads — `check` cannot resolve `:ext` names before it; `:ext` must return the stored descriptor (a rebuilt-but-equal one remounts every pass, cells.md §6.4) and the load-failure rollback remounts too; the settings `:extensions`/`:packages` keys keep working (unrelated to the root's `:extensions` address slot) |
| **P5b** | agent | manifest-declared units: `extension.edn` `:units`/`:config` — additive to the whitelist-extract parse — validated with malli before the extension's code evaluates, the handle binding when `init(api)` publishes it | needs P5a; two-phase: `check` the manifest descriptors at load start, mount once the handles exist |
| **P5c** | agent | resource ownership: extension-contributed skills, prompts and themes register under `[:app :extensions <name>]` so unload disposes them — retiring `applied-resource-paths` and its `clear-extensions!` reset | needs P5a; the resource loaders register on the app root today (`skills/load-skills-from-dir`, `prompts/load-prompt-templates-from-dir`), so they take node/owner; `/reload`'s sweeps stay correct for settings-loaded resources; settles cells.md §12 Q2 (list-slot shape) and Q3 (`:on` sugar) against the first real consumer |
| **P7** (unscheduled) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cells.md §5.6) | `mount! {:parent}` and `runtime/unit-handle` have landed; still missing — the loop group's `:handle` (the adapter that builds the child's agent state from its slots), a child session unit (`:kmet/sub-session` has no builtin), and the tool-subset rule (the child state's `:default-tools`/active set: the registry reads the app root, so a child does not isolate it); pi's `durable` stays *open* in `pi-alignment.md` |

With P4 landed, four of cells.md §1.3's mount mechanisms are absorbed: the
loop's composition, bootstrap's stage order, and the session/UI surfaces. The
`:extensions`/`:packages` keys land with P5a–P5c, where their units (the `:ext`
backend) exist. Because extension entries arrive with each load, `remount!`
becomes the assembly point — the defaults plus the loaded extensions' entries,
re-diffed by `:id` (cells.md §6.2). `:default-tools` stays a run-option —
tools and commands are registrations, not units (cells.md §5.1) — and a
per-session config-derived entry still waits on the engine running on its own
node: the app tree is shared, so such an entry would be last-wins (cells.md
§8's host rules).

Landing a phase also deletes the scaffolding that phase makes redundant
(cells.md §9): P5a's landing removes the host sketches in cells.md §8, and a
landed phase's row leaves this table.
