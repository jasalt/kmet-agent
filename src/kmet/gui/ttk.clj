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
   without an :apply gets the shared live patcher."
  (:require [kmet.gui.tk.dsl :as dsl]))

(def ^:private frame-options
  "The :ttk/frame tag's props as Tcl options."
  {:padding :padding})

(def ^:private label-options
  "The :ttk/label tag's props as Tcl options. ttk::label is one of the ttk
   widgets that accept -background/-foreground per widget."
  {:text :text :fg :foreground :bg :background})

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
                                                        (dsl/tcl-options props frame-options)))
                             props))
    :options frame-options
    :lens (dsl/child-lens)}

   :ttk/labelframe
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script "ttk::labelframe" p
                                                        (dsl/tcl-options props {:text :text})))
                             props))
    :options {:text :text}
    :lens (dsl/child-lens)}

   :ttk/label
   {:ctor (fn [spec {:keys [bold italic] :as props}]
            (dsl/make-widget spec
                             (fn [p]
                               (dsl/widget-script
                                "ttk::label" p
                                (cond-> (merge {:anchor :w :justify :left}
                                               (dsl/tcl-options props label-options))
                                  (or bold italic (some? (:font-size props)))
                                  (assoc :font (dsl/font-spec (dsl/resolve-base-font props) props)))))
                             props))
    :options label-options
    :bindings {:on-click {:event "<Button-1>"}}
    :primary :text}

   :ttk/button
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script "ttk::button" p
                                                        (merge {:text ""}
                                                               (dsl/tcl-options props {:text :text}))))
                             props))
    :options {:text :text}
    :bindings {:on-click {:option :command}}
    :primary :text}

   :ttk/entry
   {:ctor (fn [spec {:keys [text] :as props}]
            (let [w (dsl/make-widget spec (fn [p] (dsl/widget-script "ttk::entry" p {})) props)]
              (when (some? text)
                (dsl/set-entry-text! w text))
              w))
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
                                                     (dsl/tcl-options props {:text :text}))))
                                     props)]
              (dsl/eval! (str (:path w) " state " (if active "selected" "!selected")))
              w))
    :options {:text :text}
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
            (dsl/make-widget spec (fn [p] (dsl/widget-script "ttk::separator" p {})) props))}

   :ttk/progressbar
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script
                                      "ttk::progressbar" p
                                      (merge {:maximum 100}
                                             (dsl/tcl-options
                                              props {:value :value :maximum :maximum}))))
                             props))
    :options {:value :value :maximum :maximum}}})
