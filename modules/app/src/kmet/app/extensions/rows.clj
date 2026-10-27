(ns kmet.app.extensions.rows
  "The `:ext/<id>` row source: name an installed extension from a composition,
   and let the row own its load.

   An extension is already a plugin (`kmet.extension.md`); a row does not change
   that. It adds a **name** a composition can address and the row's `:config`,
   which the host carries to the extension's api map as `(:config api)`. The
   artifact still loads through the host's isolated context and mounts under
   the host ctx; the row owns its lifetime, so disposing the row unloads the
   extension.

   Resolution reads the same installed set the world loader reads
   (`kmet.app.packages/resolve-configured-packages`): top-level settings paths,
   the auto dirs, packages and bundled artifacts. It loads nothing, so
   `--dump-config` can name an extension without running it. The id is the
   manifest `:name`, or a single file's basename without `.clj`; the first
   match in pi precedence wins, so a user's copy shadows a bundled one.

   A row must mount after the `:extensions` host row (the first row of
   `:bundle/minimal`): loading an extension needs the host's service binding."
  (:require [clojure.string :as str]
            [kmet.app.debug :as debug]
            [kmet.app.extensions.context :as context]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.srce.core :as srce]))

(def ^:private prefix ":ext/")

(defn source?
  "True when X is an `:ext/<id>` row source."
  [x]
  (and (keyword? x) (str/starts-with? (str x) prefix)))

(defn- id-of
  "The extension id SOURCE (`:ext/<id>`) names."
  [source]
  (subs (str source) (count prefix)))

(defn- entry-id
  "The id an installed ITEM answers to, from its resolved artifact: the
   bundled display name, the manifest `:name`, or a single file's stem."
  [item resolved]
  (or (get-in item [:metadata :display-name])
      (let [name (:name resolved)]
        (if (= :file (:kind resolved))
          (str/replace name #"\.clj$" "")
          name))))

(defn- resolve-item
  "The artifact ITEM names, as `resolve-extension`/`-descriptor` answers it."
  [item]
  (if-let [descriptor (get-in item [:metadata :artifact])]
    (context/resolve-extension-descriptor descriptor)
    (context/resolve-extension (:path item))))

(defn- resolve-id
  "The installed extension named ID, or nil. An artifact that cannot be
   resolved is skipped: naming another extension must not depend on it, and the
   world load reports it where it belongs."
  [id]
  (some (fn [item]
          (try
            (let [resolved (resolve-item item)]
              (when (= id (entry-id item resolved))
                {:id id :item item}))
            (catch Exception e
              (debug/log "extension row: cannot resolve " (:path item) ": "
                         (ex-message e))
              nil)))
        (:extensions (packages/resolve-configured-packages))))

(defn entry
  "The installed extension SOURCE names, or nil: `{:id :item}`."
  [source]
  (when (source? source)
    (resolve-id (id-of source))))

(defn- unknown!
  [source]
  (throw (ex-info (str "unknown extension " (pr-str source)
                       " — no installed extension has that name")
                  {:type :kmet.app/unknown-extension :extension source})))

(defn- entry!
  "ENTRY for SOURCE, or `:kmet.app/unknown-extension`."
  [source]
  (or (entry source) (unknown! source)))

(defn card
  "The catalog card for an `:ext/<id>` ROW. Throws
   `:kmet.app/unknown-extension` before anything boots, like an unknown plugin
   var."
  [row]
  (let [source (:plugin row)]
    (entry! source)
    (merge {:name source} row {:id (or (:id row) source)})))

(defn- load-entry!
  "Load ENTRY's artifact with CONFIG on the extension api; throw
   `:kmet.app/extension-load` when it fails or is skipped. Returns the
   extension record the row must unload."
  [entry config]
  (let [{:keys [id item]} entry
        descriptor (get-in item [:metadata :artifact])
        result (if descriptor
                 (extensions/load-extension-descriptor! descriptor config)
                 (extensions/load-extension! (:path item) config))]
    (when (or (:error result) (:skipped result))
      (throw (ex-info (str "extension " (pr-str id) " did not load: "
                           (or (:error result)
                               (str "skipped " (name (:reason result)))))
                      {:type :kmet.app/extension-load
                       :extension id
                       :result result})))
    (or (extensions/loaded-record (:extension result))
        (throw (ex-info (str "extension " (pr-str id) " loaded without a record")
                        {:type :kmet.app/extension-load
                         :extension id
                         :result result})))))

(defn plugin
  "The composition plugin for an `:ext/<id>` row: load the extension when the
   row mounts, and unload it when the row disposes. The row's `:config` reaches
   the extension as `(:config api)`. Throws `:kmet.app/unknown-extension`
   before the row is mounted."
  [source]
  (let [{:keys [id] :as entry} (entry! source)]
    {:name (keyword "ext-row" id) ; distinct from the extension's own plugin
     :apply (fn [ctx config]
              (let [ext (load-entry! entry config)]
                (srce/effect ctx
                             (fn [] (fn [] (extensions/unload-extension! ext)))
                             (str "unload extension " id))
                nil))}))
