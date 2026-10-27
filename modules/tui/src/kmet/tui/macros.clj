(ns kmet.tui.macros
  "TUI component record boilerplate: defcomponent builds a defrecord
   implementing kmet.tui.protocols/IComponent, wiring in track! cache
   invalidation and track-watch teardown.

   The compiled expansion depends on kmet.hikap.component
   (invalidate-cache and watch teardown), so this namespace requires it;
   the runtime macros/vars — track!/with-let, stores, the frame-scheduler
   hook, the track-watch registry — are required directly by their users."
  (:require [kmet.hikap.component]))

;; ═══════════════════════════════════════════════════════════════════════════
;; defcomponent — record + IComponent boilerplate
;; ═══════════════════════════════════════════════════════════════════════════

(defn- track!-form?
  "True when FORM is a (track! ...) call — recognized however the macro is
   reached: bare/referred, or through any namespace alias of
   kmet.hikap.component."
  [form]
  (and (seq? form)
       (let [h (first form)]
         (and (symbol? h) (= "track!" (name h))))))

(defn- render-uses-track?
  "True when the render method calls track! lexically (the body must contain
   a (track! ...) form for the deref rewrite to apply)."
  [render-method]
  (boolean (some track!-form?
                 (tree-seq coll? seq render-method))))

(defn- cache-clearing-invalidate
  "An invalidate method that clears the component's track! cache (the
   reactive watches make it redundant, but explicit invalidation must stay
   correct)."
  []
  '(invalidate [this] (kmet.hikap.component/invalidate-cache this)))

(defn- prepend-cache-clear
  "Prepend the track! cache clear to a custom invalidate method body, using
   the method's own component binding (authors may name it _this when the
   body doesn't use it)."
  [invalidate-form]
  (let [[_ args & body] invalidate-form
        this-arg (first args)]
    (list* 'invalidate args
           (list 'kmet.hikap.component/invalidate-cache this-arg)
           body)))

(defn- body-method
  "First body form whose head symbol is SYM, or nil."
  [body sym]
  (first (filter #(and (seq? %) (= sym (first %))) body)))

(defn- prepend-dispose-cleanup
  "Prepend track-watch removal to a custom dispose method body, using the
   method's own component binding."
  [dispose-form]
  (let [[_ args & body] dispose-form
        this-arg (first args)]
    (list* 'dispose args
           (list 'kmet.hikap.component/remove-track-watches! this-arg)
           body)))

(defmacro defcomponent
  "Define a TUI component: a defrecord implementing protocols/IComponent.

     (defcomponent Name kind [field...]
       (render [this width] ...)          ; required
       (handle-input [this data] ...)     ; optional — defaults to no-op
       (invalidate [this] ...))           ; optional — cache clear prepended
       (dispose [this] ...))              ; optional — watch teardown prepended

   When the render body calls track!, an invalidate method is generated
   (or the cache clear prepended to a custom one) so explicit invalidation
   always clears the render cache — components only write an invalidate
   method for additional side effects (delegating to children, firing
   request-render).

   KIND is stamped as the record's FIRST FIELD (kind-as-data, tui.md §8):
   a keyword like :user / :assistant / :tool / :custom for message
   components, or nil for plain tui components (Text, Spacer, footer...).
   Dispatch reads (:kind component) — there is no IComponentKind
   protocol. Construct via map-> (kind defaults to nil when absent from
   the map). Components that need additional protocols (IFocusable) keep
   a separate extend-type form after the call. The expansion requires
   kmet.tui.protocols first, so component namespaces load standalone
   (their own ns need not require it — clj-kondo would flag that as
   unused); the symbol is inserted literally (`~'`) so a template bare
   `kmet.tui.protocols` cannot be resolved by syntax-quote at
   macro-definition time into `kmet.hikap.component/kmet.tui.protocols` when
   this namespace is compiled before protocols is loaded (jolt's all-ns
   exposes vendored namespaces before they are loaded)."
  [name _kind fields & body]
  (let [render (body-method body 'render)]
    (when-not render
      (throw (ex-info "defcomponent requires a render method" {:name name})))
    (let [track? (render-uses-track? render)
          handle-input (or (body-method body 'handle-input)
                           '(handle-input [_this _data] nil))
          ;; dispose: track-watch teardown prepended (custom bodies) or
          ;; synthesized around it — track! watches must never outlive the
          ;; component; containers override dispose to delegate to children,
          ;; and each child strips its own watches.
          custom-dispose (body-method body 'dispose)
          dispose (if custom-dispose
                    (prepend-dispose-cleanup custom-dispose)
                    '(dispose [_this]
                              (kmet.hikap.component/remove-track-watches! _this)))
          custom-invalidate (body-method body 'invalidate)
          invalidate (cond
                       custom-invalidate (if track?
                                           (prepend-cache-clear custom-invalidate)
                                           custom-invalidate)
                       track? (cache-clearing-invalidate)
                       :else '(invalidate [this] nil))]
      `(do
         (clojure.core/require '~'kmet.tui.protocols)
         (defrecord ~name ~(vec (cons 'kind fields))
           kmet.tui.protocols/IComponent
           ~render
           ~handle-input
           ~invalidate
           ~dispose)))))
