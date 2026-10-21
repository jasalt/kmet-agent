(ns kmet.app.catalog
  "The generated plugin catalog — the built-in `defplugin` sources, committed
   so a composition can be rendered without loading a plugin namespace.

   A profile row names a plugin **source** (a var, or an `:ext/…` id the
   extension host resolves). `--dump-config` has to print each row's card —
   its name, `:inject`, `:provide` — and the catalog is where that card comes
   from for a built-in: `bb generate-plugins` scans the module sources for
   `srce/defplugin` declarations and writes `kmet/app/plugin-catalog.edn`
   (kmet.tasks.plugins), and `kmet.tasks.test-plugins` keeps the committed file
   equal to a fresh scan.

   The catalog is **validation and docs, never a gate**: a source missing from
   it still composes — the row's card is then read from the loaded var
   (`defplugin` defines a valid plugin map, and dereferencing it mounts
   nothing). That is what lets an out-of-tree profile name its own plugin."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def resource
  "The committed catalog resource."
  "kmet/app/plugin-catalog.edn")

(def ^:private committed
  "The committed catalog, parsed once: the resource cannot change while the
   process runs, and every row's card reads it."
  (delay
    (if-let [r (io/resource resource)]
      (edn/read-string (slurp r))
      (throw (ex-info (str "missing generated plugin catalog " resource
                           " — run: bb generate-plugins")
                      {:type :kmet.app/missing-catalog})))))

(defn catalog
  "The committed catalog: `{:version 1 :plugins [{:var … :name … :inject …
   :provide … :config? …}]}`."
  []
  @committed)

(defn entries
  "The catalogued plugin entries."
  []
  (:plugins (catalog)))

(defn entry-for
  "The catalogued entry for SOURCE (a qualified var symbol), or nil."
  [source]
  (some (fn [entry] (when (= (:var entry) source) entry)) (entries)))

(defn- loaded-card
  "The card of a plugin var that is not in the catalog: its own map's metadata
   keys. Loading the namespace mounts nothing; a var that is not a plugin map
   (no keyword :name) is a miss, not a card."
  [source]
  (let [plugin (try
                 (let [v (requiring-resolve source)]
                   (when (var? v) @v))
                 (catch Throwable _ nil))]
    (when (and (map? plugin) (keyword? (:name plugin)))
      (select-keys plugin [:name :inject :provide]))))

(defn- card [source]
  (or (some-> (entry-for source)
              (select-keys [:name :inject :provide]))
      (loaded-card source)
      (throw (ex-info (str "unknown plugin source: " (pr-str source)
                           " — a built-in source is catalogued by"
                           " `bb generate-plugins`")
                      {:type :kmet.app/unknown-plugin :source source}))))

(defn- bad-row [row message]
  (throw (ex-info message {:type :kmet.app/bad-row :row row})))

(defn describe
  "The composition card for ROW: the row plus `:id`, `:name`, `:inject` and
   `:provide`. A var `:plugin` comes from the catalog (or the loaded var); an
   `:ext/…` keyword is external — the extension host supplies its card when it
   loads, so the dump names it and stops there. Any other keyword is a bad
   row: a built-in source is a var, never a registry key."
  [row]
  (let [source (:plugin row)]
    (cond
      (and (keyword? source) (str/starts-with? (str source) ":ext/"))
      (merge {:name source :external true} row {:id (or (:id row) source)})

      (keyword? source)
      (bad-row row (str "a keyword :plugin source is an :ext/… id the extension"
                        " host resolves; a built-in source is a var symbol — got "
                        (pr-str source)))

      (symbol? source)
      (let [c (card source)]
        (merge c row {:id (or (:id row) (:name c))}))

      :else
      (bad-row row (str "a row needs a :plugin source (a var symbol or an :ext/…"
                        " keyword), got " (pr-str row))))))

(defn- describe-patch [patch]
  (if (:plugin patch) (describe patch) patch))

(defn describe-layer
  "Describe a layer's rows, and its insert/replace patches (the ones carrying
   a `:plugin` source); config-only and remove patches pass through."
  [layer]
  (cond-> layer
    (:rows layer) (update :rows #(mapv describe %))
    (:patch layer) (update :patch #(mapv describe-patch %))))
