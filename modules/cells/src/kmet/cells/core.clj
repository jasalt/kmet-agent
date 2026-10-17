(ns kmet.cells.core
  "kmet.cells' public surface (cells.md §2.2). P0 lands runtime, check,
   register! and dispose!; mount!, composition and explain land with P1."
  (:require [kmet.cells.scope :as scope]
            [kmet.cells.spec :as spec]))

(defn runtime
  "Freeze the host's resolution backends: `{:resolve [[kind fn] …]}` -> rt.
   `rt` is a value: several may coexist — a test's, a sandbox's."
  [host]
  (spec/runtime host))

(defn check
  "The §4 checks over entries: [] when they would mount, else a vector of
   `{:path :entry :key :error}` maps. Pure — no scopes, no code, no effects."
  [rt entries]
  (spec/check rt entries))

(defn register!
  "Install v under the host-shaped key k for owner (a slot path). Returns a
   deregister fn, collected onto owner — the caller need not keep it."
  [node owner k v]
  (scope/register! node owner k v))

(defn dispose!
  "Dispose a node (every owner in reverse registration order, then the store)
   or one owner. LIFO, isolated and reported; never waits (cells.md §5.2–§5.3)."
  ([node]
   (scope/dispose! node))
  ([node owner]
   (scope/dispose! node owner)))
