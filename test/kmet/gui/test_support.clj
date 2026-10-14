(ns kmet.gui.test-support
  "Shared scaffolding for the GUI test namespaces: a recording Tcl adapter
   (every eval captured as a script, commands and pumps recorded too), the
   engine mount helpers over a backend, and the live-Tk probe."
  (:require [kmet.gui.tk :as tk]
            [kmet.gui.tk.dsl :as dsl]
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
   kmet.gui.tk/tk-backend."
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
    (require 'kmet.gui.tcl)
    (let [a ((deref (ns-resolve 'kmet.gui.tcl 'adapter)))]
      ((:prepare! a))
      a)
    (catch Throwable _ nil)))
