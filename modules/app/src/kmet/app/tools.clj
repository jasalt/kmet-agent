(ns kmet.app.tools
  "The `:tools` service — the app's tool registry, installed once and mutated
   through its handle.

   The service value is a `kmet.app.registry/Registry` handle created by the
   row's `apply` and provided into the app scope (`{:in …}`, as the events row
   does), so the app's other rows see it while the entry stays this fiber's
   effect. **Installation is the `provide!`; every later change is a handle
   method** — `(registry/register! handle :kmet/tool name tool owner)` — so a
   plugin adding a tool never re-provides, and its registration is undone when
   the registering fiber is disposed (the disposer is the tool's removal).

   The tool *semantics* — the built-in map, schema normalization, selection for
   a caller's surface — live beside it in `kmet.app.tools.registry`, which
   takes every reader a handle as its first argument (landing 4f): the built-in
   table is built per handle, so two agents' tool tables share no state. There
   is no process default behind it: a caller that needs a table holds the handle,
   which is what makes the two agents' tables independent."
  (:require [kmet.app.registries :as registries]))

(def service
  "A fresh `:tools` service value: an empty registry handle over an atom. Each
   row gets its own, so a disposed composition takes its tools with it."
  registries/service)

(def plugin
  "The `:tools` row. The shape is `kmet.app.registries`' (a handle provided into
   the app scope, one declaration per service); this var keeps the row's name
   where its history and its tests point."
  registries/tools)
