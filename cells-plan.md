# kmet Cells — implementation plan

Transitional: the build document for `modules/cells`. Design, contracts and
rationale live in `modules/cells/src/kmet/cells/cells.md`; this file carries only
the phases still ahead. It shrinks as they land: the mechanics came out with P0,
the worked shape with P2 (the deleted §1, as cells.md §9 requires), and a landed
phase's row leaves the phase table.

## 2. Staged plan

**P0, P1 and P2 have landed** — the store, `spec`, the seven facade fns, the
mount table with its `:id` diff, the snapshot and `explain`, the toy tests and
the module wiring (P0/P1); then `kmet.agent.runtime`, the app root's store and
every registry it now carries (P2). Their rows left this table (the git log is
the record). Each remaining phase is independently landable and gated by the
changed-file tasks (`bb test-changed`, `bb lint-changed`,
`bb format-check-changed`) plus `test-self-contained` / `test_modules_manifest`
/ `test_module_deps` and the agent guards (`test-layer-guard`,
`test-headless-guard`). **Framework first, agent second** was the build order;
the agent's `deps.edn` dep on cells landed with P2, its first consumer.

| phase | side | what lands | notes |
|---|---|---|---|
| **P3** | agent | `run-agent-turn`'s routing `case` becomes an `attempt-routes` table in `kmet.agent.loop` — handlers return `:again`/`:stop`, `(recur)` stays in the driver | pure in-function refactor, no host change (cells.md §A.2) |
| **P4** | agent | §1.3's five mount mechanisms — `:extensions`, `:packages`, `:default-tools`, bootstrap's stage order (the session engine and UI port ride it), and the loop's composition — become one entry list; the old keys load as aliases (§6.1) | one `mount!` diff replaces the hard-coded stage order, and `/reload` shrinks to remount + config re-read; the loop group mounts handle-less |
| **P5** | agent | extension units — `extension.edn` `:units`/`:config`, published by `init(api)`, resolved by the `:ext` backend; unload = `mount!` without the extension's entries, `shutdown(api)`, `loader/unload!` | entries mount after the extension loads — `check` cannot resolve `:ext` names before it; `applied-resource-paths` (still reset at `clear-extensions!`) retires here (cells.md §1.4) |
| **P6** (unscheduled) | agent | presets as a selector over the entry list | no consumer defined yet; the loop's `:handle` lands with its first caller (P6 or P7) |
| **P7** (unscheduled) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cells.md §5.6) | pi's `durable` is recorded *open* in `pi-alignment.md`; no kmet scope decision |

P3, a pure in-function refactor of the loop, is the lowest-risk phase now that
P2's registry move is in — which is the main reason for the cells.md §A.2 cuts.
The loop's own group is cells.md §1.3's fifth mount mechanism, so it lands with
the other four at P4 instead of with the routing table.

Landing a phase also deletes the scaffolding that phase makes redundant
(cells.md §9): P5's landing removes the host sketches in cells.md §8, and a
landed phase's row leaves this table.
