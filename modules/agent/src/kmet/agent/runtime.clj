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
   assembly point for the defaults plus what the loaded extensions published.
   P5c gives every extension registration an owner: what `init(api)` installs
   lands on `[:app :extensions <name>]` (commands, tools, skills, prompts,
   hooks, renderers, flags, contributed resources), so unloading an extension
   is one LIFO dispose of that owner plus the fns it recorded for what lives
   outside the tree."
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
   ;; the theme registry's address: themes loaded from contributed paths
   ;; report under it (the theme store itself is the TUI layer's own).
   :kmet/themes {:units {}}
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
   {:id :themes :name :kmet/themes}
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
;;
;; `extension.edn` may declare a unit the same way minus its code
;; (`declare-extension-unit!`): its unit carries neither a `:handle` nor
;; `:units`, so it neither resolves nor mounts until a publication of the same
;; name binds it — which lets the host validate the manifest before the
;; extension's code evaluates. A publication inherits what the record it
;; replaces leaves out (its declaration, or an earlier publication) and puts it
;; back if the mount is refused.
;;
;; Mounting and unmounting are the caller's boundary decision — the store
;; never waits, and `unload-extension!` is called at quiescent points
;; (`/reload`, bootstrap) — cells.md §5.3, §8.

(defonce ^:private publications (atom []))

(defn- implementable?
  "True when PUBLICATION's unit can mount at all — a `:handle` or inline
   `:units`, the framework's own rule (`kmet.cells.spec`) — and false for a
   manifest declaration, whose unit carries neither until its handle binds
   (cells.md §8.2)."
  [publication]
  (let [unit (:unit publication)]
    (boolean (or (:handle unit) (contains? unit :units)))))

(defn- record-for
  "The record holding NAME, or nil. At most one does: binding a declaration
   replaces it and a name is exclusive to its publisher, so this is also the
   record a publication replaces."
  [nm]
  (some #(when (= nm (:name %)) %) @publications))

(defn extension-unit
  "The publication for NAME as {:unit <descriptor> :extension <name>}, or nil
   — what the `:ext` backend reads. A name is namespaced to its extension
   (`:budget/spend`), so a repeat means a re-publication. A manifest
   declaration resolves only once its handle binds it (cells.md §8.2)."
  [nm]
  (when-let [publication (record-for nm)]
    (when (implementable? publication)
      (select-keys publication [:unit :extension]))))

(defn- published-entries
  "The entries the loaded extensions published, in publication order. A
   declaration mounts nothing until it is bound."
  []
  (into [] (comp (filter implementable?) (map :entry)) @publications))

(defn- ensure-name-free!
  "Refuse NAME when another extension already holds it: a published name lives
   in its publisher's namespace (cells.md §8.2)."
  [ext-name name]
  (let [owner (:extension (record-for name))]
    (when (and owner (not= ext-name owner))
      (throw (ex-info (str "kmet.agent.runtime: " name " is already published by "
                           (pr-str owner) " — a published name lives in its "
                           "extension's namespace")
                      {:type ::duplicate-unit-name
                       :extension ext-name
                       :name name
                       :published-by owner})))))

(defn- store!
  "Store PUBLICATION, replacing the record that held its name — the declaration
   it binds, or the publication it replaces (a name is exclusive to its
   publisher, so any match is ours). Nothing mounts here."
  [publication]
  (swap! publications
         (fn [ps]
           (conj (into [] (remove #(= (:name publication) (:name %))) ps)
                 publication))))

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
  (with-reported-cleanups
    #(cells/mount! app-rt (into default-entries (published-entries)))))

(defn- restore!
  "Put PUBLICATION back to the record it replaced — PREVIOUS, or nothing when
   it replaced nothing — and remount. The refused-mount rollback: a failed
   publication must not consume the declaration it was binding."
  [publication previous]
  (let [without (into [] (remove #(identical? % publication)) @publications)]
    (reset! publications (if previous (conj without previous) without))
    (remount!)))

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

(defn declare-extension-unit!
  "Store EXT-NAME's `extension.edn` declaration for NAME (cells.md §8.2): the
   unit's `:config` schema and metadata, and the `:entry` that mounts it — but
   no `:handle`, which the extension's code supplies when it publishes the same
   name. Declaring mounts nothing and the name does not resolve, so the host
   can validate the manifest before the extension's code evaluates. Returns
   nil."
  [ext-name {:keys [name entry unit] :as declaration}]
  (when-not (and (keyword? name) (map? entry) (or (nil? unit) (map? unit)))
    (throw (ex-info (str "kmet.agent.runtime: a declared unit needs :name "
                         "(a keyword), :entry and an optional :unit map, got: "
                         (pr-str declaration))
                    {:type ::bad-unit-declaration
                     :extension ext-name
                     :declaration declaration})))
  (ensure-name-free! ext-name name)
  (store! (assoc declaration :extension ext-name))
  nil)

(defn unbound-declarations
  "The declared unit names of EXT-NAME that nothing bound yet: a manifest
   promises a handle for every unit it declares, so a non-empty answer fails
   the load (cells.md §8.2)."
  [ext-name]
  (into [] (comp (filter #(and (= ext-name (:extension %))
                               (not (implementable? %))))
                 (map :name))
        @publications))

(defn publish-extension-unit!
  "Publish EXT-NAME's unit: NAME (a namespaced keyword) resolves through the
   `:ext` backend to the publication's `:unit`, and its `:entry` mounts in the
   app root. The record this call replaces — a declaration (`extension.edn`) or
   an earlier publication — supplies what the call leaves out: the `:config`
   schema, the entry that mounts it. Re-publishing (extension, name) replaces
   the earlier record, so the tree diffs instead of accumulating; another
   extension holding the name is refused, since a name lives in its publisher's
   namespace. Returns a deregister fn.

   Remounts immediately; a mount the checks refuse drops the publication and
   restores what it replaced, then surfaces the mount's error (cells.md
   §6.3)."
  [ext-name {:keys [name entry unit] :as publication}]
  (when-not (and (keyword? name) (map? unit))
    (throw (ex-info (str "kmet.agent.runtime: a published unit needs :name "
                         "(a keyword) and a :unit map, got: "
                         (pr-str publication))
                    {:type ::bad-unit-publication
                     :extension ext-name
                     :publication publication})))
  (ensure-name-free! ext-name name)
  (let [previous (record-for name)
        publication (assoc publication
                           :extension ext-name
                           :entry (assoc (merge (:entry previous) entry)
                                         :name name)
                           :unit (merge (:unit previous) unit))]
    (when-not (:id (:entry publication))
      (throw (ex-info (str "kmet.agent.runtime: " name " has no entry :id — "
                           "neither the publication nor the manifest says "
                           "where it mounts")
                      {:type ::bad-unit-publication
                       :extension ext-name
                       :publication publication})))
    (store! publication)
    (try
      (remount!)
      (catch Throwable t
        ;; the checks ran before anything was touched (or a `:start` threw
        ;; mid-walk): undo the store and re-sync, then surface the mount's
        ;; error rather than a cleanup's
        (try (restore! publication previous)
             (catch Throwable _))
        (throw t)))
    (fn deregister []
      (drop-publications! #(identical? % publication)))))

(defn unpublish-extension!
  "Drop every unit, declaration and entry EXT-NAME published and remount
   without them — unload's first step, before `shutdown(api)` and the loader
   release (cells.md §8.2). Returns true when anything was dropped."
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
