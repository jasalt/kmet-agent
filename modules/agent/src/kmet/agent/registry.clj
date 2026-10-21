(ns kmet.agent.registry
  "Name-addressed registrations in the app root's store.

   Commands, prompt templates, skills and tools all put a value in the store
   under a `[TAG name]` key, owned by an owner path (scope.md §2). They share
   one set of rules, kept here so a registry cannot drift:

   - an entry is addressed by its own name: `(store-key :kmet/command \"help\")`
   - owners are searched in first-registration order, so the owner that took
     a name keeps it (`entry`)
   - listing is display order — owners in first-registration order, each
     owner's entries in its own registration order (`entries`), optionally
     collapsed to the effective set, one entry per name (`effective-entries`)
   - a whole-registry clear removes by key across every owner (`clear!`),
     because one owner may hold several registries.

   The registries themselves stay in their namespaces: they own the tag, the
   domain validation and the owner default. Only the store mechanics live
   here."
  (:require [kmet.agent.app-runtime :as app-runtime]
            [kmet.libs.scope :as scope]))

(defn store-key
  "The store key addressing NAME in a registry with TAG."
  [tag name]
  [tag name])

(defn key?
  "True for a key a TAG registry holds. Other registries share the store, so
   the shape is checked before the head."
  [tag k]
  (and (vector? k) (= tag (first k))))

(defn by-owner
  "Every value registered under NAME in a TAG registry: {owner value}. Raw
   owner access for a collision check; lookup goes through `entry`."
  [tag name]
  (scope/for-key (app-runtime/node) (store-key tag name)))

(defn entry
  "The value registered under NAME in a TAG registry, or nil. Owners are
   searched in first-registration order, so the owner that took the name
   keeps it."
  [tag name]
  (let [owners-by-path (by-owner tag name)]
    (some #(get owners-by-path %) (scope/owners (app-runtime/node)))))

(defn entries
  "A TAG registry's entries in display order: [[name value] …]. Owners are in
   first-registration order, each owner's entries in its own registration
   order. A name held by several owners appears once per owner — use
   `effective-entries` for the registry a lookup sees."
  [tag]
  (into []
        (keep (fn [[k _ v]] (when (key? tag k) [(second k) v])))
        (scope/ordered-registrations (app-runtime/node))))

(defn effective-entries
  "A TAG registry's effective entries in display order: one per name, from the
   owner that took it (`entry`'s rule), so listing and lookup agree."
  [tag]
  (second
   (reduce (fn [[seen acc] [name value]]
             (if (contains? seen name)
               [seen acc]
               [(conj seen name) (conj acc [name value])]))
           [#{} []]
           (entries tag))))

(defn clear!
  "Remove every TAG registration from NODE, whichever owner holds it (mainly
   for /reload and tests). Returns nil."
  [tag node]
  ;; by key, not by owner: an owner holds several registries (scope.md §2)
  (doseq [[k owner v] (scope/ordered-registrations node)
          :when (key? tag k)]
    (scope/deregister! node owner k v))
  nil)
