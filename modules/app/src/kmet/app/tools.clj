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

   A built-in tool joins the table as a **row** — `:tool/read`, `:tool/bash`, …,
   mounted by `:bundle/minimal` — and a row's `apply` is
   `kmet.app.tools.registry/mount-tool!`: the same handle method an extension
   uses, with the registration owned and labelled by the row. So this service
   holds no table of its own; what the composition mounted is what the model
   sees, and patching a tool row out is how a profile (or one agent's preset)
   runs without that tool.

   The tool *semantics* — registration, schema normalization, selection for a
   caller's surface — live beside it in `kmet.app.tools.registry`, which takes
   every reader a handle as its first argument (landing 4f). There is no
   process default behind it: a caller that needs a table holds the handle,
   which is what makes two agents' tables independent."
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
