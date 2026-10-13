(ns kmet.libs.hikap
  "Backend-agnostic hiccup component engine — the toolkit-independent half
   of the former kmet.tui.hiccup (the TUI backend now lives there; the Tk
   backend in kmet.gui.tk).

   Trees are plain data: [:tag {props} children...] or [my-fn {props}]
   function heads. Compilation walks the tree through the BACKEND's closed
   tag table (the backend map passed to compile-tree/root); custom
   composition uses fn components (wrapped in ComponentFn, their bodies
   running inside reactions with auto-discovered deps) or raw components
   spliced into the tree (identity preserved, never disposed — they are
   owned elsewhere).

   Reconciliation is ONE keyed diff — the ComponentFn wrapper's child list
   AND the fill of every host container (through per-tag children lenses).
   Matching is explicit :key first (from the props map or, reagent-style,
   from the element vector's own metadata — ^{:key k} [:text …]; props win
   when both are given), fallback match-kind (tag / fn value / record
   payload / string); unmatched previous children are disposed
   (children-first contract), matched ones are reused — identity survives
   reorders by key, so stateful subtrees live across passes. =-equal input
   to a re-run body (or to a container) takes the unchanged-subtree skip
   instead of a re-parse/re-diff. Ownership rides the :dsl/meta stamp:
   everything the DSL constructs carries it; foreign records never do and
   are never disposed.

   A backend is a map:
     :name            keyword identifying the backend
     :tags            the closed host tag table (tag -> spec map)
     :text-leaf       (fn [text props] component) — a bare string child
     :component?      (fn [x] bool) — a foreign spliced component?
     :entry-component (fn [entry] component) — stack-entry payload, or nil
     :dispose!        (fn [component]) — release one constructed component
     :render-items    (fn [items ctx]) — render a ComponentFn's child items
                       (item maps {:c component …}, not bare components)
     :render          (fn [component ctx] output) — render one component
     :render-all      (fn [components ctx] output) — render several
     :bind-context    (fn [component ctx thunk]) — bind render context
                      (dynamic vars, UI-thread refs) around a body run
     :did-you-mean    optional (fn [name candidates] string)

   ComponentFn is the generic wrapper: a backend extends its own component
   protocol to it (kmet.tui.protocols/IComponent for the TUI; the Tk
   backend renders nothing) and calls render-component/dispose-component.

   Mounting goes through root — the one public constructor from a tree to
   a mounted, disposable component."
  (:refer-clojure :exclude [ref])
  (:require
   [kmet.libs.hikap.alias :as alias]
   [kmet.libs.hikap.component :as component]
   [kmet.libs.reakt :as r]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Refs — the imperative escape hatch (tui.md §2.4)
;; ═══════════════════════════════════════════════════════════════════════════

(defprotocol DslRef
  "Internal surface of hiccup refs. Not for external use — reconcile
   fills and clears refs; consumers only deref."
  (-fill-ref! [this c] "Point the ref at C (nil clears it).")
  (-ref-target [this] "The component the ref currently points at (nil
   when empty). Reconcile-side only — clearing a handle is correct only
   when it still targets the component being retired/abandoned."))

(defn ref
  "Create a ref handle: deref it (outside render bodies — handlers,
   effects) to reach the mounted record of the tree element that declares
   it as a :ref prop. Nil until reconcile first constructs that element,
   cleared when the element is disposed. Filled by reconciliation only —
   treat as read-only (a core reset! on this reify would silently no-op
   under babashka, so there is deliberately nothing to intercept: the
   type simply isn't an IAtom). One ref per element instance."
  []
  (let [cell (atom nil)]
    (reify
      DslRef
      (-fill-ref! [_ c] (reset! cell c) nil)
      (-ref-target [_] @cell)
      clojure.lang.IDeref
      (deref [_] @cell))))

(declare render)

(defn materialize-ref!
  "The component REF points at, compiling OWNER's tree first when the ref is
   still empty. An element's ref fills during a render pass (the body builds
   the element), so a caller that needs the instance *before* the host has
   painted — forwarding a key right after a branch switch, a test driving
   the component directly — would otherwise find nil and drop the input.
   CTX (the render context, a width for the TUI; default 80) only shapes the
   discarded render; structure is what the ref needs. Returns nil when the
   element is genuinely absent from the tree (a state that does not mount
   it), so callers decide what that means."
  ([backend owner ref] (materialize-ref! backend owner ref 80))
  ([backend owner ref ctx]
   (or (deref ref)
       (do (render backend owner ctx)
           (deref ref)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Construction context
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *construct-context*
  "Bound around a container's child reconcile: {:parent CONTAINER}. Backend
   ctors that must know their parent at construction time (Tk widget paths
   are parent-prefixed) read the parent here; the TUI ignores it."
  nil)

;; ═══════════════════════════════════════════════════════════════════════════
;; Observability (tui.md §2.5)
;; ═══════════════════════════════════════════════════════════════════════════
;; Behind --debug these surface the inline-callback trap (§2.5): a body
;; invoked on frames where nothing it derefs changed shows up as
;; bodies-run climbing while bodies-skipped stays flat.
(def ^:private zero-counters
  {:bodies-run 0 :bodies-skipped 0 :constructs 0 :reuses 0 :applies 0
   :disposals 0 :computes 0 :skips 0})

(defonce ^:private counters-atom (atom zero-counters))

(defn counters
  "Per-frame fn-invocation/reconcile counters ({:bodies-run :bodies-skipped
   :constructs :reuses :applies :disposals :computes :skips}). Process-wide;
   reset-counters! in tests."
  [] @counters-atom)

(defn reset-counters!
  "Back to all zeros (the immutable zero map is shared with the initial
   value — reset! swaps whole values, so no aliasing concerns)."
  []
  (reset! counters-atom zero-counters)
  nil)

(defn- bump! [k] (swap! counters-atom update k (fnil inc 0)))

(defn- known-tags [backend] (vec (sort (keys (:tags backend)))))

(defn- did-you-mean
  "The did-you-mean suffix for NAME among keyword CANDIDATES: the backend's
   :did-you-mean hook (the TUI one runs the fuzzy matcher), or empty."
  [backend name candidates]
  (if-some [f (:did-you-mean backend)]
    (f name candidates)
    ""))

(defn- unknown-tag!
  "Throw the unknown-tag error: names the tag, lists the backend's closed
   set, and suggests the nearest known tag when the backend offers a
   matcher."
  [backend tag]
  (throw (ex-info (str "kmet.libs.hikap: unknown tag " tag
                       ". Known tags: " (pr-str (known-tags backend)) "."
                       (did-you-mean backend (subs (str tag) 1)
                                     (keys (:tags backend))))
                  {:tag tag :known-tags (known-tags backend)})))

;; ═══════════════════════════════════════════════════════════════════════════
;; Parsing — tree nodes → desired items (validation happens HERE, loudly)
;; ═══════════════════════════════════════════════════════════════════════════

;; Desired/previous items share one shape:
;;   :kind   ::host | ::fncomp | ::record | ::entry | ::string
;;   :mkey   match-kind — what groups this item with its previous self
;;           (explicit key wins; else tag / fn value / payload identity /
;;           ::string constant)
;;   :key    explicit :key pseudo-prop (nil mostly)
;;   :ref    explicit :ref pseudo-prop (nil mostly)
;;   :c      the component (records: the payload; entries: the inner)
;;   :item   the raw object placed back into a container (:item for stack
;;           entry maps, :c for everything else)
;;   :owned  true iff the DSL constructed it — only owned items are disposed
;; plus construction inputs on desired items:
;;   ::host    :tag :spec :props :nodes
;;   ::fncomp  :f :props :ctree

(declare check-ref! flatten-nodes)

(defn- match-kind
  "The reconciliation match kind for a desired/previous item: an explicit
   :key wins, scoped by KIND so a keyed element never matches across kinds
   (keyed host ↔ fn ↔ alias remounts); without a key the head's own
   FALLBACK identity is the match kind (tag / fn value / alias keyword)."
  [kind fallback key]
  (if-some [k key] {::user-key k ::kind kind} fallback))

(defn- normalize-element
  "[TAG ...] → [spec props children]: an optional MAP right after the tag
   is the props; everything else is children. When the tag declares
   :primary, the FIRST child becomes that prop — with or without a props
   map ([:text \"hi\"] and [:text {:padding-x 0} \"hi\"] both fill :text);
   remaining children stay children. Pseudo-props :key/:ref are extracted
   separately — they belong to reconciliation, never to constructors.
   NB: defcomponent records ARE maps — a spliced component must never be
   mistaken for the props map; neither may a stack-entry map ({:component
   c} is never a legal prop shape)."
  [backend tag content]
  (let [spec (or (get (:tags backend) tag) (unknown-tag! backend tag))
        props-map? (let [first-content (first content)]
                     (and (map? first-content)
                          (not (record? first-content))
                          ;; {:component c} is the stack-entry shape —
                          ;; always a CHILD; consuming it as props would
                          ;; silently drop the element it wraps
                          (not (contains? first-content :component))))
        base-props (if props-map? (first content) {})
        children (if props-map? (rest content) content)
        primary? (:primary spec)
        take-primary? (boolean (and primary?
                                    (seq children)
                                    (or (not props-map?)
                                        (nil? (:lens spec)))))
        ;; a ref handle in the PRIMARY slot is almost certainly
        ;; [:widget (h/ref)] missing the props map — every other child
        ;; position rejects refs loudly, so must this one too (checked
        ;; against PRE-split children: take-primary consumes the head)
        _ (when (and take-primary? (satisfies? DslRef (first children)))
            (throw (ex-info
                    (str "kmet.libs.hikap: bare (hiccup/ref) as the value "
                         "child of " tag " — did you mean [" (name tag)
                         " {:ref ...} ...]?")
                    {:tag tag})))
        [props children] (if take-primary?
                           [(assoc base-props primary? (first children))
                            (rest children)]
                           [base-props children])]
    [spec (dissoc props :key :ref) (vec children)
     (select-keys base-props [:key :ref])]))

(declare make-component-fn)

(defn- parse-host
  "[:tag ...] → desired item. Validates before any construction: unknown
   tags and children-on-leaf throw here. META-KEY is the element vector's
   ^{:key k} metadata (nil when absent); an explicit :key prop wins over
   it, since props are the more specific form."
  [backend tag content meta-key]
  (let [[spec props children meta] (normalize-element backend tag content)
        key (or (:key meta) meta-key)]
    (when (and (seq children) (nil? (:lens spec)))
      (throw (ex-info
              (str "kmet.libs.hikap: children given to leaf tag " tag
                   " — leaves take only props")
              {:tag tag :children children})))
    (check-ref! tag (:ref meta))
    {:kind ::host :mkey (match-kind ::host tag key)
     :key key :ref (:ref meta)
     :tag tag :spec spec :props props :nodes children}))

(defn- check-ref!
  ":ref must be a handle from hiccup/ref — anything else (a keyword, a
   string) is a typo that would otherwise detonate later at fill time."
  [where r]
  (when (and (some? r) (not (satisfies? DslRef r)))
    (throw (ex-info
            (str "kmet.libs.hikap: :ref must be a (hiccup/ref) handle, got "
                 (pr-str r) (when (keyword? where) (str " on tag " where)))
            {:ref r}))))

(defn- unknown-alias!
  "Throw the unknown-alias error: names the alias, lists the registered
   aliases, and suggests the nearest one (did-you-mean, mirroring
   unknown-tag!). Qualified keywords never fall back to a host table."
  [backend tag]
  (let [known (vec (sort (keys (alias/registered))))]
    (throw (ex-info (str "kmet.libs.hikap: unknown alias " tag
                         ". Registered aliases: " (pr-str known) "."
                         (did-you-mean backend (subs (str tag) 1) known))
                    {:alias tag :registered-aliases known}))))

(defn- parse-alias
  "[:my.ns/chip {attrs} children…] → desired ::fncomp item dispatching
   through the alias registry (kmet.libs.hikap.alias). The attrs map is optional;
   :key/:ref are reconciliation pseudo-props exactly like other heads, and
   children ride :ctree — never a closure — so reuse can refresh them (a
   captured vector would freeze on reuse). The alias body is called
   (f attrs children) with [] when the element has none."
  [backend tag content meta-key]
  (when-not (alias/lookup tag)
    (unknown-alias! backend tag))
  (let [first-content (first content)
        props-map? (and (map? first-content)
                        (not (record? first-content))
                        ;; {:component c} is always a stack-entry child —
                        ;; consuming it as attrs silently drops the element
                        (not (contains? first-content :component)))
        base-props (if props-map? first-content {})
        ;; flat children (seqs spliced, nils dropped — the shape the DSL's
        ;; own child normalization produces), so an alias inspecting them
        ;; sees nodes, not one lazy seq
        raw-children (flatten-nodes (if props-map? (rest content) content) [])
        children (into [] (remove nil?) raw-children)
        key (or (:key base-props) meta-key)
        ref (:ref base-props)]
    (check-ref! tag ref)
    {:kind ::fncomp
     :mkey (match-kind ::alias tag key)
     :key key :ref ref
     :alias tag :f (alias/head tag)
     :props (dissoc base-props :key :ref)
     :ctree (when (seq children) children)}))

(defn- parse-node
  "One tree node → desired item, or nil to skip (nil nodes — the
   when/when-let support). Seqs never reach here (flattened earlier).
   An element's key may come from its props (:key) or, reagent-style, from
   the vector's own metadata (^{:key k} [:text …]) — props win; metadata on
   a non-vector child (record, string, entry map) is ignored, those match
   by identity or kind."
  [backend node stack?]
  (cond
    (nil? node) nil
    (string? node)
    {:kind ::string :mkey ::string :props {:text node}}
    (vector? node)
    (let [tag (first node)
          content (vec (rest node))
          meta-key (:key (meta node))]
      (cond
        (keyword? tag) (if (namespace tag)
                         (parse-alias backend tag content meta-key)
                         (parse-host backend tag content meta-key))
        (fn? tag)
        (let [props-map? (and (map? (first content))
                              (not (record? (first content))))
              base-props (if props-map? (first content) {})
              children (vec (if props-map? (rest content) content))
              key (or (:key base-props) meta-key)
              ref (:ref base-props)]
          (check-ref! tag ref)
          {:kind ::fncomp :mkey (match-kind ::fncomp tag key) :key key :ref ref
           :f tag :props (dissoc base-props :key :ref)
           :ctree (when (seq children) children)})
        :else (throw
               (ex-info
                (str "kmet.libs.hikap: invalid element head " (pr-str tag)
                     " — expected a keyword tag (host or alias) or a "
                     "function component")
                {:head tag}))))
    (record? node)
    {:kind ::record :mkey node :c node :item node :owned false}
    (and (some? (:component? backend)) ((:component? backend) node))
    ;; reified/deftype'd IComponent — same contract as the record splice:
    ;; identity preserved, owned elsewhere, never disposed by the tree
    {:kind ::record :mkey node :c node :item node :owned false}
    (map? node)
    (if (contains? node :component)
      (do
        (when-not stack?
          (throw
           (ex-info
            (str "kmet.libs.hikap: stack entry map {:component c} outside "
                 "a stack tag (:v-stack/:h-stack) — entries are stack "
                 "children only")
            {:entry node})))
        (when-some [entry-component (:entry-component backend)]
          {:kind ::entry :mkey (entry-component node)
           :c (entry-component node) :item node :owned false}))
      (throw
       (ex-info
        (str "kmet.libs.hikap: bare map child — maps pass through "
             "only as stack entries {:component c}; got "
             (pr-str (keys node)))
        {:map node})))
    :else (throw
           (ex-info (str "kmet.libs.hikap: cannot compile tree node "
                         (pr-str node)
                         " — for function roots use hiccup/root")
                    {:node node}))))

(defn- flatten-nodes
  "Splice seqs into a flat node list — VECTORS are single elements and
   must be checked FIRST (they are sequential too; checking sequential?
   alone would shred every element into its own head/props/content).
   Nils survive flattening; parse drops them."
  [nodes acc]
  (reduce (fn [acc n]
            (cond
              (vector? n) (conj acc n)
              (sequential? n) (flatten-nodes n acc)
              :else (conj acc n)))
          acc nodes))

(defn- parse-nodes
  "Tree nodes → desired items, seqs spliced, nils dropped, duplicate
   explicit keys throwing (two spliced siblings sharing a key makes reuse
   undefined — throwing beats silent subtree loss). STACK? licenses entry
   maps (true only for stack-tag children)."
  [backend nodes stack?]
  (loop [flat (flatten-nodes nodes [])
         parsed []
         seen #{}]
    ;; NB: seq-destructure, NOT if-some on the head — a nil CHILD must be
    ;; skipped, not end the parse
    (if-let [[n & more] (seq flat)]
      (if-some [d (parse-node backend n stack?)]
        (do
          (when (and (:key d) (contains? seen (:key d)))
            (throw (ex-info
                    (str "kmet.libs.hikap: duplicate :key " (pr-str (:key d))
                         " among siblings — keys must uniquely identify a "
                         "child for reuse")
                    {:key (:key d)})))
          (recur more (conj parsed d) (cond-> seen (:key d) (conj (:key d)))))
        (recur more parsed seen))
      parsed)))

(declare reconcile!)

;; The unchanged-subtree skip (tui.md §2.3): a body that re-ran and derived
;; the SAME tree, or a container whose children are unchanged, must not be
;; re-parsed and re-diffed. clojure.core/= ignores metadata, and an element's
;; ^{:key k} metadata IS identity — a key change on =-equal content must not
;; take the skip, or a stateful child would keep the instance its key said
;; goodbye to. Keys written as props (:key) are part of the content already.

(def ^:private no-tree
  "Initial last-tree: never =-equal to a valid tree (keywords are not tree
   nodes), so the first pass always reconciles."
  ::no-tree)

(defn- keys-same?
  "Do =-equal values A and B carry the same :key element metadata? Only
   entry vectors carry keys, but the walk descends any sequential pair, so a
   key on a vector spliced via a seq is compared too. Maps (props, stack
   entries) are not descended — keys cannot live in them."
  [a b]
  (if-not (and (sequential? a) (sequential? b))
    true
    (and (or (and (not (vector? a)) (not (vector? b)))
             (= (:key (meta a)) (:key (meta b))))
         (loop [xs (seq a)
                ys (seq b)]
           (cond
             (nil? xs) true
             (keys-same? (first xs) (first ys)) (recur (next xs) (next ys))
             :else false)))))

(defn- unchanged-tree?
  "True when NEW can take PREV's reconciled subtree as-is: =-equal and with
   matching element keys (clojure.core/= ignores metadata; see the section
   comment for why a key change must defeat the skip)."
  [new prev]
  (and (= new prev)
       (keys-same? new prev)))

;; ═══════════════════════════════════════════════════════════════════════════
;; ComponentFn — the fn-component wrapper
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord ComponentFn [backend f props kids store rx ctree last-ctx last-tree])

(defn render-component
  "Run one render pass for the ComponentFn THIS at context CTX: re-derive
   its reaction when a tracked dep changed, reconcile its tree, then hand
   the kept children to the backend's :render-items. Backends wrap this in
   their own component protocol (kmet.tui.hiccup extends IComponent to
   ComponentFn; a toolkit that paints no output just calls it)."
  [this ctx]
  (let [backend (:backend this)
        {:keys [f props kids store rx ctree last-ctx last-tree]} this
        bind (or (:bind-context backend) (fn [_component _ctx thunk] (thunk)))]
    (bind this ctx
          (fn []
            ;; Uncached by design (transparent-parent allowlist): the
            ;; memoization boundary is the REACTION — deps auto-discovered
            ;; at deref time, and an idle reaction hands back its cached
            ;; tree without running the body (zero fn invocations on frames
            ;; where nothing changed).
            (let [rx (or @rx
                         (let [nr (r/make-reaction
                                   (fn []
                                     (component/with-store store
                                       (component/begin-pass! store)
                                       (bump! :bodies-run)
                                       (let [p (r/tracked-deref props)
                                             ;; tracked: a parent adding/removing the
                                             ;; element's children changes CTREE alone,
                                             ;; with equal props — must still re-derive
                                             ct (r/tracked-deref ctree)
                                             tree (if ct (f p ct) (f p))]
                                         ;; =-equal output keeps KIDS — the
                                         ;; unchanged-subtree skip. The items
                                         ;; still render at this pass's context.
                                         (if (unchanged-tree? tree @last-tree)
                                           (do (bump! :skips) @kids)
                                           (let [kept (reconcile! backend kids tree)]
                                             (reset! last-tree tree)
                                             kept)))))
                                   {:auto-run? (fn [_] (component/schedule-frame!))
                                    ;; Bodies reading only untracked values (bare
                                    ;; @app-atom closures, static trees) must never
                                    ;; cache stale output — they re-derive per pass,
                                    ;; exactly like the pre-stage-3 batched root.
                                    ;; Consequence, pinned by test: bodies whose ONLY
                                    ;; inputs are props/ctree also re-derive each pass
                                    ;; (correct — the props watch still dirties on real
                                    ;; changes; equal-prop frames just cost a body run
                                    ;; + diff). Reactive inputs beyond these go through
                                    ;; cursors, slices, or tracked-deref and get narrow
                                    ;; memoization for free.
                                    :rerun-without-deps? true
                                    :implicit-deps [props ctree]})]
                           (reset! rx nr)
                           nr))]
              ;; The context participates in memoization: it shapes body
              ;; output but is a dynamic var, not a tracked dep — so a
              ;; context change forces one re-derive of an idle reaction,
              ;; which then re-caches (track!'s per-context cache contract,
              ;; one level up).
              (when (and (not= ctx @last-ctx)
                         (= :idle (:state (r/reaction-state rx))))
                (r/invalidate! rx))
              (reset! last-ctx ctx)
              (when (= :idle (:state (r/reaction-state rx)))
                (bump! :bodies-skipped))
              ;; Bring current: unrun/dirty → the body reconciles
              ;; synchronously; idle → the cached tree's items render
              ;; without re-running it.
              (deref rx)
              ((:render-items backend) @kids ctx))))))

(defn dispose-component
  "Dispose a ComponentFn (idempotent): children first — their cleanups
   still see intact parent state — then the reaction (unwatches deps),
   then the store's cleanups LIFO, leaving nothing registered."
  [this]
  (let [backend (:backend this)
        {:keys [kids rx store last-tree]} this]
    (doseq [it @kids :when (:owned it)]
      ((:dispose! backend) (:c it)))
    (when-some [r @rx]
      (r/dispose! r))
    ;; Release the retained previous tree even if something still holds
    ;; the record (the children are disposed above).
    (reset! last-tree nil)
    (component/destroy-store! store)))

(defn make-component-fn
  "Wrap F in a ComponentFn for BACKEND. CTREE is the element's raw child
   tree — when present, F is called as (f props children) instead of
   (f props)."
  ([backend f] (make-component-fn backend f nil))
  ([backend f ctree]
   (map->ComponentFn {:backend backend
                      :f f
                      :props (atom {})
                      :kids (atom [])
                      :store (component/new-store)
                      :rx (atom nil)
                      :ctree (atom ctree)
                      :last-ctx (atom nil)
                      :last-tree (atom no-tree)})))

;; ═══════════════════════════════════════════════════════════════════════════
(declare stamped-meta remember-ref! reconcile-into)

;; ═══════════════════════════════════════════════════════════════════════════
;; The diff — one keyed reconcile for wrapper children AND container fill
;; ═══════════════════════════════════════════════════════════════════════════

(defn- itemize-prev
  "Raw stored child (from a container's lens or a wrapper's kid list) →
   previous item. Kind/ownership inferred: our stamps mark DSL-built
   hosts, ComponentFn instances are always ours, foreign records/entries
   are never owned (and so never disposed by reconcile)."
  [backend raw]
  (cond
    (and (map? raw) (contains? raw :component))
    (let [entry-component (:entry-component backend)]
      {:kind ::entry :mkey (entry-component raw)
       :c (entry-component raw) :item raw :owned false})
    ;; our stamps come FIRST: an explicit :key must override the
    ;; tag/fn fallback on the previous side too, or keyed matching dies
    ;; at the second pass (::fncomponent marks fn wrappers, :alias marks
    ;; the ones built from an alias element — mkey is the alias keyword,
    ;; which the desired-side parse produces too)
    (and (record? raw) (some? (:dsl/meta raw)))
    (let [{:keys [tag key ref] alias-kw :alias} (stamped-meta raw)]
      (if (= tag ::fncomponent)
        (if-some [a alias-kw]
          {:kind ::fncomp :mkey (match-kind ::alias a key) :key key :ref ref
           :c raw :item raw :owned true}
          {:kind ::fncomp :mkey (match-kind ::fncomp (:f raw) key) :key key :ref ref
           :c raw :item raw :owned true})
        {:kind ::host :mkey (match-kind ::host tag key) :key key :ref ref
         :c raw :item raw :owned true}))
    (instance? ComponentFn raw)
    {:kind ::fncomp :mkey (:f raw) :c raw :item raw :owned true}
    (record? raw)
    {:kind ::record :mkey raw :c raw :item raw :owned false}
    (and (some? (:component? backend)) ((:component? backend) raw))
    ;; reified/deftype'd component stored by a previous pass — foreign,
    ;; like the record splice it mirrors
    {:kind ::record :mkey raw :c raw :item raw :owned false}
    :else
    (throw (ex-info
            (str "kmet.libs.hikap: unexpected child in container storage: "
                 (pr-str raw))
            {:child raw}))))

(defn- release-ref!
  "Clear HANDLE only while it still points at C. `diff-items` constructs a
   keyed remount (filling the new instance's ref) before retiring the
   leftover, and a handle can be handed to another element in one walk — an
   old holder's exit must not wipe a handle that is no longer its own."
  [c handle]
  (when (and (some? handle) (identical? c (-ref-target handle)))
    (-fill-ref! handle nil)))

(defn- retire-item!
  "Dispose an owned previous item: release its ref handle, drop the
   reconciler bookkeeping (`:ref`, and `:nodes` — the retained previous
   tree), then dispose. Foreign items are left alone: owned elsewhere,
   merely leaving the child list."
  [backend {:keys [c ref owned]}]
  (when owned
    (bump! :disposals)
    (release-ref! c ref)
    (when-some [m (:dsl/meta c)]
      (swap! m assoc :ref nil :nodes nil))
    ((:dispose! backend) c)))

(defn- stamp!
  "Mark a freshly-constructed component as DSL-owned. The stamp is an ATOM
   (record identity must never change, yet reconciliation must remember
   per-instance facts across passes): :tag kinds the component, :props the
   leaf reuse fast-path comparison, :key the explicit key (match-kind must
   survive into the next pass), :ref the last ref handle pointed at this
   instance, :nodes a container's last raw children (the unchanged-subtree
   skip's comparison base). All are released when the instance leaves its
   tree. Records tolerate the extra key."
  ([c tag props] (stamp! c tag props nil))
  ([c tag props key]
   (assoc c :dsl/meta (atom {:tag tag :props props :key key :ref nil
                             :nodes nil}))))

(defn- stamped-meta
  "The deref'd DSL stamp of C (callers guarantee presence)."
  [c]
  @(:dsl/meta c))

(defn- remember-ref!
  "Record R as C's live ref handle (so removal can clear it later)."
  [c r]
  (when-some [m (:dsl/meta c)]
    (swap! m assoc :ref r)))

(defn- construct-item
  "Build a fresh component for a desired item (construction inputs on the
   item; containers recursively reconcile their children here — licensed
   for entry maps by the TAG's own :entries?, never the parent context)."
  [backend {:keys [kind tag spec props nodes f ctree c item] :as d}]
  (case kind
    ::host
    (let [comp (stamp! ((:ctor spec) props) tag props (:key d))]
      (bump! :constructs)
      (when (and (:lens spec) (seq nodes))
        (reconcile-into backend (:lens spec) comp nodes (boolean (:entries? spec))))
      (when (:lens spec)
        ;; Remember the raw children the reconcile just installed: the next
        ;; pass's =-equal nodes take the unchanged-subtree skip.
        (swap! (:dsl/meta comp) assoc :nodes nodes))
      (when-some [r (:ref d)]
        (-fill-ref! r comp)
        (remember-ref! comp r))
      {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
       :c comp :item comp :owned true})
    ::fncomp
    (let [wrapper (assoc (make-component-fn backend f ctree)
                         :dsl/meta (atom (cond-> {:tag ::fncomponent
                                                  :key (:key d) :ref nil}
                                           (:alias d) (assoc :alias (:alias d)))))]
      (reset! (:props wrapper) props)
      (bump! :constructs)
      (when-some [r (:ref d)]
        (-fill-ref! r wrapper)
        (remember-ref! wrapper r))
      {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
       :c wrapper :item wrapper :owned true})
    ::string
    (let [comp (stamp! ((:text-leaf backend) (:text props) props)
                       ::string props (:key d))]
      (bump! :constructs)
      {:kind kind :mkey ::string :c comp :item comp :owned true})
    ;; ::record / ::entry pass through — never constructed, never owned
    {:kind kind :mkey (:mkey d) :key (:key d) :ref (:ref d)
     :c c :item item :owned false}))

(defn- reuse-or-build
  "Matched pair → [kept-item retired-prev?]. Display leaves rebuild when
   their props changed (identity-free; the equal-props fast path keeps
   unchanged frames free); everything else keeps the previous instance.
   A tag with an :apply spec gets the third path: props changed, but the
   live instance can express them — patch in place and keep it (state and
   focus survive), falling back to rebuild when :apply declines."
  [backend d prev]
  (letfn [(keep [c]
            {:item {:kind (:kind d) :mkey (:mkey d) :key (:key d)
                    :ref (:ref d) :c c :item c :owned true}
             :retire nil})
          (fill-ref! [c]
            ;; The element's PREVIOUS handle is stale once the element
            ;; declares a different one (or none): release it — guarded, it
            ;; may already point at another element. The stamp remembers the
            ;; handle the element currently owns so removal can release it
            ;; later (retire-item!).
            (let [prev-ref (:ref (stamped-meta c))
                  new-ref (:ref d)]
              (when-not (identical? prev-ref new-ref)
                (release-ref! c prev-ref)
                (remember-ref! c new-ref))
              (when-some [r new-ref]
                (-fill-ref! r c))))]
    (case (:kind d)
      ::host
      (let [container? (some? (:lens (:spec d)))
            apply-fn (:apply (:spec d))
            prev-meta (stamped-meta (:c prev))
            prev-props (:props prev-meta)
            props-same? (= (:props d) prev-props)]
        (cond
          ;; Containers always keep their instance and reconcile their
          ;; children in place; their structural props patch through :apply
          ;; when the tag declares one (§4's props/state migration —
          ;; padding, gap), staying as constructed otherwise. A container
          ;; must never take the rebuild branch: a fresh construct starts
          ;; with an empty child pool, so the whole subtree (and all
          ;; descendant state) would be lost. =-equal children take the
          ;; unchanged-subtree skip instead of re-parsing/re-diffing them;
          ;; a changed prop still applies below.
          container?
          (let [c (:c prev)
                nodes (:nodes d)]
            (bump! :reuses)
            (if (unchanged-tree? nodes (:nodes prev-meta))
              (bump! :skips)
              (do (reconcile-into backend (:lens (:spec d)) c nodes
                                  (boolean (:entries? (:spec d))))
                  (swap! (:dsl/meta c) assoc :nodes nodes)))
            (when (and apply-fn (not props-same?)
                       (apply-fn c prev-props (:props d)))
              (bump! :applies)
              (swap! (:dsl/meta c) assoc :props (:props d)))
            (fill-ref! c)
            (keep c))

          props-same?
          (do (bump! :reuses)
              (fill-ref! (:c prev))
              (keep (:c prev)))

          ;; changed props on a patchable tag: patch the live instance and
          ;; keep it. The stamp's :props is the next pass's comparison
          ;; base, so the patched instance must remember the new props.
          (and apply-fn (apply-fn (:c prev) prev-props (:props d)))
          (do (bump! :applies)
              (swap! (:dsl/meta (:c prev)) assoc :props (:props d))
              (fill-ref! (:c prev))
              (keep (:c prev)))

          ;; leaf with different props: rebuild. Retire FIRST — the old
          ;; instance's stamp holds the same ref handle the fresh construct
          ;; is about to fill; retiring afterwards would wipe it. Returns
          ;; the full new item map (no retire left for the caller).
          :else
          (do (retire-item! backend prev)
              {:item (construct-item backend d) :retire nil})))
      ::fncomp
      (let [w (:c prev)]
        (bump! :reuses)
        (reset! (:props w) (:props d))
        (reset! (:ctree w) (:ctree d))
        (fill-ref! w)
        (keep w))
      ;; ::string — a display leaf like a host leaf: rebuild when the text
      ;; changed (the parsed item has no :c; falling through to the
      ;; passthrough below would install a nil child), keep otherwise
      ::string
      (let [prev-props (:props (stamped-meta (:c prev)))]
        (if (= (:props d) prev-props)
          (do (bump! :reuses)
              (keep (:c prev)))
          (do (retire-item! backend prev)
              {:item (construct-item backend d) :retire nil})))
      ;; ::record / ::entry — passthrough, take the DESIRED payload (the
      ;; bucket matched structically; the tree's own object is canonical)
      {:item {:kind (:kind d) :mkey (:mkey d) :key (:key d) :ref (:ref d)
              :c (:c d) :item (:item d) :owned false}
       :retire nil})))

(defn- split-bucket
  "Pop the oldest previous item off MKEY's bucket (order-stable matching
   within a match-kind: i-th desired ↔ i-th previous). Buckets carry a
   consumption cursor, so popping is O(1) — the previous `(vec (rest ...))`
   copy made long unkeyed lists superlinear."
  [buckets mkey]
  (if-some [{:keys [v i]} (get buckets mkey)]
    (if (< i (count v))
      [(nth v i) (assoc buckets mkey {:v v :i (inc i)})]
      [nil buckets])
    [nil buckets]))

(defn- diff-items
  "The keyed diff (tui.md §2.3): walk desired items in order, reusing the
   matching previous item per match-kind bucket; leftovers are retired
   (disposed iff owned, ref cleared). Returns the kept items in desired
   order."
  [backend prev-items desired]
  (let [buckets (volatile!
                 (reduce (fn [m it]
                           (update m (:mkey it)
                                   (fn [b]
                                     (if b
                                       (update b :v conj it)
                                       {:v [it] :i 0}))))
                         {} prev-items))
        out (volatile! [])]
    (doseq [d desired]
      (let [[prev buckets'] (split-bucket @buckets (:mkey d))]
        (vreset! buckets buckets')
        (if prev
          (let [{:keys [item retire]} (reuse-or-build backend d prev)]
            (when retire (retire-item! backend retire))
            (vswap! out conj item))
          (vswap! out conj (construct-item backend d)))))
    (doseq [{:keys [v i]} (vals @buckets)
            prev (subvec v i)]
      (retire-item! backend prev))
    (vec @out)))

(defn- reconcile-into
  "Reconcile NODES into CONTAINER through SPEC's lens: read the current
   raw children, diff, install the result back. The one children
   mechanism — used by host containers at every nesting level (tui.md §2.3)."
  [backend lens container nodes stack?]
  (binding [*construct-context* {:parent container}]
    (let [prev (mapv (fn [raw] (itemize-prev backend raw)) ((:get lens) container))
          kept (diff-items backend prev (parse-nodes backend nodes stack?))]
      ((:put lens) container kept))))

(defn- as-roots
  "TREE → sequence of root nodes. An element VECTOR is ONE root; only a
  seq splices as multiple roots (vectors ARE sequential — check vector?
  first; this exact trap produced three bugs during stage 3)."
  [tree]
  (cond
    (nil? tree) []
    (vector? tree) [tree]
    (sequential? tree) tree
    :else [tree]))

(defn reconcile!
  "Diff TREE against KIDS-ATOM's current items and install the result —
   the ComponentFn render pass (tui.md §2.3). TREE may be one element, a
   sequence of roots, or nil. Returns the kept items."
  [backend kids-atom tree]
  (let [kept (diff-items backend @kids-atom
                         (parse-nodes backend (as-roots tree) false))]
    (reset! kids-atom kept)
    kept))

;; ═══════════════════════════════════════════════════════════════════════════
;; Public compile + render surfaces
;; ═══════════════════════════════════════════════════════════════════════════

(defn compile-element
  "Compile one tree node into a live component (fresh construction —
   reconcile against an empty previous pool). Returns nil for nil.
   Mounted components pass through untouched (records and reified
   IComponent instances alike — identity preserved); stack-entry maps
   ({:component c ...}) are rejected outside stack tags; strings become
   bare zero-padding Text. A SEQ input throws — splicing multiple roots
   is compile-tree's business."
  [backend el]
  (when (and (sequential? el) (not (vector? el)))
    (throw (ex-info
            (str "kmet.libs.hikap: seq passed to compile-element — a seq "
                 "splices as CHILDREN of an element or ROOTS via compile-tree")
            {:node el})))
  (let [parsed (parse-nodes backend [el] false)]
    (when (seq parsed)
      (:c (first (diff-items backend [] parsed))))))

(defn compile-tree
  "Compile a whole tree: one element VECTOR, or a sequence of elements
   spliced as multiple roots (a list/seq — a vector is always ONE element).
   Returns a single component, a vector of components, or nil."
  [backend tree]
  ;; nil → as-roots [] → zero items → nil: the empty case falls through
  (let [items (diff-items backend [] (parse-nodes backend (as-roots tree) false))]
    (case (count items)
      0 nil
      1 (:c (first items))
      (mapv :c items))))

(defn render
  "Render a compiled component (or a sequence of them) through the backend
   at context CTX. The TUI returns lines; a widget backend renders nothing."
  [backend compiled ctx]
  (cond
    (nil? compiled) []
    (sequential? compiled) ((:render-all backend) compiled ctx)
    :else ((:render backend) compiled ctx)))

(defn- owned?
  "DSL-constructed: fn wrappers always, host tags via their stamp. The
   stamp check is guarded by record? — a spliced reified component isn't
   associative and contains? would throw."
  [c]
  (or (instance? ComponentFn c)
      (and (record? c) (contains? c :dsl/meta))))

(defn dispose-tree!
  "Dispose every DSL-owned component inside COMPS — a compiled component,
   a sequence of them, or nil. Foreign spliced records are left alone.
   Complements compile-tree for lifetimes OUTSIDE the reconcile pool:
   widget factories and dialog builders compile once, hold the result,
   and call this when replaced or closed (the widget contract's :dispose
   hook is the natural trigger)."
  [backend comps]
  (doseq [c (cond
              (nil? comps) []
              (sequential? comps) comps
              :else [comps])
          :when (owned? c)]
    ((:dispose! backend) c)))

(defn compute
  "A derived reactive ref over DEPS (tui.md §3.3): kmet.libs.reakt/derive
   plus two conveniences — creation counting (--debug :computes) and
   auto-disposal when created during a component render pass
   (kmet.libs.hikap.component/*store* bound under with-let): the reaction is
   disposed with the component, so per-instance computes need no manual
   cleanup. Top-level shared computes (no enclosing store) live forever,
   which is the point. Create computes ONCE per instance — one built bare
   inside a component body leaks a reaction per pass, visible as :computes
   climbing in the counters. See kmet.libs.reakt/derive for the full
   semantics (dep seeding, =-gated notification, batched re-runs)."
  [deps f]
  ;; Count creations: computes are created ONCE per instance (top-level def
  ;; or with-let init). A compute built bare inside a component body leaks a
  ;; reaction per render pass — the counter climbing frame over frame makes
  ;; that visible.
  (let [_ (bump! :computes)
        rx (r/derive deps f)]
    (when component/*store*
      (component/register-cleanup!
       (gensym "compute-")
       #(r/dispose! rx)))
    rx))

;; root — the one mount path
;; ═══════════════════════════════════════════════════════════════════════════

(defn root
  "Mount TREE-OR-FN as a component for BACKEND — the one way trees enter a
   toolkit. An element (or sequence of elements) is compiled/reconciled on
   render passes; a vector whose first item is itself a vector counts as
   that sequence of roots; a bare FN is shorthand for [f {}] — called with
   the props map each time its reaction re-runs, so closures over app atoms
   are tracked at deref time and the body only re-runs when they actually
   change (memoized; the idle UI runs zero bodies). The owner mounts the
   returned record anywhere the backend accepts a component and calls
   dispose when it leaves."
  [backend tree-or-fn]
  (if (fn? tree-or-fn)
    ;; bare fn: shorthand for [f {}] — mounted directly, no extra wrapper
    (make-component-fn backend tree-or-fn)
    (let [tree (if (and (vector? tree-or-fn)
                        (vector? (first tree-or-fn)))
                 ;; vector of element vectors = spliced roots
                 (seq tree-or-fn)
                 tree-or-fn)]
      (make-component-fn backend (constantly tree)))))
