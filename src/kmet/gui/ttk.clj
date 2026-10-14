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
   options the catalogue leaves out.

   Containers place their children themselves (:attach on the parent):
   :ttk/notebook adds each child as a tab (the child's :tab names it) and
   :ttk/panedwindow adds each child as a pane (the child's :weight sizes
   it). Data widgets take their content as props rather than children:
   :ttk/treeview takes :columns and :rows, :ttk/combobox and :ttk/spinbox
   take :values.

   Notably absent, because Tk needs a -variable for them: a radiobutton
   group's shared selection (the caller owns which button is :active) and
   a menu item that stays down (:items are commands, separators and
   cascades)."
  (:require [clojure.string :as str]
            [kmet.gui.tk.dsl :as dsl]
            [kmet.gui.tk.options :as opt]))

;; ─── Callback slots ───────────────────────────────────────────────────────

(def ^:private entry-bindings
  "The entry-like tags' callbacks: :on-change fires while typing and
   :on-submit on Return, both receiving the text."
  {:on-change {:event "<KeyRelease>" :value :text}
   :on-submit {:event "<Return>" :value :text}})

(def ^:private spinbox-bindings
  "A spinbox adds :on-spin, Tk's -command, which fires when the arrows (not
   the keyboard) change the value."
  (assoc entry-bindings :on-spin {:option :command :value :text}))

(def ^:private checkbutton-bindings
  "The checkbutton's and the toggleswitch's toggle handler; receives the
   new checked state."
  {:on-toggled {:option :command :value :checked}})

(def ^:private radiobutton-bindings
  "The radiobutton's click handler; the caller owns which button of the
   group is :active (Tk's own group selection is a -variable)."
  {:on-click {:option :command}})

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

;; ─── The treeview's data ──────────────────────────────────────────────────

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

(defn- tree-rows!
  "Replace tree W's rows with ROWS: each row a vector of cells, or a map
   with :id and :values (ids default to r1, r2, …)."
  [w rows]
  (dsl/eval! (str (:path w) " delete [" (:path w) " children {}]"))
  (doseq [[i row] (map-indexed vector (or rows []))
          :let [cells (if (map? row) (:values row) row)
                id (str (if (map? row) (or (:id row) (str "r" (inc i))) (str "r" (inc i))))]]
    (dsl/eval! (str (:path w) " insert {} end -id " (dsl/tcl-quote id)
                    (when (seq cells)
                      (str " -values {" (str/join " " (map dsl/tcl-quote cells)) "}"))))))

(defn- tree-selection!
  "Select row IDS in tree W (one id, several, or nil to clear)."
  [w ids]
  (dsl/eval! (str (:path w) " selection remove [" (:path w) " selection]"))
  (when-some [ids (seq (if (sequential? ids) ids (when (some? ids) [ids])))]
    (dsl/eval! (str (:path w) " selection set " (str/join " " (map dsl/tcl-quote ids))))))

;; ─── The specs ────────────────────────────────────────────────────────────

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

   :ttk/checkbutton
   (dsl/checkable-spec "ttk::checkbutton" :check
                       {:options (opt/options-for [:standard :classed :themed :padded]
                                                  [:width :text :underline :justify :compound :state])
                        :fixed {:text ""}
                        :bindings checkbutton-bindings})

   :ttk/radiobutton
   (dsl/checkable-spec "ttk::radiobutton" :radio
                       {:options (opt/options-for [:standard :classed :themed :padded]
                                                  [:width :text :underline :justify :compound :state :value])
                        :fixed {:text ""}
                        :bindings radiobutton-bindings})

   :ttk/entry
   (dsl/entry-spec "ttk::entry"
                   {:options (opt/options-for [:standard :classed :themed :editable :fonted :localized]
                                              [:width :bg :fg :exportselection :justify :state])
                    :bindings entry-bindings})

   :ttk/combobox
   (dsl/entry-spec "ttk::combobox"
                   {:options (opt/options-for [:standard :classed :themed :editable :fonted :localized]
                                              [:width :height :bg :fg :exportselection :justify :state :values])
                    :bindings {:on-change {:event "<<ComboboxSelected>>" :value :text}
                               :on-submit {:event "<Return>" :value :text}}})

   :ttk/spinbox
   (dsl/entry-spec "ttk::spinbox"
                   {:options (opt/options-for [:standard :classed :themed :editable :fonted :localized]
                                              [:width :bg :fg :exportselection :justify :state
                                               :values :format :from :to :increment :wrap])
                    :bindings spinbox-bindings})

   :ttk/scale
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script "ttk::scale" p
                                                        (dsl/tcl-options props (:options spec))))
                             props))
    :options (opt/options-for [:standard :classed :themed]
                              [:length :orient :from :to :value :state])
    :bindings {:on-change {:option :command :arg :number}}}

   :ttk/scrollbar
   {:ctor (fn [spec props]
            (let [w (dsl/make-widget spec
                                     (fn [p] (dsl/widget-script "ttk::scrollbar" p
                                                                (dsl/tcl-options props (:options spec))))
                                     props)]
              (when-some [[first last] (seq (:position props))]
                (dsl/eval! (str (:path w) " set " (dsl/tcl-quote first) " "
                                (dsl/tcl-quote last))))
              w))
    :options (opt/options-for [:standard :classed :themed] [:orient])
    :bindings {:on-scroll {:option :command}}
    :patchable #{:position}
    :apply (fn [spec w prev props]
             (when (dsl/patchable? spec prev props)
               (when (not= (:position prev) (:position props))
                 (when-some [[first last] (seq (:position props))]
                   (dsl/eval! (str (:path w) " set " (dsl/tcl-quote first) " "
                                   (dsl/tcl-quote last)))))
               (dsl/apply-live! spec w prev props)))}

   :ttk/sizegrip
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script "ttk::sizegrip" p
                                                        (dsl/tcl-options props (:options spec))))
                             props))
    :options (opt/options-for [:standard :classed :themed])}

   :ttk/toggleswitch
   (dsl/checkable-spec "ttk::toggleswitch" :check
                       {:options (opt/options-for [:standard :classed :themed]
                                                  [:width :size :text :underline])
                        :fixed {:text ""}
                        :bindings checkbutton-bindings})

   :ttk/menubutton
   {:ctor (fn [spec {:keys [items] :as props}]
            (let [w (dsl/make-widget spec
                                     (fn [p] (dsl/widget-script "ttk::menubutton" p
                                                                (dsl/tcl-options props (:options spec))))
                                     props)
                  menu (dsl/alloc-path! w)]
              (dsl/eval! (str "menu " menu " -tearoff 0"))
              (dsl/eval! (str (:path w) " configure -menu " menu))
              (swap! (:styles w) assoc :menu menu)
              (dsl/set-menu-items! w menu items)
              w))
    :options (opt/options-for [:standard :classed :themed :padded]
                              [:width :text :underline :justify :compound :state :direction])
    :bindings {:on-click {:event "<Button-1>"}}
    :patchable #{:items}
    :primary :text
    :apply (fn [spec w prev props]
             (when (dsl/patchable? spec prev props)
               (when (not= (:items prev) (:items props))
                 (dsl/set-menu-items! w (get @(:styles w) :menu) (:items props)))
               (dsl/apply-live! spec w prev props)))}

   :ttk/notebook
   {:ctor (fn [spec props]
            (let [w (dsl/make-widget spec
                                     (fn [p] (dsl/widget-script "ttk::notebook" p
                                                                (dsl/tcl-options props (:options spec))))
                                     props)]
              (swap! (:styles w) assoc :attach (notebook-attach w))
              w))
    :options (opt/options-for [:standard :classed :themed :geometry :padded])
    :bindings {:on-tab-changed {:event "<<NotebookTabChanged>>" :value :tab-index}}
    :patchable #{:selected}
    :lens (dsl/child-lens)
    :apply (fn [spec w prev props]
             (when (dsl/patchable? spec prev props)
               (when (not= (:selected prev) (:selected props))
                 (dsl/eval! (str (:path w) " select " (:selected props))))
               (dsl/apply-live! spec w prev props)))}

   :ttk/panedwindow
   {:ctor (fn [spec props]
            (let [w (dsl/make-widget spec
                                     (fn [p] (dsl/widget-script "ttk::panedwindow" p
                                                                (dsl/tcl-options props (:options spec))))
                                     props)]
              (swap! (:styles w) assoc :attach (paned-attach w))
              w))
    :options (opt/options-for [:standard :classed :themed] [:width :height :orient])
    :lens (dsl/child-lens)}

   :ttk/progressbar
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script
                                      "ttk::progressbar" p
                                      (merge {:maximum 100}
                                             (dsl/tcl-options props (:options spec)))))
                             props))
    :options (opt/options-for [:standard :classed :themed :progress :fonted]
                              [:fg :anchor :justify :text :wraplength])}

   :ttk/separator
   {:ctor (fn [spec props]
            (dsl/make-widget spec
                             (fn [p] (dsl/widget-script "ttk::separator" p
                                                        (dsl/tcl-options props (:options spec))))
                             props))
    :options (opt/options-for [:standard :classed :themed] [:orient])}

   :ttk/treeview
   {:ctor (fn [spec props]
            (let [cols (columns props)
                  w (dsl/make-widget spec
                                     (fn [p] (dsl/widget-script
                                              "ttk::treeview" p
                                              (merge {:show :headings}
                                                     (dsl/tcl-options props (:options spec))
                                                     (when (seq cols)
                                                       {:columns (str/join " " (map :id cols))}))))
                                     props)]
              (doseq [{:keys [id heading width anchor stretch]} cols]
                (dsl/eval! (str (:path w) " heading " id " -text " (dsl/tcl-quote heading)))
                (when (or width anchor (some? stretch))
                  (dsl/eval! (str (:path w) " column " id
                                  (when width (str " -width " width))
                                  (when anchor (str " -anchor " (dsl/tcl-word anchor)))
                                  (when (some? stretch)
                                    (str " -stretch " (dsl/tcl-word stretch)))))))
              (tree-rows! w (:rows props))
              (tree-selection! w (:selected props))
              (when (:scrollbar props)
                (dsl/attach-scrollbar! w (dsl/parent-widget)))
              w))
    :options (opt/options-for [:standard :classed :themed :padded]
                              [:height :selectmode :show :rowheight :headingheight :striped])
    :bindings {:on-select {:event "<<TreeviewSelect>>" :value :ids}}
    :patchable #{:rows :selected}
    :apply (fn [spec w prev props]
             (when (dsl/patchable? spec prev props)
               (when (not= (:rows prev) (:rows props))
                 (tree-rows! w (:rows props)))
               (when (not= (:selected prev) (:selected props))
                 (tree-selection! w (:selected props)))
               (dsl/apply-live! spec w prev props)))}})
