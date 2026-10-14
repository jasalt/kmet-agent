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
            [kmet.gui.tk.dsl :as dsl]
            [kmet.gui.tk.options :as opt]
            [kmet.libs.hikap :as engine]
            [kmet.libs.hikap.alias :as alias]
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
    (dsl/forget-widget-options!)
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
  (let [window (engine/compile-element tk/tk-backend [:tk/toplevel {:title "t"}])
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
    (tk/run! (fn [_] [:ttk/frame {:padding 4}
                      [:ttk/label "hello"]
                      [:ttk/button {:text "Go" :on-click (constantly nil)}]])
             {:title "kmet" :width 320 :height 200})
    (let [ss (scripts rec)]
      (t/is (some #(= "wm title . kmet" %) ss))
      (t/is (some #(re-find #"ttk::frame \.w\d+ -padding 4" %) ss))
      (t/is (some #(re-find #"ttk::label \.w\d+\.w\d+ .*-text hello" %) ss))
      (t/is (some #(re-find #"ttk::button" %) ss))
      (t/is (some #(re-find #"pack .* -side top" %) ss))
      (t/is (some #(re-find #"-command \{kmet::cb \d+\}" %) ss))
      (t/is (= 1 (count @(:commands rec))) "kmet::cb trampoline registered"))))

(t/deftest bare-strings-become-labels
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {} "hi"])]
      (t/is (some #(re-find #"ttk::label .* -text hi" %) (scripts rec)))
      (engine/dispose-component root))))

(t/deftest tag-names-follow-tk
  ;; :tk/toplevel is the root window, :ttk/frame a ttk::frame, :ttk/labelframe
  ;; a ttk::labelframe, and pack geometry stays the child's (:side/:fill).
  (with-fake [rec nil]
    (let [m (mounted [:ttk/frame {}
                      [:ttk/labelframe {:text "Title"} "x"]
                      [:ttk/label {:text "row" :side :left :fill :y}]])
          ss (scripts rec)]
      (t/is (some #(re-find #"^ttk::labelframe \.w[\w.]+ -text Title" %) ss))
      (t/is (some #(re-find #"^pack \.w[\w.]+ -side left -fill y" %) ss)
            "the child's :side is Tk's pack -side")
      (engine/dispose-component (:root m)))))

(t/deftest qualified-tags-are-host-tags-not-aliases
  ;; kmet.gui's tags carry Tcl's namespace split, so they are qualified: a
  ;; keyword the backend declares is a host tag and wins over the alias
  ;; registry; every other qualified keyword is still an alias lookup.
  (let [called (atom nil)
        unregister (alias/register! :tk/frame
                                    (fn [_ _] (reset! called true) nil))]
    (try
      (with-fake [rec nil]
        (let [{:keys [root]} (mounted [:tk/frame {}])]
          (t/is (some #(re-find #"^frame " %) (scripts rec))
                "the declared :tk/frame renders the classic frame")
          (t/is (nil? @called) "the alias fn never ran")
          (engine/dispose-component root)))
      (finally (unregister))))
  (with-fake [_rec nil]
    (t/is (= :ttk/nope
             (try (mounted [:ttk/nope {}]) nil
                  (catch Exception e (:alias (ex-data e)))))
          "an undeclared qualified keyword is still an alias")))

;; ─── props, patching and rebuilds ─────────────────────────────────────────

(t/deftest entry-text-patches-in-place
  (with-fake [rec nil]
    (let [a (atom "one")
          m (mounted (fn [_] [:ttk/frame {} [:ttk/entry {:text @a}]]))]
      (t/is (some #(re-find #"insert 0 one" %) (scripts rec)))
      (let [before (count (scripts-like rec #"^destroy "))]
        (reset! a "two")
        (rerender! m)
        (t/is (= before (count (scripts-like rec #"^destroy ")))
              "an apply-path leaf is patched, never rebuilt")
        (t/is (some #(re-find #"insert 0 two" %) (scripts rec))))
      (engine/dispose-component (:root m)))))

(t/deftest label-patches-what-it-can-and-declines-what-it-cannot
  (with-fake [rec nil]
    (let [text (atom "one")
          bold (atom false)
          fill (atom :both)
          m (mounted (fn [_] [:ttk/frame {} [:ttk/label {:text @text :bold @bold
                                                         :fill @fill}]]))]
      ;; a text change is a Tcl option → patch in place, keep the instance
      (reset! text "two")
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "a patchable prop change keeps the widget")
      (t/is (some #(re-find #"-text two" %) (scripts rec)))
      ;; the font is an option too → a bold flip is a live configure
      (reset! bold true)
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "a font change patches the label in place")
      (t/is (some #(re-find #"configure -font" %) (scripts rec)))
      ;; pack geometry is applied at construction only → decline, rebuild
      (reset! fill :y)
      (rerender! m)
      (t/is (pos? (count (scripts-like rec #"^destroy ")))
            "a pack change declines and rebuilds the label")
      (t/is (some #(re-find #"-fill y" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

(t/deftest label-rebinds-a-changed-click-callback
  (with-fake [rec nil]
    (let [a (atom (fn [] :first))
          m (mounted (fn [_] [:ttk/frame {} [:ttk/label {:text "x" :on-click @a}]]))]
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
                       [:ttk/frame {}
                        (map-indexed (fn [_i s]
                                       ^{:key s}
                                       [:ttk/label {:text (str s "!")}])
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

;; ─── the option catalogue ─────────────────────────────────────────────────

(t/deftest a-tags-options-are-its-widgets-tk-option-set
  ;; :options composes families from kmet.gui.tk.options with the widget's
  ;; own options, so a tag accepts exactly the options its widget has.
  (let [tags (:tags tk/tk-backend)]
    (t/is (= {:class :class :cursor :cursor :takefocus :takefocus
              :style :style :orient :orient}
             (:options (tags :ttk/separator)))
          "three families plus :orient")
    (t/is (= {:colormap :colormap :class :class :container :container
              :visual :visual :cursor :cursor :takefocus :takefocus
              :width :width :height :height :bg :background
              :backgroundimage :backgroundimage :tile :tile :relief :relief
              :borderwidth :borderwidth :highlightbackground :highlightbackground
              :highlightcolor :highlightcolor
              :highlightthickness :highlightthickness :padx :padx :pady :pady}
             (:options (tags :tk/frame))))
    (t/is (= :background (get-in (tags :ttk/entry) [:options :bg]))
          "the themed entry reaches Tk's -background/-foreground")
    (t/is (= :mode (get-in (tags :ttk/progressbar) [:options :mode])))
    (t/is (not (contains? (:options (tags :tk/text)) :state))
          "the :text tags own -state (they are read-only surfaces)")))

(t/deftest families-and-props-are-checked-at-load
  (t/is (thrown-with-msg? Exception #"unknown families"
                          (opt/options-for [:standard :nope])))
  (t/is (thrown-with-msg? Exception #"outside the catalogue"
                          (opt/options-for [:standard] [:nope]))))

(t/deftest list-valued-props-render-as-tcl-lists
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {:padding [6 12]}
                                   [:tk/text {:tabs [40 80]}]])]
      (t/is (some #(str/includes? % "-padding 6\\ 12") (scripts rec))
            "a :padding pair is one Tcl list word")
      (t/is (some #(str/includes? % "-tabs 40\\ 80") (scripts rec)))
      (engine/dispose-component root))))

(t/deftest an-empty-value-is-a-tcl-word-not-nothing
  ;; no whitespace is an option's value in Tcl: an empty one must be an
  ;; explicit empty word, or the option after it becomes its value
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/entry {:show "" :state :normal}])]
      (t/is (some #(str/includes? % "-show {} -state normal") (scripts rec)))
      (engine/dispose-component root))))

(t/deftest creation-only-props-rebuild-while-options-patch
  (let [spec (get (:tags tk/tk-backend) :tk/frame)]
    (t/is (not (dsl/patchable? spec {:class "A"} {:class "B"}))
          "-class is creation-only in Tk: a change rebuilds")
    (t/is (dsl/patchable? spec {:bg "#111111"} {:bg "#222222"}))
    (t/is (dsl/patchable? spec {:width 10} {:width 20}))
    (t/is (not (dsl/patchable? spec {:side :top} {:side :left}))
          "pack geometry is not a Tk option: a change rebuilds")))

(t/deftest options-this-tk-lacks-fail-loudly
  ;; check-options! asks the widget for its own option list, so a prop whose
  ;; option this Tk does not have fails with tag, prop and option named
  ;; instead of scripting a broken widget.
  (let [adapter {:eval (fn [s] (if (str/includes? s " configure]")
                                 "-text\n-cursor\n-takefocus\n-class\n-style\n"
                                 ""))
                 :command! (fn [_ _] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (t/is (thrown-with-msg? Exception #"no -background"
                              (mounted [:ttk/label {:text "ok" :bg "#101014"}])))
      (dsl/forget-widget-options!)
      (t/is (some? (mounted [:ttk/label {:text "ok" :cursor "hand2"}]))
            "a prop whose option exists is accepted"))))

(t/deftest the-newly-declared-options-reach-their-widgets  (with-fake [rec nil]
                                                             (let [{:keys [root]} (mounted [:ttk/frame {}
                                                                                            [:ttk/entry {:bg "#101014" :show "*" :justify :right
                                                                                                         :state :readonly}]
                                                                                            [:ttk/separator {:orient :horizontal}]
                                                                                            [:ttk/progressbar {:mode :indeterminate :length 120}]
                                                                                            [:tk/text {:undo false :selectbackground "#333333"}]])]
                                                               (t/is (some #(str/includes? % "-background \\#101014") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-show *") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-state readonly") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-orient horizontal") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-mode indeterminate") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-selectbackground \\#333333") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-undo 0") (scripts rec)))
                                                               (engine/dispose-component root))))

(t/deftest a-text-font-change-patches-and-rebases-the-generated-tags
  ;; the :tk/text widget's mono font is an option like any other, and the
  ;; Tk tags it generates for styled segments derive from it
  (with-fake [rec nil]
    (let [size (atom 12)
          m (mounted (fn [_] [:tk/text {:text "hi" :font-size @size}]))]
      (reset! size 14)
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "a font change patches the text widget in place")
      (t/is (some #(re-find #"configure -font" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

;; ─── callbacks ────────────────────────────────────────────────────────────

(t/deftest click-dispatches-through-the-trampoline
  (with-fake [rec nil]
    (let [clicked (atom nil)]
      (mounted [:ttk/frame {}
                [:ttk/button {:text "Go" :on-click #(reset! clicked :yes)}]])
      (let [script (first (scripts-like rec #"-command \{kmet::cb \d+\}"))
            id (second (re-find #"kmet::cb (\d+)" script))]
        (t/is (some? id))
        (t/is (= ":yes" (tk/dispatch-callback! ["kmet::cb" id])))
        (t/is (= :yes @clicked))
        (t/is (string? (tk/dispatch-callback! ["kmet::cb" "999999"]))
              "unknown callback answers empty, not an error")))))

;; ─── dispose ──────────────────────────────────────────────────────────────

(t/deftest removing-a-callback-unbinds-it
  ;; A callback that leaves the tree must stop firing: the Tk binding is
  ;; cleared and its trampoline entry released.
  (with-fake [rec nil]
    (let [f (atom (fn [] :live))
          m (mounted (fn [_] [:ttk/frame {} [:ttk/label {:text "x" :on-click @f}]]))
          script (first (scripts-like rec #"bind "))
          path (second (re-find #"bind (\.w[\w.]+) <Button-1>" script))
          id (second (re-find #"kmet::cb (\d+)" script))]
      (t/is (= ":live" (tk/dispatch-callback! ["kmet::cb" id])))
      (reset! f nil)
      (rerender! m)
      (t/is (some #(= (str "bind " path " <Button-1> {}") %) (scripts rec))
            "the removed callback is unbound in Tk")
      (t/is (= "" (tk/dispatch-callback! ["kmet::cb" id]))
            "and released from the trampoline")
      (engine/dispose-component (:root m)))))

(t/deftest value-slots-receive-the-widget-value
  ;; :on-change/:on-submit get the entry's text — Tcl reads it when the
  ;; event fires — and :on-toggled gets the checked state as a boolean.
  (with-fake [rec nil]
    (let [typed (atom nil)
          sent (atom nil)
          m (mounted (fn [_] [:ttk/frame {}
                              [:ttk/entry {:text "abc"
                                           :on-change (fn [t] (reset! typed t) nil)
                                           :on-submit (fn [t] (reset! sent t) nil)}]]))
          id (fn [re] (second (re-find #"kmet::cb (\d+)" (first (scripts-like rec re)))))]
      (t/is (re-find #"\[(\.w[\w.]+) get\]" (first (scripts-like rec #"<KeyRelease>")))
            "the change event passes the entry's live text")
      (tk/dispatch-callback! ["kmet::cb" (id #"<KeyRelease>") "abcd"])
      (t/is (= "abcd" @typed))
      (tk/dispatch-callback! ["kmet::cb" (id #"<Return>") "abcd"])
      (t/is (= "abcd" @sent))
      (engine/dispose-component (:root m))))
  (with-fake [rec nil]
    (let [checked (atom ::none)
          m (mounted (fn [_] [:ttk/frame {}
                              [:ttk/checkbutton {:text "x" :active false
                                                 :on-toggled (fn [v] (reset! checked v) nil)}]]))
          script (first (scripts-like rec #"-command \{kmet::cb"))
          id (second (re-find #"kmet::cb (\d+)" script))]
      (t/is (re-find #"instate selected" script)
            "the command reads the toggled state")
      (tk/dispatch-callback! ["kmet::cb" id "1"])
      (t/is (true? @checked))
      (tk/dispatch-callback! ["kmet::cb" id "0"])
      (t/is (false? @checked))
      (engine/dispose-component (:root m)))))

(t/deftest bind-takes-chords-and-tracks-the-map
  ;; :bind binds arbitrary events by kmet chord (or a raw Tk pattern); a
  ;; chord that leaves the map is unbound again.
  (with-fake [rec nil]
    (let [hits (atom [])
          bs (atom {:ctrl+enter (fn [] (swap! hits conj :send) nil)
                    :escape (fn [] (swap! hits conj :cancel) nil)
                    "<Double-Button-1>" (fn [] nil)})
          m (mounted (fn [_] [:ttk/frame {} [:ttk/entry {:text "x" :bind @bs}]]))
          slot (fn [ev] (first (filter #(str/includes? % (str ev " ")) (scripts rec))))
          id (fn [ev] (second (re-find #"kmet::cb (\d+)" (slot ev))))]
      (t/is (some #(re-find #"<Control-Return>" %) (scripts rec)))
      (t/is (some #(str/includes? % "<Double-Button-1>") (scripts rec))
            "a Tk event pattern passes through")
      (tk/dispatch-callback! ["kmet::cb" (id "<Control-Return>")])
      (t/is (= [:send] @hits))
      (let [dropped (id "<Control-Return>")
            path (second (re-find #"bind (\.w[\w.]+) <Control-Return>" (slot "<Control-Return>")))]
        (reset! bs {:escape (fn [] nil)})
        (rerender! m)
        (t/is (some #(= (str "bind " path " <Control-Return> {}") %) (scripts rec))
              "the dropped chord is unbound")
        (t/is (= "" (tk/dispatch-callback! ["kmet::cb" dropped]))))
      (engine/dispose-component (:root m)))))

(t/deftest window-bind-binds-the-toplevel
  (with-fake [rec nil]
    (tk/run! (fn [_] [:ttk/frame {}]) {:title "k" :bind {:ctrl+q (fn [] nil)}})
    (t/is (some #(re-find #"^bind \. <Control-q> \{kmet::cb \d+\}$" %) (scripts rec)))))

(t/deftest keyword-prop-values-render-as-tcl-words
  ;; :wrap :none and :fill :both are Clojure data, not Tcl strings.
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {} [:tk/text {:text "x" :wrap :none :fill :both}]])]
      (t/is (some #(str/includes? % "-wrap none") (scripts rec)))
      (t/is (some #(str/includes? % "-fill both") (scripts rec)))
      (engine/dispose-component root))))

(t/deftest unknown-bind-chords-fail-loudly
  (with-fake [_rec nil]
    (t/is (= :kmet.gui.tk/bad-chord
             (try
               (mounted [:ttk/frame {} [:ttk/label {:text "x" :bind {:hyper+unknown (fn [] nil)}}]])
               nil
               (catch Exception e (:type (ex-data e))))))))

(t/deftest dispose-destroys-the-widget-and-releases-callbacks
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:ttk/label "bye"]
                                   [:ttk/button {:text "x" :on-click (constantly nil)}]])]
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
      (tk/run! (fn [_] [:ttk/frame {} [:ttk/label @a]]) {:title "p"}))
    (t/is (some #(re-find #"-text start" %) @scripts))
    (t/is (some #(re-find #"-text later" %) @scripts)
          "the scheduled pump re-rendered the tree")))

(t/deftest text-wrap-and-height
  (with-fake [rec nil]
    (let [m (mounted [:ttk/frame {}
                      [:tk/text {:text "one two three" :width 40 :height 1 :wrap "word"}]])
          ss (scripts rec)]
      (t/is (some #(str/includes? % "-wrap word") ss)
            "the widget wraps rather than clipping")
      (t/is (not-any? #(str/includes? % "count -displaylines") ss)
            "content that already fits costs no height round trip")
      (engine/dispose-component (:root m)))
    ;; a line longer than the widget wraps in Tk: the height must follow it
    (let [long (apply str (repeat 90 "x"))
          m (mounted [:ttk/frame {} [:tk/text {:text long :width 40 :height 1 :wrap "word"}]])
          ss (scripts rec)]
      (t/is (some #(str/includes? % "count -displaylines") ss)
            "a wrapped line re-sets -height from the display lines")
      (engine/dispose-component (:root m)))))

(t/deftest bordered-frame-uses-the-highlight-ring
  ;; -relief solid draws its border in the widget's own background, which is
  ;; invisible on a card over a similar canvas; the ring takes a colour.
  (with-fake [rec nil]
    (let [m (mounted [:ttk/frame {} [:tk/frame {:border 1 :border-color "#3b4757"} "x"]])
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
                         [:ttk/frame {} [:ttk/label (r/tracked-deref a)]]))]
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
          h (tk/mount! (fn [_] [:ttk/frame {} [:ttk/label @a]]))]
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

;; ─── the added widgets ───────────────────────────────────────────────────

(t/deftest every-tk-widget-class-is-a-tag
  ;; the classic and themed sets together cover every widget class the
  ;; interpreter has (minus the aliases: :tk/transcript is a text widget,
  ;; :tk/toplevel the window)
  (let [tags (:tags tk/tk-backend)]
    (t/is (= 38 (count tags)))
    (t/is (every? tags [:tk/button :tk/canvas :tk/checkbutton :tk/entry :tk/label
                        :tk/labelframe :tk/listbox :tk/menu :tk/menubutton :tk/message
                        :tk/panedwindow :tk/radiobutton :tk/scale :tk/scrollbar
                        :tk/spinbox :ttk/combobox :ttk/menubutton :ttk/notebook
                        :ttk/panedwindow :ttk/radiobutton :ttk/scale :ttk/scrollbar
                        :ttk/sizegrip :ttk/spinbox :ttk/toggleswitch :ttk/treeview]))
    (t/is (false? (get-in tags [:tk/menu :pack?]))
          "Tk refuses to let pack manage a menu")))

(t/deftest the-added-widgets-script-their-options
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:tk/button {:text "go" :default :active :repeatdelay 300}]
                                   [:tk/label {:text "hi" :wraplength 200}]
                                   [:tk/canvas {:scrollregion [0 0 100 100] :confine true}]
                                   [:tk/scale {:from 0 :to 10 :value 3 :orient :horizontal}]
                                   [:tk/scrollbar {:orient :vertical :position [0.2 0.4]}]
                                   [:ttk/scale {:from 0 :to 5 :value 2}]
                                   [:ttk/scrollbar {:orient :vertical :position [0.1 0.5]}]
                                   [:ttk/sizegrip {}]])]
      (t/is (some #(str/includes? % "-default active") (scripts rec)))
      (t/is (some #(str/includes? % "-repeatdelay 300") (scripts rec)))
      (t/is (some #(str/includes? % "-wraplength 200") (scripts rec)))
      (t/is (some #(str/includes? % "-scrollregion 0\\ 0\\ 100\\ 100") (scripts rec))
            "a canvas :scrollregion is one Tcl list word")
      (t/is (some #(str/includes? % "-confine 1") (scripts rec)))
      (t/is (some #(str/includes? % "-to 10") (scripts rec)))
      (t/is (some #(str/includes? % "set 0.2 0.4") (scripts rec))
            ":position sets the scrollbar's thumb")
      (engine/dispose-component root))))

(t/deftest a-checkable-widgets-state-lives-in-its-private-variable
  ;; Tk's -variable is not a prop: the tag owns one, which is the only state
  ;; a classic checkbutton can be read from (select/deselect, no query)
  (with-fake [rec nil]
    (let [active (atom false)
          m (mounted (fn [_] [:ttk/frame {} [:ttk/checkbutton {:text "agree"
                                                               :active @active}]]))]
      (t/is (some #(re-find #"-variable ::kmet_check_\S+" %) (scripts rec)))
      (t/is (some #(re-find #"set ::kmet_check_\S+ 0" %) (scripts rec))
            "an unchecked button's variable says so")
      (reset! active true)
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "flipping :active patches, it does not rebuild")
      (t/is (some #(re-find #"set ::kmet_check_\S+ 1" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

(t/deftest a-listbox-carries-items-and-a-selection
  (with-fake [rec nil]
    (let [items (atom ["alpha" "beta"])
          selected (atom [1])
          m (mounted (fn [_] [:tk/listbox {:items @items :selected @selected}]))]
      (t/is (some #(re-find #"insert end alpha beta" %) (scripts rec)))
      (t/is (some #(re-find #"selection set 1 1" %) (scripts rec)))
      (reset! items ["gamma"])
      (reset! selected [0])
      (rerender! m)
      (t/is (some #(re-find #"insert end gamma" %) (scripts rec)))
      (t/is (some #(re-find #"selection clear 0 end" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

(t/deftest a-treeview-takes-columns-rows-and-a-selection
  (with-fake [rec nil]
    (let [rows (atom [["a" "1"] ["b" "2"]])
          selected (atom "r1")
          m (mounted (fn [_] [:ttk/treeview {:columns [{:id :name :heading "Name" :width 120}
                                                       :count]
                                             :rows @rows
                                             :selected @selected}]))]
      (t/is (some #(re-find #"ttk::treeview \S+ -columns name\\ count" %) (scripts rec))
            "the column ids become Tk's -columns")
      (t/is (some #(re-find #"heading name -text Name" %) (scripts rec)))
      (t/is (some #(re-find #"column name -width 120" %) (scripts rec)))
      (t/is (some #(re-find #"insert \{\} end -id r1 -values \{a 1\}" %) (scripts rec)))
      (t/is (some #(re-find #"insert \{\} end -id r2 -values \{b 2\}" %) (scripts rec)))
      (t/is (some #(re-find #"selection set r1" %) (scripts rec)))
      (reset! rows [["c" "3"]])
      (rerender! m)
      (t/is (some #(re-find #"delete \[\S+ children \{\}\]" %) (scripts rec))
            "a rows change rebuilds the rows, not the widget")
      (t/is (zero? (count (scripts-like rec #"^destroy "))))
      (engine/dispose-component (:root m)))))

(t/deftest a-notebook-adds-tabs-and-a-panedwindow-panes
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:ttk/notebook {}
                                    [:ttk/frame {:tab "One"}]
                                    [:ttk/frame {:tab "Two"}]]
                                   [:ttk/panedwindow {:orient :horizontal}
                                    [:ttk/frame {:weight 2}]
                                    [:ttk/frame {:weight 1}]]
                                   [:tk/panedwindow {:orient :horizontal}
                                    [:tk/frame {:minsize 40}]]])]
      (t/is (some #(re-find #"^\S+ add \S+ -text One" %) (scripts rec)))
      (t/is (some #(re-find #"-text Two" %) (scripts rec)))
      (t/is (some #(re-find #"add \S+ -weight 2" %) (scripts rec)))
      (t/is (some #(re-find #"add \S+ -weight 1" %) (scripts rec)))
      (t/is (some #(re-find #"add \S+ -minsize 40" %) (scripts rec)))
      (let [added (into #{} (keep #(second (re-find #" add (\S+) " %)) (scripts rec)))
            packed (into #{} (keep #(second (re-find #"^pack (\S+)" %)) (scripts rec)))]
        (t/is (seq added))
        (t/is (empty? (filter added packed))
              "a child of a pane container is added, never packed"))
      (engine/dispose-component root))))

(t/deftest menus-carry-their-items
  (with-fake [rec nil]
    (let [clicked (atom nil)
          menu-props {:items [{:label "Open…" :accelerator "Ctrl-O"
                               :on-click (fn [] (reset! clicked :open))}
                              :separator
                              {:label "More" :items [{:label "Deep"}]}]}
          m (mounted (fn [_] [:ttk/frame {}
                              [:tk/menu menu-props]
                              [:ttk/menubutton {:text "File"
                                                :items [{:label "Quit"
                                                         :on-click identity}]}]]))]
      (t/is (some #(re-find #"add command -label Open.*-accelerator Ctrl-O" %) (scripts rec)))
      (t/is (some #(re-find #"add separator" %) (scripts rec)))
      (t/is (some #(re-find #"add cascade -label More -menu" %) (scripts rec)))
      (t/is (some #(re-find #"-tearoff 0" %) (scripts rec)))
      (t/is (some #(re-find #"add command -label Quit" %) (scripts rec)))
      (let [ids (mapv #(second (re-find #"kmet::cb (\d+)" %))
                      (scripts-like rec #"add command -label"))]
        (t/is (= 3 (count ids)) "one trampoline per command item")
        (t/is (nil? @clicked))
        (tk/dispatch-callback! ["kmet::cb" (first ids)])
        (t/is (= :open @clicked) "the item's handler fires on dispatch"))
      (engine/dispose-component (:root m)))))

(t/deftest a-menu-item-change-releases-the-old-trampolines
  (with-fake [rec nil]
    (let [f (atom (fn [] :first))
          m (mounted (fn [_] [:tk/menu {:items [{:label "x" :on-click @f}]}]))]
      (reset! f (fn [] :second))
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "an items change patches the menu")
      (let [ids (mapv #(second (re-find #"kmet::cb (\d+)" %))
                      (scripts-like rec #"add command -label"))
            [old new] (mapv parse-long ids)]
        (t/is (= 2 (count ids)))
        (t/is (not= old new))
        (t/is (= "" (tk/dispatch-callback! ["kmet::cb" (str old)]))
              "the replaced item's handler is released"))
      (engine/dispose-component (:root m)))))

(t/deftest a-canvas-draws-its-items
  (with-fake [rec nil]
    (let [items (atom [{:type :line :coords [0 0 10 10] :fill "#ff0000"}
                       {:type :text :coords [5 5] :text "hi" :anchor :w}])
          m (mounted (fn [_] [:tk/canvas {:items @items}]))]
      (t/is (some #(re-find #"create line 0 0 10 10 -fill \\#ff0000" %) (scripts rec)))
      (t/is (some #(re-find #"create text 5 5 -anchor w -text hi" %) (scripts rec)))
      (reset! items [{:type :rect :coords [1 1 2 2]}])
      (rerender! m)
      (t/is (some #(str/includes? % "delete all") (scripts rec)))
      (t/is (some #(re-find #"create rect 1 1 2 2" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

;; ─── rich text (:text :content segments) ─────────────────────────────────

(t/deftest text-content-segments-reuse-style-tags
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted
                          [:ttk/frame {}
                           [:tk/text {:content [{:text "plain "}
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
    (let [{:keys [root]} (mounted [:ttk/frame {} [:tk/text {:text "hi" :scrollbar true}]])
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
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:tk/transcript {:see-end true :read-only true}
                                    [:tk/text {:text "a"}]
                                    [:tk/text {:text "b"}]]])
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
      (engine/dispose-component root)
      (t/is (some #(str/includes? % (str "destroy " sb)) (scripts rec))
            "dispose destroys the sibling scrollbar"))))
