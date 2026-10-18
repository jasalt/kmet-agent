(ns kmet.cells.scope
  "The store behind a node: registrations keyed by host-shaped keys, owned by
   slot paths, with LIFO, isolated disposal (cells.md §5.1–§5.2).

   The store is an atom so `register!`/`dispose!` can be imperative while the
   node stays the value a handle receives. There is one registration per
   (owner, key) — re-registering replaces it, and a stale deregister fn from
   the replaced registration does nothing (the guard compares values, so an
   earlier registration of an *equal* value is indistinguishable and its fn
   does remove the newer one). Disposal runs an owner's cleanups in reverse
   registration order, then the owner's teardowns (a `:start` hook's
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

(defn- remove-entry
  "Remove OWNER's registration for k and its by-owner entry, whatever value it
   holds; a key with no owners left leaves the view."
  [s owner k]
  (-> s
      (update :by-key
              (fn [by-key]
                (let [owners (dissoc (get by-key k) owner)]
                  (if (empty? owners)
                    (dissoc by-key k)
                    (assoc by-key k owners)))))
      (update-in [:by-owner owner]
                 (fn [entries] (into [] (remove #(= k (:key %))) entries)))))

(defn- remember-owner
  ;; `(some #{owner} order)` would be wrong here: the set returns nil for a
  ;; nil owner (and false for false), so the owner would be appended every
  ;; time.
  [s owner]
  (update s :owner-order
          (fn [order] (if (some #(= owner %) order) order (conj order owner)))))

(defn- add-cleanup
  [m owner f]
  (update m owner (fnil conj []) f))

(defn node
  "A fresh node with an empty store. `mount!` (P1) is the public constructor;
   this exists so `register!`/`dispose!` are usable before the tree does. A
   node holds its parent link and live children (cells.md §5.6)."
  ([] (node nil))
  ([parent]
   {:store (atom (empty-store))
    :parent parent
    :children (atom [])}))

(defn deregister!
  "Drop OWNER's registration for K when its value is still V — a stale call
   cannot remove a newer registration. It is the targeted removal for a live
   owner, where `dispose-owner!` removes everything the owner registered, and
   the operation `register!` returns as a fn. Returns nil."
  [node owner k v]
  (swap! (:store node)
         (fn [s]
           (if (= v (get-in s [:by-key k owner]))
             (remove-entry s owner k)
             s)))
  nil)

(defn register!
  "Install v under the host-shaped key k for owner (a slot path). Returns a
   deregister fn, collected onto owner — the caller need not keep it."
  [node owner k v]
  (when (nil? v)
    (throw (ex-info "cannot register nil under a key"
                    {:type ::nil-registration :owner owner :key k})))
  (let [store (:store node)
        deregister (fn deregister [] (deregister! node owner k v))]
    (swap! store
           (fn [s]
             (-> (remove-entry s owner k)
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

(defn- owner-entries
  "OWNER's registrations in a store snapshot S: [[key value] …], in
   registration order (a replacement moves to the end)."
  [s owner]
  (into [] (map (juxt :key :value)) (get-in s [:by-owner owner])))

(defn owner-keys
  "The set of keys owner registered — what `composition`/`explain` report.
   A registry that lists itself in registration order wants
   `owner-registrations`."
  [node owner]
  (into #{} (map :key) (get-in @(:store node) [:by-owner owner])))

(defn owner-registrations
  "OWNER's registrations in registration order: [[key value] …]. Replacing a
   registration moves it to the end, as it does for the host's registry."
  [node owner]
  (owner-entries @(:store node) owner))

(defn owners
  "Every owner path on the store, in first-registration order — what
   `mount!`'s subtree disposal walks (cells.md §5.1)."
  [node]
  (:owner-order @(:store node)))

(defn registrations
  "The host-facing view: {key {owner value}}."
  [node]
  (:by-key @(:store node)))

(defn ordered-registrations
  "Every registration on the node as [key owner value]: owners in
   first-registration order, each owner's registrations in its own
   registration order — the order a host registry lists and reads in
   (cells.md §5.1). One store snapshot, so a registration made while it runs
   cannot split the answer; a single key's owners are `for-key`."
  [node]
  (let [s @(:store node)]
    (into []
          (mapcat (fn [owner]
                    (map (fn [[key value]] [key owner value])
                         (owner-entries s owner))))
          (:owner-order s))))

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
  "Dispose every owner on a store (reverse registration order, then the
   store itself). The public node teardown, which ends children first, is
   `kmet.cells.mount/dispose-node!` (cells.md §5.2–§5.3)."
  ([node]
   (let [owners (reverse (:owner-order @(:store node)))]
     (doseq [owner owners]
       (dispose-owner! node owner))
     (reset! (:store node) (empty-store))
     nil))
  ([node owner]
   (dispose-owner! node owner)))
