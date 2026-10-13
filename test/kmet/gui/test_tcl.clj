(ns kmet.gui.test-tcl
  "Real jolt.ffi Tcl adapter tests — Jolt-only.

   The adapter is a .jolt source (not loadable on bb/JVM), so this
   namespace loads on both hosts and every test skips where there is no
   Jolt. When libtcl itself is missing the one test skips too, with the
   Jolt-side start-up covered by `jolt test` on a machine that has Tcl
   installed."
  (:require [clojure.test :as t]
            [kmet.gui.tk :as tk]
            [kmet.libs.host :as host]))

(defn- live-adapter
  "The real Tcl adapter with libtcl loaded, or nil (bb/JVM, or no libtcl)."
  []
  (when (host/jolt?)
    (require 'kmet.gui.tcl)
    (try
      (let [make (deref (ns-resolve 'kmet.gui.tcl 'adapter))
            a (make)]
        ;; Forces the libtcl load; a missing library must skip, not fail.
        ((:eval a) "expr {1 + 2}")
        a)
      (catch Throwable _ nil))))

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
    (t/is true "skipped: the Tcl adapter is Jolt-only (or libtcl is absent)")))
