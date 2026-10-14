(ns kmet.app.ui.gui.style
  "The GUI's ttk style set: the TUI theme's colors and the GUI surfaces
   compiled to kmet.gui.ttk.style data, so a dark palette stops following
   the system theme (light-grey entries, scrollbars, notebook, treeview).

   Apply it once the Tk adapter is installed and the theme is known:

     (tcl/install!)
     (style/apply-theme! (theme/get-current-theme))
     (ui/run! …)

   and re-apply after a theme switch (a switch makes the other theme's
   database current). The surfaces are kmet.app.ui.gui.transcript's palette
   until the GUI grows a theme module of its own."
  (:require [kmet.app.ui.gui.transcript :as transcript]
            [kmet.gui.ttk.style :as ttk-style]))

(defn- colors
  "The style colors THEME and PALETTE resolve to: surfaces from the
   palette, text colors from the theme's tokens (nil when absent)."
  [theme palette]
  (let [hex (fn [kind token] (transcript/token-hex theme kind token))]
    {:window   (:window palette)
     :canvas   (:canvas palette)
     :field    (:card palette)
     :active   (:card-user palette)
     :pressed  (:card-tool palette)
     :disabled (:canvas palette)
     :border   (:border palette)
     :text     (hex :fg :text)
     :muted    (hex :fg :muted)
     :dim      (hex :fg :dim)
     :accent   (hex :fg :accent)
     :select   (hex :bg :selected-bg)
     :track    (hex :fg :scrollbar-track)
     :thumb    (hex :fg :scrollbar-thumb)}))

(defn- field-style
  "The entry options over COLORS."
  [{:keys [field text select border]}]
  {:fieldbackground field :foreground text
   :insertcolor text :selectbackground select :selectforeground text
   :bordercolor border :lightcolor field :darkcolor field})

(defn- arrow-field-style
  "The combobox/spinbox options over COLORS: a field plus their arrow
   button."
  [colors]
  (assoc (field-style colors)
         :background (:field colors) :arrowcolor (:muted colors)))

(defn- checkable-style
  "The checkbutton/radiobutton options over COLORS."
  [{:keys [window field text accent border]}]
  {:background window :foreground text
   :indicatorbackground field :indicatorforeground accent
   :upperbordercolor border :lowerbordercolor window})

(defn- field-states
  "The entry's disabled/readonly state values over COLORS."
  [{:keys [disabled dim]}]
  {:fieldbackground {:disabled disabled :readonly disabled}
   :foreground {:disabled dim}})

(defn- checkable-states
  "The checkbutton/radiobutton state values over COLORS."
  [{:keys [disabled dim]}]
  {:indicatorbackground {:disabled disabled}
   :foreground {:disabled dim}})

(defn style-set
  "The ttk style set for THEME over PALETTE's surfaces (default
   transcript/palette): the clam theme plus the style and map values a dark
   palette needs. A nil color stays absent from the scripts."
  ([theme] (style-set theme transcript/palette))
  ([theme palette]
   (let [c (colors theme palette)
         {:keys [window field active pressed disabled border text muted dim
                 accent select track thumb]} c
         entry-states (field-states c)
         arrow-states (assoc (field-states c) :arrowcolor {:disabled dim})]
     {:theme "clam"
      :styles
      {"TFrame"           {:background window}
       "TLabelframe"      {:background window :foreground muted :bordercolor border}
       "TLabel"           {:background window :foreground text}
       "TButton"          {:background field :foreground text
                           :bordercolor border :lightcolor field :darkcolor window
                           :padding [6 4]}
       "TEntry"           (field-style c)
       "TCombobox"        (arrow-field-style c)
       "TSpinbox"         (arrow-field-style c)
       "TCheckbutton"     (checkable-style c)
       "TRadiobutton"     (checkable-style c)
       "TNotebook"        {:background window :bordercolor border
                           :lightcolor window :darkcolor window}
       "TNotebook.Tab"    {:background window :foreground muted :padding [10 4]}
       "Treeview"         {:background (:canvas c) :fieldbackground (:canvas c)
                           :foreground text :bordercolor border
                           :lightcolor (:canvas c) :darkcolor (:canvas c)
                           :selectbackground select :selectforeground text}
       "Treeview.Heading" {:background field :foreground muted :relief :flat}
       "TScrollbar"       {:background thumb :troughcolor track :bordercolor border
                           :arrowcolor muted :lightcolor field :darkcolor window}
       "TScale"           {:background window :troughcolor track :bordercolor border
                           :lightcolor field :darkcolor window}
       "TProgressbar"     {:background accent :troughcolor track :bordercolor border
                           :lightcolor field :darkcolor window}
       "TPanedwindow"     {:background window}
       "TSeparator"       {:background border}
       "TSizegrip"        {:background window}
       "TMenubutton"      {:background field :foreground text :arrowcolor muted
                           :bordercolor border :lightcolor field :darkcolor window}}
      :maps
      {"TButton"       {:background {:active active :pressed pressed
                                     :disabled disabled}
                        :foreground {:disabled dim}}
       "TEntry"        entry-states
       "TCombobox"     arrow-states
       "TSpinbox"      arrow-states
       "TCheckbutton"  (checkable-states c)
       "TRadiobutton"  (checkable-states c)
       "TNotebook.Tab" {:background {:selected field :active active}
                        :foreground {:selected text}}
       "Treeview"      {:background {:selected select}
                        :foreground {:selected text :disabled dim}}
       "TScrollbar"    {:arrowcolor {:disabled dim}}}})))

(defn apply-theme!
  "Apply (style-set THEME PALETTE) through kmet.gui.ttk.style/apply! — once
   the adapter is installed, and again after a theme switch."
  ([theme] (apply-theme! theme transcript/palette))
  ([theme palette] (ttk-style/apply! (style-set theme palette))))
