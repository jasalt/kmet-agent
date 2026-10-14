(ns kmet.gui.tk
  "Tk (Tcl/Tk 8.6+; the adapter prefers 9.1) backend for the generic
   hiccup engine — \"hiccup-tk\".

   The backend itself plus the classic-widget tag set (`:tk/…`); the themed
   widgets are kmet.gui.ttk's `:ttk/…` set, and the machinery both are
   written against is kmet.gui.tk.dsl. Tag names follow Tcl's own namespace
   split, so a widget's toolkit is in its name: `:tk/frame` is the classic
   frame, `:ttk/frame` the themed one; `:tk/text` has no ttk counterpart.

   The Tcl interpreter is reached through *adapter* (an implementation-
   detail seam so the backend is testable headlessly): an adapter is a map

     {:eval     (fn [script] -> result string)
      :command! (fn [name handler] -> nil)   ; handler: (fn [argv] -> string)
      :run-loop (fn [pump-fn] -> nil)        ; blocks; pump on the UI thread
      :destroy! (fn [] -> nil)}              ; optional teardown

   kmet.gui.tcl supplies the real host FFI adapter (babashka.ffi on
   babashka, jolt.ffi on Jolt); tests bind a recording stub. The adapter's
   evaluator is installed into kmet.gui.tk.dsl at load, so the widget sets
   script through it without depending on this namespace.

   Props are Clojure data; a Tcl option is never spelled in a tree. A tag
   spec maps each prop to its Tcl option ({:fg :foreground}) and each
   callback to how its event reaches Tk ({:on-click {:event \"<Button-1>\"}}),
   and keywords carry the values Tcl wants as words — :wrap :word/:none/:char,
   :fill :both/:x, booleans as Tcl's 1/0. A :bind map ({:ctrl+enter f})
   binds arbitrary events, including on :tk/toplevel, and a callback prop
   set to nil is unbound. The value-carrying slots receive the widget's
   live value (:on-change/:on-submit the entry text, :on-toggled the
   checked boolean).

   Which props a tag takes is its widget's Tk option set: each spec's
   :options composes option families from kmet.gui.tk.options (the standard
   options, the bordered/graphic blocks, the text blocks, …) with the
   options its widget has on its own, so a prop is accepted exactly where
   Tk has the option — and a prop whose option this Tk does not have fails
   with kmet.gui.tk.dsl/check-options! (naming tag, prop and option)
   instead of scripting a broken widget.

   The engine's :render-items/:render-all hooks exist for output-producing
   toolkits; Tk widgets are live the moment reconcile constructs them, so
   they return nil and kmet.gui.tcl drives re-renders with a pump armed
   through kmet.libs.hikap.component/set-frame-hook!."
  (:require [kmet.gui.tk.dsl :as dsl]
            [kmet.gui.tk.options :as opt]
            [kmet.gui.ttk :as ttk]
            [kmet.libs.hikap.component :as component]
            [kmet.libs.hikap :as engine])
  (:refer-clojure :exclude [run!]))

;; ═══════════════════════════════════════════════════════════════════════════
;; The interpreter adapter seam
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *adapter*
  "The Tcl adapter (see the ns docstring), installed by kmet.gui.tcl's
   run!/start! or bound directly by tests."
  nil)

(defn set-adapter!
  "Install ADAPTER as the process adapter (kmet.gui.tcl calls this). The
   option lists cached from a previous interpreter are dropped: a new
   adapter may be a different Tk."
  [adapter]
  (dsl/forget-widget-options!)
  (alter-var-root #'*adapter* (constantly adapter))
  nil)

(defn- require-adapter! []
  (or *adapter*
      (throw (ex-info
              (str "kmet.gui.tk: no Tcl adapter installed — call "
                   "kmet.gui.tk/run! (which loads kmet.gui.tcl) or bind "
                   "*adapter* in tests")
              {:type :kmet.gui.tk/no-adapter}))))

(defn- eval!
  "Evaluate SCRIPT through the bound adapter."
  [script]
  ((:eval (require-adapter!)) script))

(defn- command!
  [name handler]
  ((:command! (require-adapter!)) name handler))

;; The widget sets script through kmet.gui.tk.dsl; hand them this adapter's
;; evaluator. The fn reads *adapter* per call, so a test's binding is seen.
(dsl/set-eval-fn! eval!)

;; ─── The public API the DSL implements ───────────────────────────────────

(defn tcl-quote
  "Quote X as a single Tcl word (always backslash-escaped; safe in any
   substitution context)."
  [x]
  (dsl/tcl-quote x))

(defn widget-path
  "The Tk path of WIDGET (\"\\.\" for the toplevel)."
  [widget]
  (dsl/widget-path widget))

(defn dispatch-callback!
  "The single command handler behind `kmet::cb`: ARGV is
   [\"kmet::cb\" id & values]. Returns the handler's result string (\"\")."
  [argv]
  (dsl/dispatch-callback! argv))

(defn destroy!
  "Dispose a TkWidget: recurse into its stored children (releasing their
   callbacks), then destroy the widget path and any derived resources."
  [w]
  (dsl/destroy! w))

(defn install-callback-command!
  "Register the `kmet::cb` trampoline with the adapter. Idempotent per
   interpreter; run! calls it at start."
  []
  (command! "kmet::cb" dispatch-callback!)
  nil)

;; ═══════════════════════════════════════════════════════════════════════════
;; The classic widget set (:tk/…)
;; ═══════════════════════════════════════════════════════════════════════════

;; ═══════════════════════════════════════════════════════════════════════════
;; The classic widget set (:tk/…)
;; ═══════════════════════════════════════════════════════════════════════════

;; ─── Transcript stack (the :tk/transcript tag) ───────────────────────────
;; One text widget hosts every child as an embedded window (each message is
;; its own widget, on its own stretched line), so the chat scrolls natively
;; — no canvas, and no drawing lines. `:attach` in the widget's :styles is
;; how kmet.gui.tk.dsl/make-widget learns to embed instead of pack.

(defn- make-transcript
  "Construct the :tk/transcript composite: one text widget whose embedded
   windows are the child widgets (each on its own line), plus its
   scrollbar. Text scrolling has no canvas coordinate limit, so this is the
   scroll container for long stacks (a chat transcript)."
  [spec props]
  (let [parent (dsl/parent-widget)
        text (dsl/alloc-path! parent)
        sb (dsl/alloc-path! parent)
        font (dsl/resolve-base-font props)
        w (dsl/->TkWidget text (:tag spec) (atom []) (atom {}) (atom {}))]
    (dsl/eval! (dsl/widget-script "text" text
                                  (merge {:wrap :none :height 1 :width 1 :padx 0 :pady 0
                                          :highlightthickness 0 :borderwidth 0 :takefocus 0
                                          :font (dsl/font-spec font {})}
                                         (dsl/tcl-options props (:options spec)))))
    (dsl/check-options! spec :tk/transcript text props)
    (dsl/eval! (str "ttk::scrollbar " sb " -orient vertical -command \"" text " yview\""))
    (dsl/eval! (str text " configure -yscrollcommand \"" sb " set\""))
    (when (:see-end props)
      (dsl/eval! (str "bind " text " <Map> {after idle {"
                      text " see end"
                      (when (:read-only props)
                        (str "; " text " configure -state disabled"))
                      "}}")))
    (swap! (:styles w) assoc
           :font font
           :scrollbar sb
           :attach (fn [p]
                     (dsl/eval! (str text " window create end -window " p " -pady 2"))
                     (dsl/eval! (str text " insert end \"\\n\""))))
    (dsl/apply-bindings! w {} props (:bindings spec))
    (when parent
      (dsl/eval! (str "pack " sb " -side right -fill y"))
      (dsl/eval! (str "pack " text " -side left -fill "
                      (dsl/tcl-quote (dsl/tcl-word (or (:fill props) :both)))
                      (when-not (false? (:expand props)) " -expand 1"))))
    w))

(def ^:private classic-specs
  {:tk/toplevel
   {:ctor (fn [spec props]
            (when-let [title (:title props)]
              (dsl/eval! (str "wm title . " (dsl/tcl-quote title))))
            (when-let [width (:width props)]
              (dsl/eval! (str "wm geometry . " (dsl/tcl-quote (dsl/tcl-word width)) "x"
                              (dsl/tcl-quote (dsl/tcl-word (or (:height props) 400))))))
            (let [w (dsl/->TkWidget "." :tk/toplevel (atom []) (atom {}) (atom {}))]
              ;; the window is Tk's own ".": its options are configured, not
              ;; created (and :width/:height above are the window's, not the
              ;; widget's, so they are not in :options)
              (dsl/check-options! spec :tk/toplevel "." props)
              (dsl/apply-live! spec w {} props)
              w))
    :options (opt/options-for [:standard :classed :creation :surface :bordered
                               :highlighted :spaced :window])
    :lens (dsl/child-lens)}

   :tk/transcript
   {:ctor make-transcript
    :options (opt/options-for [:standard :geometry :bordered :highlighted :spaced
                               :selectable :fonted :text-body]
                              [:bg :fg :locale])
    :lens (dsl/child-lens)}

   :tk/frame
   {:ctor (fn [spec {:keys [border border-color] :as props}]
            (dsl/make-widget spec
                             (fn [p]
                               (dsl/widget-script
                                "frame" p
                                (dsl/tcl-options props (:options spec))
                                ;; -relief solid draws its border in the
                                ;; widget's own background, which on a
                                ;; card sitting on a same-toned canvas
                                ;; is invisible. The highlight ring takes
                                ;; a colour, so it is the visible border.
                                (when (some? border)
                                  (str "-borderwidth 0 -highlightthickness "
                                       (dsl/tcl-quote (dsl/tcl-word (if (true? border) 1 border)))
                                       (when-some [c border-color]
                                         (str " -highlightbackground " (dsl/tcl-quote c)
                                              " -highlightcolor " (dsl/tcl-quote c)))))))
                             props))
    :options (opt/options-for [:standard :classed :creation :geometry :surface
                               :bordered :highlighted :spaced])
    :lens (dsl/child-lens)}

   :tk/text
   {:ctor (fn [spec props]
            (let [font (dsl/resolve-base-font props)
                  w (dsl/make-widget spec
                                     (fn [p]
                                       (dsl/widget-script
                                        "text" p
                                        (merge {:wrap :word :height 10 :width 80
                                                :borderwidth 0 :highlightthickness 0
                                                :font (dsl/font-spec font {})}
                                               (dsl/tcl-options props (:options spec)))))
                                     props)]
              (swap! (:styles w) assoc :font font)
              (dsl/set-text-content! w props)
              (when (:scrollbar props)
                (dsl/attach-scrollbar! w (dsl/parent-widget)))
              w))
    :options (opt/options-for [:standard :geometry :bordered :highlighted :spaced
                               :selectable :fonted :text-body]
                              [:bg :fg :locale])
    :patchable #{:content :text}
    :apply (fn [spec w prev props]
             (when (dsl/patchable? spec prev props)
               ;; the generated text tags derive from the base font, so a
               ;; font the patch applied must be the one they derive from
               (when (some #(not= (get prev %) (get props %)) opt/font-props)
                 (swap! (:styles w) assoc :font (dsl/resolve-base-font props)))
               (when (or (contains? props :content) (contains? props :text))
                 (dsl/set-text-content! w props))
               (dsl/apply-live! spec w prev props)))}})

;; ═══════════════════════════════════════════════════════════════════════════
;; The tag table
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private tags
  "The backend's closed host tag table: the classic (:tk/…) and themed
   (:ttk/…) widget sets, completed with the shared live-patching :apply."
  (dsl/widget-specs (merge classic-specs ttk/specs)))

(defn- text-leaf
  "A bare string child renders as a plain label."
  [text _props]
  (dsl/make-widget (get tags :ttk/label)
                   (fn [p] (dsl/widget-script "ttk::label" p
                                              {:text (or text "") :anchor :w :justify :left}))))

(def tk-backend
  "The Tk backend map for kmet.libs.hikap."
  {:name :tk
   :tags tags
   :text-leaf text-leaf
   :component? (constantly false)
   :entry-component nil
   :dispose! destroy!
   :render-items (fn [_items _ctx] nil)
   :render (fn [_c _ctx] nil)
   :render-all (fn [_cs _ctx] nil)
   :bind-context (fn [_component _ctx thunk] (thunk))})

;; ═══════════════════════════════════════════════════════════════════════════
;; Run loop
;; ═══════════════════════════════════════════════════════════════════════════

(defonce ^:private dirty? (atom false))

(defn- request-render!
  "The frame hook Tk installs (any thread may call it): mark the tree dirty
   and let the pump on the Tk thread do the rendering."
  []
  (reset! dirty? true)
  nil)

(defn mount!
  "Prepare the adapter and mount TREE (a tree, or a component fn that is
   re-run by every pass) in a fresh Tk window. Returns a handle for
   interactive use — {:window :root :pump} — to pass to run-loop! and
   render!.

   run! is mount! + run-loop!; this split is for development: mount on the
   thread that will own the interpreter, park run-loop! on a background
   thread, then edit a component namespace, reload it, and render! to
   repaint with the new code. The backend map and the mounted structure are
   captured at mount time, so a change inside kmet.gui.tk itself still
   needs a remount. One window per process (the dirty flag is global)."
  [tree & [options]]
  (require-adapter!)
  (when-let [prepare! (:prepare! *adapter*)]
    (prepare!))
  (install-callback-command!)
  (let [window (engine/compile-element tk-backend [:tk/toplevel (or options {})])
        root (engine/root tk-backend tree)
        pump (fn []
               (when (compare-and-set! dirty? true false)
                 (binding [engine/*construct-context* {:parent window}]
                   (engine/render-component root nil))))]
    (reset! dirty? true)
    (component/set-frame-hook! request-render!)
    (pump)
    {:window window :root root :pump pump}))

(defn render!
  "Request a render pass for HANDLE's tree at the pump's next tick, so a
   namespace the mounted component fn calls is picked up after :reload.
   Safe from any thread — it only marks the tree dirty; every Tcl call
   stays on the loop's thread. No-op when nothing is mounted."
  [_handle]
  (request-render!))

(defn run-loop!
  "Block on the Tk event loop, pumping dirty frames into HANDLE's tree, and
   dispose the tree when the window closes. Call it on the thread that owns
   the interpreter (the one that ran mount!)."
  [{:keys [root pump]}]
  (require-adapter!)
  (try
    ((:run-loop *adapter*) pump)
    (finally
      (component/set-frame-hook! nil)
      (engine/dispose-component root)
      (eval! "destroy ."))))

(defn run!
  "Mount TREE in a Tk window and run the Tk event loop until the window is
   closed. OPTIONS are the :tk/toplevel tag's props (:title :width :height
   :bg :bind). Requires an adapter (bound by the caller — kmet.gui.tcl/run!
   installs the host FFI one). Blocks."
  [tree & [options]]
  (run-loop! (mount! tree options)))
