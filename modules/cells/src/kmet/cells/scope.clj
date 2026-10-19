(ns kmet.cells.scope
  "The store behind a node: registrations keyed by host-shaped keys, owned by
   owner paths, with LIFO, isolated disposal (cells.md §2–§3).

   The store is an atom so `register!`/`dispose-owner!` can be imperative while
   the node stays the value callers pass around. There is one registration per
   (owner, key) — re-registering replaces it, and a stale deregister fn from
   the replaced registration does nothing (the guard compares values, so an
   earlier registration of an *equal* value is indistinguishable and its fn
   does remove the newer one). Disposal runs an owner's cleanups in reverse
   registration order, then the owner's teardowns (fns for what lives outside
   the store, run after its registrations). One failing cleanup never skips the
   rest; it is reported through `*error-reporter*`, as is a cleanup's attempt to
   register into the pass that is clearing its owner."
  (:require [clojure.string :as str]))

(def ^:dynamic *error-reporter*
  "Called with the throwable a failing cleanup threw. The host may rebind it
   (`kmet.agent.runtime` binds it to `kmet.agent.debug/log-error`); the
   default prints to stderr, because the module is rank 0 and cannot require
   `kmet.*` (cells.md §7)."
  (fn [t]
    (binding [*out* *err*]
      (println (str "kmet.cells: cleanup failed: " (str/trim (str (ex-message t))))))))

(defn- empty-store []
  {:by-key {}        ; {key {owner value}} — the host-facing view
   :by-owner {}      ; {owner [{:key k :value v :deregister f}]} in LIFO order
   :teardowns {}     ; {owner [f]} — run after the owner's registrations
   :owner-order []   ; first-registration order, for whole-node disposal
   :disposing nil})  ; nil, :node, or #{owner …} while a disposal pass runs

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
  "A fresh node with an empty store. A non-nil PARENT also gets NODE in its
   `:children`, so `dispose-node!` on the parent ends it; EXTRA carries the
   host's own fields (a stage map), while the node's `:store`, `:parent` and
   `:children` always win (cells.md §5)."
  ([] (node nil))
  ([parent] (node parent nil))
  ([parent extra]
   (let [n (merge extra
                  {:store (atom (empty-store))
                   :parent parent
                   :children (atom [])})]
     (when parent
       (swap! (:children parent) conj n))
     n)))

(defn in-subtree?
  "True when NODE is ROOT or a descendant of it (cells.md §5). A nil ROOT
   is the process-wide scope: every node — nil included — is in it."
  [node root]
  (cond
    (nil? root) true
    (nil? node) false
    (identical? node root) true
    :else (recur (:parent node) root)))

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

(defn- closed-error
  "The ex-info for a registration under OWNER in snapshot S, or nil when S
   accepts it: a store mid-disposal refuses what the pass would drop
   (cells.md §3)."
  [s owner]
  (let [disposing (:disposing s)]
    (when (or (= :node disposing)
              (contains? disposing owner))
      (ex-info (str "cells: cannot register under " (pr-str owner)
                    " while its disposal is running")
               {:type ::closed-store :owner owner}))))

(defn register!
  "Install v under the host-shaped key k for owner (an owner path, e.g.
   `[:app :commands]`). Returns a deregister fn, collected onto owner — the
   caller need not keep it. An owner a disposal pass is clearing refuses the
   registration (`::closed-store`, cells.md §3)."
  [node owner k v]
  (when (nil? v)
    (throw (ex-info "cannot register nil under a key"
                    {:type ::nil-registration :owner owner :key k})))
  (when-let [closed (closed-error @(:store node) owner)]
    (throw closed))
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
   registrations, LIFO. This is where a cleanup for what lives outside the
   store lands — a process, a file handle, a host callback (cells.md §3).
   Refused by the closed-store rule `register!` applies."
  [node owner f]
  (when-let [closed (closed-error @(:store node) owner)]
    (throw closed))
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
  "The set of keys owner registered. A registry that lists itself in
   registration order wants `owner-registrations`."
  [node owner]
  (into #{} (map :key) (get-in @(:store node) [:by-owner owner])))

(defn owner-registrations
  "OWNER's registrations in registration order: [[key value] …]. Replacing a
   registration moves it to the end, as it does for the host's registry."
  [node owner]
  (owner-entries @(:store node) owner))

(defn owners
  "Every owner path on the store, in first-registration order (cells.md
   §2)."
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
   (cells.md §2). One store snapshot, so a registration made while it runs
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

(defn deregister-key!
  "Drop every registration under k — or, with OWNER, only that owner's. The
   call a host registry's name-addressed removal needs (a command, a tool, a
   tool source): with an owner it removes that owner's registration while
   another owner's same-name one survives. Each removal keeps `deregister!`'s
   value guard. Returns nil."
  ([node k] (deregister-key! node k nil))
  ([node k owner]
   (doseq [[o v] (for-key node k)
           :when (or (nil? owner) (= owner o))]
     (deregister! node o k v))))

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

(defn- mark-disposing
  "Mark OWNER as being disposed in store state S. A whole-node disposal keeps
   its `:node` marker rather than narrowing to owners."
  [s owner]
  (update s :disposing
          (fn [disposing]
            (if (= :node disposing)
              disposing
              (conj (or disposing #{}) owner)))))

(defn- unmark-disposing
  "Clear OWNER's mark in S; a whole-node pass keeps its `:node` marker."
  [s owner]
  (update s :disposing
          (fn [disposing]
            (if (set? disposing)
              (disj disposing owner)
              disposing))))

(defn dispose-owner!
  "Dispose owner's registrations (LIFO), then its teardowns (LIFO). While the
   pass runs the owner is closed: a cleanup that tries to register or add a
   teardown under it is refused and reported, never dropped. A throwing cleanup
   is reported and never skips the rest. Idempotent."
  [node owner]
  (let [store (:store node)
        {:keys [by-owner teardowns]} @store]
    (swap! store mark-disposing owner)
    (run-cleanups! (map :deregister (rseq (vec (get by-owner owner)))))
    (run-cleanups! (reverse (get teardowns owner)))
    (swap! store
           (fn [s]
             (-> s
                 (update :by-owner dissoc owner)
                 (update :teardowns dissoc owner)
                 (update :owner-order (fn [order] (into [] (remove #{owner}) order)))
                 (scrub-owner owner)
                 (unmark-disposing owner))))
    nil))

(defn dispose-store!
  "Dispose every owner on NODE's store (reverse registration order, then the
   store itself); the whole pass is closed, so a teardown cannot register into
   a store that is going away. `dispose-node!` is the tree teardown, which
   ends children first (cells.md §3)."
  [node]
  (let [store (:store node)
        owners (reverse (:owner-order @store))]
    (swap! store assoc :disposing :node)
    (doseq [owner owners]
      (dispose-owner! node owner))
    (reset! store (empty-store))
    nil))

(defn dispose-node!
  "End a node: children first (reverse order), then the store; the node leaves
   its parent's `:children`. Idempotent (cells.md §3, §5)."
  [node]
  (doseq [child (reverse @(:children node))]
    (dispose-node! child))
  (when-let [parent (:parent node)]
    (swap! (:children parent)
           (fn [children] (into [] (remove #(identical? % node) children)))))
  (dispose-store! node))
