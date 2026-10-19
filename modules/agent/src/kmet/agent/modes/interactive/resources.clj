(ns kmet.agent.modes.interactive.resources
  "The loaded-resources sections shown by the interactive mode's
   LoadedResources component (pi: showLoadedResources)."
  (:require [babashka.fs :as fs]
            [kmet.agent.config :as cfg]
            [kmet.agent.context :as context]
            [kmet.agent.skills :as skills]
            [kmet.agent.prompts :as prompts]
            [kmet.agent.extensions.core :as extensions]
            [kmet.libs.path :as path]))

;; ─── Loaded resources (pi: showLoadedResources) ────────────────────────────

(defn build-loaded-resource-sections
  "Pi: showLoadedResources — one section per loaded resource group (Context,
   Skills, Prompts, Extensions). Sections carry raw items; styling happens
   in the LoadedResources component render."
  []
  (let [context-files (context/load-project-context-files
                       (cfg/get-agent-dir) (str (fs/cwd)))
        skills (skills/get-skills)
        templates (prompts/get-prompt-templates)
        extensions (extensions/get-loaded-extensions)
        sections (cond-> []
                   (seq context-files)
                   (conj {:name "Context"
                          :items (mapv (comp path/home-relative :path) context-files)
                          :expanded-items (mapv #(str "  " (path/home-relative (:path %))) context-files)})
                   (seq skills)
                   (conj {:name "Skills"
                          :items (mapv :name skills)
                          :expanded-items (mapv (fn [s]
                                                  (str "  " (or (:file-path s) (:name s))))
                                                skills)})
                   (seq templates)
                   (conj {:name "Prompts"
                          :items (mapv #(str "/" (:name %)) templates)
                          :expanded-items (mapv (fn [t] (str "  /" (:name t))) templates)})
                   (seq extensions)
                   (conj {:name "Extensions"
                          :items (mapv :name extensions)
                          :expanded-items (mapv (fn [e]
                                                  (str "  " (path/home-relative (:path e))))
                                                extensions)}))]
    sections))
