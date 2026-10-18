(ns kmet.agent.runtime
  "The composition runtime: kmet's own units, resolved by name and mounted
   into one node (cells.md §8.1–§8.2). This is the host side of kmet.cells;
   the session engine a front end drives is kmet.agent.session.runtime.

   P4 groups the loop's stages into the loop's own composition: `:kmet/agent-loop`
   carries them as inline defaults, so the same descriptor mounts as the root
   loop and as a subagent's loop. What a unit registers in `:start` (event
   subscribers, tools, commands, renderers) then has an owner — its slot path —
   so it is disposed with the mount instead of reset by hand.

   P5a adds the `:ext` backend: an extension publishes units and the entries
   that carry them during `init(api)` (cells.md §8.2), and `remount!` is the
   assembly point for the defaults plus what the loaded extensions published."
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
   addresses. The group carries its stages inline; the session, extension and
   UI slots are addresses the engine and the front end register their live
   values under (cells.md §5.1). Extensions' published entries mount in the
   same list, after these (§8.2); an id one of these already uses is the
   plan's duplicate-id error, not a replacement — the replaceable defaults are
   a group's inline stages, which an entry with the same `:id`/`:into`
   overrides. A config-derived entry waits on the engine having its own node:
   this tree is shared (cells.md §8)."
  [{:id :agent-loop :name :kmet/agent-loop}
   {:id :commands :name :kmet/commands}
   {:id :tools :name :kmet/tools}
   {:id :prompts :name :kmet/prompts}
   {:id :skills :name :kmet/skills}
   {:id :session :name :kmet/session}
   {:id :extensions :name :kmet/extensions}
   {:id :ui :name :kmet/ui}])

;; ─── Extension units (the `:ext` backend) ────────────────────────────────
;;
;; An extension publishes units during `init(api)` (cells.md §8.2). A
;; publication is {:extension <name> :name <keyword> :entry <entry>
;; :unit <descriptor>}: the name resolves through the `:ext` backend and the
;; entry mounts the unit in the app root, so what an extension contributes
;; goes into the tree the same way the host's own entries do. One publication
;; per (extension, name); `unpublish-extension!` drops everything one
;; extension published, which is unload's first step.

(defonce ^:private publications (atom []))

(defn extension-unit
  "The publication for NAME as {:unit <descriptor> :extension <name>}, or nil
   — what the `:ext` backend reads. A name is namespaced to its extension
   (`:budget/spend`), so a repeat means a re-publication."
  [nm]
  (when-let [publication (last (filter #(= nm (:name %)) @publications))]
    (select-keys publication [:unit :extension])))

(defn- published-entries
  "The entries the loaded extensions published, in publication order."
  []
  (mapv :entry @publications))

(defn runtime
  "A composition runtime over the builtin units and the extensions' published
   ones: resolution (`:builtin` first, then `:ext`) plus a fresh root node for
   `mount!` to target."
  []
  (cells/runtime
   {:resolve [[:builtin (fn [nm] (get builtin-units nm))]
              [:ext (fn [nm] (:unit (extension-unit nm)))]]}))

(defonce ^:private app-rt (runtime))

(defn remount!
  "Synchronize the app root with the current entry list: the defaults plus the
   entries the loaded extensions published. Mounts them the first time and
   re-diffs after that, so a unit whose namespace was reloaded remounts, an
   extension's new unit appears, and a dropped one is disposed with its
   registrations (cells.md §6.2, §6.4). `/reload` and every publish/unpublish
   call do this."
  []
  (cells/mount! app-rt (into default-entries (published-entries))))

(defn- drop-publications!
  "Remove the publications matching PRED and remount when any went — the
   tree's slots and the list stay in step. Returns true when something was
   dropped."
  [pred]
  (let [before @publications
        kept (into [] (remove pred) before)]
    (when (< (count kept) (count before))
      (reset! publications kept)
      (remount!)
      true)))

(defn publish-extension-unit!
  "Publish EXT-NAME's unit: NAME (a namespaced keyword) resolves through the
   `:ext` backend to the publication's `:unit`, and its `:entry` mounts in the
   app root. Re-publishing (extension, name) replaces the earlier record, so
   the tree diffs instead of accumulating. Returns a deregister fn.

   Remounts immediately; a mount the checks refuse drops the publication
   before the error reaches the caller (cells.md §6.3)."
  [ext-name {:keys [name entry unit] :as publication}]
  (when-not (and (keyword? name) (map? entry) (map? unit))
    (throw (ex-info (str "kmet.agent.runtime: a published unit needs :name "
                         "(a keyword), :entry and :unit maps, got: "
                         (pr-str publication))
                    {:type ::bad-unit-publication
                     :extension ext-name
                     :publication publication})))
  (let [publication (assoc publication
                           :extension ext-name
                           :entry (assoc entry :name name))]
    (swap! publications
           (fn [ps]
             (conj (into [] (remove #(and (= ext-name (:extension %))
                                          (= name (:name %)))
                                    ps))
                   publication)))
    (try
      (remount!)
      (catch Throwable t
        ;; the checks ran before anything was touched (or a `:start` threw
        ;; mid-walk): drop the publication and re-sync, then surface the
        ;; mount's error rather than a cleanup's
        (try (drop-publications! #(identical? % publication))
             (catch Throwable _))
        (throw t)))
    (fn deregister []
      (drop-publications! #(identical? % publication)))))

(defn unpublish-extension!
  "Drop every unit and entry EXT-NAME published and remount without them —
   unload's first step, before `shutdown(api)` and the loader release
   (cells.md §8.2). Returns true when anything was dropped."
  [ext-name]
  (drop-publications! #(= ext-name (:extension %))))

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
