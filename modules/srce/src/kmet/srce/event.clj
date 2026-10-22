(ns kmet.srce.event
  "One listener table: `on`/`once` register, `listeners` resolves it, and the six
   non-awaiting dispatch modes walk it — `emit!`, `bail!`, `waterfall!`,
   `collect-last!`, `collect-all!` and `boundary!`. The awaited two (`serial`,
   `parallel`) are absent because their *content* is the await: in a sync kernel
   they collapse onto `bail!` and `collect-all!` (`srce.md` §5, *why serial/parallel are
   absent*).

   Every mode is a fold over the same resolved list, which is why they live
   together: registration order, the one exception rule (`invoke!`), and
   whatever per-registration data the caller attached (`{:data x}` on `on`,
   reported by `collect-all!`). No mode interprets an event name or a payload —
   the vocabulary and the owner value are the app's (`kmet.app.events`).

   A listener is `(fn [event])`, or `(fn [event next])` for a waterfall, which
   is the only mode whose mechanism *is* the extra argument. A listener has no
   ctx of its own at call time: the listener's registration ctx is visible to
   the `:filter` and in the resolved list, which is what keeps one table shape
   for every mode."
  (:require [kmet.srce.ctx :as ctx]))

(def dispatch-modes
  "The dispatch modes this kernel implements, each a fold over the resolved
   listener list (`emit!`, `bail!`, `waterfall!`, `collect-last!`, `collect-all!`,
   `boundary!`). It is the authority: an app declares an event's mode, and the
   declaration is only honest if the mode is in this set. `:serial` and
   `:parallel` are Cordis's awaiting modes: strip the await and they are
   `bail!` and `collect-all!`, so a declaration naming one would promise a
   contract the kernel cannot honour — absent rather than half-supported
   (`srce.md` §5)."
  #{:emit :bail :waterfall :collect-last :collect-all :boundary})

(defn listeners
  "The resolved listener list for NAME from CTX: `[{:ctx c :f f :data d} …]` in
   dispatch order (this ctx first, then its ancestors), with `:filter` applied
   to each listener's own ctx. `:data` is whatever the registration attached
   (`{:data x}` on `on`) — opaque to the kernel, and what `collect-all!` reports
   beside a result so a caller can attribute a contribution."
  [ctx name opts]
  (let [keep? (:filter opts)]
    (loop [c ctx acc []]
      (if-not c
        acc
        (recur (:parent c)
               (into acc (for [{:keys [f data]} (get @(:listeners c) name)
                               :when (or (nil? keep?) (keep? c))]
                           {:ctx c :f f :data data})))))))

(defn invoke!
  "Call LISTENER with EVENT and return its value; a throw is logged and reads as
   nil, so a broken listener never stops the dispatch. It is the one exception
   rule for every mode — the kernel's own three and the app's protocols, which
   walk the same resolved list."
  [ctx name listener event]
  (try
    (listener event)
    (catch Throwable t
      (ctx/log! ctx :error "event listener threw" {:event name} t)
      nil)))

(defn emit!
  "Emit EVENT to the listeners of NAME visible from CTX, synchronously, in
   dispatch order. Listener results are ignored."
  ([ctx name event]
   (emit! ctx name event nil))
  ([ctx name event opts]
   (doseq [{:keys [f]} (listeners ctx name opts)]
     (invoke! ctx name f event))
   nil))

(defn bail!
  "Emit EVENT until a listener answers: the first non-nil result is the answer
   and the remaining listeners never see the event (Cordis: bail). Nil keeps
   going, and `false` is a value here, not an absence: a listener with no
   opinion returns nil."
  ([ctx name event]
   (bail! ctx name event nil))
  ([ctx name event opts]
   (loop [ls (seq (listeners ctx name opts))]
     (when ls
       (let [{:keys [f]} (first ls)]
         (if-some [result (invoke! ctx name f event)]
           result
           (recur (next ls))))))))

(defn waterfall!
  "Emit EVENT as around-middleware: each listener is called `(f event next)` and
   returns its own value, and `next` runs the rest of the chain and returns that
   value — so a listener that does not call `next` vetoes the rest, and the
   outermost listener's value is the answer. Nil when nothing listens."
  ([ctx name event]
   (waterfall! ctx name event nil))
  ([ctx name event opts]
   (letfn [(step [ls]
             (when-let [{:keys [f]} (first ls)]
               (invoke! ctx name (fn [event] (f event #(step (next ls)))) event)))]
     (step (seq (listeners ctx name opts))))))

(defn collect-last!
  "Run every listener in dispatch order and return the LAST non-nil result, nil
   when nobody answered. A later answer wins over an earlier one — the fold a
   tree navigation reads (a later `:cancel` or `:summary` overrides an earlier
   one), as opposed to `bail!`'s first."
  ([ctx name event] (collect-last! ctx name event nil))
  ([ctx name event opts]
   (reduce (fn [acc {f :f}]
             (let [result (invoke! ctx name f event)]
               (if (some? result) result acc)))
           nil
           (listeners ctx name opts))))

(defn collect-all!
  "Run every listener and return every non-nil result as `[[data result] …]`, in
   dispatch order, where DATA is what the registration attached (`{:data x}` on
   `on`). The collect shape: each contributor's result is kept beside the
   registration that produced it, so a caller applies a contribution under the
   owner that made it."
  ([ctx name event] (collect-all! ctx name event nil))
  ([ctx name event opts]
   (into []
         (keep (fn [{:keys [f data]}]
                 (when-some [result (invoke! ctx name f event)]
                   [data result])))
         (listeners ctx name opts))))

(defn boundary!
  "Dispatch EVENT as a boundary: each listener receives EVENT merged with the
   accumulated result so far and returns a map whose keys replace the
   accumulated ones; nil or a non-map keeps them (pi: emitBoundary — the data
   protocol, where `waterfall!` is the call protocol). Returns the accumulated
   map, or nil when no listener returned one."
  ([ctx name event] (boundary! ctx name event nil))
  ([ctx name event opts]
   (reduce (fn [acc {f :f}]
             (let [result (invoke! ctx name f (if acc (merge event acc) event))]
               (if (map? result) (merge acc result) acc)))
           nil
           (listeners ctx name opts))))

(defn on
  "Register F as a listener for NAME on CTX, carrying OPTIONS as the
   registration's data:

     :data  opaque registration data — `collect-all!` reports it beside F's
            result (the app puts an owner there), and nothing in the kernel
            interprets it

   Returns a single-shot disposer that reports whether it removed a
   registration (nil once already gone)."
  ([ctx name f] (on ctx name nil f))
  ([ctx name {:keys [data]} f]
   (let [entry {:f f :data data}]
     (ctx/effect ctx
                 (fn []
                   (swap! (:listeners ctx) update name (fnil conj []) entry)
                   (fn []
                     (let [registered? (boolean (some #(identical? % entry)
                                                      (get @(:listeners ctx) name)))]
                       (swap! (:listeners ctx) update name
                              (fn [v] (vec (remove #(identical? % entry) v))))
                       registered?)))
                 (str "on " name)))))

(defn once
  "Register F for NAME's next emit, with OPTIONS as `on`'s registration data.
   The registration is disposed before F runs, so the data survives to the one
   delivery it gets."
  ([ctx name f] (once ctx name nil f))
  ([ctx name opts f]
   (let [dispose (atom nil)
         d (on ctx name opts (fn [event] (@dispose) (f event)))]
     (reset! dispose d)
     d)))

(defn names
  "Every event name registered on CTX or an ancestor, sorted — the enumerate
   half of the table, for reporting. Nothing else walks `:listeners` from
   outside this namespace."
  [ctx]
  (loop [c ctx acc #{}]
    (if-not c
      (vec (sort acc))
      (recur (:parent c) (into acc (keys @(:listeners c)))))))
