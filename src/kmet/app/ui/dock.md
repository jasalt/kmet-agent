# Dock lifecycle — design and status

Owner: `src/kmet/app/ui/dock.clj`. Related ticket:
[kmetia/kmet-agent#5](https://github.com/kmetia/kmet-agent/issues/5) —
"panel stays on screen with dead keys", reported mostly in selectors and
login dialogs.

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | membership `release!`, login flows leave by identity, real-dock regression test | done |
| 2 | single slot → stack, `cover!`, membership handles, `dispose!` invariant, extension dialogs covered | done |

`dock-generation` / `invalidate-pending!` are gone: validity is membership
(component identity), not a mount token.

## The dock

`DOCK-STACK` on `CoreState` is a stack of entries
`{:component c :focus-target f :borrowed? b}`. The top renders; entries
below stay mounted but hidden — the overlay-stack shape. The editor is
not an entry: `make-dock-area` falls back to `CURRENT-EDITOR-ATOM` when
the stack is empty.

Entering:

- `mount!` replaces the top (pi `showSelector` + `disposeActiveSelector`):
  the displaced top is removed and disposed unless it was mounted
  `:borrowed?`; entries below stay, so closing the new top reveals them.
- `cover!` pushes without touching what is below (pi `showAuthSelect`'s
  temporary `editorContainer` swap).

Leaving is always by membership — never by a mount token:

- the `done` fn returned by `mount!`/`cover!` removes that component from
  wherever it sits in the stack; a handle whose component is gone is inert
  (the replacement semantics pi gets from clearing `editorContainer`);
- `release!` is the same removal for owners that never kept a handle, e.g.
  a flow that mounted its dialog before a selector covered it;
- `clear!` empties the stack (a custom-editor swap, a session reset, an
  extension teardown), disposing every non-borrowed entry.

Disposal stays with the owner, in the fixed order **leave, then dispose**.
Removals never dispose: a docked panel is often composed of several
owners' parts (a compiled frame *plus* a spliced foreign list), and only
the owner knows the parts. `dispose!` is the owner's ordered close — it
removes a still-registered component first (so the visible failure cannot
happen), records the violation in `kmet.error.log`, and throws with
`--debug`. Borrowed entries are never disposed by the dock; their owner
re-mounts or disposes them.

Focus: the `::focus-guard` watch on `DOCK-STACK`. When the component
holding input leaves the stack, input goes to the new top's
`:focus-target` (a selector's inner list, not its inert chrome), else to
the resolver's fallback (topmost capturing overlay, else the focus home).
`mount!`/`cover!` take focus explicitly before publishing the stack,
because only they know the target. The layout's focus home reads
`dock/top-focus-target`, so a selector restored by an overlay's hide gets
real input rather than keys dropped on chrome.

## Failure taxonomy

| Failure | Status |
|---|---|
| Surface covered → its handle invalidated → never leaves (the login bug) | covered surfaces keep their entries; `cover!` + membership |
| Owner finishes while its surface is buried | `release!`/`done` work from any depth |
| A replaced surface's late close yanks its successor | absent components are inert |
| A flow forgets to leave before dispose | `dispose!` removes first, logs, throws in debug |
| Surface removed but still holds keys | `::focus-guard` (any stack change) |
| Extension dialogs clearing selectors below them | they `cover!` / `release!` only their own dialog |

## Issue #5, concretely

`oauth-login!` captured the mount's `done` for its dialog. The method
prompt re-mounted the dialog through the selector path, so the captured
`done` was revoked (generation) and the finally block was inert; it then
disposed the still-docked dialog — panel on screen, keys dead.

The fix removes the round-trip itself: `oauth-prompt!`'s `:select` case
now `cover!`s the dialog with the method selector (pi `showAuthSelect`),
so closing the selector reveals the very same dialog and no restore mount
(or mount handle) exists. The flow leaves with `release!` + `dispose!`,
both by component identity. Regression test:
`test-oauth-login-dialog-covers-and-releases` in
`test/kmet/app/test_interactive_ui.clj` drives the real dock through the
`:select` round trip with no `dock/mount!` stub.

## Call-site rules

- A selector replaces the top with `dock/mount!`; a temporary surface over
  an owner's panel uses `dock/cover!`. Both take one options map:
  `{:focus-target f :borrowed? b}` (the focus target is the interactive
  child when the panel itself is inert chrome).
- An owner close is `release!`/`done`, then `dispose!`. Never call
  `protocols/dispose` (or `dispose-tree!`) directly on a dock panel.
- `clear!` is only for a wholesale takeover (custom-editor swap, session
  reset, extension teardown) — not for closing one panel.
- Tests assert through `dock/top-component`/`dock/top-focus-target`, never
  by destructuring the entry maps.
- A CS without `:dock-stack` is treated as an empty dock: removal paths
  (`release!`, `dispose!`, `clear!`, the guard) no-op, so minimal test
  states keep working. Mounting still needs the real atom.

## Follow-ups

- The overlay stack has the same "disposed while registered" hazard; it
  has a ghost guard but no `dispose!`-style check. Read `tui.md`'s overlay
  section before copying the dock pattern there.
- The `done` handles are kept as the close-path name (pi parity). They are
  now thin `release!` wrappers, so a flow may drop the handle at any time.
