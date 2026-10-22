(ns kmet.app.catalog
  "Row cards — what a profile row's plugin declares, for composition and
   `--dump-config`.

   A profile row names a plugin **source** (a var, or an `:ext/…` id the
   extension host resolves). Describing a row resolves the source and reads the
   plugin map's own keys — name, `:inject`, `:provide` — so the card is the
   declaration itself: there is no second copy to drift, and no generated file
   in the path. `defplugin` defines a valid map and dereferencing it mounts
   nothing, so this is inspection, not boot.

   A source that does not resolve is a composition error (`:kmet.app/unknown-plugin`),
   reported by `--dump-config` as well as by boot: a profile naming a plugin
   that is not there is a typo, not a runtime condition. An `:ext/…` keyword is
   the exception — the extension host supplies its card when it loads, so the
   dump names it and stops there."
  (:require [clojure.string :as str]))

(defn- plugin-map
  "The plugin map SOURCE names, or nil when it does not resolve (or is not a
   plugin map: no keyword :name). Loading the namespace mounts nothing."
  [source]
  (let [plugin (try
                 (let [v (requiring-resolve source)]
                   (when (var? v) @v))
                 (catch Throwable _ nil))]
    (when (and (map? plugin) (keyword? (:name plugin)))
      plugin)))

(defn- card [source]
  (if-let [plugin (plugin-map source)]
    (select-keys plugin [:name :inject :provide])
    (throw (ex-info (str "unknown plugin source: " (pr-str source)
                         " — a row's :plugin must name a defplugin var")
                    {:type :kmet.app/unknown-plugin :source source}))))

(defn- bad-row [row message]
  (throw (ex-info message {:type :kmet.app/bad-row :row row})))

(defn describe
  "The composition card for ROW: the row plus `:id`, `:name`, `:inject` and
   `:provide`. A var `:plugin` is loaded and read; an `:ext/…` keyword is
   external — the extension host supplies its card when it loads, so the dump
   names it and stops there. Any other keyword is a bad row: a built-in source
   is a var, never a registry key."
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
