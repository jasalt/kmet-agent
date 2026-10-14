(ns kmet.gui.ttk.style
  "The ttk style database: style names, option values, state maps and theme
   switching, as Clojure data.

   A ttk *style* is not a widget class and not a data structure of ours: it
   is a name in the interpreter's per-theme style database. Every ttk
   widget has a class (`ttk::entry` -> `TEntry`); a widget's style is its
   class name unless its `:style` prop names another, and a style name
   inherits from itself without the first component (`Kmet.TEntry` falls
   back to `TEntry` for layout and options). Under one name Tk keeps

     - static option values      (ttk::style configure)
     - per-state option values   (ttk::style map — one state->value table
                                  per option: disabled, active, focus, …)
     - a layout and element options, usually inherited

   and resolves each option as: the widget's own option, the style's map
   for the widget's current state, the style's static value, the inherited
   style's value, the theme default (`ttk::style lookup`).

   The database is per theme: a theme switch (`ttk::setTheme`) makes the
   new theme's values current and hides the values set under the old one.
   Switch first, define the styles after, and re-apply a set on every
   switch — which is what apply! does."
  (:require [clojure.string :as str]
            [kmet.gui.tk.dsl :as dsl]))

(defn- tcl-name
  "X as the plain name Tcl uses (a string, or a keyword/symbol by name)."
  [x]
  (if (string? x) x (name x)))

(defn- style-word
  "V as one Tcl word of a style option: a sequence joins into a Tcl list."
  [v]
  (if (sequential? v)
    (str/join " " (map dsl/tcl-word v))
    (dsl/tcl-word v)))

(defn- option-args
  "OPTS ({:option value}) as `-option value` words, in option-name order so
   a style script is stable."
  [opts]
  (for [[option v] (sort-by (comp name key) opts)
        :when (some? v)]
    (str "-" (name option) " " (dsl/tcl-quote (style-word v)))))

(defn use-theme!
  "Switch the interpreter to ttk theme THEME (\"clam\", \"alt\", \"default\",
   \"classic\", a platform theme, or a pixmap theme package Tk can load).
   A switch makes the new theme's values current: define styles after it."
  [theme]
  (dsl/eval! (str "ttk::setTheme " (dsl/tcl-quote (tcl-name theme))))
  nil)

(defn define-style!
  "Configure STYLE's static option values. OPTIONS maps a Tk option name
   (`:fieldbackground`, `:padding`, `:relief`, …) to its value; a sequence
   is a Tcl list (`:padding [6 2]`)."
  [style options]
  (when-let [args (seq (option-args options))]
    (dsl/eval! (str "ttk::style configure " (dsl/tcl-quote (tcl-name style))
                    " " (str/join " " args))))
  nil)

(defn map-style!
  "Set STYLE's per-state option values. MAPS maps a Tk option name to a
   state->value table:
   `{:background {:disabled \"#333\" :active \"#444\"}}`.
   An empty table clears that option's map."
  [style maps]
  (let [args (for [[option states] (sort-by (comp name key) maps)]
               (str "-" (name option) " "
                    (dsl/tcl-quote
                     (str/join " " (mapcat (fn [[state v]]
                                             [(name state) (style-word v)])
                                           (sort-by (comp name key)
                                                    (remove (comp nil? val) states)))))))]
    (when (seq args)
      (dsl/eval! (str "ttk::style map " (dsl/tcl-quote (tcl-name style))
                      " " (str/join " " args)))))
  nil)

(defn apply!
  "Apply a whole style set in one call:

     {:theme  \"clam\"
      :styles {\"TEntry\" {:fieldbackground \"#1f2735\"}}
      :maps   {\"Kmet.TEntry\" {:fieldbackground {:disabled \"#161a20\"}}}}

   The theme is switched first (a switch makes the new theme's database
   current), then the static styles, then the state maps."
  [{:keys [theme styles maps]}]
  (when theme
    (use-theme! theme))
  (doseq [[style options] styles]
    (define-style! style options))
  (doseq [[style states] maps]
    (map-style! style states))
  nil)
