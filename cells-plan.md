# kmet Cells — implementation plan

Transitional: the build document for `modules/cells`. Design, contracts and
rationale live in `modules/cells/src/kmet/cells/cells.md`; this file carries the
one phase still ahead (P7, deferred) and the follow-up record (§3). It shrinks
as they land: a landed row leaves its table and the git log is the record.

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
registrations or as the units an extension publishes, and `remount!` assembles
the defaults plus those entries by `:id` (cells.md §6.2). A per-session
config-derived entry still waits on the engine running on its own node: the app
tree is shared (cells.md §8).

## 3. Follow-ups and sequencing

The P0–P5c review's follow-ups all landed (F1–F14; the git log is the record),
each gated by the changed-file tasks and the guards §2 names. The order was
dependency and risk, not id order; none is outstanding. A new follow-up gets a
row here: what lands, the evidence that it has not, and any prerequisite. The
ids were `F`-prefixed because `P6` was retired (presets had no design section
and no consumer); the sides matched §2's: *framework* is `modules/cells`,
*agent* is the host.

**P7 is what remains**, with its prerequisites met (F3, F13); its design
(cells.md §5.6) and its missing pieces are in §2.
