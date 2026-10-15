# kmet Tk backend (`kmet.tk.*`)

bb/Jolt GUI backend for the toolkit-agnostic hiccup engine: **Tk**
(Tcl/Tk **8.6 or newer**; 9.1 preferred). `kmet.tk.core` is "hiccup-tk"
— the backend itself plus the classic-widget tag set (`:tk/…`) —
`kmet.tk.ttk` is the themed-widget set (`:ttk/…`),
`kmet.tk.dsl` the widget machinery both are written against, and
`kmet.tk.tcl` the host FFI adapter (`babashka.ffi` under babashka,
`jolt.ffi` under Jolt) that owns a real Tcl interpreter and the Tk event
loop. kmet's own widget set (`:ui/…`) is app UI over the backend:
`kmet.app.ui.gui.ui`.

## Where the layers sit

```
kmet.libs.reakt           reactions over plain atoms (host-agnostic)
kmet.libs.hikap           the hiccup engine: parse, keyed reconcile,
                          ComponentFn, refs — parameterized by a backend map
kmet.libs.hikap.component track! render cache, with-let stores, frame hook
kmet.libs.hikap.alias     the qualified-keyword alias registry
kmet.tui.hikap            the TUI backend (tag table, *width*, IComponent)
kmet.tk.dsl               the Tk widget machinery: Tcl quoting and script
                          building, widget records, the callback/binding
                          registry, live-patch decisions, rich text
kmet.tk.options           the Tk option vocabulary: props, value shapes,
                          and the families a spec composes :options from
kmet.tk.core              the Tk backend + the classic widget set (:tk/…),
                          tag-table assembly, run-backend!/run! loop
kmet.tk.ttk               the themed widget set (:ttk/…)
kmet.tk.ttk.style         the ttk style database: ttk::style configure/map
                          and theme switching, as Clojure data
kmet.tk.tcl               the host FFI Tcl/Tk adapter (babashka.ffi / jolt.ffi)
```

The GUI tag namespaces mirror Tcl's own: classic widgets are `:tk/…`,
the themed ones `:ttk/…` (Tcl's global namespace vs `ttk::`). Those two
sets hold **only Tk widget classes**; kmet's own widgets and composites
are `:ui/…` tags in `kmet.app.ui.gui.ui` (app UI over this backend),
which mounts through the backend's extra-tag seam
(`kmet.tk.core/mount-backend!`, `run-backend!`). A declared host tag wins
over the alias registry, so `:tk/canvas` and `:ttk/frame` are host tags —
see kmet.libs.hikap's parse-host — while any other qualified keyword
still resolves as an alias; a `:ui/…` tag is unknown to the plain Tk
backend on purpose.

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
plus a pump on the UI thread. See `kmet.tk.core/run!`.

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
spinbox toggleswitch treeview`. `scripts/tk_option_audit.clj` reports
that coverage against a live Tk.

Three shapes of tag, on top of the plain leaves:

- **Text fields** (`:tk/entry`, `:tk/spinbox`, `:ttk/entry`,
  `:ttk/combobox`, `:ttk/spinbox`) take `:text` as a prop set through the
  widget's own subcommands — patchable, and never an option.
- **Data widgets** take their content as props rather than children:
  `:tk/listbox` `:items`/`:selected`, `:ttk/treeview` `:columns`/`:rows`/
  `:tags`/`:selected`, `:tk/canvas` `:items` (each a `{:type :line
  :coords [x y …]}` map of Tk's own canvas-item options, plus `:id`,
  `:tags` and `:on-click`),
  `:tk/menu`/`:tk/menubutton`/`:ttk/menubutton` `:items`,
  `:ttk/combobox`/`:ttk/spinbox` `:values`.
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

The `:ttk/treeview` tag is both a flat table and a tree. A row is a vector
of cells or a map of Tk's own item options — `:text` (the tree column),
`:values` (the data columns), `:tags`, `:open`, `:image` — plus `:id` and
`:children`, which nest under the row's id; ids default to `r1, r2, …` at
the top level and to `<parent>.1, …` below it, so a row can be addressed
without being named. Tk's `delete` is recursive, so a `:rows` change
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
plus `:id`, `:tags` and `:on-click`. Tk gives an item a numeric id of its
own but lets a tag stand for it, so an item's `:id` (`i1`, `i2`, … when the
render names none) is written as its first Tk tag, and every item command
addresses it by that. A re-render *reconciles*: an id it still has keeps
its item — the coordinates through Tk's `coords` subcommand, the changed
options through `itemconfigure`, and its `:on-click` binding (Tk's item
bind, `<Button-1>` on the item's own tag) — a new id is drawn, a gone id
deleted with the callback it held, and an item whose type or option set
changed is rebuilt, since Tk cannot unset an option. A click hands the
handler the click's coordinates and reaches the *topmost* item under the
pointer, Tk's own rule: overlapping items shadow each other's handlers.

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

### kmet's own widgets (`kmet.app.ui.gui.ui`)

The two tag sets above are Tk widget classes only. A kmet widget — a
composite that scripts Tk itself but reuses widget behavior — is a
`:ui/…` tag in the app layer's `kmet.app.ui.gui.ui`, completed by the
same `dsl/widget-specs`. `kmet.app.ui.gui.ui/backend` is the Tk backend
with that tag table merged in, and `kmet.app.ui.gui.ui/mount!`/`run!`
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
| an item's `:on-click` | `:tk/canvas` `:items` (Tk's item bind) | the click's coordinates, `[x y]` |
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
from the four of them, and an image prop resolves its value to a Tk image
name — a name passes through, a description is created and owned by the
widget (see *Images*). An empty string is `{}` — not nothing, which would
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
because it needs machinery a prop cannot be: script-typed (`-command` is
`:on-click`/`:on-toggled`/`:on-scroll`, `-validatecommand`,
`-xscrollcommand`), the variable-backed `-variable`/`-textvariable`/
`-listvariable` — a checkable widget's state is the tag's own private
variable, so `-onvalue`/`-offvalue`/`-tristatevalue` are not props either —
and the options that name another widget, `-menu` and `-labelwidget`.

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
(`kmet.tk.tcl/install!`), and re-applied after a theme switch.
`kmet.app.ui.gui.style` is the app's set: `style-set` compiles the TUI
theme's colors and `kmet.app.ui.gui.transcript`'s surfaces to the values
above (frames and labels on the window surface, fields on the card
surface, notebook, treeview, scrollbars, and the active/pressed/disabled
maps), and `apply-theme!` applies it. `scripts/gui_tk_session.clj` calls
`(gui-style/apply-theme! (:theme ctx))` right after `tcl/install!`; the
app's own startup and a runtime theme switch land with the GUI app port.

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
them. `:scrollbar true` adds a wired vertical `ttk::scrollbar`. A display
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

Wrapping belongs to the component, not the widget: the transcript wraps
its styled lines to the transcript width before they reach Tk (`:wrap
:word` on `:tk/text` is then only a safety net). See
`kmet.app.ui.gui.transcript/palette` for the three deliberately distinct
surfaces — window, transcript canvas, message panel — so a missing paint
or an overflowing body is obvious at a glance.

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
declared: read it with `kmet.libs.reakt/tracked-deref` (a plain `@atom`
read caches the tree and a click repaints nothing).

## Testing

`kmet.tk.test-tk` (bb or Jolt) exercises the whole engine → backend →
script path against a recording adapter — no display needed — and
`kmet.app.ui.gui.test-ui` does the same for the `:ui/…` widgets over the
UI backend, and `kmet.tk.test-ttk-style` records the `ttk::style`
scripts
headlessly and round-trips them — `use-theme!`/`define-style!`/
`map-style!`/`apply!`, and the app palette set (`kmet.app.ui.gui.style`) —
through a real Tk when a display is there (on Termux, under jolt + Xvfb).
`kmet.tk.test-tcl` (bb or Jolt, skipping where libtcl is absent)
smoke-tests the real interpreter: eval, the command trampoline,
`tcl-quote` round-tripping through the Tcl parser, the recorded Tcl/Tk
pair with the interpreter's own patchlevel, the image layer — real
photos from data, sized and deleted with their widget — the dialogs,
answered by stand-in procs that check the emitted option words (a
`-filetypes` list above all) with no user involved, the editable
text, typed into with real key events: its value round-trips through
Tcl's parser, Return submits without a newline, and undo takes back the
typed key alone, and a treeview's hierarchy, row tags and tree column
read back out of the widget, and a canvas whose items a re-render moves in
place (the very item, by Tk's own numeric id) and clicks to its handler.

`scripts/tk_option_audit.clj` (bb, needs Tcl/Tk and a display) enumerates
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
families in `kmet.tk.options` (see *Options* above); the options Tk
gives to no plain prop — script- and variable-backed — are the
ones still missing, and `scripts/tk_option_audit.clj` reports both sides
against a live Tk. kmet's own widgets are the `:ui/…` set in
`kmet.app.ui.gui.ui` (today `:ui/transcript`, the chat scroll container).

The first cuts that remain — checkable or imaged menu items, a right-click
popup, canvas item events beyond a click, and everything Tk offers beside
its widgets (`grid`/`place`, window management, the clipboard, variables) —
are tracked in [`tcl.md`](../../tcl.md) at the repository root, which lists
each gap with what closing it needs and how the two guards below keep the
list honest; the GUI app port's own work — the composer, the transcript's
rendering, the extension seam — is listed after them.
The `:tk/text` tag carries the displayed content — styled segments in
Tk tags, an optional scrollbar, generated mono fonts, wrapping — or, with
`:editable true`, the app's own multi-line input; `:ui/transcript`
(kmet.app.ui.gui.ui) is the scroll container over it: one text widget
whose children are embedded windows (one widget per message) with a
sibling scrollbar and "scroll to the end" on first map.

`kmet.app.ui.gui.transcript` is the chat UI over it: one bordered,
collapsible card per session message type (user, assistant, tool, custom),
reusing the TUI markdown renderer and palette, and
`scripts/gui_tk_session.clj` opens a saved session in it (display only).
kmet's own application UI is still written against `kmet.tui` (line-oriented
components) and is not ported to this backend yet; the next steps are
incremental transcript append (instead of re-rendering the whole stack),
keyed-list virtualization over `ttk::treeview`, and an extension-UI bridge.
