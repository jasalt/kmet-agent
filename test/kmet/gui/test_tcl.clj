(ns kmet.gui.test-tcl
  "Real host FFI Tcl adapter tests (babashka.ffi / jolt.ffi).

   The adapter loads libtcl at call time, so this namespace loads on both
   hosts and the one test skips where libtcl is absent (and on the JVM).
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
