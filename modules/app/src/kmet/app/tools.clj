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
   a caller's surface — stay in `kmet.agent.tools.registry` (rank 4): the app
   module holds the service and nothing above it knows which tools exist.
   Until the loop runs on the app (landing 7) the only reader is the contract
   test and the tool layer's explicit-argument reads."
  (:require [kmet.agent.registry :as registry]
            [kmet.srce.core :as srce]))

(defn service
  "A fresh `:tools` service value: an empty registry handle over an atom. Each
   row gets its own, so a disposed composition takes its tools with it."
  []
  (registry/atom-registry))

(defn- serve
  "The `:tools` row's apply: provide the handle into the app scope — the mount
   site's ctx — so the app's other rows see it, while the entry stays this
   fiber's effect."
  [ctx _config]
  (let [scope (:parent ctx)]
    (srce/provide! ctx :tools (service) {:in scope})))

(srce/defplugin plugin
  {:name :tools
   :provide #{:tools}
   :apply serve})
