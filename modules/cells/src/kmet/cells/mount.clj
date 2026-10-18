(ns kmet.cells.mount
  "The mount table: entries -> node, the `:id` diff, `:start` execution and
   owner paths (cells.md §5.1–§6.3). `rt` carries the root node `mount!`
   targets, which is what makes repeated calls diff instead of rebuild."
  (:require [kmet.cells.scope :as scope]
            [kmet.cells.spec :as spec]))

(def ^:private framework-keys
  "The descriptor keys the framework interprets; everything else is host
   metadata, preserved untouched (cells.md §3)."
  #{:handle :units :start :config})

(defn- make-node
  "A node with a mount table, recorded on PARENT when given."
  [parent]
  (let [n (assoc (scope/node parent) :mounted (atom {}))]
    (when parent
      (swap! (:children parent) conj n))
    n))

(defn runtime
  "The host's resolution backends plus a fresh root node. `mount!` targets that
   node, so one `rt` mounts and re-mounts one tree (cells.md §6.2)."
  [host]
  (assoc (spec/runtime host) :root (make-node nil)))

(defn- prefix?
  [prefix path]
  (and (vector? path)
       (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))

(def ^:private subtree-round-limit
  "A subtree disposal rescans: an owner that appears under PREFIX while the
   pass runs — a teardown registering under a new path inside the subtree it
   is being disposed with — is disposed by the next round. The cap turns a
   unit that keeps creating owners into a report, not a hung mount."
  16)

(defn- report-unsettled-subtree!
  "Report a subtree disposal that did not settle: OWNERS still sit under
   PREFIX after `subtree-round-limit` rounds."
  [prefix owners]
  (scope/*error-reporter*
   (ex-info (str "cells: subtree " (pr-str prefix) " still owns "
                 (pr-str (vec owners)) " after " subtree-round-limit
                 " disposal rounds")
            {:type ::unsettled-subtree :prefix prefix :owners (vec owners)})))

(defn- dispose-subtree!
  "Dispose every owner at or under PREFIX in LIFO order — a replaced or
   dropped slot takes its descendants' registrations with it (§5.1). The pass
   rescans, so an owner a teardown installs under PREFIX is disposed too, not
   left as a ghost; a subtree that keeps growing is reported rather than
   looped (see `subtree-round-limit`)."
  [node prefix]
  (loop [round 0]
    (let [owners (vec (filter #(prefix? prefix %) (scope/owners node)))]
      (when (seq owners)
        (doseq [owner (reverse owners)]
          (scope/dispose-owner! node owner))
        (if (< round subtree-round-limit)
          (recur (inc round))
          (when-let [left (seq (filter #(prefix? prefix %) (scope/owners node)))]
            (report-unsettled-subtree! prefix left)))))))

(defn- publish!
  "Record one slot in the live mount table as it is admitted, so the table
   names exactly the slots that are mounted — mid-walk and after an abort
   included (cells.md §6.3)."
  [node comp-id id record]
  (swap! (:mounted node) assoc-in [comp-id id] record))

(defn- publish-composition!
  "Mark COMP-ID as mounted, empty until its slots land: the walk reaching a
   composition is what makes it part of the snapshot, so an empty one is `{}`
   rather than unknown — the same state a composition whose slots were all
   dropped is left in."
  [node comp-id]
  (swap! (:mounted node) update comp-id #(or % {})))

(defn- forget-composition!
  "Drop COMP-ID and every composition under it from the live table, reading
   OLD (the pre-walk table) for the nested ids — called where a slot's subtree
   is disposed."
  [node old comp-id]
  (doseq [{:keys [id unit]} (get old comp-id)]
    (when (contains? unit :units)
      (forget-composition! node old id)))
  (swap! (:mounted node) dissoc comp-id))

(defn- forget-slot!
  "Remove a dropped slot from the live table: its group's composition subtree
   (found in OLD) and its own record under COMP-ID."
  [node old comp-id id]
  (forget-composition! node old id)
  (swap! (:mounted node) update comp-id (fnil dissoc {}) id))

(defn- checked-tree
  "The tree `mount!` walks: SPEC's effective tree, its §4 errors thrown as
   ex-info (cells.md §6.1–§6.3)."
  [rt entries]
  (let [{:keys [tree errors]} (spec/effective-tree rt entries)]
    (when (seq errors)
      (throw (ex-info (str "cells: mount checks failed (" (count errors)
                           " error" (when (< 1 (count errors)) "s") "): "
                           (:error (first errors)))
                      {:type ::check-failed :errors errors})))
    tree))

(defn- same-slot?
  "A slot is kept when its owner path, resolved unit, `:name` and `:config`
   are unchanged; any change remounts it (cells.md §6.2). The path matters: a
   composition id can reappear under a different parent, and a slot's
   registrations must move with it."
  [record owner unit entry]
  (let [old (:entry record)]
    (and (= (:owner record) owner)
         (= (:unit record) unit)
         (= (:name old) (:name entry))
         (= (contains? old :config) (contains? entry :config))
         (= (:config old) (:config entry)))))

(defn- admit!
  "Run the unit's `:start` with (node owner) — the owner path is what it
   registers under — collect its teardown, and return the slot record. A
   truthy return that is not a fn or var is a mount error, not a teardown, and
   a throwing `:start` unwinds everything under its owner before the error
   propagates (cells.md §6.3)."
  [node owner unit entry]
  (when-let [start (:start unit)]
    (try
      (when-let [teardown (start node owner)]
        (when-not (spec/fn-or-var? teardown)
          (throw (ex-info (str "cells: :start for " (pr-str owner)
                               " returned a teardown that is not a fn or var")
                          {:type ::bad-teardown :owner owner :value teardown})))
        (scope/add-teardown! node owner teardown))
      (catch Throwable t
        (dispose-subtree! node owner)
        (throw t))))
  {:owner owner :unit unit :entry entry :handle (:handle unit)})

(defn- mount-composition
  "Mount COMP-ID's slots — from TREE, the pre-validated effective tree — at
   owner prefix COMP-PATH, diffing against OLD (the pre-walk table). A slot is
   kept only while the live table still holds its record, so a subtree a
   replacement disposed is never re-used. Each slot is published as it is
   admitted; children mount after their parent's `:start`, which is what makes
   children dispose first (cells.md §5.1)."
  [node old tree comp-id comp-path]
  (let [current (get old comp-id {})
        slots (get tree comp-id)
        slot-ids (set (map :id slots))]
    (publish-composition! node comp-id)
    (doseq [[id record] current
            :when (not (contains? slot-ids id))]
      (dispose-subtree! node (:owner record))
      (forget-slot! node old comp-id id))
    (doseq [{:keys [id entry unit]} slots]
      (let [owner (conj comp-path id)
            existing (get-in @(:mounted node) [comp-id id])
            keep? (and existing (same-slot? existing owner unit entry))
            _ (when (and existing (not keep?))
                (dispose-subtree! node (:owner existing))
                (forget-composition! node old id))
            record (if keep? existing (admit! node owner unit entry))]
        (publish! node comp-id id record)
        (when (contains? unit :units)
          (mount-composition node old tree id owner))))))

(defn mount!
  "Mount ENTRIES into RT's root node, or a fresh child of `:parent` (one per
   call — §5.6's per-instance lifetimes). Checks first — throwing ex-info with
   `:errors` when they fail — then diffs by `:id`: additions mount, changed
   and dropped slots have their owner subtree disposed first (cells.md
   §6.2–§6.3). Composition ids are validated before anything is touched, and
   the mount table is written as each slot is admitted, so a host `:start`
   that throws leaves earlier slots mounted and *recorded*, with its own slot
   unwound — never a store the snapshot cannot describe. Returns the node."
  ([rt entries] (mount! rt entries nil))
  ([rt entries {:keys [parent] :as opts}]
   (when-not (or (nil? opts) (map? opts))
     (throw (ex-info "cells: mount! opts must be a map, or nil"
                     {:type ::bad-opts :opts opts})))
   (let [tree (checked-tree rt entries)
         target (if parent (make-node parent) (:root rt))
         old @(:mounted target)]
     (mount-composition target old tree :app [:app])
     target)))

(defn- snapshot-entry
  "The ABI value for one slot: the handle, owner path, config and registry
   keys, plus the unit's host metadata verbatim (cells.md §5.4/§7)."
  [node {:keys [handle owner unit entry]}]
  (let [keys (scope/owner-keys node owner)
        metadata (apply dissoc unit framework-keys)]
    (cond-> (assoc metadata :owner owner)
      handle (assoc :handle handle)
      (contains? entry :config) (assoc :config (:config entry))
      (seq keys) (assoc :keys keys))))

(defn composition
  "One composition's flat snapshot `{slot-id -> entry-map}` — `:app` when no
   id is given, nil when the composition is unknown (a nil node included, so
   a caller can read a state's node unconditionally). Take it once per attempt
   (cells.md §5.4)."
  ([node] (composition node :app))
  ([node id]
   (when-let [mounted (:mounted node)]
     (when-let [records (get @mounted id)]
       (into {} (map (fn [[slot-id record]] [slot-id (snapshot-entry node record)]))
             records)))))

(defn dispose-node!
  "End a node: children first (cells.md §5.6), then every owner, then the
   mount table. A disposed child leaves its parent's list; idempotent."
  [node]
  (doseq [child (reverse @(:children node))]
    (dispose-node! child))
  (when-let [parent (:parent node)]
    (swap! (:children parent)
           (fn [children] (into [] (remove #(identical? % node)) children))))
  (some-> (:mounted node) (reset! {}))
  (scope/dispose! node))
