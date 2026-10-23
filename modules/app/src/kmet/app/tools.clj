(ns kmet.app.tools
  "The `:tools` service — the app's tool registry, installed once and mutated
   through its handle.

   The service value is a `kmet.agent.registry/Registry` handle created by the
   row's `apply` and provided into the app scope (`{:in …}`, as the events row
   does), so the app's other rows see it while the entry stays this fiber's
   effect. **Installation is the `provide!`; every later change is a handle
   method** — `(registry/register! handle :kmet/tool name tool owner)` — so a
   plugin adding a tool never re-provides, and its registration is undone when
   the registering fiber is disposed (the disposer is the tool's removal).

   The tool *semantics* — the built-in map, schema normalization, selection for
   a caller's surface — live beside it in `kmet.agent.tools.registry`, which
   took every reader a handle as its first argument at landing 4f; the arity-less
   forms resolve through a default cell kmet1 installs, so a kmet2 reader that
   forgets the handle fails instead of reading another agent's tools. Until the
   loop runs on the app (landing 7) the only readers are the contract test and
   the tool layer's explicit-argument reads."
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
