(ns gui-tk-session
  "Display a saved kmet session in a Tk window using the GUI transcript
   components (kmet.agent.ui.gui.transcript): one Tk widget per message, in a
   scroll container, scrolled to the end once loaded.

   Display only: it loads the session file, renders it once, and shows it.
   No controls, no live updates, no session writes.

   Run:  bb scripts/demos/gui_tk_session.clj [session.ednl] [--width 110]
         jolt scripts/demos/gui_tk_session.clj [session.ednl] [--width 110]

   Without a path it opens the most recent session for the current
   directory. The window is sized for --width mono columns."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.agent.session :as session]
            [kmet.agent.ui.gui.style :as gui-style]
            [kmet.agent.ui.gui.transcript :as transcript]
            [kmet.agent.ui.gui.ui :as ui]
            [kmet.tk.tcl :as tcl]
            [kmet.libs.hikap.component :as component]
            [kmet.tui.theme :as theme]))

(defn- latest-session-file
  "The most recent session file for the current directory, or nil."
  []
  (let [dir (fs/path (fs/home) ".kmet" "sessions"
                     (session/encode-cwd (str (fs/cwd))))]
    (when (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(str/ends-with? (str %) ".ednl"))
           (sort-by str)
           last
           str))))

(defn- parse-args [args]
  (loop [args args, opts {:width 110 :font-size 12}]
    (if-let [a (first args)]
      (case a
        "--width" (recur (drop 2 args) (assoc opts :width (parse-long (second args))))
        "--font-size" (recur (drop 2 args) (assoc opts :font-size (parse-long (second args))))
        (recur (rest args) (assoc opts :session a)))
      opts)))

(defn- font-metrics
  "The mono family, size and pixel width of one character the transcript
   renders with, measured in the live interpreter (so the window is sized
   for --width columns instead of guessing an advance)."
  [a font-size]
  (let [ev (:eval a)
        family (ev "font actual TkFixedFont -family")
        size (or font-size (parse-long (ev "font actual TkFixedFont -size")))
        px (parse-long (ev (str "font measure [list {" family "} " size "] \"0\"")))]
    {:family family :size size :char-px (or px 8)}))

(defn -main [& args]
  (let [{:keys [session width font-size]} (parse-args args)
        file (or session (latest-session-file))]
    (when-not file
      (binding [*out* *err*]
        (println "No session found — pass a session .ednl path."))
      (System/exit 1))
    (let [a (tcl/adapter)
          _ ((:prepare! a))
          {:keys [family size char-px]} (font-metrics a font-size)
          s (session/load-session file)
          collapsed (atom #{})
          ctx {:width width
               :font-family family
               :font-size size
               :theme (theme/get-current-theme)
               :bg (transcript/palette :window)
               :fg "#d7dbe0"
               ;; clicking a card title collapses/expands its body; the
               ;; pump re-renders on the next tick (schedule-frame!)
               :collapsed collapsed
               :toggle (fn [id]
                         (swap! collapsed (fn [s]
                                            (if (contains? s id)
                                              (disj s id)
                                              (conj s id))))
                         (component/schedule-frame!))}]
      (binding [*out* *err*]
        (println (str "kmet session viewer — " file))
        (println (str "  " (count @(:entries s)) " entries, "
                      family " " size ", " char-px "px/char")))
      (tcl/install!)
      (gui-style/apply-theme! (:theme ctx))
      (ui/run! (transcript/live-page s ctx)
               {:title (str "kmet session "
                            (subs (str (:id s)) 0 (min 8 (count (str (:id s))))))
                :width (max 640 (int (+ (* width char-px) 48)))
                :height 780
                :bg (transcript/palette :window)}))))

(apply -main *command-line-args*)
