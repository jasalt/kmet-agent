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

(defonce ^:private app-root
  ;; Mounted as this namespace loads — earlier than -main rather than later,
  ;; which is the guarantee the root has to satisfy: every init(api) needs an
  ;; owner to register under (cells.md §8.2). `mount!` diffs, so a later mount
  ;; of the same entries is a no-op.
  (cells/mount! app-rt default-entries))

(defn node
  "The app's root node, with the default entries mounted. One app tree per
   process: -main reaches this before loading extensions, and a run's
   AgentState defaults to it (kmet.agent.loop/make-agent-state)."
  []
  app-root)

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

(defn unit-handle
  "The handle mounted at root slot ID, or nil — the one-off read for a caller
   that is not running an attempt."
  [node id]
  (handle (snapshot node) id))
