(ns gui-tk-demo
  "A small hiccup-tk demo: a Tk window rendered from hiccup through
   kmet.gui.tk, with a background thread streaming lines into the view.

   Run with:  jolt scripts/gui_tk_demo.clj

   Needs Tcl/Tk and a display. The interesting parts to copy:
   - the component tree is ordinary hiccup (fn component + host tags);
   - state lives in plain atoms, and any thread may update it —
     component/schedule-frame! marks the tree dirty and the Tk pump
     re-renders on the UI thread."
  (:require [clojure.string :as str]
            [kmet.gui.tcl :as tcl]
            [kmet.libs.hikap.component :as component]))

(defonce ^:private log (atom ["hiccup-tk ready"]))

(defn- append-line! [line]
  (swap! log conj line)
  ;; safe from any thread: run! installed the frame hook, the pump renders
  (component/schedule-frame!))

(defn app [_props]
  [:vbox {:padding 8}
   [:label "kmet — hiccup-tk demo (close the window to exit)"]
   [:text {:text (str/join "\n" @log)}]
   [:hbox {}
    [:button {:label "Add line"
              :on-click #(append-line! (str "line " (inc (count @log))))}]
    [:button {:label "Clear"
              :on-click #(do (reset! log []) (component/schedule-frame!))}]]])

(defn -main [& _]
  ;; a simulated stream from a non-Tk thread
  (future
    (dotimes [i 5]
      (Thread/sleep 700)
      (append-line! (str "streamed " (inc i)))))
  (tcl/run! app {:title "kmet hiccup-tk" :width 640 :height 420}))

(-main)
