(ns kmet.tk.dsl
  "The widget-building machinery the Tk backends' tag tables are written
   in: Tcl quoting and script building, the TkWidget record and path
   allocation, the callback/binding registry (including the :bind chord to
   Tk event translation), the live-patch decisions (with the check that a
   prop's option exists on the running widget), widget construction and
   disposal, the Tk images a widget owns, and the rich-text/:text helpers.

   The option vocabulary itself — which props exist, which Tcl option each
   names, and the families a spec composes its :options from — is
   kmet.tk.options; this namespace executes it.

   It is deliberately below the widget namespaces (kmet.tk.core's classic
   set and kmet.tk.ttk) and above nothing: the Tcl evaluator is installed
   by kmet.tk.core at load (see set-eval-fn!), the tag specs themselves are
   completed by kmet.tk.core/widget-specs, so a widget namespace only needs
   this one require."
  (:require [clojure.string :as str]
            [kmet.tk.options :as opt]
            [kmet.libs.hikap :as engine]))

;; ═══════════════════════════════════════════════════════════════════════════
;; The evaluator seam
;; ═══════════════════════════════════════════════════════════════════════════

;; kmet.tk.core installs its adapter's evaluator here at load; the widget
;; namespaces script through it. F must read the adapter per call (never
;; capture one), so tests binding kmet.tk.core/*adapter* are honoured.
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
    (throw (ex-info (str "kmet.tk.dsl: no Tcl evaluator installed — "
                         "require kmet.tk.core (which installs the adapter's) "
                         "before scripting widgets")
                    {:type :kmet.tk.core/no-adapter}))))

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

(defn- check-var
  "A Tcl variable name private to the widget at PATH. A classic Tk
   checkbutton has select/deselect and no state query, so a checkable
   widget's state lives in its own variable — and a radiobutton's own
   -value is what its variable is compared against."
  [path]
  (str "::kmet_check_" (str/replace path #"[^A-Za-z0-9]" "_")))

(defrecord TkWidget [path tag kids callbacks styles]
  ;; Records are maps, so the engine can stamp :dsl/meta onto the instance.
  ;; CALLBACKS maps a binding slot to the trampoline id bound for it (the
  ;; registry owns the handler); STYLES is the per-widget cache of derived
  ;; Tk resources — the generated text tags (and the named fonts behind
  ;; them) the :text tag inserts with, and the Tk images the widget created
  ;; (see the Images section).
  )

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

;; The `kmet::cb` registry: callback id -> {:handler f :value :text/:variable}.
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
   VALUE is the fragment kind the bound script reads the widget with (nil
   when Tk hands the value over itself, as a scale's -command does) and
   ARG how the handler wants the arguments Tk passes.
   Rebinding matters: Tk's bind (and -command) replaces the script, so
   without the release the old command would stay dispatchable."
  [w slot f value arg]
  (release-slot! w slot)
  (when (some? f)
    (let [id (swap! next-callback inc)]
      (swap! callbacks assoc id {:handler f :value value :arg (or arg value)})
      (swap! (:callbacks w) assoc slot id)
      (str "kmet::cb " id))))

(def text-field-bindings
  "A text field's standard slots: :on-change while typing, :on-submit on
   Return, both reading the text back."
  {:on-change {:event "<KeyRelease>" :value :text}
   :on-submit {:event "<Return>" :value :text}})

(def text-bindings
  "An editable text's slots: :on-change while typing (a paste or an undo is
   a key release too) and :on-submit on Return, which ends in `break` so
   Tk's class binding does not also insert a newline. Both read the
   widget's own text with :body."
  {:on-change {:event "<KeyRelease>" :value :body}
   :on-submit {:event "<Return>" :value :body :stop true}})

(def spinbox-bindings
  "A spinbox's text-field slots plus :on-spin, Tk's -command, which fires
   when the arrows (not the keyboard) change the value."
  (assoc text-field-bindings :on-spin {:option :command :value :text}))

(def check-bindings
  "A checkbutton's or toggleswitch's slot: it reads the widget's private
   variable, so the handler receives the new checked state."
  {:on-toggled {:option :command :value :variable}})

(def radio-bindings
  "A radiobutton's slot: it compares the private variable with the widget's
   own :value, so the handler learns whether this button was selected. The
   caller owns which button of the group is :active (Tk's own group
   selection is a -variable)."
  {:on-toggled {:option :command :value :radio}})

(defn- as-number
  "S as a Clojure number: an integral Tcl value (7, 7.0) as a long, else as
   a double, else S itself."
  [s]
  (let [s (str s)]
    (if (re-matches #"[-+]?\d+(\.0*)?" s)
      (parse-long (re-find #"[-+]?\d+" s))
      (or (parse-double s) s))))

(def ^:private value-kinds
  "The kinds a slot's :value names, and what each means: the fragment the
   bound script reads the widget's live value with, and how the arguments
   the handler receives are shaped. A slot that has :arg but no :value (a
   scale's -command passes its value) reads nothing and only shapes what Tk
   handed it; the list kinds expand ({*}) so the handler gets one argument
   per selected item."
  {:text      {:fragment #(str "[" % " get]")}
   ;; a multi-line widget's whole text: Tk keeps a newline of its own at the
   ;; end, which end-1c drops
   :body      {:fragment #(str "[" % " get 1.0 end-1c]")}
   :number    {:args #(vector (as-number (first %)))}
   :index     {:fragment #(str "{*}[" % " curselection]")
               :args #(vector (mapv (fn [i] (or (parse-long i) i)) %))}
   :ids       {:fragment #(str "{*}[" % " selection]")
               :args #(vector (vec %))}
   :tab-index {:fragment #(str "[" % " index current]")
               :args #(vector (or (parse-long (first %)) (first %)))}
   ;; a click's coordinates, which only a binding substitutes: the handler
   ;; gets [x y] as Tk's %x/%y, relative to the widget
   :pointer   {:fragment (fn [_] "%x %y")
               :args #(vector (mapv as-number %))}
   ;; a checkable widget's state lives in its private variable
   :variable  {:fragment #(str "[set " (check-var %) "]")
               :args #(mapv (fn [v] (= "1" v)) %)}
   :radio     {:fragment #(str "[expr {[set " (check-var %) "] eq [" %
                               " cget -value]}]")
               :args #(mapv (fn [v] (= "1" v)) %)}})

(defn- value-fragment
  "The Tcl fragment Tk evaluates when the slot's event fires, handing the
   handler the widget's live VALUE; nil means the handler takes no
   arguments (or reads none)."
  [w value]
  (when-some [fragment (:fragment (value-kinds value))]
    (fragment (:path w))))

(defn- bind-slot!
  "Point W's SLOT at F (nil unbinds), through the slot spec's Tcl -OPTION
   or EVENT — :target names a tagOrId to bind on instead of the widget
   itself, which is Tk's item bind for a canvas. The value fragment sits
   inside the bound script, so Tcl evaluates it when the event fires, not
   at bind time. :stop closes the script with `break`, which keeps Tk's
   class binding from running too — the Return slot of an editable text
   must not insert a newline."
  [w slot {:keys [event option value arg stop target]} f]
  (let [callback (set-callback! w slot f value arg)
        script (when (some? callback)
                 (str callback
                      (when-let [frag (value-fragment w value)] (str " " frag))
                      (when stop "; break")))
        body (str "{" (or script "") "}")]
    (if option
      (eval! (str (:path w) " configure -" (name option) " " body))
      (if target
        ;; Tk's item bind is the widget's own subcommand (bind <tagOrId> …),
        ;; not the window-level `bind` command
        (eval! (str (:path w) " bind " (tcl-quote target) " " event " " body))
        (eval! (str "bind " (:path w) " " event " " body))))))

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
  "ARGV as the arguments the handler receives, in the shape its slot's kind
   names (Tcl answers everything as strings)."
  [value argv]
  (if-some [shape (:args (value-kinds value))]
    (shape argv)
    argv))

(defn dispatch-callback!
  "The single command handler behind `kmet::cb`: ARGV is
   [\"kmet::cb\" id & values]. Returns the handler's result string (\"\")."
  [argv]
  (let [id (some-> (second argv) parse-long)
        {:keys [handler arg]} (get @callbacks id)]
    (if (nil? handler)
      ""
      (str (or (apply handler (coerce-value arg (drop 2 argv))) "")))))

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
  (throw (ex-info (str "kmet.tk.core: " msg)
                  (assoc data :type :kmet.tk.core/bad-chord))))

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

(defn tcl-list
  "X as Tcl list syntax: a sequence as its elements, each one the word it
   names (:x -> x, true -> 1) and quoted, so any value survives; anything
   else is the one element. A nested sequence becomes one element of its
   own — Tk's -filetypes is [label extensions] pairs, where the extensions
   element is itself a list."
  [x]
  (if (sequential? x)
    (str/join " " (map (comp tcl-quote tcl-list) x))
    (tcl-word x)))

(defn tcl-value
  "X as the one Tcl word an option value is: a sequence as Tcl list syntax
   (so an element with a space stays one element), anything else the word
   it names."
  [x]
  (tcl-quote (if (sequential? x) (tcl-list x) (tcl-word x))))

;; option-value builds a widget's font from the font props, so it reaches
;; forward to the rich-text helpers below (the same base font the :text tag
;; caches on its widget).
(declare ^:private resolve-base-font font-spec)

;; ─── Images ───────────────────────────────────────────────────────────────
;; An image-valued prop takes either a Tk image name — a string or keyword
;; used as it is: a built-in bitmap such as :questhead, or a name the app
;; created for itself in Tcl — or a description map the widget then owns:
;;
;;   {:file "logo.png"}                  read the image from a file
;;   {:data "iVBORw0…" :format :png}     from base64 image data
;;   {:zoom 2}     | {:zoom [2 3]}       integer upscale after loading
;;   {:subsample 2} | {:subsample [2 3]} integer downscale after loading
;;   {:fit {:width 320 :height 200}}     subsample so it fits the box
;;
;; A description is created once per widget and kind — a re-render with the
;; same description reuses the name — and destroy! releases every image the
;; widget created. A :bitmap prop creates `image create bitmap`, every
;; other image prop (the catalogue's image-props) a photo. Sizing is
;; photo-only (a Tk bitmap has no copy): Tk sizes a photo through a
;; self-copy (`copy … -zoom/-subsample -shrink`), not a zoom subcommand.

(defonce ^:private next-image (atom 0))

(defn- image-kind
  "The image a PROP's option names: a bitmap for :bitmap, a photo for the
   rest of the catalogue's image props."
  [prop]
  (if (= :bitmap prop) :bitmap :photo))

(defn- image-pair
  "X — a number or an [x y] pair — as the [x y] pair Tk's zoom and
   subsample take."
  [x]
  (if (sequential? x) [(first x) (second x)] [x x]))

(defn- image-copy!
  "Apply Tk's in-place copy to the image at IMG: `copy IMG -KIND x y
   -shrink`, how a photo is zoomed or subsampled. FACTOR is a number or an
   [x y] pair."
  [img kind factor]
  (let [[x y] (image-pair factor)]
    (eval! (str img " copy " img " -" (name kind) " " x " " y " -shrink"))))

(defn- image-fit!
  "Shrink the photo at IMG to fit the :width/:height box FIT names (a bare
   number is a width), without distorting it: one factor, from the axis
   that constrains hardest, applied to both — a photo is never enlarged to
   fit."
  [img fit]
  (let [{:keys [width height]} (if (map? fit) fit {:width fit})
        pw (some-> (eval! (str "image width " img)) str/trim parse-long)
        ph (some-> (eval! (str "image height " img)) str/trim parse-long)
        factor (fn [px target]
                 (if (and px target (pos? target) (> px target))
                   (quot (+ px (dec target)) target)
                   1))
        f (max (factor pw width) (factor ph height))]
    (when (and pw ph (> f 1))
      (image-copy! img :subsample f))))

(defn- create-image!
  "Create the photo or bitmap V describes at IMG (`:file` beats `:data`),
   sized per V's :zoom, :subsample and :fit — in that order, so :fit
   measures what the first two produced."
  [img kind v]
  (eval! (str "image create " (name kind) " " img
              (when-some [file (:file v)] (str " -file " (tcl-quote file)))
              (when (and (nil? (:file v)) (some? (:data v)))
                (str " -data " (tcl-quote (:data v))))
              (when (some? (:format v))
                (str " -format " (tcl-value (:format v))))))
  (when (some? (:zoom v)) (image-copy! img :zoom (:zoom v)))
  (when (some? (:subsample v)) (image-copy! img :subsample (:subsample v)))
  (when (some? (:fit v)) (image-fit! img (:fit v))))

(def ^:private image-description-keys
  "The keys an image description may carry."
  #{:file :data :format :zoom :subsample :fit})

(defn- check-image-description!
  "Throw when description V cannot be expressed: no source (:file/:data) —
   an image with no source would be silently empty; an unknown key (a typo
   would otherwise be ignored); or a sizing key on a bitmap (Tk bitmaps
   have no copy subcommand to size them)."
  [prop v]
  (when (and (nil? (:file v)) (nil? (:data v)))
    (throw (ex-info (str "kmet.tk.dsl: an image description needs :file"
                         " or :data (prop " prop ")")
                    {:type :kmet.tk.core/image-without-source
                     :prop prop :description v})))
  (when-some [unknown (seq (remove image-description-keys (keys v)))]
    (throw (ex-info (str "kmet.tk.dsl: unknown image description keys "
                         (pr-str (vec unknown)) " (prop " prop ")")
                    {:type :kmet.tk.core/unknown-image-key
                     :prop prop :keys (vec unknown)})))
  (when-some [sizing (seq (filter #(contains? v %) [:zoom :subsample :fit]))]
    (when (= :bitmap (image-kind prop))
      (throw (ex-info (str "kmet.tk.dsl: a :bitmap description cannot be"
                           " sized — " (pr-str (vec sizing))
                           " (Tk bitmaps have no copy; prop " prop ")")
                      {:type :kmet.tk.core/bitmap-cannot-be-sized
                       :prop prop :keys (vec sizing) :description v})))))

(defn image-name
  "V as the Tk image name to script for the image prop PROP on W: a name
   (string or keyword) passes through, a description map is created once
   per widget and image kind and cached on the widget. W nil — a caller
   with no widget to own the image — accepts names only."
  [w prop v]
  (if-not (map? v)
    v
    (do
      (check-image-description! prop v)
      (when (nil? w)
        (throw (ex-info (str "kmet.tk.dsl: an image description needs the "
                             "widget that owns it (prop " prop ")")
                        {:type :kmet.tk.core/image-without-widget :prop prop})))
      (let [kind (image-kind prop)
            k [kind v]]
        (or (get-in @(:styles w) [:images k])
            (let [img (str "::kmet_img_" (swap! next-image inc))]
              (create-image! img kind v)
              (swap! (:styles w) update :images assoc k img)
              img))))))

(defn- release-images!
  "Delete the Tk images W created (a name the app owns is not ours)."
  [w]
  (doseq [name (vals (:images @(:styles w)))]
    (eval! (str "image delete " (tcl-quote name))))
  (swap! (:styles w) dissoc :images)
  nil)

(defn- option-value
  "V as the Tcl value PROP's option takes, per the catalogue's shape: a
   list-valued prop is Tcl list syntax (tcl-list, so an element with a
   space stays one element: -padding/-tabs/-values), the font props build
   the widget's font from PROPS (so any of the four determines it), an
   image prop resolves its description to the widget-owned image name, and
   anything else is the word it names."
  [props prop v w]
  (case (opt/coerce prop)
    :list (tcl-list v)
    :font (font-spec (resolve-base-font props) props)
    :image (image-name w prop v)
    v))

(defn tcl-options
  "PROPS through OPTIONS ({:prop :tcl-option}) as a Tcl option map
   ({:text \"hi\" :foreground \"#fff\"}); a nil prop is absent. Values take
   the shape their prop's catalogue entry names (kmet.tk.options/coerce):
   a Tcl list joins a sequence, the font props build the widget's font, and
   an image prop is created as a resource of W."
  [props options w]
  (into {} (for [[prop option] options
                 :let [v (get props prop)]
                 :when (some? v)]
             [option (option-value props prop v w)])))

(defn- option-args
  "A Tcl option map as `-option value` words, in option-name order so a
   widget's script is stable: a sequence is Tcl list syntax, a keyword or a
   boolean the word it names, and every value is one quoted word."
  [options]
  (for [[option v] (sort-by (comp name key) options)
        :when (some? v)]
    (str "-" (name option) " " (tcl-value v))))

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
   nil when nothing is patchable is set. W owns any image the props describe."
  [w props options]
  (when-let [args (seq (option-args (tcl-options props (configurable-options options) w)))]
    (str/join " " (concat [(:path w) "configure"] args))))

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
        (throw (ex-info (str "kmet.tk.core: " tag " has no " named
                             " on this Tk (prop " prop ")")
                        {:type :kmet.tk.core/unknown-option
                         :tag tag :prop prop :option named})))))
  nil)

(defn- unknown-widget-command
  "The command name in E's Tcl unknown-command error, or nil: a widget
   class the running Tk does not have (ttk::toggleswitch before Tk 9.0)
   fails with `invalid command name \"…\"`."
  [e]
  (second (re-find #"invalid command name \"([^\"]+)\"" (str (ex-message e)))))

(defn- create-widget!
  "Evaluate the widget-construction SCRIPT for SPEC, rethrowing a Tcl
   unknown-command error with the tag and the interpreter's patchlevel — so
   a class this Tk lacks names itself instead of reporting Tcl's raw
   message."
  [spec script]
  (try
    (eval! script)
    (catch Exception e
      (if-let [command (unknown-widget-command e)]
        (throw (ex-info (str "kmet.tk.core: " (:tag spec) " (" command
                             ") does not exist on this Tk ("
                             (eval! "info patchlevel") ")")
                        {:type :kmet.tk.core/missing-widget
                         :tag (:tag spec) :command command}
                        e))
        (throw e)))))

;; ─── Geometry (how a child is placed in its parent) ──────────────────────
;; A child is packed unless it carries a geometry map: {:grid {:row 0
;; :column 1 :sticky "nsew"}} for Tk's grid (a form) or {:place {:relx 0.5
;; :rely 0.5 :anchor :center}} for place (an overlay), each taking Tk's own
;; options for that manager. Naming two managers, or a manager together
;; with pack's own :side/:fill/:expand, is an error here; a *parent* whose
;; children mix grid and pack is Tk's own error, whose message names both
;; (place coexists with either, which is what makes an overlay work in Tk).

(def ^:private pack-props
  "The props a packed child's placement comes from."
  [:side :fill :expand])

(defn- geometry-manager
  "The geometry manager TAG's child names in PROPS: :grid or :place from
   its own map, else nil for pack."
  [tag props]
  (let [named (filterv #(some? (get props %)) [:grid :place])
        pack-named (filterv #(some? (get props %)) pack-props)]
    (when (> (count named) 1)
      (throw (ex-info (str "kmet.tk.core: " tag " names two geometry managers "
                           (pr-str named) " — a child has one")
                      {:type :kmet.tk.core/two-geometry-managers
                       :tag tag :managers named})))
    (when (and (seq named) (seq pack-named))
      (throw (ex-info (str "kmet.tk.core: " tag " names " (first named)
                           " and pack's own " (pr-str pack-named)
                           " — a child has one geometry manager")
                      {:type :kmet.tk.core/mixed-geometry-props
                       :tag tag :manager (first named) :pack-props pack-named})))
    (first named)))

(defn- geometry-args
  "GEOMETRY — PROPS' :grid or :place map — as ` -option value` words in
   option-name order, Tk's own geometry options for the manager."
  [tag manager geometry]
  (when (contains? geometry :in)
    (throw (ex-info (str "kmet.tk.core: " tag "'s " (name manager)
                         " names -in: a child's master is the tag it renders"
                         " under")
                    {:type :kmet.tk.core/geometry-master-not-supported
                     :tag tag :manager manager})))
  (when-some [args (seq (option-args geometry))]
    (str " " (str/join " " args))))

(defn- check-attached-props!
  "Throw when PROPS name a geometry manager for a child its container places
   itself — a notebook tab, a pane, a text widget's embedded window: the
   container owns that placement, so the map would be silently ignored."
  [tag props]
  (when-some [named (seq (filterv #(some? (get props %)) [:grid :place]))]
    (throw (ex-info (str "kmet.tk.core: " tag " names " (first named)
                         " under a container that places it itself (a tab, a"
                         " pane or an embedded window)")
                    {:type :kmet.tk.core/geometry-manager-not-supported
                     :tag tag :managers (vec named)}))))

(defn- manage-child!
  "Place the TAG child at PATH by the manager PROPS name — grid or place
   with the child's own geometry options, else pack with Tk's
   :side/:fill/:expand."
  [tag path props]
  (case (geometry-manager tag props)
    :grid (eval! (str "grid " path (geometry-args tag :grid (:grid props))))
    :place (eval! (str "place " path (geometry-args tag :place (:place props))))
    (eval! (str "pack " path
                " -side " (tcl-value (or (:side props) :top))
                " -fill " (tcl-word (or (:fill props)
                                        (if (#{:ttk/frame :tk/frame :ttk/labelframe} tag)
                                          :both
                                          :x)))
                (when (:expand props) " -expand 1")))))

(defn- grid-master!
  "Configure W's rows and columns from PROPS' :rowconfigure and
   :columnconfigure — {index {:weight 1 :minsize 40}} — through Tk's own
   `grid columnconfigure`, which takes an index or a whole range (`0`, `1`,
   `0 3`, …)."
  [w props]
  (doseq [kind [:columnconfigure :rowconfigure]
          [index opts] (get props kind)]
    (eval! (str "grid " (name kind) " " (:path w) " " (tcl-value index)
                (when-some [args (seq (option-args opts))]
                  (str " " (str/join " " args)))))))

(defn make-widget
  "Construct a TkWidget at a fresh path under the current construction
   parent, run the ctor SCRIPT (a fn of the path and the widget record),
   wire SPEC's bindings and pack it into the parent. PROPS (optional) also
   carry the pack geometry — Tk's own pack options on the child: :side
   (default :top), :fill (default both for pane containers, x for leaves)
   and :expand. A creation command this Tk does not have is rethrown as a
   kmet error naming the tag (see create-widget!)."
  [spec ctor-script & [props]]
  (let [props (or props {})
        tag (:tag spec)
        parent (parent-widget)
        path (alloc-path! parent)
        w (->TkWidget path tag (atom []) (atom {}) (atom {}))]
    (try
      (create-widget! spec (ctor-script path w))
      (check-options! spec tag path props)
      (catch Exception e
        ;; a failed construction can still have created the widget's images
        ;; (the ctor script runs before the option check): release them
        (release-images! w)
        (throw e)))
    (apply-bindings! w {} props (:bindings spec))
    (when (and parent (not (false? (:pack? spec))))
      (if-let [attach (:attach @(:styles parent))]
        ;; containers that own their child placement (an embedded text
        ;; window, a notebook tab, a pane) — and read the child's props for it
        (do (check-attached-props! (:tag spec) props)
            (attach path props))
        (manage-child! (:tag spec) path props)))
    w))

(defn child-lens
  "The engine's children lens over a TkWidget's stored raw children."
  []
  {:get (fn [w] @(:kids w))
   :put (fn [w items] (reset! (:kids w) (mapv :c items)))})

(defn- dispose-widget-extras!
  "Release resources a widget created outside its Tk path — the sibling
   scrollbar the :text tag packs next to the widget."
  [w]
  (when-some [sb (:scrollbar @(:styles w))]
    (eval! (str "destroy " (tcl-quote sb)))))

(defn destroy!
  "Dispose a TkWidget: recurse into its stored children (releasing their
   callbacks), then destroy the widget path, its derived resources and the
   Tk images it created."
  [w]
  (when (some? w)
    (release-callbacks! w)
    (doseq [k @(:kids w)]
      (destroy! k))
    (reset! (:kids w) [])
    (when-not (= "." (:path w))
      (eval! (str "destroy " (:path w))))
    (dispose-widget-extras! w)
    (release-images! w)))

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
  "The command that adds SEG to W: an image segment — `{:image description}`
   with optional :align/:padx/:pady — inserts the embedded image, a text
   segment the styled text; nil for empty text."
  [w seg]
  (if (some? (:image seg))
    (str (:path w) " image create end -image "
         (tcl-quote (image-name w :image (:image seg)))
         (when-some [a (:align seg)] (str " -align " (tcl-word a)))
         (when-some [px (:padx seg)] (str " -padx " (tcl-word px)))
         (when-some [py (:pady seg)] (str " -pady " (tcl-word py))))
    (let [text (str (:text seg))]
      (when (pos? (count text))
        (str (:path w) " insert end " (tcl-quote text)
             (when-some [tag (style-tag! w (segment-style seg))]
               (str " " tag)))))))

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
   string), caching the base font the generated style tags derive from.
   Read-only afterwards (`-state disabled`) but still selectable."
  [w props]
  (swap! (:styles w) assoc :font (resolve-base-font props))
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

(defn set-text-value!
  "Set W's editable text from PROPS' :text, leaving it editable and the
   caret in view. The value is the widget's own text — Tk's trailing
   newline is not part of it — so it is read back and compared here rather
   than in Tcl: a re-render that mirrors :on-change into :text writes
   nothing and never moves the cursor, and a write that does happen resets
   the undo history, so undo takes back the user's edits and not the app's
   (a cleared composer cannot be undone back)."
  [w props]
  (let [p (:path w)
        value (str (or (:text props) ""))]
    (when (not= value (eval! (str p " get 1.0 end-1c")))
      (eval! (str p " delete 1.0 end"))
      (eval! (str p " insert 1.0 " (tcl-quote value)))
      (eval! (str p " edit reset"))
      (eval! (str p " see insert")))))

(defn set-text!
  "Set W's text from PROPS: an editable text takes :text as its value
   (set-text-value!), a read-only one renders :text/:content as display
   segments (set-text-content!)."
  [w props]
  (let [editable? (:editable props)]
    (cond
      (and editable? (some? (:content props)))
      (throw (ex-info (str "kmet.tk.core: an editable text takes :text, not"
                           " :content (segments are a display path)")
                      {:type :kmet.tk.core/editable-with-content
                       :tag (:tag w) :prop :content}))

      editable? (set-text-value! w props)
      :else (set-text-content! w props))))

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
  (apply-config! (configure-script w props (:options spec))))

(defn- default-apply
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

(defn- set-entry-text!
  "Replace W's text with TEXT (nil clears it)."
  [w text]
  (eval! (str (:path w) " delete 0 end"))
  (when (some? text)
    (eval! (str (:path w) " insert 0 " (tcl-quote text)))))

(defn set-scrollbar-position!
  "Set scrollbar W's thumb to PROPS' :position [first last] (a no-op when
   the prop is absent)."
  [w props]
  (when-some [[first last] (seq (:position props))]
    (eval! (str (:path w) " set " (tcl-quote first) " " (tcl-quote last)))))

;; ─── Canvas items (the :tk/canvas tag) ───────────────────────────────────
;; Tk gives a canvas item a numeric id of its own, but lets any tag stand
;; for the item wherever a tagOrId is taken — so a rendered item's identity
;; is written as its first tag: the app's own :id (i1, i2, … when it names
;; none), which every item command then addresses. A re-render reconciles
;; against what the widget holds: a surviving id keeps its item — the
;; coordinates through Tk's `coords` subcommand (there is no -coords item
;; option) and the changed options through `itemconfigure` — a new id is
;; created, a gone id deleted with the callbacks it held, and an item whose
;; type or option set changed is recreated, since Tk cannot unset an option.
;; Tk's creation order is the render's stacking order (a later item is on
;; top): items are created in the render's own order, and a render that
;; reorders, inserts or rebuilds below the top raises them into place. An
;; item's handlers (canvas-item-events) are Tk's item binds on that tag, so
;; Tk directs to them the mouse events it gives the current item.

(def ^:private canvas-item-events
  "An item's handlers and the Tk sequence each is bound to. Tk directs
   mouse events to the current item — the topmost one under the pointer — so
   these fire while the pointer is over the item: the button for a click,
   motion with the button held for a drag, and the crossing for entering and
   leaving it. A drag that leaves the item is the canvas's own bind, where
   Tk's pointer grab keeps the events coming."
  {:on-click "<Button-1>"
   :on-drag "<B1-Motion>"
   :on-enter "<Enter>"
   :on-leave "<Leave>"})

(def ^:private canvas-item-keys
  "The :items entry keys that are not Tcl canvas-item options."
  (into #{:id :type :coords} (keys canvas-item-events)))

(def ^:private canvas-image-options
  "The canvas-item options that name an image: the item's own vocabulary,
   taking the same values an image prop does (a Tk image name, or a
   description the canvas widget owns)."
  #{:image :bitmap :activeimage :disabledimage})

(defn- item-options
  "ITEM's own options — the keys in KMET-KEYS dropped — with every option in
   IMAGE-KEYS resolved to the image W owns (the option's own description, or
   the Tk image name it passes through)."
  [w item kmet-keys image-keys]
  (into {}
        (map (fn [[k v]] [k (if (contains? image-keys k) (image-name w k v) v)]))
        (apply dissoc item kmet-keys)))

(defn check-unique-ids!
  "Throw when IDS are not distinct: an id is how a kmet render addresses an
   item in Tk, so two items sharing one would move, style and delete
   together — and a selection by that id would reach only one of them."
  [tag ids]
  (when-some [dupes (seq (keep (fn [[id n]] (when (> n 1) id)) (frequencies ids)))]
    (throw (ex-info (str "kmet.tk.core: " tag " renders the item id "
                         (pr-str (vec dupes)) " twice — an id names one item")
                    {:type :kmet.tk.core/duplicate-item-id
                     :tag tag :ids (vec dupes)}))))

(defn- canvas-item-id
  "ITEM's identity: its own :id (which is also its first Tk tag), or i<n>
   from its place in the render."
  [i item]
  (str (or (:id item) (str "i" (inc i)))))

(defn- canvas-item-options
  "ITEM's Tcl canvas-item options for the item ID: its own options — an
   image description resolved to the image W owns — with its tags, ID
   first, which is what makes the item addressable by its id."
  [w id item]
  (-> (item-options w item canvas-item-keys canvas-image-options)
      (assoc :tags (into [id] (:tags item)))))

(defn- canvas-create-item!
  "Create ITEM as the item ID on canvas W."
  [w id item]
  (eval! (str (:path w) " create " (tcl-word (or (:type item) :line))
              (when-let [coords (seq (:coords item))]
                (str " " (str/join " " (map tcl-value coords))))
              " " (str/join " " (option-args (canvas-item-options w id item))))))

(defn- canvas-configure-item!
  "Update canvas W's item ID from WAS to ITEM in place."
  [w id was item]
  (when (not= (:coords was) (:coords item))
    (eval! (str (:path w) " coords " (tcl-quote id) " "
                (str/join " " (map tcl-value (:coords item))))))
  (let [before (canvas-item-options w id was)
        after (canvas-item-options w id item)
        changed (into {} (remove (fn [[k v]] (= v (get before k)))) after)]
    (when (seq changed)
      (eval! (str (:path w) " itemconfigure " (tcl-quote id) " "
                  (str/join " " (option-args changed)))))))

(defn- bind-canvas-item!
  "Point W's item ID at ITEM's handlers: each is Tk's item bind for its own
   event, so it fires while the pointer is over the item and hands the
   handler the event's coordinates — which item it is, the render that made
   it knows. A handler WAS had and ITEM drops is unbound."
  [w id item was]
  (doseq [[prop event] canvas-item-events
          :when (not= (get item prop) (get was prop))]
    (bind-slot! w [:item id prop] {:target id :event event :value :pointer}
                (get item prop))))

(defn- restack-items!
  "Make W's stacking order the render's IDS: a later item draws on top.
   Tk's raise is by tag, and an item's id tag names just it."
  [w ids]
  (eval! (str "foreach ::kmet_item " (tcl-value ids)
              " { " (:path w) " raise $::kmet_item }")))

(defn- order-stale?
  "Whether a render's IDS differ from what PREVIOUS's stacking already is: a
   render that only appends keeps creation order, while an insertion or a
   reorder does not."
  [previous ids]
  (not= previous (vec (take-while (set previous) ids))))

(defn- canvas-rebuild?
  "Whether a canvas item must be rebuilt rather than updated: it is new, its
   type changed, or an option it had is gone — Tk cannot unset an option."
  [was item]
  (or (nil? was)
      (not= (:type was) (:type item))
      (seq (remove #(contains? item %) (remove canvas-item-keys (keys was))))))

(defn- restack-needed?
  "Whether IDS, some of them REBUILT this render, need raising: a rebuild
   is created on top, so it is out of place anywhere but at the end."
  [rebuilt ids]
  (some #(and (contains? rebuilt %) (not= % (peek ids))) ids))

(defn canvas-items!
  "Reconcile canvas W's items with PROPS' :items — each an item map of Tk's
   own canvas-item options (`{:type :line :coords [x y …] :fill \"#fff\"}`)
   plus :id (its identity and its first Tk tag), :tags, and the handlers an
   item takes (canvas-item-events: :on-click, :on-drag, :on-enter,
   :on-leave). An id this render keeps is updated in place, a new one
   created, a gone one deleted with the trampolines its handlers held; the
   render's order is Tk's stacking order — a later item draws on top."
  [w props]
  (let [items-list (or (:items props) [])
        ids (mapv canvas-item-id (range) items-list)
        previous (:canvas-items @(:styles w))
        order (:canvas-order @(:styles w))
        pairs (mapv vector ids items-list)
        items (into {} pairs)
        ;; the ids this render rebuilt: created, or deleted and created again
        rebuilt (into #{} (keep (fn [[id item]]
                                  (when (canvas-rebuild? (get previous id) item) id)))
                      pairs)]
    (check-unique-ids! (:tag w) ids)
    (doseq [[id was] previous
            :when (not (contains? items id))]
      (bind-canvas-item! w id nil was)
      (eval! (str (:path w) " delete " (tcl-quote id))))
    ;; in render order, so a render's new items are created bottom to top
    (doseq [[id item] pairs]
      (let [was (get previous id)]
        (if (contains? rebuilt id)
          (do (when (some? was)
                (bind-canvas-item! w id nil was)
                (eval! (str (:path w) " delete " (tcl-quote id))))
              (canvas-create-item! w id item)
              (bind-canvas-item! w id item nil))
          (do (when (not= was item) (canvas-configure-item! w id was item))
              (bind-canvas-item! w id item was)))))
    (when (and (some? order)
               (or (order-stale? order ids)
                   (restack-needed? rebuilt ids)))
      (restack-items! w ids))
    (swap! (:styles w) assoc :canvas-items items :canvas-order ids)
    nil))

;; ─── Menus (the :tk/menu tag and the menubuttons) ─────────────────────────

(defn- release-menu-slots!
  "Drop W's menu-item callbacks (slot keys [ROOT …]): rebuilding a menu's
   items must release the trampolines the old items held."
  [w root]
  (doseq [k (keys @(:callbacks w))
          :when (and (vector? k) (= root (first k)))]
    (release-slot! w k))
  nil)

(def ^:private menu-item-keys
  "The :items entry keys that are not Tcl menu-item options."
  #{:kind :items :on-click :checked :group})

(defn- menu-item-options
  "ITEM's `add` options: its label, then every key it carries that is a Tcl
   menu-item option (-accelerator, -state, -underline, -image, a radio
   item's -value, …) — Tk's own item vocabulary, with :image resolved to
   the image W owns."
  [w item]
  (merge {:label (or (:label item) "")}
         (item-options w item menu-item-keys #{:image})))

(defn- item-var
  "The private Tcl variable a checkbutton item at SLOT of the menu at
   MENU keeps its checked state in."
  [menu slot]
  (check-var (str menu "_item_" (str/join "_" (rest slot)))))

(defn- group-var
  "The private Tcl variable the radio group GROUP of the menu at MENU
   shares: one variable per group is what makes Tk's radio items exclusive."
  [menu group]
  (check-var (str menu "_group_" (if (some? group) (str group) "menu"))))

(defn- menu-radio-states
  "The value each radio group among ITEMS should hold: the :value of the
   last of its items that is :checked, else \"\" (nothing selected)."
  [items]
  (let [radios (filter #(and (map? %) (= :radio (:kind %)))
                       (tree-seq sequential? seq items))]
    (merge (zipmap (map :group radios) (repeat ""))
           (into {} (comp (filter :checked) (map (juxt :group :value))) radios))))

(defn- menu-item-command
  "The trampoline script for ITEM's :on-click: a checkbutton item's handler
   receives the state Tk has just written to its variable VAR, a
   radiobutton item's whether its group variable now holds its :value, and
   a plain command item's nothing, as Tk hands it nothing."
  [w slot item var]
  (when-some [f (:on-click item)]
    (let [kind (:kind item)
          state (case kind
                  :check (str "[set " var "]")
                  ;; `string equal`, not `expr`'s eq: the value is Tcl's own
                  ;; word, which expr would read as a bareword
                  :radio (str "[string equal [set " var "] " (tcl-value (:value item)) "]")
                  nil)]
      (str (set-callback! w slot f nil (when state :variable))
           (when state (str " " state))))))

(defn- menu-add!
  "Add ITEMS to the menu at PATH in W, giving each item its own slot
   (SLOT conj'd with its index) so a rebuild can release what it held. A
   checkbutton item keeps its state in a private variable of its own, a
   radiobutton item in its group's — which is what makes the group
   exclusive, in Tk."
  [w path items slot]
  (doseq [[i item] (map-indexed vector items)
          :let [slot (conj slot i)]]
    (cond
      (or (= item :separator) (= (:kind item) :separator))
      (eval! (str path " add separator"))

      (:items item)
      (let [sub (alloc-path! w)]
        (eval! (str "menu " sub " -tearoff 0"))
        (eval! (str path " add cascade -label " (tcl-quote (or (:label item) ""))
                    " -menu " sub))
        (menu-add! w sub (:items item) slot))

      :else
      (let [kind (:kind item)
            var (case kind
                  :check (item-var path slot)
                  :radio (group-var path (:group item))
                  nil)
            command (menu-item-command w slot item var)]
        (when (and (= :radio kind) (not (contains? item :value)))
          (throw (ex-info (str "kmet.tk.core: " (:tag w) "'s radio item "
                               (pr-str (:label item)) " has no :value — Tk"
                               " compares the group's variable against it")
                          {:type :kmet.tk.core/radio-item-without-value
                           :tag (:tag w) :label (:label item)})))
        (when (= :check kind)
          (eval! (str "set " var " " (tcl-value (boolean (:checked item))))))
        (eval! (str path " add " (case kind :check "checkbutton" :radio "radiobutton" "command")
                    " " (str/join " " (option-args
                                       (cond-> (menu-item-options w item)
                                         (= :check kind) (assoc :variable var :onvalue 1 :offvalue 0)
                                         (= :radio kind) (assoc :variable var))))
                    (when command (str " -command {" command "}"))))))))

(defn- menu-path
  "The menu W's :items go in: W's own path when W is the menu, else the
   popped-down menu it keeps (created, tear-off off, and wired to its -menu
   option on first use)."
  [w]
  (if (= :tk/menu (:tag w))
    (:path w)
    (or (get @(:styles w) :menu)
        (let [menu (alloc-path! w)]
          (eval! (str "menu " menu " -tearoff 0"))
          (eval! (str (:path w) " configure -menu " menu))
          (swap! (:styles w) assoc :menu menu)
          menu))))

(defn set-menu-items!
  "Replace W's menu items with PROPS' :items, releasing the trampolines the
   old items held. `[:separator]` (or {:kind :separator}) is a rule, a map
   with :items a cascade (its nested items become a child menu);
   {:kind :check :label … :checked …} is a checkbutton item and
   {:kind :radio :value … :group … :checked …} a radiobutton one — a
   handler on either receives the new boolean. Any other menu-item option
   in the map (-accelerator, -state, -underline, -image) is Tk's own. A
   radio group's shared variable carries the selection, so the render
   writes it before the items read it."
  [w props]
  (let [path (menu-path w)
        items (:items props)]
    (release-menu-slots! w :menu)
    (eval! (str path " delete 0 end"))
    (doseq [[group value] (menu-radio-states items)]
      (eval! (str "set " (group-var path group) " " (tcl-value (or value "")))))
    (menu-add! w path items [:menu])
    nil))

;; ─── The context menu (:context-menu, on any tag) ────────────────────────
;; Tk has no widget option for a popup menu: the idiom — Tk's own, from the
;; menu man page — posts with tk_popup from a binding on the platform's
;; secondary click. Tk 9 has that click as its own event, <<ContextMenu>>
;; (generated for the platform's gesture, and there on every windowing
;; system), so it is what is bound where it exists; a Tk without it gets
;; the classic <Button-3>, and on macOS the pair Tk's own scripts use —
;; binding X11's <Button-2> would take over the primary-selection paste.
;; tk_popup posts wherever the click was, in root coordinates, and unposts
;; a menu that is already up, so the handler needs no state of its own.

(defn- context-menu-events
  "The events that post a widget's context menu on this Tk."
  []
  (cond
    (seq (eval! "event info <<ContextMenu>>"))
    ["<<ContextMenu>>"]

    (= "aqua" (str/trim (eval! "tk windowingsystem")))
    ["<Button-2>" "<Control-Button-1>"]

    :else
    ["<Button-3>"]))

(defn- context-menu-path
  "The popup menu W's :context-menu items go in: created under W on first
   use (tear-off off), so Tk destroys it with the widget."
  [w]
  (or (get @(:styles w) :context-menu)
      (let [menu (alloc-path! w)]
        (eval! (str "menu " menu " -tearoff 0"))
        (swap! (:styles w) assoc :context-menu menu)
        menu)))

(defn- bind-context-menu!
  "Point W's context-menu events at tk_popup on MENU — posted where the
   click was, in root coordinates — or unbind them when MENU is nil."
  [w menu]
  (doseq [event (context-menu-events)]
    (eval! (str "bind " (:path w) " " event " "
                (if menu (str "{tk_popup " menu " %X %Y}") "{}")))))

(defn set-context-menu!
  "Point W's platform context-menu event at PROPS' :context-menu items,
   which are a menu's own (`:tk/menu` :items): a map with :items is a
   cascade, :separator a rule, anything else a command whose :on-click
   fires through the trampoline. Nil (or no items) unbinds the event and
   destroys the menu; the items are W's own menu, created under it."
  [w props]
  (let [items (:context-menu props)
        menu (get @(:styles w) :context-menu)]
    (release-menu-slots! w :context-menu)
    (if (empty? items)
      (do (bind-context-menu! w nil)
          (when (some? menu)
            (eval! (str "destroy " menu))
            (swap! (:styles w) dissoc :context-menu)))
      (let [menu (context-menu-path w)]
        (eval! (str menu " delete 0 end"))
        (menu-add! w menu items [:context-menu])
        (bind-context-menu! w menu)))))

;; ─── The spec builder ─────────────────────────────────────────────────────

;; A checkable widget's state lives in a private Tcl variable: a classic Tk
;; checkbutton answers select/deselect and has no state query at all, so the
;; variable is what :active writes, what :on-toggled reads back, and what a
;; radiobutton's own -value is compared against.

(defn- checkable-options
  "The creation options a checkable widget needs: its private variable and
   its values (a checkbutton's 1/0, a radiobutton's own :value)."
  [path kind props]
  (if (= kind :radio)
    {:variable (check-var path) :value (or (:value props) 1)}
    {:variable (check-var path) :onvalue 1 :offvalue 0}))

(defn- select!
  "Apply PROPS' :active to a checkable widget of KIND — the variable holds
   the on value, or OFF when this button is not the selected one."
  [w kind props]
  (let [radio? (= kind :radio)]
    (eval! (str "set " (check-var (:path w)) " "
                (tcl-value (if (:active props)
                             (if radio? (or (:value props) 1) 1)
                             (if radio? "" 0)))))))

(defn widget-spec
  "The spec of a widget whose tag is its Tk command plus its options — the
   one place a tag's construction and its live patching are described.

     command      the Tk creation command
     :options     the props it scripts (kmet.tk.options/options-for)
     :fixed       options the tag always passes, under the props' values: a
                  map, or a fn of the props when they depend on them
     :commands    {prop (fn [w props])} — props Tk sets through the widget's
                  own subcommands (a listbox's :items, a scrollbar's
                  :position, a text's :content). They run at construction and
                  again when the prop changes, so they patch live
     :attach      (fn [w] -> (fn [child-path child-props])): a container that
                  places its children itself
     :after       (fn [w props]): what the widget needs once it exists and
                  that is not a prop (its menu, its scrollbar)
     :bindings    its callback slots (see binding-slots)
     :primary     the prop a bare string child fills (:text for a tag that
                  has one)
     :patchable   extra props beyond what :commands covers
     :lens        the children lens (a container)
     :pack?       false for a widget Tk will not let pack manage (a menu)
     :text-field  true when :text is set through the widget's subcommands
     :checkable   :check or :radio: its state is a private Tcl variable"
  [command {:keys [options fixed commands attach after bindings primary
                   patchable lens pack? text-field checkable]}]
  (let [commands (merge (cond-> {:context-menu set-context-menu!}
                          ;; a container that packs or grids its children owns
                          ;; their rows and columns
                          (and lens (not attach))
                          (assoc :columnconfigure grid-master!
                                 :rowconfigure grid-master!))
                        commands)
        fixed (or fixed {})
        data-props (into (set (keys commands))
                         (concat patchable
                                 (when text-field [:text])
                                 (when checkable [:active])))
        run-commands!
        (fn [w prev props]
          (doseq [[prop setter] commands
                  :when (not= (get prev prop) (get props prop))]
            (setter w props)))]
    (into {}
          (remove (comp nil? val))
          {:options options
           :fixed fixed
           :bindings bindings
           :primary (or primary (when (or text-field checkable) :text))
           :patchable data-props
           :lens lens
           :pack? pack?
           :ctor (fn [spec props]
                   (let [w (make-widget spec
                                        (fn [p w] (widget-script
                                                   command p
                                                   (merge (if (fn? fixed) (fixed props) fixed)
                                                          (when checkable
                                                            (checkable-options p checkable props))
                                                          (tcl-options props (:options spec) w))))
                                        props)]
                     (when checkable
                       (select! w checkable props))
                     (when (and text-field (some? (:text props)))
                       (set-entry-text! w (:text props)))
                     (run-commands! w {} props)
                     (when attach
                       (swap! (:styles w) assoc :attach (attach w)))
                     (when after
                       (after w props))
                     w))
           :apply (fn [spec w prev props]
                    (when (patchable? spec prev props)
                      (when (and checkable (not= (:active prev) (:active props)))
                        (select! w checkable props))
                      (when (and text-field (not= (:text prev) (:text props)))
                        (set-entry-text! w (:text props)))
                      (run-commands! w prev props)
                      (apply-live! spec w prev props)))})))
