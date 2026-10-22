(ns kmet.agent.registry-store
  "kmet1's registry backend: the app root's scope store (`scope.md` §2), behind
   `kmet.agent.registry/Registry` so commands, prompts, skills and tools read
   their registrations through the same handle kmet2's `:tools` service
   implements.

   This namespace is the migration shim and dies with the store at landing 4
   (`new-app.md`): it is the only place that knows a registration is a
   `[TAG name]` key in a node, and the only place the shared registry rules
   meet `kmet.libs.scope`. It lives in `modules/agent` because it belongs to
   kmet1 — the app module must not require the store."
  (:require [kmet.agent.registry :as registry]
            [kmet.libs.scope :as scope]))

(defn store-key
  "The address NAME occupies in a TAG registry: a `[TAG name]` key in the
   store. Store-specific — the handle's contract names tags and names, not
   keys."
  [tag name]
  [tag name])

(defn key?
  "True for a store key a TAG registry holds. Other registries share the store,
   so the shape is checked before the head."
  [tag k]
  (and (vector? k) (= tag (first k))))

(defn store-registry
  "The `Registry` handle over NODE's store: every registration addressed by
   `(store-key tag name)` and held by an owner path. Owners are
   searched in first-registration order, which is the display order the store
   already keeps."
  [node]
  (reify registry/Registry
    (register! [_ tag name value owner]
      (let [k (store-key tag name)
            dispose (scope/register! node owner k value)]
        ;; the store's own deregister is value-guarded: a disposer whose
        ;; registration was replaced must not report (or perform) a removal
        (fn []
          (if (some? (get (scope/for-key node k) owner))
            (do (dispose) true)
            false))))
    (deregister! [_ tag name owner]
      (scope/deregister-key! node (store-key tag name) owner)
      nil)
    (by-owner [_ tag name]
      (scope/for-key node (store-key tag name)))
    (entries [_ tag]
      (into []
            (keep (fn [[k _ v]] (when (key? tag k) [(second k) v])))
            (scope/ordered-registrations node)))
    (clear! [_ tag]
      ;; by key, not by owner: an owner holds several registries (scope.md §2)
      (doseq [[k owner v] (scope/ordered-registrations node)
              :when (key? tag k)]
        (scope/deregister! node owner k v))
      nil)))
