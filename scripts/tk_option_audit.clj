(ns tk-option-audit
  "Audit the Tk tag table against the interpreter it will run on: every
   option a tag declares must be an option of its widget, and the report
   lists what the tags do not reach yet (options a widget has but no tag
   prop names, and widget classes with no tag at all).

   Needs Tcl/Tk and a display (it creates the widgets once to ask them).

   Run: bb scripts/tk_option_audit.clj
        bb scripts/tk_option_audit.clj --widgets   ; per-widget option dump

   Exits 1 when a tag declares an option its widget lacks."
  (:require [clojure.string :as str]
            [kmet.gui.tcl :as tcl]
            [kmet.gui.tk :as tk]))

(def ^:private tag-widget
  "Our tags → the Tk creation command they script."
  {:tk/toplevel "toplevel" :tk/transcript "text" :tk/frame "frame" :tk/text "text"
   :ttk/frame "::ttk::frame" :ttk/labelframe "::ttk::labelframe" :ttk/label "::ttk::label"
   :ttk/button "::ttk::button" :ttk/entry "::ttk::entry" :ttk/checkbutton "::ttk::checkbutton"
   :ttk/separator "::ttk::separator" :ttk/progressbar "::ttk::progressbar"})

(def ^:private classic-candidates
  "The documented classic Tk widget classes (dialogs such as tk_messageBox
   are commands, not widgets)."
  ["button" "canvas" "checkbutton" "entry" "frame" "label" "labelframe" "listbox"
   "menu" "menubutton" "message" "panedwindow" "radiobutton" "scale" "scrollbar"
   "spinbox" "text" "toplevel"])

(def ^:private deprecated-helpers
  "ttk commands that resolve to a widget but are deprecated aliases."
  #{"::ttk::paned"})

(def ^:private probe-src
  (str "proc tkoptionprobe {creator path} {\n"
       "  if {[catch {$creator $path} err]} { return \"ERR\\t$err\" }\n"
       "  if {![winfo exists $path]} { return \"NOWIN\" }\n"
       "  set out [list \"CLASS\\t[winfo class $path]\"]\n"
       "  foreach o [$path configure] {\n"
       "    lappend out \"OPT\\t[lindex $o 0]\\t[lindex $o 1]\"\n"
       "  }\n"
       "  catch {destroy $path}\n"
       "  catch {image delete $path}\n"
       "  return [join $out \"\\n\"]\n"
       "}\n"))

(defn- probe!
  "Create CREATOR's widget once at PATH: {:class str :options #{opt}
   :aliases #{opt}} — or {:error str} / {:nowin true}. Tk lists an option's
   aliases (-bd/-bg/-fg) as options too; they carry the canonical option in
   the dbName column, so they are split out."
  [e creator path]
  (let [out (e (str "tkoptionprobe " creator " " path))]
    (cond
      (str/starts-with? out "ERR\t") {:error (subs out 4)}
      (= out "NOWIN") {:nowin true}
      :else (let [rows (keep (fn [l]
                               (when (str/starts-with? l "OPT\t")
                                 (let [parts (str/split (subs l 4) #"\t")
                                       opt (first parts)
                                       db (second parts)]
                                   {:opt opt :alias? (str/starts-with? (or db "") "-")})))
                             (str/split-lines out))]
              {:class (some->> (str/split-lines out)
                               (some #(when (str/starts-with? % "CLASS\t") (subs % 6))))
               :options (into #{} (comp (remove :alias?) (map :opt)) rows)}))))

(defn- inventory
  "Probe every widget class this interpreter has."
  [e]
  (let [n (atom 0)
        path (fn [] (str ".audit" (swap! n inc)))
        ttk-names (->> (str/split-lines (e "join [lsort [info commands ::ttk::*]] \"\\n\""))
                       (map str/trim)
                       (filter #(re-matches #"::ttk::[a-z]+" %))
                       (remove deprecated-helpers))]
    (concat
     (for [creator (filter (fn [c] (= "1" (e (str "llength [info commands " c "]"))))
                           classic-candidates)]
       {:creator creator :set :classic :probe (probe! e creator (path))})
     (for [creator ttk-names]
       {:creator creator :set :ttk :probe (probe! e creator (path))}))))

(defn- audit [e]
  (let [inv (inventory e)
        widgets (into {} (keep (fn [{:keys [creator probe]}]
                                 (when (:options probe) [creator probe])))
                      inv)
        tags (:tags tk/tk-backend)
        rows (for [[tag spec] (sort-by (comp str key) tags)
                   :let [creator (get tag-widget tag)
                         w (get widgets creator)]]
               (if (nil? w)
                 {:tag tag :creator creator :no-probe true}
                 (let [declared (into #{} (map #(str "-" (name %)))
                                      (concat (vals (:options spec))
                                              (keep :option (vals (:bindings spec)))))
                       known (:options w)]
                   {:tag tag :creator creator :class (:class w)
                    :declared (count declared)
                    :missing (vec (sort (remove known declared)))
                    :unreached (vec (sort (remove declared known)))})))
        missing-widgets (for [{:keys [creator set probe]} inv
                              :when (and (:options probe)
                                         (not-any? #(= creator (tag-widget %)) (keys tags)))]
                          {:creator creator :set set :n (count (:options probe))})
        accepted (->> inv (keep (comp :options :probe)) (apply concat) frequencies)]
    {:rows rows
     :widgets widgets
     :missing-widgets missing-widgets
     :accepted accepted
     :patchlevel (str/trim (e "info patchlevel"))}))

(defn- -main [args]
  (let [_ (tcl/mount! [:ttk/label {:text "audit"}] {:title "kmet tk option audit"})
        e (:eval (tcl/adapter))
        _ (e probe-src)
        {:keys [rows widgets missing-widgets accepted patchlevel]} (audit e)
        broken (filter (comp seq :missing) rows)
        widget-dump? (some #{"--widgets"} args)]
    (println "Tk" patchlevel "—" (count widgets) "widget classes\n")
    (println "tag                    widget               declared  missing  unreached")
    (doseq [{:keys [tag creator declared missing unreached no-probe]} rows]
      (println (format "%-22s %-20s %8s  %7s  %9s"
                       (str tag) (str creator)
                       (if no-probe "?" (str declared))
                       (if no-probe "?" (str (count missing)))
                       (if no-probe "?" (str (count unreached)))))
      (when (seq missing)
        (println (str "    DECLARES what the widget lacks: " (str/join " " missing))))
      (when (seq unreached)
        (println (str "    not reached: " (str/join " " unreached)))))
    (println "\nwidget classes with no tag:")
    (doseq [{:keys [creator set n]} (sort-by (juxt :set :creator) missing-widgets)]
      (println (format "  %-8s %-24s %3d options" (name set) creator n)))
    (println "\noptions of no tag (widget count):"
             (str/join " " (for [[o n] (sort-by (comp - val) accepted)
                                 :when (>= n 5)]
                             (str o "(" n ")"))))
    (when widget-dump?
      (println "\nper-widget options:")
      (doseq [[creator w] (sort-by key widgets)]
        (println (str "\n" creator " (" (:class w) "): "
                      (str/join " " (sort (:options w)))))))
    (when (seq broken)
      (println "\nFAIL:" (count broken) "tag(s) declare options their widget lacks"))
    (e "destroy .")
    (tcl/stop!)
    (shutdown-agents)
    (when (seq broken) (System/exit 1))))

(-main *command-line-args*)
