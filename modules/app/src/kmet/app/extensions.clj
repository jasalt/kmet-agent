(ns kmet.app.extensions
  "The extension host as a plugin: what a run's hooks resolve through.

   The host lives in `modules/app/src/kmet/agent/extensions` and may not
   require what owns its registries, so it takes them as a value
   (`kmet.agent.extensions.core/bind-services!`): a registry handle for its own
   tags — hooks, renderers, flags, the UI port — and the ctx an extension's
   `:inject` mounts under. This row binds both from the composition it runs in.
   It provides no service: the binding *is* the effect, and the handle becomes a
   service when something reads it (the modes' UI at landing 5), not before.

   The binding **overlays** what it replaces: this row's `:registry` and `:ctx`
   win, and every other key the previous owner bound carries over. That is what
   keeps a process host working — kmet1 binds the host at load with the resource
   registries its extension installers write to, and it boots this same
   composition as its root, so a row that replaced the whole binding would take
   commands, prompts and skills away from every extension loaded afterwards
   (`:kmet/no-resource-registries`, reported as a failed extension).

   A composition that inherits no resource registries has none: in a kmet2
   process installing an extension is still kmet1's, and those registries'
   loaders are policy the modes take with them (landing 5). A contribution with
   nowhere to go is refused by name instead of dropped.

   **Tool registrations are routed, not held.** A tool — or a run_code-only
   tool source — an extension registers is read through the composition's
   `:tools` service (the loop's tool table, the `/tools` selector, run_code's
   surface). The row's own handle would hold them where no reader looks, so the
   host's `:registry` is a tag router: `:kmet/tool` and `:kmet/tool-source` go
   to the composition's `:tools` service, every other host tag (hooks,
   renderers, flags, the UI port, themes) stays in this row's handle.

   The binding is process-wide and one owner at a time, so disposing this row
   puts back what it replaced — a second composition (or a test) cannot leave
   the host pointing at a dead root."
  (:require [kmet.agent.extensions.core :as extensions]
            [kmet.agent.registry :as registry]
            [kmet.app.registries :as registries]
            [kmet.srce.core :as srce]))

(defn- serve
  "Bind the host's services for this composition and publish its registry as
   the process handle: the registry routes every tag to the service that owns
   it, the resource operations are the app's own, and the ctx is the scope an
   extension's `:inject` mounts under. Returns the disposer that restores both
   (the previous binding, the previous publisher)."
  [ctx _config]
  (let [base (extensions/host-binding)
        scope (:parent ctx)
        handle (registries/process-handle scope)
        previous (registry/publish-process-handle! handle)]
    (extensions/bind-services!
     (fn [] (merge {:registry handle :ctx scope}
                   (registries/resource-services handle))))
    (fn []
      (registry/publish-process-handle! previous)
      (extensions/bind-services! base))))

(srce/defplugin plugin
  {:name :extensions
   :apply serve})
