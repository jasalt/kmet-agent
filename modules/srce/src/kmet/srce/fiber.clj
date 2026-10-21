(ns kmet.srce.fiber
  "Mounting, the refresh pass, the disposal axes, and plugin runtimes.

   Every mutation that runs plugin code stays on the thread that owns the root;
   the one concurrency primitive is the optimistic pass flag in the shared
   :kernel map. A mutation that only appends (provide!, on, effect) is safe but
   late from a foreign thread; it records and the owner's next `settle!` applies
   it."
  (:require [kmet.srce.ctx :as ctx]
            [kmet.srce.event :as event]))

(def ^:private max-rounds
  "The pass budget. A provider chain that has not settled after eight rounds is
   a churn: warn, clear the change set, stop."
  8)

;; ── inject ────────────────────────────────────────────────────────────────

(defn normalize-inject
  "Normalize a plugin's :inject declaration to `{name nil|:optional}`. A vector
   is all-required; a map's values are nil (required) or :optional. Any other
   value throws :srce/bad-inject: the object form's values are reserved for the
   intercept config a future landing may bring back."
  [deps]
  (cond
    (nil? deps) {}
    (sequential? deps) (into {} (map (fn [n] [n nil])) deps)
    (map? deps) (reduce-kv (fn [m k v]
                             (cond
                               (nil? v) (assoc m k nil)
                               (= :optional v) (assoc m k :optional)
                               :else
                               (throw (ex-info (str "reserved :inject value: " (pr-str [k v]))
                                               {:type :srce/bad-inject
                                                :name k
                                                :value v
                                                :problems [k]}))))
                           {} deps)
    :else (throw (ex-info (str "invalid :inject: " (pr-str deps))
                          {:type :srce/bad-inject :value deps :problems [deps]}))))

;; ── plugins ───────────────────────────────────────────────────────────────

(defn plugin-source
  "Resolve a plugin source: a var passes through; a keyword is looked up by
   RESOLVER. A miss or an unsupported source throws :srce/bad-source. The kernel
   never decides what a keyword means."
  [source resolver]
  (cond
    (var? source) source
    (keyword? source) (if-let [value (and resolver (resolver source))]
                        value
                        (throw (ex-info (str "unresolved plugin source: " (pr-str source))
                                        {:type :srce/bad-source :source source})))
    :else (throw (ex-info (str "invalid plugin source: " (pr-str source))
                          {:type :srce/bad-source :source source}))))

(defn plugin-problems
  "The shape problems of PLUGIN as a vector (empty when valid). The bad :inject
   problems are included, so `plugin!` reports one map-level error."
  [plugin]
  (if-not (map? plugin)
    [:map]
    (let [inject-problems (try
                            (normalize-inject (:inject plugin))
                            nil
                            (catch Throwable t
                              (if (= :srce/bad-inject (:type (ex-data t)))
                                (vec (:problems (ex-data t)))
                                (throw t))))]
      (cond-> []
        (not (keyword? (:name plugin))) (conj :name)
        (not (fn? (:apply plugin))) (conj :apply)
        (not (or (nil? (:inject plugin))
                 (map? (:inject plugin))
                 (sequential? (:inject plugin)))) (conj :inject)
        (seq inject-problems) (into inject-problems)
        (not (or (nil? (:config plugin)) (fn? (:config plugin)))) (conj :config)
        (not (or (nil? (:provide plugin))
                 (set? (:provide plugin))
                 (sequential? (:provide plugin)))) (conj :provide)))))

(defn validate-plugin!
  "Throw :srce/bad-plugin with :problems unless PLUGIN is a valid plugin map.
   Returns PLUGIN."
  [plugin]
  (let [problems (plugin-problems plugin)]
    (when (seq problems)
      (throw (ex-info (str "invalid plugin: " (pr-str plugin) " " (pr-str problems))
                      {:type :srce/bad-plugin :problems problems})))
    plugin))

(defn- resolve-plugin
  "The validated plugin map for SOURCE: a map as-is, a var dereferenced, a
   keyword through the :resolver."
  [ctx source]
  (let [resolved (if (map? source)
                   source
                   (let [value (plugin-source source (ctx/attr ctx :resolver))]
                     (if (var? value) @value value)))]
    (validate-plugin! resolved)))

;; ── runtimes ──────────────────────────────────────────────────────────────

(defn runtimes
  "Every plugin runtime, mount order."
  [ctx]
  @(:runtimes (:kernel ctx)))

(defn runtime
  "The runtime for SOURCE, or nil."
  [ctx source]
  (some (fn [rt] (when (= source (:source rt)) rt)) (runtimes ctx)))

(defn runtime?
  "Is a runtime mounted for this source?"
  [ctx source]
  (some? (runtime ctx source)))

(defn- runtime-for!
  [ctx source plugin]
  (or (runtime ctx source)
      (let [rt {:name (:name plugin)
                :source source
                :config (:config plugin)
                :fibers (atom [])}]
        (swap! (:runtimes (:kernel ctx)) conj rt)
        rt)))

(defn- drop-runtime!
  "Drop FIBER from its runtime, and the runtime itself once its last fiber is
   gone. A later plugin! recreates it."
  [ctx fiber]
  (when-let [rt (:runtime @fiber)]
    (swap! (:fibers rt) (fn [v] (filterv #(not (identical? % fiber)) v)))
    (when (empty? @(:fibers rt))
      (swap! (:runtimes (:kernel ctx))
             (fn [v] (filterv #(not (identical? % rt)) v)))))
  nil)

;; ── the pass ──────────────────────────────────────────────────────────────

(defn- providers
  "The provider set (the epoch): {injected-name provider-uid | :srce/absent}. An
   optional dependency participates like a required one — absent contributes
   :srce/absent — which is what makes it part of the epoch and not a no-op."
  [ctx inject]
  (reduce-kv (fn [m name _]
               (assoc m name
                      (if-let [p (ctx/provider ctx name)]
                        (if (ctx/available? p)
                          (:uid @(:fiber p))
                          :srce/absent)
                        :srce/absent)))
             {} inject))

(defn- set-state!
  "Set FIBER's state and emit :srce/status with the previous one. Transitions
   run on the owner thread, so the read-then-swap needs no CAS."
  [fiber to]
  (let [from (:state @fiber)]
    (swap! fiber assoc :state to)
    (when (not= from to)
      (event/emit! (:ctx @fiber) :srce/status {:fiber fiber :from from :to to})))
  nil)

(defn- check-provides!
  "Compare the declared :provide with what the fiber actually provided. A
   mismatch is a warning, not a failure: a plugin may provide conditionally."
  [fiber]
  (let [{:keys [ctx plugin provides]} @fiber
        declared (set (:provide plugin))
        actual (set provides)]
    (when-not (= declared actual)
      (ctx/log! ctx :warn "plugin :provide does not match what it provided"
                {:plugin (:name @fiber)
                 :declared declared
                 :provided actual
                 :declared-not-provided (into #{} (remove actual) declared)
                 :provided-not-declared (into #{} (remove declared) actual)}))))

(defn- fail!
  "A throwing apply/config: mark :failed first (so its own disposers cannot
   register), unwind the partial effects — their provide! disposers free the
   names — then report."
  [fiber error]
  (let [{:keys [ctx name]} @fiber]
    (swap! fiber assoc :error error)
    (set-state! fiber :failed)
    (ctx/unwind-effects! fiber)
    (event/emit! ctx :srce/failure {:fiber fiber :error error})
    (ctx/log! ctx :error "plugin failed" name error)
    nil))

(defn- activate!
  "Satisfied? run apply with the resolved config; otherwise stay :pending with
   the required names it waits for (an optional :inject never holds it). The
   state is set before the plugin body runs, so the body may provide and
   register. apply's return value is its first effect."
  [fiber]
  (let [{:keys [ctx inject plugin config]} @fiber
        epoch (providers ctx inject)
        missing (keep (fn [[name optional]]
                        (when (and (nil? optional)
                                   (= :srce/absent (get epoch name)))
                          name))
                      inject)]
    (if (seq missing)
      (do
        (swap! fiber assoc :epoch epoch :waiting-for (set missing))
        (set-state! fiber :pending))
      (do
        (swap! fiber assoc :epoch epoch :waiting-for #{})
        (try
          (let [cfg (if-let [validate (:config plugin)]
                      (validate config)
                      config)]
            (swap! fiber assoc :error nil)
            (set-state! fiber :active)
            (let [result ((:apply plugin) ctx cfg)]
              (when (some? result)
                (ctx/effect ctx (constantly result) (str "apply " (:name @fiber)))))
            (check-provides! fiber))
          (catch Throwable t
            (fail! fiber t)))))
    fiber))

(defn- unload!
  "Unwind every effect LIFO; the state must already be non-:active. The provide!
   disposers delete their store entries and record their names as changes."
  [fiber]
  (let [{:keys [ctx provides]} @fiber]
    (ctx/unwind-effects! fiber)
    (when (seq provides)
      (ctx/record-change! ctx provides)))
  nil)

(defn- reload!
  "The pass's remount: same fiber, same uid, config re-validated."
  [fiber epoch]
  (swap! fiber assoc :epoch epoch)
  (set-state! fiber :pending)
  (unload! fiber)
  (activate! fiber))

(defn- refresh!
  "One fiber, one decision: an unchanged provider set is a no-op (so a :failed
   fiber is never retried by the pass); a changed one reloads."
  [fiber]
  (let [{:keys [ctx inject state epoch]} @fiber]
    (when-not (= :disposed state)
      (let [next-epoch (providers ctx inject)]
        (when (not= next-epoch epoch)
          (reload! fiber next-epoch)))))
  nil)

(defn- take-changes!
  "Atomically drain the change set. A concurrent append of a name we just took
   is harmless (it is being processed now); a different name survives."
  [changes]
  (loop []
    (let [v @changes]
      (if (compare-and-set! changes v [])
        (set v)
        (recur)))))

(defn- run-pass!
  "Recompute the provider set of every fiber the change set can affect, remount
   what changed, and repeat to a fixpoint. Round 0 also re-checks pending
   fibers, which is how a :check that turns true is noticed."
  [ctx]
  (let [{:keys [mounted changes]} (:kernel ctx)]
    (loop [round 0]
      (let [names (take-changes! changes)
            candidates (filterv (fn [fiber]
                                  (let [{:keys [inject state]} @fiber]
                                    (and (not= :disposed state)
                                         (or (and (zero? round) (= :pending state))
                                             (some #(contains? inject %) names)))))
                                @mounted)]
        (doseq [fiber candidates]
          (refresh! fiber))
        (cond
          (and (seq @changes) (< (inc round) max-rounds))
          (recur (inc round))

          (seq @changes)
          (do
            (ctx/log! ctx :warn ":srce/churn" {:rounds (inc round)
                                               :pending (count @changes)})
            (reset! changes [])))))
    nil))

(declare settle!)

(defn- run-owned!
  "Run F while holding the pass flag, so plugin code that mutates only records,
   then drain once at the end. Without the flag F runs as-is: its change is
   late, never corrupting."
  [ctx f]
  (let [pass (:pass (:kernel ctx))]
    (if (compare-and-set! pass nil :running)
      (try
        (f)
        (finally
          (reset! pass nil)
          (settle! ctx)))
      (f))))

(defn settle!
  "Run any pending provider change to a fixpoint. Idempotent; the app calls it
   at the turn boundary and after every cross-thread submission. When it returns
   and no other thread owns the pass, :changes is empty."
  [ctx]
  (let [{:keys [pass changes]} (:kernel ctx)]
    (loop []
      (when (compare-and-set! pass nil :running)
        (try
          (run-pass! ctx)
          (finally
            (reset! pass nil))))
      (when (and (seq @changes) (nil? @pass))
        (recur))))
  nil)

(defn- poke!
  "Apply a recorded change now unless a pass is already running; a nested or
   foreign change belongs to the running pass's owner."
  [ctx]
  (when (nil? @(:pass (:kernel ctx)))
    (settle! ctx))
  nil)

;; ── services ──────────────────────────────────────────────────────────────

(defn provide!
  "Register VALUE as NAME for the current ctx's realm. Throws
   :srce/duplicate-service when the name is already held on the way up (an
   entry held by a non-active fiber still occupies its name). Returns a
   single-shot disposer."
  ([ctx name value]
   (provide! ctx name value nil))
  ([ctx name value opts]
   (let [fiber (:fiber ctx)]
     (ctx/assert-active! fiber)
     (when (ctx/provider ctx name)
       (throw (ex-info (str "service " name " is already provided in this realm path")
                       {:type :srce/duplicate-service :name name})))
     (ctx/effect ctx
                 (fn []
                   (swap! (:store ctx) assoc name {:name name
                                                   :fiber fiber
                                                   :value value
                                                   :check (:check opts)})
                   (swap! fiber update :provides conj name)
                   (ctx/record-change! ctx [name])
                   (event/emit! ctx :srce/service {:name name :value value})
                   (poke! ctx)
                   (fn []
                     (swap! (:store ctx) dissoc name)
                     (swap! fiber update :provides disj name)
                     (ctx/record-change! ctx [name])
                     (event/emit! ctx :srce/service {:name name :value nil})
                     (poke! ctx)
                     true))
                 (str "provide " name)))))

;; ── mount / unmount ───────────────────────────────────────────────────────

(defn plugin!
  "Mount SOURCE (a var, a plugin map, or a keyword for the :resolver) as a new
   fiber under CTX with CONFIG. Returns the fiber atom. The plugin body runs
   only when its inject is satisfied; otherwise the fiber is :pending."
  ([ctx source]
   (plugin! ctx source nil))
  ([ctx source config]
   (let [plugin (resolve-plugin ctx source)]
     (ctx/assert-active! (:fiber ctx))
     (run-owned!
      ctx
      (fn []
        (let [fiber (atom nil)
              fctx (ctx/mount-ctx ctx (:name plugin) fiber)
              rt (runtime-for! ctx source plugin)]
          (reset! fiber {:uid (ctx/next-uid! ctx)
                         :source source
                         :plugin plugin
                         :config config
                         :inject (normalize-inject (:inject plugin))
                         :provides #{}
                         :epoch ::none
                         :waiting-for #{}
                         :name (:name plugin)
                         ;; nil until the :pending transition below emits it
                         :state nil
                         :error nil
                         :effects []
                         :parent (:fiber ctx)
                         :runtime rt
                         :ctx fctx})
          (swap! (:fibers rt) conj fiber)
          (swap! (:mounted (:kernel ctx)) conj fiber)
          (set-state! fiber :pending)
          (activate! fiber)
          fiber))))))

(defn inject!
  "Mount `{:name name :inject deps :apply apply-fn}` under CTX. The name is
   required: a Clojure fn carries no name to derive one from, and the mount's
   ctx path is built from it."
  [ctx name deps apply-fn]
  (plugin! ctx {:name name :inject deps :apply apply-fn} nil))

(defn dispose!
  "Unwind FIBER's effects LIFO, detach it, and drop its runtime when it was the
   last. Idempotent; returns nil. The state flips to :disposed first, so a
   disposer cannot register and `get` already reads nil."
  [fiber]
  (let [{:keys [ctx]} @fiber]
    (run-owned!
     ctx
     (fn []
       (when-not (= :disposed (:state @fiber))
         (let [fctx (:ctx @fiber)]
           (set-state! fiber :disposed)
           (unload! fiber)
           (swap! (:mounted (:kernel ctx))
                  (fn [v] (filterv #(not (identical? % fiber)) v)))
           (when-let [parent (:parent fctx)]
             (swap! (:children parent)
                    (fn [v] (filterv #(not (identical? % fctx)) v))))
           (drop-runtime! ctx fiber)))
       nil))))

(defn restart!
  "Dispose FIBER and mount the same source and config again; returns the new
   fiber. There is no update!: a config change is a restart, which is what
   /reload does. A :failed fiber is retried this way."
  [fiber]
  (let [mount-ctx (:ctx @fiber)
        {source :source config :config} @fiber]
    (run-owned!
     (:parent mount-ctx)
     (fn []
       (dispose! fiber)
       (plugin! (:parent mount-ctx) source config)))))

(defn dispose-plugin!
  "Dispose every fiber of SOURCE, in one pass; the last one drops the runtime."
  [ctx source]
  (run-owned!
   ctx
   (fn []
     (when-let [rt (runtime ctx source)]
       (doseq [fiber (rseq @(:fibers rt))]
         (dispose! fiber)))
     nil)))

(defn dispose-subtree!
  "Dispose every fiber under CTX, children first and in one pass. Mount order is
   topological, so the reverse mount list is already children-first."
  [ctx]
  (run-owned!
   ctx
   (fn []
     (doseq [fiber (rseq @(:mounted (:kernel ctx)))
             :when (ctx/inside? (:ctx @fiber) ctx)]
       (dispose! fiber))
     nil)))

(defn effects
  "The effect labels in creation order (the reverse of unwinding)."
  [fiber]
  (mapv :label (:effects @fiber)))
