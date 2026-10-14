(ns kmet.gui.ttk.style
  "The ttk style database as Clojure data: ttk::style configure/map and
   theme switching.

   A style is a name, not a class: a widget's style is its class name
   (`TEntry`) unless its `:style` prop names another, and a name inherits
   from itself without its first component (`Kmet.TEntry` falls back to
   `TEntry`). Tk resolves an option as widget option, state map, static
   value, inherited style, then theme default — and the database is per
   theme, so switch first, define after, and re-apply a set on every
   switch (apply! does). gui/README.md has the whole model."
  (:require [clojure.string :as str]
            [kmet.gui.tk.dsl :as dsl]))

(defn- tcl-name
  "X as the plain name Tcl uses (a string, or a keyword/symbol by name)."
  [x]
  (if (string? x) x (name x)))

(defn- style-word
  "V as one Tcl word of a style option: a sequence joins into a Tcl list."
  [v]
  (if (sequential? v) (str/join " " (map dsl/tcl-word v)) (dsl/tcl-word v)))

(defn- style!
  "Evaluate one `ttk::style SUBCOMMAND STYLE ARGS…` (nothing when ARGS is
   empty) and return nil."
  [subcommand style args]
  (when (seq args)
    (dsl/eval! (str "ttk::style " subcommand " " (dsl/tcl-quote (tcl-name style))
                    " " (str/join " " args))))
  nil)

(defn- state-table
  "STATES ({:state value}) as the Tcl dict of one style-map option: state
   names and values in state-name order, nil values absent."
  [states]
  (str/join " " (mapcat (fn [[state v]] [(name state) (style-word v)])
                        (->> states
                             (remove (comp nil? val))
                             (sort-by (comp name key))))))

(defn use-theme!
  "Switch the interpreter to ttk theme THEME (\"clam\", \"alt\", \"default\",
   \"classic\", a platform theme, or a pixmap theme package Tk can load).
   A switch makes the new theme's database current: define styles after it."
  [theme]
  (dsl/eval! (str "ttk::setTheme " (dsl/tcl-quote (tcl-name theme))))
  nil)

(defn define-style!
  "Configure STYLE's static option values. OPTIONS maps a Tk option name
   (`:fieldbackground`, `:padding`, `:relief`, …) to its value; a sequence
   is a Tcl list (`:padding [6 2]`)."
  [style options]
  (style! "configure" style
          (for [[option v] (sort-by (comp name key) options)
                :when (some? v)]
            (str "-" (name option) " " (dsl/tcl-quote (style-word v))))))

(defn map-style!
  "Set STYLE's per-state option values. MAPS maps a Tk option name to a
   state->value table:
   `{:background {:disabled \"#333\" :active \"#444\"}}`.
   An empty table clears that option's map."
  [style maps]
  (style! "map" style
          (for [[option states] (sort-by (comp name key) maps)]
            (str "-" (name option) " " (dsl/tcl-quote (state-table states))))))

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
    (map-style! style states)))
