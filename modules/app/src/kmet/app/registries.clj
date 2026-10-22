(ns kmet.app.registries
  "The registry services — `:commands`, `:prompts`, `:skills`, `:tools` — as
   one row shape.

   Each registry is a `kmet.agent.registry/Registry` handle over an atom,
   installed by one `provide!` into the app scope, and mutated through that
   handle afterwards (`kmet.app.tools`' rule: installation is the provide, and a
   plugin adding a command never re-provides). They stay **separate services**
   rather than one `:registries` key because their consumers differ — the model
   sees `:tools`, the slash parser `:commands`, the prompt builder `:prompts` —
   and because a per-agent override should be per-seam ([`new-app.md`]
   § Service catalog, decision 11).

   The rows are one declaration each and one implementation: a handle has no
   knowledge of its tag, so the difference between them is the name and the key,
   and writing four copies of the same twenty lines would be the thing
   AGENTS.md forbids."
  (:require [kmet.agent.registry :as registry]
            [kmet.srce.core :as srce]))

(defn service
  "A fresh registry handle: an atom, empty, owned by whatever provides it."
  []
  (registry/atom-registry))

(defn apply-row
  "The row's apply for the service NAME: build the handle and provide it into
   the mount site's scope, so the app's other rows see it while the entry stays
   this fiber's effect."
  [name]
  (fn [ctx _config]
    (srce/provide! ctx name (service) {:in (:parent ctx)})))

(srce/defplugin tools
  {:name :tools
   :provide #{:tools}
   :apply (apply-row :tools)})

(srce/defplugin commands
  {:name :commands
   :provide #{:commands}
   :apply (apply-row :commands)})

(srce/defplugin prompts
  {:name :prompts
   :provide #{:prompts}
   :apply (apply-row :prompts)})

(srce/defplugin skills
  {:name :skills
   :provide #{:skills}
   :apply (apply-row :skills)})
