# kmet Tk backend (`kmet.tk.*`)

bb/JVM/Jolt GUI backend for the toolkit-agnostic hiccup engine: **Tk**
(Tcl/Tk **8.6 or newer**; 9.1 preferred). `kmet.tk.core` is "hiccup-tk"
— the backend itself plus the classic-widget tag set (`:tk/…`) —
`kmet.tk.ttk` is the themed-widget set (`:ttk/…`),
`kmet.tk.dsl` the widget machinery both are written against, and
`kmet.tk.tcl` the host FFI adapter (`babashka.ffi` on babashka and the
JVM, `jolt.ffi` on Jolt) that owns a real Tcl interpreter and the Tk event
loop. kmet's widget set (`:ui/…`) is demonstrated by the dependency-free
sample `kmet.gui-sample.ui`: one scroll-container composite over the
backend's extra-tag seam.

## Where the layers sit

```
kmet.reakt.core           reactions over plain atoms (host-agnostic)
kmet.hikap.core           the hiccup engine: parse, keyed reconcile,
                          ComponentFn, refs — parameterized by a backend map
kmet.hikap.component      track! render cache, with-let stores, frame hook
kmet.hikap.alias          the qualified-keyword alias registry
kmet.tui.hikap            the TUI backend (tag table, *width*, IComponent)
kmet.tk.dsl               the Tk widget machinery: Tcl quoting and script
                          building, widget records, the callback/binding
                          registry, shared variables, live-patch decisions,
                          rich text
kmet.tk.options           the Tk option vocabulary: props, value shapes,
                          and the families a spec composes :options from
kmet.tk.core              the Tk backend + the classic widget set (:tk/…),
                          tag-table assembly, run-backend!/run! loop
kmet.tk.ttk               the themed widget set (:ttk/…)
kmet.tk.ttk.style         the ttk style database: ttk::style configure/map
                          and theme switching, as Clojure data
kmet.tk.tcl               the host FFI Tcl/Tk adapter (babashka.ffi on bb
                          and the JVM, jolt.ffi on Jolt)
kmet.gui-sample.ui        the widget skeleton: :ui/… over the backend
```

The GUI tag namespaces mirror Tcl's own: classic widgets are `:tk/…`,
the themed ones `:ttk/…` (Tcl's global namespace vs `ttk::`). Those two
sets hold **only Tk widget classes**; kmet's own widgets and composites
are `:ui/…` tags in `kmet.gui-sample.ui` (the sample app UI over this
backend),
which mounts through the backend's extra-tag seam
(`kmet.tk.core/mount-backend!`, `run-backend!`). A declared host tag wins
over the alias registry, so `:tk/canvas` and `:ttk/frame` are host tags —
see kmet.hikap.core's parse-host — while any other qualified keyword
still resolves as an alias; a `:ui/…` tag is unknown to the plain Tk
backend on purpose.

The pre-split `kmet.tui.hiccup` was one namespace; the toolkit-agnostic
half is now `kmet.hikap.core` (+ `.component` / `.alias`), and
`kmet.tui.hikap` is the TUI backend over it: the tag table, the
width-bound render context, the `IComponent` extension, and the TUI-bound
entry points (`root`, `compile-tree`, `render-lines`, `materialize-ref!`).
Backend-independent pieces (`ref`, `compute`, the counters) are required
from `kmet.hikap.core`.

## The backend map

`kmet.hikap.core` reaches every toolkit-specific operation through a map:

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
return nil, and re-renders are driven by `kmet.hikap.component/set-frame-hook!`
plus a pump on the UI thread. See `kmet.tk.core/run!`.

## Running the Tk backend

Requirements: babashka, the JVM or Jolt, plus Tcl/Tk **8.6 or newer** (`apt install
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
A failed Tcl resolution is remembered for the process: the sweep stats
every install directory for all 18 candidate pairs, so a machine without
Tcl pays it once, not on every `prepare!`. The memo is keyed on PATH,
`$PREFIX`, the working directory and the two override variables, so a
changed environment searches again (`stop!` leaves it alone — it records
an installation fact, not interpreter state).
Tcl 8.6 has no zipfs, so a dropped-in 8.6 DLL needs an installed Tcl/Tk
(or `TCL_LIBRARY`) to supply tk.tcl. There is no display support on Termux
without Termux:X11; on Termux, jolt (bionic) can use the system Tcl/Tk
while the glibc babashka build needs a glibc Tcl/Tk.

```sh
bb scripts/demos/gui_tk_demo.clj       # opens a small demo window
jolt scripts/demos/gui_tk_demo.clj     # the same on Jolt
```

The adapter scripts the UI as Tcl (`ttk::label`, `ttk::entry`, `text`,
`ttk::treeview`, …), so adding widgets is a tag spec plus the Tcl command
— no per-widget C bindings. Callbacks run through one `kmet::cb`
trampoline (`kmet.tk.core/dispatch-callback!`).

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
spinbox toggleswitch treeview`. `scripts/bench/tk_option_audit.clj` reports
that coverage against a live Tk.

Three shapes of tag, on top of the plain leaves:

- **Text fields** (`:tk/entry`, `:tk/spinbox`, `:ttk/entry`,
  `:ttk/combobox`, `:ttk/spinbox`) take `:text` as a prop set through the
  widget's own subcommands — patchable, and never an option.
- **Data widgets** take their content as props rather than children:
  `:tk/listbox` `:items`/`:selected`, `:ttk/treeview` `:columns`/`:rows`/
  `:tags`/`:selected`, `:tk/canvas` `:items` (each a `{:type :line
  :coords [x y …]}` map of Tk's own canvas-item options, plus `:id`,
  `:tags` and the item handlers `:on-click`/`:on-drag`/`:on-enter`/
  `:on-leave`),
  `:tk/menu`/`:tk/menubutton`/`:ttk/menubutton` `:items`,
  `:ttk/combobox`/`:ttk/spinbox` `:values`.
- **Containers** place their children themselves: `:ttk/notebook` adds
  each child as a tab (the child's `:tab` names it, `:selected` picks one),
  `:ttk/panedwindow` as a pane (the child's `:weight`), `:tk/panedwindow`
  as a pane (the child's `:minsize`/`:stretch`). A child of a `:tk/labelframe`
  or `:ttk/labelframe` carrying `:labelwidget true` is the frame's *label*
  instead of a placed child (Tk's `-labelwidget`): the tag builds it and
  names it on the parent, which draws it in the label position and drops
  the reference if the widget is later destroyed. A labelframe takes one
  label widget; a later child replaces the earlier. A `:tk/menu`
  with `:menubar true` wires itself onto the window, and a menubutton's
  `:items` build the menu it pops.

The `:items` of a menu, a menubutton or a `:context-menu` are Tk's own menu
entries: `[:separator]` (or `{:kind :separator}`) a rule, a map with
`:items` a cascade (its nested items get a child menu), and
`{:kind :check …}` or `{:kind :radio …}` Tk's checkbutton and radiobutton
entries (a radio one needs its `:value`: it is what Tk compares the group's
variable against). A check item keeps its state in a private Tcl variable, a radio
item in one shared by its `:group` — all the radios of that menu when it
names none — which is what makes the group exclusive in Tk. A handler on
either kind receives the new boolean; `:checked` is what the render
declares, and a radio group's variable is written from its items once per
render, so a group with nothing checked has nothing selected. Every other
key of an item map is a Tk menu-item option — `-state`, `-underline`,
`-accelerator`, `-image` (an image description is created as the menu's
own) — and `:accelerator` is a label and nothing more, as in Tk itself:
the app binds the chord where it binds its other keys (`:bind`, on the
window or the widget).

A checkable widget (`:tk/checkbutton`, `:tk/radiobutton`,
`:ttk/checkbutton`, `:ttk/radiobutton`, `:ttk/toggleswitch`) shows its
`:active` state and reports it through `:on-toggled`: the tag owns a private
Tcl variable per widget unless `:variable` names a shared one — which is
also the only state a classic Tk checkbutton can be read from (it has
`select`/`deselect` and no query). A radiobutton compares that variable
against its own `:value`, so with the default private variables `:active`
on each button is what makes a group exclusive; a group sharing one
`:variable` is Tk's own group, and the render writes only the active
button's value into it.

The `:ttk/treeview` tag is both a flat table and a tree. A row is a vector
of cells or a map of Tk's own item options — `:text` (the tree column),
`:values` (the data columns), `:tags`, `:open`, `:image` — plus `:id` and
`:children`, which nest under the row's id; ids default to `r1, r2, …` at
the top level and to `<parent>.1, …` below it, so a row can be addressed
without being named (a render repeating one is an error, as on a canvas).
Tk's `delete` is recursive, so a `:rows` change
rebuilds the whole hierarchy and clears Tk's selection — which is why
`:selected` is applied again to the ids the new rows still have (an id
that is gone is dropped rather than failing the render), and `:open` is
part of a row's own data. `{:id "#0"}` in `:columns` is Tk's
tree column — the one a hierarchy indents in — configured with
`heading #0`/`column #0` and kept out of `-columns`; `:show
[:tree :headings]` asks Tk for it, where the tag's own default is
`:headings` (a flat table). `:tags {tag {:foreground … :background …
:image …}}` configures Tk's row tags (`tag configure`), which the rows
naming them in their own `:tags` take; a tag's font is built from the font
props like a widget's own, so `:bold`/`:font-family` work there. Tk's
`tag bind` and `item move` are not exposed: a row click is the widget's
`:on-select`, and a re-render rebuilds rather than reorders (the row
vector's order is the tree's).

A canvas (`:tk/canvas`) draws its `:items` as Tk's own items: each is a map
of Tk's canvas-item options (`{:type :line :coords [x y …] :fill "#fff"}`)
plus `:id`, `:tags` and the handlers an item takes — `:on-click`,
`:on-drag`, `:on-enter` and `:on-leave`, which are Tk's item binds for
`<Button-1>`, motion with the button held, and the crossings. Tk gives an
item a numeric id of its own but lets a tag stand for it, so an item's
`:id` (`i1`, `i2`, … when the render names none) is written as its first Tk
tag, and every item command addresses it by that — so an id a render
repeats is an error, Tk tolerating duplicates that would then move and
delete together. A re-render *reconciles*: an id it still has keeps its
item — the coordinates through Tk's `coords` subcommand, the changed
options through `itemconfigure`, a changed handler rebound on its own event
with the trampoline it held released — a new id is drawn, a gone id deleted
with the callbacks it held, and an item whose type or option set changed is
rebuilt, since Tk cannot unset an option.

The render's order is Tk's stacking order, a later item on top: a render
that inserts or reorders raises the items into place, so the stacking
survives a re-render the way identity does. Tk directs a mouse event to the
*current* item, the topmost one under the pointer, so overlapping items
shadow each other's handlers and each handler gets the event's own
coordinates. Those are the *item's* events: a drag that leaves its item
reaches the canvas's own `:bind` instead — Tk's pointer grab keeps the
events coming to the widget, whose handlers hand over no value (a pointer
reading there is a gap in the plan).

The classic specs are `kmet.tk.core`'s `classic-specs`, the themed ones
`kmet.tk.ttk/specs`; `kmet.tk.core` completes both through
`kmet.tk.dsl/widget-specs` into the backend's closed tag table, which
is why the qualified `:tk/…`/`:ttk/…` keywords are host tags and never
fall through to the alias registry. A spec is one `dsl/widget-spec`: the Tk
command, its `:options`, and whatever else the tag needs — `:commands` for
the props Tk sets through subcommands (a listbox's `:items`, a scrollbar's
`:position`), `:attach` for a container that places its children (a
notebook's tabs), `:after` for what a widget needs once it exists (a
menubutton's menu), `:text-field`/`:checkable` for the two shapes that need
more than options (a text set through the widget's subcommands; a state in
a private Tcl variable).

### kmet's own widgets (`kmet.gui-sample.ui`)

The two tag sets above are Tk widget classes only. A kmet widget — a
composite that scripts Tk itself but reuses widget behavior — is a
`:ui/…` tag in the sample app's `kmet.gui-sample.ui`, completed by the
same `dsl/widget-specs`. `kmet.gui-sample.ui/backend` is the Tk backend
with that tag table merged in, and `kmet.gui-sample.ui/mount!`/`run!`
mount through it; the seam is
`kmet.tk.core/mount-backend!`/`run-backend!`, so the plain Tk backend
still rejects a `:ui/…` tag.

The one kmet widget today is `:ui/transcript`, the chat scroll container:
one `text` widget whose children are embedded windows (Tk's `window
create`), one card per line, plus a sibling scrollbar (`:see-end` scrolls
to the end on first map, `:read-only` disables the host then). Text
scrolling has no canvas coordinate ceiling (a canvas hits Tk's 16-bit
limit around 32k pixels, which a long transcript reaches quickly), which
is why the container is a text widget.

Geometry is the child's, like Tk's own geometry options. A child without a
geometry map is packed: `:side` (`-side`, default `top`), `:fill` (default
`both` inside a pane container, `x` otherwise) and `:expand`. A child
carrying `:grid {:row 0 :column 1 :sticky "nsew" :padx [4 6]}` is gridded
(a form) and one carrying `:place {:relx 0.5 :rely 0.5 :anchor :center}`
placed (an overlay), each map taking Tk's own options for that manager; the
two share a container, as in Tk, which is what an overlay is. A child that
names two managers at once, or a manager beside pack's own
`:side`/`:fill`/`:expand`, is an error here; a *container* whose children
mix grid and pack is Tk's own error, and Tk's `-in` is refused — a child's
master is the tag it renders under, and a map under a container that places
its child itself (a tab, a pane, an embedded window, a label widget) is an
error too. A container that packs or grids its
children also takes `:rowconfigure` and `:columnconfigure` — {index Tk's
own options}, through `grid columnconfigure` — for its rows and columns.

```clojure
[:tk/toplevel {:title "kmet"}
 [:ttk/frame {:padding 8}
  [:ttk/label "hi"]
  [:ttk/frame {:columnconfigure {1 {:weight 1}}}
   [:ttk/button {:text "Go" :grid {:row 0 :column 0} :on-click go!}]
   [:ttk/entry {:grid {:row 0 :column 1 :sticky "ew"}}]
   [:ttk/label {:text "over" :place {:relx 0.5 :rely 0.5 :anchor :center}}]]
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
| an item's `:on-click`/`:on-drag`/`:on-enter`/`:on-leave` | `:tk/canvas` `:items` (Tk's item bind) | the event's coordinates, `[x y]` |
| `:on-scroll` | `:tk/scrollbar`/`:ttk/scrollbar` (`-command`) | Tk's scroll arguments (`"moveto" "0.5"`) |
| `:on-tab-changed` | `:ttk/notebook` (`<<NotebookTabChanged>>`) | the tab index |
| `:validatecommand` | the entry-like widgets (`-validatecommand`) | Tk's `%P`, the value the widget would have; the handler answers a boolean (false/nil refuses the edit) |
| `:invalidcommand` | the entry-like widgets (`-invalidcommand`) | Tk's `%P` |
| `:xscrollcommand` / `:yscrollcommand` | `:tk/text`, `:tk/canvas`, `:tk/listbox`, `:ttk/treeview` (both); the text fields (`-xscrollcommand`) | `[first last]` as numbers |
| `:postcommand` | `:tk/menu`, `:ttk/combobox` (`-postcommand`) | nothing |

`:on-change`/`:on-submit`/`:on-spin` read the text back, and the scales'
`:on-change` receives the number. The value slots read the widget when the
event fires, so a controlled component writes the value back from the
callback. A callback set to nil is unbound in Tk and released from the
trampoline. `:validate` (Tk's mode: `:key`, `:focus`, `:focusin`,
`:focusout`, `:all`) is what makes Tk ask `:validatecommand` at all; the
`:scrollbar` prop and an explicit `:yscrollcommand` are two wirings of
Tk's one option, so asking for both is an error.

`:bind` binds arbitrary events on any tag — `:tk/toplevel` included — by
kmet chord (`:ctrl+enter`, `"ctrl+shift+p"`) or a raw Tk pattern
(`"<Double-Button-1>"`):

```clojure
[:ttk/entry {:text @draft
             :on-change (fn [t] (reset! draft t))
             :bind {:ctrl+enter send! :escape cancel!}}]
[:tk/toplevel {:bind {:ctrl+q quit!}}]
```

A binding value may also be a map with the handler under `:handler`, a
`:value` kind the bound script reads — the same kinds the slots use:
`:pointer` puts the event's `%x %y` in the handler's hands, a drag on the
*canvas* keeps its coordinates after the pointer leaves the item (where
the item's own `:on-drag`, Tk's item bind, no longer fires) and `:text`
reads the widget's value — and `:stop true` to end the script in `break`,
which keeps Tk's class binding from running too. Those three keys are the
whole map: a typo'd one is refused rather than binding nothing. A kind the
option itself supplies (`:validate`, `:scroll`) belongs to that option,
not to a binding.

```clojure
[:tk/canvas {:items @items
             :bind {"<B1-Motion>" {:handler drag! :value :pointer}}}]
```

`:context-menu` gives any tag a popup: its value is a menu's own `:items`
(a map with `:items` is a cascade, `:separator` a rule, anything else a
command whose `:on-click` fires through the trampoline). Tk has no widget
option for a popup, so the tag binds the platform's own secondary click —
Tk 9's `<<ContextMenu>>`, which Tk generates for the platform's gesture,
and where Tk does not have that event the classic `<Button-3>` — to Tk's
`tk_popup`, which posts the menu where the click was (root coordinates)
and unposts one that is already up, returning as soon as it has posted.
The menu is the widget's own child, created on first use and destroyed
with the widget; setting the prop to nil unbinds the event and destroys
the menu.

```clojure
[:tk/canvas {:items @items
             :context-menu [{:label "Copy" :on-click copy!}
                            :separator
                            {:label "Zoom" :items [{:label "2×" :on-click zoom!}]}]}]
```

A prop that is not in a tag spec's `:options`/`:bindings`/`:patchable`
sets declines the live patch, so the engine rebuilds the widget instead of
silently dropping the change.

### Options: the catalogue and its families

Which props a tag takes is its widget's Tk option set. `kmet.tk.options`
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
| `:imaged` | the classic widgets' image pair, `:image :bitmap`; the ttk widgets take `:image` alone, so their specs list it themselves |
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
(`:padding [6 12]`, `:tabs [40 80]`), the font props build `-font`
from the four of them, an image prop resolves its value to a Tk image
name, and a variable-backed prop to its variable's Tcl name (see *Shared
variables*). An empty string is `{}` — not nothing, which would
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
- **A widget class this Tk lacks fails with the tag.** A class newer than
  the interpreter (`ttk::toggleswitch` before Tk 9.0) fails inside Tcl at
  creation, before an option can be checked; `make-widget` rethrows it
  with the tag and the interpreter's patchlevel:
  `kmet.tk.core: :ttk/toggleswitch (ttk::toggleswitch) does not exist on
  this Tk (8.6.16)`.

Options that are not a plain value are deliberately not props, each
because it needs machinery a prop cannot be: `-tearoffcommand` (Tk calls
it when a tear-off menu is created, and kmet menus are tear-off off), and
the options that name another widget — `-menu` is a menubutton's `:items`
or a menu's `:menubar true`, `-labelwidget` a labelframe child's
`:labelwidget true`. The script-typed options
that are callbacks — `-validatecommand`, `-invalidcommand`,
`-xscrollcommand`, `-yscrollcommand`, `-postcommand` — are binding slots
of their tags (see the table above). Tk's `-browsecmd`/`-invokecommand`
are not options of any core Tk widget (they belong to BWidget/tklib), so
no tag declares them.

### Shared variables

The variable-backed options *are* props: `:variable` (a checkable widget's
state), `:textvariable` (a text field's value) and `:listvariable` (a
listbox's items) take a **kmet variable** — `kmet.tk.core/variable`, whose
Tcl name lives in a namespace of its own (`::kmet_var_<n>`, which the
app's Tcl should leave alone):

```clojure
(def draft (tk/variable ""))
[:ttk/entry {:textvariable draft
             :on-change (fn [t] (println t))}]
(tk/variable-watch! draft (fn [t] (reset! last-text t)))  ; fires on any write
```

A variable is created lazily — nothing touches Tcl until a widget names it
or it is read or written — and `variable-watch!` rides Tk's `trace add
variable`, so a write from any side (the user's typing, Tk's own toggle,
`variable-set!`) reaches the handler with the value Tcl wrote — including
the write a widget's render makes for its declared `:active`, so a watcher
can fire while that widget mounts. `variable-value`/`variable-set!` read
and write it through Tcl, and `variable-dispose!` unsets it and releases
its watches. The calls that
touch Tcl run on the Tk thread, like every Tcl call; `kmet.tk.tcl/install!`
registers the callback trampoline a trace needs, so a watched variable can
be materialized right after installing.

`:onvalue`, `:offvalue` and `:tristatevalue` are plain props beside a
shared `:variable`: `:active` writes the on/off value into the variable,
and `:on-toggled` compares the variable with the widget's own `-onvalue`
(a radiobutton's `-value`), so either side can move the state. A
radiobutton group sharing one `:variable` is Tk's own exclusive group.
A prop may also name the app's own Tcl variable (a string or keyword),
which kmet resolves as it is — quoting it wherever a script reads or
writes it — and never unsets; such a variable has no watch, since watches
belong to kmet variables. A variable belongs to the app's state, not to a
render: one created inside a component body is a new object every pass,
which rebuilds the widget each time (declare it outside, as state is).
Two props that would be a second source of truth are refused: `:text`
beside `:textvariable` on a text field, and a listbox's `:items` beside
its `:listvariable`.

A checkable widget without a shared `:variable` gets a private state
variable of the tag's own, which its disposal unsets; a shared one is the
app's to keep.

### Images

The image-backed options *are* props: `:image`, `:bitmap`, `:selectimage`,
`:tristateimage`, `:backgroundimage`, `:tile` on the widgets that have them,
and the canvas items' `:image`/`:bitmap`/`:disabledimage`. A value is
either a Tk image name — a string or keyword used as it is: a built-in
bitmap such as `:questhead`, or a name the app created in Tcl — or a
description map the widget then owns:

```clojure
[:tk/label {:image {:file "logo.png"}}]
[:tk/label {:image {:data png-base64 :format :png :fit {:width 320}}}]
[:tk/canvas {:items [{:type :image :coords [10 20] :image {:file "pic.png"}}]}]
[:tk/text {:content [{:text "before"} {:image {:file "pic.png"} :pady 4}]}]
```

| key | meaning |
|---|---|
| `:file` | read the image from a file |
| `:data` | base64 image data, with `:format` (`:png`, `:gif`, …) |
| `:zoom` | `n` or `[x y]`: integer upscale after loading |
| `:subsample` | `n` or `[x y]`: integer downscale after loading |
| `:fit` | `{:width px :height px}` (or a bare width): subsample so the image fits the box without distortion; never enlarges |

A `:bitmap` description creates an `image create bitmap`; every other image
prop a photo, and only a photo can be sized — `:zoom`/`:subsample`/`:fit`
(Tk bitmaps have no copy subcommand). Tk sizes a photo through a self-copy
(`copy … -zoom/-subsample -shrink`), which `:zoom`/`:subsample`/`:fit`
script; `:fit` measures the size the first two produced. A description is
created once per widget and description — a re-render reuses the name — and
`destroy!` deletes the images the widget created; a name the app owns is
never deleted. A `:text` widget's `:content` segments also take
`{:image description}` (with `:align`/`:padx`/`:pady`) to embed one.

An image prop that holds a name is set as an option like any other, so it
patches live; a changed description creates the new image and configures the
widget with its name.

### Styles and themes (`kmet.tk.ttk.style`)

`kmet.tk.ttk.style` writes ttk's style database as Clojure data. A ttk
*style* is a **name** — not a class, not a data structure of ours: a
widget's style is its class name (`TEntry`) unless its `:style` prop names
another, and a name inherits from itself without its first component
(`Kmet.TEntry` falls back to `TEntry` for layout and options). Under a name
Tk keeps static option values, per-state values (one state→value "map" per
option: `disabled`, `active`, `focus`, …), and a layout:

```clojure
(style/use-theme! "clam")            ; ttk::setTheme; a switch makes the
                                     ; new theme's database current
(style/define-style! "Kmet.TEntry"   ; ttk::style configure
                     {:fieldbackground "#1f2735"
                      :padding [6 2]})
(style/map-style! "Kmet.TEntry"      ; ttk::style map
                  {:fieldbackground {:disabled "#161a20" :focus "#20242c"}})
;; or the whole set in one call — theme first, then styles, then maps:
(style/apply! {:theme "clam"
               :styles {"TFrame" {:background "#10141b"}}
               :maps {"Kmet.TButton" {:background {:active "#2f3a4f"}}}})
```

Tk resolves an option as: the widget's own option, then the style's map
for the widget's current state, then its static value, then the inherited
style's value, then the theme default (`ttk::style lookup`). The database
is **per theme**: values set under one theme are hidden after a switch and
return when switching back, so `use-theme!` comes first and `apply!` is
what a theme change re-runs. Tk accepts unknown option names in
`configure`/`map` silently (only state names are checked), so a typo is
silent — `ttk::style configure STYLE` with no options dumps the names when
a check is wanted. Widget options still win over style values, so the
existing `:font-family`/`:font-size` props keep working.

Styles are defined on the Tk thread once the adapter has Tk
(`kmet.tk.tcl/install!`), and re-applied after a theme switch. kmet ships no
app palette set: an app compiles its own from the same `style-set` data (the
shipping app's startup and a runtime theme switch land with the GUI app port).

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
bold/italic picks a font description derived from Tk's `TkFixedFont`
(`:font-family`/`:font-size` override). `:scrollbar true` adds a wired
vertical `ttk::scrollbar`. A display
text is left `-state disabled` (selectable, not editable); with a wrapping
mode its `-height` follows the wrapped content when a line exceeds the
widget width. `:fill`/`:expand` control packing for any tag, not just
`:tk/text`.

### An editable text (`:editable true`)

The same tag is the backend's multi-line input — a chat composer is one:

```clojure
[:tk/text {:editable true :text @draft :height 3 :wrap :word
           :on-change (fn [text] (reset! draft text))
           :on-submit (fn [text] (send! text))}]
```

* `:text` is the widget's *value* here: it is read back (`get 1.0 end-1c`
  — Tk's own trailing newline is not part of it) and written only when it
  differs, so an app that mirrors `:on-change` into `:text` never moves the
  caret; a write resets the undo history, so `edit undo` covers the user's
  own edits and a cleared composer cannot be undone back.
* `:on-change` fires on `<KeyRelease>` — typing, a Ctrl+V paste and an
  `edit undo` all end in one — and hands the text over.
* `:on-submit` fires on `<Return>` and its binding ends in `break`, so
  Tk's class binding does not also insert a newline. Tk matches `<Return>`
  for `<Shift-Return>` too, so a Shift-Enter newline needs that chord
  bound as well — `:bind {"shift+enter" no-op}`, where the handler takes
  no arguments (`:bind` slots hand over no value) — which wins as the more
  specific pattern and lets Tk insert the line.
* `:content` (styled segments) is refused on an editable text — segments
  are a display path, and rendering them would disable the widget.
* An editable text keeps the `:height` it is given, where a display text
  follows its wrapped content: sizing a composer to what it holds is the
  app's.
* Tk's `-undo` is turned on; `:insert*`, `:spacing*`, `:tabs` and the rest
  of the text body are props already.

### Surfaces, borders and wrapping

`:tk/frame` takes `:border` (thickness) and `:border-color`, plus its
widget's Tk options (`:bg`, `:relief`, `:borderwidth`, the `-highlight*`
ring, `:padx`/`:pady`, `:width`/`:height`, …); its labeled sibling is
`:ttk/labelframe` (`ttk::labelframe`, taking `:text`). The border is drawn
with the highlight ring rather than `-relief solid`: the relief paints in
the widget's own background, which is invisible for a panel sitting on a
similar canvas.

Wrapping belongs to the component, not the widget: a card renderer wraps
its styled lines to the transcript width before they reach Tk (`:wrap
:word` on `:tk/text` is then only a safety net). Keep the surfaces
deliberately distinct — window, transcript canvas, message panel — so a
missing paint or an overflowing body is obvious at a glance.

### Dialogs

Tk's five native dialogs are `kmet.tk.core` functions: `message-box!`,
`open-file!`, `save-file!`, `choose-directory!` and `choose-color!`. Each
scripts Tk's own command (`tk_messageBox`, `tk_getOpenFile`, …) and
returns its answer as Clojure data:

```clojure
(tk/message-box! {:type :yes-no :icon :question :title "kmet"
                  :message "Save the session?" :detail "…" :default :yes})
;; => :yes / :no — nil when the window was closed without a button

(tk/open-file! {:title "Open" :initial-dir "/home/me"
                :file-types [["Session files" ["*.ednl" "*.jsonl"]]
                             ["All files" "*"]]})
;; => "/home/me/s.ednl"; a vector of paths with :multiple true; nil cancelled

(tk/save-file! {:default-extension ".ednl"})  ;; => "/home/me/s.ednl"
(tk/choose-directory! {:must-exist true})     ;; => "/home/me"
(tk/choose-color! {:initial-color "#ff5500"}) ;; => "#ff5500"
```

The props are the dialog's Tcl options, kebab-cased (`:initial-dir` is
`-initialdir`, `:must-exist` `-mustexist`). A dialog's vocabulary is
closed: an unknown prop is an error, as is a message box `:type`/`:icon`
outside its set, a `:default` button its `:type` does not show, or a
`:file-types` value that is not `[label extensions]` pairs (Tk would read
a malformed one as a filter with no extensions). `:type`
is `:ok` (default) or one of `:ok-cancel`, `:yes-no`, `:yes-no-cancel`,
`:retry-cancel`, `:abort-retry-ignore` (Tk spells them `okcancel` … — the
keywords are kmet's); `:file-types` is `[label extensions]` pairs, the
extensions one pattern or a vector.

A dialog blocks its caller — Tk runs a nested event loop, so the window
stays live but the calling frame waits — and must run on the thread that
owns the interpreter (`mount!`/`run-loop!`): call it from a callback. A
component body renders inside the pump, so a dialog there stalls the
render pass until the user answers.

### Interactive development

`run!` is `mount!` + `run-loop!`. The split exists for the REPL: mount on
the thread that will own the interpreter, park `run-loop!` there, edit a
component namespace, `:reload` it and call `kmet.tk.core/render!` (safe
from any thread — it only marks the tree dirty; all Tcl work stays on the
loop's thread). The backend map and the mounted structure are captured at
mount time, so a change inside `kmet.tk.core` itself needs a remount.

A mounted body is memoized by the engine, so state it depends on must be
declared: read it with `kmet.reakt.core/tracked-deref` (a plain `@atom`
read caches the tree and a click repaints nothing).

## Testing

`kmet.tk.test-tk` (bb, the JVM or Jolt) exercises the whole engine → backend →
script path against a recording adapter — no display needed — and
`kmet.gui-sample.test-ui` does the same for the `:ui/…` widget skeleton over
the sample UI backend, and `kmet.tk.test-ttk-style` records the `ttk::style`
scripts headlessly and round-trips them — `use-theme!`/`define-style!`/
`map-style!`/`apply!` — through a real Tk when a display is there (on Termux,
under jolt + Xvfb).
`kmet.tk.test-tcl` (bb, the JVM or Jolt, skipping where libtcl is absent — the
miss is memoized, so the skip is one probe per process, not one per test)
smoke-tests the real interpreter: eval, the command trampoline,
`tcl-quote` round-tripping through the Tcl parser, the recorded Tcl/Tk
pair with the interpreter's own patchlevel, the image layer — real
photos from data, sized and deleted with their widget — the dialogs,
answered by stand-in procs that check the emitted option words (a
`-filetypes` list above all) with no user involved, the editable
text, typed into with real key events: its value round-trips through
Tcl's parser, Return submits without a newline, and undo takes back the
typed key alone, a treeview's hierarchy, row tags and tree column
read back out of the widget, a canvas whose items a re-render moves in
place (the very item, by Tk's own numeric id), keeps its order by raising
them, and hands a click, a hover and a drag to the handlers its render
gave it,
a context menu posted at the click, a menu's check and radio items
invoked through Tk (the pointer is synthetic — `event generate` — so a
press picks the item under its point and the events that follow reach it;
motion with no press behind it only reaches an item when a real pointer
moves it, which is a manual check), a frame's gridded and placed children read back
with `winfo manager`, a shared variable's two-way binding through a real
entry (Tk writes the user's typing back, `variable-set!` reaches the
widget, and the trace fires for both), a radio group sharing one, a
validator refusing a key, a text's scroll command called with real
fractions, a canvas `:bind` keeping a drag's coordinates past the item's
edge, a posted menu's `-postcommand`, and a labelframe's label widget read
back with `cget -labelwidget`.

`scripts/bench/tk_option_audit.clj` (bb, needs Tcl/Tk and a display) enumerates
the interpreter's widget classes and their options and diffs them against
the tag table: the options a tag declares that its widget lacks, and the
options it does not reach yet. `kmet.tk.test-tcl`'s
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
families in `kmet.tk.options` (see *Options* above), and its script-typed
options are the tag's binding slots (validation, the scroll commands,
`-postcommand`); the cross-widget options are reached through the tag that
owns them (a menubutton's `:items`, a menu's `:menubar`, a labelframe
child's `:labelwidget`), and `scripts/bench/tk_option_audit.clj` reports what is
still missing against a live Tk. kmet's own widgets are the `:ui/…` set in
`kmet.gui-sample.ui` (today `:ui/transcript`, the chat scroll container).

The first cuts that remain — everything Tk offers beside its widgets
(window management, the clipboard) — are tracked
in [`tcl.md`](../../tcl.md) at the repository root, which lists
each gap with what closing it needs and how the two guards below keep the
list honest; the GUI app port's own work — the composer, the transcript's
rendering, the extension seam — is listed after them.
The `:tk/text` tag carries the displayed content — styled segments in
Tk tags, an optional scrollbar, generated mono fonts, wrapping — or, with
`:editable true`, the app's own multi-line input; `:ui/transcript`
(kmet.gui-sample.ui) is the scroll container over it: one text widget
whose children are embedded windows, with a sibling scrollbar and "scroll
to the end" on first map.

The sample ships no card renderer: a chat UI is app content and lives above
this module, so kmet's application UI is still written against `kmet.tui`
(line-oriented components) and is not ported to this backend yet. The next
steps are incremental transcript append (instead of re-rendering the whole
stack), keyed-list virtualization over `ttk::treeview`, and an extension-UI
bridge.
