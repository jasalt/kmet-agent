(ns kmet.agent.modes.interactive.resources
  "The loaded-resources sections shown by the interactive mode's
   LoadedResources component (pi: showLoadedResources)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.agent.config :as cfg]
            [kmet.agent.project-context :as context]
            [kmet.agent.skills :as skills]
            [kmet.agent.prompts :as prompts]
            [kmet.agent.extensions.core :as extensions]
            [kmet.libs.path :as path]))

;; ─── Loaded resources (pi: showLoadedResources) ────────────────────────────

(defn- extension-state
  "The lifecycle suffix of a loaded extension, or nil when it is simply loaded:
   a pending `:inject` mount names the services it waits for, a failed one
   says its activation threw."
  [ext]
  (case (:fiber-state ext)
    :pending (str "pending: " (str/join ", " (map str (sort (:waiting-for ext)))))
    :failed "failed"
    nil))

(defn- extension-line
  "One extension's line in the expanded list: its path, plus what a pending or
   failed mount is doing."
  [ext]
  (str "  " (path/home-relative (:path ext))
       (case (:fiber-state ext)
         :pending "  [pending]"
         :failed (str "  [failed: "
                      (or (some-> (:error ext) ex-message) "activation threw")
                      "]")
         "")))

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
                          :items (mapv (fn [e]
                                         (if-let [state (extension-state e)]
                                           (str (:name e) " (" state ")")
                                           (:name e)))
                                       extensions)
                          :expanded-items (mapv extension-line extensions)}))]
    sections))
