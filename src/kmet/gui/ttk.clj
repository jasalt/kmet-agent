(ns kmet.gui.ttk
  "The themed-widget tag set — `:ttk/…` — of the Tk backend (kmet.gui.tk):
   the specs of the ttk widgets, which kmet.gui.tk completes (dsl/widget-specs)
   and merges into its closed tag table. The classic widgets are kmet.gui.tk's
   own `:tk/…` set.

   Tag names follow Tcl's own namespace split: the ttk widgets live under
   Tcl's `ttk::` namespace, so they are `:ttk/frame`, `:ttk/label`, …;
   classic Tk widgets are `:tk/frame`, `:tk/text`, ….

   A spec is written against kmet.gui.tk.dsl: its :ctor and :apply receive
   the spec itself (so :options, :bindings and :patchable stay the single
   source of truth for what the widget can and cannot patch), and a spec
   without an :apply gets the shared live patcher. Its :options are the Tk
   options its widget accepts, composed from kmet.gui.tk.options: the
   families that fit the widget (options-for) plus the individual options
   that widget has on its own. The families are additive and a widget's
   odd option is listed directly, so what a tag scripts is exactly the
   widget's option set minus the script-, variable- and image-backed
   options the catalogue leaves out (and minus -labelwidget, a widget)."
  (:require [kmet.gui.tk.dsl :as dsl]
            [kmet.gui.tk.options :as opt]))

(def ^:private entry-bindings
  "The :ttk/entry tag's callbacks; both receive the entry's text."
  {:on-change {:event "<KeyRelease>" :value :text}
   :on-submit {:event "<Return>" :value :text}})

(def ^:private checkbutton-bindings
  "The :ttk/checkbutton tag's toggle handler; receives the new checked state."
  {:on-toggled {:option :command :value :checked}})

(def specs
  "The ttk widget specs, keyed by tag."
  {:ttk/frame
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script "ttk::frame" p
                                                        (dsl/tcl-options props (:options spec))))
                             props))
    :options (opt/options-for [:standard :classed :themed :geometry :bordered :padded])
    :lens (dsl/child-lens)}

   :ttk/labelframe
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script "ttk::labelframe" p
                                                        (dsl/tcl-options props (:options spec))))
                             props))
    :options (opt/options-for [:standard :classed :themed :geometry :bordered :padded]
                              [:text :underline :labelanchor])
    :lens (dsl/child-lens)}

   :ttk/label
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script
                                      "ttk::label" p
                                      (merge {:anchor :w :justify :left}
                                             (dsl/tcl-options props (:options spec)))))
                             props))
    :options (opt/options-for [:standard :classed :themed :bordered :padded
                               :labelled :layout :fonted]
                              [:width :bg :fg :state])
    :bindings {:on-click {:event "<Button-1>"}}
    :primary :text}

   :ttk/button
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script
                                      "ttk::button" p
                                      (merge {:text ""}
                                             (dsl/tcl-options props (:options spec)))))
                             props))
    :options (opt/options-for [:standard :classed :themed :padded]
                              [:width :text :underline :justify :compound :state :default])
    :bindings {:on-click {:option :command}}
    :primary :text}

   :ttk/entry
   {:ctor (fn [spec {:keys [text] :as props}]
            (let [w (dsl/make-widget spec
                                     (fn [p] (dsl/widget-script "ttk::entry" p
                                                                (dsl/tcl-options props (:options spec))))
                                     props)]
              (when (some? text)
                (dsl/set-entry-text! w text))
              w))
    :options (opt/options-for [:standard :classed :themed :editable :fonted :localized]
                              [:width :bg :fg :exportselection :justify :state])
    :bindings entry-bindings
    :patchable #{:text}
    :primary :text
    :apply (fn [spec w prev props]
             (when (dsl/patchable? spec prev props)
               (when (not= (:text prev) (:text props))
                 (dsl/set-entry-text! w (:text props)))
               (dsl/apply-live! spec w prev props)))}

   :ttk/checkbutton
   {:ctor (fn [spec {:keys [active] :as props}]
            (let [w (dsl/make-widget spec
                                     (fn [p] (dsl/widget-script
                                              "ttk::checkbutton" p
                                              (merge {:text ""}
                                                     (dsl/tcl-options props (:options spec)))))
                                     props)]
              (dsl/eval! (str (:path w) " state " (if active "selected" "!selected")))
              w))
    :options (opt/options-for [:standard :classed :themed :padded :checkable]
                              [:width :text :underline :justify :compound :state])
    :bindings checkbutton-bindings
    :patchable #{:active}
    :primary :text
    :apply (fn [spec w prev props]
             (when (dsl/patchable? spec prev props)
               (when (not= (:active prev) (:active props))
                 (dsl/eval! (str (:path w) " state "
                                 (if (:active props) "selected" "!selected"))))
               (dsl/apply-live! spec w prev props)))}

   :ttk/separator
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script "ttk::separator" p
                                                        (dsl/tcl-options props (:options spec))))
                             props))
    :options (opt/options-for [:standard :classed :themed] [:orient])}

   :ttk/progressbar
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script
                                      "ttk::progressbar" p
                                      (merge {:maximum 100}
                                             (dsl/tcl-options props (:options spec)))))
                             props))
    :options (opt/options-for [:standard :classed :themed :progress :fonted]
                              [:fg :anchor :justify :text :wraplength])}})
