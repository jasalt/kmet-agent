(ns kmet.app.extensions
  "The extension host as a plugin: what a run's hooks resolve through.

   The host lives in `modules/app/src/kmet/agent/extensions` and may not
   require what owns its registries, so it takes them as a value
   (`kmet.agent.extensions.core/bind-services!`): a registry handle for its own
   tags — hooks, renderers, flags, the UI port — and the ctx an extension's
   `:inject` mounts under. This row binds both from the composition it runs in,
   and provides the handle as `:extensions` so a consumer reads the same one.

   The host's *resource* registries (`:commands`, `:prompts`, `:skills`) stay
   unbound: installing an extension is still kmet1's, and those registries'
   loaders are policy the modes take with them (landing 5). A contribution with
   nowhere to go is refused by name (`kmet/no-resource-registries`) instead of
   dropped.

   The binding is process-wide and one owner at a time, so mounting this row
   replaces whatever was bound and disposing it puts that back — a second
   composition (or a test) cannot leave the host pointing at a dead root."
  (:require [kmet.agent.extensions.core :as extensions]
            [kmet.app.registries :as registries]
            [kmet.srce.core :as srce]))

(defn- serve
  "Bind the host's services for this composition. Returns the disposer that
   restores the binding it replaced."
  [ctx _config]
  (let [scope (:parent ctx)
        host-registry (registries/service)
        previous (extensions/bind-services! (fn [] {:registry host-registry
                                                    :ctx scope}))]
    (srce/provide! ctx :extensions host-registry {:in scope})
    (fn [] (extensions/bind-services! previous))))

(srce/defplugin plugin
  {:name :extensions
   :provide #{:extensions}
   :apply serve})
