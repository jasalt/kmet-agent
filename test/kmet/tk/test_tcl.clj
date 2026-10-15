(ns kmet.tk.test-tcl
  "Real host FFI Tcl adapter tests (babashka.ffi / jolt.ffi).

   The adapter loads libtcl at call time, so this namespace loads on both
   hosts and the tests skip where libtcl is absent (and on the JVM).
   The libtcl-absent start-up is covered by running it on a machine that
   has Tcl/Tk installed."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tk.test-support :refer [find-class-src rerender! stop-live-tk! with-live-tk]]
            [kmet.tk.core :as tk]
            [kmet.tk.options :as opt]
            [kmet.libs.hikap :as engine]))

(defn- live-adapter
  "The real Tcl adapter with libtcl loaded, or nil (JVM, or no libtcl)."
  []
  (try
    (require 'kmet.tk.tcl)
    (let [make (deref (ns-resolve 'kmet.tk.tcl 'adapter))
          a (make)]
      ;; Forces the libtcl load; a missing library must skip, not fail.
      ((:eval a) "expr {1 + 2}")
      a)
    (catch Throwable _ nil)))

(t/deftest tcl-eval-command-and-quoting
  (if-let [a (live-adapter)]
    (try
      (t/is (= "3" ((:eval a) "expr {1 + 2}")))
      ((:command! a) "kmet::test" (fn [argv] (str "hi " (second argv))))
      (t/is (= "hi world" ((:eval a) "kmet::test world")))
      ;; round-trip through the real Tcl parser: braces, brackets, $, quote,
      ;; newline and a trailing backslash must survive tcl-quote unchanged
      (let [s "a b {c} [d] \"e\" $f\nline2\\end"]
        (t/is (= s ((:eval a) (str "set x " (tk/tcl-quote s) "\nset x")))))
      (t/is (= :threw (try ((:eval a) "error boom") (catch Exception _ :threw)))
            "a Tcl error is rethrown as ExceptionInfo")
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl is absent (or this is the JVM)")))

(t/deftest loaded-pair-and-version
  ;; The adapter records the Tcl/Tk pair it chose, and the interpreter it
  ;; created reports its own patchlevel — together they say which of the
  ;; supported Tcl lines (8.6/9.0/9.1) this host is exercising.
  (if-let [a (live-adapter)]
    (try
      (let [libs (-> (ns-resolve 'kmet.tk.tcl 'libraries) deref deref)
            patchlevel ((:eval a) "info patchlevel")]
        (t/is (string? (:tcl libs)) "the loaded Tcl library is recorded")
        (t/is (seq (:tk-candidates libs))
              "the pair's Tk candidates are recorded")
        (t/is (re-matches #"\d+\.\d+.*" patchlevel)
              (str "a plausible Tcl patchlevel, got " (pr-str patchlevel)))
        (t/is (contains? #{"8.6" "9.0" "9.1"} (subs patchlevel 0 3))
              (str "a supported Tcl version, got " patchlevel)))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl is absent (or this is the JVM)")))

(t/deftest derived-tk-names-from-tcl-spelling
  ;; KMET_TCL_LIB without KMET_TK_LIB pairs with the Tk names derived
  ;; from the Tcl library's own spelling (see the adapter docstring). A
  ;; sibling's separator is the platform's (the adapter joins through
  ;; babashka.fs), so the path case compares slashes-normalized.
  (if-let [dtk (try (require 'kmet.tk.tcl)
                    (deref (ns-resolve 'kmet.tk.tcl 'derived-tk-names))
                    (catch Throwable _ nil))]
    (do
      (t/is (= ["libtk8.6.so"] (dtk "libtcl8.6.so")))
      (t/is (= ["/opt/homebrew/opt/tcl-tk/lib/libtk9.1.dylib"]
               (mapv #(str/replace (str %) "\\" "/")
                     (dtk "/opt/homebrew/opt/tcl-tk/lib/libtcl9.1.dylib")))
            "the derived sibling keeps the Tcl path's directory")
      (t/is (= ["tk86t.dll"] (dtk "tcl86t.dll")))
      (t/is (= ["tk91.dll" "tcl9tk91.dll"] (dtk "tcl91.dll"))))
    (t/is true "skipped: the FFI adapter is unavailable")))

(t/deftest vwait-pump-and-nested-eval
  ;; The parked-eval path: Tcl_Eval (vwait) blocks while Tcl's timer calls
  ;; back into the trampoline, and the handler re-enters Tcl — exactly the
  ;; shape kmet.tk.tcl/run-loop! uses for the UI pump.
  (if-let [a (live-adapter)]
    (try
      (let [pumps (atom 0)]
        ((:command! a) "kmet::test-pump"
                       (fn [_argv]
                         (swap! pumps inc)
                         ((:eval a) "set ::nested [expr {2 + 3}]")
                         ""))
        (t/is (= "5" ((:eval a) (str "set ::done 0\n"
                                     "after 5 kmet::test-pump\n"
                                     "after 30 {set ::done 1}\n"
                                     "vwait ::done\n"
                                     "set ::nested"))))
        (t/is (= 1 @pumps)))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl is absent (or this is the JVM)")))

;; ─── the tag table against a real Tk ──────────────────────────────────────

(def ^:private tag-widget
  "Our tags → the Tk creation command they script. (the mapping the GUI README's option audit records)."
  {:tk/toplevel "toplevel" :tk/frame "frame" :tk/text "text"
   :tk/button "button" :tk/label "label" :tk/labelframe "labelframe"
   :tk/message "message" :tk/checkbutton "checkbutton" :tk/radiobutton "radiobutton"
   :tk/entry "entry" :tk/spinbox "spinbox" :tk/listbox "listbox" :tk/scale "scale"
   :tk/scrollbar "scrollbar" :tk/canvas "canvas" :tk/menubutton "menubutton"
   :tk/menu "menu" :tk/panedwindow "panedwindow"
   :ttk/frame "::ttk::frame" :ttk/labelframe "::ttk::labelframe" :ttk/label "::ttk::label"
   :ttk/button "::ttk::button" :ttk/checkbutton "::ttk::checkbutton"
   :ttk/radiobutton "::ttk::radiobutton" :ttk/entry "::ttk::entry"
   :ttk/combobox "::ttk::combobox" :ttk/spinbox "::ttk::spinbox" :ttk/scale "::ttk::scale"
   :ttk/scrollbar "::ttk::scrollbar" :ttk/sizegrip "::ttk::sizegrip"
   :ttk/toggleswitch "::ttk::toggleswitch" :ttk/menubutton "::ttk::menubutton"
   :ttk/notebook "::ttk::notebook" :ttk/panedwindow "::ttk::panedwindow"
   :ttk/progressbar "::ttk::progressbar" :ttk/separator "::ttk::separator"
   :ttk/treeview "::ttk::treeview"})

(t/deftest every-tag-scripts-only-options-this-tk-has
  ;; The strongest check of the option catalogue: for every tag, the Tcl
  ;; options its :options declares must be options of the widget class in
  ;; the running Tk (a family that does not fit the widget, a typo, or an
  ;; option newer than this Tk fails here), and a widget built with every
  ;; declared prop present — each set to the value Tk itself reports — must
  ;; be accepted, which exercises the value shapes too (lists, fonts,
  ;; keywords, booleans).
  (with-live-tk [e]
    (let [_ (e (str "proc optnames {w} { join [lmap o [$w configure] "
                    "{lindex $o 0}] \"\\n\" }"))
          n (atom 0)
          path (fn [] (str ".opt" (swap! n inc)))
          option-names (fn [p] (into #{} (filter #(str/starts-with? % "-")
                                                 (str/split-lines (e (str "optnames " p))))))
          samples (fn [spec creator]
                      ;; a prop value Tk accepts by definition: the option's
                      ;; own current value; the font props get a real family
                    (let [p (path)]
                      (e (str creator " " p))
                      (let [vals (into {}
                                       (keep (fn [[prop option]]
                                               (when-not (contains? opt/font-props prop)
                                                 [prop (e (str p " cget -" (name option)))])))
                                       (:options spec))]
                        (e (str "destroy " p))
                        (merge vals
                               (when (some opt/font-props (keys (:options spec)))
                                 {:font-family "Courier" :font-size 9
                                  :bold false :italic false})))))
          tags (:tags tk/tk-backend)]
      (doseq [[tag spec] (sort-by (comp str key) tags)]
        (let [creator (tag-widget tag)
              p (path)
              _ (e (str creator " " p))
              names (option-names p)
              _ (e (str "destroy " p))
              declared (concat (vals (:options spec))
                               (keep :option (vals (:bindings spec))))
              missing (sort (remove names (map #(str "-" (name %)) declared)))]
          (t/is (seq names) (str tag ": " creator " answers configure"))
          (t/is (empty? missing)
                (str tag " declares options " creator " does not have: "
                     (pr-str (vec missing))))))
        ;; one mount with every tag and every declared prop present
      (let [props (fn [tag] (samples (get tags tag) (tag-widget tag)))
            tree (into [:ttk/frame {}]
                       (for [tag (sort-by str (remove #{:tk/toplevel :ttk/frame}
                                                      (keys tags)))]
                         [tag (props tag)]))]

        (let [h (tk/mount! tree (props :tk/toplevel))]
          (t/is (some? h))
          (engine/dispose-component (:root h)))
        (t/is true "every tag accepted a widget with all its props set")))))

(t/deftest a-checkable-and-a-scale-hand-their-value-to-the-handler
  ;; The value slots of the widgets added later: a checkable widget's private
  ;; variable is what its -command reads, and a scale's -command receives the
  ;; number Tk's set put there (Tk's own invoke/set drive both here).
  (with-live-tk [e]
    (let [got (atom nil)]
      (e find-class-src)

      (let [h (tk/mount! [:ttk/frame {}
                          [:ttk/checkbutton {:text "agree"
                                             :on-toggled (fn [v] (reset! got v))}]
                          [:ttk/scale {:from 0 :to 10
                                       :on-change (fn [v] (reset! got v))}]] {})
            check (e "kmetfind . TCheckbutton")
            scale (e "kmetfind . TScale")]
        (t/is (seq check) "the checkbutton is in the window")
        (t/is (seq scale) "the scale is in the window")
        (e (str check " invoke"))
        (t/is (true? @got) "a click hands over the new checked state")
        (e (str check " invoke"))
        (t/is (false? @got) "and the next one flips it back")
        (e (str scale " set 7"))
        (t/is (= 7 @got) "a scale reports its value as a number")
        (engine/dispose-component (:root h))))))

(t/deftest a-treeview-nests-rows-tags-and-a-tree-column
  ;; A real ttk::treeview: a row's :children nest under its own id, a row tag
  ;; reaches and styles the rows naming it, the tree column is configured
  ;; like any other column (and is not one of Tk's -columns), and the :open
  ;; and :selected the app renders are what Tk reports back.
  (with-live-tk [e]
    (let [_ (e "image create photo ::icon -width 8 -height 8")
          h
          (tk/mount! (fn [_] [:ttk/treeview
                              {:columns [{:id "#0" :heading "Tool" :width 180}
                                         {:id :kind :heading "Kind"}]
                               :show [:tree :headings]
                               :height 6
                               :font-family "Courier New" :font-size 11
                               :tags {"done" {:foreground "#888888" :bold true}}
                               :rows [{:id "git" :text "git" :values ["shell"]
                                       :open true :tags ["done"]
                                       :children [{:text "status" :values ["read"]
                                                   :image "::icon"}]}
                                      {:text "llm" :values ["call"]}]
                               :selected "git.1"}])
                     {})
          tv (do (e find-class-src) (e "kmetfind . Treeview"))]
      (t/is (seq tv) "the treeview is in the window")
      (t/is (= "git r2" (e (str tv " children {}")))
            "the hierarchy has two roots, with the ids kmet defaulted")
      (t/is (= "git.1" (e (str tv " children git")))
            "the child nests under its parent's id")
      (t/is (= "1" (e (str tv " item git -open"))) "a row's :open is Tk's own")
      (t/is (= "done" (e (str tv " item git -tags"))))
      (t/is (= "1" (e (str tv " tag has done git")))
            "the tag reaches the row that names it")
      (t/is (= "0" (e (str tv " tag has done r2"))))
      (t/is (str/includes? (e (str tv " tag configure done")) "#888888")
            "and Tk holds the style the app gave it")
      (t/is (str/includes? (e (str tv " tag configure done")) "bold")
            "including the font its font props built")
      (t/is (= "::icon" (e (str tv " item git.1 -image"))) "a row image is Tk's own")
      (t/is (= "tree headings" (e (str tv " cget -show"))))
      (t/is (= "180" (e (str tv " column #0 -width")))
            "the tree column is configured like any other")
      (t/is (= "kind" (e (str tv " cget -columns")))
            "and is not one of Tk's -columns")
      (t/is (= "git.1" (e (str tv " selection"))) "a nested row is selected by id")
      (engine/dispose-component (:root h)))))

(t/deftest a-canvas-reconciles-real-items-and-clicks-them
  ;; A real canvas: an item's id is a Tk tag, so a re-render that moves an
  ;; item keeps the very item Tk drew (its numeric id does not change), the
  ;; options land through Tk's own itemconfigure/coords, and a click on the
  ;; item runs the handler its render gave it, with the click's coordinates.
  (with-live-tk [e]
    (let [clicked (atom nil)
          hovered (atom [])
          click #(reset! clicked %)
          items (atom [{:id "n1" :type :rect :coords [0 0 40 30] :fill "red"
                        :tags ["hot"] :on-click click
                        :on-enter #(swap! hovered conj [:enter %])
                        :on-leave #(swap! hovered conj [:leave %])
                        :on-drag #(swap! hovered conj [:drag %])}
                       {:id "n2" :type :line :coords [0 0 10 10]}])
          h
          (tk/mount! (fn [_] [:tk/canvas {:width 100 :height 60 :items @items}]) {})
          c (do (e find-class-src) (e "kmetfind . Canvas"))
          stack (fn []
                  (mapv #(first (str/split (e (str c " itemcget " % " -tags")) #"\s+"))
                        (str/split (e (str c " find all")) #"\s+")))]
      (t/is (seq c) "the canvas is in the window")
      (t/is (= "red" (e (str c " itemcget n1 -fill"))))
      (t/is (= "n1 hot" (e (str c " itemcget n1 -tags")))
            "the app's id is the item's first tag, its own tags after it")
      (let [item (e (str c " find withtag n1"))]
        (t/is (seq item) "the item is addressable by its id")
        (swap! items (fn [items]
                       [(assoc (first items) :coords [10 10 50 40])
                        {:id "n3" :type :line :coords [60 0 80 10]}]))
        (rerender! h)
        (t/is (= item (e (str c " find withtag n1")))
              "a moved item is the same Tk item, not a new one")
        (t/is (= [10.0 10.0 50.0 40.0] (mapv parse-double (str/split (e (str c " coords n1")) #"\s+")))
              "its coordinates move through Tk's coords subcommand")
        (t/is (= "" (e (str c " find withtag n2"))) "a gone id is deleted")
        (t/is (seq (e (str c " find withtag n3"))) "a new id is drawn"))
      (e "update")
      (e (str "event generate " c " <Button-1> -x 20 -y 20 -when tail"))
      (e "update")
      (t/is (= [20 20] @clicked)
            "a click on the item runs the handler its render gave it")
      (t/is (= ["n1" "n3"] (stack))
            "the render's order is Tk's stacking order, bottom first")
      (doseq [event ["<Enter>" "<Leave>" "<B1-Motion>"]]
        (t/is (seq (e (str c " bind n1 " event)))
              (str event " binds the item's own tag, where Tk directs it")))
      ;; Tk directs mouse events to the current item: the click made n1
      ;; current (an Enter, with the click's coordinates), and motion over
      ;; it reaches :on-drag with the motion's own.
      (e (str "event generate " c " <Button-1> -x 10 -y 10 -when tail"))
      (e (str "event generate " c " <B1-Motion> -x 12 -y 14 -when tail"))
      (e "update")
      (t/is (some #(= [:enter [20 20]] %) @hovered)
            "an item the pointer arrives at gets its :on-enter, with the event's point")
      (t/is (some #(= [:drag [12 14]] %) @hovered)
            "motion over the item hands :on-drag the motion's own coordinates")
      ;; Reordering the render restacks Tk's items without rebuilding them.
      (let [item (e (str c " find withtag n1"))]
        (swap! items reverse)
        (rerender! h)
        (t/is (= ["n3" "n1"] (stack))
              "a reordered render raises the items into its own order")
        (t/is (= item (e (str c " find withtag n1")))
              "and the raised item is still the very item Tk drew"))
      (engine/dispose-component (:root h)))))

(t/deftest a-context-menu-posts-at-the-click
  ;; On this platform (not macOS) a context menu answers <Button-3>, and its
  ;; handler is tk_popup at the pointer in root coordinates. tk_popup itself
  ;; is stubbed: what the DSL owns is the binding, the coordinates it hands
  ;; over and the menu it creates under the widget.
  (with-live-tk [e]
    (let [items (atom [{:label "Copy" :on-click (fn [] nil)}])
          h
          (tk/mount! (fn [_] [:ttk/frame {:width 60 :height 40
                                          :context-menu @items}]) {})
          w (do (e find-class-src) (e "kmetfind . TFrame"))]
      (t/is (seq w) "the widget is in the window")
      (e "set ::got {}")
      (e "proc tk_popup {args} {set ::got $args}")
      (e "update")
      (e (str "event generate " w " <Button-3> -x 3 -y 4 -when tail"))
      (e "update")
      (let [[menu x y] (str/split (e "set ::got") #"\s+")]
        (t/is (str/starts-with? menu (str w "."))
              "the menu is the widget's own child")
        (t/is (= (+ 3 (parse-long (e (str "winfo rootx " w)))) (parse-long x))
              "posted at the click, in root coordinates")
        (t/is (= (+ 4 (parse-long (e (str "winfo rooty " w)))) (parse-long y))))
      (reset! items nil)
      (rerender! h)
      (t/is (= "0" (e (str "winfo exists " (first (str/split (e "set ::got") #"\s+")))))
            "dropping the items destroys the menu")
      (e "set ::got {}")
      (e "update")
      (e (str "event generate " w " <Button-3> -x 5 -y 6 -when tail"))
      (e "update")
      (t/is (= "" (e "set ::got")) "and unbinds the event")
      (engine/dispose-component (:root h)))))

(t/deftest menu-items-carry-check-and-radio-state
  ;; A real menu: Tk's own checkbutton/radiobutton items, the private
  ;; variables the tag owns for them, and what Tk hands the handler — the
  ;; radio items' shared variable is what makes their group exclusive.
  (with-live-tk [e]
    (let [toggled (atom nil)
          click #(reset! toggled %)
          h
          (tk/mount! [:tk/menu {:items [{:kind :check :label "Wrap" :checked false
                                         :on-click click}
                                        {:kind :radio :group :mode :value :light
                                         :label "Light" :checked true
                                         :on-click click}
                                        {:kind :radio :group :mode :value :dark
                                         :label "Dark" :on-click click}]}]
                     {})
          m (do (e find-class-src) (e "kmetfind . Menu"))
          var-of (fn [i] (e (str m " entrycget " i " -variable")))]
      (t/is (seq m) "the menu is in the window")
      (t/is (= "Wrap" (e (str m " entrycget 0 -label"))))
      (t/is (= "0" (e (str "set " (var-of 0)))) "the check item starts unchecked")
      (e (str m " invoke 0"))
      (t/is (true? @toggled) "invoking it hands the handler the new state")
      (t/is (= "1" (e (str "set " (var-of 0)))) "and Tk flipped its variable")
      (t/is (= (var-of 1) (var-of 2)) "the radio group shares one variable")
      (t/is (= "light" (e (str "set " (var-of 1))))
            "which holds the value of the checked item")
      (e (str m " invoke 2"))
      (t/is (= "dark" (e (str "set " (var-of 1))))
            "invoking the other moves the whole group")
      (engine/dispose-component (:root h)))))

(t/deftest a-real-grid-and-place-lay-out-their-children
  ;; A real frame with a gridded and a placed child: Tk reports the manager
  ;; each one has — grid and place share a master, which is what makes an
  ;; overlay work — the container's configured column, and the options each
  ;; geometry map passed.
  (with-live-tk [e]
    (let [h
          (tk/mount! [:ttk/frame {:width 120 :height 80
                                  :columnconfigure {0 {:weight 1 :minsize 50}}}
                      [:ttk/label {:text "a" :grid {:row 0 :column 1 :sticky "nsew"}}]
                      [:ttk/label {:text "b" :place {:relx 0.25 :rely 0.5}}]]
                     {})
          f (do (e find-class-src) (e "kmetfind . TFrame"))
          kids (str/split (e (str "winfo children " f)) #"\s+")
          [first-kid second-kid] kids]
      (t/is (seq f) "the frame is in the window")
      (t/is (= 2 (count kids)) "both children are there")
      (t/is (= "grid" (e (str "winfo manager " first-kid))) "the first is gridded")
      (t/is (= "place" (e (str "winfo manager " second-kid)))
            "the second is placed — Tk lets place share a master")
      (t/is (and (str/includes? (e (str "grid info " first-kid)) "-column 1")
                 (str/includes? (e (str "grid info " first-kid)) "-row 0"))
            "with the row and column the render gave it")
      (t/is (str/includes? (e (str "place info " second-kid)) "-relx 0.25")
            "and the relative position its own map gave it")
      (t/is (str/includes? (e (str "grid columnconfigure " f " 0")) "-weight 1")
            "the container's column is configured")
      (engine/dispose-component (:root h)))))

(t/deftest install-leaves-tk-loaded-for-a-non-mounting-caller
  ;; kmet.tk.ttk.style scripts ttk::style between install! and mount, so
  ;; install! must load Tk itself instead of leaving it to the first mount:
  ;; the interpreter is stopped first, on purpose.
  (with-live-tk [e]
    (stop-live-tk!)
    ((deref (ns-resolve 'kmet.tk.tcl 'install!)))
    (t/is (seq (e "ttk::style theme use")) "Tk answers after install! alone")))

(t/deftest image-descriptions-make-real-photos
  ;; The image layer against a real Tk: a description creates a photo, the
  ;; sizing ops size it, a bitmap name passes through, and disposal deletes
  ;; the images the widget owned. The fixtures are 1x1 and 4x4 PNGs.
  (with-live-tk [e]

    (let [one (str "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8"
                   "z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
          four (str "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAEklEQVR4"
                    "nGP4z8DwHxkzkC4AADxAH+HggXe0AAAAAElFTkSuQmCC")
          size (fn [i] [(e (str "image width " i)) (e (str "image height " i))])
          kmet-images (fn [] (re-seq #"::kmet_img_\d+" (e "join [image names] \" \"")))
          h (tk/mount! [:ttk/frame {}
                        [:tk/label {:text "one" :image {:data one :format :png}}]
                        [:tk/label {:text "up" :image {:data one :format :png :zoom 3}}]
                        [:tk/label {:text "fit" :image {:data four :format :png
                                                        :fit {:width 2}}}]
                        [:tk/label {:text "bitmap" :bitmap :questhead}]] {})
          images (kmet-images)]
      (t/is (= 3 (count images))
            "one photo per description; the bitmap name is not one")
      (t/is (some #(= ["1" "1"] (size %)) images)
            "the 1x1 photo loads at its natural size")
      (t/is (some #(= ["3" "3"] (size %)) images) ":zoom 3 upscales it")
      (t/is (some #(= ["2" "2"] (size %)) images)
            ":fit subsamples the 4x4 photo to a 2-wide box")
      (engine/dispose-component (:root h))
      (t/is (empty? (kmet-images))
            "disposal deletes the photos the widget created"))))

(t/deftest dialogs-reach-tk-and-answer-as-clojure-data
  ;; The dialogs without a user: each Tk command the backend scripts is
  ;; redefined as a proc that checks (or reshapes) its own arguments, so Tcl
  ;; parses the emitted scripts exactly as it would for the real dialog —
  ;; the -filetypes structure above all — and an answer rides back through
  ;; the same path a real one takes. The interpreter is deleted afterwards,
  ;; so the redefinitions do not outlive the test.
  (with-live-tk [e]
    (e (str "proc tk_messageBox {args} {\n"
            "  set t [lindex $args [expr {[lsearch $args -type] + 1}]]\n"
            "  if {$t ne \"yesno\"} { error \"bad -type $t\" }\n"
            "  return yes\n"
            "}\n"
            "proc kmet_show {p} { list [lindex $p 0] [llength [lindex $p 1]] [lindex [lindex $p 1] 0] }\n"
            "proc tk_getOpenFile {args} {\n"
            "  if {[lsearch $args -multiple] >= 0} { return [list C:/a.txt {C:/b b.txt}] }\n"
            "  set ft [lindex $args [expr {[lsearch $args -filetypes] + 1}]]\n"
            "  join [list [llength $ft] {*}[kmet_show [lindex $ft 0]] {*}[kmet_show [lindex $ft 1]]] |\n"
            "}\n"
            "proc tk_getSaveFile {args} { return C:/out.txt }\n"
            "proc tk_chooseDirectory {args} { return /tmp }\n"
            "proc tk_chooseColor {args} { return #ff5500 }\n"))

    (t/is (= :yes (tk/message-box! {:type :yes-no :message "Save?"}))
          "the -type word reached Tk and the answer is a keyword")
    (t/is (= "2|All files|2|*.clj|Weird \\ {x}|1|*.md"
             (tk/open-file! {:file-types [["All files" ["*.clj" "*.cljc"]]
                                          ["Weird \\ {x}" "*.md"]]}))
          "the -filetypes value parses as Tk's [label extensions] pairs")
    (t/is (= ["C:/a.txt" "C:/b b.txt"] (tk/open-file! {:multiple true}))
          "a multiple answer's list is split by Tcl, spaces intact")
    (t/is (= "C:/out.txt" (tk/save-file! {:title "Save"})))
    (t/is (= "/tmp" (tk/choose-directory! {:must-exist true})))
    (t/is (= "#ff5500" (tk/choose-color! {:title "Colour"})))))

(t/deftest an-editable-text-keeps-its-value-exactly
  ;; The value goes through tcl-quote and comes back out of Tcl's own
  ;; parser: a multi-line text whose lines carry Tcl metacharacters must
  ;; return unchanged, Tk's trailing newline excepted.
  (with-live-tk [e]
    (let [value "one\ntwo $x [y] {z} \"q\"\\"
          h
          (tk/mount! (fn [_] [:tk/text {:editable true :text value}]) {})
          text (do (e find-class-src) (e "kmetfind . Text"))]
      (t/is (= value (e (str text " get 1.0 end-1c")))
            "the value round-trips through the Tcl parser unchanged")
      (t/is (= (str value "\n") (e (str text " get 1.0 end")))
            "Tk's own trailing newline is the only thing added")
      (engine/dispose-component (:root h)))))

(t/deftest an-editable-text-types-submits-and-undoes
  ;; A real text widget driven through its own bindings: a typed key reaches
  ;; :on-change with the value read back, Return runs :on-submit without Tk's
  ;; class binding also inserting a newline, `edit undo` takes back the typed
  ;; key alone (a value the app wrote is not an undo step, so clearing a sent
  ;; composer cannot be undone back), and a re-render that passes the text
  ;; the widget already holds writes nothing. mount! is what registers the
  ;; kmet::cb command the bound scripts dispatch through.
  (with-live-tk [e]
    (let [value (atom "hi")
          typed (atom [])
          submitted (atom [])
            ;; the Shift-Enter recipe: a 0-arity no-op chord that suppresses
            ;; the <Return> slot and lets Tk's own binding insert the line
          no-op (fn [] nil)
          h
          (tk/mount! (fn [_] [:tk/text {:editable true :text @value
                                        :on-change (fn [v] (swap! typed conj v))
                                        :on-submit (fn [v] (swap! submitted conj v))
                                        :bind {"shift+enter" no-op}}])
                     {})
          text (do (e find-class-src) (e "kmetfind . Text"))]
      (t/is (seq text) "the editable text is in the window")
      (t/is (= "normal" (e (str text " cget -state")))
            "an editable text stays editable")
      (t/is (= "1" (e (str text " cget -undo"))) "and it can undo")
      (t/is (= "hi" (e (str text " get 1.0 end-1c"))) "the value is its text")
      (e (str "focus -force " text))
      (e "update")
      (e (str "event generate " text " <KeyPress-a> -when tail"))
      (e (str "event generate " text " <KeyRelease-a> -when tail"))
      (e "update")
      (t/is (= ["hia"] @typed) ":on-change read the text the key had inserted")
      (e (str text " mark set insert 1.1"))
      (reset! value "hia")
      (rerender! h)
      (t/is (= "1.1" (e (str text " index insert")))
            "the value the widget already holds is not rewritten")
      (e (str "event generate " text " <Return> -when tail"))
      (e "update")
      (t/is (= ["hia"] @submitted) ":on-submit handed the text over")
      (t/is (= "hia" (e (str text " get 1.0 end-1c")))
            "Return did not also insert a newline (the slot ends in break)")
      (e (str text " edit undo"))
      (t/is (= "hi" (e (str text " get 1.0 end-1c"))) "the typed key undoes")
      (t/is (= "0" (e (str text " edit canundo")))
            "and the app's own value write is not an undo step")
      (e (str "event generate " text " <Shift-Return> -when tail"))
      (e "update")
      (t/is (= ["hia"] @submitted) "Shift-Return does not submit")
      (t/is (= "hi\n" (e (str text " get 1.0 end-1c")))
            "the more specific chord lets Tk insert the newline instead")
      (engine/dispose-component (:root h)))))

;; ─── shared variables (the variable-backed props) ────────────────────────

(t/deftest a-textvariable-carries-an-entry-s-text-both-ways
  ;; A real ttk entry bound to a kmet variable: the widget takes its text
  ;; from the variable, the user's typing lands in it (and its trace reaches
  ;; the watcher), and a kmet write reaches the widget — the two-way binding
  ;; the variable-based options exist for.
  (with-live-tk [e]
    (let [v (tk/variable "hi")
          seen (atom [])
          unwatch (tk/variable-watch! v #(swap! seen conj %))
          h (tk/mount! (fn [_] [:ttk/frame {} [:ttk/entry {:textvariable v}]]) {})
          w (do (e find-class-src) (e "kmetfind . TEntry"))]
      (t/is (seq w) "the entry is in the window")
      (t/is (= "hi" (e (str w " get"))) "its text starts as the variable's value")
      (e (str "focus -force " w))
      (e (str w " icursor end"))
      (e "update")
      (e (str "event generate " w " <KeyPress-a> -when tail"))
      (e (str "event generate " w " <KeyRelease-a> -when tail"))
      (e "update")
      (t/is (= "hia" (e (str w " get"))))
      (t/is (= "hia" (tk/variable-value v))
            "Tk wrote the typing back into the variable")
      (t/is (= ["hia"] @seen) "and the trace reached the watcher")
      (tk/variable-set! v "b y")
      (t/is (= "b y" (e (str w " get"))) "a kmet write reaches the widget")
      (t/is (= ["hia" "b y"] @seen)
            "and fires the watcher too — the value's space is one word")
      (t/is (= "b y" (tk/variable-value v))
            "the value round-trips through the trace unchanged")
      (unwatch)
      (tk/variable-dispose! v)
      (engine/dispose-component (:root h)))))

(t/deftest a-render-write-fires-a-watcher
  ;; The render writes the state a widget declares (:active) into its
  ;; variable, and that is a Tcl write like any other — a watch sees it
  ;; while the widget mounts, not only when the user or the app writes.
  (with-live-tk [e]
    (let [v (tk/variable 0)
          seen (atom [])]
      (tk/variable-watch! v #(swap! seen conj %))
      (let [h (tk/mount! [:ttk/frame {}
                          [:ttk/checkbutton {:text "a" :variable v :active true}]] {})]
        (e "update")
        (t/is (= ["1"] @seen) "the mount's own write reached the watcher")
        (engine/dispose-component (:root h))
        (tk/variable-dispose! v)))))

(t/deftest a-shared-variable-is-tk-s-radio-group
  ;; One variable shared by two radiobuttons is Tk's own exclusive group:
  ;; invoking one moves the variable, and the handler Tk calls reads the
  ;; group back — the render writes only the active button's value.
  (with-live-tk [e]
    (let [v (tk/variable :light)
          toggled (atom [])
          h (tk/mount! [:ttk/frame {}
                        [:ttk/radiobutton {:text "Light" :variable v :value :light
                                           :active true
                                           :on-toggled #(swap! toggled conj [:light %])}]
                        [:ttk/radiobutton {:text "Dark" :variable v :value :dark
                                           :on-toggled #(swap! toggled conj [:dark %])}]]
                       {})
          light (do (e find-class-src) (e "kmetfind . TRadiobutton"))
          dark (last (str/split (e (str "winfo children [winfo parent " light "]"))
                                #"\s+"))]
      (t/is (seq light) "the radiobutton is in the window")
      (t/is (= "light" (tk/variable-value v)) "the active button's value is selected")
      (e (str dark " invoke"))
      (t/is (= "dark" (tk/variable-value v))
            "invoking the sibling moves the whole group")
      (t/is (= [[:dark true]] @toggled)
            "only the invoked button's handler fires, and it reports itself selected")
      (e (str light " invoke"))
      (t/is (= "light" (tk/variable-value v)))
      (t/is (= [[:dark true] [:light true]] @toggled))
      (engine/dispose-component (:root h))
      (tk/variable-dispose! v))))

(t/deftest a-listvariable-drives-a-real-listbox-both-ways
  (with-live-tk [e]
    (let [v (tk/variable ["alpha" "beta"])
          h (tk/mount! [:tk/listbox {:listvariable v}] {})
          lb (do (e find-class-src) (e "kmetfind . Listbox"))]
      (t/is (seq lb) "the listbox is in the window")
      (t/is (= "alpha beta" (e (str lb " get 0 end"))))
      (tk/variable-set! v ["gamma"])
      (t/is (= "gamma" (e (str lb " get 0 end")))
            "a kmet write replaces the listbox's items")
      (e (str lb " insert end delta"))
      (t/is (= "gamma delta" (tk/variable-value v))
            "and a widget edit writes its list back into the variable")
      (engine/dispose-component (:root h))
      (tk/variable-dispose! v))))

;; ─── script-typed options ────────────────────────────────────────────────

(t/deftest a-real-entry-validates-its-edits
  ;; The validatecommand answers Tk from kmet: an allowed key is inserted,
  ;; a refused one leaves the entry as it was and runs the invalidcommand.
  ;; The handler sees %P, the value the entry would have.
  (with-live-tk [e]
    (let [proposed (atom [])
          refused (atom 0)
          h (tk/mount! (fn [_] [:ttk/frame {}
                                [:ttk/entry {:text ""
                                             :validate :key
                                             :validatecommand
                                             (fn [v] (swap! proposed conj v)
                                               (not (str/includes? v " ")))
                                             :invalidcommand
                                             (fn [_] (swap! refused inc) nil)}]])
                       {})
          w (do (e find-class-src) (e "kmetfind . TEntry"))]
      (t/is (seq w) "the entry is in the window")
      (e (str "focus -force " w))
      (e (str w " icursor end"))
      (e "update")
      (e (str "event generate " w " <KeyPress-a> -when tail"))
      (e (str "event generate " w " <KeyRelease-a> -when tail"))
      (e "update")
      (t/is (= "a" (e (str w " get"))))
      (e (str "event generate " w " <KeyPress-space> -when tail"))
      (e (str "event generate " w " <KeyRelease-space> -when tail"))
      (e "update")
      (t/is (= "a" (e (str w " get")))
            "the space the validator refused was not inserted")
      (t/is (= ["a" "a "] @proposed)
            "the handler saw the value the entry would have had")
      (t/is (= 1 @refused) "and the refusal ran the invalidcommand")
      (engine/dispose-component (:root h)))))

(t/deftest a-real-text-reports-its-scroll-fractions
  ;; A text widget's own -yscrollcommand: Tk calls it with the visible
  ;; fractions, which reach the handler as numbers.
  (with-live-tk [e]
    (let [seen (atom [])
          h (tk/mount! (fn [_] [:tk/text {:content (mapv (fn [i] {:text (str "line " i "\n")})
                                                         (range 60))
                                          :height 3
                                          :yscrollcommand
                                          (fn [fractions] (swap! seen conj fractions))}])
                       {})
          w (do (e find-class-src) (e "kmetfind . Text"))]
      (t/is (seq w) "the text is in the window")
      (t/is (str/includes? (e (str w " cget -yscrollcommand")) "kmet::cb"))
      (e "update")
      (e (str w " yview moveto 0.5"))
      (t/is (seq @seen) "moving the view called the command")
      (let [[first last] (last @seen)]
        (t/is (number? first))
        (t/is (number? last)))
      (engine/dispose-component (:root h)))))

(t/deftest a-real-menu-runs-its-post-command
  ;; A posted menu builds itself: tk_popup runs -postcommand, and the
  ;; handler gets no value (the menu's own state is the app's).
  (with-live-tk [e]
    (let [posted (atom 0)
          h (tk/mount! [:ttk/frame {}
                        [:tk/menu {:items [{:label "x"}]
                                   :postcommand (fn [] (swap! posted inc) nil)}]]
                       {})
          m (do (e find-class-src) (e "kmetfind . Menu"))]
      (t/is (seq m) "the menu is in the window")
      (e "update")
      (e (str "tk_popup " m " 10 10"))
      (e (str m " unpost"))
      (t/is (= 1 @posted) "posting the menu ran the command")
      (engine/dispose-component (:root h)))))

;; ─── the label widget (-labelwidget) ─────────────────────────────────────

(t/deftest a-real-labelframe-takes-a-child-as-its-label-widget
  ;; A labelframe whose label is a child widget: Tk holds the child as
  ;; -labelwidget, and the child is placed by no geometry manager — it is
  ;; the label, drawn in the frame's label position.
  (with-live-tk [e]
    (let [h (tk/mount! [:ttk/frame {}
                        [:ttk/labelframe {:padding 4}
                         [:ttk/frame {:labelwidget true :padding 2}
                          [:ttk/label {:text "Options"}]]]]
                       {})
          lf (do (e find-class-src) (e "kmetfind . TLabelframe"))
          label (when (seq lf) (e (str lf " cget -labelwidget")))]
      (t/is (seq lf) "the labelframe is in the window")
      (t/is (seq label) "it names a label widget")
      (t/is (= "TFrame" (e (str "winfo class " label))))
      (t/is (not (contains? #{"pack" "grid" "place"}
                            (e (str "winfo manager " label))))
            "a label widget is not placed by a layout manager")
      (t/is (= "TLabel" (e (str "winfo class "
                                (first (str/split (e (str "winfo children " label)) #"\s+")))))
            "the label frame holds the widget the render put in it")
      (engine/dispose-component (:root h)))))

;; ─── a binding that reads a value ────────────────────────────────────────

(t/deftest a-widget-bind-keeps-a-drag-s-coordinates
  ;; Tk directs a mouse event to the current item, so an item's :on-drag
  ;; stops at its edge. The canvas's own :bind with :value :pointer keeps
  ;; the coordinates coming — the drag that leaves the item, which is the
  ;; gap section 1 of tcl.md named.
  (with-live-tk [e]
    (let [seen (atom [])
          h (tk/mount! [:tk/canvas {:width 120 :height 80
                                    :items [{:id "box" :type :rect
                                             :coords [0 0 30 30] :fill "red"}]
                                    :bind {"<B1-Motion>" {:handler (fn [xy] (swap! seen conj xy))
                                                          :value :pointer}}}]
                       {})
          c (do (e find-class-src) (e "kmetfind . Canvas"))]
      (t/is (seq c) "the canvas is in the window")
      (e "update")
      (e (str "event generate " c " <Button-1> -x 10 -y 10 -when tail"))
      (e (str "event generate " c " <B1-Motion> -x 90 -y 60 -when tail"))
      (e "update")
      (t/is (= [[90 60]] @seen)
            "the drag's coordinates reached the widget's own binding")
      (engine/dispose-component (:root h)))))
