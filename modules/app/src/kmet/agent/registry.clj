(ns kmet.agent.registry
  "Name-addressed registrations behind one handle.

   Commands, prompt templates, skills and tools all register a value under a
   `[TAG name]` address, held by an owner, and answer the same questions: which
   value is `entry`'s for a name, what does the registry list (`entries`,
   `effective-entries`), what did one owner register (`by-owner`). Those rules
   live here once, over a **handle** — a value implementing `Registry` — so a
   backend cannot drift from them:

   - `atom-registry` — an atom of entries. What the app's `:tools` service
     installs: `register!` returns a disposer the caller ties to its fiber
     (`srce/effect`), so an extension's registrations unwind with it. This is
     the backend kmet2 uses.
   - `kmet.agent.registry-store/store-registry` — kmet1's scope-node store,
     until landing 4 deletes it (`new-app.md`).

   The rules are the same for both: an entry is addressed by its own name;
   owners are searched in display order, so the owner that took a name keeps
   it; `effective-entries` collapses the listing to one entry per name, so
   listing and lookup agree; a whole-registry clear removes by tag across every
   owner, because one owner holds several registries. The listing layout is the
   backend's (see `entries`).

   The registries themselves stay in their namespaces: they own the tag, the
   domain validation and the owner default. Only the mechanics live here.")

(defprotocol Registry
  "A tag/name/owner-addressed registration store. The five methods are the
   whole contract; `entry`, `entries` and `effective-entries` below are the
   shared readings over it, so every backend answers them identically."
  (register!
    [registry tag name value owner]
    "Install VALUE under NAME in TAG's registry for OWNER; re-registering the
     same (tag, name, owner) replaces. Returns a disposer that reports whether
     it removed the registration.")
  (deregister!
    [registry tag name owner]
    "Remove NAME's registration for TAG — only OWNER's with an OWNER, every
     owner's with nil. Returns nil.")
  (by-owner
    [registry tag name]
    "Every value registered under NAME in TAG's registry: {owner value}. The
     raw owner view a collision check needs; lookup goes through `entry`.")
  (entries
    [registry tag]
    "TAG's registrations in display order: `[[name value] …]`, a name held by
     several owners appearing once per owner — use `effective-entries` for the
     registry a lookup sees. The backend chooses the layout (kmet1's store
     lists owner-major, kmet2's atom interleaves owners in registration order),
     but the winning registration must come first for a name: the first
     occurrence is what `entry` returns.")
  (clear!
    [registry tag]
    "Remove every TAG registration, whichever owner holds it (mainly for
     /reload and tests). Returns nil."))

;; ─── Shared readings ────────────────────────────────────────────────────────

(defn entry
  "The value registered under NAME in TAG's registry, or nil. Owners are
   searched in display order, so the owner that took the name keeps it."
  [registry tag name]
  (second (first (filter #(= name (first %)) (entries registry tag)))))

(defn effective-entries
  "TAG's effective registrations in display order: one per name, from the
   owner that took it (`entry`'s rule), so listing and lookup agree."
  [registry tag]
  (second
   (reduce (fn [[seen acc] [name value]]
             (if (contains? seen name)
               [seen acc]
               [(conj seen name) (conj acc [name value])]))
           [#{} []]
           (entries registry tag))))

;; ─── The atom backend (kmet2) ──────────────────────────────────────────────

(defn atom-registry
  "A registry handle over an atom: the app's `:tools` service backend.

   An entry is plain data — `{:tag :name :owner :value}` — and registration
   order is display order, so `entries` lists what was installed in the order
   it was installed. A disposer removes exactly the entry it installed (a stale
   disposer cannot remove a newer registration), which is how an extension's
   tools unwind with its fiber."
  []
  (let [state (atom [])]
    (reify Registry
      (register! [_ tag name value owner]
        (let [entry {:tag tag :name name :owner owner :value value}]
          (swap! state
                 (fn [v]
                   (conj (into [] (remove #(and (= tag (:tag %))
                                                (= name (:name %))
                                                (= owner (:owner %))))
                               v)
                         entry)))
          (fn []
            (let [present? (boolean (some #(identical? % entry) @state))]
              (swap! state (fn [v] (into [] (remove #(identical? % entry)) v)))
              present?))))
      (deregister! [_ tag name owner]
        (swap! state
               (fn [v]
                 (into [] (remove #(and (= tag (:tag %))
                                        (= name (:name %))
                                        (or (nil? owner) (= owner (:owner %)))))
                       v)))
        nil)
      (by-owner [_ tag name]
        (into {}
              (keep (fn [e] (when (and (= tag (:tag e)) (= name (:name e)))
                              [(:owner e) (:value e)])))
              @state))
      (entries [_ tag]
        (into [] (keep (fn [e] (when (= tag (:tag e)) [(:name e) (:value e)]))) @state))
      (clear! [_ tag]
        (swap! state (fn [v] (into [] (remove #(= tag (:tag %))) v)))
        nil))))
