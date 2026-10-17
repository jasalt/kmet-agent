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

(defn- dispose-subtree!
  "Dispose every owner at or under PREFIX in LIFO order — a replaced or
   dropped slot takes its descendants' registrations with it (§5.1)."
  [node prefix]
  (doseq [owner (reverse (filter #(prefix? prefix %) (scope/owners node)))]
    (scope/dispose-owner! node owner)))

(defn- publish!
  "Record one slot in the live mount table as it is admitted, so the table
   names exactly the slots that are mounted — mid-walk and after an abort
   included (cells.md §6.3)."
  [node comp-id id record]
  (swap! (:mounted node) assoc-in [comp-id id] record))

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

(defn- candidate-slot
  "The `{:id :unit :entry}` view of a plan candidate."
  [candidate]
  (select-keys candidate [:id :unit :entry]))

(defn- effective-slots
  "A composition's slots: the owning unit's inline `:units` defaults with the
   plan's entries merged in. An entry whose :id matches an inline slot
   replaces it; other entries append in entry order."
  [plan comp-id inline-units]
  (let [entries (get (:compositions plan) comp-id)
        by-id (into {} (map (juxt :id identity)) entries)]
    (into []
          (concat
           (map (fn [id]
                  (if-let [c (get by-id id)]
                    (candidate-slot c)
                    {:id id
                     :unit (get inline-units id)
                     :entry {:id id :into comp-id}}))
                (keys inline-units))
           (map candidate-slot
                (remove #(contains? inline-units (:id %)) entries))))))

(defn- effective-tree
  "The composition tree `{comp-id [slot …]}` for PLAN, refusing a repeated id.
   The tree doubles as the visited set, so a repeat in a sibling subtree is
   refused too; building it before anything is mounted is what keeps a
   structural error from touching a node (cells.md §6.1)."
  [plan]
  (let [tree (atom {})]
    (letfn [(walk [comp-id inline-units]
              (when (contains? @tree comp-id)
                (throw (ex-info (str "cells: composition id " comp-id
                                     " is used twice in one mount")
                                {:type ::duplicate-composition :id comp-id})))
              (let [slots (effective-slots plan comp-id inline-units)]
                (swap! tree assoc comp-id slots)
                (doseq [{:keys [id unit]} slots
                        :when (contains? unit :units)]
                  (walk id (:units unit)))))]
      (walk :app {})
      @tree)))

(defn- checked-tree
  "The tree `mount!` walks: SPEC's plan, its §4 errors thrown as ex-info, and
   the duplicate-id refusal (cells.md §6.1–§6.3)."
  [rt entries]
  (let [plan (spec/plan rt entries)
        errors (:errors plan)]
    (when (seq errors)
      (throw (ex-info (str "cells: mount checks failed (" (count errors)
                           " error" (when (< 1 (count errors)) "s") "): "
                           (:error (first errors)))
                      {:type ::check-failed :errors errors})))
    (effective-tree plan)))

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
   truthy, non-callable return is a mount error, not a teardown, and a
   throwing `:start` unwinds its own owner before the error propagates
   (cells.md §6.3)."
  [node owner unit entry]
  (when-let [start (:start unit)]
    (try
      (when-let [teardown (start node owner)]
        (when-not (ifn? teardown)
          (throw (ex-info (str "cells: :start for " (pr-str owner)
                               " returned a non-callable teardown")
                          {:type ::bad-teardown :owner owner :value teardown})))
        (scope/add-teardown! node owner teardown))
      (catch Throwable t
        (scope/dispose-owner! node owner)
        (throw t))))
  {:owner owner :unit unit :entry entry :handle (:handle unit)})

(defn- mount-composition
  "Mount COMP-ID's slots — from TREE, the pre-validated effective tree — at
   owner prefix COMP-PATH, diffing against OLD (the pre-walk table). Each slot
   is published as it is admitted; children mount after their parent's
   `:start`, which is what makes children dispose first (cells.md §5.1)."
  [node old tree comp-id comp-path]
  (let [current (get old comp-id {})
        slots (get tree comp-id)
        slot-ids (set (map :id slots))]
    (doseq [[id record] current
            :when (not (contains? slot-ids id))]
      (dispose-subtree! node (:owner record))
      (forget-slot! node old comp-id id))
    (doseq [{:keys [id entry unit]} slots]
      (let [owner (conj comp-path id)
            existing (get current id)
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
   id is given, nil when the composition is unknown. Take it once per attempt
   (cells.md §5.4)."
  ([node] (composition node :app))
  ([node id]
   (when-let [records (get @(:mounted node) id)]
     (into {} (map (fn [[slot-id record]] [slot-id (snapshot-entry node record)]))
           records))))

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
