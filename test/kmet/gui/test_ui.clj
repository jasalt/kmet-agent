(ns kmet.gui.test-ui
  "Headless tests for kmet.gui.ui — kmet's own Tk widgets — against the same
   recording adapter as kmet.gui.test-tk: the engine → UI spec → widget
   script path, no display needed."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.gui.tk :as tk]
            [kmet.gui.tk.dsl :as dsl]
            [kmet.gui.ui :as ui]
            [kmet.libs.hikap :as engine]))

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
(defn- scripts-like [rec re] (filterv #(re-find re %) (scripts rec)))

;; ─── the backend's tag table ─────────────────────────────────────────────

(t/deftest ui-tags-are-kept-out-of-the-tk-backend
  ;; kmet.gui.tk and kmet.gui.ttk hold only Tk widget classes: the kmet
  ;; composite is a :ui/… tag, added to the backend by kmet.gui.ui alone.
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
