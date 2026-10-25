(ns kmet.app.world
  "The process world a run stands on: the provider catalogs and the models.edn
   layer over them, the config, the extension packages, the themes, and the
   extension CLI flags.

   An entry loads it **once**, after the composition that hosts the registries
   is mounted and before a mode mounts, so every profile has the extensions,
   themes and catalogs kmet1's `-main` gives every mode. The order inside is
   load-bearing: providers before config (so `--model` and settings.edn can
   select an extension-registered provider), extensions before themes (the
   packages resolve the theme paths), and the flags last (their registrations
   are the ones the parse collected)."
  (:require [kmet.agent.config :as cfg]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.packages :as packages]
            [kmet.ai.image-models :as image-models]
            [kmet.ai.models :as models]
            [kmet.app.root :as root]
            [kmet.tui.theme :as theme]))

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
   ;; Configuration + extensions load before model resolution (pi:
   ;; createAgentSessionRuntime registers extension providers, then the CLI model
   ;; resolves) — so --model/--provider and settings.edn can select
   ;; extension-registered providers, and print mode gets extension hooks.
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
