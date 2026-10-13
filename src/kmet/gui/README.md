# kmet GUI backends (`kmet.gui.*`)

bb/Jolt GUI frontends for the toolkit-agnostic hiccup engine. The first
backend is **Tk** (Tcl/Tk 9.1 preferred, 9.0 and 8.6 accepted): `kmet.gui.tk`
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

Requirements: babashka or Jolt, plus Tcl/Tk installed — 9.1 is preferred,
and the adapter warns on stderr and falls back through the 9.0 and 8.6
sonames when it is absent (`apt install tcl tk`, `brew install tcl-tk`, or
MSYS2/Windows DLLs). Tk is a Tcl package: the adapter loads libtcl for the
interpreter, then libtk and loads it through Tcl, so one Tcl/Tk
installation supplies both libraries (the Tk version must match Tcl's);
the Tk load prefers the ABI the interpreter loaded. The Tk DLL is resolved
on PATH and the zipfs library its build appended to the DLL is mounted
before the load, so the Windows DLLs are standalone — `tcl91.dll`,
`tcl9tk91.dll` and their two dependencies can be dropped next to the
executable or anywhere on PATH, with no installed Tcl/Tk and no compiled-in
prefix. There is no display support on Termux without Termux:X11.

```sh
bb scripts/gui_tk_demo.clj       # opens a small demo window
jolt scripts/gui_tk_demo.clj     # the same on Jolt
```

The adapter scripts the UI as Tcl (`ttk::label`, `ttk::entry`, `text`,
`ttk::treeview`, …), so adding widgets is a tag spec plus the Tcl command
— no per-widget C bindings. Callbacks run through one `kmet::cb`
trampoline (`kmet.gui.tk/dispatch-callback!`).

## Testing

`kmet.gui.test-tk` (bb or Jolt) exercises the whole engine → backend →
script path against a recording adapter — no display needed.
`kmet.gui.test-tcl` (bb or Jolt, skipping where libtcl is absent)
smoke-tests the real interpreter: eval, the command trampoline, and
`tcl-quote` round-tripping through the Tcl parser.

## Status

v1 widget set: `:window :vbox :hbox :frame :label :button :entry
:checkbutton :separator :progressbar :text`, plus bare-string labels.
kmet's own application UI is still written against `kmet.tui`
(line-oriented components) and is not ported to this backend yet; the next
steps are a native transcript widget, keyed-list virtualization
(`ttk::treeview`/listbox), and an extension-UI bridge.
