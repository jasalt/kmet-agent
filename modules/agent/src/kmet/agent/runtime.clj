(ns kmet.agent.runtime
  "The composition runtime: kmet's own units, resolved by name and mounted
   into one node (cells.md §8.1–§8.2). This is the host side of kmet.cells;
   the session engine a front end drives is kmet.agent.session.runtime.

   P4 groups the loop's stages into the loop's own composition: `:kmet/agent-loop`
   carries them as inline defaults, so the same descriptor mounts as the root
   loop and as a subagent's loop. What a unit registers in `:start` (event
   subscribers, tools, commands, renderers) then has an owner — its slot path —
   so it is disposed with the mount instead of reset by hand.

   P5c gives every extension registration an owner: what `init(api)` installs
   lands on `[:app :extensions <name>]` (commands, tools, skills, prompts,
   hooks, renderers, flags, contributed resources), so unloading an extension
   is one LIFO dispose of that owner plus the fns it recorded for what lives
   outside the tree.

   Two sharp edges come with the node, and the host owns both (cells.md §6.4,
   §8): `node` is the app root, a process global every front end, engine and
   loaded extension shares — a tree that must not share config or registrations
   mounts its own node from its own `runtime`; and `mount!` and `dispose!`
   never wait, so the host mutates the live tree only where nothing is mid-run
   (`/reload`, bootstrap, a session swap)."
  (:require [clojure.string :as str]
            [kmet.agent.debug :as debug]
            [kmet.agent.loop-guard :as loop-guard]
            [kmet.agent.retry :as retry]
            [kmet.cells.core :as cells]
            [kmet.cells.scope :as scope]))

(defn- with-reported-cleanups
  "Run F with the store's cleanup reporter bound to
   `kmet.agent.debug/log-error` (cells.md §5.2): a failing cleanup is reported
   and never skips the rest, and the report reaches the agent's error log."
  [f]
  (binding [scope/*error-reporter*
            (fn [t] (debug/log-error "cells cleanup failed: " t))]
    (f)))

(def ^:private loop-guard-unit
  "The repeat-loop guard's descriptor: the loop group's inline stage default,
   and a resolvable name so an entry can replace it with a configured one
   (`:into :agent-loop`)."
  {:config [:map [:threshold :int]]
   :handle loop-guard/loop-guard-filter})

(def ^:private retry-unit
  "The provider-error retry policy's descriptor: the loop group's second
   inline stage default, and a resolvable name so an entry can replace it
   (`:into :agent-loop`). The handle classifies an errored call into the
   loop's next move — a retry with backoff, the overflow recovery, or
   terminal; its config carries the budget and the backoff shape."
  {:config [:map [:max-retries {:optional true} :int]
            [:base-delay-ms {:optional true} :int]
            [:max-agent-delay-ms {:optional true} :int]]
   :handle retry/retry-decision})

(def builtin-units
  "kmet's own units by name. A unit is a descriptor (cells.md §3): `:start`
   runs at mount with `(node owner-path)` and `:handle` is the value a caller
   reaches through the snapshot.

   `:kmet/agent-loop` is the loop's composition (cells.md §5.6): its inline
   `:units` are the stages a loop mounts with — the repeat-loop guard and the
   retry policy — so the same descriptor mounts as the root loop and as a
   subagent's loop. An entry with the same id and `:into :agent-loop` replaces
   one.

   The host's registries — commands, tools, prompt templates, skills, themes,
   and the session and front-end surfaces — are not units: their address is
   the owner path they register under (`[:app :commands]`, `[:app :session]`,
   …), and `/units` reads that address from the store (cells.md §5.1, §7)."
  {:kmet/agent-loop
   {:units {:loop-guard loop-guard-unit
            :retry-policy retry-unit}}
   ;; the stages' own names, so an entry can override an inline default
   :kmet/loop-guard loop-guard-unit
   :kmet/retry retry-unit})

(def default-entries
  "The entries the app mounts at its root: the loop group. An id one of these
   already uses is the plan's duplicate-id error, not a replacement — the
   replaceable defaults are a group's inline stages, which an entry with the
   same `:id`/`:into` overrides. A config-derived entry waits on the engine
   having its own node: this tree is shared (cells.md §8)."
  [{:id :agent-loop :name :kmet/agent-loop}])

(defn runtime
  "A composition runtime over the builtin units, plus a fresh root node for
   `mount!` to target."
  []
  (cells/runtime
   {:resolve [[:builtin (fn [nm] (get builtin-units nm))]]}))

(defonce ^:private app-rt (runtime))

(defn remount!
  "Synchronize the app root with the default entries. Mounts them the first
   time and re-diffs after that, so a unit whose namespace was reloaded
   remounts and its old registrations are disposed (cells.md §6.2, §6.4).
   `/reload` does this."
  []
  (with-reported-cleanups
    #(cells/mount! app-rt default-entries)))

(defonce ^:private app-node
  ;; The once latch: the first deref mounts, and mounting returns the same
  ;; node every time (rt carries its root), so the cached value stays right
  ;; through a remount.
  (delay (remount!)))

(defn node
  "The app's root node, mounting the default entries the first time it is
   asked for. -main reaches this before extension loading, so every
   `init(api)` has an owner to register under (cells.md §8.2)."
  []
  @app-node)

(defn dispose-owner!
  "Dispose OWNER's registrations on the app root, LIFO and isolated — an
   extension's own registrations when it unloads, or a slot's when it is
   replaced. Failures are reported, not swallowed (cells.md §5.2)."
  [owner]
  (with-reported-cleanups #(cells/dispose! (node) owner)))

(defn dispose-deregisters!
  "Run FNS — the deregister fns an extension recorded for what lives outside
   the app tree — in reverse registration order, each isolated and a failure
   reported through the same reporter the store uses instead of skipping the
   rest (cells.md §1.2, §5.2). Returns nil."
  [fns]
  (with-reported-cleanups
    #(doseq [f (reverse fns)]
       (try (f) (catch Throwable t (scope/*error-reporter* t)))))
  nil)

(defn snapshot
  "Take a composition read: the root, or COMPOSITION-ID's own slots (the
   loop's stages are `:agent-loop`; cells.md §5.4, §5.6). Thread the value
   through the attempt so a mid-attempt change to the tree cannot alter a
   running one."
  ([node] (cells/composition node))
  ([node composition-id] (cells/composition node composition-id)))

(defn- path-prefix?
  "Whether PREFIX is PATH's leading segment list — the test that a composition
   hangs at the walk's current path, not merely under the same slot id."
  [prefix path]
  (and (vector? path)
       (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))

(defn- deepest-prefix
  "The longest of PATHS that properly prefixes PATH, or nil."
  [paths path]
  (last (sort-by count (filter #(and (path-prefix? % path)
                                     (not= % path))
                               paths))))

(defn- child-composition
  "The composition mounted at SLOT when it hangs at CHILD-PATH — every entry's
   owner path prefixed by CHILD-PATH — or nil. Slot ids may repeat across
   compositions (only composition ids are global), so an id another subtree
   uses is a leaf here: both the walk and `slot-paths` stop at it."
  [node slot child-path]
  (let [sub (cells/composition node slot)]
    (when (and sub
               (every? #(path-prefix? child-path (:owner %)) (vals sub)))
      sub)))

(defn- slot-paths
  "Every slot path in COMP-ID's subtree — the set `store-owners-by-parent`
   measures the store's owners against (cells.md §7)."
  [node comp-id path]
  (into #{}
        (mapcat (fn [[slot _]]
                  (let [child (conj path slot)]
                    (if (child-composition node slot child)
                      (conj (slot-paths node slot child) child)
                      #{child}))))
        (cells/composition node comp-id)))

(defn- owner-line
  "One store owner as `/units` prints it: `<owner> keys=[…]`, the owner's
   registrations in registration order (cells.md §5.1, §7)."
  [node owner]
  (str (pr-str owner)
       " keys=" (pr-str (mapv first (scope/owner-registrations node owner)))))

(defn- store-owners-by-parent
  "The store owners that hold registrations but have no slot of their own,
   keyed by the deepest path that prefixes them — a slot path or another
   owner path — or `[:app]` when nothing proper does. A registry's address
   (`[:app :commands]`) and an extension's `[:app :extensions <name>]` — where
   P5c lands every registration `init(api)` makes — hang under the root. A
   slot's own keys already render in its line, so a slot path is not repeated
   here (cells.md §5.1, §7). One entry per owner, in registration order."
  [node slots]
  (let [owners (scope/owners node)
        hang-paths (into (set slots) owners)]
    (reduce (fn [acc owner]
              (if (or (empty? (scope/owner-keys node owner))
                      (contains? slots owner))
                acc
                (let [parent (or (deepest-prefix hang-paths owner) [:app])]
                  (update acc parent (fnil conj []) owner))))
            {}
            owners)))

(defn explain
  "Render NODE's composition tree for a human: `cells/explain` per composition,
   each group's slots indented under the slot that mounts it, and every store
   owner that is not a slot — a registry's address, an extension's
   registrations — as a line of its own, indented under the deepest slot or
   owner path that prefixes it (cells.md §5.1, §7). `/units` prints this;
   `cells/composition` stays the stable form."
  [node]
  (let [store-owners (store-owners-by-parent node (slot-paths node :app [:app]))]
    (letfn [(pad [depth] (apply str (repeat (* 2 depth) " ")))
            (owner-lines [path depth]
              (mapcat (fn [owner]
                        (concat
                         [(str (pad depth) (owner-line node owner))]
                         ;; an owner registered at the root keys under its own
                         ;; path: render it once, not into itself
                         (when-not (= owner path)
                           (owner-lines owner (inc depth)))))
                      (get store-owners path)))
            (slot-lines [comp-id path depth]
              (mapcat (fn [[slot entry]]
                        (let [child-path (conj path slot)]
                          (concat
                           [(str (pad depth) (cells/explain {slot entry}))]
                           (owner-lines child-path (inc depth))
                           (when (child-composition node slot child-path)
                             (slot-lines slot child-path (inc depth))))))
                      (sort-by (comp :owner val) (cells/composition node comp-id))))]
      (str/join "\n"
                (concat (slot-lines :app [:app] 0)
                        (owner-lines [:app] 0))))))

(defn handle
  "The handle mounted at SLOT-ID in SNAPSHOT, or nil."
  [snapshot slot-id]
  (:handle (get snapshot slot-id)))

(defn require-handle
  "The handle mounted at SLOT-ID in SNAPSHOT. Throws when the run's tree does
   not carry the slot — a caller that needs a unit should hear which slot and
   which tree, not call nil."
  [snapshot slot-id]
  (if-let [handle (handle snapshot slot-id)]
    handle
    (let [mounted (vec (sort (keys snapshot)))]
      (throw (ex-info (str "kmet.agent.runtime: this run's tree has no "
                           (pr-str slot-id) " unit (mounted: "
                           (pr-str mounted) ")")
                      {:type ::missing-unit
                       :slot slot-id
                       :mounted mounted})))))

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
