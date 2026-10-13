(ns kmet.gui.test-tk
  "Headless tests for the Tk backend (\"hiccup-tk\").

   A recording adapter stands in for a Tcl/Tk interpreter: every eval is
   captured as a script string and command registrations go to an atom, so
   the full engine → backend → script path is exercised without a display.
   The real jolt.ffi adapter (kmet.gui.tcl) is smoke-tested separately on a
   machine with Tcl/Tk."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.gui.tk :as tk]
            [kmet.libs.hikap :as engine]
            [kmet.libs.hikap.component :as component]
            [kmet.libs.reakt :as r]))

(defn- with-fake*
  [f]
  (let [scripts (atom [])
        commands (atom {})
        pumps (atom [])
        adapter {:eval (fn [s] (swap! scripts conj s) "")
                 :command! (fn [n h] (swap! commands assoc n h) nil)
                 :run-loop (fn [pump] (swap! pumps conj pump) nil)}]
    (binding [tk/*adapter* adapter]
      (f {:scripts scripts :commands commands :pumps pumps}))))

(defmacro ^{:clj-kondo/lint-as 'clojure.core/let} with-fake
  [binding & body]
  (let [rec (first binding)]
    `(with-fake* (fn [~rec] ~@body))))

(defn- mounted
  "Mount TREE under a fresh window through the engine, returning the window
   record and the root component."
  [tree]
  (let [window (engine/compile-element tk/tk-backend [:window {:title "t"}])
        root (engine/root tk/tk-backend tree)]
    (binding [engine/*construct-context* {:parent window}]
      (engine/render-component root nil))
    {:window window :root root}))

(defn- rerender!
  [{:keys [window root]}]
  (binding [engine/*construct-context* {:parent window}]
    (engine/render-component root nil)))

(defn- scripts [rec] @(:scripts rec))
(defn- scripts-like [rec re] (filterv #(re-find re %) (scripts rec)))

;; ─── Tcl quoting ──────────────────────────────────────────────────────────

(t/deftest tcl-quote-escapes-every-substitution
  (t/is (= "plain" (tk/tcl-quote "plain")))
  (t/is (= "a\\ b" (tk/tcl-quote "a b")))
  (t/is (= "\\{x\\}" (tk/tcl-quote "{x}")))
  (t/is (= "\\[x\\]" (tk/tcl-quote "[x]")))
  (t/is (= "\\$x" (tk/tcl-quote "$x")))
  (t/is (= "\\\"q\\\"" (tk/tcl-quote "\"q\"")))
  (t/is (= "a\\nb" (tk/tcl-quote "a\nb")))
  (t/is (= "c:\\\\path" (tk/tcl-quote "c:\\path"))))

;; ─── construction ─────────────────────────────────────────────────────────

(t/deftest run-builds-a-widget-tree
  (with-fake [rec nil]
    (tk/run! (fn [_] [:vbox {:padding 4}
                      [:label "hello"]
                      [:button {:label "Go" :on-click (constantly nil)}]])
             {:title "kmet" :width 320 :height 200})
    (let [ss (scripts rec)]
      (t/is (some #(= "wm title . kmet" %) ss))
      (t/is (some #(re-find #"ttk::frame \.w\d+$" %) ss))
      (t/is (some #(re-find #"ttk::label \.w\d+\.w\d+ -text hello" %) ss))
      (t/is (some #(re-find #"ttk::button" %) ss))
      (t/is (some #(re-find #"pack .* -side top" %) ss))
      (t/is (some #(re-find #"-command \{kmet::cb \d+\}" %) ss))
      (t/is (= 1 (count @(:commands rec))) "kmet::cb trampoline registered"))))

(t/deftest bare-strings-become-labels
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:vbox {} "hi"])]
      (t/is (some #(re-find #"ttk::label .* -text hi" %) (scripts rec)))
      (engine/dispose-component root))))

;; ─── props, patching and rebuilds ─────────────────────────────────────────

(t/deftest entry-text-patches-in-place
  (with-fake [rec nil]
    (let [a (atom "one")
          m (mounted (fn [_] [:vbox {} [:entry {:text @a}]]))]
      (t/is (some #(re-find #"insert 0 one" %) (scripts rec)))
      (let [before (count (scripts-like rec #"^destroy "))]
        (reset! a "two")
        (rerender! m)
        (t/is (= before (count (scripts-like rec #"^destroy ")))
              "an apply-path leaf is patched, never rebuilt")
        (t/is (some #(re-find #"insert 0 two" %) (scripts rec))))
      (engine/dispose-component (:root m)))))

(t/deftest label-patches-text-and-declines-what-it-cannot-express
  (with-fake [rec nil]
    (let [text (atom "one")
          bold (atom false)
          m (mounted (fn [_] [:vbox {} [:label {:text @text :bold @bold}]]))]
      ;; a text change is a Tcl option → patch in place, keep the instance
      (reset! text "two")
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "a patchable prop change keeps the widget")
      (t/is (some #(re-find #"-text two" %) (scripts rec)))
      ;; a font is baked into the widget at construction → decline, rebuild
      (reset! bold true)
      (rerender! m)
      (t/is (pos? (count (scripts-like rec #"^destroy ")))
            "a font change declines and rebuilds the label")
      (t/is (some #(re-find #"-font" %) (scripts-like rec #"ttk::label ")))
      (engine/dispose-component (:root m)))))

(t/deftest label-rebinds-a-changed-click-callback
  (with-fake [rec nil]
    (let [a (atom (fn [] :first))
          m (mounted (fn [_] [:vbox {} [:label {:text "x" :on-click @a}]]))]
      (t/is (some #(re-find #"<Button-1>" %) (scripts rec)))
      (let [before (count (scripts-like rec #"^destroy "))]
        (reset! a (fn [] :second))
        (rerender! m)
        (t/is (= before (count (scripts-like rec #"^destroy ")))
              "a changed callback rebinds instead of rebuilding")
        (let [ids (mapv #(second (re-find #"kmet::cb (\d+)" %))
                        (scripts-like rec #"<Button-1>"))
              [id1 id2] (mapv parse-long ids)]
          (t/is (= 2 (count ids)))
          (t/is (not= id1 id2) "the new binding uses a fresh command")
          (t/is (= "" (tk/dispatch-callback! ["kmet::cb" (str id1)]))
                "the replaced callback is released, not left dispatchable")
          (t/is (= ":second" (tk/dispatch-callback! ["kmet::cb" (str id2)])))))
      (engine/dispose-component (:root m)))))

(t/deftest keyed-children-reuse-identity
  (with-fake [rec nil]
    (let [a (atom ["a" "b" "c"])
          m (mounted (fn [_]
                       [:vbox {}
                        (map-indexed (fn [_i s]
                                       ^{:key s}
                                       [:label {:text (str s "!")}])
                                     @a)]))]
      (let [labels (scripts-like rec #"ttk::label")
            paths (mapv #(second (re-find #"(\.w[\w.]+)" %)) labels)]
        (t/is (= 3 (count labels)))
        ;; drop "b": its widget is destroyed, "a" and "c" keep their paths
        (reset! a ["a" "c"])
        (rerender! m)
        (let [destroyed (set (mapv #(second (re-find #"destroy (\.w[\w.]+)" %))
                                   (scripts-like rec #"^destroy ")))]
          (t/is (= 1 (count destroyed)))
          (t/is (not (contains? destroyed (nth paths 0))) "first child reused")
          (t/is (not (contains? destroyed (nth paths 2))) "third child reused")
          (t/is (contains? destroyed (nth paths 1)) "removed child destroyed")))
      (engine/dispose-component (:root m)))))

;; ─── callbacks ────────────────────────────────────────────────────────────

(t/deftest click-dispatches-through-the-trampoline
  (with-fake [rec nil]
    (let [clicked (atom nil)]
      (mounted [:vbox {}
                [:button {:label "Go" :on-click #(reset! clicked :yes)}]])
      (let [script (first (scripts-like rec #"-command \{kmet::cb \d+\}"))
            id (second (re-find #"kmet::cb (\d+)" script))]
        (t/is (some? id))
        (t/is (= ":yes" (tk/dispatch-callback! ["kmet::cb" id])))
        (t/is (= :yes @clicked))
        (t/is (string? (tk/dispatch-callback! ["kmet::cb" "999999"]))
              "unknown callback answers empty, not an error")))))

;; ─── dispose ──────────────────────────────────────────────────────────────

(t/deftest dispose-destroys-the-widget-and-releases-callbacks
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:vbox {}
                                   [:label "bye"]
                                   [:button {:label "x" :on-click (constantly nil)}]])]
      (engine/dispose-component root)
      (t/is (>= (count (scripts-like rec #"^destroy \.w")) 2)
            "container and children destroyed"))))

;; ─── the run! loop ────────────────────────────────────────────────────────

(t/deftest run-pump-renders-on-demand
  ;; A bespoke adapter drives the loop's wiring: run! installs the frame
  ;; hook, the adapter mutates state, schedules and pumps — the tree must
  ;; re-render with the new value inside the run loop.
  (let [a (atom "start")
        scripts (atom [])
        adapter {:eval (fn [s] (swap! scripts conj s) "")
                 :command! (fn [_n _h] nil)
                 :run-loop (fn [pump]
                             (reset! a "later")
                             (component/schedule-frame!)
                             (pump))}]
    (binding [tk/*adapter* adapter]
      (tk/run! (fn [_] [:vbox {} [:label @a]]) {:title "p"}))
    (t/is (some #(re-find #"-text start" %) @scripts))
    (t/is (some #(re-find #"-text later" %) @scripts)
          "the scheduled pump re-rendered the tree")))

(t/deftest text-wrap-and-height
  (with-fake [rec nil]
    (let [m (mounted [:vbox {}
                      [:text {:text "one two three" :width 40 :height 1 :wrap "word"}]])
          ss (scripts rec)]
      (t/is (some #(str/includes? % "-wrap word") ss)
            "the widget wraps rather than clipping")
      (t/is (not-any? #(str/includes? % "count -displaylines") ss)
            "content that already fits costs no height round trip")
      (engine/dispose-component (:root m)))
    ;; a line longer than the widget wraps in Tk: the height must follow it
    (let [long (apply str (repeat 90 "x"))
          m (mounted [:vbox {} [:text {:text long :width 40 :height 1 :wrap "word"}]])
          ss (scripts rec)]
      (t/is (some #(str/includes? % "count -displaylines") ss)
            "a wrapped line re-sets -height from the display lines")
      (engine/dispose-component (:root m)))))

(t/deftest bordered-frame-uses-the-highlight-ring
  ;; -relief solid draws its border in the widget's own background, which is
  ;; invisible on a card over a similar canvas; the ring takes a colour.
  (with-fake [rec nil]
    (let [m (mounted [:vbox {} [:frame {:border 1 :border-color "#3b4757"} "x"]])
          ss (scripts-like rec #"^frame ")]
      (t/is (some #(str/includes? % "-highlightthickness 1") ss))
      (t/is (some #(str/includes? % "-highlightbackground \\#3b4757") ss))
      (t/is (some #(str/includes? % "-borderwidth 0") ss))
      (engine/dispose-component (:root m)))))

(t/deftest tracked-inputs-invalidate-the-mounted-body
  ;; A mounted component body that also reads tracked inputs must declare
  ;; them with tracked-deref; an untracked read of a plain atom caches the
  ;; tree and a click repaints nothing (the bug this pins).
  (with-fake [rec nil]
    (let [a (atom "one")
          bodies (atom 0)
          h (tk/mount! (fn [_]
                         (swap! bodies inc)
                         [:vbox {} [:label (r/tracked-deref a)]]))]
      (t/is (= 1 @bodies))
      (reset! a "two")
      ((:pump h))
      (t/is (= 2 @bodies) "a tracked plain-atom read re-runs the body")
      (t/is (some #(re-find #"-text two" %) (scripts rec))
            "and the repaint reaches the widget")
      (tk/run-loop! h))))

(t/deftest mount-render-loop-splits-the-loop-for-the-repl
  (with-fake [rec nil]
    (let [a (atom "one")
          h (tk/mount! (fn [_] [:vbox {} [:label @a]]))]
      (t/is (some #(re-find #"-text one" %) (scripts rec))
            "mount! renders the tree once")
      (let [before (count (scripts rec))]
        ((:pump h))
        (t/is (= before (count (scripts rec)))
              "an idle pump renders nothing"))
      ;; the REPL loop: edit, reload the namespace, render!
      (reset! a "two")
      (tk/render! h)
      ((:pump h))
      (t/is (some #(re-find #"-text two" %) (scripts rec))
            "render! + pump repaints with the new code")
      (tk/run-loop! h)
      (t/is (= 1 (count @(:pumps rec))) "run-loop! parks on the adapter's loop")
      (t/is (some #(= "destroy ." %) (scripts rec))
            "the loop's exit disposes the tree"))))

;; ─── rich text (:text :content segments) ─────────────────────────────────

(t/deftest text-content-segments-reuse-style-tags
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted
                          [:vbox {}
                           [:text {:content [{:text "plain "}
                                             {:text "red " :fg "#ff0000"}
                                             {:text "bold" :bold true}
                                             {:text "red2" :fg "#ff0000"}]}]])]
      (let [ss (scripts rec)
            configs (scripts-like rec #"tag configure")
            inserts (into [] (comp (filter #(re-find #" insert end " %))
                                   (mapcat #(str/split-lines %)))
                          ss)]
        (t/is (= 2 (count configs)) "one tag configure per distinct style")
        (t/is (= 1 (count (scripts-like rec #"-foreground \\#ff0000")))
              "the two red runs share one configured tag")
        (t/is (= 1 (count (scripts-like rec #"tag configure \S+ -font .*bold")))
              "bold uses a font description derived from the base fixed font")
        (t/is (= 4 (count inserts)))
        (t/is (re-find #"insert end plain\\ $|insert end plain$" (first inserts))
              "an unstyled segment inserts without a tag")
        (let [reds (filterv #(re-find #"insert end red" %) inserts)
              last-token (fn [s] (last (str/split (str/trim s) #" +")))]
          (t/is (= 2 (count reds)))
          (t/is (= (last-token (first reds)) (last-token (second reds))))))
      (engine/dispose-component root))))

(t/deftest text-scrollbar-is-wired
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:vbox {} [:text {:text "hi" :scrollbar true}]])
          path (second (re-find #"^text (\.w[\w.]+)" (first (scripts-like rec #"^text \.w"))))
          sb (second (re-find #"^ttk::scrollbar (\.w[\w.]+) -orient vertical -command \""
                              (first (scripts-like rec #"^ttk::scrollbar "))))]
      (t/is (some true? (map #(= (str "ttk::scrollbar " sb " -orient vertical -command \""
                                      path " yview\"")
                                 %)
                             (scripts rec))))
      (t/is (some true? (map #(= (str path " configure -yscrollcommand \"" sb " set\"") %)
                             (scripts rec))))
      (t/is (some true? (map #(= (str "pack " sb " -side right -fill y") %)
                             (scripts rec))))
      (t/is (some true? (map #(= (str "pack " path " -side left -fill both -expand 1") %)
                             (scripts rec))))
      (engine/dispose-component root)
      (t/is (some #(str/includes? % (str "destroy " sb)) (scripts rec))
            "dispose destroys the sibling scrollbar"))))

;; ─── the :transcript stack ───────────────────────────────────────────────

(t/deftest transcript-embeds-each-child-as-a-window
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:vbox {}
                                   [:transcript {:see-end true :read-only true}
                                    [:text {:text "a"}]
                                    [:text {:text "b"}]]])
          ss (scripts rec)
          text (second (re-find #"^text (\.w[\w.]+)" (first (scripts-like rec #"^text \."))))
          sb (second (re-find #"^ttk::scrollbar (\.w[\w.]+) -orient vertical -command \""
                              (first (scripts-like rec #"^ttk::scrollbar "))))]
      (t/is (some #(str/includes? % "-wrap none -height 1 -width 1") ss))
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
      (engine/dispose-component root)
      (t/is (some #(str/includes? % (str "destroy " sb)) (scripts rec))
            "dispose destroys the sibling scrollbar"))))
