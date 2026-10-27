(ns kmet.app.extensions.layers
  "The extension plugin rows of the two ambient composition layers.

   Extensions are plugins: a run loads the ones its composition names
   (`:ext/<id>` rows) and nothing else. The two files a user edits directly
   are the global `~/.kmet/agent/patch.edn` and the project `.kmet/patch.edn`
   (see `docs/profiles.md`); this namespace reads those layers, resolves the
   extension rows they compose, and writes the row or remove patch a
   `kmet config` toggle needs.

   Reading is data-only: a layer is an EDN map with `:rows` and `:patch`
   vectors, and nothing here loads an extension. A malformed file is reported
   as `:error` on that layer's record and composes as empty, so the config
   screen still opens (the run itself fails at compose with the usual
   `:kmet.app/bad-patch-file`). Writing refuses to touch a malformed file.

   The composition mirrors `kmet.app.profile/compose` for extension rows only:
   rows add, a patch with a `:plugin` adds or replaces by id, and a `:remove`
   patch drops the row with that id. Other rows and patches are ignored here;
   the run's own compose still sees them."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.rows :as rows]))

(defn- layer-error
  [path message]
  {:type :kmet.app/bad-patch-file :path path :message message})

(defn- read-layer
  "One layer file as `{:path PATH :data MAP}`; a missing file is empty, a
   malformed or non-layer file carries `:error` and empty `:data`."
  [path]
  (if-not (fs/exists? path)
    {:path path :data {}}
    (try
      (let [data (edn/read-string (slurp path))]
        (if (and (map? data)
                 (or (nil? (:rows data)) (vector? (:rows data)))
                 (or (nil? (:patch data)) (vector? (:patch data))))
          {:path path :data data}
          {:path path :data {}
           :error (layer-error path "a composition layer is a map with :rows and :patch vectors")}))
      (catch Exception e
        {:path path :data {}
         :error (layer-error path (or (ex-message e) (str e)))}))))

(defn layers
  "The ambient layers in application order, `{:global LAYER :project LAYER}`.
   Both are read even when missing; `kmet config` edits them by name."
  []
  {:global (read-layer (cfg/global-patch-path))
   :project (read-layer (cfg/project-patch-path))})

(defn- row-id
  "A row's identity: its `:id`, or its source when the row omits one (the
   catalog defaults the id to the card name, which for an extension row is
   the source)."
  [row]
  (or (:id row) (:plugin row)))

(defn- ext-row?
  [row]
  (rows/source? (:plugin row)))

(defn- apply-layer
  "Apply one layer's extension rows and patches to STATE
   (`{:rows {id source}}`). Last row with an id wins, as in the run's
   compose: a later insert with an existing id replaces it."
  [state layer]
  (let [state (reduce (fn [s row]
                        (if (ext-row? row)
                          (assoc-in s [:rows (row-id row)] (:plugin row))
                          s))
                      state
                      (:rows layer))]
    (reduce (fn [s patch]
              (cond
                (:remove patch) (update s :rows dissoc (:id patch))
                (ext-row? patch) (assoc-in s [:rows (row-id patch)] (:plugin patch))
                :else s))
            state
            (:patch layer))))

(defn- source-ids
  "The row ids STATE composes SOURCE with (a row or a patch insert)."
  [state source]
  (into #{} (keep (fn [[id src]] (when (= src source) id)) (:rows state))))

(defn- enablement-ids
  "The row ids ROWS or patch inserts compose SOURCE with, ignoring remove
   patches (what a toggle-on must clear)."
  [rows patches source]
  (into #{} (keep #(when (and (ext-row? %) (= source (:plugin %)))
                     (row-id %)))
        (concat rows patches)))

(defn composition
  "The extension rows the two layers compose:
   `{:global {:rows {id source}} :project {:rows {id source}}}`. The project
   state is the global state with the project layer applied on top."
  [{:keys [global project]}]
  (let [global-state (apply-layer {:rows {}} (:data global))]
    {:global global-state
     :project (apply-layer global-state (:data project))}))

(defn sources-of
  "The set of `:ext/<id>` sources STATE composes."
  [state]
  (set (vals (:rows state))))

(defn- source-id
  "The row id a state composes SOURCE with, or nil."
  [state source]
  (some (fn [[id src]] (when (= src source) id)) (:rows state)))

(defn item-state
  "The composition state of SOURCE in SCOPE (`:global` or `:project`):
   `{:enabled bool :override-state :inherit|:load|:unload}`. The project
   state is the difference from the global state, matching the tri-state
   rows the config screen already renders."
  [{:keys [global project]} source scope]
  (let [global? (contains? (sources-of global) source)
        project? (contains? (sources-of project) source)]
    (if (= scope :global)
      {:enabled global? :override-state :inherit}
      {:enabled project?
       :override-state (cond
                         (and project? (not global?)) :load
                         (and global? (not project?)) :unload
                         :else :inherit)})))

;; ─── Writing ─────────────────────────────────────────────────────────────

(defn- read-data!
  "The parsed layer DATA, or `:kmet.app/bad-patch-file` — a write must never
   clobber a file it cannot read."
  [path]
  (let [{:keys [data error]} (read-layer path)]
    (when error
      (throw (ex-info (str "cannot edit " path ": " (:message error))
                      (assoc error :type :kmet.app/bad-patch-file))))
    data))

(defn- write-layer!
  [path data]
  (when-let [dir (fs/parent path)]
    (fs/create-dirs dir))
  (spit path (with-out-str (pprint/pprint data))))

(defn- update-layer!
  "Read PATH, apply F to the layer map and write it back when it changed.
   Returns true when the file changed."
  [path f]
  (let [data (read-data! path)
        next (f data)]
    (when (not= data next)
      (write-layer! path next))
    (not= data next)))

(defn- without-source
  "ROWS/patches with every extension row for SOURCE and every patch that
   enables it removed. A config-only patch on a row that is not removed
   stays: it changes what the row mounts, not whether it mounts."
  [rows patches source]
  (let [removed (filterv #(and (ext-row? %) (= source (:plugin %))) rows)
        ids (set (map row-id removed))]
    {:rows (into [] (remove #(contains? ids (row-id %))) rows)
     :patch (into []
                  (remove (fn [patch]
                            (or (contains? ids (:id patch))
                                (and (ext-row? patch) (= source (:plugin patch))))))
                  patches)}))

(defn enable-global!
  "Compose SOURCE in the global layer: append its row unless the layer already
   composes it (as a row or a patch insert), and drop any remove patch that
   was carrying its disabled state (a toggle-off leaves the row, with its
   config, in place). Returns true when the file changed."
  [source]
  (update-layer!
   (cfg/global-patch-path)
   (fn [data]
     (let [rows (vec (or (:rows data) []))
           patches (vec (or (:patch data) []))
           ids (enablement-ids rows patches source)
           patch (into [] (remove #(and (:remove %)
                                        (or (= (:id %) source)
                                            (contains? ids (:id %)))))
                       patches)
           rows (if (seq ids) rows (conj rows {:id source :plugin source}))]
       (if (and (= rows (vec (or (:rows data) [])))
                (= patch patches))
         data
         (assoc data :rows rows :patch patch))))))

(defn disable-global!
  "Disable SOURCE in the global layer without losing its row: a `:remove`
   patch targets the row's id, so the row (and its config) survives and a
   later enable only drops the patch. Returns true when the file changed."
  [source]
  (update-layer!
   (cfg/global-patch-path)
   (fn [data]
     (let [rows (vec (or (:rows data) []))
           patches (vec (or (:patch data) []))
           ids (source-ids (apply-layer {} {:rows rows :patch patches}) source)
           removed? #(and (:remove %) (contains? ids (:id %)))
           already? (every? (fn [id] (some #(and (:remove %) (= (:id %) id)) patches))
                            ids)]
       (if (or (empty? ids) already?)
         data
         (assoc data :patch (into [] (concat (remove removed? patches)
                                             (map (fn [id] {:id id :remove true}) ids)))))))))

(defn apply-project-state!
  "Set SOURCE's project state (`:load`, `:unload` or `:inherit`) in the
   project layer. `:load` composes a row when the global layer does not
   already; `:unload` composes a remove patch for the global row; `:inherit`
   drops the enablement entry (a config-only patch on the row stays). Returns
   true when the file changed."
  [source state]
  (let [{:keys [global]} (composition (layers))
        global? (contains? (sources-of global) source)
        global-ids (into #{} (keep (fn [[id src]] (when (= src source) id))
                                   (:rows global)))]
    (update-layer!
     (cfg/project-patch-path)
     (fn [data]
       (let [rows (vec (or (:rows data) []))
             patches (vec (or (:patch data) []))
             {:keys [rows patch]} (without-source rows patches source)
             ;; a remove patch for a composed global row is this scope's
             ;; disable state; a :config patch on that row is not, so it stays
             patch (into [] (remove (fn [p]
                                      (and (:remove p)
                                           (contains? global-ids (:id p))))
                                    patch))
             patch (case state
                     :load (if global?
                             patch
                             (conj patch {:id source :plugin source}))
                     :unload (if (and global? (seq global-ids))
                               (conj patch {:id (source-id global source)
                                            :remove true})
                               patch)
                     patch)]
         (assoc data :rows rows :patch patch))))))
