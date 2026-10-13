# kmet GUI backends (`kmet.gui.*`)

Jolt-only GUI frontends for the toolkit-agnostic hiccup engine. The first
backend is **Tk** (Tcl/Tk 8.6+): `kmet.gui.tk` is "hiccup-tk" — the tag
table, widget records and Tcl script generation — and `kmet.gui.tcl` is
the `jolt.ffi` adapter that owns a real Tcl interpreter and the Tk event
loop.

## Where the layers sit

```
kmet.libs.reakt           reactions over plain atoms (host-agnostic)
kmet.libs.hikap           the hiccup engine: parse, keyed reconcile,
                          ComponentFn, refs — parameterized by a backend map
kmet.libs.hikap.component track! render cache, with-let stores, frame hook
kmet.libs.hikap.alias     the qualified-keyword alias registry
kmet.tui.hiccup           the TUI backend (tag table, *width*, IComponent)
kmet.gui.tk               the Tk backend (tag table, Tcl scripts, run! loop)
kmet.gui.tcl              the jolt.ffi Tcl/Tk adapter
```

The pre-split `kmet.tui.hiccup` was one namespace; it is now a facade over
the engine that keeps the public API (`root`, `ref`, `compile-tree`,
`render-lines`, `compute`, `dispose-tree!`, `counters`) unchanged for app
code and extensions.

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

Requirements: Jolt, plus Tcl/Tk installed (`apt install tcl tk`,
`brew install tcl-tk`, or MSYS2/Windows DLLs). There is no display support
on Termux without Termux:X11.

```sh
jolt scripts/gui_tk_demo.clj     # opens a small demo window
```

The adapter scripts the UI as Tcl (`ttk::label`, `ttk::entry`, `text`,
`ttk::treeview`, …), so adding widgets is a tag spec plus the Tcl command
— no per-widget C bindings. Callbacks run through one `kmet::cb`
trampoline (`kmet.gui.tk/dispatch-callback!`).

## Testing

`kmet.gui.test-tk` (bb or Jolt) exercises the whole engine → backend →
script path against a recording adapter — no display needed.
`kmet.gui.test-tcl` is Jolt-only and smoke-tests the real interpreter:
eval, the command trampoline, and `tcl-quote` round-tripping through the
Tcl parser.

## Status

v1 widget set: `:window :vbox :hbox :frame :label :button :entry
:checkbutton :separator :progressbar :text`, plus bare-string labels.
kmet's own application UI is still written against `kmet.tui`
(line-oriented components) and is not ported to this backend yet; the next
steps are a native transcript widget, keyed-list virtualization
(`ttk::treeview`/listbox), and an extension-UI bridge.
