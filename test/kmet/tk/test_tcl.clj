(ns kmet.tk.test-tcl
  "Real host FFI Tcl adapter tests (babashka.ffi / jolt.ffi).

   The adapter loads libtcl at call time, so this namespace loads on both
   hosts and the tests skip where libtcl is absent (and on the JVM).
   The libtcl-absent start-up is covered by running it on a machine that
   has Tcl/Tk installed."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tk.test-support :refer [find-class-src live-tk rerender!]]
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
  (if-let [a (live-tk)]
    (try
      (let [e (:eval a)
            _ (e (str "proc optnames {w} { join [lmap o [$w configure] "
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
                missing (sort (remove names (map #(str "-" (name %))
                                                 (vals (:options spec)))))]
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
          (binding [tk/*adapter* a]
            (let [h (tk/mount! tree (props :tk/toplevel))]
              (t/is (some? h))
              (engine/dispose-component (:root h))))
          (t/is true "every tag accepted a widget with all its props set")))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(t/deftest a-checkable-and-a-scale-hand-their-value-to-the-handler
  ;; The value slots of the widgets added later: a checkable widget's private
  ;; variable is what its -command reads, and a scale's -command receives the
  ;; number Tk's set put there (Tk's own invoke/set drive both here).
  (if-let [a (live-tk)]
    (try
      (let [e (:eval a)
            got (atom nil)]
        (e find-class-src)
        (binding [tk/*adapter* a]
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
            (engine/dispose-component (:root h)))))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(t/deftest a-treeview-nests-rows-tags-and-a-tree-column
  ;; A real ttk::treeview: a row's :children nest under its own id, a row tag
  ;; reaches and styles the rows naming it, the tree column is configured
  ;; like any other column (and is not one of Tk's -columns), and the :open
  ;; and :selected the app renders are what Tk reports back.
  (if-let [a (live-tk)]
    (try
      (let [e (:eval a)
            _ (e "image create photo ::icon -width 8 -height 8")
            h (binding [tk/*adapter* a]
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
                           {}))
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
        (binding [tk/*adapter* a] (engine/dispose-component (:root h))))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(t/deftest install-leaves-tk-loaded-for-a-non-mounting-caller
  ;; kmet.tk.ttk.style scripts ttk::style between install! and mount, so
  ;; install! must load Tk itself instead of leaving it to the first mount.
  (if-let [a (live-tk)]
    (try
      ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))
      ((deref (ns-resolve 'kmet.tk.tcl 'install!)))
      (t/is (seq ((:eval a) "ttk::style theme use"))
            "Tk answers after install! alone")
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(t/deftest image-descriptions-make-real-photos
  ;; The image layer against a real Tk: a description creates a photo, the
  ;; sizing ops size it, a bitmap name passes through, and disposal deletes
  ;; the images the widget owned. The fixtures are 1x1 and 4x4 PNGs.
  (if-let [a (live-tk)]
    (try
      (binding [tk/*adapter* a]
        (let [e (:eval a)
              one (str "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8"
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
                "disposal deletes the photos the widget created")))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(t/deftest dialogs-reach-tk-and-answer-as-clojure-data
  ;; The dialogs without a user: each Tk command the backend scripts is
  ;; redefined as a proc that checks (or reshapes) its own arguments, so Tcl
  ;; parses the emitted scripts exactly as it would for the real dialog —
  ;; the -filetypes structure above all — and an answer rides back through
  ;; the same path a real one takes. The interpreter is deleted afterwards,
  ;; so the redefinitions do not outlive the test.
  (if-let [a (live-tk)]
    (try
      (let [e (:eval a)]
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
        (binding [tk/*adapter* a]
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
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(t/deftest an-editable-text-keeps-its-value-exactly
  ;; The value goes through tcl-quote and comes back out of Tcl's own
  ;; parser: a multi-line text whose lines carry Tcl metacharacters must
  ;; return unchanged, Tk's trailing newline excepted.
  (if-let [a (live-tk)]
    (try
      (let [e (:eval a)
            value "one\ntwo $x [y] {z} \"q\"\\"
            h (binding [tk/*adapter* a]
                (tk/mount! (fn [_] [:tk/text {:editable true :text value}]) {}))
            text (do (e find-class-src) (e "kmetfind . Text"))]
        (t/is (= value (e (str text " get 1.0 end-1c")))
              "the value round-trips through the Tcl parser unchanged")
        (t/is (= (str value "\n") (e (str text " get 1.0 end")))
              "Tk's own trailing newline is the only thing added")
        (binding [tk/*adapter* a] (engine/dispose-component (:root h))))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(t/deftest an-editable-text-types-submits-and-undoes
  ;; A real text widget driven through its own bindings: a typed key reaches
  ;; :on-change with the value read back, Return runs :on-submit without Tk's
  ;; class binding also inserting a newline, `edit undo` takes back the typed
  ;; key alone (a value the app wrote is not an undo step, so clearing a sent
  ;; composer cannot be undone back), and a re-render that passes the text
  ;; the widget already holds writes nothing. mount! is what registers the
  ;; kmet::cb command the bound scripts dispatch through.
  (if-let [a (live-tk)]
    (try
      (let [e (:eval a)
            value (atom "hi")
            typed (atom [])
            submitted (atom [])
            ;; the Shift-Enter recipe: a 0-arity no-op chord that suppresses
            ;; the <Return> slot and lets Tk's own binding insert the line
            no-op (fn [] nil)
            h (binding [tk/*adapter* a]
                (tk/mount! (fn [_] [:tk/text {:editable true :text @value
                                              :on-change (fn [v] (swap! typed conj v))
                                              :on-submit (fn [v] (swap! submitted conj v))
                                              :bind {"shift+enter" no-op}}])
                           {}))
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
        (binding [tk/*adapter* a] (rerender! h))
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
        (binding [tk/*adapter* a] (engine/dispose-component (:root h))))
      (finally
        ((deref (ns-resolve 'kmet.tk.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))
