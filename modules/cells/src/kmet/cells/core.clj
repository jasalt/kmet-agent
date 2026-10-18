(ns kmet.cells.core
  "kmet.cells' public surface: the seven functions (cells.md §2.2)."
  (:require [kmet.cells.explain :as explain]
            [kmet.cells.mount :as mount]
            [kmet.cells.scope :as scope]
            [kmet.cells.spec :as spec]))

(defn runtime
  "Freeze the host's resolution backends: `{:resolve [[kind fn] …]}` -> rt.
   `rt` is a value — several may coexist — and carries its own root node, so
   repeated `mount!` calls diff instead of rebuilding (cells.md §6.2)."
  [host]
  (mount/runtime host))

(defn check
  "The §4 checks over entries: [] when they would mount, else a vector of
   `{:path :entry :key :error}` maps. Pure — no scopes, no code, no effects."
  [rt entries]
  (spec/check rt entries))

(defn mount!
  "Mount ENTRIES: `check` first (throws ex-info with `:errors` when it fails),
   then diff by `:id` into rt's root node, or a fresh child of `:parent`
   (cells.md §5.6, §6.2–§6.3). Returns the node."
  ([rt entries]
   (mount/mount! rt entries))
  ([rt entries opts]
   (mount/mount! rt entries opts)))

(defn dispose!
  "End a node (children first, then every owner, then the mount table) or one
   owner's registrations. LIFO, isolated and reported; never waits (cells.md
   §5.2–§5.3, §5.6)."
  ([node]
   (mount/dispose-node! node))
  ([node owner]
   (scope/dispose! node owner)))

(defn register!
  "Install v under the host-shaped key k for owner (a slot path). Returns a
   deregister fn, collected onto owner — the caller need not keep it. A unit's
   `:start` receives its own path as its second argument."
  [node owner k v]
  (scope/register! node owner k v))

(defn composition
  "One composition's flat snapshot `{slot-id -> entry-map}` — `:app` when no id
   is given, nil when the composition is unknown (a nil node included). Take it
   once per attempt (cells.md §5.4)."
  ([node]
   (mount/composition node))
  ([node id]
   (mount/composition node id)))

(defn explain
  "Render a snapshot for a human: one line per slot, in owner-path order.
   Cosmetic and free to change; `composition` is the ABI (cells.md §7)."
  [snapshot]
  (explain/explain snapshot))
