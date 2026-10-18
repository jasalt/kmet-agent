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
phase is independently landable
and gated by the changed-file tasks (`bb test-changed`, `bb lint-changed`,
`bb format-check-changed`) plus `test-self-contained` / `test_modules_manifest`
/ `test_module_deps` and the agent guards (`test-layer-guard`,
`test-headless-guard`). **Framework first, agent second** was the build order;
the agent's `deps.edn` dep on cells landed with P2, its first consumer.

| phase | side | what lands | notes |
|---|---|---|---|
| **P5** | agent | extension units — `extension.edn` `:units`/`:config`, published by `init(api)`, resolved by the `:ext` backend; the `:extensions`/`:packages` keys load as entries/aliases here (cells.md §6.1); unload = `mount!` without the extension's entries, `shutdown(api)`, `loader/unload!` | entries mount after the extension loads — `check` cannot resolve `:ext` names before it; `applied-resource-paths` (still reset at `clear-extensions!`) retires here (cells.md §1.4) |
| **P6** (unscheduled) | agent | presets as a selector over the entry list | no consumer defined yet; the loop's `:handle` lands with its first caller (P6 or P7) |
| **P7** (unscheduled) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cells.md §5.6) | pi's `durable` is recorded *open* in `pi-alignment.md`; no kmet scope decision |

With P4 landed, four of cells.md §1.3's mount mechanisms are absorbed: the
loop's composition, bootstrap's stage order, and the session/UI surfaces. The
`:extensions`/`:packages` keys land with P5, where their units (the `:ext`
backend) exist. `:default-tools` stays a run-option — tools and commands are
registrations, not units (cells.md §5.1) — and a per-session config-derived
entry still waits on the engine running on its own node: the app tree is
shared, so such an entry would be last-wins (cells.md §8's host rules).

Landing a phase also deletes the scaffolding that phase makes redundant
(cells.md §9): P5's landing removes the host sketches in cells.md §8, and a
landed phase's row leaves this table.
