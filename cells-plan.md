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
| **P7** (deferred) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cells.md §5.6) | `mount! {:parent}` and `runtime/unit-handle` have landed, as have P7's prerequisites (F3's node threading, F13's event scope). Still missing: the loop group's `:handle` (the adapter that builds the child's agent state from its slots), a child session unit (`:kmet/sub-session` has no builtin), and the tool-subset rule (the child state's `:default-tools`/active set — the registry reads the app root, so a child does not isolate it). pi's `durable` stays *open* in `pi-alignment.md` |

The `:extensions`/`:packages` settings keys stay the enable/disable surface and
`:default-tools` stays a run-option (cells.md §6.1): what they enable lands as
registrations, not entries. A per-session config-derived entry goes with the
entry mechanism §3 retires (cells.md §8).

## 3. Reduction plan

The composition layer is weighted for consumers it does not have: production
mounts one group of two stages, `/units` renders it, and `/reload` remounts it.
The store and the scope value are not speculative — the registries and node
threading stand on them — so the reduction targets the composition layer, not
the module.

| step | side | what lands | notes |
|---|---|---|---|
| **R2** | agent | collapse entries to one static group: no `default-entries`, resolution backends, `spec` or `check`; `runtime/node` mounts the loop group directly; the loop reads its two stages from a plain map; `/units` becomes a flat owner listing | one group with two stages is all production mounts, and `check` has no caller outside tests. Touches `runtime.clj`, `loop.clj` (4 read sites), `session/runtime.clj` (dispose), `builtins.clj`, `test_runtime` / `test_loop`; `/units` text changes |
| **R3** | framework + agent | shrink `modules/cells` to the store and a scope: keep `scope.clj` and a minimal node (parent, children, `in-subtree?`), delete `mount.clj` / `spec.clj` / `explain.clj` / `core.clj` and their tests; `mount! {:parent}` becomes `child-node` | ~600 src lines out (878 → ~300); `test_scope` (176 lines) is the store's contract and stays; `child-node` is what P7 needs, not descriptors. Prerequisite: R2 |
| **R4** | docs | retire the framework documents with the layer: `cells.md` (1059 lines) becomes a short store-and-scope contract (or an agent README section); the AGENTS.md topic map loses the cells entry; this file retires when R2–R3 land (cells.md §9's rule) | P7's record stays in `pi-alignment.md` (`durable` open), as "a child scope" rather than descriptors |
| **R5** (optional) | agent | delete the dependency: port the store to `kmet.agent.registry`, drop the `modules.edn` row and the `deps.edn` entry | only if "no `kmet.cells` namespace" is itself a goal; costs the rank-0 self-containment and buys little over R3 |

Each step is independently landable and gated like §2's. What the reduction
gives up: extension-contributed slots and `:units` declarations (no consumer; a
host API can carry a policy list if one appears), declared-unit validation,
`/units`'s tree rendering (a flat owner listing remains), and P7 as designed —
a subagent mounts a child scope, not a descriptor tree.

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
