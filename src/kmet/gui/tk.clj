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
  (:require [clojure.string :as str]
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

(defrecord TkWidget [path tag kids callbacks styles]
  ;; Records are maps, so the engine can stamp :dsl/meta onto the instance.
  ;; STYLES is the per-widget cache of derived Tk resources — the generated
  ;; text tags (and the named fonts behind them) the :text tag inserts with.
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

(defn- register-callback-id!
  "Register F as Tk-callable and return its id. The id is recorded on
   WIDGET so dispose (or a rebind) can release it."
  [widget f]
  (let [id (swap! next-callback inc)]
    (swap! callbacks assoc id f)
    (when (some? widget)
      (swap! (:callbacks widget) conj id))
    id))

(defn- release-callback!
  "Drop the Tk-callable registered under ID for WIDGET (a no-op when it is
   already gone)."
  [widget id]
  (swap! callbacks dissoc id)
  (swap! (:callbacks widget) (fn [ids] (vec (remove #{id} ids)))))

(defn- set-callback
  "Register F as the callback WIDGET stores under KEY (one per binding
   slot), releasing whatever that slot held before, and return its
   `kmet::cb <id>` argument. Rebinding matters: Tk's bind (and -command)
   replaces the script, so without the release the old command would stay
   registered until dispose."
  [widget key f]
  (when-some [old (get-in @(:styles widget) [:callbacks key])]
    (release-callback! widget old))
  (let [id (register-callback-id! widget f)]
    (swap! (:styles widget) assoc-in [:callbacks key] id)
    (str "kmet::cb " id)))

(defn- bind-click!
  "Point WIDGET's <Button-1> at F."
  [widget f]
  (eval! (str "bind " (:path widget) " <Button-1> {"
              (set-callback widget :click f) "}")))

(defn- bind-key!
  "Bind EVENT on WIDGET to F, keeping one callback per KEY (see
   set-callback)."
  [widget event key f]
  (eval! (str "bind " (:path widget) " " event " {"
              (set-callback widget key f) "}")))

(defn- set-command!
  "Point WIDGET's -command (buttons, checkbuttons) at F."
  [widget f]
  (eval! (str (:path widget) " configure -command {"
              (set-callback widget :command f) "}")))

(defn- props-diff
  "The prop keys whose value differs between PREV and PROPS."
  [prev props]
  (remove #(= (get prev %) (get props %))
          (distinct (concat (keys prev) (keys props)))))

(defn- patchable?
  "True when every key that changed between PREV and PROPS is in KEYS — the
   props the live widget can express. A prop it cannot (a font, a geometry,
   a re-wrap width) must decline so the engine rebuilds instead of
   silently dropping the change."
  [prev props keys]
  (every? keys (props-diff prev props)))

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
   parent. PROPS (optional) control the pack geometry: :fill (default both
   for pane containers, x for leaves) and :expand."
  [tag ctor-script & [props]]
  (let [props (or props {})
        parent (parent-widget)
        path (alloc-path! parent)
        w (->TkWidget path tag (atom []) (atom []) (atom {}))]
    (eval! (ctor-script path))
    (when parent
      (if-let [attach (:attach @(:styles parent))]
        ;; containers that own their child placement (the :transcript text)
        (attach path)
        (eval! (str "pack " path " -side "
                    (if (= (:tag parent) :hbox) "left" "top")
                    " -fill " (name (or (:fill props)
                                        (if (#{:vbox :hbox :frame} tag) :both :x)))
                    (when (:expand props) " -expand 1")))))
    w))

(defn- child-lens
  "The engine's children lens over a TkWidget's stored raw children."
  []
  {:get (fn [w] @(:kids w))
   :put (fn [w items] (reset! (:kids w) (mapv :c items)))})

(defn- dispose-widget-extras!
  "Release resources a widget created outside its Tk path — the sibling
   scrollbar the :text/:transcript tags pack next to the widget."
  [w]
  (when-some [sb (:scrollbar @(:styles w))]
    (eval! (str "destroy " (tcl-quote sb)))))

(defn destroy!
  "Dispose a TkWidget: recurse into its stored children (releasing their
   callbacks), then destroy the widget path and any derived resources."
  [w]
  (when (some? w)
    (release-callbacks! w)
    (doseq [k @(:kids w)]
      (destroy! k))
    (reset! (:kids w) [])
    (when-not (= "." (:path w))
      (eval! (str "destroy " (:path w))))
    (dispose-widget-extras! w)))

(defn- apply-config!
  "Run SCRIPT when it is non-nil, then report the patch as applied (the
   engine keeps the live instance when :apply returns truthy)."
  [script]
  (when script (eval! script))
  true)

;; ─── Rich text (the :text tag) ────────────────────────────────────────────
;; A :text widget displays a plain :text string or a :content vector of
;; styled segments {:text s :fg "#rrggbb" :bg ... :bold :italic :underline
;; :overstrike}. Each distinct style gets one Tk tag, configured on first
;; use and cached on the widget; segments insert with that tag, and
;; bold/italic picks a font description derived from Tk's TkFixedFont (the
;; platform's terminal font) unless :font-family/:font-size override it.

;; Resolved base mono fonts by [family size] prop pair — the values are
;; platform constants, so the cache outlives an interpreter restart.
(defonce ^:private base-fonts (atom {}))

(defn- resolve-base-font
  "The {:family :size} a widget's generated tags derive from: the
   :font-family/:font-size props, else Tk's TkFixedFont actual values."
  [{:keys [font-family font-size]}]
  (let [key [font-family font-size]]
    (or (get @base-fonts key)
        (let [font {:family (or font-family (eval! "font actual TkFixedFont -family"))
                    :size (or font-size (eval! "font actual TkFixedFont -size"))}]
          (when (and (seq (:family font)) (seq (str (:size font))))
            (swap! base-fonts assoc key font))
          font))))

(defn- font-spec
  "A braced Tcl font description for base font FONT with STYLE's bold and
   italic flags. The family is its own braced list element so a multi-word
   family (TkFixedFont is Courier New on Windows) stays one word."
  [{:keys [family size]} {:keys [bold italic]}]
  (str "{{" family "} " size (when bold " bold") (when italic " italic") "}"))

(defn- style-options
  "The `tag configure` option string for STYLE in W."
  [w style]
  (let [{:keys [fg bg bold italic underline overstrike]} style]
    (str/join " "
              (cond-> []
                (some? fg) (conj (str "-foreground " (tcl-quote fg)))
                (some? bg) (conj (str "-background " (tcl-quote bg)))
                (or bold italic) (conj (str "-font " (font-spec (get-in @(:styles w) [:font]) style)))
                underline (conj "-underline 1")
                overstrike (conj "-overstrike 1")))))

(defn- segment-style
  "The style keys of SEG (the tag's identity)."
  [seg]
  (select-keys seg [:fg :bg :bold :italic :underline :overstrike]))

(defn- style-tag!
  "The Tk tag for STYLE in W, configured on first use; nil when unstyled."
  [w style]
  (when (seq style)
    (or (get (:tags @(:styles w)) style)
        (let [tag (str "k" (:next @(:styles w) 0))]
          (eval! (str (:path w) " tag configure " tag " "
                      (style-options w style)))
          (swap! (:styles w) (fn [s] (-> s (assoc-in [:tags style] tag) (update :next (fnil inc 0)))))
          tag))))

(defn- insert-script
  "The `insert end` command for SEG, or nil for empty text."
  [w seg]
  (let [text (str (:text seg))]
    (when (pos? (count text))
      (str (:path w) " insert end " (tcl-quote text)
           (when-some [tag (style-tag! w (segment-style seg))]
             (str " " tag))))))

(defn- text-props
  "Props normalized for script building: a keyword :wrap becomes its Tcl
   word (\"word\", \"none\", \"char\")."
  [props]
  (cond-> props
    (keyword? (:wrap props)) (update :wrap name)))

(def ^:private text-options
  "The :text tag's patchable Tcl options."
  {:wrap "-wrap" :height "-height" :bg "-background" :fg "-foreground"
   :padx "-padx" :pady "-pady"})

(defn- fit-height!
  "Grow W's -height to the display lines its content occupies when a line
   is longer than the widget's width and Tk therefore wraps it: a text
   widget sized to its logical line count would clip the wrapped tail.
   Wrapping normally happens in the renderer (see kmet.app.ui.gui.transcript)
   and the round trip is skipped whenever every line fits — which is the
   whole transcript in the common case."
  [w props lines]
  (let [width (or (:width props) 80)]
    (when (and (not= "none" (str (:wrap props)))
               ;; a character count is the backend's safe cheap proxy:
               ;; wide glyphs only ever make Tk wrap earlier
               (some #(> (count %) width) lines))
      (eval! (str (:path w) " configure -height [expr {max 1 [lindex ["
                  (:path w) " count -displaylines 1.0 end] 0]}]")))))

(defn- set-text-content!
  "Replace W's content with PROPS' :content segments (or a plain :text
   string). Read-only afterwards (`-state disabled`) but still selectable."
  [w props]
  (let [props (text-props props)
        segs (cond (some? (:content props)) (:content props)
                   (some? (:text props)) [{:text (:text props)}]
                   :else nil)]
    (eval! (str (:path w) " configure -state normal"))
    (eval! (str (:path w) " delete 1.0 end"))
    (when-some [script (and (seq segs)
                            (not-empty (str/join "\n" (keep #(insert-script w %) segs))))]
      (eval! script))
    (fit-height! w props (map :text segs))
    (eval! (str (:path w) " configure -state disabled"))))

(defn- attach-scrollbar!
  "Give W a vertical ttk scrollbar wired both ways, packed as W's sibling
   (a Tk pack master must be the slave's own parent, so the scrollbar gets
   a path under the same parent, not under the widget). The scrollbar is
   packed first — pack allocates in order, so the text takes the rest."
  [w parent]
  (let [p (:path w)
        sb (alloc-path! parent)]
    (eval! (str "ttk::scrollbar " sb " -orient vertical -command \"" p " yview\""))
    (eval! (str p " configure -yscrollcommand \"" sb " set\""))
    (eval! (str "pack forget " p))
    (eval! (str "pack " sb " -side right -fill y"))
    (eval! (str "pack " p " -side left -fill both -expand 1"))
    (swap! (:styles w) assoc :scrollbar sb)))

;; ─── Transcript stack (the :transcript tag) ──────────────────────────────
;; One text widget hosts every child as an embedded window (each message is
;; its own widget, on its own stretched line), so the chat scrolls natively
;; — no canvas, and no drawing lines. `:attach` in the widget's :styles is
;; how make-widget learns to embed instead of pack.

(defn- make-transcript
  "Construct the :transcript composite: one text widget whose embedded
   windows are the child widgets (each on its own line), plus its
   scrollbar. Text scrolling has no canvas coordinate limit, so this is the
   scroll container for long stacks (a chat transcript)."
  [props]
  (let [parent (parent-widget)
        text (alloc-path! parent)
        sb (alloc-path! parent)
        font (resolve-base-font props)
        bg (:bg props)
        w (->TkWidget text :transcript (atom []) (atom []) (atom {}))]
    (eval! (str "text " text
                " -wrap none -height 1 -width 1 -padx 0 -pady 0"
                " -highlightthickness 0 -borderwidth 0 -takefocus 0"
                " -font " (font-spec font {})
                (when (some? bg) (str " -background " (tcl-quote bg)))))
    (eval! (str "ttk::scrollbar " sb " -orient vertical -command \"" text " yview\""))
    (eval! (str text " configure -yscrollcommand \"" sb " set\""))
    (when (:see-end props)
      (eval! (str "bind " text " <Map> {after idle {"
                  text " see end"
                  (when (:read-only props)
                    (str "; " text " configure -state disabled"))
                  "}}")))
    (swap! (:styles w) assoc
           :font font
           :scrollbar sb
           :attach (fn [p]
                     (eval! (str text " window create end -window " p " -pady 2"))
                     (eval! (str text " insert end \"\\n\""))))
    (when parent
      (eval! (str "pack " sb " -side right -fill y"))
      (eval! (str "pack " text " -side left -fill "
                  (name (or (:fill props) :both))
                  (when-not (false? (:expand props)) " -expand 1"))))
    w))

(def ^:private tags
  {:window
   {:ctor (fn [{:keys [title width height bg]}]
            (when title (eval! (str "wm title . " (tcl-quote title))))
            (when bg (eval! (str ". configure -background " (tcl-quote bg))))
            (when width
              (eval! (str "wm geometry . " (tcl-quote width) "x"
                          (tcl-quote (or height 400)))))
            (->TkWidget "." :window (atom []) (atom []) (atom {})))
    :lens (child-lens)}

   :transcript
   {:ctor (fn [props] (make-transcript props))
    :lens (child-lens)}

   :vbox
   {:ctor (fn [{:keys [padding] :as props}]
            (let [w (make-widget :vbox (fn [p] (str "ttk::frame " p)) props)]
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
   {:ctor (fn [_props] (make-widget :hbox (fn [p] (str "ttk::frame " p)) _props))
    :lens (child-lens)
    :apply (fn [_w _prev _props] true)}

   :frame
   {:ctor (fn [{:keys [label border border-color bg padx pady] :as props}]
            (make-widget :frame
                         (fn [p]
                           (if (some? label)
                             (str "ttk::labelframe " p " -text " (tcl-quote label))
                             (str "frame " p
                                  (when (some? bg)
                                    (str " -background " (tcl-quote bg)))
                                  ;; -relief solid draws its border in the
                                  ;; widget's own background, which on a
                                  ;; card sitting on a same-toned canvas
                                  ;; is invisible. The highlight ring takes
                                  ;; a colour, so it is the visible border.
                                  (when (some? border)
                                    (str " -borderwidth 0 -highlightthickness "
                                         (tcl-quote (if (true? border) 1 border))
                                         (when border-color
                                           (str " -highlightbackground "
                                                (tcl-quote border-color)
                                                " -highlightcolor "
                                                (tcl-quote border-color)))))
                                  (when (some? padx)
                                    (str " -padx " (tcl-quote padx)))
                                  (when (some? pady)
                                    (str " -pady " (tcl-quote pady))))))
                         props))
    :lens (child-lens)
    :apply (fn [_w _prev _props] true)}

   :label
   {:ctor (fn [{:keys [text on-click] :as props}]
            (let [w (make-widget :label
                                 (fn [p]
                                   (str "ttk::label " p " -text "
                                        (tcl-quote (or text ""))
                                        " -anchor w -justify left"
                                        (when-some [fg (:fg props)]
                                          (str " -foreground " (tcl-quote fg)))
                                        (when-some [bg (:bg props)]
                                          (str " -background " (tcl-quote bg)))
                                        (when (or (:bold props) (:italic props)
                                                  (some? (:font-size props)))
                                          (str " -font " (font-spec (resolve-base-font props)
                                                                    props)))))
                                 props)]
              (when on-click (bind-click! w on-click))
              w))
    :primary :text
    :apply (fn [w prev props]
             (when (patchable? prev props #{:text :fg :bg :on-click})
               (when (not= (:on-click prev) (:on-click props))
                 (when-some [f (:on-click props)] (bind-click! w f)))
               (apply-config! (configure-script (:path w) props
                                                {:text "-text" :fg "-foreground"
                                                 :bg "-background"}))))}

   :button
   {:ctor (fn [{:keys [label on-click] :as props}]
            (let [w (make-widget :button
                                 (fn [p]
                                   (str "ttk::button " p " -text "
                                        (tcl-quote (or label ""))))
                                 props)]
              (when on-click (set-command! w on-click))
              w))
    :primary :label
    :apply (fn [w prev props]
             (when (patchable? prev props #{:label :on-click})
               (when (not= (:on-click prev) (:on-click props))
                 (when-some [f (:on-click props)] (set-command! w f)))
               (apply-config! (configure-script (:path w) props {:label "-text"}))))}

   :entry
   {:ctor (fn [{:keys [text on-change on-activate] :as props}]
            (let [w (make-widget :entry (fn [p] (str "ttk::entry " p)) props)]
              (when (some? text)
                (eval! (str (:path w) " insert 0 " (tcl-quote text))))
              (when on-change (bind-key! w "<KeyRelease>" :change on-change))
              (when on-activate (bind-key! w "<Return>" :activate on-activate))
              w))
    :primary :text
    :apply (fn [w prev props]
             (when (patchable? prev props #{:text :on-change :on-activate})
               (when (not= (:text prev) (:text props))
                 (eval! (str (:path w) " delete 0 end"))
                 (when (some? (:text props))
                   (eval! (str (:path w) " insert 0 " (tcl-quote (:text props))))))
               (when (not= (:on-change prev) (:on-change props))
                 (when-some [f (:on-change props)]
                   (bind-key! w "<KeyRelease>" :change f)))
               (when (not= (:on-activate prev) (:on-activate props))
                 (when-some [f (:on-activate props)]
                   (bind-key! w "<Return>" :activate f)))
               true))}

   :checkbutton
   {:ctor (fn [{:keys [label active on-toggled] :as props}]
            (let [w (make-widget :checkbutton
                                 (fn [p]
                                   (str "ttk::checkbutton " p " -text "
                                        (tcl-quote (or label ""))))
                                 props)]
              (eval! (str (:path w) " state "
                          (if active "selected" "!selected")))
              (when on-toggled (set-command! w on-toggled))
              w))
    :primary :label
    :apply (fn [w prev props]
             (when (patchable? prev props #{:label :active :on-toggled})
               (when (not= (:active prev) (:active props))
                 (eval! (str (:path w) " state "
                             (if (:active props) "selected" "!selected"))))
               (when (not= (:on-toggled prev) (:on-toggled props))
                 (when-some [f (:on-toggled props)] (set-command! w f)))
               (apply-config! (configure-script (:path w) props {:label "-text"}))))}

   :separator
   {:ctor (fn [_props] (make-widget :separator (fn [p] (str "ttk::separator " p)) _props))}

   :progressbar
   {:ctor (fn [{:keys [value maximum] :as props}]
            (let [w (make-widget :progressbar
                                 (fn [p] (str "ttk::progressbar " p
                                              " -maximum " (or maximum 100)))
                                 props)]
              (when (some? value)
                (eval! (str (:path w) " configure -value " (tcl-quote value))))
              w))
    :apply (fn [w _prev props]
             (when (some? (:value props))
               (eval! (str (:path w) " configure -value "
                           (tcl-quote (:value props)))))
             true)}

   :text
   {:ctor (fn [props]
            (let [props (text-props props)
                  font (resolve-base-font props)
                  w (make-widget :text
                                 (fn [p]
                                   (str "text " p
                                        " -wrap " (or (:wrap props) "word")
                                        " -height " (or (:height props) 10)
                                        " -width " (or (:width props) 80)
                                        " -borderwidth 0 -highlightthickness 0"
                                        " -font " (font-spec font {})
                                        (when-some [bg (:bg props)]
                                          (str " -background " (tcl-quote bg)))
                                        (when-some [fg (:fg props)]
                                          (str " -foreground " (tcl-quote fg)))
                                        (when-some [padx (:padx props)]
                                          (str " -padx " (tcl-quote padx)))
                                        (when-some [pady (:pady props)]
                                          (str " -pady " (tcl-quote pady)))))
                                 props)]
              (swap! (:styles w) assoc :font font)
              (set-text-content! w props)
              (when (:scrollbar props)
                (attach-scrollbar! w (parent-widget)))
              w))
    :apply (fn [w prev props]
             (when (patchable? prev props
                               (into #{} (concat [:content :text] (keys text-options))))
               (when (or (contains? props :content) (contains? props :text))
                 (set-text-content! w props))
               (apply-config! (configure-script (:path w) (text-props props)
                                                text-options))))}})

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
  (let [window (engine/compile-element tk-backend [:window (or options {})])
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
   closed. OPTIONS: :title :width :height. Requires an adapter (bound by
   the caller — kmet.gui.tcl/run! installs the host FFI one). Blocks."
  [tree & [options]]
  (run-loop! (mount! tree options)))
