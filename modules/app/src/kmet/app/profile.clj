(ns kmet.app.profile
  "Profiles are composition data: ordered plugin rows plus patches, layered.

   A **bundle** is a reusable row list — the analog of dsh's `dsh.bundle` —
   and a **profile** is the app entry: it stacks bundles in order, then adds
   its own rows and patches (`new-app.md` § Profiles and presets). A row names
   a plugin **source** (`:plugin`), never a keyword registry entry:
   `{:id :tool/bash :plugin kmet.app.tools.bash/plugin :config {}}`. `:id`
   is the row identity a patch targets and `--dump-config` prints; it defaults
   to the plugin's `:name`, which the catalog resolves before composition.

   A patch targets one row by `:id`:

     {:id :permissions :config {:policy :allow-all}}   ; deep-merge :config
     {:id :permissions :remove true}                   ; drop the row
     {:id :tool/ls :plugin … :after :tool/read}         ; insert (unknown id)

   Config *values* deep-merge; composition does not — a bundle's rows are
   appended as they are, and later layers patch by id. A patch that names an
   unknown id without a `:plugin` is an error, not a silent no-op.

   This namespace is data only: it never loads a plugin and never reads a
   process value. `kmet.app.catalog` turns rows into cards, `kmet.app.core`
   prints the composed result."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def resource
  "The committed built-in composition table (bundles + profiles)."
  "kmet/app/profiles.edn")

(def default-profile
  "The profile a bare run composes."
  :tui)

(def patch-keys
  "The keys a patch entry may carry. `:name :inject :provide :external` are
   the card keys an insert/replace patch gains when `kmet.app.catalog`
   describes it; the rest are the patch itself."
  #{:id :plugin :config :remove :after :before
    :name :inject :provide :external})

(defn builtin
  "The built-in composition table: `{:bundles {name bundle} :profiles {name
   profile}}`. A missing resource is a packaging bug, not an empty table."
  []
  (if-let [r (io/resource resource)]
    (edn/read-string (slurp r))
    (throw (ex-info (str "missing built-in profile resource " resource)
                    {:type :kmet.app/missing-profiles}))))

(defn profile
  "The named PROFILE from TABLE; throws :kmet.app/unknown-profile."
  [table profile-name]
  (or (get (:profiles table) profile-name)
      (throw (ex-info (str "unknown profile: " (pr-str profile-name))
                      {:type :kmet.app/unknown-profile
                       :profile profile-name
                       :known (vec (sort (keys (:profiles table))))}))))

(defn- bundle-entry
  "Check one `:bundles` entry — `[:bundle/name layer?]` — and return its
   `[name layer]` pair."
  [entry]
  (when-not (and (vector? entry)
                 (<= 1 (count entry) 2)
                 (keyword? (first entry))
                 (or (nil? (second entry)) (map? (second entry))))
    (throw (ex-info (str "a :bundles entry is [:bundle/name layer?]: " (pr-str entry))
                    {:type :kmet.app/bad-bundle-entry :entry entry})))
  [(first entry) (second entry)])

(defn- bundle [table bundle-name]
  (or (get (:bundles table) bundle-name)
      (throw (ex-info (str "unknown bundle: " (pr-str bundle-name))
                      {:type :kmet.app/unknown-bundle
                       :bundle bundle-name
                       :known (vec (sort (keys (:bundles table))))}))))

(defn layers
  "PROFILE-NAME's layers in application order: each bundle (followed by its
   per-stack extra layer, the `[:bundle/x {:rows … :patch …}]` second
   element), then the profile's own rows and patches."
  [table profile-name]
  (let [p (profile table profile-name)]
    (into []
          (concat
           (mapcat (fn [entry]
                     (let [[bundle-name extra] (bundle-entry entry)]
                       (cond-> [(bundle table bundle-name)]
                         (seq extra) (conj extra))))
                   (:bundles p))
           [(select-keys p [:rows :patch])]))))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn- row-index [rows id]
  (first (keep-indexed (fn [i row] (when (= (:id row) id) i)) rows)))

(defn- unknown-row [id patch]
  (throw (ex-info (str "patch targets an unknown row: " (pr-str id))
                  {:type :kmet.app/unknown-row :id id :patch patch})))

(defn- check-patch!
  "Throw :kmet.app/bad-patch unless PATCH is a well-formed patch entry."
  [patch]
  (when-not (map? patch)
    (throw (ex-info (str "a patch entry must be a map, got " (pr-str patch))
                    {:type :kmet.app/bad-patch :patch patch})))
  (when-not (contains? patch :id)
    (throw (ex-info (str "a patch entry needs an :id, got " (pr-str patch))
                    {:type :kmet.app/bad-patch :patch patch})))
  (when-let [bad (seq (remove patch-keys (keys patch)))]
    (throw (ex-info (str "unknown patch keys " (vec bad) " in " (pr-str patch))
                    {:type :kmet.app/bad-patch :patch patch :keys (vec bad)})))
  (when-not (or (contains? patch :config) (contains? patch :plugin) (:remove patch))
    (throw (ex-info (str "a patch entry changes nothing: " (pr-str patch))
                    {:type :kmet.app/bad-patch :patch patch})))
  (when (and (:remove patch) (contains? patch :config))
    (throw (ex-info (str "a :remove patch cannot also set :config: " (pr-str patch))
                    {:type :kmet.app/bad-patch :patch patch})))
  (when (and (:remove patch) (contains? patch :plugin))
    (throw (ex-info (str "a :remove patch cannot also set :plugin: " (pr-str patch))
                    {:type :kmet.app/bad-patch :patch patch})))
  (when (and (:after patch) (:before patch))
    (throw (ex-info (str "a patch insert takes :after or :before, not both: " (pr-str patch))
                    {:type :kmet.app/bad-patch :patch patch})))
  patch)

(defn- merge-config [row patch]
  (if-let [cfg (:config patch)]
    (update row :config #(deep-merge (or % {}) cfg))
    row))

(defn- patched-row
  "The row a source/config patch produces: its source and card replaced (an
   insert/replace patch arrives already described), its config deep-merged."
  [row patch]
  (merge-config (merge row (select-keys patch [:plugin :name :inject :provide :external]))
                patch))

(defn- insert-row
  "Insert PATCH (a row with an explicit or defaulted :id) among ROWS,
   positioned by :after/:before or appended."
  [rows patch]
  (let [new-row (dissoc patch :after :before)
        anchor (or (:after patch) (:before patch))
        at (if anchor
             (if-let [i (row-index rows anchor)]
               (if (:before patch) i (inc i))
               (unknown-row anchor patch))
             (count rows))]
    (vec (concat (subvec rows 0 at) [new-row] (subvec rows at)))))

(defn- apply-patch [rows patch]
  (let [patch (check-patch! patch)
        id (:id patch)
        idx (row-index rows id)]
    (cond
      (:remove patch) (if idx
                        (vec (concat (subvec rows 0 idx) (subvec rows (inc idx))))
                        (unknown-row id patch))
      idx (do (when (or (:after patch) (:before patch))
                (throw (ex-info (str "a patch on an existing row takes no"
                                     " :after/:before: " (pr-str patch))
                                {:type :kmet.app/bad-patch :patch patch})))
              (assoc rows idx (patched-row (nth rows idx) patch)))
      (contains? patch :plugin) (insert-row rows patch)
      :else (unknown-row id patch))))

(defn compose
  "Apply LAYERS in order to an empty composition → `{:rows [row …] :patches
   n}`. Every row must carry an `:id` (a card's, resolved by the catalog);
   duplicate ids across stacked bundles are an error."
  [layers]
  (let [{:keys [rows patches]}
        (reduce (fn [state layer]
                  {:rows (reduce apply-patch
                                 (into (:rows state) (:rows layer))
                                 (:patch layer))
                   :patches (+ (:patches state) (count (:patch layer)))})
                {:rows [] :patches 0}
                layers)
        missing (seq (remove :id rows))
        dupes (->> rows (map :id) frequencies (filter (fn [[_ n]] (> n 1))) (map key) sort)]
    (when missing
      (throw (ex-info "composed rows must carry an :id"
                      {:type :kmet.app/row-without-id :rows (vec missing)})))
    (when (seq dupes)
      (throw (ex-info (str "duplicate row ids: " (pr-str (vec dupes)))
                      {:type :kmet.app/duplicate-row :ids (vec dupes)})))
    {:rows rows :patches patches}))

(defn- pad [s width] (str s (apply str (repeat (max 0 (- width (count s))) \space))))

(defn- render-row
  "One row line: id and source aligned, then the card (name when it differs,
   inject, provide, config) or `external` for a source the host resolves."
  [id-w src-w {:keys [id plugin name inject provide config external]}]
  (let [head (str (pad (pr-str id) id-w) "  " (pad (pr-str plugin) src-w) "  ")]
    (if external
      (str head "external")
      (str head
           (when (not= name id) (str "name=" (pr-str name) " "))
           "inject=" (pr-str (into (sorted-map) (or inject {})))
           " provide=" (pr-str (vec (sort-by str (or provide []))))
           " config=" (pr-str (or config {}))))))

(defn render
  "The `--dump-config` text for a composed row list: one line per row, ids and
   sources aligned, the card's inject/provide/config after them."
  [profile-name {:keys [rows patches]}]
  (let [id-w (reduce max 0 (map (comp count pr-str :id) rows))
        src-w (reduce max 0 (map (comp count pr-str :plugin) rows))]
    (str (str/join \newline
                   (cons (str "profile " (pr-str profile-name)
                              " rows=" (count rows) " patches=" patches)
                         (map (partial render-row id-w src-w) rows)))
         \newline)))
