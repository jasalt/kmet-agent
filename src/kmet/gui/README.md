# kmet GUI backends (`kmet.gui.*`)

bb/Jolt GUI frontends for the toolkit-agnostic hiccup engine. The first
backend is **Tk** (Tcl/Tk **9.1 only**): `kmet.gui.tk`
is "hiccup-tk" — the tag table, widget records and Tcl script generation —
and `kmet.gui.tcl` is the host FFI adapter (`babashka.ffi` under babashka,
`jolt.ffi` under Jolt) that owns a real Tcl interpreter and the Tk event
loop.

## Where the layers sit

```
kmet.libs.reakt           reactions over plain atoms (host-agnostic)
kmet.libs.hikap           the hiccup engine: parse, keyed reconcile,
                          ComponentFn, refs — parameterized by a backend map
kmet.libs.hikap.component track! render cache, with-let stores, frame hook
kmet.libs.hikap.alias     the qualified-keyword alias registry
kmet.tui.hikap            the TUI backend (tag table, *width*, IComponent)
kmet.gui.tk               the Tk backend (tag table, Tcl scripts, run! loop)
kmet.gui.tcl              the host FFI Tcl/Tk adapter (babashka.ffi / jolt.ffi)
```

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

Requirements: babashka or Jolt, plus Tcl/Tk **9.1** (`apt install tcl tk`,
`brew install tcl-tk`, or the MSYS2/Windows DLLs); the adapter throws with
the names it tried when libtcl 9.1 or libtk 9.1 is missing. Tk is a Tcl
package: the adapter loads libtcl for the interpreter, then libtk and
loads it through Tcl, so one Tcl/Tk installation supplies both libraries.
The Tk DLL is resolved
on PATH and the zipfs library its build appended to the DLL is mounted
before the load, so the Windows DLLs are standalone — `tcl91.dll`,
`tcl9tk91.dll` and their two dependencies can be dropped next to the
executable or anywhere on PATH, with no installed Tcl/Tk and no compiled-in
prefix. There is no display support on Termux without Termux:X11.

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

### Rich text (`:text`)

`:text` takes a plain `:text` string or a `:content` vector of styled
segments:

```clojure
[:text {:scrollbar true :fill :both :expand true
        :wrap "word" :font-size 12 :bg "#1f2735" :fg "#d7dbe0"
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
width. `:fill`/`:expand` control packing for any tag, not just `:text`.

### Surfaces, borders and wrapping

`:frame` takes `:border` (thickness), `:border-color`, `:bg`, `:padx`,
`:pady`. The border is drawn with the highlight ring rather than
`-relief solid`: the relief paints in the widget's own background, which
is invisible for a panel sitting on a similar canvas.

Wrapping belongs to the component, not the widget: the transcript wraps
its styled lines to the transcript width before they reach Tk (`:wrap
word` on `:text` is then only a safety net). See
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
script path against a recording adapter — no display needed.
`kmet.gui.test-tcl` (bb or Jolt, skipping where libtcl is absent)
smoke-tests the real interpreter: eval, the command trampoline, and
`tcl-quote` round-tripping through the Tcl parser.

## Status

v1 widget set: `:window :vbox :hbox :frame :label :button :entry
:checkbutton :separator :progressbar :text :transcript`, plus bare-string
labels. The `:text` tag carries the displayed content — styled segments in
Tk tags, an optional scrollbar, generated mono fonts, wrapping.
`:transcript` is the scroll container: one text widget whose children are
embedded windows (one widget per message) with a sibling scrollbar and
"scroll to the end" on first map. Text scrolling has no canvas coordinate
ceiling (a canvas hits Tk's 16-bit limit around 32k pixels, which a long
transcript reaches quickly), which is why the container is a text widget.

`kmet.app.ui.gui.transcript` is the chat UI over it: one bordered,
collapsible card per session message type (user, assistant, tool, custom),
reusing the TUI markdown renderer and palette, and
`scripts/gui_tk_session.clj` opens a saved session in it (display only).
kmet's own application UI is still written against `kmet.tui` (line-oriented
components) and is not ported to this backend yet; the next steps are
incremental transcript append (instead of re-rendering the whole stack),
keyed-list virtualization (`ttk::treeview`), and an extension-UI bridge.
