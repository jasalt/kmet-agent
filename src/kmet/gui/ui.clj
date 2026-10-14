(ns kmet.gui.ui
  "kmet's own Tk widgets: composites built out of the real widget classes of
   kmet.gui.tk (classic `:tk/…`) and kmet.gui.ttk (themed `:ttk/…`). Those
   namespaces hold only Tk widget classes; a kmet widget lives here, under
   its own `:ui/…` tags, and kmet.gui.ui/backend adds the whole set to the
   Tk backend's tag table — mount!/run! mount through it.

   A UI tag's spec has the same shape as a backend tag's: a :ctor, its
   :options, and a :lens/:attach when it places children itself — a custom
   :ctor where dsl/widget-spec is not enough."
  (:require [kmet.gui.tk :as tk]
            [kmet.gui.tk.dsl :as dsl]
            [kmet.gui.tk.options :as opt])
  (:refer-clojure :exclude [run!]))

;; ─── :ui/transcript ──────────────────────────────────────────────────────
;; One text widget hosts every child as an embedded window (each message is
;; its own widget, on its own stretched line), so the chat scrolls natively
;; — no canvas, and no drawing lines. `:attach` in the widget's :styles is
;; how kmet.gui.tk.dsl/make-widget learns to embed instead of pack.

(def ^:private transcript-options
  "The text widget's option families (the transcript host is a `text`)."
  (opt/options-for [:standard :geometry :bordered :highlighted :spaced :selectable
                    :fonted :text-body]
                   [:bg :fg :locale]))

(defn- make-transcript
  "Construct the :ui/transcript composite: one text widget whose embedded
   windows are the child widgets (each on its own line), plus its
   scrollbar. Text scrolling has no canvas coordinate limit, so this is the
   scroll container for long stacks (a chat transcript)."
  [spec props]
  (let [parent (dsl/parent-widget)
        text (dsl/alloc-path! parent)
        sb (dsl/alloc-path! parent)
        font (dsl/resolve-base-font props)
        w (dsl/->TkWidget text (:tag spec) (atom []) (atom {}) (atom {}))]
    (dsl/eval! (dsl/widget-script "text" text
                                  (merge {:wrap :none :height 1 :width 1 :padx 0 :pady 0
                                          :highlightthickness 0 :borderwidth 0 :takefocus 0
                                          :font (dsl/font-spec font {})}
                                         (dsl/tcl-options props (:options spec)))))
    (dsl/check-options! spec :ui/transcript text props)
    (dsl/eval! (str "ttk::scrollbar " sb " -orient vertical -command \"" text " yview\""))
    (dsl/eval! (str text " configure -yscrollcommand \"" sb " set\""))
    (when (:see-end props)
      (dsl/eval! (str "bind " text " <Map> {after idle {"
                      text " see end"
                      (when (:read-only props)
                        (str "; " text " configure -state disabled"))
                      "}}")))
    (swap! (:styles w) assoc
           :scrollbar sb
           :attach (fn [p _props]
                     (dsl/eval! (str text " window create end -window " p " -pady 2"))
                     (dsl/eval! (str text " insert end \"\\n\""))))
    (dsl/apply-bindings! w {} props (:bindings spec))
    (when parent
      (dsl/eval! (str "pack " sb " -side right -fill y"))
      (dsl/eval! (str "pack " text " -side left -fill "
                      (dsl/tcl-quote (dsl/tcl-word (or (:fill props) :both)))
                      (when-not (false? (:expand props)) " -expand 1"))))
    w))

(def ^:private specs
  "kmet's own Tk widgets: a spec per `:ui/…` tag (a Tk widget class plus
   whatever composite behavior its ctor needs)."
  {:ui/transcript
   {:ctor make-transcript
    :options transcript-options
    :lens (dsl/child-lens)}})

(def backend
  "The Tk backend with kmet's own widgets added to its tag table. Mount a
   tree through it with mount!/run!."
  (update tk/tk-backend :tags merge (dsl/widget-specs specs)))

(defn mount!
  "kmet.gui.tk/mount! with the kmet UI tag set (the backend above)."
  [tree & [options]]
  (tk/mount-backend! backend tree options))

(defn run!
  "kmet.gui.tk/run! with the kmet UI tag set (the backend above)."
  [tree & [options]]
  (tk/run-backend! backend tree options))
