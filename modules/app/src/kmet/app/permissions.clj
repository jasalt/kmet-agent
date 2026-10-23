(ns kmet.app.permissions
  "The `:permissions` policy and the `:approvals` delivery seam — one decision,
   two keys.

   `:permissions` decides whether a tool call may run and costs nothing when it
   says so:

     {:policy    (fn [ctx tool-call] :allow | :deny | :ask
                                   | {:decision … :reason …})
      :remember! (fn [ctx tool-call scope])}   ; optional; scope is :once,
                                               ; :agent or :workspace

   `:approvals` answers the `:ask` verdict, and it is the only thing it does —
   a generic dialog belongs to `:ui`:

     {:ask! (fn [ctx request] :allow | :deny | {:decision … :scope …})}

   The split is the point: deciding is synchronous and per-agent (an agent's
   `setup`, or a preset's child ctx, shadows the service), while asking a human
   is I/O that blocks and belongs to whichever front end can answer. `:deny`
   becomes the hook result the loop already understands, so no tool signature
   changes.

   **The gate is the loop's pre-tool waterfall, and it runs last.** Extension
   tool-call hooks run first (they may rewrite the arguments), then the policy
   judges the effective call, so a rewrite cannot smuggle a call past it. A
   policy that throws, or returns something that is not a verdict, is a deny —
   the failure mode of an approval gate must never be \"allow\".

   A ctx with no `:permissions` allows: the gate exists only where an app
   mounted it. A ctx with a policy but no `:approvals` denies an `:ask` —
   \"nobody can ask\" is not \"yes\"."
  (:require [kmet.srce.core :as srce]))

;; ── the values ────────────────────────────────────────────────────────────

(def verdicts
  "Every answer a policy or an approver may give."
  #{:allow :deny :ask})

(def scopes
  "Where a remembered approval applies: this call, this agent, this workspace."
  #{:once :agent :workspace})

(defn- check-fns!
  "Throw for a service value that cannot answer its contract. Names the op, so
   a bad provider fails at the mount rather than at the first tool call."
  [service required optional kind]
  (when-not (map? service)
    (throw (ex-info (str kind " service must be a map, got " (pr-str service))
                    {:type :kmet.app/bad-service :service kind})))
  (doseq [k required]
    (when-not (fn? (get service k))
      (throw (ex-info (str kind " service is missing " (pr-str k))
                      {:type :kmet.app/bad-service :service kind :missing k}))))
  (doseq [k optional]
    (when-some [f (get service k)]
      (when-not (fn? f)
        (throw (ex-info (str kind " service has a non-fn " (pr-str k))
                        {:type :kmet.app/bad-service :service kind :bad k})))))
  service)

(defn policy
  "A `:permissions` value over POLICY, with an optional REMEMBER! (ctx
   tool-call scope) that records a decision an `:ask` returned a scope for."
  ([decide] (policy decide nil))
  ([decide remember!]
   (check-fns! {:policy decide :remember! remember!} [:policy] [:remember!]
               "permission")))

(defn approver
  "An `:approvals` value over ASK!."
  [ask!]
  (check-fns! {:ask! ask!} [:ask!] [] "approval"))

(defn allow-all
  "The default policy: nothing is gated. What a ctx that mounted no policy
   would do anyway — installing it says so explicitly."
  [_ctx _tool-call]
  :allow)

(defn no-approver
  "The default approver: there is nobody to ask, so an `:ask` is a deny. A
   headless run and a server without a dialog both get this until a front end
   provides `:approvals`."
  [_ctx _request]
  {:decision :deny
   :reason "no approval channel is mounted — this run cannot ask"})

(defn- verdict
  "The keyword VERDICT of an answer, or nil when it is not one. A map answer
   carries `:decision`; anything else is a bug in the provider."
  [answer]
  (let [v (if (map? answer) (:decision answer) answer)]
    (when (contains? verdicts v) v)))

(defn- reason-of
  "The reason an answer carries, if any."
  [answer]
  (when (map? answer) (:reason answer)))

(defn- block
  "The hook-shaped block result the loop settles a call with."
  [reason]
  {:block true :reason reason})

;; ── the gate ──────────────────────────────────────────────────────────────

(defn decide
  "POLICY's verdict for REQUEST, normalized to `:allow`, `:deny` or `:ask`.
   A policy that throws or answers something else is a deny, named so a broken
   provider is diagnosable from the tool result."
  [permission-service ctx request]
  (let [answer (try ((:policy permission-service) ctx request)
                    (catch Throwable t {:decision :deny
                                        :reason (str "permission policy error: "
                                                     (ex-message t))}))]
    (if-some [v (verdict answer)]
      {:decision v :reason (reason-of answer)}
      {:decision :deny
       :reason (str "permission policy returned " (pr-str answer)
                    " — expected :allow, :deny or :ask")})))

(defn check
  "The pre-tool gate: nil when REQUEST may proceed, otherwise the block result
   the loop settles the call with. REQUEST is a tool call —
   `{:tool-name … :args … :signal … :tool-call-id …}` — so the policy and the
   approver see the same thing.

   `:permissions` and `:approvals` are read from CTX, nearest on the chain, so
   an agent's child ctx decides for that agent. No policy at all allows; a
   policy with no approver denies an `:ask`."
  [ctx request]
  (when-let [permission-service (srce/get ctx :permissions)]
    (let [{:keys [decision reason]} (decide permission-service ctx request)]
      (case decision
        :allow nil
        :deny (block (or reason "denied by the permission policy"))
        :ask (let [answer (try ((:ask! (or (srce/get ctx :approvals) {:ask! no-approver}))
                                ctx request)
                               (catch Throwable t
                                 {:decision :deny
                                  :reason (str "approval request failed: "
                                               (ex-message t))}))]
               (if (= :allow (verdict answer))
                 (do (when-let [remember! (:remember! permission-service)]
                       ;; only a defined scope is remembered: an approver that
                       ;; invents one gets its answer honored, not persisted
                       (when-some [scope (:scope answer)]
                         (when (contains? scopes scope)
                           (remember! ctx request scope))))
                     nil)
                 (block (or (reason-of answer) "the approval request was denied"))))))))

(defn gate
  "HOOK with the permission check after it: HOOK may rewrite the arguments or
   block, and then the policy judges the effective call at CTX. SIGNAL rides
   the request so an `:ask` can be aborted with the run.

   Returns nil when there is no hook and the policy allows — the shape
   `invoke/prepare-tool-call` and the run_code tool hooks expect."
  [hook ctx signal]
  (fn [payload]
    (let [rewritten (when hook (hook payload))
          request (cond-> (assoc payload :args (or (:args rewritten) (:args payload)))
                    signal (assoc :signal signal))]
      (or (when-not (:block rewritten) (check ctx request))
          rewritten))))

;; ── the rows ──────────────────────────────────────────────────────────────

(defn- serve-policy
  "The `:permissions` row: provide a policy (the row's `:config` may name a
   stricter one) into the mount site's scope, so siblings and child ctxs see it
   while the entry stays this fiber's effect."
  [ctx config]
  (srce/provide! ctx :permissions
                 (policy (or (:policy config) allow-all) (:remember! config))
                 {:in (:parent ctx)}))

(defn- serve-approvals
  "The `:approvals` row: provide the delivery seam. The default has no one to
   ask, so it denies. A front end supplies the real one through this row's
   config (`{:id :approvals :config {:ask! …}}`) — not by providing the key
   beside it, which the kernel refuses as a duplicate."
  [ctx config]
  (srce/provide! ctx :approvals
                 (approver (or (:ask! config) no-approver))
                 {:in (:parent ctx)}))

(srce/defplugin permissions
  {:name :permissions
   :provide #{:permissions}
   :apply serve-policy})

(srce/defplugin approvals
  {:name :approvals
   :provide #{:approvals}
   :apply serve-approvals})
