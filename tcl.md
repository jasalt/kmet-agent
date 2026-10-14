# Tcl/Tk backend — what is left

Working notes on the gaps of the Tk backend (`kmet.gui.*`), ordered by what
would matter first to an app UI built on it. `src/kmet/gui/README.md` is the
package's design document; this file is the open-work list beside it.

## What is complete

Every widget class Tk 9.1 has is a tag — classic `:tk/…` (window, frames,
text, button, label, labelframe, message, checkbutton,
radiobutton, entry, spinbox, listbox, menu, menubutton, panedwindow, scale,
scrollbar, canvas) and themed `:ttk/…` (frame, labelframe, label, button,
checkbutton, radiobutton, entry, combobox, spinbox, scale, scrollbar,
sizegrip, toggleswitch, menubutton, notebook, panedwindow, progressbar,
separator, treeview) — and each tag reaches its widget's whole option set
minus four kinds of option, listed below.

kmet's own widgets are not part of those sets: they are `:ui/…` tags in
`kmet.gui.ui` (today `:ui/transcript`, the chat scroll container), added to
the backend through `kmet.gui.tk/mount-backend!`/`run-backend!`.

Two guards keep that honest, and are the place to start from when adding a
widget (the `:ui/…` widgets have their own guard, `bb test
kmet.gui.test-ui`):

```sh
bb scripts/tk_option_audit.clj          # declared vs. the live widget, per tag
bb test kmet.gui.test-tcl               # the same claim in test form: every
                                        # tag's options checked, every tag
                                        # mounted with all its props set
```

The audit's per-widget dump (`--widgets`) prints each class's option list, so
a new tag starts from an audit line rather than from guessing.

## Tk 8.6 vs 9.1 — what the running version changes

The tag table is kmet's, not Tk's: the tag names are the same on every
supported Tk (8.6, 9.0, 9.1). What a version changes is which
*options* exist and whether a widget *class* is there at all. Measured here
against both interpreters on one machine (Tk 9.1.0 from the kmet bundle, Tk
8.6.18 from the Git for Windows bundle):

```sh
KMET_TCL_LIB=…/tcl86.dll KMET_TK_LIB=…/tk86.dll bb scripts/tk_option_audit.clj
```

### Options Tk 9 added (they fail on 8.6)

| tag | 8.6 lacks |
|---|---|
| `:tk/entry` `:tk/spinbox` `:ttk/entry` `:ttk/combobox` `:ttk/spinbox` | `-locale`, `-placeholder`, `-placeholderforeground` |
| `:tk/frame` `:tk/toplevel` | `-backgroundimage`, `-tile` |
| `:tk/label` `:ttk/label` | `-textangle` |
| `:tk/text` | `-locale` |
| `:tk/listbox` | `-inactiveselectbackground`, `-inactiveselectforeground` |
| `:tk/menu` | `-activerelief` |
| `:ttk/button` `:ttk/checkbutton` `:ttk/radiobutton` `:ttk/menubutton` | `-justify` |
| `:ttk/progressbar` | `-anchor`, `-font`, `-foreground`, `-justify`, `-text`, `-wraplength` |
| `:ttk/treeview` | `-headingheight`, `-rowheight`, `-striped` |

Nothing goes the other way: no option exists on 8.6 that 9.1 lacks, so there
is no dropped or renamed option to carry. Tk's `-bd`/`-bg`/`-fg` (and 9.1's
`-bgimg`) are *aliases* in both, and kmet scripts the canonical name — the
audit treats the alias entries as non-canonical so they never show up as
gaps.

### Widget classes

One class in the tag table does not exist before Tk 9.0: `:ttk/toggleswitch`
(`ttk::toggleswitch`). Tk 8.6 has 36 widget classes, 9.1 has 37; every other
class in the table is in both.

### What happens when the running Tk is older

- **An option the widget lacks** fails at the first widget of that tag, from
  `dsl/check-options!` (it asks the widget for its own option list once per
  tag): `kmet.gui.tk: :ttk/entry has no -placeholder on this Tk (prop
  :placeholder)` — a kmet message naming tag, prop and option.
- **A widget class that does not exist** fails inside Tcl at creation,
  before any option can be checked: `kmet.gui.tcl: invalid command name
  "::ttk::toggleswitch"`. That is the one hole in the error story — the
  check cannot run when there is no widget to ask, so the message is Tcl's
  own. Catching it in `make-widget` and rethrowing with the tag and the
  interpreter's patchlevel would close it.
- **The test suite is version-aware on purpose**: on 8.6, `bb test
  kmet.gui.test-tcl` reports 19 failures and 1 error — the failures are its
  own option-membership assertions (the table above, one per tag), the error
  is `ttk::toggleswitch`. It asserts the *running* Tk rather than a version
  range, so a version's gap is discovered rather than assumed.

So a kmet tree that stays inside the 8.6 option set runs unchanged on
8.6/9.0/9.1; a Tk 9 prop fails loudly at the first widget of its tag on an
older host, and `ttk::toggleswitch` needs Tk 9.0+.

## 1. Visible in a themed app

- **ttk styles and themes.** The database layer exists
  (`kmet.gui.ttk.style`: `use-theme!`, `define-style!`, `map-style!`,
  `apply!`, with the name/class/map and per-theme rules in its docstring),
  but nothing calls it at startup yet. The ttk widgets kmet uses for input
  (entry, combobox, spinbox, scale, progressbar, notebook, treeview,
  scrollbar) therefore still follow the *system* theme: a dark kmet palette
  has light-grey entry fields and scrollbars. What is left is the app's own
  style set — the TUI theme tokens compiled to `TFrame`/`TEntry`/… values —
  applied once at startup (and re-applied on a theme switch).
- **Images.** No photo-image support at all: `-image`/`-bitmap`/`-selectimage`
  are not props, the canvas cannot draw one, and a markdown image (which the
  TUI renders) is a gap in a GUI transcript. Needs `image create photo`, a
  way to load a file (`photo read`/`copy -from`), sizing (`zoom`/`subsample`)
  and the Tk 9.1 background-image options (`-backgroundimage`, `-tile` are
  already props for the classic frame).
- **Dialogs.** `tk_messageBox`, `tk_getOpenFile`/`tk_getSaveFile`,
  `tk_chooseDirectory`, `tk_chooseColor` have no home. They are imperative
  (`kmet.gui.tk/message-box!`, `open-file!`), blocking in Tcl's event loop,
  so they must run on the Tk thread — mount!/run-loop!'s thread — not from a
  component body, which renders on the same thread and may call them.
- **An editable text tag.** `:tk/text` owns `-state`: it is a read-only
  display surface (`:ui/transcript` in kmet.gui.ui hosts the embedded-window
  chat stack). A multi-line input (a chat composer
  with the TUI's editing keys) needs a second tag with insert/delete from
  Clojure, marks, `edit undo` (the `:undo`/`:maxundo` props are already
  there), a `see` target, `:on-change` and focus handling.

## 2. Data a tag cannot express

- **The treeview's tree mode.** Rows are flat. A hierarchy needs a row's
  `:children`, the `#0` column (`:show :tree`), `item open`/`move`, and
  per-row `:tags` for colours and fonts (an app's tool-call tree).
- **Canvas item identity.** `:items` is redrawn wholesale when it changes.
  An interactive canvas needs per-item ids, `itemconfigure` for an update,
  `item bind` (an `:on-item-click`) and item tags to address a group.
- **A checkable menu item.** `:items` are commands, separators and
  cascades; Tk's checkbutton/radiobutton items need a `-variable`, and
  `-accelerator` is a label today, not a binding.
- **A right-click popup.** A menu is a menubutton's own (`:items`) or a
  window's menubar (`:tk/menu` with `:menubar true`); `tk_popup` on an
  arbitrary widget (and `-menu` as a prop) has no path yet.
- **Layout beyond pack.** `grid` (forms: rows, columns, weights) and `place`
  (overlays) are missing, so a settings form has to be nested pack frames.

## 3. Options that are not props

The catalogue (`kmet.gui.tk.options`) leaves out what a plain value cannot
be; each group needs machinery:

- **Variable-backed**: `-variable`, `-textvariable`, `-listvariable` and
  their companions `-onvalue`/`-offvalue`/`-tristatevalue`. A checkable
  widget already keeps a *private* variable (that is how `:active` and
  `:on-toggled` work, since a classic Tk checkbutton has no state query);
  what is missing is a *shared* one — two-way text binding, a radiobutton
  group Tk owns, a listbox driven by a Tcl list — with
  `trace add variable` as the piece that carries a Tcl-side change back to
  a kmet callback, and a naming scheme so a kmet-owned variable cannot
  collide with the app's own Tcl.
- **Script-typed**: `-validatecommand`/`-invalidcommand` (entry validation
  with Tk's `%P`-style substitutions), `-xscrollcommand`/`-yscrollcommand`
  outside the `:scrollbar` prop, `-postcommand` (a menu built on open),
  `-browsecmd`/`-invokecommand`.
- **Image-backed**: the `-image` family above.
- **Cross-widget**: `-menu` (covered for menubuttons and the menubar),
  `-labelwidget`.

## 4. Tk beyond its widgets

- **Window management.** `wm` beyond `title`/`geometry`: minimum size,
  `resizable`, `attributes` (fullscreen, topmost, alpha), `iconphoto`,
  `iconify`/`withdraw`, and a `:on-close` hook on `:tk/toplevel` — the run
  loop already traps `WM_DELETE_WINDOW`, but only to end itself.
- **More than one window per process.** `mount!` owns `.` and the dirty
  flag is global (one window per process by design today); a second window
  needs per-window state and a window registry.
- **Clipboard and selection.** `clipboard get`/`append`, the primary
  selection, `tk_textCopy`-style helpers, and drag and drop.
- **Scaling, focus, virtual events.** `tk scaling` (HiDPI), `tk busy` for a
  subtree, `font create`/`configure` for named fonts and Tk 9's fallback
  lists (today a font is a description derived per widget from TkFixedFont),
  `event add`/`generate` so kmet code can publish an event others bind, and
  focus traversal/`tk_focusNext` helpers.

## 5. Rendering

- **Incremental append.** The transcript re-renders its whole stack as a
  session grows; appending a card should add one widget (`:ui/transcript`
  already hosts each card as its own embedded window, so the missing piece
  is an append path in the component rather than a re-render).
- **Virtualization.** Mount only the visible rows of a long list; the
  scrolling itself is Tk's.
- **The extension-UI bridge.** kmet's own UI is written against `kmet.tui`;
  the GUI backend has no extension-facing seam yet, so no shipped extension
  can render a GUI surface.

## How to keep this list honest

- `bb scripts/tk_option_audit.clj` — declared vs. live options per tag;
  exits non-zero when a tag declares an option its widget lacks, and prints
  the widget classes without a tag (currently none).
- `bb test kmet.gui.test-tcl` — the same check in test form plus a mount of
  every tag with every declared prop, and a checkbutton/scale driven through
  Tk; it skips where there is no display.
- `bb test kmet.gui.test-tk` — the headless recording adapter: tag
  construction, patching, callbacks, the value slots and disposal.
- `bb test kmet.gui.test-ui` — the same for the kmet-widget set (`:ui/…`),
  mounted through `kmet.gui.ui/backend`.
- `jolt test kmet.gui.test-ttk-style` under a display (on Termux, `xvfb-run
  -a`) — the ttk style database round-trips through a real Tk; the same
  tests record the scripts headlessly on babashka.
