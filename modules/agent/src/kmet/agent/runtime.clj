(ns kmet.agent.runtime
  "The composition runtime: kmet's own units, resolved by name and mounted
   into one node (cells.md §8.1–§8.2). This is the host side of kmet.cells;
   the session engine a front end drives is kmet.agent.session.runtime.

   P2 fills the table in: the registries that used to be process-wide atoms
   (event subscribers, tools, commands, renderers) become units here, so
   their lifetime is a mount and their owners are slot paths. Until then
   `builtin-units` is empty and nothing but the tests requires this
   namespace."
  (:require [kmet.cells.core :as cells]))

(def builtin-units
  "kmet's own units by name. A unit is a descriptor (cells.md §3): `:start`
   runs at mount with `(node owner-path)` and `:handle` is the fn a caller
   reaches through the snapshot."
  {})

(defn runtime
  "A composition runtime over the builtin units: resolution plus a fresh root
   node for `mount!` to target. The host mounts that node before loading any
   extension, so `init(api)` always has an owner to register under
   (cells.md §8.2)."
  []
  (cells/runtime
   {:resolve [[:builtin (fn [nm] (get builtin-units nm))]]}))

(defn unit-handle
  "The handle mounted at root slot ID, or nil — what a caller uses instead of
   requiring the unit's namespace."
  [node id]
  (:handle (get (cells/composition node) id)))
