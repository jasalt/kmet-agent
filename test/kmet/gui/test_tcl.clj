(ns kmet.gui.test-tcl
  "Real host FFI Tcl adapter tests (babashka.ffi / jolt.ffi).

   The adapter loads libtcl at call time, so this namespace loads on both
   hosts and the tests skip where libtcl is absent (and on the JVM).
   The libtcl-absent start-up is covered by running it on a machine that
   has Tcl/Tk installed."
  (:require [clojure.test :as t]
            [kmet.gui.tk :as tk]))

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
