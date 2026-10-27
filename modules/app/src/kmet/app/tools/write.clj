(ns kmet.app.tools.write
  "Write tool implementation — create or overwrite files.
   Pi: write.ts — success message matches pi's wording."
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.path :as path]
            [kmet.srce.core :as srce]))

(defn title
  "Quiet one-liner body for the write tool: verb + `~`-shortened path — pure data, or nil when the path is missing/empty (the quiet branch falls back to the tool name). Nil-safe over partial streaming args."
  [args]
  (when-let [raw-path (tool-util/title-path-arg args)]
    (str "write " (path/home-relative raw-path))))

(defn execute
  "Write content to a file (create or overwrite)."
  [{:keys [path content]}]
  (try
    ;; relative paths resolve against the runtime cwd (pi: resolveToCwd), not
    ;; the process cwd — a session resumed from another project
    (let [f (io/file (tool-util/resolve-tool-path path))]
      ;; Pi: mkdir(dir, {recursive: true}) — skip when the path has no parent
      (when-let [parent (fs/parent f)]
        (fs/create-dirs parent))
      (spit f content)
      {:content (str "Successfully wrote " (count content) " bytes to " path)})
    (catch Exception e
      {:content (str "Error writing to " path ": " (ex-message e)) :is-error true})))

;; ─── The tool and its row ──────────────────────────────────────────────────

(def definition
  "The `write` tool's record — what the `:tool/write` row mounts."
  (tool/make-tool
   :name "write"
   :label "Write file"
   :description "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories."
   :prompt-snippet "Create or overwrite files"
   :prompt-guidelines ["Use write only for new files or complete rewrites."]
   :params {:path    {:type :string :description "File path to write to (relative or absolute)"}
            :content {:type :string :description "Content to write to the file"}}
   :execute execute
   :title title))

(srce/defplugin plugin
  {:name :tool/write
   :inject [:tools]
   :apply (fn [ctx config] (registry/mount-tool! ctx definition config))})
