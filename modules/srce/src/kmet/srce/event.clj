(ns kmet.srce.event
  "One listener table: `on`/`once` register, `emit!` walks it, `listeners`
   resolves it for the app's other four dispatch modes.

   A listener is `(fn [event])`. It has no ctx of its own at call time: the
   listener's registration ctx is visible to the `:filter` and in the resolved
   list, which is what keeps one table shape for all five modes."
  (:require [kmet.srce.ctx :as ctx]))

(defn listeners
  "The resolved listener list for NAME from CTX: `[{:ctx c :f f} …]` in dispatch
   order (this ctx first, then its ancestors), with `:filter` applied to each
   listener's own ctx."
  [ctx name opts]
  (let [keep? (:filter opts)]
    (loop [c ctx acc []]
      (if-not c
        acc
        (recur (:parent c)
               (into acc (for [f (get @(:listeners c) name)
                               :when (or (nil? keep?) (keep? c))]
                           {:ctx c :f f})))))))

(defn emit!
  "Emit EVENT to the listeners of NAME visible from CTX, synchronously. Listener
   results are ignored; a throwing listener is logged and does not stop the
   rest."
  ([ctx name event]
   (emit! ctx name event nil))
  ([ctx name event opts]
   (doseq [{:keys [f]} (listeners ctx name opts)]
     (try
       (f event)
       (catch Throwable t
         (ctx/log! ctx :error "event listener threw" {:event name} t))))
   nil))

(defn on
  "Register F as a listener for NAME on CTX. Returns a single-shot disposer that
   reports whether it removed a registration (nil once already gone)."
  [ctx name f]
  (ctx/effect ctx
              (fn []
                (swap! (:listeners ctx) update name (fnil conj []) f)
                (fn []
                  (let [registered? (boolean (some #(identical? % f)
                                                   (get @(:listeners ctx) name)))]
                    (swap! (:listeners ctx) update name
                           (fn [v] (vec (remove #(identical? % f) v))))
                    registered?)))
              (str "on " name)))

(defn once
  "Register F for NAME's next emit. The registration is disposed before F runs."
  [ctx name f]
  (let [dispose (atom nil)]
    (reset! dispose (on ctx name (fn [event] (@dispose) (f event))))
    @dispose))
