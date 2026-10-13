(ns kmet.gui.tk
  "Tk (Tcl/Tk 8.6+; the adapter prefers 9.1) backend for the generic
   hiccup engine — \"hiccup-tk\".

   Toolkit code only: the tag table, widget records and Tcl script building.
   The Tcl interpreter itself is reached through *adapter* (an
   implementation-detail seam so the backend is testable headlessly): an
   adapter is a map

     {:eval     (fn [script] -> result string)
      :command! (fn [name handler] -> nil)   ; handler: (fn [argv] -> string)
      :run-loop (fn [pump-fn] -> nil)        ; blocks; pump on the UI thread
      :destroy! (fn [] -> nil)}              ; optional teardown

   kmet.gui.tcl supplies the real host FFI adapter (babashka.ffi on
   babashka, jolt.ffi on Jolt); tests bind a recording stub.

   The engine's :render-items/:render-all hooks exist for output-producing
   toolkits; Tk widgets are live the moment reconcile constructs them, so
   they return nil and kmet.gui.tcl drives re-renders with a pump armed
   through kmet.libs.hikap.component/set-frame-hook!."
  (:require [kmet.libs.hikap.component :as component]
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
  "Install ADAPTER as the process adapter (kmet.gui.tcl calls this)."
  [adapter]
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
  [script]
  ((:eval (require-adapter!)) script))

(defn- command!
  [name handler]
  ((:command! (require-adapter!)) name handler))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tcl quoting
;; ═══════════════════════════════════════════════════════════════════════════

(defn- escape-char
  "One character of a Tcl word, backslash-escaped so it survives every
   substitution context. Backslash first; \\n/\\t/\\r are Tcl escape
   sequences (a backslash followed by a literal newline would be a line
   continuation instead)."
  [c]
  (case c
    \\ "\\\\"
    \newline "\\n"
    \tab "\\t"
    \return "\\r"
    (\space \{ \} \[ \] \$ \; \" \#) (str "\\" c)
    (str c)))

(defn tcl-quote
  "Quote X as a single Tcl word (always backslash-escaped; safe in any
   substitution context)."
  [x]
  (apply str (map escape-char (str x))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Widget records + callback registry
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord TkWidget [path tag kids callbacks]
  ;; Records are maps, so the engine can stamp :dsl/meta onto the instance.
  )

(defn widget-path
  "The Tk path of WIDGET (\"\\.\" for the toplevel)."
  [widget]
  (:path widget))

(defonce ^:private next-id (atom 0))
(defonce ^:private next-callback (atom 0))
(defonce ^:private callbacks (atom {}))

(defn- alloc-path!
  "A fresh child path under PARENT (a TkWidget or nil = the toplevel)."
  [parent]
  (let [base (if (some? parent) (:path parent) ".")
        n (swap! next-id inc)]
    (if (= base ".")
      (str ".w" n)
      (str base ".w" n))))

(defn- register-callback!
  "Register F as Tk-callable and return its command argument: the script
   `kmet::cb <id>`. The id is recorded on WIDGET so dispose can release
   it."
  [widget f]
  (let [id (swap! next-callback inc)]
    (swap! callbacks assoc id f)
    (when (some? widget)
      (swap! (:callbacks widget) conj id))
    (str "kmet::cb " id)))

(defn dispatch-callback!
  "The single command handler behind `kmet::cb`: ARGV is
   [\"kmet::cb\" id & args]. Returns the handler's result string (\"\")."
  [argv]
  (let [id (some-> (second argv) parse-long)
        f (get @callbacks id)]
    (if (nil? f)
      ""
      (str (or (apply f (drop 2 argv)) "")))))

(defn install-callback-command!
  "Register the `kmet::cb` trampoline with the adapter. Idempotent per
   interpreter; run! calls it at start."
  []
  (command! "kmet::cb" dispatch-callback!)
  nil)

(defn- release-callbacks!
  [widget]
  (doseq [id @(:callbacks widget)]
    (swap! callbacks dissoc id))
  (reset! (:callbacks widget) []))

;; ═══════════════════════════════════════════════════════════════════════════
;; Script fragments
;; ═══════════════════════════════════════════════════════════════════════════

(defn- configure-script
  "A `$w configure` script for PROPS' entries whose key is in OPTIONS
   ({:clj-key \"-tk-option\"})."
  [w props options]
  (let [args (for [[k opt] options
                   :let [v (get props k)]
                   :when (some? v)]
               (str " " opt " " (tcl-quote (if (boolean? v) (if v 1 0) v))))]
    (when (seq args)
      (str w " configure" (apply str args)))))

(defn- parent-widget
  "The container being constructed into, from the engine's construction
   context (nil at the root)."
  []
  (:parent engine/*construct-context*))

(defn- make-widget
  "Construct a TkWidget at a fresh path under the current construction
   parent, run the ctor SCRIPT (a fn of the path) and pack it into the
   parent."
  [tag ctor-script]
  (let [parent (parent-widget)
        path (alloc-path! parent)
        w (->TkWidget path tag (atom []) (atom []))]
    (eval! (ctor-script path))
    (when parent
      (eval! (str "pack " path " -side "
                  (if (= (:tag parent) :hbox) "left" "top")
                  " -fill " (if (#{:vbox :hbox :frame} tag) "both" "x"))))
    w))

(defn- child-lens
  "The engine's children lens over a TkWidget's stored raw children."
  []
  {:get (fn [w] @(:kids w))
   :put (fn [w items] (reset! (:kids w) (mapv :c items)))})

(defn destroy!
  "Dispose a TkWidget: recurse into its stored children (releasing their
   callbacks), then destroy the widget path."
  [w]
  (when (some? w)
    (release-callbacks! w)
    (doseq [k @(:kids w)]
      (destroy! k))
    (reset! (:kids w) [])
    (when-not (= "." (:path w))
      (eval! (str "destroy " (:path w))))))

(defn- apply-config!
  "Run SCRIPT when it is non-nil, then report the patch as applied (the
   engine keeps the live instance when :apply returns truthy)."
  [script]
  (when script (eval! script))
  true)

(def ^:private tags
  {:window
   {:ctor (fn [{:keys [title width height]}]
            (when title (eval! (str "wm title . " (tcl-quote title))))
            (when width
              (eval! (str "wm geometry . " (tcl-quote width) "x"
                          (tcl-quote (or height 400)))))
            (->TkWidget "." :window (atom []) (atom [])))
    :lens (child-lens)}

   :vbox
   {:ctor (fn [{:keys [padding]}]
            (let [w (make-widget :vbox (fn [p] (str "ttk::frame " p)))]
              (when (some? padding)
                (eval! (configure-script (:path w) {:padding padding}
                                         {:padding "-padding"})))
              w))
    :lens (child-lens)
    :apply (fn [w prev props]
             (if (= (:padding prev) (:padding props))
               true
               (apply-config! (configure-script (:path w) props
                                                {:padding "-padding"}))))}

   :hbox
   {:ctor (fn [_props] (make-widget :hbox (fn [p] (str "ttk::frame " p))))
    :lens (child-lens)
    :apply (fn [_w _prev _props] true)}

   :frame
   {:ctor (fn [{:keys [label]}]
            (make-widget :frame
                         (fn [p]
                           (if (some? label)
                             (str "ttk::labelframe " p " -text " (tcl-quote label))
                             (str "ttk::frame " p)))))
    :lens (child-lens)
    :apply (fn [_w _prev _props] true)}

   :label
   {:ctor (fn [{:keys [text]}]
            (make-widget :label
                         (fn [p] (str "ttk::label " p " -text "
                                      (tcl-quote (or text ""))
                                      " -anchor w -justify left"))))
    :primary :text}

   :button
   {:ctor (fn [{:keys [label on-click]}]
            (let [w (make-widget :button
                                 (fn [p]
                                   (str "ttk::button " p " -text "
                                        (tcl-quote (or label "")))))]
              (when on-click
                (eval! (str (:path w) " configure -command {"
                            (register-callback! w on-click) "}")))
              w))
    :primary :label
    :apply (fn [w _prev props]
             (apply-config! (configure-script (:path w) props {:label "-text"})))}

   :entry
   {:ctor (fn [{:keys [text on-change on-activate]}]
            (let [w (make-widget :entry (fn [p] (str "ttk::entry " p)))]
              (when (some? text)
                (eval! (str (:path w) " insert 0 " (tcl-quote text))))
              (when on-change
                (eval! (str "bind " (:path w)
                            " <KeyRelease> {" (register-callback! w on-change) "}")))
              (when on-activate
                (eval! (str "bind " (:path w)
                            " <Return> {" (register-callback! w on-activate) "}")))
              w))
    :primary :text
    :apply (fn [w _prev props]
             (when (contains? props :text)
               (eval! (str (:path w) " delete 0 end"))
               (eval! (str (:path w) " insert 0 " (tcl-quote (:text props)))))
             true)}

   :checkbutton
   {:ctor (fn [{:keys [label active on-toggled]}]
            (let [w (make-widget :checkbutton
                                 (fn [p]
                                   (str "ttk::checkbutton " p " -text "
                                        (tcl-quote (or label "")))))]
              (eval! (str (:path w) " state "
                          (if active "selected" "!selected")))
              (when on-toggled
                (eval! (str (:path w) " configure -command {"
                            (register-callback! w on-toggled) "}")))
              w))
    :primary :label
    :apply (fn [w _prev props]
             (when (contains? props :active)
               (eval! (str (:path w) " state "
                           (if (:active props) "selected" "!selected"))))
             true)}

   :separator
   {:ctor (fn [_props] (make-widget :separator (fn [p] (str "ttk::separator " p))))}

   :progressbar
   {:ctor (fn [{:keys [value maximum]}]
            (let [w (make-widget :progressbar
                                 (fn [p] (str "ttk::progressbar " p
                                              " -maximum " (or maximum 100))))]
              (when (some? value)
                (eval! (str (:path w) " configure -value " (tcl-quote value))))
              w))
    :apply (fn [w _prev props]
             (when (some? (:value props))
               (eval! (str (:path w) " configure -value "
                           (tcl-quote (:value props)))))
             true)}

   :text
   {:ctor (fn [{:keys [text]}]
            (let [w (make-widget :text
                                 (fn [p]
                                   (str "text " p " -wrap word -height 10"
                                        " -state normal")))]
              (when (some? text)
                (eval! (str (:path w) " insert 1.0 " (tcl-quote text))))
              (eval! (str (:path w) " configure -state disabled"))
              w))
    :primary :text
    :apply (fn [w _prev props]
             (when (contains? props :text)
               (eval! (str (:path w) " configure -state normal"))
               (eval! (str (:path w) " delete 1.0 end"))
               (eval! (str (:path w) " insert 1.0 " (tcl-quote (:text props))))
               (eval! (str (:path w) " configure -state disabled")))
             true)}})

(defn- text-leaf
  "A bare string child renders as a plain label."
  [text _props]
  (make-widget :label
               (fn [p] (str "ttk::label " p " -text " (tcl-quote (or text ""))
                            " -anchor w -justify left"))))

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

(defn run!
  "Mount TREE in a Tk window and run the Tk event loop until the window is
   closed. OPTIONS: :title :width :height. Requires an adapter (bound by
   the caller — kmet.gui.tcl/run! installs the host FFI one). Blocks."
  [tree & [options]]
  (require-adapter!)
  (when-let [prepare! (:prepare! *adapter*)]
    (prepare!))
  (install-callback-command!)
  (let [window (engine/compile-element tk-backend [:window (or options {})])
        root (engine/root tk-backend tree)
        render-root! (fn []
                       (binding [engine/*construct-context* {:parent window}]
                         (engine/render-component root nil)))]
    (reset! dirty? false)
    (component/set-frame-hook! request-render!)
    (render-root!)
    (try
      ((:run-loop *adapter*)
       (fn [] (when (compare-and-set! dirty? true false) (render-root!))))
      (finally
        (component/set-frame-hook! nil)
        (engine/dispose-component root)
        (eval! "destroy .")))))
