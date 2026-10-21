# Remaining findings — `modules/agent` architecture review

Temporary note at the project root (the `loop-plan.md` convention). Work the
items in order; each is a self-contained, revertible change. Delete this file
when the list is empty.

**Landed so far:** 1 docs/cleanup, 2 session source split, 4 god functions
(`make-agent-event-handler` → one handler per event family +
`run-attempt!`, `compact-context!`, the settings row table, and
`replay-branch!` decomposed), 5 compaction + bash-executor ownership, 6 Tk
sample/theme-controller relocation, 7 test splits (`test_session`,
`test_loop`, `test_extensions`, `test_interactive_ui`, `test_llm`), 9
thinking-level policy moved to `kmet.ai.models`, plus the naming pass
(`project_context`, `app_runtime`, `interactive/builtins`, `packages_cli`,
`kmet.extension`).

**Baseline before each item:** `bb check` compiles, `bb lint` 0/0,
`bb test` = 3,915 tests / 22,645 assertions, `bb test-ext` green except the
pre-existing network-dependent `test-llm-copilot-responses-end-to-end`.

---

## 8. Extension documentation (medium)

`modules/agent/src/kmet/extension.md` is 1,465 lines and mixes three
audiences: the API reference (what every `kmet.extension` fn takes and
returns), the authoring guide (layout, manifests, loaders, isolation), and
worked examples (`extensions/*`). The naming half of finding 8 is done.

Proposal: keep `extension.md` as the **contract reference** (the fn tables,
the api map keys, the allowed namespaces, lifecycle guarantees) and move the
authoring walkthrough + examples to `docs/extensions.md` (user-facing docs
belong in `docs/` per `AGENTS.md`), with links both ways. `AGENTS.md`'s topic
map and `extensions/README.md` follow. Target: contract ≤ ~700 lines.

Do not rename `kmet.extension` again — it is the published ABI.

## 3. List-screen extraction (large)

Three selectors re-implement the same list viewport:

| file | lines | duplicates |
|---|---|---|
| `ui/tui/session_selector.clj` | 1,058 | viewport window, centering, `(N/M)` indicator (`:772`), filter input |
| `ui/tui/tree_selector.clj` | 1,201 | viewport window, centering, scroll indicator |
| `ui/tui/fork_selector.clj` | 174 | same viewport+indicator at `:70–76` |
| `ui/tui/resource_config.clj` | 794 | its own scroll window and selected-item math (`:377`) |

Proposal, in two steps:

1. **Pure helpers** — `ui/tui/list_screen.clj` with `window`
   (`start`/`end` from `selected`, `visible-rows`, `count`), `scroll-indicator`
   (`(N/M)` string), `center-offset`/`pad`, and the row-selection clamp. These
   are pure and unit-testable; replace the three copies. `resource_config`
   adopts `window`/`clamp` if its shape allows.
2. **Shared component** — only if the duplication remains after step 1: a
   `list-screen` defcomponent carrying the search input, key handling
   (up/down/page/home/end/escape), and render loop, parameterized by row
   renderer, filter fn, and select/cancel callbacks. Keep the three
   components' public constructors unchanged.

Risk: off-by-one in the window bounds and centering (the selectors snap the
window when the selection moves). Fix the helpers with tests derived from the
current behaviour before touching the components; then change one selector at
a time, snapshotting render output.

---

## Carry-overs

1. **Façade stubbing is not universal.** `session.core` re-exports 63 vars as
   `(def name impl/name)`, so `with-redefs` on `session/get-branch` replaces
   the façade var only; internal callers read `store/get-branch` directly
   (`session/context.clj:22,87,123`, `session/stats.clj:84,153`,
   `session/tree.clj:15,188`). Tests that stub the façade
   (`loop/test_compact.clj:616`, `modes/test_interactive.clj:1242`) rely on
   the call path going through it. Document this in the agent README's
   session section (a `with-redefs` on a façade alias cannot intercept the
   implementation namespaces).
2. **`session/store.clj` is 566 lines** — the largest session module and the
   one to watch. Split if the entry/usage helpers grow; the façade's public
   surface (pinned by `session/test_facade.clj`) must stay 63 names.
3. **Optional: `tools/bash_output.clj`.** `tools/bash_executor.clj` mixes
   process execution with pure formatting/truncation. `ui/tui/bash_execution.clj`
   and `ui/tui/tool_renderers.clj` require the whole engine only for
   `format-size`, `byte-length`, and `truncate-*`. Moving the pure half into
   `tools/bash_output.clj` keeps the UI out of the process engine; the
   executor, `tools/bash`, and `tools/read` require both. Independent of the
   items above.
