# kmet GUI backends (`kmet.gui.*`)

bb/Jolt GUI frontends for the toolkit-agnostic hiccup engine. The first
backend is **Tk** (Tcl/Tk **8.6 or newer**; 9.1 preferred): `kmet.gui.tk`
is "hiccup-tk" — the backend itself plus the classic-widget tag set
(`:tk/…`) — `kmet.gui.ttk` is the themed-widget set (`:ttk/…`),
`kmet.gui.ui` is kmet's own widget set (`:ui/…`) over both, `kmet.gui.tk.dsl`
the widget machinery all three are written against, and `kmet.gui.tcl`
the host FFI adapter (`babashka.ffi` under babashka, `jolt.ffi` under
Jolt) that owns a real Tcl interpreter and the Tk event loop.

## Where the layers sit

```
kmet.libs.reakt           reactions over plain atoms (host-agnostic)
kmet.libs.hikap           the hiccup engine: parse, keyed reconcile,
                          ComponentFn, refs — parameterized by a backend map
kmet.libs.hikap.component track! render cache, with-let stores, frame hook
kmet.libs.hikap.alias     the qualified-keyword alias registry
kmet.tui.hikap            the TUI backend (tag table, *width*, IComponent)
kmet.gui.tk.dsl           the Tk widget machinery: Tcl quoting and script
                          building, widget records, the callback/binding
                          registry, live-patch decisions, rich text
kmet.gui.tk.options       the Tk option vocabulary: props, value shapes,
                          and the families a spec composes :options from
kmet.gui.tk               the Tk backend + the classic widget set (:tk/…),
                          tag-table assembly, run-backend!/run! loop
kmet.gui.ttk              the themed widget set (:ttk/…)
kmet.gui.ui               kmet's own widget set (:ui/…), over kmet.gui.tk:
                          the backend plus the kmet tag table
kmet.gui.tcl              the host FFI Tcl/Tk adapter (babashka.ffi / jolt.ffi)
```

The GUI tag namespaces mirror Tcl's own: classic widgets are `:tk/…`,
the themed ones `:ttk/…` (Tcl's global namespace vs `ttk::`). Those two
sets hold **only Tk widget classes**; kmet's own widgets and composites
are `:ui/…` tags in `kmet.gui.ui`, which mounts through the backend's
extra-tag seam (`kmet.gui.tk/mount-backend!`, `run-backend!`). A declared
host tag wins over the alias registry, so `:tk/canvas` and `:ttk/frame`
are host tags — see kmet.libs.hikap's parse-host — while any other
qualified keyword still resolves as an alias; a `:ui/…` tag is unknown to
the plain Tk backend on purpose.

The pre-split `kmet.tui.hiccup` was one namespace; the toolkit-agnostic
half is now `kmet.libs.hikap` (+ `.component` / `.alias`), and
`kmet.tui.hikap` is the TUI backend over it: the tag table, the
width-bound render context, the `IComponent` extension, and the TUI-bound
entry points (`root`, `compile-tree`, `render-lines`, `materialize-ref!`).
Backend-independent pieces (`ref`, `compute`, the counters) are required
from `kmet.libs.hikap`.

## The backend map

`kmet.libs.hikap` reaches every toolkit-specific operation through a map:

| key | meaning |
|---|---|
| `:name` | keyword identifying the backend |
| `:tags` | the closed host tag table (`tag` → spec with `:ctor`, `:primary`, `:lens`, `:entries?`, `:apply`) |
| `:text-leaf` | `(fn [text props] component)` for bare string children |
| `:component?` | predicate for foreign spliced components |
| `:entry-component` | stack-entry payload accessor, or nil |
| `:dispose!` | `(fn [component])` release one constructed component |
| `:render-items` | `(fn [items ctx])` render a ComponentFn's child items |
| `:render` / `:render-all` | render one / several compiled components |
| `:bind-context` | bind dynamic render context around a component body |
| `:did-you-mean` | optional unknown-tag suggestion hook |

Backends whose widgets are live objects (Tk) render nothing: `:render*`
return nil, and re-renders are driven by `kmet.libs.hikap.component/set-frame-hook!`
plus a pump on the UI thread. See `kmet.gui.tk/run!`.

## Running the Tk backend

Requirements: babashka or Jolt, plus Tcl/Tk **8.6 or newer** (`apt install
tcl tk`, `brew install tcl-tk`, or the MSYS2/Windows DLLs). The adapter
tries the newest pair whose Tcl loads — 9.1, 9.0, then 8.6 — and throws
with the names it tried when none is found. `KMET_TCL_LIB`/`KMET_TK_LIB`
force a pair (both together, or a Tcl library whose Tk sibling is derived
from its spelling) — the escape hatch for a Homebrew keg or a mixed
installation. Tk is a Tcl package: the adapter loads libtcl for the
interpreter, then libtk and loads it through Tcl, so one Tcl/Tk
installation supplies both libraries — always as a matching pair, since
Tk 8.6 links libtcl8.6 and Tk 9.x links Tcl 9.x. The Tk library is
resolved in PATH, the working directory and Termux's `$PREFIX/lib`, and
the zipfs library a Tcl 9 build appended to the DLL is mounted before the
load, so the Windows DLLs are standalone — `tcl91.dll`, `tcl9tk91.dll`
and their two dependencies can be dropped next to the executable or
anywhere on PATH, with no installed Tcl/Tk and no compiled-in prefix.
Tcl 8.6 has no zipfs, so a dropped-in 8.6 DLL needs an installed Tcl/Tk
(or `TCL_LIBRARY`) to supply tk.tcl. There is no display support on Termux
without Termux:X11; on Termux, jolt (bionic) can use the system Tcl/Tk
while the glibc babashka build needs a glibc Tcl/Tk.

```sh
bb scripts/gui_tk_demo.clj       # opens a small demo window
jolt scripts/gui_tk_demo.clj     # the same on Jolt

# display a saved session (TUI palette, markdown, tool output):
bb scripts/gui_tk_session.clj [session.ednl] [--width 110]
jolt scripts/gui_tk_session.clj [session.ednl] [--width 110]
#   no path: the most recent session for the current directory
#   --dump N: print the first N rendered lines instead of opening a window
```

The adapter scripts the UI as Tcl (`ttk::label`, `ttk::entry`, `text`,
`ttk::treeview`, …), so adding widgets is a tag spec plus the Tcl command
— no per-widget C bindings. Callbacks run through one `kmet::cb`
trampoline (`kmet.gui.tk/dispatch-callback!`).

### Tags are Tk widgets, in Tcl's two namespaces

A tag names the widget it constructs, in Tcl's own namespace split: the
classic widgets are `:tk/…` and the themed ones `:ttk/…`, so the toolkit
is part of the name — `:tk/frame` is the classic frame (a card needs
`-background` and the highlight ring), `:ttk/frame` is `ttk::frame`, and
`:tk/text` has no ttk counterpart because Tk has no `ttk::text`.
`:tk/toplevel` is the root window.

**Every widget class the interpreter has is a tag**: the classic
`button canvas checkbutton entry frame label labelframe listbox menu
menubutton message panedwindow radiobutton scale scrollbar spinbox text
toplevel` and the themed `ttk::button
checkbutton combobox entry frame label labelframe menubutton notebook
panedwindow progressbar radiobutton scale scrollbar separator sizegrip
spinbox toggleswitch treeview`. `scripts/tk_option_audit.clj` reports
that coverage against a live Tk.

Three shapes of tag, on top of the plain leaves:

- **Text fields** (`:tk/entry`, `:tk/spinbox`, `:ttk/entry`,
  `:ttk/combobox`, `:ttk/spinbox`) take `:text` as a prop set through the
  widget's own subcommands — patchable, and never an option.
- **Data widgets** take their content as props rather than children:
  `:tk/listbox` `:items`/`:selected`, `:ttk/treeview` `:columns`/`:rows`/
  `:selected`, `:tk/canvas` `:items` (each a `{:type :line :coords [x y …]}`
  map plus that item's own canvas options), `:tk/menu`/`:tk/menubutton`/
  `:ttk/menubutton` `:items`, `:ttk/combobox`/`:ttk/spinbox` `:values`.
- **Containers** place their children themselves: `:ttk/notebook` adds
  each child as a tab (the child's `:tab` names it, `:selected` picks one),
  `:ttk/panedwindow` as a pane (the child's `:weight`), `:tk/panedwindow`
  as a pane (the child's `:minsize`/`:stretch`). A `:tk/menu` with
  `:menubar true` wires itself onto the window, and a menubutton's
  `:items` build the menu it pops.

A checkable widget (`:tk/checkbutton`, `:tk/radiobutton`,
`:ttk/checkbutton`, `:ttk/radiobutton`, `:ttk/toggleswitch`) shows its
`:active` state and reports it through `:on-toggled`: Tk's `-variable` is
not a prop, so the tag owns a private Tcl variable per widget — which is
also the only state a classic Tk checkbutton can be read from (it has
`select`/`deselect` and no query). A radiobutton compares that variable
against its own `:value`, so `:active` on each button is what makes a
group exclusive.

The classic specs are `kmet.gui.tk`'s `classic-specs`, the themed ones
`kmet.gui.ttk/specs`; `kmet.gui.tk` completes both through
`kmet.gui.tk.dsl/widget-specs` into the backend's closed tag table, which
is why the qualified `:tk/…`/`:ttk/…` keywords are host tags and never
fall through to the alias registry. A spec is one `dsl/widget-spec`: the Tk
command, its `:options`, and whatever else the tag needs — `:commands` for
the props Tk sets through subcommands (a listbox's `:items`, a scrollbar's
`:position`), `:attach` for a container that places its children (a
notebook's tabs), `:after` for what a widget needs once it exists (a
menubutton's menu), `:text-field`/`:checkable` for the two shapes that need
more than options (a text set through the widget's subcommands; a state in
a private Tcl variable).

### kmet's own widgets (`kmet.gui.ui`)

The two tag sets above are Tk widget classes only. A kmet widget — a
composite that scripts Tk itself but reuses widget behavior — is a `:ui/…`
tag in `kmet.gui.ui`, completed by the same `dsl/widget-specs`.
`kmet.gui.ui/backend` is the Tk backend with that tag table merged in, and
`kmet.gui.ui/mount!`/`run!` mount through it; the seam is
`kmet.gui.tk/mount-backend!`/`run-backend!`, so the plain Tk backend
still rejects a `:ui/…` tag.

The one kmet widget today is `:ui/transcript`, the chat scroll container:
one `text` widget whose children are embedded windows (Tk's `window
create`), one card per line, plus a sibling scrollbar (`:see-end` scrolls
to the end on first map, `:read-only` disables the host then). Text
scrolling has no canvas coordinate ceiling (a canvas hits Tk's 16-bit
limit around 32k pixels, which a long transcript reaches quickly), which
is why the container is a text widget.

Pack geometry is the child's, like Tk's own pack options: `:side`
(`-side`, default `top`), `:fill` (default `both` inside a pane container,
`x` otherwise) and `:expand`.

```clojure
[:tk/toplevel {:title "kmet"}
 [:ttk/frame {:padding 8}
  [:ttk/label "hi"]
  [:ttk/frame {}
   [:ttk/button {:text "Go" :side :left :on-click go!}]]
  [:tk/text {:text "…" :wrap :word}]]]
```

### Props and bindings

Props are Clojure data; Tcl spelling stays in the backend. A tag spec maps
each prop to its Tcl option (`{:fg :foreground}` for `:ttk/label`) and each
callback to how its event reaches Tk (`{:on-click {:event "<Button-1>"}}`
on `:ttk/label`, `{:on-click {:option :command}}` on `:ttk/button`).
Keywords and booleans render as Tcl words and flags — `:wrap
:word`/`:none`/`:char`, `:fill :both`/`:x`/`:y`, `:anchor :w` — and a prop
set to nil is absent, so a `configure` only carries what changed.

The callback slots and what the handler receives:

| prop | tags | handler receives |
|---|---|---|
| `:on-click` | `:ttk/label` (`<Button-1>`), `:ttk/button` (`-command`), `:tk/button`, `:tk/menubutton`, `:ttk/menubutton` | nothing |
| `:on-change` | the text fields (`<KeyRelease>`) | the text |
| `:on-change` | `:tk/scale`/`:ttk/scale` (`-command`, the value Tk passes) | the value, as a number |
| `:on-submit` | the text fields (`<Return>`) | the text |
| `:on-spin` | `:tk/spinbox`/`:ttk/spinbox` (`-command`) | the text |
| `:on-toggled` | the checkable widgets (`-command`) | the new boolean |
| `:on-select` | `:tk/listbox` (`<<ListboxSelect>>`) | a vector of indices |
| `:on-select` | `:ttk/treeview` (`<<TreeviewSelect>>`) | a vector of row ids |
| `:on-scroll` | `:tk/scrollbar`/`:ttk/scrollbar` (`-command`) | Tk's scroll arguments (`"moveto" "0.5"`) |
| `:on-tab-changed` | `:ttk/notebook` (`<<NotebookTabChanged>>`) | the tab index |

`:on-change`/`:on-submit`/`:on-spin` read the text back, and the scales'
`:on-change` receives the number. The value slots read the widget when the
event fires, so a controlled component writes the value back from the
callback. A callback set to nil is unbound in Tk and released from the
trampoline.

`:bind` binds arbitrary events on any tag — `:tk/toplevel` included — by
kmet chord (`:ctrl+enter`, `"ctrl+shift+p"`) or a raw Tk pattern
(`"<Double-Button-1>"`):

```clojure
[:ttk/entry {:text @draft
             :on-change (fn [t] (reset! draft t))
             :bind {:ctrl+enter send! :escape cancel!}}]
[:tk/toplevel {:bind {:ctrl+q quit!}}]
```

A prop that is not in a tag spec's `:options`/`:bindings`/`:patchable`
sets declines the live patch, so the engine rebuilds the widget instead of
silently dropping the change.

### Options: the catalogue and its families

Which props a tag takes is its widget's Tk option set. `kmet.gui.tk.options`
is the vocabulary: prop → the Tcl option it names, the value shape, and the
families a spec composes from. A family is a named prop set whose members
co-occur on a widget population, so a spec adds the families that fit its
widget and lists the odd options itself:

| family | options |
|---|---|
| `:standard` | `:cursor :takefocus` |
| `:classed` `:creation` `:window` | `:class`; `:colormap :container :visual`; `:use :screen` |
| `:themed` | `:style` |
| `:geometry` `:surface` | `:width :height`; `:bg :backgroundimage :tile` |
| `:bordered` `:highlighted` `:spaced` `:padded` | `:relief :borderwidth`; `:highlightbackground :highlightcolor :highlightthickness`; `:padx :pady`; `:padding` |
| `:labelled` `:layout` | `:text :textangle :underline`; `:justify :anchor :wraplength :compound` |
| `:stateful` | `:state :activebackground :activeforeground :disabledforeground` |
| `:classic-text` | the classic text-and-graphics block: `:activebackground :activeforeground :anchor :compound :disabledforeground :justify :underline :wraplength` |
| `:selectable` | `:exportselection :selectbackground :selectforeground :selectborderwidth :inactiveselectbackground` |
| `:fonted` | `:font-family :font-size :bold :italic` → `-font` |
| `:progress` | `:value :maximum :mode :orient :phase :length` |
| `:entry-body` | the entry/spinbox editing block: `:insert*`, `:select*`, `:validate`, `:disabled*`, `:readonlybackground`, `:placeholder*`, `:locale` |
| `:scrollbar-body` | `:orient :troughcolor :jump :elementborderwidth :activerelief :activebackground :repeatdelay :repeatinterval :width` |
| `:scale-body` | the classic scale's own block: `:from :to :resolution :digits :showvalue :sliderlength :sliderrelief :tickinterval :bigincrement :label :repeatdelay :repeatinterval :troughcolor :orient :length` |
| `:text-body` | the text widget's editing body (`:wrap`, `:undo`, `:tabs`, `:spacing*`, `:insert*`, `:select*` where they fit) |

```clojure
;; a tag's whole vocabulary, in one line per widget
:options (opt/options-for [:standard :classed :themed :geometry :bordered :padded])
:options (opt/options-for [:standard :classed :themed :progress :fonted]
                          [:fg :anchor :justify :text :wraplength])
```

`options-for` checks every family and prop against the catalogue at load,
so a typo fails there. Values keep their shape per prop: keywords and
booleans render as Tcl words and flags, a sequence is one Tcl list
(`:padding [6 12]`, `:tabs [40 80]`), and the font props build `-font`
from the four of them. An empty string is `{}` — not nothing, which would
hand the next option to Tk as its value.

Two consequences worth knowing:

- **Creation-only options.** Tk takes `-class`, `-colormap`, `-container`,
  `-visual`, `-use`, `-screen` only at creation, so `check-options!` and the
  patch script leave them out: changing one declines the live patch and the
  widget is rebuilt with the new value. Everything else in `:options`
  patches through `configure`.
- **A prop this Tk lacks fails loudly.** Before a widget is used, the
  backend asks it for its own option list (`configure`, cached per tag) and
  throws naming tag, prop and option — a family that does not fit the
  widget, or an option newer than the running Tk (Tk 9 added `-locale`,
  `-placeholder`, `-textangle`; 9.1 `-backgroundimage`, `-tile`). The check
  covers what a tag declares; a prop no tag declares is still ignored, so
  adding a widget means declaring its options (see *Status*).

Options that are not a plain value are deliberately not props, each
because it needs machinery a prop cannot be: script-typed (`-command` is
`:on-click`/`:on-toggled`/`:on-scroll`, `-validatecommand`,
`-xscrollcommand`), the variable-backed `-variable`/`-textvariable`/
`-listvariable` — a checkable widget's state is the tag's own private
variable, so `-onvalue`/`-offvalue`/`-tristatevalue` are not props either —
the image-backed `-image`/`-bitmap`/`-selectimage`, and the options that
name another widget, `-menu` and `-labelwidget`.

### Rich text (`:tk/text`)

`:tk/text` takes a plain `:text` string or a `:content` vector of styled
segments:

```clojure
[:tk/text {:scrollbar true :fill :both :expand true
           :wrap :word :font-size 12 :bg "#1f2735" :fg "#d7dbe0"
           :content [{:text "plain "}
                     {:text "red" :fg "#ff5500"}
                     {:text " bold" :bold true}
                     {:text " code" :fg "#8abeb7" :bg "#1e2127"}]}]
```

Each distinct style gets one generated Tk tag, cached on the widget;
bold/italic picks one of four named mono fonts derived from Tk's
`TkFixedFont` (`:font-family`/`:font-size` override) and disposal deletes
them. `:scrollbar true` adds a wired vertical `ttk::scrollbar`. The widget
is left `-state disabled` (selectable, not editable); with a wrapping mode
its `-height` follows the wrapped content when a line exceeds the widget
width. `:fill`/`:expand` control packing for any tag, not just `:tk/text`.

### Surfaces, borders and wrapping

`:tk/frame` takes `:border` (thickness) and `:border-color`, plus its
widget's Tk options (`:bg`, `:relief`, `:borderwidth`, the `-highlight*`
ring, `:padx`/`:pady`, `:width`/`:height`, …); its labeled sibling is
`:ttk/labelframe` (`ttk::labelframe`, taking `:text`). The border is drawn
with the highlight ring rather than `-relief solid`: the relief paints in
the widget's own background, which is invisible for a panel sitting on a
similar canvas.

Wrapping belongs to the component, not the widget: the transcript wraps
its styled lines to the transcript width before they reach Tk (`:wrap
:word` on `:tk/text` is then only a safety net). See
`kmet.app.ui.gui.transcript/palette` for the three deliberately distinct
surfaces — window, transcript canvas, message panel — so a missing paint
or an overflowing body is obvious at a glance.

### Interactive development

`run!` is `mount!` + `run-loop!`. The split exists for the REPL: mount on
the thread that will own the interpreter, park `run-loop!` there, edit a
component namespace, `:reload` it and call `kmet.gui.tk/render!` (safe
from any thread — it only marks the tree dirty; all Tcl work stays on the
loop's thread). The backend map and the mounted structure are captured at
mount time, so a change inside `kmet.gui.tk` itself needs a remount.

A mounted body is memoized by the engine, so state it depends on must be
declared: read it with `kmet.libs.reakt/tracked-deref` (a plain `@atom`
read caches the tree and a click repaints nothing).

## Testing

`kmet.gui.test-tk` (bb or Jolt) exercises the whole engine → backend →
script path against a recording adapter — no display needed — and
`kmet.gui.test-ui` does the same for the `:ui/…` widgets over the UI
backend.
`kmet.gui.test-tcl` (bb or Jolt, skipping where libtcl is absent)
smoke-tests the real interpreter: eval, the command trampoline,
`tcl-quote` round-tripping through the Tcl parser, and the recorded
Tcl/Tk pair with the interpreter's own patchlevel.

`scripts/tk_option_audit.clj` (bb, needs Tcl/Tk and a display) enumerates
the interpreter's widget classes and their options and diffs them against
the tag table: the options a tag declares that its widget lacks, and the
options it does not reach yet. `kmet.gui.test-tcl`'s
`every-tag-scripts-only-options-this-tk-has` is the same check in test
form, and additionally builds a widget with *every* declared prop present
(each set to the value Tk itself reports) so the value shapes are
exercised against a real interpreter.

## Status

v1 widget set: **every widget class the Tk interpreter has is a tag** —
the classic `:tk/…` set (window, frames, text, buttons,
labels, fields, list, menu, panes, scale, scrollbar, canvas) and the
themed `:ttk/…` set (frames, labels, buttons, fields, notebook, panes,
progress, separator, sizegrip, toggleswitch, treeview), plus bare-string
labels. Every tag accepts the Tk options its widget has, through the
families in `kmet.gui.tk.options` (see *Options* above); the options Tk
gives to no plain prop — script-, variable- and image-backed — are the
ones still missing, and `scripts/tk_option_audit.clj` reports both sides
against a live Tk. kmet's own widgets are the `:ui/…` set in
`kmet.gui.ui` (today `:ui/transcript`, the chat scroll container).

The first cuts that remain — the treeview's flat tables, canvas items
without identity, checkable menu items, the read-only text tags, and
everything Tk offers beside its widgets (dialogs, images, `grid`/`place`,
window management, ttk styles, the clipboard, variables) — are tracked in
[`tcl.md`](../../tcl.md) at the repository root, which lists each gap with
what closing it needs and how the two guards below keep the list honest.
The `:tk/text` tag carries the displayed content — styled segments in
Tk tags, an optional scrollbar, generated mono fonts, wrapping — and
`:ui/transcript` (kmet.gui.ui) is the scroll container over it: one text
widget whose children are embedded windows (one widget per message) with
a sibling scrollbar and "scroll to the end" on first map.

`kmet.app.ui.gui.transcript` is the chat UI over it: one bordered,
collapsible card per session message type (user, assistant, tool, custom),
reusing the TUI markdown renderer and palette, and
`scripts/gui_tk_session.clj` opens a saved session in it (display only).
kmet's own application UI is still written against `kmet.tui` (line-oriented
components) and is not ported to this backend yet; the next steps are
incremental transcript append (instead of re-rendering the whole stack),
keyed-list virtualization over `ttk::treeview`, and an extension-UI bridge.
