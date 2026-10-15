(ns kmet.gui.tk
  "Tk (Tcl/Tk 8.6+; the adapter prefers 9.1) backend for the generic
   hiccup engine — \"hiccup-tk\": the backend itself plus the classic-widget
   tag set (`:tk/…`). The themed widgets are kmet.gui.ttk's `:ttk/…` set,
   the machinery both are written against is kmet.gui.tk.dsl, and the Tk
   option vocabulary is kmet.gui.tk.options. Every tag here is a Tk widget
   class; kmet's own widgets and composites are a separate UI namespace
   over this backend (kmet.gui.ui), which mounts through mount-backend! /
   run-backend!. Tag names follow Tcl's own namespace split, so a widget's
   toolkit is in its name: `:tk/frame` is the classic frame, `:ttk/frame`
   the themed one, and `:tk/text` has no ttk counterpart. The contract —
   props and callbacks, the option families and the run loop — is
   gui/README.md.

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

   The engine's :render-items/:render-all hooks exist for output-producing
   toolkits; Tk widgets are live the moment reconcile constructs them, so
   they return nil and kmet.gui.tcl drives re-renders with a pump armed
   through kmet.libs.hikap.component/set-frame-hook!."
  (:require [clojure.string :as str]
            [kmet.gui.tk.dsl :as dsl]
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

;; ═══════════════════════════════════════════════════════════════════════════
;; The classic widget set (:tk/…)
;; ═══════════════════════════════════════════════════════════════════════════

;; ═══════════════════════════════════════════════════════════════════════════
;; The classic widget set (:tk/…)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private canvas-image-options
  "The canvas-item options that name an image: the item's own vocabulary,
   taking the same values an image prop does (a Tk image name, or a
   description the canvas widget owns)."
  #{:image :bitmap :disabledimage})

(defn- canvas-items!
  "Replace canvas W's items with PROPS' :items — `{:type :line :coords [x y …]}`
   plus the item's own Tcl canvas options (`-fill`, `-width`, `-text`,
   `-anchor`, …), which are Tk's canvas-item vocabulary, not the widget
   catalogue's. An image option's description is created as a resource of
   W, so it survives the redraw and is released with the widget."
  [w props]
  (dsl/eval! (str (:path w) " delete all"))
  (doseq [{:keys [type coords] :as item} (or (:items props) [])]
    (let [opts (into {}
                     (map (fn [[k v]]
                            [k (if (contains? canvas-image-options k)
                                 (dsl/image-name w k v)
                                 v)]))
                     (dissoc item :type :coords))]
      (dsl/eval! (str (:path w) " create " (dsl/tcl-word (or type :line))
                      (when (seq coords)
                        (str " " (str/join " "
                                           (map #(dsl/tcl-quote (dsl/tcl-word %)) coords))))
                      (when (seq opts)
                        (str " " (str/join " "
                                           (for [[k v] (sort-by (comp name key) opts)]
                                             (str "-" (name k) " "
                                                  (dsl/tcl-quote (dsl/tcl-word v))))))))))))

(defn- scale-value!
  "Set classic scale W to PROPS' :value (a scale's value is a subcommand,
   not an option)."
  [w props]
  (when (some? (:value props))
    (dsl/eval! (str (:path w) " set " (dsl/tcl-quote (dsl/tcl-word (:value props)))))))

(defn- listbox-items!
  "Replace listbox W's items with PROPS' :items."
  [w props]
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
  "Give W a wired vertical scrollbar when PROPS ask for one."
  [w props]
  (when (:scrollbar props)
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
   widgets and composites live in kmet.gui.ui, not here."
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
                              {:wrap :word :height 10 :width 80
                               :borderwidth 0 :highlightthickness 0
                               :font (dsl/font-spec (dsl/resolve-base-font props) {})})
                     :commands {:content dsl/set-text-content!
                                :text dsl/set-text-content!}
                     :after maybe-scrollbar!})

   :tk/button
   (dsl/widget-spec "button"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :default :overrelief
                                                :repeatdelay :repeatinterval])
                     :bindings {:on-click {:option :command}}
                     :primary :text})

   :tk/label
   (dsl/widget-spec "label"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :textangle])
                     :bindings {:on-click {:event "<Button-1>"}}
                     :primary :text})

   :tk/labelframe
   (dsl/widget-spec "labelframe"
                    {:options (opt/options-for [:standard :classed :creation :geometry
                                                :bordered :highlighted :spaced :fonted]
                                               [:bg :fg :text :labelanchor])
                     :lens (dsl/child-lens)
                     :primary :text})

   :tk/message
   (dsl/widget-spec "message"
                    {:options (opt/options-for [:standard :bordered :highlighted
                                                :spaced :fonted]
                                               [:bg :fg :text :anchor :aspect :justify
                                                :width])
                     :primary :text})

   :tk/checkbutton
   (dsl/widget-spec "checkbutton"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :selectcolor :indicatoron
                                                :offrelief :overrelief :selectimage
                                                :tristateimage])
                     :bindings dsl/check-bindings
                     :checkable :check})

   :tk/radiobutton
   (dsl/widget-spec "radiobutton"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :selectcolor :indicatoron
                                                :offrelief :overrelief :value :selectimage
                                                :tristateimage])
                     :bindings dsl/radio-bindings
                     :checkable :radio})

   :tk/entry
   (dsl/widget-spec "entry"
                    {:options (opt/options-for [:standard :bordered :highlighted
                                                :fonted :entry-body]
                                               [:bg :fg :width :state :show])
                     :bindings dsl/text-field-bindings
                     :text-field true})

   :tk/spinbox
   (dsl/widget-spec "spinbox"
                    {:options (opt/options-for [:standard :bordered :highlighted
                                                :fonted :entry-body]
                                               [:bg :fg :width :state :activebackground
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
                                               [:bg :fg :activestyle :justify :selectmode
                                                :setgrid :state :disabledforeground
                                                :inactiveselectforeground])
                     :fixed {:height 8}
                     :bindings {:on-select {:event "<<ListboxSelect>>" :value :index}}
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
                     :commands {:items canvas-items!}
                     :after maybe-scrollbar!})

   :tk/menubutton
   (dsl/widget-spec "menubutton"
                    {:options (opt/options-for [:standard :geometry :highlighted
                                                :bordered :spaced :fonted
                                                :classic-text :stateful :imaged]
                                               [:bg :fg :text :direction :indicatoron])
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
                                                        (dsl/tcl-quote (dsl/tcl-word minsize))))
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
  "The Tk backend map for kmet.libs.hikap."
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

(defonce ^:private dirty? (atom false))

(defn- request-render!
  "The frame hook Tk installs (any thread may call it): mark the tree dirty
   and let the pump on the Tk thread do the rendering."
  []
  (reset! dirty? true)
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
  (let [window (engine/compile-element backend [:tk/toplevel (or options {})])
        root (engine/root backend tree)
        pump (fn []
               (when (compare-and-set! dirty? true false)
                 (binding [engine/*construct-context* {:parent window}]
                   (engine/render-component root nil))))]
    (reset! dirty? true)
    (component/set-frame-hook! request-render!)
    (pump)
    {:window window :root root :pump pump}))

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
  (mount-tree! tk-backend tree options))

(defn mount-backend!
  "Mount TREE with BACKEND — kmet.gui.tk/tk-backend, or one whose :tags add
   kmet's own widgets on top of it (kmet.gui.ui/backend). Same contract as
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
  (run-loop! (mount-tree! tk-backend tree options)))

(defn run-backend!
  "run! with BACKEND (see mount-backend!): mount TREE with its tag table
   and run the Tk event loop until the window closes."
  [backend tree & [options]]
  (run-loop! (mount-tree! backend tree options)))
