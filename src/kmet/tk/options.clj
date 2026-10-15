(ns kmet.tk.options
  "The Tk option vocabulary our tags script, as props: which Tcl option a
   prop names, what shape its value takes, and the families that group the
   options sharing a widget population. kmet.tk.core's classic specs and
   kmet.tk.ttk's themed specs compose their :options from families plus
   their widget's own additions (options-for), so one declaration drives the
   construction script, the live patch and the patchable decision.

   A prop is Tk's own option name without the dash (`:highlightthickness`
   is -highlightthickness); the exceptions are the short forms we have
   always used (:bg/:fg for -background/-foreground) and the font family,
   where four props set one option (-font).

   Reached values are plain data: words (keywords, booleans, numbers,
   colours, names), Tcl lists (:padding [6 12], :tabs [40 80], :values,
   :scrollregion, :show), the font family
   (:font-family/:font-size/:bold/:italic), and images (see image-props:
   a Tk image name, or a description kmet.tk.dsl/image-name turns into
   a widget-owned photo or bitmap). Options that are not a plain value are
   deliberately absent, each because it needs machinery a prop cannot be:

     - script-typed: a callback option is a tag's binding slot (a Tcl
       -command rides {:on-click {:option :command}}, -validatecommand /
       -invalidcommand / -xscrollcommand / -yscrollcommand / -postcommand
       have slots of their own), and -tearoffcommand is not reached yet;
     - another widget: -menu (a menubutton's :items build their own),
       and -labelwidget is not reached yet;
     - only meaningful with one of the above: -selecttype, -title,
       -titlecolumns, -titleitems, -type, -displaycolumns.

   The variable-backed group is a prop family of its own: :variable,
   :textvariable and :listvariable take a shared variable (see
   kmet.tk.dsl/variable) and resolve to its Tcl name, so the widget
   option — and :onvalue/:offvalue/:tristatevalue with it — is a plain
   prop. A variable-backed prop's change rebuilds the widget: the tag
   wires its state (the callback fragments, a private variable's
   fallback) around the name.

   Tk 9 additions (-locale, -placeholder, -placeholderforeground,
   -textangle, -backgroundimage, -tile, -striped, -rowheight,
   -headingheight, -size) are listed: the backend checks a prop's option
   against the running widget (dsl/check-options!), so on a Tk 8.6 host
   they fail with a message naming the option instead of silently doing
   nothing."
  (:require [clojure.set :as set]))

(def ^:private option-props
  "The props that are Tk's own option name without the dash — the bulk of
   the vocabulary."
  #{:class :colormap :container :visual :screen :use :cursor :takefocus :style
    :width :height :backgroundimage :tile :relief :borderwidth
    :highlightbackground :highlightcolor :highlightthickness :padx :pady
    :padding :text :textangle :underline :justify :anchor :wraplength :compound
    :state :activebackground :activeforeground :activeborderwidth :activerelief
    :disabledforeground :default :exportselection :selectbackground
    :selectforeground :selectborderwidth :selectcolor :inactiveselectbackground
    :inactiveselectforeground :indicatoron :offrelief :overrelief :value :maximum
    :mode :orient :phase :length :from :to :resolution :digits :increment :format
    :values :wrap :showvalue :sliderlength :sliderrelief :tickinterval
    :bigincrement :label :troughcolor :jump :elementborderwidth :repeatdelay
    :repeatinterval :buttonbackground :buttoncursor :buttondownrelief
    :buttonuprelief :labelanchor :activestyle :selectmode :setgrid :scrollregion
    :xscrollincrement :yscrollincrement :closeenough :confine :offset :handlepad
    :handlesize :opaqueresize :sashpad :sashrelief :sashwidth :showhandle
    :tearoff :aspect :direction :size :rowheight :headingheight :striped
    :autoseparators :blockcursor :endline :startline :insertbackground
    :insertborderwidth :insertofftime :insertontime :insertunfocussed
    :insertwidth :maxundo :spacing1 :spacing2 :spacing3 :tabs :tabstyle :undo
    :show :placeholder :placeholderforeground :validate :locale
    :disabledbackground :readonlybackground
    :image :bitmap :selectimage :tristateimage :disabledimage
    :variable :textvariable :listvariable
    :onvalue :offvalue :tristatevalue})

(def options
  "prop → the Tcl option it names: the bulk are Tk's own option names, plus
   the short forms kmet keeps and the font family."
  (merge (zipmap option-props option-props)
         {:bg :background
          :fg :foreground
          :font-family :font
          :font-size :font
          :bold :font
          :italic :font}))

(def creation-only
  "Props whose option Tk takes only at creation: a change to one rebuilds
   the widget (never a live configure)."
  #{:class :colormap :container :visual :screen :use})

(def variable-props
  "Props whose value is a shared Tcl variable (kmet.tk.dsl/variable): the
   option takes the variable's Tcl name, and a change to one rebuilds the
   widget — the tag wires its state around the name (a checkable widget's
   read-back fragment, a text field's value path)."
  #{:variable :textvariable :listvariable})

(def list-valued
  "Props whose option is a Tcl list: a sequence renders as one (a single
   value stays the word it is)."
  #{:padding :tabs :values :scrollregion :show})

(def font-props
  "The font family: all four props set the widget's -font, so a font is
   built from whichever of them the render supplied."
  #{:font-family :font-size :bold :italic})

(def image-props
  "The props whose option names an image. A value is either a Tk image name
   — a built-in bitmap such as :questhead, or a name the app created — or a
   description map the DSL turns into a widget-owned image (see
   kmet.tk.dsl/image-name): :file, :data with :format, and the sizing
   :zoom/:subsample/:fit. :bitmap creates a Tk bitmap image, the rest
   photos."
  #{:image :bitmap :selectimage :tristateimage :backgroundimage :tile
    :disabledimage})

(def families
  "Named prop sets a spec composes its :options from (options-for). A
   family holds props that co-occur on one widget population, so a spec
   adds a family whole — and lists its widget's odd options itself rather
   than using a family that is almost right."
  {:standard    #{:cursor :takefocus}
   :classed     #{:class}
   :creation    #{:colormap :container :visual}
   :window      #{:use :screen}
   :themed      #{:style}
   :geometry    #{:width :height}
   :surface     #{:bg :backgroundimage :tile}
   :bordered    #{:relief :borderwidth}
   :highlighted #{:highlightbackground :highlightcolor :highlightthickness}
   :spaced      #{:padx :pady}
   :padded      #{:padding}
   :labelled    #{:text :textangle :underline}
   :layout      #{:justify :anchor :wraplength :compound}
   :stateful    #{:state :activebackground :activeforeground
                  :disabledforeground}
   ;; the classic text-and-graphics widgets: label, button, checkbutton,
   ;; radiobutton, menubutton — every one of them has all of these
   :classic-text #{:activebackground :activeforeground :anchor :compound
                   :disabledforeground :justify :underline :wraplength}
   ;; the classic widgets' image pair; the ttk widgets take :image alone,
   ;; so their specs list it themselves
   :imaged      #{:image :bitmap}
   :selectable  #{:exportselection :selectbackground :selectforeground
                  :selectborderwidth :inactiveselectbackground}
   :fonted      #{:font-family :font-size :bold :italic}
   :editable    #{:show :validate}
   :localized   #{:placeholder :placeholderforeground :locale}
   ;; the entry/spinbox editing block (-show is not in Tk's classic spinbox,
   ;; so it is a prop of its own)
   :entry-body  #{:exportselection :justify :selectbackground
                  :selectforeground :selectborderwidth :insertbackground
                  :insertborderwidth :insertofftime :insertontime
                  :insertwidth :validate :disabledbackground
                  :disabledforeground :readonlybackground :placeholder
                  :placeholderforeground :locale}
   :scrollbar-body #{:orient :troughcolor :jump :elementborderwidth
                     :activerelief :activebackground :repeatdelay
                     :repeatinterval :width}
   ;; the classic scale's own block (ttk's scale has only from/to/orient)
   :scale-body  #{:from :to :resolution :digits :showvalue :sliderlength
                  :sliderrelief :tickinterval :bigincrement :label
                  :repeatdelay :repeatinterval :troughcolor :orient :length}
   :progress    #{:value :maximum :mode :orient :phase :length}
   ;; the text widget's editing body
   :text-body   #{:wrap :autoseparators :blockcursor :endline :startline
                  :insertbackground :insertborderwidth :insertofftime
                  :insertontime :insertunfocussed :insertwidth :maxundo
                  :setgrid :spacing1 :spacing2 :spacing3 :tabs :tabstyle
                  :undo}})

(defn- family-names
  "The family keywords options-for understands."
  []
  (sort (keys families)))

(defn variable-backed?
  "True when PROP takes a shared Tcl variable."
  [prop]
  (contains? variable-props prop))

(defn coerce
  "PROP's value shape: :word (the default), :list, :font, :image or :var."
  [prop]
  (cond
    (contains? image-props prop) :image
    (contains? variable-props prop) :var
    (contains? list-valued prop) :list
    (contains? font-props prop) :font
    :else :word))

(defn creation-only?
  "True when PROP can only be given at creation."
  [prop]
  (contains? creation-only prop))

(defn options-for
  "The {:prop :tcl-option} map of NAMES' families plus the individual
   props in EXTRA, for a spec's :options. Every name is checked against
   the catalogue: a typo must fail here, not script an option Tk rejects."
  [names & [extra]]
  (let [unknown-families (remove #(contains? families %) names)
        props (into (apply set/union (map families names)) extra)
        unknown (remove #(contains? options %) props)]
    (when (seq unknown-families)
      (throw (ex-info (str "kmet.tk.options: unknown families "
                           (pr-str (vec unknown-families))
                           "; known: " (pr-str (vec (family-names))))
                      {:type :kmet.tk.options/unknown-family
                       :families (vec unknown-families)})))
    (when (seq unknown)
      (throw (ex-info (str "kmet.tk.options: props outside the catalogue: "
                           (pr-str (vec unknown)))
                      {:type :kmet.tk.options/unknown-prop
                       :props (vec unknown)})))
    (into {} (map (fn [p] [p (options p)])) (sort props))))
