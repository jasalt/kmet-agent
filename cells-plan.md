# kmet Cells — implementation plan

Transitional: the build document for `modules/cells`. Design, contracts and
rationale live in `modules/cells/src/kmet/cells/cells.md`; this file carries the
phase still ahead (P7, deferred), the reduction plan (§3) and the follow-up
record (§4). It shrinks as they land: a landed row leaves its table and the git
log is the record.

## 2. Staged plan

**P0–P5c have landed** — the framework and its wiring (P0/P1), the agent
runtime and the registries (P2), the loop's `attempt-routes` (P3), its stages
and the session/extension/UI owner paths (P4), the `:ext` backend (P5a),
declared units (P5b) and resource ownership (P5c).

Each remaining row is landable on its own and gated by the changed-file tasks
(`bb test-changed`, `bb lint-changed`, `bb format-check-changed`) plus
`test-self-contained` / `test_modules_manifest` / `test_module_deps` and the
agent guards (`test-layer-guard`, `test-headless-guard`).

| phase | side | what lands | notes |
|---|---|---|---|
| **P7** (deferred) | agent | subagents: a subagent tool mounts a child node with its own model, stage map, tool subset and hooks (cells.md §5.6) | `runtime/child-node` gives a run its own node and stages, and P7's prerequisites (F3's node threading, F13's event scope) are met. Still missing: the child-state builder (agent state from the child's model, stages, tool subset and hooks), a child session setup, and the tool-subset rule (the child state's `:default-tools`/active set — the registry reads the app root, so a child does not isolate it). pi's `durable` stays *open* in `pi-alignment.md` |

The `:extensions`/`:packages` settings keys stay the enable/disable surface and
`:default-tools` stays a run-option (cells.md §6.1): what they enable lands as
registrations, not entries.

## 3. Reduction plan

The composition layer is gone (R1–R3): the agent uses `kmet.cells.scope`
alone, and `modules/cells` is the store plus its node. What remains is the
documentation and the optional dependency removal.

| step | side | what lands | notes |
|---|---|---|---|
| **R4** | docs | retire the framework documents: `cells.md` (~1k lines) becomes a short store-and-scope contract (or an agent README section); the AGENTS.md topic map loses the cells entry; this file retires with it (cells.md §9's rule) | P7's record stays in `pi-alignment.md` (`durable` open), as "a child node" rather than descriptors |
| **R5** (optional) | agent | delete the dependency: port the store to `kmet.agent.registry`, drop the `modules.edn` row and the `deps.edn` entry | only if "no `kmet.cells` namespace" is itself a goal; costs the rank-0 self-containment and buys little over R3 |

R4 and R5 are independently landable and gated like §2's. What the reduction
gave up: extension-contributed slots and `:units` declarations (no consumer; a
host API can carry a policy list if one appears), declared-unit validation,
`/units`'s tree rendering (a flat owner listing remains), and P7 as designed —
a subagent mounts a child node, not a descriptor tree.

## 4. Follow-ups and sequencing

The P0–P5c review's follow-ups all landed (F1–F14; the git log is the record),
each gated by the changed-file tasks and the guards §2 names. The order was
dependency and risk, not id order; none is outstanding. A new follow-up gets a
row here: what lands, the evidence that it has not, and any prerequisite. The
ids were `F`-prefixed because `P6` was retired (presets had no design section
and no consumer); the sides matched §2's: *framework* is `modules/cells`,
*agent* is the host.

**P7 is what remains of §2's table**, with its prerequisites met (F3, F13);
its design (cells.md §5.6) and its missing pieces are in §2. §3 carries the
reduction plan.
