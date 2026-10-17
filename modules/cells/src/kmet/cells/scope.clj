(ns kmet.cells.scope
  "The store behind a node: registrations keyed by host-shaped keys, owned by
   slot paths, with LIFO, isolated disposal (cells.md §5.1–§5.2).

   The store is an atom so `register!`/`dispose!` can be imperative while the
   node stays the value a handle receives. There is one registration per
   (owner, key) — re-registering replaces it, and a stale deregister fn from
   the replaced registration is a no-op. Disposal runs an owner's cleanups in
   reverse registration order, then the owner's teardowns (a `:start` hook's
   returned fn, which unwinds after everything the hook registered). One
   failing cleanup never skips the rest; it is reported through
   `*error-reporter*`."
  (:require [clojure.string :as str]))

(def ^:dynamic *error-reporter*
  "Called with the throwable a failing cleanup threw. The host may rebind it
   (`kmet.agent.runtime` binds it to `kmet.agent.debug/log-error`); the
   default prints to stderr, because the module is rank 0 and cannot require
   `kmet.*` (cells.md §9)."
  (fn [t]
    (binding [*out* *err*]
      (println (str "kmet.cells: cleanup failed: " (str/trim (str (ex-message t))))))))

(defn- empty-store []
  {:by-key {}        ; {key {owner value}} — the host-facing view
   :by-owner {}      ; {owner [{:key k :value v :deregister f}]} in LIFO order
   :teardowns {}     ; {owner [f]} — run after the owner's registrations
   :owner-order []}) ; first-registration order, for whole-node disposal

(defn- drop-entry
  "Drop owner's registration for k when its value is still v — a stale
   deregister fn must not remove a newer registration."
  [s owner k v]
  (if (not= v (get-in s [:by-key k owner]))
    s
    (-> s
        (update :by-key
                (fn [by-key]
                  (let [owners (dissoc (get by-key k) owner)]
                    (if (empty? owners)
                      (dissoc by-key k)
                      (assoc by-key k owners)))))
        (update-in [:by-owner owner]
                   (fn [entries] (into [] (remove #(= k (:key %))) entries))))))

(defn- remember-owner
  [s owner]
  (update s :owner-order
          (fn [order] (if (some #{owner} order) order (conj order owner)))))

(defn- add-cleanup
  [m owner f]
  (update m owner (fnil conj []) f))

(defn node
  "A fresh node with an empty store. `mount!` (P1) is the public constructor;
   this exists so `register!`/`dispose!` are usable before the tree does."
  []
  {:store (atom (empty-store)) :parent nil :children []})

(defn register!
  "Install v under the host-shaped key k for owner (a slot path). Returns a
   deregister fn, collected onto owner — the caller need not keep it."
  [node owner k v]
  (when (nil? v)
    (throw (ex-info "cannot register nil under a key"
                    {:type ::nil-registration :owner owner :key k})))
  (let [store (:store node)
        deregister (fn deregister [] (swap! store drop-entry owner k v))]
    (swap! store
           (fn [s]
             (-> (drop-entry s owner k v)
                 (assoc-in [:by-key k owner] v)
                 (update :by-owner add-cleanup owner {:key k :value v :deregister deregister})
                 (remember-owner owner))))
    deregister))

(defn add-teardown!
  "Collect f on owner; disposal runs teardowns after the owner's
   registrations, LIFO. Where a `:start` hook's returned fn lands (cells.md §3)."
  [node owner f]
  (swap! (:store node)
         (fn [s] (-> (update s :teardowns add-cleanup owner f)
                     (remember-owner owner))))
  f)

(defn owner-keys
  "The set of keys owner registered — what `composition`/`explain` report."
  [node owner]
  (into #{} (map :key) (get-in @(:store node) [:by-owner owner])))

(defn registrations
  "The host-facing view: {key {owner value}}."
  [node]
  (:by-key @(:store node)))

(defn for-key
  "Every value registered under k: {owner value}."
  [node k]
  (get (registrations node) k {}))

(defn- run-cleanups!
  [cleanups]
  (doseq [f cleanups]
    (try
      (f)
      (catch Throwable t
        (*error-reporter* t)))))

(defn- scrub-owner
  "Remove owner from the host-facing view; a key with no owners is dropped."
  [s owner]
  (update s :by-key
          (fn [by-key]
            (reduce-kv (fn [acc k owners]
                         (let [owners (dissoc owners owner)]
                           (if (empty? owners)
                             (dissoc acc k)
                             (assoc acc k owners))))
                       {}
                       by-key))))

(defn dispose-owner!
  "Dispose owner's registrations (LIFO), then its teardowns (LIFO). A throwing
   cleanup is reported and never skips the rest. Idempotent."
  [node owner]
  (let [store (:store node)
        {:keys [by-owner teardowns]} @store]
    (run-cleanups! (map :deregister (rseq (vec (get by-owner owner)))))
    (run-cleanups! (reverse (get teardowns owner)))
    (swap! store
           (fn [s]
             (-> s
                 (update :by-owner dissoc owner)
                 (update :teardowns dissoc owner)
                 (update :owner-order (fn [order] (into [] (remove #{owner}) order)))
                 (scrub-owner owner))))
    nil))

(defn dispose!
  "Dispose a node (every owner in reverse registration order, then the store)
   or one owner. LIFO, isolated and reported; never waits (cells.md §5.2–§5.3)."
  ([node]
   (let [owners (reverse (:owner-order @(:store node)))]
     (doseq [owner owners]
       (dispose-owner! node owner))
     (reset! (:store node) (empty-store))
     nil))
  ([node owner]
   (dispose-owner! node owner)))
