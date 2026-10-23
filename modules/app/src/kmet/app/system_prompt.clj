(ns kmet.app.system-prompt
  "The `:systemPrompt` row: the prompt builder a loop driver hands its state as
   `:system-prompt-fn`, over the `:skills` registry.

   What a prompt is made of stays `kmet.agent.system-prompt`'s — the ordered
   sections, the context files, the tool snippets. This row fills the one input
   a service owns: the skills a run may be told about. Everything else is the
   caller's, because only the caller knows which agent it is building a prompt
   for; a tool table read here would be a process default, which is the thing
   the driver exists to replace.

   The row provides the builder itself rather than a record around it, so a
   driver reads one value and calls it — the same keyword-argument shape the
   pure builder takes, which is also the shape `:system-prompt-fn` promises."
  (:require [kmet.agent.registry :as registry]
            [kmet.agent.system-prompt :as system-prompt]
            [kmet.srce.core :as srce]))

(defn- registered-skills
  "SKILLS' effective skills in display order — one per name, from the owner
   that took it, so the prompt and a lookup name the same skill."
  [skills]
  (mapv second (registry/effective-entries skills :kmet/skill)))

(defn builder
  "A prompt builder over SKILLS (a `:skills` handle): `build-system-prompt`'s
   keyword options with `:skills` defaulting to the registered ones. A caller
   that names its own `:skills` keeps them — an agent-scoped list, or a test's
   empty one."
  [skills]
  (fn [& opts]
    (let [opts (apply hash-map opts)]
      (apply system-prompt/build-system-prompt
             (mapcat identity
                     (update opts :skills #(or % (registered-skills skills))))))))

(defn- serve
  "The `:systemPrompt` row: the builder over the injected `:skills` registry,
   provided into the mount site's scope so a sibling row (the loop driver) sees
   it while the entry stays this fiber's effect."
  [ctx _config]
  (srce/provide! ctx :system-prompt (builder (srce/get ctx :skills))
                 {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :system-prompt
   :inject [:skills]
   :provide #{:system-prompt}
   :apply serve})
