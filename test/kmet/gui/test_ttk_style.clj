(ns kmet.gui.test-ttk-style
  "Tests for kmet.gui.ttk.style: the ttk::style scripts against a recording
   adapter (headless), and one round trip through a real Tk under a display
   (skipped where there is none). The app palette set has its own namespace,
   kmet.app.ui.gui.test-style."
  (:require [clojure.test :as t]
            [kmet.gui.test-support :refer [live-tk scripts with-fake]]
            [kmet.gui.tk :as tk]
            [kmet.gui.ttk.style :as style]))

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

;; ─── the real Tk ─────────────────────────────────────────────────────────

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
