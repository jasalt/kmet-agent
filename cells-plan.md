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
| **P3** | agent | `run-agent-turn` wrapped as one group; its stage fns lifted into slots it calls; the routing `case` becomes a data table the loop's namespace owns | straight refactor — no new execution model (cells.md §A.2) |
| **P4** | agent | session runtime, TUI and extension surfaces as entries; one mount table; old config keys as aliases | the four ad-hoc mount mechanisms become one entry list (cells.md §1.3) |
| **P5** | agent | `extension.edn` `:config`/`:units`; `:ext` backend; reload = `mount!` diff + `dispose!` owner; `extensions/core.cljc` splits into loader/manifest/lifecycle; the ABI's `init(api)` registrations map onto the extension unit's `:start` | unload becomes the cells.md §8.2 three steps: `mount!` without the extension's entries (the diff disposes its owners), `shutdown(api)`, `loader/unload!` |
| **P6** | agent | presets as a selector over the entry list | DSH's "the agent loop is itself a plugin", earned |
| **P7** (unscheduled) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cells.md §5.6) | pi's `durable` is recorded *open* in `pi-alignment.md`; no kmet scope decision |

P3, a pure refactor of the loop, is the lowest-risk phase now that P2's registry
move is in — which is the main reason for the cells.md §A.2 cuts.

Landing a phase also deletes the scaffolding that phase makes redundant
(cells.md §9): P5's landing removes the host sketches in cells.md §8, and a
landed phase's row leaves this table.
