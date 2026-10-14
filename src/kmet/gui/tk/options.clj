(ns kmet.gui.tk.options
  "The Tk option vocabulary our tags script, as props: which Tcl option a
   prop names, what shape its value takes, and the families that group the
   options sharing a widget population. kmet.gui.tk's classic specs and
   kmet.gui.ttk's themed specs compose their :options from families plus
   their widget's own additions (options-for), so one declaration drives
   the construction script, the live patch and the patchable decision.

   A prop is Tk's own option name without the dash (`:highlightthickness`
   is -highlightthickness); the exceptions are the short forms we have
   always used (:bg/:fg for -background/-foreground) and the font family,
   where four props set one option (-font).

   Reached values are plain data: words (keywords, booleans, numbers,
   colours, names), Tcl lists (:padding [6 12], :tabs [40 80], :values,
   :scrollregion, :show), and the font family
   (:font-family/:font-size/:bold/:italic). Options that are not a plain
   value are deliberately absent, each because it needs machinery a prop
   cannot be:

     - script-typed: -command (declare it in :bindings with
       {:option :command}, as :on-click does), -validatecommand,
       -invalidcommand, -xscrollcommand, -yscrollcommand (the :scrollbar
       prop wires those), -postcommand, -browsecmd, -invokecommand,
       -tearoffcommand;
     - variable-backed: -variable, -textvariable, -listvariable (kmet
       would have to own the Tcl variable and trace it — a checkable
       widget's values are the tag's own: it gives the widget a private
       variable holding 1/0, or its :value when it is a radiobutton, so
       -onvalue/-offvalue/-tristatevalue are not props either);
     - image-backed: -image, -bitmap, -disabledimage, -selectimage,
       -tristateimage (kmet does no image loading), -labelwidget;
     - another widget: -menu (a menubutton's :items build their own menu),
       -displaycolumns? no, that is a list;
     - only meaningful with one of the above: -selecttype, -title,
       -titlecolumns, -titleitems, -type, -tearoffcommand.

   Tk 9 additions (-locale, -placeholder, -placeholderforeground,
   -textangle, -backgroundimage, -tile, -textangle, -striped, -rowheight,
   -headingheight, -size) are listed: the backend checks a prop's option
   against the running widget (dsl/check-options!), so on a Tk 8.6 host
   they fail with a message naming the option instead of silently doing
   nothing."
  (:require [clojure.set :as set]))

(def options
  "prop → the Tcl option it names."
  {:class :class
   :colormap :colormap
   :container :container
   :visual :visual
   :screen :screen
   :use :use
   :cursor :cursor
   :takefocus :takefocus
   :style :style
   :width :width
   :height :height
   :bg :background
   :fg :foreground
   :backgroundimage :backgroundimage
   :tile :tile
   :relief :relief
   :borderwidth :borderwidth
   :highlightbackground :highlightbackground
   :highlightcolor :highlightcolor
   :highlightthickness :highlightthickness
   :padx :padx
   :pady :pady
   :padding :padding
   :text :text
   :textangle :textangle
   :underline :underline
   :justify :justify
   :anchor :anchor
   :wraplength :wraplength
   :compound :compound
   :state :state
   :activebackground :activebackground
   :activeforeground :activeforeground
   :activeborderwidth :activeborderwidth
   :activerelief :activerelief
   :disabledforeground :disabledforeground
   :default :default
   :exportselection :exportselection
   :selectbackground :selectbackground
   :selectforeground :selectforeground
   :selectborderwidth :selectborderwidth
   :selectcolor :selectcolor
   :inactiveselectbackground :inactiveselectbackground
   :inactiveselectforeground :inactiveselectforeground
   :font-family :font
   :font-size :font
   :bold :font
   :italic :font
   :show :show
   :placeholder :placeholder
   :placeholderforeground :placeholderforeground
   :validate :validate
   :locale :locale
   :disabledbackground :disabledbackground
   :readonlybackground :readonlybackground
   :indicatoron :indicatoron
   :offrelief :offrelief
   :overrelief :overrelief
   :value :value
   :maximum :maximum
   :mode :mode
   :orient :orient
   :phase :phase
   :length :length
   :from :from
   :to :to
   :resolution :resolution
   :digits :digits
   :increment :increment
   :format :format
   :values :values
   :wrap :wrap
   :showvalue :showvalue
   :sliderlength :sliderlength
   :sliderrelief :sliderrelief
   :tickinterval :tickinterval
   :bigincrement :bigincrement
   :label :label
   :troughcolor :troughcolor
   :jump :jump
   :elementborderwidth :elementborderwidth
   :repeatdelay :repeatdelay
   :repeatinterval :repeatinterval
   :buttonbackground :buttonbackground
   :buttoncursor :buttoncursor
   :buttondownrelief :buttondownrelief
   :buttonuprelief :buttonuprelief
   :labelanchor :labelanchor
   :activestyle :activestyle
   :selectmode :selectmode
   :setgrid :setgrid
   :scrollregion :scrollregion
   :xscrollincrement :xscrollincrement
   :yscrollincrement :yscrollincrement
   :closeenough :closeenough
   :confine :confine
   :offset :offset
   :handlepad :handlepad
   :handlesize :handlesize
   :opaqueresize :opaqueresize
   :sashpad :sashpad
   :sashrelief :sashrelief
   :sashwidth :sashwidth
   :showhandle :showhandle
   :tearoff :tearoff
   :aspect :aspect
   :direction :direction
   :size :size
   :rowheight :rowheight
   :headingheight :headingheight
   :striped :striped
   :autoseparators :autoseparators
   :blockcursor :blockcursor
   :endline :endline
   :startline :startline
   :insertbackground :insertbackground
   :insertborderwidth :insertborderwidth
   :insertofftime :insertofftime
   :insertontime :insertontime
   :insertunfocussed :insertunfocussed
   :insertwidth :insertwidth
   :maxundo :maxundo
   :spacing1 :spacing1
   :spacing2 :spacing2
   :spacing3 :spacing3
   :tabs :tabs
   :tabstyle :tabstyle
   :undo :undo})

(def creation-only
  "Props whose option Tk takes only at creation: a change to one rebuilds
   the widget (never a live configure)."
  #{:class :colormap :container :visual :screen :use})

(def list-valued
  "Props whose option is a Tcl list: a sequence renders as one (a single
   value stays the word it is)."
  #{:padding :tabs :values :scrollregion :show})

(def font-props
  "The font family: all four props set the widget's -font, so a font is
   built from whichever of them the render supplied."
  #{:font-family :font-size :bold :italic})

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
   :selectable  #{:exportselection :selectbackground :selectforeground
                  :selectborderwidth :inactiveselectbackground}
   :fonted      #{:font-family :font-size :bold :italic}
   :editable    #{:show :validate}
   :localized   #{:placeholder :placeholderforeground :locale}
   ;; the classic entry/ttk entry/spinbox editing block (-show is not in
   ;; Tk's classic spinbox, so it is a prop of its own)
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

(defn family-names
  "The family keywords options-for understands."
  []
  (sort (keys families)))

(defn option-of
  "The Tcl option PROP names, or nil when the catalogue has no such prop."
  [prop]
  (get options prop))

(defn coerce
  "PROP's value shape: :word (the default), :list or :font."
  [prop]
  (cond
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
      (throw (ex-info (str "kmet.gui.tk.options: unknown families "
                           (pr-str (vec unknown-families))
                           "; known: " (pr-str (vec (family-names))))
                      {:type :kmet.gui.tk.options/unknown-family
                       :families (vec unknown-families)})))
    (when (seq unknown)
      (throw (ex-info (str "kmet.gui.tk.options: props outside the catalogue: "
                           (pr-str (vec unknown)))
                      {:type :kmet.gui.tk.options/unknown-prop
                       :props (vec unknown)})))
    (into {} (map (fn [p] [p (options p)])) (sort props))))
