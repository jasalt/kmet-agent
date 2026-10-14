(ns kmet.gui.test-ttk-style
  "Tests for the GUI's ttk style data: the kmet.gui.ttk.style scripts
   against a recording adapter (headless), and the app palette set
   (kmet.app.ui.gui.style) both recorded and round-tripped through a real
   Tk under a display (skipped where there is none)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.ui.gui.style :as gui-style]
            [kmet.app.ui.gui.transcript :as transcript]
            [kmet.gui.tk :as tk]
            [kmet.gui.tk.dsl :as dsl]
            [kmet.gui.ttk.style :as style]
            [kmet.tui.theme :as theme]))

(defn- with-fake* [f]
  (let [scripts (atom [])
        adapter {:eval (fn [s] (swap! scripts conj s) "")
                 :command! (fn [_n _h] nil)
                 :run-loop (fn [_pump] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (f {:scripts scripts}))))

(defmacro ^{:clj-kondo/lint-as 'clojure.core/let} with-fake
  [binding & body]
  (let [rec (first binding)]
    `(with-fake* (fn [~rec] ~@body))))

(defn- scripts [rec] @(:scripts rec))

;; ─── the scripts ─────────────────────────────────────────────────────────

(t/deftest use-theme-goes-through-ttk-set-theme
  (with-fake [rec nil]
    (style/use-theme! "clam")
    (t/is (= ["ttk::setTheme clam"] (scripts rec)))
    (style/use-theme! :alt)
    (t/is (= "ttk::setTheme alt" (last (scripts rec)))
          "a keyword theme names itself")))

(t/deftest define-style-scripts-ttk-style-configure
  (with-fake [rec nil]
    (style/define-style! "Kmet.TEntry" {:fieldbackground "#1f2735"
                                        :foreground "#d7dbe0"
                                        :padding [6 2]})
    (t/is (= ["ttk::style configure Kmet.TEntry -fieldbackground \\#1f2735 -foreground \\#d7dbe0 -padding 6\\ 2"]
             (scripts rec))
          "option-name order is stable; a sequence is one Tcl list word")
    (style/define-style! :TFrame {:background "#10141b"})
    (t/is (= "ttk::style configure TFrame -background \\#10141b" (last (scripts rec)))
          "a keyword style names itself")
    (style/define-style! "TFrame" {:relief nil :padding [6 12]})
    (t/is (= "ttk::style configure TFrame -padding 6\\ 12" (last (scripts rec)))
          "a nil option is absent")
    (let [n (count (scripts rec))]
      (style/define-style! "TFrame" {})
      (t/is (= n (count (scripts rec))) "an empty option map scripts nothing"))))

(t/deftest map-style-scripts-ttk-style-map
  (with-fake [rec nil]
    (style/map-style! "Kmet.TEntry" {:fieldbackground {:disabled "#161a20"
                                                       :focus "#20242c"}})
    (t/is (= ["ttk::style map Kmet.TEntry -fieldbackground disabled\\ \\#161a20\\ focus\\ \\#20242c"]
             (scripts rec))
          "one state->value table per option, states in name order")
    (style/map-style! "Kmet.TEntry" {:background {}})
    (t/is (= "ttk::style map Kmet.TEntry -background {}" (last (scripts rec)))
          "an empty table clears the option's map")
    (let [n (count (scripts rec))]
      (style/map-style! "Kmet.TEntry" {})
      (t/is (= n (count (scripts rec))) "an empty map set scripts nothing"))))

(t/deftest apply-switches-the-theme-then-defines
  (with-fake [rec nil]
    (style/apply! (array-map
                   :theme "clam"
                   :styles (array-map "TEntry" {:fieldbackground "#1f2735"})
                   :maps (array-map "Kmet.TEntry" {:background {:disabled "#161a20"}})))
    (t/is (= ["ttk::setTheme clam"
              "ttk::style configure TEntry -fieldbackground \\#1f2735"
              "ttk::style map Kmet.TEntry -background disabled\\ \\#161a20"]
             (scripts rec))
          "theme first (a switch makes the new theme current), then styles, then maps"))
  (with-fake [rec nil]
    (style/apply! {:styles {"TEntry" {:fieldbackground "#1f2735"}}})
    (t/is (= ["ttk::style configure TEntry -fieldbackground \\#1f2735"] (scripts rec))
          "no theme, no switch")))

;; ─── the app palette set ─────────────────────────────────────────────────

(t/deftest the-dark-set-takes-its-colors-from-the-theme-and-palette
  (let [palette transcript/palette
        s (gui-style/style-set theme/dark-theme palette)]
    (t/is (= "clam" (:theme s)) "clam is the themable built-in")
    (t/is (= (:window palette) (get-in s [:styles "TFrame" :background])))
    (t/is (= (:card palette) (get-in s [:styles "TEntry" :fieldbackground])))
    (t/is (= (:canvas palette) (get-in s [:styles "Treeview" :background])))
    (t/is (= (transcript/token-hex theme/dark-theme :fg :text)
             (get-in s [:styles "TLabel" :foreground]))
          "text colors come from the theme's tokens")
    (t/is (= (transcript/token-hex theme/dark-theme :bg :selected-bg)
             (get-in s [:styles "Treeview" :selectbackground])))
    (t/is (= {:active (:card-user palette) :pressed (:card-tool palette)
              :disabled (:canvas palette)}
             (get-in s [:maps "TButton" :background])))))

(t/deftest apply-theme-scripts-the-palette-set
  (with-fake [rec nil]
    (gui-style/apply-theme! theme/dark-theme)
    (let [ss (scripts rec)]
      (t/is (= "ttk::setTheme clam" (first ss)))
      (t/is (some #(str/includes? % (str "ttk::style configure TFrame -background "
                                         (dsl/tcl-quote (:window transcript/palette))))
                  ss))
      (t/is (some #(and (str/starts-with? % "ttk::style configure TEntry ")
                        (str/includes? % (str "-fieldbackground "
                                              (dsl/tcl-quote (:card transcript/palette)))))
                  ss))
      (t/is (some #(str/includes? % "ttk::style map TButton -background active") ss))
      (t/is (some #(str/includes? % "ttk::style map Treeview -background selected") ss)))))

;; ─── the real Tk ─────────────────────────────────────────────────────────

(defn- live-tk
  "The real adapter with Tk initialized (a display), or nil: no libtcl, no
   libtk, or no display (a headless machine)."
  []
  (try
    (require 'kmet.gui.tcl)
    (let [a ((deref (ns-resolve 'kmet.gui.tcl 'adapter)))]
      ((:prepare! a))
      a)
    (catch Throwable _ nil)))

(t/deftest style-database-round-trips-on-a-live-tk
  (if-let [a (live-tk)]
    (try
      (binding [tk/*adapter* a]
        (let [e (:eval a)]
          (style/use-theme! "clam")
          (style/define-style! "Kmet.TEntry" {:fieldbackground "#1f2735"
                                              :padding [6 2]})
          (t/is (= "clam" (e "ttk::style theme use")))
          (t/is (= "#1f2735" (e "ttk::style lookup Kmet.TEntry -fieldbackground")))
          (t/is (= "6 2" (e "ttk::style lookup Kmet.TEntry -padding")))
          (style/map-style! "Kmet.TEntry" {:fieldbackground {:disabled "#161a20"}})
          (t/is (= "#161a20" (e "ttk::style lookup Kmet.TEntry -fieldbackground disabled")))
          (t/is (= "#1f2735" (e "ttk::style lookup Kmet.TEntry -fieldbackground"))
                "the static value still answers outside the mapped state")
          (e "ttk::entry .kmetstyle -style Kmet.TEntry")
          (t/is (= "Kmet.TEntry" (e ".kmetstyle cget -style")))
          (style/apply! {:theme "default"
                         :styles {"TFrame" {:background "#10141b"}}})
          (t/is (= "default" (e "ttk::style theme use")))
          (t/is (= "#10141b" (e "ttk::style lookup TFrame -background")))))
      (finally
        ((deref (ns-resolve 'kmet.gui.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(t/deftest the-dark-set-round-trips-on-a-live-tk
  (if-let [a (live-tk)]
    (try
      (binding [tk/*adapter* a]
        (gui-style/apply-theme! theme/dark-theme)
        (let [e (:eval a)]
          (t/is (= "clam" (e "ttk::style theme use")))
          (t/is (= (:card transcript/palette)
                   (e "ttk::style lookup TEntry -fieldbackground")))
          (t/is (= (:card-user transcript/palette)
                   (e "ttk::style lookup TButton -background active"))
                "the state map is a shade of the palette")
          (t/is (= (:window transcript/palette)
                   (e "ttk::style lookup TFrame -background")))))
      (finally
        ((deref (ns-resolve 'kmet.gui.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))
