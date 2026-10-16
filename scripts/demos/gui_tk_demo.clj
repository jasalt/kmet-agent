(ns gui-tk-demo
  "A small hiccup-tk demo: a Tk window rendered from hiccup through
   kmet.tk.core, with a background thread streaming lines into the view.

   Run with:  bb scripts/demos/gui_tk_demo.clj     (or jolt scripts/demos/gui_tk_demo.clj)

   Needs Tcl/Tk and a display. The interesting parts to copy:
   - the component tree is ordinary hiccup (fn component + host tags);
   - state lives in plain atoms, and any thread may update it —
     component/schedule-frame! marks the tree dirty and the Tk pump
     re-renders on the UI thread."
  (:require [clojure.string :as str]
            [kmet.tk.tcl :as tcl]
            [kmet.libs.hikap.component :as component]))

(defonce ^:private log (atom ["hiccup-tk ready"]))

(defn- append-line! [line]
  (swap! log conj line)
  ;; safe from any thread: run! installed the frame hook, the pump renders
  (component/schedule-frame!))

(defn app [_props]
  [:ttk/frame {:padding 8}
   [:ttk/label "kmet — hiccup-tk demo (close the window to exit)"]
   [:tk/text {:text (str/join "\n" @log)}]
   [:ttk/frame {}
    ;; pack geometry is the child's: :side is Tk's pack -side
    [:ttk/button {:text "Add line" :side :left
              :on-click #(append-line! (str "line " (inc (count @log))))}]
    [:ttk/button {:text "Clear" :side :left
              :on-click #(do (reset! log []) (component/schedule-frame!))}]]])

(defn -main [& _]
  ;; a simulated stream from a non-Tk thread
  (future
    (dotimes [i 5]
      (Thread/sleep 700)
      (append-line! (str "streamed " (inc i)))))
  (tcl/run! app {:title "kmet hiccup-tk" :width 640 :height 420}))

(-main)
