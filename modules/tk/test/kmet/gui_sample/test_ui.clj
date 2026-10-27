(ns kmet.gui-sample.test-ui
  "Tests for kmet.gui-sample.ui — kmet's own Tk widgets — against the
   recording adapter (headless), plus one live Tk mount of :ui/transcript
   under a display (skipped where there is none)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.gui-sample.ui :as ui]
            [kmet.tk.test-support :refer [find-class-src live-tk scripts scripts-like with-fake]]
            [kmet.tk.core :as tk]
            [kmet.hikap.core :as engine]))

;; ─── the backend's tag table ─────────────────────────────────────────────

(t/deftest ui-tags-are-kept-out-of-the-tk-backend
  ;; kmet.tk.core and kmet.tk.ttk hold only Tk widget classes: the kmet
  ;; composite is a :ui/… tag, added to the backend by kmet.gui-sample.ui alone.
  (t/is (contains? (:tags ui/backend) :ui/transcript))
  (t/is (contains? (:tags ui/backend) :tk/text) "the Tk widget classes are all there")
  (t/is (not (contains? (:tags tk/tk-backend) :ui/transcript))
        "the Tk backend does not carry kmet's widget")
  (t/is (thrown? Exception (engine/compile-element tk/tk-backend [:ui/transcript {}]))
        "mounting a :ui tag on the plain Tk backend fails loudly"))

;; ─── :ui/transcript ──────────────────────────────────────────────────────

(t/deftest transcript-embeds-each-child-as-a-window
  (with-fake [rec nil]
    (let [h (ui/mount! [:ttk/frame {}
                        [:ui/transcript {:see-end true :read-only true}
                         [:tk/text {:text "a"}]
                         [:tk/text {:text "b"}]]]
                       {:title "t"})
          ss (scripts rec)
          text (second (re-find #"^text (\.w[\w.]+)" (first (scripts-like rec #"^text \."))))
          sb (second (re-find #"^ttk::scrollbar (\.w[\w.]+) -orient vertical -command \""
                              (first (scripts-like rec #"^ttk::scrollbar "))))]
      (t/is (some #(and (str/includes? % "-wrap none")
                        (str/includes? % "-height 1")
                        (str/includes? % "-width 1"))
                  ss)
            "the transcript text widget is one line deep and unwrapped")
      (t/is (some #(str/includes? % (str "ttk::scrollbar " sb " -orient vertical -command \""
                                         text " yview\""))
                  ss))
      (t/is (some #(str/includes? % (str text " configure -yscrollcommand \"" sb " set\"")) ss))
      (t/is (some #(str/includes? % (str text " window create end -window ")) ss)
            "every child is embedded as a window on its own line")
      (t/is (some #(str/includes? % "insert end") ss)
            "the embedded window is followed by a newline")
      (t/is (some #(str/includes? % "see end") ss)
            "the transcript scrolls to the end once mapped")
      (t/is (some #(str/includes? % "configure -state disabled") ss)
            ":read-only disables the host after the first map")
      (t/is (some true? (map #(= (str "pack " sb " -side right -fill y") %) ss)))
      (t/is (some true? (map #(= (str "pack " text " -side left -fill both -expand 1") %) ss)))
      (tk/run-loop! h)
      (t/is (some #(str/includes? % (str "destroy " sb)) (scripts rec))
            "dispose destroys the sibling scrollbar"))))

;; ─── the live Tk ─────────────────────────────────────────────────────────

(t/deftest transcript-embeds-on-a-live-tk
  ;; The headless test checks the scripts; here the real Tk hosts the card
  ;; as an embedded window and :see-end/:read-only take effect on map.
  (if-let [a (live-tk)]
    (try
      (binding [tk/*adapter* a]
        (let [h (ui/mount! [:ttk/frame {}
                            [:ui/transcript {:see-end true :read-only true}
                             [:ttk/label {:text "card"}]]]
                           {:title "t"})
              e (:eval a)]
          (e find-class-src)
          (let [text (e "kmetfind . Text")
                sb (e "kmetfind . TScrollbar")]
            (t/is (seq text) "the transcript text host is in the window")
            (t/is (seq sb) "and its scrollbar")
            (t/is (seq (e (str text " window names")))
                  "the card is embedded as a text window")
            (e "update")
            (t/is (= "disabled" (e (str text " cget -state")))
                  ":read-only disables the host once mapped"))
          (engine/dispose-component (:root h))))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))
