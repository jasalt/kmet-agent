# Remaining findings — `modules/agent` architecture review

Temporary note at the project root (the `loop-plan.md` convention). Work the
items in order; each is a self-contained, revertible change. Delete this file
when the list is empty.

**Landed so far:** 1 docs/cleanup, 2 session source split, 4 god functions
(`make-agent-event-handler` → one handler per event family +
`run-attempt!`, `compact-context!`, the settings row table, and
`replay-branch!` decomposed), 5 compaction + bash-executor ownership, 6 Tk
sample/theme-controller relocation, 7 test splits (`test_session`,
`test_loop`, `test_extensions`, `test_interactive_ui`, `test_llm`), 8
extension documentation split (contract reference + `docs/extensions.md`),
9 thinking-level policy moved to `kmet.ai.models`, plus the naming pass
(`project_context`, `app_runtime`, `interactive/builtins`, `packages_cli`,
`kmet.extension`) and carry-over 1 (façade-stubbing note in the agent
README's session section).

**Baseline before each item:** `bb check` compiles, `bb lint` 0/0,
`bb test` = 3,915 tests / 22,645 assertions, `bb test-ext` green except the
pre-existing network-dependent `test-llm-copilot-responses-end-to-end`.

---

## 8. Extension documentation (landed)

`modules/agent/src/kmet/extension.md` is now the **contract reference**
(1,083 lines): the `init`/`shutdown` contract, allowed namespaces and
isolation guarantees, the bundled-library table, the runtime lifecycle, and
the full `kmet.extension` API surface. The authoring walkthrough — discovery,
layouts (single file / manifest dir / jar), `:loader` backends and the Jolt
host, `deps.edn`, the bundled-set and Babashka limits, HTTP and background
work, the shared TUI library, testing workflow, worked example — moved to
[`docs/extensions.md`](../docs/extensions.md) (535 lines). Cross-links were
fixed in `AGENTS.md` (topic map), `docs/README.md`, `docs/customization.md`,
`extensions/README.md` and `modules/loader/src/kmet/loader/loader.md`.

The ≤700-line target was not reachable without dropping reference detail: the
API surface alone is 891 lines. If a smaller contract is wanted later, the
only separable bulk left is UI (`### Custom renderers`, `### UI`,
`#### UI requests`, `#### Tag aliases`, ~270 lines) into an `extension-ui.md`
in the same directory — not done, because the api reference reads better as
one file and both audiences now have one document each.

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

1. ~~**Façade stubbing is not universal.**~~ Landed: `kmet.agent.session.core`
   re-exports 63 vars as `(def name impl/name)`; `modules/agent/src/kmet/README.md`
   § Session storage façade now records that the aliases capture values at load
   time and that a `with-redefs` on a façade alias cannot intercept the
   implementation namespaces (`context`/`stats`/`tree` call `store/*`
   directly).
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
