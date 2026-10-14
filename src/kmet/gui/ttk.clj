(ns kmet.gui.ttk
  "The themed-widget tag set — `:ttk/…` — of the Tk backend (kmet.gui.tk):
   the specs of the ttk widgets, which kmet.gui.tk completes (dsl/widget-specs)
   and merges into its closed tag table. The classic widgets are kmet.gui.tk's
   own `:tk/…` set.

   Tag names follow Tcl's own namespace split: the ttk widgets live under
   Tcl's `ttk::` namespace, so they are `:ttk/frame`, `:ttk/label`, …;
   classic Tk widgets are `:tk/frame`, `:tk/text`, ….

   Every spec is dsl/widget-spec: the Tk command plus its :options (the
   families in kmet.gui.tk.options that fit the widget, with its own
   options), and whatever else the tag needs — :commands for the props Tk
   sets through subcommands (a treeview's rows), :attach for a container
   that places its children (a notebook's tabs), :after for what a widget
   needs once it exists (a menubutton's menu). The families are additive and
   a widget's odd option is listed directly, so what a tag scripts is
   exactly the widget's option set minus the script-, variable- and
   image-backed options the catalogue leaves out.

   Notably absent, because Tk needs a -variable for them: a radiobutton
   group's shared selection (the caller owns which button is :active) and a
   menu item that stays down (:items are commands, separators and cascades)."
  (:require [clojure.string :as str]
            [kmet.gui.tk.dsl :as dsl]
            [kmet.gui.tk.options :as opt]))

;; ─── Containers ───────────────────────────────────────────────────────────

(defn- notebook-attach
  "Place PATH in notebook W as a tab, named by the child's :tab."
  [w]
  (fn [path props]
    (dsl/eval! (str (:path w) " add " path " -text "
                    (dsl/tcl-quote (or (:tab props) ""))))))

(defn- paned-attach
  "Place PATH in panedwindow W as a pane, weighted by the child's :weight."
  [w]
  (fn [path props]
    (dsl/eval! (str (:path w) " add " path
                    (when-some [weight (:weight props)]
                      (str " -weight " (dsl/tcl-quote (dsl/tcl-word weight))))))))

;; ─── The data widgets' own subcommands ────────────────────────────────────

(defn- columns
  "PROPS' :columns as [{:id :heading :width :anchor :stretch}]: a bare
   keyword is a column named by it, and its heading defaults to its name."
  [props]
  (mapv (fn [c]
          (let [spec (if (map? c) c {:id c})]
            {:id (name (:id spec))
             :heading (or (:heading spec) (name (:id spec)))
             :width (:width spec)
             :anchor (:anchor spec)
             :stretch (:stretch spec)}))
        (:columns props)))

(defn- headings!
  "Configure tree W's headings and column widths from PROPS' :columns."
  [w props]
  (doseq [{:keys [id heading width anchor stretch]} (columns props)]
    (dsl/eval! (str (:path w) " heading " id " -text " (dsl/tcl-quote heading)))
    (when (or width anchor (some? stretch))
      (dsl/eval! (str (:path w) " column " id
                      (when width (str " -width " width))
                      (when anchor (str " -anchor " (dsl/tcl-word anchor)))
                      (when (some? stretch)
                        (str " -stretch " (dsl/tcl-word stretch))))))))

(defn- tree-rows!
  "Replace tree W's rows with PROPS' :rows: each row a vector of cells, or a
   map with :id and :values (ids default to r1, r2, …)."
  [w props]
  (dsl/eval! (str (:path w) " delete [" (:path w) " children {}]"))
  (doseq [[i row] (map-indexed vector (or (:rows props) []))
          :let [cells (if (map? row) (:values row) row)
                id (str (if (map? row) (or (:id row) (str "r" (inc i))) (str "r" (inc i))))]]
    (dsl/eval! (str (:path w) " insert {} end -id " (dsl/tcl-quote id)
                    (when (seq cells)
                      (str " -values {" (str/join " " (map dsl/tcl-quote cells)) "}"))))))

(defn- tree-selection!
  "Select PROPS' :selected rows in tree W (one id, several, or nil to
   clear)."
  [w props]
  (dsl/eval! (str (:path w) " selection remove [" (:path w) " selection]"))
  (let [ids (if (sequential? (:selected props)) (:selected props)
                (when (some? (:selected props)) [(:selected props)]))]
    (when (seq ids)
      (dsl/eval! (str (:path w) " selection set "
                      (str/join " " (map dsl/tcl-quote ids)))))))

(defn- notebook-selected!
  "Select PROPS' :selected tab in notebook W."
  [w props]
  (when (some? (:selected props))
    (dsl/eval! (str (:path w) " select " (:selected props)))))

;; ─── The specs ────────────────────────────────────────────────────────────

(def specs
  "The ttk widget specs, keyed by tag."
  {:ttk/frame
   (dsl/widget-spec "ttk::frame"
                    {:options (opt/options-for [:standard :classed :themed
                                                :geometry :bordered :padded])
                     :lens (dsl/child-lens)})

   :ttk/labelframe
   (dsl/widget-spec "ttk::labelframe"
                    {:options (opt/options-for [:standard :classed :themed :geometry
                                                :bordered :padded]
                                               [:text :underline :labelanchor])
                     :lens (dsl/child-lens)})

   :ttk/label
   (dsl/widget-spec "ttk::label"
                    {:options (opt/options-for [:standard :classed :themed :bordered
                                                :padded :labelled :layout :fonted]
                                               [:width :bg :fg :state])
                     :fixed {:anchor :w :justify :left}
                     :bindings {:on-click {:event "<Button-1>"}}
                     :primary :text})

   :ttk/button
   (dsl/widget-spec "ttk::button"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:width :text :underline :justify
                                                :compound :state :default])
                     :bindings {:on-click {:option :command}}})

   :ttk/checkbutton
   (dsl/widget-spec "ttk::checkbutton"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:width :text :underline :justify
                                                :compound :state])
                     :bindings dsl/check-bindings
                     :checkable :check})

   :ttk/radiobutton
   (dsl/widget-spec "ttk::radiobutton"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:width :text :underline :justify
                                                :compound :state :value])
                     :bindings dsl/radio-bindings
                     :checkable :radio})

   :ttk/entry
   (dsl/widget-spec "ttk::entry"
                    {:options (opt/options-for [:standard :classed :themed :editable
                                                :fonted :localized]
                                               [:width :bg :fg :exportselection
                                                :justify :state])
                     :bindings dsl/text-field-bindings
                     :text-field true})

   :ttk/combobox
   (dsl/widget-spec "ttk::combobox"
                    {:options (opt/options-for [:standard :classed :themed :editable
                                                :fonted :localized]
                                               [:width :height :bg :fg :exportselection
                                                :justify :state :values])
                     :bindings (assoc dsl/text-field-bindings
                                      :on-change {:event "<<ComboboxSelected>>"
                                                  :value :text})
                     :text-field true})

   :ttk/spinbox
   (dsl/widget-spec "ttk::spinbox"
                    {:options (opt/options-for [:standard :classed :themed :editable
                                                :fonted :localized]
                                               [:width :bg :fg :exportselection :justify
                                                :state :values :format :from :to
                                                :increment :wrap])
                     :bindings dsl/spinbox-bindings
                     :text-field true})

   :ttk/scale
   (dsl/widget-spec "ttk::scale"
                    {:options (opt/options-for [:standard :classed :themed]
                                               [:length :orient :from :to :value :state])
                     :bindings {:on-change {:option :command :arg :number}}})

   :ttk/scrollbar
   (dsl/widget-spec "ttk::scrollbar"
                    {:options (opt/options-for [:standard :classed :themed] [:orient])
                     :bindings {:on-scroll {:option :command}}
                     :commands {:position dsl/set-scrollbar-position!}})

   :ttk/sizegrip
   (dsl/widget-spec "ttk::sizegrip"
                    {:options (opt/options-for [:standard :classed :themed])})

   :ttk/toggleswitch
   (dsl/widget-spec "ttk::toggleswitch"
                    {:options (opt/options-for [:standard :classed :themed]
                                               [:width :size :text :underline])
                     :bindings dsl/check-bindings
                     :checkable :check})

   :ttk/menubutton
   (dsl/widget-spec "ttk::menubutton"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:width :text :underline :justify
                                                :compound :state :direction])
                     :bindings {:on-click {:event "<Button-1>"}}
                     :primary :text
                     :commands {:items dsl/menu-items!}})

   :ttk/notebook
   (dsl/widget-spec "ttk::notebook"
                    {:options (opt/options-for [:standard :classed :themed
                                                :geometry :padded])
                     :bindings {:on-tab-changed {:event "<<NotebookTabChanged>>"
                                                 :value :tab-index}}
                     :commands {:selected notebook-selected!}
                     :attach notebook-attach
                     :lens (dsl/child-lens)})

   :ttk/panedwindow
   (dsl/widget-spec "ttk::panedwindow"
                    {:options (opt/options-for [:standard :classed :themed]
                                               [:width :height :orient])
                     :attach paned-attach
                     :lens (dsl/child-lens)})

   :ttk/progressbar
   (dsl/widget-spec "ttk::progressbar"
                    {:options (opt/options-for [:standard :classed :themed
                                                :progress :fonted]
                                               [:fg :anchor :justify :text :wraplength])})

   :ttk/separator
   (dsl/widget-spec "ttk::separator"
                    {:options (opt/options-for [:standard :classed :themed] [:orient])})

   :ttk/treeview
   (dsl/widget-spec "ttk::treeview"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:height :selectmode :show :rowheight
                                                :headingheight :striped])
                     :fixed (fn [props]
                              (let [cols (columns props)]
                                (merge {:show :headings}
                                       (when (seq cols)
                                         {:columns (str/join " " (map :id cols))}))))
                     :bindings {:on-select {:event "<<TreeviewSelect>>" :value :ids}}
                     :commands {:rows tree-rows!
                                :selected tree-selection!}
                     :after headings!})})
