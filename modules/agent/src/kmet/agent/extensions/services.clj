(ns kmet.agent.extensions.services
  "kmet1's binding for the extension host.

   The host lives in `app`, so it cannot require what owns its registries: the
   process's registry handle, the app root ctx its `:inject` mounts land under,
   and the commands/prompts/skills registries are all kmet1's, above it. It
   takes them as a value instead (`kmet.agent.extensions.core/bind-services!`)
   — this namespace builds this process's map, and a second owner binds its
   own the same way."
  (:require [kmet.agent.registry :as registry]))

(def ^:private resource-fns
  ;; Resolved on first use, not at load. The three registries require
  ;; kmet.agent.app-runtime, and that shim binds this map while its own
  ;; namespace loads: a static require here would close the cycle
  ;; (app-runtime → this → commands → app-runtime).
  (delay
    {:commands {:register! @(requiring-resolve 'kmet.agent.commands/register-command!)
                :unregister! @(requiring-resolve 'kmet.agent.commands/unregister-command!)
                :all @(requiring-resolve 'kmet.agent.commands/get-commands)}
     :prompts {:register! @(requiring-resolve 'kmet.agent.prompts/register-prompt-template!)
               :load-dir! @(requiring-resolve 'kmet.agent.prompts/load-prompt-templates-from-dir)}
     :skills {:register! @(requiring-resolve 'kmet.agent.skills/register-extension-skill!)
              :load-dir! @(requiring-resolve 'kmet.agent.skills/load-skills-from-dir)}}))

(def resource-tags
  "The registries an extension's contributed resources live in — what unloading
   it sweeps besides the host's own tags
   (`kmet.agent.extensions.core/host-tags`). Together the two must equal the
   tags kmet1 routes (`kmet.agent.app-runtime/registry-tags`); a test holds
   them to it."
  [:kmet/command :kmet/prompt :kmet/skill])

(defn host-services
  "The host's services for this process: HANDLE is kmet1's façade registry and
   CTX the app root's ctx. The resource operations delegate to the registries
   that own them, so the host never names the namespaces they live in; the
   owner sweep takes an unloaded extension's commands, prompts and skills with
   it."
  [handle ctx]
  (let [{:keys [commands prompts skills]} @resource-fns]
    {:registry handle
     :ctx ctx
     :commands commands
     :prompts prompts
     :skills skills
     :deregister-owner! (fn [owner]
                          (doseq [tag resource-tags]
                            (registry/deregister-owner! handle tag owner)))}))
