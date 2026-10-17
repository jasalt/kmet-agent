(ns kmet.cells.spec
  "Descriptors, entries, resolution and the two checks (cells.md §3–§4, §6).

   A descriptor is plain data: a unit with `:handle`, `:units`, or both; its
   optional `:config` is a malli schema. An entry is a manifest record
   `{:id :name :config :disabled :into}` that asks for a unit in a slot; its
   `:config` is a value validated against the resolved unit's schema.

   `check` is pure: it resolves names without loading anything, validates
   configs, and returns [] or a vector of `{:path :entry :key :error} maps`.
   It builds the mount plan and discards it; `mount!` (P1) re-plans rather
   than accepting a plan (cells.md §6.3)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(defn runtime
  "Validate the host map `{:resolve [[kind fn] …]}` and freeze it. `rt` is a
   value: several may coexist — a test's, a sandbox's (cells.md §2.1)."
  [host]
  (let [resolve (:resolve host)]
    (when-not (and (vector? resolve)
                   (seq resolve)
                   (every? #(and (vector? %) (= 2 (count %))
                                 (keyword? (first %)) (fn? (second %)))
                           resolve))
      (throw (ex-info "runtime requires :resolve as a non-empty vector of [kind fn] pairs"
                      {:type ::invalid-runtime :host host})))
    {:resolve (vec resolve)}))

(defn- err
  [path entry key message]
  {:path path :entry entry :key key :error message})

(defn resolve-name
  "Resolve nm through rt's backends in order; the first hit wins. Returns
   `{:unit descriptor :backend kind}`, or `{:error message}` — a miss names
   every backend tried, a throwing backend its exception (cells.md §6.1/§6.4)."
  [rt nm]
  (loop [backends (seq (:resolve rt)) tried []]
    (if-let [[kind f] (first backends)]
      (let [outcome (try
                      {:unit (f nm)}
                      (catch Throwable t
                        {:error (str "backend " kind " failed to resolve " nm
                                     ": " (ex-message t))}))]
        (cond
          (:error outcome) outcome
          (:unit outcome) (assoc outcome :backend kind)
          :else (recur (next backends) (conj tried kind))))
      {:error (str "no backend resolved " nm " (tried " (str/join ", " tried) ")")})))

(defn- descriptor-errors
  "Check 1's shape rule over a unit and, recursively, its inline children."
  [id path unit]
  (cond
    (not (map? unit))
    [(err path id :descriptor "unit must be a map")]

    (and (contains? unit :units) (not (map? (:units unit))))
    [(err path id :units "unit :units must be a map of slot -> unit")]

    (not (or (:handle unit) (contains? unit :units)))
    [(err path id :descriptor "unit must declare :handle or :units")]

    :else
    (into [] (mapcat (fn [[slot child]] (descriptor-errors id (conj path slot) child)))
          (:units unit))))

(defn- config-errors
  "Check 2: an entry's :config against the unit's malli schema. A unit without
   a schema accepts any config; an entry without one is not an error."
  [id path unit value]
  (if-not (contains? unit :config)
    []
    (try
      (if (m/validate (:config unit) value)
        []
        [(err path id :config
              (str "does not match the unit's :config schema: "
                   (pr-str (me/humanize (m/explain (:config unit) value)))))])
      (catch Throwable t
        [(err path id :config (str "unit :config is not a usable malli schema: " (ex-message t)))]))))

(defn- composition-path
  "The path from :app to composition id through :into links; `::missing` when
   a parent is unknown, `::cycle` when the chain revisits an id."
  [id parent-of]
  (loop [x id acc [] seen #{}]
    (cond
      (= :app x) (into [:app] acc)
      (contains? seen x) ::cycle
      :else (if-let [p (get parent-of x)]
              (recur p (cons x acc) (conj seen x))
              ::missing))))

(defn- entry-path
  [into id parent-of]
  (let [path (composition-path into parent-of)]
    (conj (if (vector? path) path [:app]) id)))

(defn- into-errors
  "The :into tree errors for one entry (cells.md §4): an unknown composition,
   one that is not a group, a cycle, or a broken parent chain."
  [id path into declared-ids group-ids parent-of]
  (let [chain (composition-path into parent-of)]
    (cond
      (= :app into)
      []

      (not (contains? declared-ids into))
      [(err path id :into (str "no composition " into " (disabled or undeclared)"))]

      (not (contains? group-ids into))
      [(err path id :into
            (str "entry " into " is not a composition (its unit declares no :units)"))]

      (= ::cycle chain)
      [(err path id :into (str "composition cycle through " into))]

      (= ::missing chain)
      [(err path id :into
            (str "composition " into " is not reachable (its :into chain is broken)"))]

      :else
      [])))

(defn- composition?
  "True when the candidate's resolved unit owns slots."
  [candidate]
  (contains? (:unit candidate) :units))

(defn- admit
  "Admit candidates in order: the first slot per (composition, id) wins, and so
   does the first claim on a composition id. Later candidates become
   collisions, `{:candidate c :key :id :message string}`."
  [candidates]
  (reduce
   (fn [acc c]
     (let [slot [(:into c) (:id c)]
           message (cond
                     (and (composition? c) (contains? (:seen-comp acc) (:id c)))
                     (str "composition id " (:id c) " must be unique")

                     (contains? (:seen-slot acc) slot)
                     (str "duplicate entry id " (:id c) " in composition " (:into c)))]
       (if message
         (update acc :collisions conj {:candidate c :key :id :message message})
         (let [acc (-> acc
                       (update :kept conj c)
                       (update :seen-slot conj slot))]
           (if (composition? c)
             (update acc :seen-comp conj (:id c))
             acc)))))
   {:kept [] :seen-slot #{} :seen-comp #{} :collisions []}
   candidates))

(defn plan
  "Build the mount plan from entries:
   `{:compositions {comp-id [{:id :entry :unit} …]} :errors [err …]}`.
   Compositions group resolved entries under their target composition (:app or
   a group entry's id); inline `:units` defaults are merged by `mount!` (P1).
   Slot ids are unique within a composition; composition ids globally."
  [rt entries]
  (let [entries (vec entries)
        active (into [] (remove #(and (map? %) (true? (:disabled %)))) entries)
        malformed (into [] (remove map?) active)
        missing-id (into [] (filter #(and (map? %) (nil? (:id %)))) active)
        reserved (into [] (filter #(and (map? %) (= :app (:id %)))) active)
        usable (into [] (filter #(and (map? %) (some? (:id %)) (not= :app (:id %)))) active)
        candidates (mapv (fn [e]
                           (let [resolution (when (contains? e :name)
                                              (resolve-name rt (:name e)))]
                             {:entry e
                              :id (:id e)
                              :into (or (:into e) :app)
                              :resolution resolution
                              :unit (:unit resolution)}))
                         usable)
        {:keys [kept collisions]} (admit candidates)
        parent-of (into {} (keep #(when (composition? %) [(:id %) (:into %)])) kept)
        group-ids (into #{} (keep #(when (composition? %) (:id %))) kept)
        declared-ids (into #{} (map :id) candidates)
        structural (into []
                         (concat
                          (map (fn [_] (err [:app] nil :entry "entry must be a map")) malformed)
                          (map (fn [_] (err [:app] nil :id "entry has no :id")) missing-id)
                          (map (fn [_] (err [:app :app] :app :id ":app is the root composition")) reserved)
                          (map (fn [{:keys [candidate key message]}]
                                 (err (entry-path (:into candidate) (:id candidate) parent-of)
                                      (:id candidate) key message))
                               collisions)))
        detailed (into []
                       (mapcat
                        (fn [{:keys [entry id into resolution unit]}]
                          (let [path (entry-path into id parent-of)]
                            (concat
                             (when-not (contains? entry :name)
                               [(err path id :name "entry has no :name")])
                             (when-let [message (:error resolution)]
                               [(err path id :name message)])
                             (when unit
                               (descriptor-errors id path unit))
                             (when (and unit (contains? entry :config))
                               (config-errors id path unit (:config entry)))
                             (into-errors id path into declared-ids group-ids parent-of))))
                        kept))
        compositions (reduce (fn [acc {:keys [entry id into unit]}]
                               (if unit
                                 (update acc into (fnil conj [])
                                         {:id id :entry entry :unit unit})
                                 acc))
                             {} kept)]
    {:compositions compositions
     :errors (into structural detailed)}))

(defn check
  "The §4 checks over entries: [] when they would mount, else a vector of
   `{:path :entry :key :error}` maps. Pure — no scopes, no code, no effects."
  [rt entries]
  (:errors (plan rt entries)))
