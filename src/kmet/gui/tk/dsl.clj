(ns kmet.gui.tk.dsl
  "The widget-building machinery the Tk backends' tag tables are written
   in: Tcl quoting and script building, the TkWidget record and path
   allocation, the callback/binding registry (including the :bind chord to
   Tk event translation), the live-patch decisions (with the check that a
   prop's option exists on the running widget), widget construction and
   disposal, and the rich-text/:text helpers.

   The option vocabulary itself — which props exist, which Tcl option each
   names, and the families a spec composes its :options from — is
   kmet.gui.tk.options; this namespace executes it.

   It is deliberately below the widget namespaces (kmet.gui.tk's classic
   set and kmet.gui.ttk) and above nothing: the Tcl evaluator is installed
   by kmet.gui.tk at load (see set-eval-fn!), the tag specs themselves are
   completed by kmet.gui.tk/widget-specs, so a widget namespace only needs
   this one require."
  (:require [clojure.string :as str]
            [kmet.gui.tk.options :as opt]
            [kmet.libs.hikap :as engine]))

;; ═══════════════════════════════════════════════════════════════════════════
;; The evaluator seam
;; ═══════════════════════════════════════════════════════════════════════════

;; kmet.gui.tk installs its adapter's evaluator here at load; the widget
;; namespaces script through it. F must read the adapter per call (never
;; capture one), so tests binding kmet.gui.tk/*adapter* are honoured.
(defonce ^:private eval-fn (atom nil))

(defn set-eval-fn!
  "Install F (`(fn [script] -> result string)`) as the script evaluator."
  [f]
  (reset! eval-fn f))

(defn eval!
  "Evaluate SCRIPT through the installed evaluator."
  [script]
  (if-some [f @eval-fn]
    (f script)
    (throw (ex-info (str "kmet.gui.tk.dsl: no Tcl evaluator installed — "
                         "require kmet.gui.tk (which installs the adapter's) "
                         "before scripting widgets")
                    {:type :kmet.gui.tk/no-adapter}))))

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
   substitution context). An empty string is {} — a bare empty word would
   not exist for Tcl, so the next option would take its place."
  [x]
  (let [s (str x)]
    (if (empty? s)
      "{}"
      (apply str (map escape-char s)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Widget records + callback registry
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord TkWidget [path tag kids callbacks styles]
  ;; Records are maps, so the engine can stamp :dsl/meta onto the instance.
  ;; CALLBACKS maps a binding slot to the trampoline id bound for it (the
  ;; registry owns the handler); STYLES is the per-widget cache of derived
  ;; Tk resources — the generated text tags (and the named fonts behind
  ;; them) the :text tag inserts with.
  )

(defn widget-path
  "The Tk path of WIDGET (\"\\.\" for the toplevel)."
  [widget]
  (:path widget))

(defonce ^:private next-id (atom 0))

(defn alloc-path!
  "A fresh child path under PARENT (a TkWidget or nil = the toplevel)."
  [parent]
  (let [base (if (some? parent) (:path parent) ".")
        n (swap! next-id inc)]
    (if (= base ".")
      (str ".w" n)
      (str base ".w" n))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Callbacks and bindings
;; ═══════════════════════════════════════════════════════════════════════════

(defonce ^:private next-callback (atom 0))

;; The `kmet::cb` registry: callback id -> {:handler f :value :text/:checked}.
(defonce ^:private callbacks (atom {}))

(defn- release-slot!
  "Drop W's SLOT callback (a no-op when the slot is empty)."
  [w slot]
  (when-some [id (get @(:callbacks w) slot)]
    (swap! callbacks dissoc id)
    (swap! (:callbacks w) dissoc slot)))

(defn- set-callback!
  "Register F as W's SLOT handler (releasing whatever the slot held) and
   return the `kmet::cb <id>` argument; nil F just releases the slot.
   Rebinding matters: Tk's bind (and -command) replaces the script, so
   without the release the old command would stay dispatchable."
  [w slot f value]
  (release-slot! w slot)
  (when (some? f)
    (let [id (swap! next-callback inc)]
      (swap! callbacks assoc id {:handler f :value value})
      (swap! (:callbacks w) assoc slot id)
      (str "kmet::cb " id))))

(defn- value-fragment
  "The Tcl fragment Tk evaluates when the slot's event fires, handing the
   handler the widget's live VALUE; nil means the handler takes no
   arguments."
  [w value]
  (case value
    :text (str "[" (:path w) " get]")
    :checked (str "[" (:path w) " instate selected]")
    nil))

(defn- bind-slot!
  "Point W's SLOT at F (nil unbinds), through the slot spec's Tcl -OPTION
   or EVENT. The value fragment sits inside the bound script, so Tcl
   evaluates it when the event fires, not at bind time."
  [w slot {:keys [event option value]} f]
  (let [callback (set-callback! w slot f value)
        script (when (some? callback)
                 (str callback (when-let [frag (value-fragment w value)] (str " " frag))))
        body (str "{" (or script "") "}")]
    (if option
      (eval! (str (:path w) " configure -" (name option) " " body))
      (eval! (str "bind " (:path w) " " event " " body)))))

;; tcl-event is defined with the chord translation below; the slot tables
;; only call it when a tree renders.
(declare ^:private tcl-event)

(defn- binding-slots
  "PROPS' binding slots as {slot {:event … :handler f}}: the tag's SLOTS
   table plus the universal :bind map, whose chord keys become [:bind chord]
   slots. SLOTS may be nil (the tag declares none)."
  [props slots]
  (into (into {} (for [[slot spec] slots]
                   [slot (assoc spec :handler (get props slot))]))
        (for [[chord f] (:bind props)]
          [[:bind chord] {:event (tcl-event chord) :handler f}])))

(defn apply-bindings!
  "Rebind the slots whose handler changed between PREV and PROPS, and
   unbind the slots PROPS dropped — a callback that leaves the tree must
   stop firing."
  [w prev props slots]
  (let [before (binding-slots prev slots)
        after (binding-slots props slots)]
    (doseq [[slot spec] after
            :when (not= (:handler spec) (:handler (get before slot)))]
      (bind-slot! w slot spec (:handler spec)))
    (doseq [[slot spec] before
            :when (not (contains? after slot))]
      (bind-slot! w slot spec nil)))
  true)

(defn- coerce-value
  "ARGV's values as the handler's arguments, per SLOT's :value kind — Tcl
   answers `instate` with \"0\"/\"1\", the Clojure contract is a boolean."
  [value args]
  (if (= :checked value) (mapv #(= "1" %) args) args))

(defn dispatch-callback!
  "The single command handler behind `kmet::cb`: ARGV is
   [\"kmet::cb\" id & values]. Returns the handler's result string (\"\")."
  [argv]
  (let [id (some-> (second argv) parse-long)
        {:keys [handler value]} (get @callbacks id)]
    (if (nil? handler)
      ""
      (str (or (apply handler (coerce-value value (drop 2 argv))) "")))))

(defn- release-callbacks!
  [widget]
  (doseq [id (vals @(:callbacks widget))]
    (swap! callbacks dissoc id))
  (reset! (:callbacks widget) {}))

;; ─── Chord -> Tk event ───────────────────────────────────────────────────
;; :bind takes kmet chords (:ctrl+enter, \"ctrl+shift+p\") or a Tk pattern
;; string that already spells its own event (\"<Double-Button-1>\").

(def ^:private tcl-keysyms
  "A named chord key as Tk's keysym: Tk spells the page keys Prior/Next and
   the main-keyboard Enter Return."
  {:escape "Escape" :enter "Return" :return "Return" :tab "Tab"
   :space "space" :backspace "BackSpace" :delete "Delete" :insert "Insert"
   :up "Up" :down "Down" :left "Left" :right "Right"
   :home "Home" :end "End" :pageup "Prior" :pagedown "Next"
   :f1 "F1" :f2 "F2" :f3 "F3" :f4 "F4" :f5 "F5" :f6 "F6"
   :f7 "F7" :f8 "F8" :f9 "F9" :f10 "F10" :f11 "F11" :f12 "F12"})

(def ^:private tcl-modifiers
  "A chord modifier as Tk's event name."
  {"ctrl" "Control" "control" "Control" "shift" "Shift" "alt" "Alt" "meta" "Meta"})

(defn- chord-error
  [msg data]
  (throw (ex-info (str "kmet.gui.tk: " msg)
                  (assoc data :type :kmet.gui.tk/bad-chord))))

(defn- chord-keysym
  "CHORD's key part as Tk's keysym: a single character passes through
   (Tk's own case convention — p is Control-p, P implies Shift), a named
   key is looked up."
  [s]
  (if (= 1 (count s))
    s
    (or (get tcl-keysyms (keyword (str/lower-case (str/replace s #"[-_]" ""))))
        (chord-error (str "unknown key " (pr-str s) " in a :bind chord")
                     {:key s}))))

(defn- tcl-event
  "A kmet chord — :ctrl+enter, :escape, \"ctrl+shift+p\" — as Tk's event
   pattern (:ctrl+enter -> <Control-Return>). A string already in Tk's own
   <…> spelling passes through."
  [chord]
  (let [s (name chord)]
    (if (str/starts-with? s "<")
      s
      (let [parts (str/split s #"\+")]
        (str "<"
             (str/join "-" (concat (map (fn [m]
                                          (or (get tcl-modifiers (str/lower-case m))
                                              (chord-error
                                               (str "unknown modifier " (pr-str m)
                                                    " in a :bind chord")
                                               {:modifier m})))
                                        (butlast parts))
                                   [(chord-keysym (last parts))]))
             ">")))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Patch decisions
;; ═══════════════════════════════════════════════════════════════════════════

(defn- props-diff
  "The prop keys whose value differs between PREV and PROPS."
  [prev props]
  (remove #(= (get prev %) (get props %))
          (distinct (concat (keys prev) (keys props)))))

(defn patchable?
  "True when every prop that changed between PREV and PROPS is one SPEC
   can express on the live widget — its :options, its :bindings, the
   universal :bind, plus its :patchable extras. A prop it cannot (a
   creation-only option, a geometry, a re-wrap width) must decline so the
   engine rebuilds instead of silently dropping the change."
  [spec prev props]
  (let [{:keys [options bindings patchable]} spec
        live (into #{} (comp (map key) (remove opt/creation-only?)) options)]
    (every? (into (into live (keys bindings)) (concat patchable [:bind]))
            (props-diff prev props))))

;; apply-live!/default-apply/widget-specs sit at the end of this namespace:
;; they read configure-script, defined with the script fragments below.

(defn- apply-config!
  "Run SCRIPT when it is non-nil, then report the patch as applied (the
   engine keeps the live instance when :apply returns truthy)."
  [script]
  (when script (eval! script))
  true)

;; ═══════════════════════════════════════════════════════════════════════════
;; Script fragments
;; ═══════════════════════════════════════════════════════════════════════════

(defn tcl-word
  "X as the Tcl word it names: a keyword or symbol by name (so a prop value
   stays Clojure data: :wrap :word, :fill :both), a boolean as Tcl's 1/0
   (Tcl has no boolean), anything else unchanged."
  [x]
  (cond
    (keyword? x) (name x)
    (symbol? x) (name x)
    (boolean? x) (if x "1" "0")
    :else x))

;; option-value builds a widget's font from the font props, so it reaches
;; forward to the rich-text helpers below (the same base font the :text tag
;; caches on its widget).
(declare ^:private resolve-base-font font-spec)

(defn- option-value
  "V as the Tcl value PROP's option takes, per the catalogue's shape: a
   sequence joins into a Tcl list (-padding/-tabs), the font props build
   the widget's font from PROPS (so any of the four determines it), and
   anything else is the word it names."
  [props prop v]
  (case (opt/coerce prop)
    :list (if (sequential? v) (str/join " " (map tcl-word v)) v)
    :font (font-spec (resolve-base-font props) props)
    v))

(defn tcl-options
  "PROPS through OPTIONS ({:prop :tcl-option}) as a Tcl option map
   ({:text \"hi\" :foreground \"#fff\"}); a nil prop is absent. Values take
   the shape their prop's catalogue entry names (kmet.gui.tk.options/coerce):
   a Tcl list joins a sequence, the font props build the widget's font."
  [props options]
  (into {} (for [[prop option] options
                 :let [v (get props prop)]
                 :when (some? v)]
             [option (option-value props prop v)])))

(defn- option-args
  "A Tcl option map as `-option value` words, in option-name order so a
   widget's script is stable: keywords and booleans render as Tcl words,
   every value is quoted so any string survives."
  [options]
  (for [[option v] (sort-by (comp name key) options)
        :when (some? v)]
    (str "-" (name option) " " (tcl-quote (tcl-word v)))))

(defn widget-script
  "A `COMMAND PATH -option value … FRAGMENTS…` construction script from an
   option map; EXTRA fragments are Tcl the caller builds itself (a
   subcommand, a value Tk must evaluate) and are appended verbatim."
  [command path options & extra]
  (str/join " " (concat [command path] (option-args options) (remove nil? extra))))

(defn- configurable-options
  "OPTIONS without its creation-only props: Tk refuses -class and friends
   on configure, so a patch must not send them (the change rebuilds the
   widget instead, see patchable?)."
  [options]
  (into {} (remove (fn [[prop _]] (opt/creation-only? prop))) options))

(defn- configure-script
  "A `PATH configure -option value …` script for PROPS through OPTIONS, or
   nil when nothing is patchable is set."
  [path props options]
  (when-let [args (seq (option-args (tcl-options props (configurable-options options))))]
    (str/join " " (concat [path "configure"] args))))

(defn parent-widget
  "The container being constructed into, from the engine's construction
   context (nil at the root)."
  []
  (:parent engine/*construct-context*))

(defonce ^:private tcl-option-cache (atom {}))

(defn forget-widget-options!
  "Drop the per-tag option lists cached from an interpreter: a new
   interpreter may be a different Tk (a test's stub, a remounted one)."
  []
  (reset! tcl-option-cache {}))

(defn- widget-class-options
  "The Tcl options a widget at PATH accepts, asked of Tk itself and cached
   per TAG (a tag names one widget class in a backend's table). An empty
   answer — a stub evaluator in a headless test — is not cached and skips
   the check."
  [tag path]
  (or (get @tcl-option-cache tag)
      (let [out (eval! (str "join [lmap o [" path " configure] {lindex $o 0}] \"\\n\""))
            opts (into #{} (filter #(str/starts-with? % "-")) (str/split-lines out))]
        (when (seq opts)
          (swap! tcl-option-cache assoc tag opts))
        opts)))

(defn check-options!
  "Throw when PROPS sets an option SPEC scripts that the widget at PATH
   does not have — a family that does not fit this widget, a typo, or an
   option newer than the running Tk (which would otherwise fail inside a
   generated script, or on a later patch)."
  [spec tag path props]
  (let [known (widget-class-options tag path)]
    (when (seq known)
      (doseq [[prop option] (sort-by (comp name key) (:options spec))
              :when (and (some? option) (some? (get props prop)))
              :let [named (str "-" (name option))]
              :when (not (contains? known named))]
        (throw (ex-info (str "kmet.gui.tk: " tag " has no " named
                             " on this Tk (prop " prop ")")
                        {:type :kmet.gui.tk/unknown-option
                         :tag tag :prop prop :option named})))))
  nil)

(defn make-widget
  "Construct a TkWidget at a fresh path under the current construction
   parent, run the ctor SCRIPT (a fn of the path), wire SPEC's bindings and
   pack it into the parent. PROPS (optional) also carry the pack geometry —
   Tk's own pack options on the child: :side (default :top), :fill (default
   both for pane containers, x for leaves) and :expand."
  [spec ctor-script & [props]]
  (let [props (or props {})
        tag (:tag spec)
        parent (parent-widget)
        path (alloc-path! parent)
        w (->TkWidget path tag (atom []) (atom {}) (atom {}))]
    (eval! (ctor-script path))
    (check-options! spec tag path props)
    (apply-bindings! w {} props (:bindings spec))
    (when parent
      (if-let [attach (:attach @(:styles parent))]
        ;; containers that own their child placement (the :transcript text)
        (attach path)
        (eval! (str "pack " path
                    " -side " (tcl-quote (tcl-word (or (:side props) :top)))
                    " -fill " (tcl-word (or (:fill props)
                                            (if (#{:ttk/frame :tk/frame :ttk/labelframe} tag)
                                              :both
                                              :x)))
                    (when (:expand props) " -expand 1")))))
    w))

(defn child-lens
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

(defn resolve-base-font
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

(defn font-spec
  "A Tcl font description for base font FONT with STYLE's bold and italic
   flags: a braced list, so a multi-word family (TkFixedFont is Courier New
   on Windows) stays one element."
  [{:keys [family size]} {:keys [bold italic]}]
  (str "{{" family "} " size (when bold " bold") (when italic " italic") "}"))

(defn- style-options
  "The `tag configure` option string for STYLE in W."
  [w style]
  (let [{:keys [fg bg bold italic underline overstrike]} style]
    (str/join " " (option-args
                   (cond-> {}
                     (some? fg) (assoc :foreground fg)
                     (some? bg) (assoc :background bg)
                     (or bold italic) (assoc :font (font-spec (get-in @(:styles w) [:font]) style))
                     underline (assoc :underline true)
                     overstrike (assoc :overstrike true))))))

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

(defn- fit-height!
  "Grow W's -height to the display lines its content occupies when a line
   is longer than the widget's width and Tk therefore wraps it: a text
   widget sized to its logical line count would clip the wrapped tail.
   Wrapping normally happens in the renderer (see kmet.app.ui.gui.transcript)
   and the round trip is skipped whenever every line fits — which is the
   whole transcript in the common case."
  [w props lines]
  (let [width (or (:width props) 80)]
    (when (and (not= "none" (tcl-word (:wrap props)))
               ;; a character count is the backend's safe cheap proxy:
               ;; wide glyphs only ever make Tk wrap earlier
               (some #(> (count %) width) lines))
      (eval! (str (:path w) " configure -height [expr {max 1 [lindex ["
                  (:path w) " count -displaylines 1.0 end] 0]}]")))))

(defn set-text-content!
  "Replace W's content with PROPS' :content segments (or a plain :text
   string). Read-only afterwards (`-state disabled`) but still selectable."
  [w props]
  (let [segs (cond (some? (:content props)) (:content props)
                   (some? (:text props)) [{:text (:text props)}]
                   :else nil)]
    (eval! (str (:path w) " configure -state normal"))
    (eval! (str (:path w) " delete 1.0 end"))
    (when-some [script (and (seq segs)
                            (not-empty (str/join "\n" (keep #(insert-script w %) segs))))]
      (eval! script))
    (fit-height! w props (map :text segs))
    (eval! (str (:path w) " configure -state disabled"))))

(defn attach-scrollbar!
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

;; ─── Live patching + spec completion ─────────────────────────────────────
;; Below the script fragments on purpose: apply-live! reads configure-script.

(defn apply-live!
  "Apply what SPEC can express live: its :options patches and :bindings
   (re)binds to W. Returns true (the engine keeps the instance)."
  [spec w prev props]
  (apply-bindings! w prev props (:bindings spec))
  (apply-config! (configure-script (:path w) props (:options spec))))

(defn default-apply
  "The shared :apply: patch SPEC's live-expressible props and bindings to
   W, else decline so the engine rebuilds the widget."
  [spec w prev props]
  (when (patchable? spec prev props)
    (apply-live! spec w prev props)))

(defn widget-specs
  "Complete a `tag -> spec` map for the engine: every spec carries its own
   :tag, its :ctor and :apply are called with the spec itself (so a ctor
   wires its own :bindings and a custom :apply reads its own data), and a
   spec without an :apply gets the shared live patcher (default-apply)."
  [specs]
  (into {}
        (map (fn [[tag spec]]
               (let [spec (assoc spec :tag tag)
                     ctor (:ctor spec)
                     apply-fn (or (:apply spec) default-apply)]
                 [tag (assoc spec
                             :ctor (fn [props] (ctor spec props))
                             :apply (fn [w prev props] (apply-fn spec w prev props)))])))
        specs))

(defn set-entry-text!
  "Replace W's text with TEXT (nil clears it)."
  [w text]
  (eval! (str (:path w) " delete 0 end"))
  (when (some? text)
    (eval! (str (:path w) " insert 0 " (tcl-quote text)))))
