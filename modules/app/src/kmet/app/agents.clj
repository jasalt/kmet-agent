(ns kmet.app.agents
  "The `:agents` registry: live agent handles, the creation factory seam, and
   the send surface a front end drives.

   **An agent is a child context plus a handle** (`new-app.md` § Multi-session
   server mode). The child context is the registry's: it extends the ctx
   `create!` was called with, inherits the services the root provides, and is
   the tree line `--dump-state` prints (`[:app :agent/5f3a2b1c]`). That ctx is
   the app ctx, or another agent's ctx for a subagent — never a plugin's mount
   ctx, which a fiber's disposal drops out of the tree with its children still
   hanging on it. The handle is the factory's: what an agent *is* belongs to the
   driver that runs it, so this namespace never learns what a turn, a session or
   a model is.

   **The factory is the seam, and there is none until a loop mounts.** `:agents`
   exists as soon as the row mounts, but `create!` throws
   `:kmet.app/no-agent-factory` until a driver registers one
   (`register-factory!`, the disposer a plugin's `apply` returns) — dsh's
   `setFactory`. One factory per registry: a second registration while one is
   live is `:kmet.app/factory-taken`, because two drivers over one registry is
   two owners of one agent's teardown.

   **The handle is the capability.** `create!` resolves with it; `dispose!`
   takes it and nothing else, and a handle that is not the registered one (an
   id, a copy, a value a consumer assembled) is refused. `get` returns the bare
   agent, which is what a listing or a wire client needs and cannot dispose
   with.

   **A disposal takes the subtree with it.** The ctx tree under an agent's ctx
   is this registry's too, and a subagent created under it is a child ctx that
   the kernel's `detach!` would refuse to release around — so `dispose!`
   disposes the agents registered under it (deepest first) before its own. A
   release that fails is reported and the capability withdrawn anyway: a handle
   outliving its ctx is a handle nobody can use. Disposing a handle twice is
   `:kmet.app/unknown-agent`, not a no-op — the capability was spent by the
   first call.

   **Creation is serialized and rolls back.** A failed factory leaves no handle
   and no child context: the ctx is disposed and detached in the same breath, so
   nothing observes a half-built agent. The kernel is single-owner-threaded
   (`srce.md` §8), so a create already in flight is a caller on another thread —
   it is refused (`:kmet.app/create-busy`) rather than interleaved; such a
   caller submits through `kmet.app.runtime/submit!`.

   **Sending is durable.** `send!` appends the queue fact through
   `:projections` and only then notifies the live handle (its `:send!`, the
   driver's in-memory queue): the log is the record, the atom is the wake-up.
   A message queued while the process was down is still in the inbox when the
   session resumes."
  (:require [kmet.srce.core :as srce]))

;; ── the handle contract ───────────────────────────────────────────────────

(def handle-contract
  "What every handle the factory returns must be, key by key. A handle is a
   map the driver builds; this table is the whole of what the registry asks of
   it, and `validate-handle!` reads it rather than restating it:

     :id       the id the registry minted (passed to the factory in its opts)
     :ctx      the agent's child context — the registry's own
     :agent    the bare agent (non-nil), what `get` and `list` answer with
     :session  a thunk returning the live session (a session switch moves it)
     :dispose! the factory's teardown: abort the turn, drain the queue. The
               registry releases the child context after it returns."
  {:id (fn [id _ handle] (= id (:id handle)))
   :ctx (fn [_ child handle] (identical? child (:ctx handle)))
   :agent (fn [_ _ handle] (some? (:agent handle)))
   :session (fn [_ _ handle] (fn? (:session handle)))
   :dispose! (fn [_ _ handle] (fn? (:dispose! handle)))})

(def handle-ops
  "The optional control surface a handle may answer. A caller asking for one a
   handle does not implement gets `:kmet.app/unsupported-op` naming it, never a
   silent nil."
  [:send! :inject! :cancel! :when-idle :status])

(defn- validate-handle!
  "HANDLE checked against `handle-contract` for ID and CHILD; returns it. A
   factory that returns the wrong shape fails here, at creation, rather than
   inside the first caller."
  [id child handle]
  (let [problems (if-not (map? handle)
                   [:map]
                   (into (into [] (keep (fn [[k fits?]]
                                          (when-not (fits? id child handle) k)))
                               handle-contract)
                         ;; an op present but not callable is a shape problem
                         ;; too, so the caller gets the op's name instead of
                         ;; "not a function" from inside it
                         (keep (fn [op]
                                 (when (and (contains? handle op)
                                            (not (fn? (get handle op))))
                                   op))
                               handle-ops)))]
    (when (seq problems)
      (throw (ex-info (str "the agent factory returned a handle that does not fit the contract: "
                           (pr-str problems))
                      {:type :kmet.app/bad-agent-handle
                       :id id
                       :problems problems
                       :required (vec (keys handle-contract))
                       :optional handle-ops})))
    handle))

(defn- session-of [handle] ((:session handle)))

;; ── emission ──────────────────────────────────────────────────────────────

(defn- emit!
  "Tell SCOPE's listeners that an agent came or went. The kernel's `emit!` from
   the scope the service serves, so the registry needs no `:events` lookup (and
   cannot hold a provider record that a re-provide made stale)."
  [scope type event]
  (srce/emit! scope type (assoc event :type type)))

;; ── ids ───────────────────────────────────────────────────────────────────

(defn- mint
  "A short id no live handle holds: `5f3a2b1c`-style, so the tree's
   `[:agent/5f3a2b1c]` line stays readable and two agents cannot share a path."
  [taken]
  (loop []
    (let [id (subs (str (random-uuid)) 0 8)]
      (if (contains? taken id) (recur) id))))

;; ── creation ──────────────────────────────────────────────────────────────

(defn- claim-busy!
  "Mark the registry busy with ID, or throw when another create is in flight.
   A CAS rather than a `swap!` side effect: the claim is the state transition."
  [state id]
  (loop []
    (let [v @state]
      (if-let [busy (:busy v)]
        (throw (ex-info (str "an agent creation is already in flight (" busy ")")
                        {:type :kmet.app/create-busy :creating busy}))
        (when-not (compare-and-set! state v (assoc v :busy id))
          (recur))))))

(defn- release-subtree!
  "Release CTX's mounts, then the ctx itself — the rollback a failed creation
   and a disposal share. `detach!` refuses while a mount remains, so the order
   is the kernel's rule, not a preference."
  [ctx]
  (srce/dispose-subtree! ctx)
  (srce/detach! ctx))

(defn- subtree-ctxs
  "CTX's descendant ctxs, parents before children and eagerly: a disposal
   mutates the tree it is walking, so the walk cannot be a lazy seq."
  [ctx]
  (reduce (fn [acc child]
            (into (conj acc child) (subtree-ctxs child)))
          []
          (srce/children ctx)))

(defn- mount-ctx?
  "Is CTX a plugin mount's own ctx, rather than a plain child (`extend`)? A
   mount installs its own atom and its fiber holds the ctx back, so the identity
   check separates the two without a marker key."
  [ctx]
  (let [fiber (:fiber ctx)]
    ;; a plain child inherits its parent's fiber (the identity fails), and the
    ;; root has a fiber of its own but no parent — so both are not mounts
    (boolean (and fiber (:parent ctx) (identical? (:ctx @fiber) ctx)))))

(defn- parent-ctx!
  "CTX checked as an agent's parent. A plugin's mount ctx is refused: disposing
   a fiber drops its mount ctx out of the tree with whatever child ctxs it
   still holds, and nothing can see them afterwards — an agent parented there
   would leak invisibly. The registry's scope (the app root) or another agent's
   ctx outlives what is created under it."
  [ctx]
  (when (mount-ctx? ctx)
    (throw (ex-info (str "an agent cannot be parented to a plugin's mount ctx ("
                         (pr-str (:name ctx)) "): name the app ctx or an agent ctx")
                    {:type :kmet.app/agent-parent-is-a-mount
                     :ctx (:name ctx)})))
  ctx)

(defn- no-factory [ctx]
  (throw (ex-info "no agent factory is registered: mount a loop driver (`:agentLoop`) that registers one"
                  {:type :kmet.app/no-agent-factory :ctx (:name ctx)})))

(defn- factory-for
  "The registered factory, or a throw naming the seam that fills it."
  [state ctx]
  (or (:factory @state)
      (no-factory ctx)))

;; ── the service ───────────────────────────────────────────────────────────

(defn service
  "The `:agents` value serving SCOPE, over PROJECTIONS: a registry of live
   handles, the factory seam, and the control surface.

   Read ops (`list`, `get`, `roots`, `inbox`) take a ctx and check it is inside
   the scope the service serves — a registry answers for its own tree. The
   control ops take the handle, because the handle is the capability."
  [scope projections]
  (when-not (fn? (:enqueue! projections))
    (throw (ex-info "an :agents service needs the :projections service"
                    {:type :kmet.app/incomplete-service :missing :projections})))
  (let [state (atom {:handles {} :order [] :factory nil :busy nil})
        inside! (fn [ctx]
                  (when-not (srce/inside? ctx scope)
                    (throw (ex-info "this agent registry serves another ctx tree"
                                    {:type :kmet.app/outside-scope})))
                  ctx)
        summary (fn [handle]
                  {:id (:id handle)
                   :agent (:agent handle)
                   :status (when-let [f (:status handle)] (f))})
        live (fn [handle]
               (let [registered (get-in @state [:handles (:id handle)])]
                 (when-not (identical? registered handle)
                   (throw (ex-info (str "not a live agent handle: " (pr-str (:id handle)))
                                   {:type :kmet.app/unknown-agent
                                    :id (:id handle)})))
                 registered))
        unsupported (fn [op id]
                      (throw (ex-info (str "agent " (pr-str id) " does not answer " op)
                                      {:type :kmet.app/unsupported-op
                                       :op op :id id})))
        dispatch (fn [handle k args]
                   (let [handle (live handle)]
                     (if-let [f (get handle k)]
                       (apply f args)
                       (unsupported k (:id handle)))))]
    (letfn [(create! [ctx opts]
              (parent-ctx! (inside! ctx))
              (let [factory (factory-for state ctx)
                    id (mint (set (keys (:handles @state))))]
                (claim-busy! state id)
                (try
                  (let [child (srce/extend ctx {:name (keyword "agent" id)})
                        ;; one rollback for both failure modes: the factory
                        ;; throwing, and the factory returning a handle that
                        ;; does not fit the contract
                        handle (try
                                 (validate-handle! id child
                                                   ((:f factory) child (assoc opts :id id)))
                                 (catch Throwable t
                                   (release-subtree! child)
                                   (if (= :kmet.app/bad-agent-handle (:type (ex-data t)))
                                     (throw t)
                                     (throw (ex-info (str "agent creation failed: "
                                                          (or (ex-message t) (str t)))
                                                     {:type :kmet.app/agent-create-failed
                                                      :id id}
                                                     t)))))]
                    (swap! state (fn [s]
                                   (-> s
                                       (assoc-in [:handles id] handle)
                                       (update :order conj id))))
                    ;; the id and the agent, never the ctx: a ctx is cyclic,
                    ;; so a listener that logs its event would never return
                    (emit! scope :agent/created {:id id :agent (:agent handle)})
                    handle)
                  (finally
                    (swap! state assoc :busy nil)))))

            (send! [handle msg opts]
              (let [handle (live handle)
                    session (session-of handle)
                    ;; the inbox is the log: an agent without one has nowhere
                    ;; to queue, and the backend would fail deeper in instead
                    _ (when-not session
                        (throw (ex-info (str "agent " (pr-str (:id handle))
                                             " has no session to queue into")
                                        {:type :kmet.app/agent-has-no-session
                                         :id (:id handle)})))
                    entry ((:enqueue! projections) session msg opts)]
                (when-let [f (:send! handle)]
                  (f msg opts))
                entry))

            (dispose! [handle]
              (let [handle (live handle)
                    id (:id handle)
                    ;; the agents under this one go first: a subagent's ctx is
                    ;; a child of its parent's, and detach! refuses while a
                    ;; child ctx hangs there. Deepest first, so each release
                    ;; leaves its parent's ctx free.
                    under (into []
                                (keep (into {} (map (juxt :ctx identity))
                                            (vals (:handles @state))))
                                (reverse (subtree-ctxs (:ctx handle))))]
                (doseq [child under] (dispose! child))
                (try
                  ((:dispose! handle))
                  (catch Throwable t
                    ;; a failed teardown is reported, not thrown: the ctx is
                    ;; released and the capability withdrawn either way, so
                    ;; nothing is left half-owned
                    (srce/log! scope :error "agent teardown failed" t)))
                (try
                  (release-subtree! (:ctx handle))
                  (catch Throwable t
                    (srce/log! scope :error "agent ctx release failed" t)))
                (swap! state (fn [s]
                               (-> s
                                   (update :handles dissoc id)
                                   (update :order (fn [order]
                                                    (into [] (remove #{id}) order))))))
                (emit! scope :agent/disposed {:id id :agent (:agent handle)})
                nil))]
      {:register-factory!
       (fn [ctx factory]
         (inside! ctx)
         (when-not (fn? factory)
           (throw (ex-info "an agent factory is a fn of (ctx opts)"
                           {:type :kmet.app/bad-agent-factory})))
         (loop []
           (let [v @state]
             (if-let [taken (:factory v)]
               (throw (ex-info (str "an agent factory is already registered ("
                                    (:owner taken) ")")
                               {:type :kmet.app/factory-taken
                                :owner (:owner taken)}))
               (if (compare-and-set! state v (assoc v :factory {:owner (:name ctx)
                                                                :f factory}))
                 ;; the disposer removes *this* factory only: a restarted
                 ;; driver's old disposer must not clear its replacement
                 (fn []
                   (swap! state (fn [s]
                                  (if (identical? (:f (:factory s)) factory)
                                    (assoc s :factory nil)
                                    s)))
                   nil)
                 (recur))))))

       :create! create!

       :resume! (fn [ctx id] (create! ctx {:resume id}))

       :get (fn [ctx id]
              (inside! ctx)
              (:agent (get-in @state [:handles id])))

       :list (fn [ctx]
               (inside! ctx)
               (let [{:keys [handles order]} @state]
                 (into [] (keep #(some-> (get handles %) summary)) order)))

       :roots (fn [ctx]
                (inside! ctx)
                (let [{:keys [handles order]} @state
                      agent-ctxs (into #{} (map (comp :ctx val)) handles)]
                  (into []
                        (keep (fn [id]
                                (let [handle (get handles id)]
                                  (when-not (contains? agent-ctxs (:parent (:ctx handle)))
                                    (summary handle)))))
                        order)))

       :dispose! dispose!

       :send! send!

       :steer! (fn [handle msg] (send! handle msg {:target :next-step}))

       :follow-up! (fn [handle msg] (send! handle msg {:target :next-turn}))

       :inbox (fn [ctx handle]
                (inside! ctx)
                ((:inbox projections) (session-of (live handle))))

       :inject! (fn [handle msg] (dispatch handle :inject! [msg]))

       :cancel! (fn [handle opts] (dispatch handle :cancel! [opts]))

       :when-idle (fn [handle] (dispatch handle :when-idle []))

       :status (fn [handle] (dispatch handle :status []))})))

(defn- serve
  "The `:agents` row: the registry over the injected projections, provided into
   the mount site's scope, plus the emission scope it serves."
  [ctx _config]
  (let [scope (:parent ctx)]
    (srce/provide! ctx :agents (service scope (srce/get ctx :projections))
                   {:in scope})))

(srce/defplugin plugin
  {:name :agents
   :inject [:projections]
   :provide #{:agents}
   :events
   {:agent/created {:mode :emit :domain :app
                    :doc "Fired after an agent's handle is published, carrying the
                          id, the bare agent and its child context. The registry
                          is the only emitter: a handle exists before this fires."}
    :agent/disposed {:mode :emit :domain :app
                     :doc "Fired after an agent's child context is released and
                           its handle withdrawn, carrying the id and the bare
                           agent it was. A listener that still holds the handle
                           can no longer dispose it through the registry."}}
   :apply serve})
