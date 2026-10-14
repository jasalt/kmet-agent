(ns kmet.gui.test-tcl
  "Real host FFI Tcl adapter tests (babashka.ffi / jolt.ffi).

   The adapter loads libtcl at call time, so this namespace loads on both
   hosts and the tests skip where libtcl is absent (and on the JVM).
   The libtcl-absent start-up is covered by running it on a machine that
   has Tcl/Tk installed."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.gui.tk :as tk]
            [kmet.gui.tk.options :as opt]
            [kmet.libs.hikap :as engine]))

(defn- live-adapter
  "The real Tcl adapter with libtcl loaded, or nil (JVM, or no libtcl)."
  []
  (try
    (require 'kmet.gui.tcl)
    (let [make (deref (ns-resolve 'kmet.gui.tcl 'adapter))
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
        ((deref (ns-resolve 'kmet.gui.tcl 'stop!)))))
    (t/is true "skipped: libtcl is absent (or this is the JVM)")))

(t/deftest loaded-pair-and-version
  ;; The adapter records the Tcl/Tk pair it chose, and the interpreter it
  ;; created reports its own patchlevel — together they say which of the
  ;; supported Tcl lines (8.6/9.0/9.1) this host is exercising.
  (if-let [a (live-adapter)]
    (try
      (let [libs (-> (ns-resolve 'kmet.gui.tcl 'libraries) deref deref)
            patchlevel ((:eval a) "info patchlevel")]
        (t/is (string? (:tcl libs)) "the loaded Tcl library is recorded")
        (t/is (seq (:tk-candidates libs))
              "the pair's Tk candidates are recorded")
        (t/is (re-matches #"\d+\.\d+.*" patchlevel)
              (str "a plausible Tcl patchlevel, got " (pr-str patchlevel)))
        (t/is (contains? #{"8.6" "9.0" "9.1"} (subs patchlevel 0 3))
              (str "a supported Tcl version, got " patchlevel)))
      (finally
        ((deref (ns-resolve 'kmet.gui.tcl 'stop!)))))
    (t/is true "skipped: libtcl is absent (or this is the JVM)")))

(t/deftest derived-tk-names-from-tcl-spelling
  ;; KMET_TCL_LIB without KMET_TK_LIB pairs with the Tk names derived
  ;; from the Tcl library's own spelling (see the adapter docstring).
  (if-let [dtk (try (require 'kmet.gui.tcl)
                    (deref (ns-resolve 'kmet.gui.tcl 'derived-tk-names))
                    (catch Throwable _ nil))]
    (do
      (t/is (= ["libtk8.6.so"] (dtk "libtcl8.6.so")))
      (t/is (= ["/opt/homebrew/opt/tcl-tk/lib/libtk9.1.dylib"]
               (dtk "/opt/homebrew/opt/tcl-tk/lib/libtcl9.1.dylib")))
      (t/is (= ["tk86t.dll"] (dtk "tcl86t.dll")))
      (t/is (= ["tk91.dll" "tcl9tk91.dll"] (dtk "tcl91.dll"))))
    (t/is true "skipped: the FFI adapter is unavailable")))

(t/deftest vwait-pump-and-nested-eval
  ;; The parked-eval path: Tcl_Eval (vwait) blocks while Tcl's timer calls
  ;; back into the trampoline, and the handler re-enters Tcl — exactly the
  ;; shape kmet.gui.tcl/run-loop! uses for the UI pump.
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
        ((deref (ns-resolve 'kmet.gui.tcl 'stop!)))))
    (t/is true "skipped: libtcl is absent (or this is the JVM)")))

;; ─── the tag table against a real Tk ──────────────────────────────────────

(def ^:private tag-widget
  "Our tags → the Tk creation command they script. (the mapping the GUI README's option audit records)."
  {:tk/toplevel "toplevel" :tk/transcript "text" :tk/frame "frame" :tk/text "text"
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
        ((deref (ns-resolve 'kmet.gui.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))

(def ^:private find-class-src
  "A Tcl helper: the first descendant of W whose class is CLASS."
  (str "proc kmetfind {w class} {\n"
       "  foreach c [winfo children $w] {\n"
       "    if {[winfo class $c] eq $class} { return $c }\n"
       "    set r [kmetfind $c $class]\n"
       "    if {$r ne \"\"} { return $r }\n"
       "  }\n"
       "  return \"\"\n"
       "}\n"))

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
        ((deref (ns-resolve 'kmet.gui.tcl 'stop!)))))
    (t/is true "skipped: libtcl, libtk or a display is absent")))
