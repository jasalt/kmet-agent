(ns kmet.agent.runtime
  "The composition runtime: kmet's own units, resolved by name and mounted
   into one node (cells.md §8.1–§8.2). This is the host side of kmet.cells;
   the session engine a front end drives is kmet.agent.session.runtime.

   P4 groups the loop's stages into the loop's own composition: `:kmet/agent-loop`
   carries them as inline defaults, so the same descriptor mounts as the root
   loop and as a subagent's loop. What a unit registers in `:start` (event
   subscribers, tools, commands, renderers) then has an owner — its slot path —
   so it is disposed with the mount instead of reset by hand."
  (:require [clojure.string :as str]
            [kmet.agent.loop-guard :as loop-guard]
            [kmet.agent.retry :as retry]
            [kmet.cells.core :as cells]))

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
   one."
  {:kmet/agent-loop
   {:units {:loop-guard loop-guard-unit
            :retry-policy retry-unit}}
   ;; the stages' own names, so an entry can override an inline default
   :kmet/loop-guard loop-guard-unit
   :kmet/retry retry-unit
   ;; an address, not a container: the slash command registry registers under
   ;; this slot's path, so the slot is what makes its keys visible in /units
   ;; (cells.md §5.1). Commands are registrations, not units — the group is
   ;; empty — and there is no handle for the host to read.
   :kmet/commands {:units {}}
   ;; the same address shape for the tool registry: the custom tools and
   ;; contributed sources registered under [:app :tools] report as its keys
   ;; in /units (cells.md §5.1).
   :kmet/tools {:units {}}
   ;; the prompt-template registry's address: package- and extension-loaded
   ;; templates report under it.
   :kmet/prompts {:units {}}
   ;; the skill registry's address: package- and extension-loaded skills
   ;; report under it.
   :kmet/skills {:units {}}
   ;; the session surface's address: the live session and the context / entry
   ;; sinks the engine installs report under it (cells.md §5.1, §5.6).
   :kmet/session {:units {}}
   ;; the extension surface's address: an extension's own registrations are
   ;; owned by [:app :extensions <name>] — under this slot's path, so dropping
   ;; the slot disposes them (each extension's keys stay on its own owner).
   :kmet/extensions {:units {}}
   ;; the front-end surface's address: the capability map a front end
   ;; installs for extensions (the UI port) reports under it.
   :kmet/ui {:units {}}})

(def default-entries
  "The entries the app mounts at its root: the loop group and the domains'
   addresses. The group carries its stages inline; config-derived and
   front-end-shaped units mount with the session (P4)."
  [{:id :agent-loop :name :kmet/agent-loop}
   {:id :commands :name :kmet/commands}
   {:id :tools :name :kmet/tools}
   {:id :prompts :name :kmet/prompts}
   {:id :skills :name :kmet/skills}
   {:id :session :name :kmet/session}
   {:id :extensions :name :kmet/extensions}
   {:id :ui :name :kmet/ui}])

(defn runtime
  "A composition runtime over the builtin units: resolution plus a fresh root
   node for `mount!` to target."
  []
  (cells/runtime
   {:resolve [[:builtin (fn [nm] (get builtin-units nm))]]}))

(defonce ^:private app-rt (runtime))

(defn remount!
  "Diff the default entries into the app root — mount them the first time and
   re-diff after that, so a unit whose namespace was reloaded remounts and its
   new handle reaches the app: a mounted slot holds the handle value it was
   mounted with (cells.md §6.2, §6.4). `/reload` calls this, and `node` calls
   it once.

   These entries are the root's complete slot list — `mount!` drops what the
   list no longer names (§6.2) — so a later phase that mounts more into this
   tree extends `default-entries` rather than mounting beside them."
  []
  (cells/mount! app-rt default-entries))

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

(defn explain
  "Render NODE's composition tree for a human: `cells/explain` per composition,
   each group's slots indented under the slot that mounts it (a group is a
   composition id in the node's mount table). `/units` prints this;
   `cells/composition` stays the stable form (cells.md §7)."
  [node]
  (letfn [(lines [comp-id path depth]
            (let [pad (apply str (repeat (* 2 depth) " "))]
              (into []
                    (mapcat (fn [[slot entry]]
                              (let [child-path (conj path slot)
                                    sub (cells/composition node slot)]
                                (cons (str pad (cells/explain {slot entry}))
                                      ;; slot ids may repeat across compositions
                                      ;; (only composition ids are global), so
                                      ;; the owners must hang at this path
                                      (when (and sub
                                                 (every? #(path-prefix? child-path (:owner %))
                                                         (vals sub)))
                                        (lines slot child-path (inc depth)))))))
                    (sort-by (comp :owner val) (cells/composition node comp-id)))))]
    (str/join "\n" (lines :app [:app] 0))))

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
