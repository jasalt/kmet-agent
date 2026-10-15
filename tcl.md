# Tcl/Tk backend — what is left

Working notes on the gaps of the Tk backend (`kmet.tk.*`), ordered by what
would matter first to an app UI built on it. The app port's own work, which
waits for these, is listed at the end. `src/kmet/tk/README.md` is the
package's design document; this file is the open-work list beside it.

## What is complete

Every widget class Tk 9.1 has is a tag — classic `:tk/…` (window, frames,
text, button, label, labelframe, message, checkbutton,
radiobutton, entry, spinbox, listbox, menu, menubutton, panedwindow, scale,
scrollbar, canvas) and themed `:ttk/…` (frame, labelframe, label, button,
checkbutton, radiobutton, entry, combobox, spinbox, scale, scrollbar,
sizegrip, toggleswitch, menubutton, notebook, panedwindow, progressbar,
separator, treeview) — and each tag reaches its widget's whole option set
minus three kinds of option (variable-, script- and cross-widget), listed
below.

Images are supported on the widget surfaces the tags script: the image
props (`:image`, `:bitmap`, `:selectimage`, `:tristateimage`,
`:backgroundimage`, `:tile`) and the canvas items'
`:image`/`:bitmap`/`:disabledimage` take a Tk image name (a built-in bitmap
such as `:questhead`, or a name the app created) or a description map the
widget owns — a file or base64 data with `:format`, and the photo-only
`:zoom`/`:subsample`/`:fit` sizing — and a `:text` widget's `:content` can
embed one. The image is created (`image create photo`/`bitmap`), cached per
widget and description, and deleted with the widget; a name the app owns is
never touched. Item vocabularies outside the tag specs — a menu item's
`-image` — are not props.

`:ttk/treeview` is a table *and* a tree: a row is a vector of cells or a
map with Tk's own item options — `:text` (the tree column), `:values`,
`:tags`, `:open`, `:image` — plus `:id` and `:children`, which nest under
the row's id (default ids extend to `<parent>.1`, … below the top level's
`r1`, `r2`, …). The widget's `:tags` map configures Tk's row tags
(`tag configure`), so colours, a font and a row image reach the rows
naming them in their own `:tags`; `{:id "#0"}` is Tk's tree column —
configured with `heading #0`/`column #0`, kept out of `-columns` — and
`:show [:tree :headings]` asks Tk for it, where the tag's own default is
`:headings`. Tk's `delete` is recursive, so a `:rows` change rebuilds the
whole hierarchy and clears Tk's selection: `:selected` is applied again to
the ids the new rows still have (a stale id is dropped, not a render
failure), and `:open` is part of a row's own data (`tag bind` and
`item move` are not exposed).

`:tk/canvas` items have identity: an item's `:id` is written as its first
Tk tag (Tk's own item ids are numeric, but a tag stands for an item
wherever a tagOrId is taken), so a re-render reconciles by id — a kept
item moves through Tk's `coords` subcommand and takes its changed options
through `itemconfigure`, a new id is drawn, a gone id is deleted with the
callback its `:on-click` held, and a retyped or reshaped item is rebuilt
(Tk cannot unset an option). `:tags` reach Tk's item tags, `:on-click` is
Tk's item bind on the item's tag with the click's coordinates, and a click
reaches the topmost item under the pointer, as in Tk.

Any tag can carry a popup menu: `:context-menu` takes a menu's own items,
the menu is created as the widget's child, and the tag binds the platform's
own secondary click — Tk 9's `<<ContextMenu>>`, which Tk generates for
that gesture, or `<Button-3>` on a Tk without the event — to Tk's
`tk_popup`, which posts at the click and returns at once.

Menu items are Tk's own: `{:kind :check}` for a checkbutton entry and
`{:kind :radio :value … :group …}` for a radiobutton one — a private Tcl
variable for a check item, one variable per radio group, which is Tk's own
exclusivity — with `:checked` as the render's declaration and either
handler receiving the new boolean. Every other key is a Tk menu-item
option (`-state`, `-underline`, `:image`, created as the menu's own), and
`:accelerator` stays what Tk makes it, a label that the app's own `:bind`
chord backs.

Layout is Tk's own: a child is packed unless it carries `:grid {:row …
:column … :sticky …}` or `:place {:relx … :anchor …}`, each map Tk's
options for that manager (grid and place share a container, as in Tk), and
a container that packs or grids takes `:rowconfigure`/`:columnconfigure`
for its rows and columns through `grid columnconfigure`. Naming two
managers on one child, or a manager beside pack's own props, is a kmet
error; a container whose children mix grid and pack is Tk's own, and Tk's
`-in` is refused (a child's master is the tag it renders under).

Tk's native dialogs are `kmet.tk.core` functions: `message-box!`,
`open-file!`, `save-file!`, `choose-directory!` and `choose-color!` script
`tk_messageBox`/`tk_getOpenFile`/… with the props as that command's own
options and answer with Clojure data — a button keyword, a path, a colour
— or nil when the user cancelled (`:multiple` answers a vector of paths,
which only Tcl can split out of the list Tk returns). They block in Tcl's
nested event loop, so they must run on the Tk thread: call one from a
callback, not from a component body, which renders inside the pump.

ttk styles and themes are wired: `kmet.tk.ttk.style` is the style database
(`use-theme!`, `define-style!`, `map-style!`, `apply!`) and
`kmet.app.ui.gui.style` compiles the TUI theme + the transcript palette
into a dark style set, which `scripts/gui_tk_session.clj` applies after
`tcl/install!`. Re-applying the set when the *user* changes the theme is
app work: it is the last item of the app port at the end of this file.

`:tk/text` is both the display surface and the backend's multi-line input.
`:editable true` keeps `-state normal`, turns Tk's undo on, takes `:text`
as the widget's value (read back with `get 1.0 end-1c` and written only
when it differs, so mirroring `:on-change` into `:text` never moves the
caret; a write resets the undo history, so undo covers only the user's own
edits) and binds `:on-change` (`<KeyRelease>`: typing, a paste and an undo
all end in one) and `:on-submit` (`<Return>`, ending in `break` so Tk's
class binding does not also insert a newline). `:content` is refused on an
editable text. The composer built on it (`:ui/composer`) is app work too —
it waits for the app port, listed at the end of this file.

kmet's own widgets are not part of those sets: they are `:ui/…` tags in
`kmet.app.ui.gui.ui` (today `:ui/transcript`, the chat scroll container), added to
the backend through `kmet.tk.core/mount-backend!`/`run-backend!`.

Two guards keep that honest, and are the place to start from when adding a
widget (the `:ui/…` widgets have their own guard, `bb test
kmet.app.ui.gui.test-ui`):

```sh
bb scripts/tk_option_audit.clj          # declared vs. the live widget, per tag
bb test kmet.tk.test-tcl               # the same claim in test form: every
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
  tag): `kmet.tk.core: :ttk/entry has no -placeholder on this Tk (prop
  :placeholder)` — a kmet message naming tag, prop and option.
- **A widget class that does not exist** fails inside Tcl at creation,
  before any option can be checked; `dsl/make-widget` catches that error
  and rethrows it with the tag and the interpreter's patchlevel:
  `kmet.tk.core: :ttk/toggleswitch (ttk::toggleswitch) does not exist on
  this Tk (8.6.16)`.
- **The test suite is version-aware on purpose**: on 8.6, `bb test
  kmet.tk.test-tcl` reports 19 failures and 1 error — the failures are its
  own option-membership assertions (the table above, one per tag), the error
  is the missing `:ttk/toggleswitch` class. It asserts the *running* Tk
  rather than a version range, so a version's gap is discovered rather than
  assumed.

So a kmet tree that stays inside the 8.6 option set runs unchanged on
8.6/9.0/9.1; a Tk 9 prop fails loudly at the first widget of its tag on an
older host, and `ttk::toggleswitch` needs Tk 9.0+.

## 1. Data a tag cannot express

- **Canvas item events beyond a click.** An item's `:on-click` is Tk's
  item bind for `<Button-1>`; `<Enter>`/`<Leave>` (hover), `<B1-Motion>`
  (drag) and a tag bound as a group need the same target machinery for
  other sequences, and Tk's `tag raise`/`lower` for item order.

## 2. Options that are not props

The catalogue (`kmet.tk.options`) leaves out what a plain value cannot
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
- **Cross-widget**: `-menu` (covered for menubuttons and the menubar),
  `-labelwidget`.

## 3. Tk beyond its widgets

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

## The app port (after the backend list)

The numbered sections above are the `kmet.tk.*` gaps that block the GUI app
port. This one is the port's own work — the app's input and rendering, plus
the seam extensions need — and it starts once those gaps are closed.
Nothing here is a backend gap.

- **`:ui/composer`.** The chat input on the editable `:tk/text`: a
  content-sized height (Tk's display-line count), focus on mount, and the
  TUI editor's history.
- **Incremental append.** The transcript re-renders its whole stack as a
  session grows; appending a card should add one widget (`:ui/transcript`
  already hosts each card as its own embedded window, so the missing piece
  is an append path in the component rather than a re-render).
- **Image blocks in a card.** The image layer can display one, but
  `kmet.app.ui.gui.transcript` still renders a `{:type :image}` content
  block as the literal `[image]`: a card body is one height-counted `:text`
  widget, so an image needs its own widget, a pixel fit width (a card is
  measured in character columns), and a fallback for a format the running
  Tk cannot read (its built-ins are PNG, GIF, PPM and XBM; SVG on 9.1 —
  not JPEG).
- **Virtualization.** Mount only the visible rows of a long list; the
  scrolling itself is Tk's.
- **The extension-UI bridge.** kmet's own UI is written against `kmet.tui`;
  the GUI backend has no extension-facing seam yet, so no shipped extension
  can render a GUI surface.
- **The runtime theme switch.** Re-applying `kmet.tk.ttk.style`'s set when
  the user changes the theme (the set itself is complete — see What is
  complete).

## How to keep this list honest

- `bb scripts/tk_option_audit.clj` — declared vs. live options per tag;
  exits non-zero when a tag declares an option its widget lacks, and prints
  the widget classes without a tag (currently none).
- `bb test kmet.tk.test-tcl` — the same check in test form plus a mount of
  every tag with every declared prop, a checkbutton/scale driven through
  Tk, and the dialogs answered by stand-in procs (their emitted option
  words asserted, no user); it skips where there is no display.
- `bb test kmet.tk.test-tk` — the headless recording adapter: tag
  construction, patching, callbacks, the value slots and disposal.
- `bb test kmet.app.ui.gui.test-ui` — the same for the kmet-widget set (`:ui/…`),
  mounted through `kmet.app.ui.gui.ui/backend`.
- `jolt test kmet.tk.test-ttk-style` under a display (on Termux, `xvfb-run
  -a`) — the ttk style database round-trips through a real Tk; the same
  tests record the scripts headlessly on babashka.
