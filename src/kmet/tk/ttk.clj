(ns kmet.tk.ttk
  "The themed-widget tag set — `:ttk/…` — of the Tk backend (kmet.tk.core):
   the specs of the ttk widgets, which kmet.tk.core completes (dsl/widget-specs)
   and merges into its closed tag table. The classic widgets are kmet.tk.core's
   own `:tk/…` set.

   Tag names follow Tcl's own namespace split: the ttk widgets live under
   Tcl's `ttk::` namespace, so they are `:ttk/frame`, `:ttk/label`, …;
   classic Tk widgets are `:tk/frame`, `:tk/text`, ….

   Every spec is dsl/widget-spec: the Tk command, its :options (the families
   in kmet.tk.options that fit the widget, with its own options), and
   whatever else the tag needs — :commands for the props Tk sets through
   subcommands (a treeview's rows), :attach for a container that places its
   children (a notebook's tabs). The families are additive and a widget's
   odd option is listed directly, so what a tag scripts is exactly the
   widget's option set minus the ones the catalogue leaves out."
  (:require [clojure.string :as str]
            [kmet.tk.dsl :as dsl]
            [kmet.tk.options :as opt]))

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
                      (str " -weight " (dsl/tcl-value weight)))))))

;; ─── The data widgets' own subcommands ────────────────────────────────────

(defn- columns
  "PROPS' :columns as [{:id :heading :width :anchor :stretch}]: a bare
   keyword is a column named by it, and its heading defaults to its name.
   The tree column is {:id \"#0\"} — the one a hierarchy indents in."
  [props]
  (mapv (fn [c]
          (let [spec (if (map? c) c {:id c})]
            {:id (name (:id spec))
             :heading (or (:heading spec) (name (:id spec)))
             :width (:width spec)
             :anchor (:anchor spec)
             :stretch (:stretch spec)}))
        (:columns props)))

(defn- data-column-ids
  "The data columns among COLUMNS: Tk's -columns, which must not name the
   tree column (#0)."
  [columns]
  (into [] (comp (remove #(= "#0" (:id %))) (map :id)) columns))

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

(def ^:private tree-tag-options
  "The ttk::treeview tag options a row tag's style map may set. A tag's font
   comes from the widget's font props, not a raw -font."
  #{:foreground :background :image})

(defn- tag-font
  "STYLE's font over the tree's own font, as the Tcl description a tag
   configures — or nil when the style names no font prop."
  [props style]
  (when-some [f (not-empty (select-keys style opt/font-props))]
    (dsl/font-spec (dsl/resolve-base-font (merge props f)) f)))

(defn- tag-options
  "The `tag configure` options for tree W's tag STYLE: the tag's own Tcl
   options, :image resolved to the image it names, plus the font its font
   props build over the tree's own."
  [w props style]
  (let [font (tag-font props style)]
    (cond-> (select-keys style tree-tag-options)
      (:image style) (update :image #(dsl/image-name w :image %))
      font (assoc :font font))))

(defn- tree-tags!
  "Configure tree W's row tags from PROPS' :tags — {tag {:foreground …}} —
   through Tk's `tag configure`, so the rows naming a tag in their own
   :tags take its style: colours, font, row image."
  [w props]
  (doseq [[tag style] (:tags props)]
    (when-some [unknown (seq (remove #(or (contains? tree-tag-options %)
                                          (contains? opt/font-props %))
                                     (keys style)))]
      (throw (ex-info (str "kmet.tk.core: unknown row tag options "
                           (pr-str (vec unknown)) " (tag " tag ") — a treeview"
                           " tag takes :foreground, :background, :image and the"
                           " font props")
                      {:type :kmet.tk.core/unknown-tag-option
                       :tag (:tag w) :row-tag tag :options (vec unknown)})))
    (when-let [opts (seq (tag-options w props style))]
      (dsl/eval! (str (:path w) " tag configure " (dsl/tcl-quote (name tag)) " "
                      (str/join " " (for [[k v] (sort-by (comp name key) opts)]
                                      (str "-" (name k) " "
                                           (dsl/tcl-value v)))))))))

(defn- insert-rows!
  "Insert ROWS under the Tcl item PARENT ({} for the roots) into tree W, ids
   defaulting to r1, r2, … at the top level and to <parent>.<n> below it,
   each row's own :children after the row — Tk nests by parent. Returns
   every id it inserted."
  [w parent prefix rows]
  (into []
        (mapcat (fn [[i row]]
                  (let [m (if (map? row) row {})
                        cells (if (map? row) (:values row) row)
                        id (str (or (:id m)
                                    (if (str/blank? prefix)
                                      (str "r" (inc i))
                                      (str prefix (inc i)))))]
                    (dsl/eval! (str (:path w) " insert " parent " end -id " (dsl/tcl-quote id)
                                    (when-let [t (:text m)]
                                      (str " -text " (dsl/tcl-value t)))
                                    (when (seq cells)
                                      (str " -values " (dsl/tcl-quote (dsl/tcl-list cells))))
                                    (when (seq (:tags m))
                                      (str " -tags " (dsl/tcl-quote (dsl/tcl-list (:tags m)))))
                                    (when (some? (:open m))
                                      (str " -open " (dsl/tcl-word (:open m))))
                                    (when-let [img (:image m)]
                                      (str " -image " (dsl/tcl-quote (dsl/image-name w :image img))))))
                    (into [id] (insert-rows! w (dsl/tcl-quote id) (str id ".") (:children m))))))
        (map-indexed vector (or rows []))))

(defn- selection-ids
  "PROPS' :selected as a vector of ids (none when nil)."
  [props]
  (let [s (:selected props)]
    (cond (sequential? s) (vec s)
          (some? s) [s]
          :else [])))

(defn- tree-selection!
  "Select PROPS' :selected rows in tree W (one id, several, or nil to
   clear)."
  [w props]
  (dsl/eval! (str (:path w) " selection remove [" (:path w) " selection]"))
  (when-let [ids (seq (selection-ids props))]
    (dsl/eval! (str (:path w) " selection set "
                    (str/join " " (map dsl/tcl-quote ids))))))

(defn- tree-rows!
  "Replace tree W's rows with PROPS' :rows: a vector of cells, or a map with
   :text (the tree column), :values, :id, :tags, :open, :image and
   :children, which nest. Tk's delete is recursive, so a rows change
   rebuilds the whole hierarchy and clears Tk's selection: the part of
   :selected the new rows still have is selected again — an id that is gone
   is dropped, not a render failure."
  [w props]
  (dsl/eval! (str (:path w) " delete [" (:path w) " children {}]"))
  (let [ids (insert-rows! w "{}" "" (:rows props))]
    (dsl/check-unique-ids! (:tag w) ids)
    (tree-selection! w (assoc props :selected (filterv (set ids) (selection-ids props))))))

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
                                               [:width :bg :fg :state :image :textvariable])
                     :fixed {:anchor :w :justify :left}
                     :bindings {:on-click {:event "<Button-1>"}}
                     :primary :text})

   :ttk/button
   (dsl/widget-spec "ttk::button"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:width :text :textvariable :underline :justify
                                                :compound :state :default :image])
                     :bindings {:on-click {:option :command}}})

   :ttk/checkbutton
   (dsl/widget-spec "ttk::checkbutton"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:width :text :textvariable :variable :onvalue
                                                :offvalue :underline :justify :compound
                                                :state :image])
                     :bindings dsl/check-bindings
                     :checkable :check})

   :ttk/radiobutton
   (dsl/widget-spec "ttk::radiobutton"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:width :text :textvariable :variable :underline
                                                :justify :compound :state :value :image])
                     :bindings dsl/radio-bindings
                     :checkable :radio})

   :ttk/entry
   (dsl/widget-spec "ttk::entry"
                    {:options (opt/options-for [:standard :classed :themed :editable
                                                :fonted :localized]
                                               [:width :bg :fg :exportselection :textvariable
                                                :justify :state])
                     :bindings dsl/text-field-bindings
                     :text-field true})

   :ttk/combobox
   (dsl/widget-spec "ttk::combobox"
                    {:options (opt/options-for [:standard :classed :themed :editable
                                                :fonted :localized]
                                               [:width :height :bg :fg :exportselection
                                                :textvariable :justify :state :values])
                     :bindings (assoc dsl/text-field-bindings
                                      :on-change {:event "<<ComboboxSelected>>"
                                                  :value :text})
                     :text-field true})

   :ttk/spinbox
   (dsl/widget-spec "ttk::spinbox"
                    {:options (opt/options-for [:standard :classed :themed :editable
                                                :fonted :localized]
                                               [:width :bg :fg :exportselection :textvariable
                                                :justify :state :values :format :from :to
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
                                               [:width :size :text :variable :onvalue :offvalue
                                                :underline])
                     :bindings dsl/check-bindings
                     :checkable :check})

   :ttk/menubutton
   (dsl/widget-spec "ttk::menubutton"
                    {:options (opt/options-for [:standard :classed :themed :padded]
                                               [:width :text :textvariable :underline :justify
                                                :compound :state :direction :image])
                     :bindings {:on-click {:event "<Button-1>"}}
                     :primary :text
                     :commands {:items dsl/set-menu-items!}})

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
                              (let [cols (data-column-ids (columns props))]
                                (merge {:show :headings}
                                       (when (seq cols)
                                         {:columns (str/join " " cols)}))))
                     :bindings {:on-select {:event "<<TreeviewSelect>>" :value :ids}}
                     :commands {:rows tree-rows!
                                :tags tree-tags!
                                :selected tree-selection!}
                     :after headings!})})
