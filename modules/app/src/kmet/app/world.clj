(ns kmet.app.world
  "The process world a run stands on: the provider catalogs and the models.edn
   layer over them, the config, the extension packages, the themes, and the
   extension CLI flags.

   An entry loads it **once**, after the composition that hosts the registries
   is mounted and before a mode mounts, so every profile has the extensions,
   themes and catalogs kmet1's `-main` gives every mode. The order inside is
   load-bearing: the provider catalogs and models.edn come first, so the model
   patterns the run resolves afterwards see both the built-ins and the user's
   providers; extensions load before the themes (the packages resolve the theme
   paths); and the CLI flags are last, because their registrations are the ones
   the parse collected."
  (:require [kmet.app.config :as cfg]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.ai.image-models :as image-models]
            [kmet.ai.models :as models]
            [kmet.app.root :as root]
            [kmet.libs.theme :as theme]))

(defn load!
  "Load the process world and return the config.

   With CTX, it is published as the process root before the first extension
   registers: an entry that booted the composition itself hands it here, so
   the extension host's registrations land in the tree the run mounted rather
   than in a second one booted behind it. Without CTX — kmet1's entry, which
   has no composition yet — the host's bound services boot the root on their
   first read, so nothing here has to."
  ([opts] (load! opts nil))
  ([opts ctx]
   ;; Provider/model registry — loads the committed catalogs (pi registers its
   ;; generated providers at startup), then the models.edn user config layer
   ;; (custom providers + overrides; errors surface as warnings)
   (models/load-catalogs!)
   (image-models/load-image-catalogs!)
   (models/load-models-config!)
   (when-let [err (models/get-model-config-error)]
     (binding [*out* *err*]
       (println "Warning: models.edn:" err)))
   ;; Config before the packages (their settings decide what loads), and both
   ;; before the model resolution `run-mode` does afterwards.
   (let [config (cfg/init!)]
     (when ctx (root/publish! ctx))
     ;; One unified load per type (pi: resourceLoader — top-level entries, auto
     ;; dirs, then packages in precedence order)
     (packages/load-extensions!)
     (theme/load-theme-paths! (packages/resolve-theme-paths))
     ;; Extension CLI flags: the collected --flags become readable via
     ;; extensions/get-flag (pi: registerFlag + getFlag).
     (extensions/set-cli-flags! (:ext-flags opts))
     config)))
