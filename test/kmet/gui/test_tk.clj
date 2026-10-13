(ns kmet.gui.test-tk
  "Headless tests for the Tk backend (\"hiccup-tk\").

   A recording adapter stands in for a Tcl/Tk interpreter: every eval is
   captured as a script string and command registrations go to an atom, so
   the full engine → backend → script path is exercised without a display.
   The real jolt.ffi adapter (kmet.gui.tcl) is smoke-tested separately on a
   machine with Tcl/Tk."
  (:require [clojure.test :as t]
            [kmet.gui.tk :as tk]
            [kmet.libs.hikap.component :as component]
            [kmet.libs.hikap :as engine]))

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

(t/deftest display-leaf-rebuilds-on-change
  (with-fake [rec nil]
    (let [a (atom "one")
          m (mounted (fn [_] [:vbox {} [:label @a]]))]
      (reset! a "two")
      (rerender! m)
      (t/is (pos? (count (scripts-like rec #"^destroy ")))
            "a display leaf is identity-free: changed props rebuild it")
      (t/is (some #(re-find #"-text two" %) (scripts rec)))
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
