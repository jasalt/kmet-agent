(ns kmet.tk.core
  "Tk (Tcl/Tk 8.6+; the adapter prefers 9.1) backend for the generic
   hiccup engine — \"hiccup-tk\": the backend itself plus the classic-widget
   tag set (`:tk/…`). The themed widgets are kmet.tk.ttk's `:ttk/…` set,
   the machinery both are written against is kmet.tk.dsl, and the Tk
   option vocabulary is kmet.tk.options. Every tag here is a Tk widget
   class; kmet's own widgets and composites are a separate UI namespace
   over this backend (the app layer's kmet.app.ui.gui.ui), which mounts
   through mount-backend!/run-backend!. Tag names follow Tcl's own
   namespace split, so a widget's
   toolkit is in its name: `:tk/frame` is the classic frame, `:ttk/frame`
   the themed one, and `:tk/text` has no ttk counterpart. The contract —
   props and callbacks, the option families and the run loop — is
   tk/README.md.

   The Tcl interpreter is reached through *adapter* (an implementation-
   detail seam so the backend is testable headlessly): an adapter is a map

     {:eval     (fn [script] -> result string)
      :command! (fn [name handler] -> nil)   ; handler: (fn [argv] -> string)
      :run-loop (fn [pump-fn] -> nil)        ; blocks; pump on the UI thread
      :destroy! (fn [] -> nil)}              ; optional teardown

   kmet.tk.tcl supplies the real host FFI adapter (babashka.ffi on
   babashka, jolt.ffi on Jolt); tests bind a recording stub. The adapter's
   evaluator is installed into kmet.tk.dsl at load, so the widget sets
   script through it without depending on this namespace.

   The engine's :render-items/:render-all hooks exist for output-producing
   toolkits; Tk widgets are live the moment reconcile constructs them, so
   they return nil and kmet.tk.tcl drives re-renders with a pump armed
   through kmet.libs.hikap.component/set-frame-hook!."
  (:require [clojure.string :as str]
            [kmet.tk.dsl :as dsl]
            [kmet.tk.options :as opt]
            [kmet.tk.ttk :as ttk]
            [kmet.libs.hikap.component :as component]
            [kmet.libs.hikap.core :as engine])
  (:refer-clojure :exclude [run!]))

;; ═══════════════════════════════════════════════════════════════════════════
;; The interpreter adapter seam
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *adapter*
  "The Tcl adapter (see the ns docstring), installed by kmet.tk.tcl's
   run!/start! or bound directly by tests."
  nil)

(defn set-adapter!
  "Install ADAPTER as the process adapter (kmet.tk.tcl calls this). The
   option lists cached from a previous interpreter are dropped: a new
   adapter may be a different Tk."
  [adapter]
  (dsl/forget-widget-options!)
  (alter-var-root #'*adapter* (constantly adapter))
  nil)

(defn- require-adapter! []
  (or *adapter*
      (throw (ex-info
              (str "kmet.tk.core: no Tcl adapter installed — call "
                   "kmet.tk.core/run! (which loads kmet.tk.tcl) or bind "
                   "*adapter* in tests")
              {:type :kmet.tk.core/no-adapter}))))

(defn- eval!
  "Evaluate SCRIPT through the bound adapter."
  [script]
  ((:eval (require-adapter!)) script))

(defn- command!
  [name handler]
  ((:command! (require-adapter!)) name handler))

;; The widget sets script through kmet.tk.dsl; hand them this adapter's
;; evaluator. The fn reads *adapter* per call, so a test's binding is seen.
(dsl/set-eval-fn! eval!)

;; ─── The DSL's entry points (the adapter and tests use these) ────────────

(defn tcl-quote
  "Quote X as a single Tcl word (always backslash-escaped; safe in any
   substitution context)."
  [x]
  (dsl/tcl-quote x))

(defn dispatch-callback!
  "The single command handler behind `kmet::cb`: ARGV is
   [\"kmet::cb\" id & values]. Returns the handler's result string (\"\")."
  [argv]
  (dsl/dispatch-callback! argv))

;; ─── Shared variables (the variable-backed props) ────────────────────────
;; A widget option whose value is a shared Tcl variable — -variable,
;; -textvariable, -listvariable — takes one of these, or a name the app's
;; own Tcl owns. A kmet variable is created lazily (nothing touches Tcl
;; until it is first used, read or written), its Tcl name lives in kmet's
;; own namespace (which the app's Tcl should leave alone), and a watch
;; rides Tk's `trace add variable`, which is what carries a Tcl-side
;; change back to kmet.
;;
;;   (def draft (tk/variable ""))
;;   [:ttk/entry {:textvariable draft :on-change (fn [t] (println t))}]
;;   (tk/variable-watch! draft (fn [t] (reset! last-text t)))
;;
;; Every call that reaches Tcl runs on the Tk thread (from a callback or a
;; component body, not a plain thread).

(defn variable
  "A kmet-owned Tcl variable, the value a :variable/:textvariable/
   :listvariable prop takes. INITIAL is its Tcl value (a sequence is Tcl
   list syntax); nothing touches Tcl until the variable is first used,
   read or written, so one may be created before an interpreter exists."
  [initial]
  (dsl/variable initial))

(defn variable?
  "True when V is a kmet variable object."
  [v]
  (dsl/variable? v))

(defn variable-name
  "V's Tcl variable name."
  [v]
  (dsl/variable-name v))

(defn variable-value
  "V's current Tcl value as a string. Materializes V when it has not been
   used yet."
  [v]
  (dsl/variable-value v))

(defn variable-set!
  "Set V's Tcl value (a sequence is Tcl list syntax, a keyword its name, a
   boolean 1/0) and return VALUE. Writes through Tcl, so watchers and the
   widgets bound to V see the change."
  [v value]
  (dsl/variable-set! v value))

(defn variable-watch!
  "Call F with the string each Tcl write gives V — the user's typing into a
   -textvariable widget, Tk's own toggle, the app's variable-set!. Returns
   an unwatch fn (calling it again is a no-op)."
  [v f]
  (dsl/variable-watch! v f))

(defn variable-dispose!
  "Drop V: remove its watchers' traces, unset its Tcl variable, and release
   the watchers. V materializes again (with its initial value) if it is used
   after this."
  [v]
  (dsl/variable-dispose! v))

;; ═══════════════════════════════════════════════════════════════════════════
;; The classic widget set (:tk/…)
;; ═══════════════════════════════════════════════════════════════════════════

;; ═══════════════════════════════════════════════════════════════════════════
;; The classic widget set (:tk/…)
;; ═══════════════════════════════════════════════════════════════════════════

(declare register-close-handler! size-pair window-dummy wm-size-command!)
(declare apply-window-props-to!)
(declare ^:dynamic *window-path*)
(declare next-window-number)

(defn- scale-value!
  "Set classic scale W to PROPS' :value (a scale's value is a subcommand,
   not an option)."
  [w props]
  (when (some? (:value props))
    (dsl/eval! (str (:path w) " set " (dsl/tcl-value (:value props))))))

(defn- listbox-items!
  "Replace listbox W's items with PROPS' :items. A -listvariable is the
   listbox's other source of items: Tk links the widget to the variable
   both ways, so :items beside it would fight the variable's list."
  [w props]
  (when (and (some? (:items props)) (some? (:listvariable props)))
    (throw (ex-info (str "kmet.tk.core: " (:tag w) " takes :items or"
                         " :listvariable, not both — a listbox has one item"
                         " source")
                    {:type :kmet.tk.core/items-and-listvariable
                     :tag (:tag w) :props [:items :listvariable]})))
  (dsl/eval! (str (:path w) " delete 0 end"))
  (when (seq (:items props))
    (dsl/eval! (str (:path w) " insert end "
                    (str/join " " (map #(dsl/tcl-quote (str %)) (:items props)))))))

(defn- listbox-selection!
  "Select PROPS' :selected indices in listbox W (nil clears it)."
  [w props]
  (dsl/eval! (str (:path w) " selection clear 0 end"))
  (doseq [i (:selected props)]
    (dsl/eval! (str (:path w) " selection set " i " " i))))

(defn- maybe-scrollbar!
  "Give W a wired vertical scrollbar when PROPS ask for one. A :scrollbar
   and an explicit :yscrollcommand are two wirings of Tk's one
   -yscrollcommand option — the scrollbar would silently win — so asking
   for both is an error."
  [w props]
  (when (:scrollbar props)
    (when (:yscrollcommand props)
      (throw (ex-info (str "kmet.tk.core: " (:tag w) " names :scrollbar and"
                           " :yscrollcommand — Tk's -yscrollcommand takes"
                           " one")
                      {:type :kmet.tk.core/scrollbar-and-command
                       :tag (:tag w) :props [:scrollbar :yscrollcommand]})))
    (dsl/attach-scrollbar! w (dsl/parent-widget))))

(defn- wire-menubar!
  "Wire PROPS' :menubar menu onto the window it belongs to."
  [w props]
  (when (:menubar props)
    (dsl/eval! (str ". configure -menu " (:path w)))))

(def ^:private text-options
  "The options the text widget takes."
  (opt/options-for [:standard :geometry :bordered :highlighted :spaced :selectable
                    :fonted :text-body]
                   [:bg :fg :locale]))

(def ^:private classic-specs
  "The classic widget set: Tk's classic widget classes as dsl/widget-specs,
   plus the root window (`:tk/toplevel`), whose ctor is its own. kmet's own
   widgets and composites live in kmet.app.ui.gui.ui, not here."
  {:tk/toplevel
   {:ctor (fn [spec props]
            (let [path *window-path*]
              (when-not (= path ".")
                (when (not= "1" (eval! (str "winfo exists " path)))
                  (eval! (str "toplevel " path))))
              (when-let [title (:title props)]
                (dsl/eval! (str "wm title " path " " (dsl/tcl-quote title))))
              (when-let [width (:width props)]
                (dsl/eval! (str "wm geometry " path " " (dsl/tcl-value width) "x"
                                (dsl/tcl-value (or (:height props) 400)))))
              (apply-window-props-to! path props)
              (let [w (dsl/->TkWidget path :tk/toplevel (atom []) (atom {}) (atom {}))]
                ;; the window is Tk's own root: its options are configured, not
                ;; created (and :width/:height above are the window's, not
                ;; the widget's, so they are not in :options)
                (dsl/check-options! spec :tk/toplevel path props)
                (dsl/apply-live! spec w {} props)
                w)))
    :options (opt/options-for [:standard :classed :creation :surface :bordered
                               :highlighted :spaced :window])
    :patchable [:minimum-size :minsize :maximum-size :maxsize :resizable
                :fullscreen :full-screen :topmost :always-on-top :topmost?
                :always-on-top? :alpha :zoomed :icon-photo :iconphoto :on-close
                :state :iconified :withdrawn :icon-name :icon-photo-default
                :default :default-icon? :titlepath]
    :apply (fn [spec w prev props]
             (when (dsl/patchable? spec prev props)
               (dsl/apply-live! spec w prev props)
               (apply-window-props-to! (:path w) props)
               true))
    :lens (dsl/child-lens)}

   :tk/frame
   (dsl/widget-spec "frame"
                    {:options (opt/options-for [:standard :classed :creation :geometry
                                                :surface :bordered :highlighted :spaced])
                     ;; -relief solid draws its border in the widget's own
                     ;; background, which on a card sitting on a same-toned
                     ;; canvas is invisible: :border/:border-color use the
                     ;; highlight ring, which takes a colour
                     :fixed (fn [{:keys [border border-color]}]
                              (when (some? border)
                                {:borderwidth 0
                                 :highlightthickness (if (true? border) 1 border)
                                 :highlightbackground border-color
                                 :highlightcolor border-color}))
                     :lens (dsl/child-lens)})

   :tk/text
   (dsl/widget-spec "text"
                    {:options text-options
                     :fixed (fn [props]
                              (cond-> {:wrap :word :height 10 :width 80
                                       :borderwidth 0 :highlightthickness 0
                                       :font (dsl/font-spec (dsl/resolve-base-font props) {})}
                                ;; an editor needs Tk's undo, which is off by
                                ;; default
                                (:editable props) (assoc :undo true)))
                     ;; (:editable true) makes it an input: :text is its value
                     ;; and -state stays normal, where a display surface
                     ;; renders :text/:content segments and disables itself
                     :commands {:content dsl/set-text!
                                :text dsl/set-text!}
                     :bindings (merge dsl/text-bindings dsl/scroll-bindings)
                     :after maybe-scrollbar!})

   :tk/button
   (dsl/widget-spec "button"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :textvariable :default
                                                :overrelief :repeatdelay :repeatinterval])
                     :bindings {:on-click {:option :command}}
                     :primary :text})

   :tk/label
   (dsl/widget-spec "label"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :textvariable :textangle])
                     :bindings {:on-click {:event "<Button-1>"}}
                     :primary :text})

   :tk/labelframe
   (dsl/widget-spec "labelframe"
                    {:options (opt/options-for [:standard :classed :creation :geometry
                                                :bordered :highlighted :spaced :fonted]
                                               [:bg :fg :text :labelanchor])
                     :lens (dsl/child-lens)
                     :primary :text
                     :label-widget true})

   :tk/message
   (dsl/widget-spec "message"
                    {:options (opt/options-for [:standard :bordered :highlighted
                                                :spaced :fonted]
                                               [:bg :fg :text :textvariable :anchor :aspect
                                                :justify :width])
                     :primary :text})

   :tk/checkbutton
   (dsl/widget-spec "checkbutton"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :textvariable :variable :onvalue
                                                :offvalue :tristatevalue :selectcolor
                                                :indicatoron :offrelief :overrelief
                                                :selectimage :tristateimage])
                     :bindings dsl/check-bindings
                     :checkable :check})

   :tk/radiobutton
   (dsl/widget-spec "radiobutton"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :textvariable :variable
                                                :tristatevalue :selectcolor :indicatoron
                                                :offrelief :overrelief :value :selectimage
                                                :tristateimage])
                     :bindings dsl/radio-bindings
                     :checkable :radio})

   :tk/entry
   (dsl/widget-spec "entry"
                    {:options (opt/options-for [:standard :bordered :highlighted
                                                :fonted :entry-body]
                                               [:bg :fg :width :state :show :textvariable])
                     :bindings dsl/text-field-bindings
                     :text-field true})

   :tk/spinbox
   (dsl/widget-spec "spinbox"
                    {:options (opt/options-for [:standard :bordered :highlighted
                                                :fonted :entry-body]
                                               [:bg :fg :width :state :textvariable :activebackground
                                                :buttonbackground :buttoncursor
                                                :buttondownrelief :buttonuprelief :from :to
                                                :increment :format :values :wrap
                                                :repeatdelay :repeatinterval])
                     :bindings dsl/spinbox-bindings
                     :text-field true})

   :tk/listbox
   (dsl/widget-spec "listbox"
                    {:options (opt/options-for [:standard :geometry :bordered :highlighted
                                                :selectable :fonted]
                                               [:bg :fg :listvariable :activestyle :justify
                                                :selectmode :setgrid :state
                                                :disabledforeground
                                                :inactiveselectforeground])
                     :fixed {:height 8}
                     :bindings (merge {:on-select {:event "<<ListboxSelect>>" :value :index}}
                                      dsl/scroll-bindings)
                     :commands {:items listbox-items!
                                :selected listbox-selection!}
                     :after maybe-scrollbar!})

   :tk/scale
   (dsl/widget-spec "scale"
                    {:options (opt/options-for [:standard :bordered :highlighted
                                                :fonted :scale-body]
                                               [:bg :fg :width :state :activebackground])
                     :bindings {:on-change {:option :command :arg :number}}
                     :commands {:value scale-value!}})

   :tk/scrollbar
   (dsl/widget-spec "scrollbar"
                    {:options (opt/options-for [:standard :bordered :highlighted
                                                :scrollbar-body]
                                               [:bg])
                     :bindings {:on-scroll {:option :command}}
                     :commands {:position dsl/set-scrollbar-position!}})

   :tk/canvas
   (dsl/widget-spec "canvas"
                    {:options (opt/options-for [:standard :geometry :bordered :highlighted]
                                               [:bg :scrollregion :closeenough :confine
                                                :offset :state :insertbackground
                                                :insertborderwidth :insertofftime
                                                :insertontime :insertwidth
                                                :selectbackground :selectborderwidth
                                                :selectforeground :xscrollincrement
                                                :yscrollincrement])
                     :fixed {:borderwidth 0 :highlightthickness 0}
                     :bindings dsl/scroll-bindings
                     :commands {:items dsl/canvas-items!}
                     :after maybe-scrollbar!})

   :tk/menubutton
   (dsl/widget-spec "menubutton"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :textvariable :direction
                                                :indicatoron])
                     :bindings {:on-click {:event "<Button-1>"}}
                     :primary :text
                     :commands {:items dsl/set-menu-items!}})

   :tk/menu
   (dsl/widget-spec "menu"
                    {;; Tk refuses to let pack manage a menu: the menubutton or
                     ;; the window it belongs to places it
                     :pack? false
                     :fixed {:tearoff false}
                     :options (opt/options-for [:standard :fonted]
                                               [:bg :fg :activebackground :activeforeground
                                                :activeborderwidth :activerelief
                                                :borderwidth :relief :disabledforeground
                                                :selectcolor :tearoff])
                     :bindings {:postcommand {:option :postcommand}}
                     :commands {:items dsl/set-menu-items!}
                     :after wire-menubar!})

   :tk/panedwindow
   (dsl/widget-spec "panedwindow"
                    {:options (opt/options-for []
                                               [:cursor :bg :borderwidth :relief :orient
                                                :width :height :handlepad :handlesize
                                                :opaqueresize :sashpad :sashrelief
                                                :sashwidth :showhandle])
                     :attach (fn [w]
                               (fn [path props]
                                 (dsl/eval! (str (:path w) " add " path
                                                 (when-some [minsize (:minsize props)]
                                                   (str " -minsize "
                                                        (dsl/tcl-value minsize)))
                                                 (when-some [stretch (:stretch props)]
                                                   (str " -stretch "
                                                        (dsl/tcl-word stretch)))))))
                     :lens (dsl/child-lens)})})

;; ═══════════════════════════════════════════════════════════════════════════
;; The tag table
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private tags
  "The backend's closed host tag table: the classic (:tk/…) and themed
   (:ttk/…) widget sets — every tag a Tk widget class — completed with the
   shared live-patching :apply."
  (dsl/widget-specs (merge classic-specs ttk/specs)))

(defn- text-leaf
  "A bare string child renders as a plain label."
  [text _props]
  (dsl/make-widget (get tags :ttk/label)
                   (fn [p _w] (dsl/widget-script "ttk::label" p
                                                 {:text (or text "") :anchor :w :justify :left}))))

(def tk-backend
  "The Tk backend map for kmet.libs.hikap.core."
  {:name :tk
   :tags tags
   :text-leaf text-leaf
   :component? (constantly false)
   :entry-component nil
   :dispose! dsl/destroy!
   :render-items (fn [_items _ctx] nil)
   :render (fn [_c _ctx] nil)
   :render-all (fn [_cs _ctx] nil)
   :bind-context (fn [_component _ctx thunk] (thunk))})

;; ═══════════════════════════════════════════════════════════════════════════
;; Run loop
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *window-path*
  "The Tk window path the current mount is creating; \".\" for the first
   window, a fresh top-level path thereafter."
  ".")

(defonce ^:private all-windows (atom #{}))

(defn- allocate-window-path!
  "A free top-level path — \".\" for the first window, \".win<n>\" after."
  []
  (if (empty? @all-windows)
    "."
    (str ".win" (swap! next-window-number inc))))

;; The real counter, so a second window never reuses a number.
(defonce ^:private next-window-number (atom 0))

(defn- request-render!
  "The frame hook Tk installs (any thread may call it): mark every mounted
   window dirty so the pump repaints the one whose tree changed."
  []
  (doseq [h @all-windows]
    (reset! (:dirty h) true))
  nil)

(defn- mount-tree!
  "The mount! body for BACKEND: prepare the adapter, register the
   trampoline, and build the window, root and pump over the backend map."
  [backend tree options]
  (require-adapter!)
  (when-let [prepare! (:prepare! *adapter*)]
    (prepare!))
  ;; the one command handler every callback rides
  (command! "kmet::cb" dispatch-callback!)
  (let [path (allocate-window-path!)
        _ (when-not (= path ".")
            (eval! (str "toplevel " path)))
        window (binding [*window-path* path]
                 (engine/compile-element backend [:tk/toplevel (or options {})]))
        root (engine/root backend tree)
        dirty (atom true)
        pump (fn []
               (when (compare-and-set! dirty true false)
                 (binding [engine/*construct-context* {:parent window}]
                   (engine/render-component root nil))))
        h {:window window :root root :pump pump :dirty dirty
           :window-path path}]
    (swap! all-windows conj h)
    (reset! dirty true)
    (component/set-frame-hook! request-render!)
    (pump)
    h))

(defn mount!
  "Prepare the adapter and mount TREE (a tree, or a component fn that is
   re-run by every pass) in a fresh Tk window. Returns a handle for
   interactive use — {:window :root :pump} — to pass to run-loop! and
   render!.

   run! is mount! + run-loop!; this split is for development: mount on the
   thread that will own the interpreter, park run-loop! on a background
   thread, then edit a component namespace, reload it, and render! to
   repaint with the new code. The backend map and the mounted structure are
   captured at mount time, so a change inside kmet.tk.core itself still
   needs a remount. One window per process (the dirty flag is global)."
  [tree & [options]]
  (mount-tree! tk-backend tree options))

(defn mount-backend!
  "Mount TREE with BACKEND — kmet.tk.core/tk-backend, or one whose :tags add
   kmet's own widgets on top of it (kmet.app.ui.gui.ui/backend). Same contract as
   mount!."
  [backend tree & [options]]
  (mount-tree! backend tree options))

(defn render!
  "Request a render pass for HANDLE's tree at the pump's next tick, so a
   namespace the mounted component fn calls is picked up after :reload.
   Safe from any thread — it only marks the tree dirty; every Tcl call
   stays on the loop's thread. No-op when nothing is mounted."
  [_handle]
  (request-render!))

(defn run-loop!
  "Block on the Tk event loop, pumping dirty frames into every mounted
   window's tree, and dispose them when the loop closes. Call it on the
   thread that owns the interpreter (the one that ran mount!)."
  [_handle]
  (require-adapter!)
  (try
    ((:run-loop *adapter*)
     (fn []
       (doseq [h @all-windows]
         ((:pump h)))))
    (finally
      (component/set-frame-hook! nil)
      (doseq [h @all-windows]
        (engine/dispose-component (:root h)))
      (doseq [p (distinct (map :window-path @all-windows))]
        (eval! (str "destroy " p)))
      (reset! all-windows #{}))))

(defn run!
  "Mount TREE in a Tk window and run the Tk event loop until the window is
   closed. OPTIONS are the :tk/toplevel tag's props (:title :width :height
   :bg :bind). Requires an adapter (bound by the caller — kmet.tk.tcl/run!
   installs the host FFI one). Blocks."
  [tree & [options]]
  (run-loop! (mount-tree! tk-backend tree options)))

(defn run-backend!
  "run! with BACKEND (see mount-backend!): mount TREE with its tag table
   and run the Tk event loop until the window closes."
  [backend tree & [options]]
  (run-loop! (mount-tree! backend tree options)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Dialogs
;; ═══════════════════════════════════════════════════════════════════════════
;; Tk's five native dialogs: tk_messageBox, tk_getOpenFile/tk_getSaveFile,
;; tk_chooseDirectory and tk_chooseColor — one imperative Tcl command each,
;; running Tcl's own nested event loop. A call blocks its caller but not
;; the UI (the nested loop keeps delivering events, the frame pump
;; included), so it must run on the thread that owns the interpreter: a
;; callback, not a component body, which renders inside the pump. Every
;; function below repeats that rule where a caller meets it.
;;
;; A dialog's props are its Tcl options, kebab-cased, and its vocabulary is
;; closed: a typo fails here instead of silently doing nothing.

(def ^:private message-box-types
  "Tk's message-box sets: :type -> {:tcl the -type word, :buttons what the
   box shows}. Tk spells the multi-button sets without a dash (okcancel);
   the keywords carry one."
  {:ok                 {:tcl "ok"               :buttons #{:ok}}
   :ok-cancel          {:tcl "okcancel"         :buttons #{:ok :cancel}}
   :yes-no             {:tcl "yesno"            :buttons #{:yes :no}}
   :yes-no-cancel      {:tcl "yesnocancel"      :buttons #{:yes :no :cancel}}
   :retry-cancel       {:tcl "retrycancel"      :buttons #{:retry :cancel}}
   :abort-retry-ignore {:tcl "abortretryignore" :buttons #{:abort :retry :ignore}}})

(def ^:private message-box-icons
  "The icons Tk's message box offers (the :icon prop)."
  #{:error :info :question :warning})

(def ^:private message-box-options
  "The message box's props -> Tk's options."
  {:message "-message" :detail "-detail" :title "-title" :type "-type"
   :icon "-icon" :default "-default"})

(def ^:private file-dialog-options
  "The props tk_getOpenFile and tk_getSaveFile share."
  {:title "-title" :initial-dir "-initialdir" :initial-file "-initialfile"
   :file-types "-filetypes" :default-extension "-defaultextension"})

(def ^:private open-file-options
  "The open-file dialog's props: the file dialog's plus :multiple."
  (assoc file-dialog-options :multiple "-multiple"))

(def ^:private directory-dialog-options
  "The directory dialog's props -> Tk's options."
  {:title "-title" :initial-dir "-initialdir" :must-exist "-mustexist"})

(def ^:private color-dialog-options
  "The colour dialog's props -> Tk's options."
  {:title "-title" :initial-color "-initialcolor"})

(defn- prop-names
  "PROPS (a collection of prop keywords) as \":a :b\" in name order, for
   an error message."
  [props]
  (str/join " " (sort (map #(str ":" (name %)) props))))

(defn- check-dialog-props!
  "Throw when PROPS names a prop COMMAND has no option for: a dialog's
   vocabulary is closed, so a typo would otherwise do nothing silently."
  [command options props]
  (when-some [unknown (seq (remove #(contains? options %) (keys props)))]
    (throw (ex-info (str "kmet.tk.core: " command " has no " (prop-names unknown)
                         " (props: " (prop-names (keys options)) ")")
                    {:type :kmet.tk.core/unknown-dialog-prop
                     :command command
                     :props (vec (sort-by name unknown))}))))

(defn- check-dialog-value!
  "Throw when V is not one of ALLOWED for PROP — a closed vocabulary, better
   reported here than by Tk after the dialog command has been built."
  [command prop v allowed]
  (when-not (contains? allowed v)
    (throw (ex-info (str "kmet.tk.core: " command " " prop " must be one of "
                         (prop-names allowed) ", not " (pr-str v))
                    {:type :kmet.tk.core/bad-dialog-value
                     :command command :prop prop :value v :allowed allowed}))))

(defn- file-types-value
  "V as Tk's -filetypes list syntax, checked first: Tk reads a malformed
   value as a filter with no extensions instead of complaining."
  [v]
  (when-not (and (sequential? v)
                 (every? #(and (sequential? %) (= 2 (count %))) v))
    (throw (ex-info (str "kmet.tk.core: :file-types must be a sequence of"
                         " [label extensions] pairs, not " (pr-str v))
                    {:type :kmet.tk.core/bad-file-types :value v})))
  (dsl/tcl-list v))

(defn- dialog-value
  "V as the Tcl value PROP's option takes: file types are Tcl list syntax,
   a message box's :type Tk's own spelling; everything else is the word it
   names (:info -> info, true -> 1)."
  [prop v]
  (case prop
    :file-types (file-types-value v)
    :type (:tcl (get message-box-types v))
    (dsl/tcl-word v)))

(defn- dialog-script
  "The `COMMAND -option value …` Tcl script for PROPS through OPTIONS, in
   prop-name order so the script is stable; a nil prop is absent and every
   value is quoted."
  [command options props]
  (check-dialog-props! command options props)
  (str/join " " (concat [command]
                        (for [[prop option] (sort-by (comp name key) options)
                              :let [v (get props prop)]
                              :when (some? v)]
                          (str option " " (dsl/tcl-quote (dialog-value prop v)))))))

(defn- dialog-answer
  "Tk's answer to the dialog SCRIPT: nil when the user cancelled (Tk
   answers the empty string), ANSWER-FN (default identity) on an answer."
  ([script] (dialog-answer script identity))
  ([script answer-fn]
   (let [s (eval! script)]
     (when (seq s) (answer-fn s)))))

(defonce ^:private next-dialog (atom 0))

(defn- dialog-list-answer
  "The elements of the Tcl list the dialog SCRIPT answered with, or nil when
   the list is empty (cancelled). Tcl itself splits the list — its string
   form braces elements containing spaces, and only Tcl reads that back
   exactly — and every element is then fetched with lindex."
  [script]
  (let [var (str "::kmet_dialog_" (swap! next-dialog inc))]
    (eval! (str "set " var " [" script "]"))
    (try
      (let [n (parse-long (eval! (str "llength $" var)))]
        (when (pos? n)
          (mapv #(eval! (str "lindex $" var " " %)) (range n))))
      (finally
        (eval! (str "unset " var))))))

(defn message-box!
  "Show Tk's native message box (tk_messageBox) and return the button the
   user chose — :ok, :cancel, :yes, :no, :abort, :retry or :ignore — or nil
   when the window was closed without one.

   PROPS are Tk's own options:
     :message  the text
     :detail   the small text under it
     :title    the window title
     :type     the button set: :ok (default), :ok-cancel, :yes-no,
               :yes-no-cancel, :retry-cancel, :abort-retry-ignore
     :icon     :info (default), :error, :question, :warning
     :default  the button Return picks, one of the :type's own

   Blocks until the user answers — Tk runs a nested event loop, so the UI
   stays live but the calling frame does not. Call it on the Tk thread (from
   a callback), not from a component body, which renders inside the pump."
  [props]
  (let [type (or (:type props) :ok)]
    (check-dialog-value! "message-box!" :type type (set (keys message-box-types)))
    (when-some [icon (:icon props)]
      (check-dialog-value! "message-box!" :icon icon message-box-icons))
    (when-some [default (:default props)]
      (check-dialog-value! "message-box!" :default default
                           (:buttons (get message-box-types type))))
    (dialog-answer (dialog-script "tk_messageBox" message-box-options props) keyword)))

(defn open-file!
  "Show Tk's native open-file dialog (tk_getOpenFile) and return the file the
   user chose (nil when cancelled); with :multiple true a vector of paths
   (nil when none was chosen).

   PROPS are Tk's own options: :title, :initial-dir (:initialdir),
   :initial-file, :default-extension, :file-types — [label extensions]
   pairs, EXTENSIONS one pattern or a vector — and :multiple to select
   several files.

   Blocks until the user answers; see message-box! for the thread rule."
  [props]
  (let [script (dialog-script "tk_getOpenFile" open-file-options props)]
    (if (:multiple props)
      (dialog-list-answer script)
      (dialog-answer script))))

(defn save-file!
  "Show Tk's native save-file dialog (tk_getSaveFile) and return the path the
   user chose, nil when cancelled. Tk asks before an existing file is
   overwritten where the platform does not already.

   PROPS are open-file!'s without :multiple.

   Blocks until the user answers; see message-box! for the thread rule."
  [props]
  (dialog-answer (dialog-script "tk_getSaveFile" file-dialog-options props)))

(defn choose-directory!
  "Show Tk's native directory dialog (tk_chooseDirectory) and return the
   directory the user chose, nil when cancelled.

   PROPS: :title, :initial-dir and :must-exist (true to accept only a
   directory that exists).

   Blocks until the user answers; see message-box! for the thread rule."
  [props]
  (dialog-answer (dialog-script "tk_chooseDirectory" directory-dialog-options props)))

(defn choose-color!
  "Show Tk's native colour dialog (tk_chooseColor) and return the colour the
   user chose as \"#rrggbb\", nil when cancelled.

   PROPS: :title and :initial-color, the colour the dialog opens with (a
   \"#rrggbb\" string or a Tk colour name such as :red).

   Blocks until the user answers; see message-box! for the thread rule."
  [props]
  (dialog-answer (dialog-script "tk_chooseColor" color-dialog-options props)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Window management (wm): entries on `:tk/toplevel`, plus imperative helpers.
;; The window IS Tk's own ".": everything here scripts `wm … .`.
;; ═══════════════════════════════════════════════════════════════════════════

(defn- size-pair
  "V as a [width height] pair for wm minsize/maxsize/resizable: a sequence of
   two, a map with :width/:height (or :w/:h), or a single value applied to
   both dimensions."
  [v]
  (cond
    (sequential? v) [(first v) (second v)]
    (map? v) [(or (:width v) (:w v)) (or (:height v) (:h v))]
    :else [v v]))

(defn- window-dummy
  "A TkWidget record for the root window, so image descriptions can be
   resolved against Tk's own \".\" (the window owns the image)."
  []
  (dsl/->TkWidget "." :tk/toplevel (atom []) (atom {}) (atom {})))

(defn- register-close-handler!
  "Register F as PATH's close handler and install it through Tk's
   WM_DELETE_WINDOW protocol. F returning true (or a truthy value) keeps
   the window open; false or nil destroys it (the run loop's default)."
  ([f] (register-close-handler! "." f))
  ([path f]
   (let [w (window-dummy)
         cb (dsl/register-callback! w :on-close f {:value nil :answer true})]
     (eval! (str "wm protocol " path " WM_DELETE_WINDOW { if {![ " cb "]} { destroy " path " } }")))))

(defn- apply-window-props-to!
  "Script the window-management props of the `:tk/toplevel` tag onto PATH."
  [path props]
  (when-some [v (or (get props :minimum-size) (get props :minsize))]
    (let [[w h] (size-pair v)]
      (eval! (str "wm minsize " path " " (dsl/tcl-value w) " " (dsl/tcl-value h)))))
  (when-some [v (or (get props :maximum-size) (get props :maxsize))]
    (let [[w h] (size-pair v)]
      (eval! (str "wm maxsize " path " " (dsl/tcl-value w) " " (dsl/tcl-value h)))))
  (when (contains? props :resizable)
    (let [v (:resizable props)
          [x y] (if (or (boolean? v) (number? v)) [v v] (size-pair v))]
      (eval! (str "wm resizable " path " " (dsl/tcl-word x) " " (dsl/tcl-word y)))))
  (when-some [v (or (get props :fullscreen) (get props :full-screen))]
    (eval! (str "wm attributes " path " -fullscreen " (dsl/tcl-word v))))
  (when-some [v (or (get props :topmost) (get props :always-on-top)
                    (get props :topmost?) (get props :always-on-top?))]
    (eval! (str "wm attributes " path " -topmost " (dsl/tcl-word v))))
  (when-some [v (get props :alpha)]
    (eval! (str "wm attributes " path " -alpha " (dsl/tcl-word v))))
  (when-some [v (get props :zoomed)]
    (eval! (str "wm attributes " path " -zoomed " (dsl/tcl-word v))))
  (when-some [v (get props :titlepath)]
    (eval! (str "wm attributes " path " -titlepath " (dsl/tcl-value v))))
  (when-some [v (or (get props :icon-photo) (get props :iconphoto))]
    (let [img (if (map? v) (dsl/image-name (window-dummy) :image v) v)
          default? (or (get props :icon-photo-default)
                       (get props :default-icon?) (get props :default))]
      (eval! (str "wm iconphoto " path " " (when default? "-default ") (dsl/tcl-value img)))))
  (when-some [v (get props :icon-name)]
    (eval! (str "wm iconname " path " " (dsl/tcl-quote v))))
  (when (contains? props :state)
    (case (:state props)
      (:normal nil) nil
      :iconic (eval! (str "wm iconify " path))
      :withdrawn (eval! (str "wm withdraw " path))
      (eval! (str "wm state " path " " (dsl/tcl-word (:state props))))))
  (when (contains? props :iconified)
    (if (:iconified props)
      (eval! (str "wm iconify " path))
      (eval! (str "wm deiconify " path))))
  (when (contains? props :withdrawn)
    (if (:withdrawn props)
      (eval! (str "wm withdraw " path))
      (eval! (str "wm deiconify " path))))
  (when (contains? props :on-close)
    (if-some [f (:on-close props)]
      (register-close-handler! path f)
      (eval! (str "wm protocol " path " WM_DELETE_WINDOW { destroy " path " }")))))

(defn iconify!
  "Iconify the root window."
  []
  (eval! "wm iconify ."))

(defn deiconify!
  "Deiconify (raise) the root window."
  []
  (eval! "wm deiconify ."))

(defn withdraw!
  "Withdraw (hide) the root window."
  []
  (eval! "wm withdraw ."))

(defn window-state
  "The root window's wm state: \"normal\", \"iconic\" or \"withdrawn\"."
  []
  (eval! "wm state ."))

(defn- wm-size-command!
  [cmd v]
  (let [[w h] (size-pair v)]
    (eval! (str cmd " . " (dsl/tcl-value w) " " (dsl/tcl-value h)))))

(defn minsize!
  "Set the root window's minimum size. Accepts a [w h] sequence, a map, a
   single value for both, or two separate arguments."
  ([v] (wm-size-command! "wm minsize" v))
  ([w h] (eval! (str "wm minsize . " (dsl/tcl-value w) " " (dsl/tcl-value h)))))

(defn maximum-size!
  "Set the root window's maximum size (minimum-size! on 8.6 raises it too)."
  ([v] (wm-size-command! "wm maxsize" v))
  ([w h] (eval! (str "wm maxsize . " (dsl/tcl-value w) " " (dsl/tcl-value h)))))

(defn maxsize!
  "Alias for maximum-size!."
  ([v] (maximum-size! v))
  ([w h] (maximum-size! w h)))

(defn resizable!
  "Set whether the root window is user-resizable. A boolean applies to both
   axes; a [width height] sequence or two boolean arguments set them
   separately."
  ([v]
   (let [v' (if (or (boolean? v) (number? v)) [v v] (size-pair v))
         [x y] v']
     (eval! (str "wm resizable . " (dsl/tcl-word x) " " (dsl/tcl-word y)))))
  ([x y]
   (eval! (str "wm resizable . " (dsl/tcl-word x) " " (dsl/tcl-word y)))))

(defn fullscreen!
  "Set the root window's fullscreen state."
  [v]
  (eval! (str "wm attributes . -fullscreen " (dsl/tcl-word v))))

(defn topmost!
  "Set whether the root window stays above others."
  [v]
  (eval! (str "wm attributes . -topmost " (dsl/tcl-word v))))

(defn always-on-top!
  "Alias for topmost!."
  [v]
  (topmost! v))

(defn alpha!
  "Set the root window's opacity (0.0–1.0)."
  [v]
  (eval! (str "wm attributes . -alpha " (dsl/tcl-word v))))

(defn zoomed!
  "Set the root window's zoomed (maximized) state where the platform
   supports it."
  [v]
  (eval! (str "wm attributes . -zoomed " (dsl/tcl-word v))))

(defn raise!
  "Raise the root window (optionally above WINDOW)."
  ([] (eval! "wm raise ."))
  ([above] (eval! (str "wm raise . " (dsl/tcl-value above)))))

(defn lower!
  "Lower the root window (optionally below WINDOW)."
  ([] (eval! "wm lower ."))
  ([below] (eval! (str "wm lower . " (dsl/tcl-value below)))))

(defn- wm-attr-name [k]
  (str "-" (name (get {:always-on-top :topmost
                       :always-on-top? :topmost
                       :topmost? :topmost} k k))))

(defn attributes!
  "Script wm attributes onto the root window. A map sets each key; two
   arguments set a single attribute name (a keyword) to a value."
  ([attrs]
   (doseq [[k v] attrs]
     (eval! (str "wm attributes . " (wm-attr-name k) " " (dsl/tcl-value v)))))
  ([k v]
   (eval! (str "wm attributes . " (wm-attr-name k) " " (dsl/tcl-value v)))))

(defn iconphoto!
  "Set the root window's icon image (a Tk name or a description map)."
  ([image] (iconphoto! image false))
  ([image x]
   (let [default? (if (map? x) (or (:default x) (:default? x)) x)
         img (if (map? image) (dsl/image-name (window-dummy) :image image) image)]
     (eval! (str "wm iconphoto . " (when default? "-default ") (dsl/tcl-value img))))))

(defn on-close!
  "Install F (or clear with nil) as `:tk/toplevel`'s close handler."
  [f]
  (if f
    (register-close-handler! f)
    (eval! "wm protocol . WM_DELETE_WINDOW { destroy . }")))

(defn wm-command!
  "Run an arbitrary `wm SUBCOMMAND …` script on the root window."
  [subcommand & args]
  (eval! (str "wm " (name subcommand) " . "
              (str/join " " (map dsl/tcl-value args)))))

(def minimumsize! minsize!)
(def maximumsize! maximum-size!)
(def max-size! maximum-size!)
(def min-size! minsize!)
(def minimum-size! minsize!)
(def iconify-window! iconify!)
(def deiconify-window! deiconify!)

;; ═══════════════════════════════════════════════════════════════════════════
;; Clipboard and primary selection (`clipboard`, `selection`).
;; ═══════════════════════════════════════════════════════════════════════════

(defn- widget-path [w] (if (and (map? w) (:path w)) (:path w) (str w)))

(defn- opt-words [m]
  (when (seq m)
    (str/join " " (mapcat (fn [[k v]] [(str "-" (name k)) (str (dsl/tcl-value v))]) m))))

(defn clipboard-clear!
  "Clear the clipboard. OPTIONS: :displayof (a window/widget)."
  [& {:keys [displayof]}]
  (eval! (str "clipboard clear"
              (when displayof (str " -displayof " (dsl/tcl-value (widget-path displayof)))))))

(defn clipboard-get
  "Read the clipboard (CLIPBOARD selection). OPTIONS: :displayof, :type."
  [& {:keys [displayof type]}]
  (eval! (str "clipboard get"
              (when displayof (str " -displayof " (dsl/tcl-value (widget-path displayof))))
              (when type (str " -type " (dsl/tcl-word type))))))

(defn clipboard-append!
  "Append DATA to the clipboard. OPTIONS (keyword args): :displayof, :format,
   :type."
  [data & {:keys [displayof format type]}]
  (eval! (str "clipboard append"
              (when displayof (str " -displayof " (dsl/tcl-value (widget-path displayof))))
              (when format (str " -format " (dsl/tcl-word format)))
              (when type (str " -type " (dsl/tcl-word type)))
              " -- " (dsl/tcl-quote (str data)))))

(defn clipboard-set!
  "Replace the clipboard's contents with DATA (clear + append)."
  [data & opts]
  (apply clipboard-clear! opts)
  (apply clipboard-append! data opts))

(defn selection-clear!
  "Clear the selection. OPTIONS: :displayof, :selection (:primary, :clipboard
   or a string)."
  [& {:keys [displayof selection]}]
  (eval! (str "selection clear"
              (when displayof (str " -displayof " (dsl/tcl-value (widget-path displayof))))
              (when selection (str " -selection " (dsl/tcl-word selection))))))

(defn selection-get
  "Read the selection. OPTIONS: :displayof, :selection, :type."
  [& {:keys [displayof selection type]}]
  (eval! (str "selection get"
              (when displayof (str " -displayof " (dsl/tcl-value (widget-path displayof))))
              (when selection (str " -selection " (dsl/tcl-word selection)))
              (when type (str " -type " (dsl/tcl-word type))))))

(defn selection-handle!
  "Registers CMD as the handler producing the SELECTION's data (advanced)."
  [window cmd & {:keys [selection type format]}]
  (eval! (str "selection handle"
              (when selection (str " -selection " (dsl/tcl-word selection)))
              (when type (str " -type " (dsl/tcl-word type)))
              (when format (str " -format " (dsl/tcl-word format)))
              " " (dsl/tcl-value (widget-path window)) " "
              (dsl/tcl-quote (str cmd)))))

(defn selection-own!
  "Take ownership of the selection for WINDOW. OPTIONS: :command, :selection."
  [window & {:keys [command selection]}]
  (eval! (str "selection own"
              (when selection (str " -selection " (dsl/tcl-word selection)))
              (when command (str " -command " (dsl/tcl-quote (str command))))
              " " (dsl/tcl-value (widget-path window)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Scaling, focus, busy, Tk resource (fonts) helpers.
;; ═══════════════════════════════════════════════════════════════════════════

(defn scaling
  "The Tk scaling factor."
  ([] (eval! "tk scaling"))
  ([factor] (eval! (str "tk scaling " (dsl/tcl-value factor))) (scaling)))

(defn set-scaling! [factor] (eval! (str "tk scaling " (dsl/tcl-value factor))))

(defn busy-hold!
  "Block input to W (and its descendants) with a busy cursor."
  [w & {:keys [cursor]}]
  (eval! (str "tk busy hold" (when cursor (str " -cursor " (dsl/tcl-word cursor)))
              " " (dsl/tcl-value (widget-path w)))))

(defn busy-release! [w]
  (eval! (str "tk busy release " (dsl/tcl-value (widget-path w)))))

(defn busy? [w]
  (eval! (str "tk busy status " (dsl/tcl-value (widget-path w)))))

(defn busy-configure! [w opts]
  (eval! (str "tk busy configure " (dsl/tcl-value (widget-path w)) " " (opt-words opts))))

(defn focus-set! [w]
  (eval! (str "focus " (dsl/tcl-value (widget-path w)))))

(defn focus-get [] (eval! "focus"))

(defn focus-next! [w]
  (eval! (str "tk_focusNext " (dsl/tcl-value (widget-path w)))))

(defn focus-prev! [w]
  (eval! (str "tk_focusPrev " (dsl/tcl-value (widget-path w)))))

(defn- tcl-seq [v]
  (if (sequential? v) (str/join " " (map dsl/tcl-value v)) (dsl/tcl-value v)))

(defn event-add!
  "Add virtual event <<VIRTUAL>> bound to the sequences."
  [virtual & sequences]
  (eval! (str "event add " (dsl/tcl-value virtual) " " (str/join " " (map tcl-seq sequences)))))

(defn event-delete! [virtual & [sequence]]
  (eval! (str "event delete " (dsl/tcl-value virtual)
              (when sequence (str " " (tcl-seq sequence))))))

(defn event-generate! [w event & [opts]]
  (eval! (str "event generate " (dsl/tcl-value (widget-path w)) " " (dsl/tcl-value event)
              (when (seq opts) (str " " (opt-words opts))))))

(defn event-info [virtual]
  (eval! (str "event info " (dsl/tcl-value virtual))))

(defn text-copy! [w] (eval! (str "tk_textCopy " (dsl/tcl-value (widget-path w)))))
(defn text-cut! [w] (eval! (str "tk_textCut " (dsl/tcl-value (widget-path w)))))
(defn text-paste! [w] (eval! (str "tk_textPaste " (dsl/tcl-value (widget-path w)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Named fonts (`font …`).
;; ═══════════════════════════════════════════════════════════════════════════

(defn font-create! [name opts]
  (eval! (str "font create " (dsl/tcl-word name)
              (when (seq opts) (str " " (opt-words opts))))))

(defn font-configure! [name opts]
  (eval! (str "font configure " (dsl/tcl-word name) " " (opt-words opts))))

(defn font-delete! [name]
  (eval! (str "font delete " (dsl/tcl-word name))))

(defn font-families [] (eval! "font families"))
(defn font-names [] (eval! "font names"))

(defn font-measure [name text]
  (eval! (str "font measure " (dsl/tcl-word name) " " (dsl/tcl-quote (str text)))))

(defn font-metrics [name]
  (eval! (str "font metrics " (dsl/tcl-word name))))

(defn font-actual [name & [option]]
  (eval! (str "font actual " (dsl/tcl-word name)
              (when option (str " " (dsl/tcl-word option))))))

;; virtual events as above.

;; ═══════════════════════════════════════════════════════════════════════════
;; Cross-app drag and drop (TkDnD, where the host provides it).
;; ═══════════════════════════════════════════════════════════════════════════

(defn dnd-available?
  "Whether the TkDnD package can be loaded into this interpreter."
  []
  (try (not (str/blank? ((:eval (require-adapter!)) "package require tkdnd")))
       (catch Exception _ false)))

(defn dnd-require!
  "Load TkDnD and return its version string."
  []
  (eval! "package require tkdnd"))

(defn dnd-version
  "The TkDnD version, or nil when it cannot be loaded."
  []
  (try (eval! "package require tkdnd")
       (catch Exception _ nil)))

(defn dnd-drag-source-register!
  "Register W as a drag source for the type list TYPES, firing HANDLER
   (optional) on a drag."
  [w types & [handler]]
  (eval! (str "tkdnd::drag_source register " (widget-path w)
              " " (dsl/tcl-value types)
              (when handler (str " " (dsl/tcl-quote (str handler)))))))

(defn dnd-drag-source-unregister! [w]
  (eval! (str "tkdnd::drag_source unregister " (widget-path w))))

(defn dnd-drop-target-register!
  "Register W as a drop target for TYPES, firing HANDLER on a drop."
  [w types & [handler]]
  (eval! (str "tkdnd::drop_target register " (widget-path w)
              " " (dsl/tcl-value types)
              (when handler (str " " (dsl/tcl-quote (str handler)))))))

(defn dnd-drop-target-unregister! [w]
  (eval! (str "tkdnd::drop_target unregister " (widget-path w))))

(def dnd-drag-source! dnd-drag-source-register!)
(def dnd-drop-target! dnd-drop-target-register!)
