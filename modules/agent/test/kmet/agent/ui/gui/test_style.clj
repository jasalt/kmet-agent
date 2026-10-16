(ns kmet.agent.ui.gui.test-style
  "Tests for kmet.agent.ui.gui.style: the dark set's data, the scripts it
   records against the recording adapter, and its round trip through a real
   Tk under a display (skipped where there is none)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.agent.ui.gui.style :as gui-style]
            [kmet.agent.ui.gui.transcript :as transcript]
            [kmet.tk.test-support :refer [live-tk scripts with-fake]]
            [kmet.tk.core :as tk]
            [kmet.tk.dsl :as dsl]
            [kmet.tui.theme :as theme]))

;; ─── the set's data ──────────────────────────────────────────────────────

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
             (get-in s [:maps "TButton" :background])))
    (t/is (= "#010101"
             (get-in (gui-style/style-set theme/dark-theme
                                          (assoc palette :window "#010101"))
                     [:styles "TFrame" :background]))
          "an alternate palette drives the surfaces")))

;; ─── the scripts ─────────────────────────────────────────────────────────

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
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))
