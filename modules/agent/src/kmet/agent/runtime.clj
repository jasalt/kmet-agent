(ns kmet.agent.runtime
  "The composition runtime: kmet's own units, resolved by name and mounted
   into one node (cells.md §8.1–§8.2). This is the host side of kmet.cells;
   the session engine a front end drives is kmet.agent.session.runtime.

   P2 fills the table in one unit at a time: a namespace fn becomes a unit's
   `:handle`, and its caller reads that handle from an attempt's snapshot
   instead of requiring the namespace. What a unit registers in `:start`
   (event subscribers, tools, commands, renderers) then has an owner — its
   slot path — so it is disposed with the mount instead of reset by hand."
  (:require [kmet.agent.loop-guard :as loop-guard]
            [kmet.cells.core :as cells]))

(def builtin-units
  "kmet's own units by name. A unit is a descriptor (cells.md §3): `:start`
   runs at mount with `(node owner-path)` and `:handle` is the value a caller
   reaches through the snapshot."
  {:kmet/loop-guard
   {:config [:map [:threshold :int]]
    :handle loop-guard/loop-guard-filter}})

(def default-entries
  "The entries the app mounts at its root: units with no per-session config
   and no front-end surface. Config-derived and front-end-shaped units mount
   with the session (P4)."
  [{:id :loop-guard :name :kmet/loop-guard}])

(defn runtime
  "A composition runtime over the builtin units: resolution plus a fresh root
   node for `mount!` to target."
  []
  (cells/runtime
   {:resolve [[:builtin (fn [nm] (get builtin-units nm))]]}))

(defonce ^:private app-rt (runtime))

(defonce ^:private app-root* (atom nil))

(defn remount!
  "Diff the default entries into the app root — mount them the first time,
   and after that re-diff, so a unit whose namespace was reloaded remounts
   and its new handle reaches the app: a mounted slot holds the handle value
   it was mounted with (cells.md §6.2, §6.4). `/reload` calls this. Returns
   the node."
  []
  (locking app-root*
    (let [mounted (cells/mount! app-rt default-entries)]
      (reset! app-root* mounted)
      mounted)))

(defn node
  "The app's root node, mounting the default entries the first time it is
   asked for. -main reaches this before extension loading, so every
   `init(api)` has an owner to register under (cells.md §8.2)."
  []
  (or @app-root* (remount!)))

(defn snapshot
  "Take an attempt's composition read: thread the value through the attempt so
   a mid-attempt change to the tree cannot alter a running one (cells.md
   §5.4)."
  [node]
  (cells/composition node))

(defn handle
  "The handle mounted at SLOT-ID in SNAPSHOT, or nil."
  [snapshot slot-id]
  (:handle (get snapshot slot-id)))

(defn require-handle
  "The handle mounted at SLOT-ID in SNAPSHOT. Throws when the run's tree does
   not carry the slot — a caller that needs a unit should hear which slot and
   which tree, not call nil."
  [snapshot slot-id]
  (or (handle snapshot slot-id)
      (throw (ex-info (str "kmet.agent.runtime: this run's tree has no "
                           (pr-str slot-id) " unit (mounted: "
                           (pr-str (vec (sort (keys snapshot)))) ")")
                      {:type ::missing-unit
                       :slot slot-id
                       :mounted (vec (sort (keys snapshot)))}))))

(defn config
  "SLOT-ID's entry config in SNAPSHOT, or nil — the value the mount validated
   against the unit's `:config` schema (cells.md §3, §6.1)."
  [snapshot slot-id]
  (:config (get snapshot slot-id)))

(defn unit-handle
  "The handle mounted at root slot ID, or nil — the one-off read for a caller
   that is not running an attempt."
  [node id]
  (handle (snapshot node) id))
