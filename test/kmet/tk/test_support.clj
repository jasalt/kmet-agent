(ns kmet.tk.test-support
  "Shared scaffolding for the GUI test namespaces: a recording Tcl adapter
   (every eval captured as a script, commands and pumps recorded too), the
   engine mount helpers over a backend, and the live-Tk probe."
  (:require [kmet.tk.core :as tk]
            [kmet.tk.dsl :as dsl]
            [kmet.libs.hikap :as engine]))

(defn with-fake*
  "Call F with a recording adapter bound as tk/*adapter*; it sees
   {:scripts :commands :pumps}."
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
  "Run BODY with BINDING — `[rec nil]` — where REC is the recording
   adapter's {:scripts :commands :pumps}."
  [binding & body]
  (let [rec (first binding)]
    `(with-fake* (fn [~rec] ~@body))))

(defn scripts [rec] @(:scripts rec))
(defn scripts-like [rec re] (filterv #(re-find re %) (scripts rec)))

(defn mounted
  "Mount TREE through BACKEND, returning {:window :root}. With no BACKEND,
   kmet.tk.core/tk-backend."
  ([tree] (mounted tk/tk-backend tree))
  ([backend tree]
   (let [window (engine/compile-element backend [:tk/toplevel {:title "t"}])
         root (engine/root backend tree)]
     (binding [engine/*construct-context* {:parent window}]
       (engine/render-component root nil))
     {:window window :root root})))

(defn rerender!
  "Run one render pass for a `mounted` result."
  [{:keys [window root]}]
  (binding [engine/*construct-context* {:parent window}]
    (engine/render-component root nil)))

(defn live-tk
  "The real adapter with Tk initialized (a display), or nil: no libtcl, no
   libtk, or no display (a headless machine)."
  []
  (try
    (require 'kmet.tk.tcl)
    (let [a ((deref (ns-resolve 'kmet.tk.tcl 'adapter)))]
      ((:prepare! a))
      a)
    (catch Throwable _ nil)))

(defn stop-live-tk!
  "Stop the interpreter a live adapter installed, if one is running."
  []
  (when-let [stop (and (find-ns 'kmet.tk.tcl) (ns-resolve 'kmet.tk.tcl 'stop!))]
    ((deref stop))))

(defmacro with-live-tk
  "Run BODY with BINDING — [eval], the real Tcl adapter's eval — when a
   display with a working libtcl/libtk is there, stopping the interpreter
   afterwards; when there is none, the test reports the skip. The adapter
   is bound for the body, so it mounts and disposes without binding it
   again."
  [[eval-fn] & body]
  `(if-let [a# (live-tk)]
     (try
       (binding [tk/*adapter* a#]
         (let [~eval-fn (:eval a#)]
           ~@body))
       (finally
         (stop-live-tk!)))
     (clojure.test/is true "skipped: libtcl, libtk or a display is absent")))

(def find-class-src
  "Tcl source defining `kmetfind W CLASS`: the first descendant of W whose
   window class is CLASS (\"\" when none)."
  (str "proc kmetfind {w class} {\n"
       "  foreach c [winfo children $w] {\n"
       "    if {[winfo class $c] eq $class} { return $c }\n"
       "    set r [kmetfind $c $class]\n"
       "    if {$r ne \"\"} { return $r }\n"
       "  }\n"
       "  return \"\"\n"
       "}\n"))
