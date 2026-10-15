(ns kmet.tk.test-tk
  "Headless tests for the Tk backend (\"hiccup-tk\").

   A recording adapter stands in for a Tcl/Tk interpreter: every eval is
   captured as a script string and command registrations go to an atom, so
   the full engine → backend → script path is exercised without a display.
   The real jolt.ffi adapter (kmet.tk.tcl) is smoke-tested separately on a
   machine with Tcl/Tk."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tk.test-support :refer [mounted rerender! scripts scripts-like with-fake]]
            [kmet.tk.core :as tk]
            [kmet.tk.dsl :as dsl]
            [kmet.tk.options :as opt]
            [kmet.libs.hikap :as engine]
            [kmet.libs.hikap.alias :as alias]
            [kmet.libs.hikap.component :as component]
            [kmet.libs.reakt :as r]))

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
    (tk/run! (fn [_] [:ttk/frame {:padding 4}
                      [:ttk/label "hello"]
                      [:ttk/button {:text "Go" :on-click (constantly nil)}]])
             {:title "kmet" :width 320 :height 200})
    (let [ss (scripts rec)]
      (t/is (some #(= "wm title . kmet" %) ss))
      (t/is (some #(re-find #"ttk::frame \.w\d+ -padding 4" %) ss))
      (t/is (some #(re-find #"ttk::label \.w\d+\.w\d+ .*-text hello" %) ss))
      (t/is (some #(re-find #"ttk::button" %) ss))
      (t/is (some #(re-find #"pack .* -side top" %) ss))
      (t/is (some #(re-find #"-command \{kmet::cb \d+\}" %) ss))
      (t/is (= 1 (count @(:commands rec))) "kmet::cb trampoline registered"))))

(t/deftest bare-strings-become-labels
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {} "hi"])]
      (t/is (some #(re-find #"ttk::label .* -text hi" %) (scripts rec)))
      (engine/dispose-component root))))

(t/deftest tag-names-follow-tk
  ;; :tk/toplevel is the root window, :ttk/frame a ttk::frame, :ttk/labelframe
  ;; a ttk::labelframe, and pack geometry stays the child's (:side/:fill).
  (with-fake [rec nil]
    (let [m (mounted [:ttk/frame {}
                      [:ttk/labelframe {:text "Title"} "x"]
                      [:ttk/label {:text "row" :side :left :fill :y}]])
          ss (scripts rec)]
      (t/is (some #(re-find #"^ttk::labelframe \.w[\w.]+ -text Title" %) ss))
      (t/is (some #(re-find #"^pack \.w[\w.]+ -side left -fill y" %) ss)
            "the child's :side is Tk's pack -side")
      (engine/dispose-component (:root m)))))

(t/deftest qualified-tags-are-host-tags-not-aliases
  ;; kmet.tk's tags carry Tcl's namespace split, so they are qualified: a
  ;; keyword the backend declares is a host tag and wins over the alias
  ;; registry; every other qualified keyword is still an alias lookup.
  (let [called (atom nil)
        unregister (alias/register! :tk/frame
                                    (fn [_ _] (reset! called true) nil))]
    (try
      (with-fake [rec nil]
        (let [{:keys [root]} (mounted [:tk/frame {}])]
          (t/is (some #(re-find #"^frame " %) (scripts rec))
                "the declared :tk/frame renders the classic frame")
          (t/is (nil? @called) "the alias fn never ran")
          (engine/dispose-component root)))
      (finally (unregister))))
  (with-fake [_rec nil]
    (t/is (= :ttk/nope
             (try (mounted [:ttk/nope {}]) nil
                  (catch Exception e (:alias (ex-data e)))))
          "an undeclared qualified keyword is still an alias")))

;; ─── props, patching and rebuilds ─────────────────────────────────────────

(t/deftest entry-text-patches-in-place
  (with-fake [rec nil]
    (let [a (atom "one")
          m (mounted (fn [_] [:ttk/frame {} [:ttk/entry {:text @a}]]))]
      (t/is (some #(re-find #"insert 0 one" %) (scripts rec)))
      (let [before (count (scripts-like rec #"^destroy "))]
        (reset! a "two")
        (rerender! m)
        (t/is (= before (count (scripts-like rec #"^destroy ")))
              "an apply-path leaf is patched, never rebuilt")
        (t/is (some #(re-find #"insert 0 two" %) (scripts rec))))
      (engine/dispose-component (:root m)))))

(t/deftest label-patches-what-it-can-and-declines-what-it-cannot
  (with-fake [rec nil]
    (let [text (atom "one")
          bold (atom false)
          fill (atom :both)
          m (mounted (fn [_] [:ttk/frame {} [:ttk/label {:text @text :bold @bold
                                                         :fill @fill}]]))]
      ;; a text change is a Tcl option → patch in place, keep the instance
      (reset! text "two")
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "a patchable prop change keeps the widget")
      (t/is (some #(re-find #"-text two" %) (scripts rec)))
      ;; the font is an option too → a bold flip is a live configure
      (reset! bold true)
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "a font change patches the label in place")
      (t/is (some #(re-find #"configure -font" %) (scripts rec)))
      ;; pack geometry is applied at construction only → decline, rebuild
      (reset! fill :y)
      (rerender! m)
      (t/is (pos? (count (scripts-like rec #"^destroy ")))
            "a pack change declines and rebuilds the label")
      (t/is (some #(re-find #"-fill y" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

(t/deftest label-rebinds-a-changed-click-callback
  (with-fake [rec nil]
    (let [a (atom (fn [] :first))
          m (mounted (fn [_] [:ttk/frame {} [:ttk/label {:text "x" :on-click @a}]]))]
      (t/is (some #(re-find #"<Button-1>" %) (scripts rec)))
      (let [before (count (scripts-like rec #"^destroy "))]
        (reset! a (fn [] :second))
        (rerender! m)
        (t/is (= before (count (scripts-like rec #"^destroy ")))
              "a changed callback rebinds instead of rebuilding")
        (let [ids (mapv #(second (re-find #"kmet::cb (\d+)" %))
                        (scripts-like rec #"<Button-1>"))
              [id1 id2] (mapv parse-long ids)]
          (t/is (= 2 (count ids)))
          (t/is (not= id1 id2) "the new binding uses a fresh command")
          (t/is (= "" (tk/dispatch-callback! ["kmet::cb" (str id1)]))
                "the replaced callback is released, not left dispatchable")
          (t/is (= ":second" (tk/dispatch-callback! ["kmet::cb" (str id2)])))))
      (engine/dispose-component (:root m)))))

(t/deftest keyed-children-reuse-identity
  (with-fake [rec nil]
    (let [a (atom ["a" "b" "c"])
          m (mounted (fn [_]
                       [:ttk/frame {}
                        (map-indexed (fn [_i s]
                                       ^{:key s}
                                       [:ttk/label {:text (str s "!")}])
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

;; ─── the option catalogue ─────────────────────────────────────────────────

(t/deftest a-tags-options-are-its-widgets-tk-option-set
  ;; :options composes families from kmet.tk.options with the widget's
  ;; own options, so a tag accepts exactly the options its widget has.
  (let [tags (:tags tk/tk-backend)]
    (t/is (= {:class :class :cursor :cursor :takefocus :takefocus
              :style :style :orient :orient}
             (:options (tags :ttk/separator)))
          "three families plus :orient")
    (t/is (= {:colormap :colormap :class :class :container :container
              :visual :visual :cursor :cursor :takefocus :takefocus
              :width :width :height :height :bg :background
              :backgroundimage :backgroundimage :tile :tile :relief :relief
              :borderwidth :borderwidth :highlightbackground :highlightbackground
              :highlightcolor :highlightcolor
              :highlightthickness :highlightthickness :padx :padx :pady :pady}
             (:options (tags :tk/frame))))
    (t/is (= :background (get-in (tags :ttk/entry) [:options :bg]))
          "the themed entry reaches Tk's -background/-foreground")
    (t/is (= :mode (get-in (tags :ttk/progressbar) [:options :mode])))
    (t/is (not (contains? (:options (tags :tk/text)) :state))
          "the :text tags own -state: the tag sets it, a prop never does")))

(t/deftest families-and-props-are-checked-at-load
  (t/is (thrown-with-msg? Exception #"unknown families"
                          (opt/options-for [:standard :nope])))
  (t/is (thrown-with-msg? Exception #"outside the catalogue"
                          (opt/options-for [:standard] [:nope]))))

(t/deftest list-valued-props-render-as-tcl-lists
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {:padding [6 12]}
                                   [:tk/text {:tabs [40 80]}]
                                   [:ttk/spinbox {:values ["a b" "c"]}]])]
      (t/is (some #(str/includes? % "-padding 6\\ 12") (scripts rec))
            "a :padding pair is one Tcl list word")
      (t/is (some #(str/includes? % "-tabs 40\\ 80") (scripts rec)))
      (t/is (some #(str/includes? % (str "-values " (dsl/tcl-quote "a\\ b c")))
                  (scripts rec))
            "an element with a space stays one element of the list")
      (engine/dispose-component root))))

(t/deftest an-empty-value-is-a-tcl-word-not-nothing
  ;; no whitespace is an option's value in Tcl: an empty one must be an
  ;; explicit empty word, or the option after it becomes its value
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/entry {:show "" :state :normal}])]
      (t/is (some #(str/includes? % "-show {} -state normal") (scripts rec)))
      (engine/dispose-component root))))

(t/deftest creation-only-props-rebuild-while-options-patch
  (let [spec (get (:tags tk/tk-backend) :tk/frame)]
    (t/is (not (dsl/patchable? spec {:class "A"} {:class "B"}))
          "-class is creation-only in Tk: a change rebuilds")
    (t/is (dsl/patchable? spec {:bg "#111111"} {:bg "#222222"}))
    (t/is (dsl/patchable? spec {:width 10} {:width 20}))
    (t/is (not (dsl/patchable? spec {:side :top} {:side :left}))
          "pack geometry is not a Tk option: a change rebuilds"))
  (t/is (not (dsl/patchable? (get (:tags tk/tk-backend) :tk/text)
                             {:editable false} {:editable true}))
        ":editable decides how :text is applied and what the widget is: a change rebuilds"))

(t/deftest options-this-tk-lacks-fail-loudly
  ;; check-options! asks the widget for its own option list, so a prop whose
  ;; option this Tk does not have fails with tag, prop and option named
  ;; instead of scripting a broken widget.
  (let [adapter {:eval (fn [s] (if (str/includes? s " configure]")
                                 "-text\n-cursor\n-takefocus\n-class\n-style\n"
                                 ""))
                 :command! (fn [_ _] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (t/is (thrown-with-msg? Exception #"no -background"
                              (mounted [:ttk/label {:text "ok" :bg "#101014"}])))
      (dsl/forget-widget-options!)
      (t/is (some? (mounted [:ttk/label {:text "ok" :cursor "hand2"}]))
            "a prop whose option exists is accepted"))))

(t/deftest a-widget-class-this-tk-lacks-fails-with-the-tag
  ;; ttk::toggleswitch is Tk 9.0+; on 8.6 creation fails inside Tcl, before
  ;; any option check. make-widget rethrows it with the tag and patchlevel.
  (let [adapter {:eval (fn [s]
                         (cond
                           (str/includes? s "ttk::toggleswitch")
                           (throw (ex-info "kmet.tk.tcl: invalid command name \"ttk::toggleswitch\""
                                           {:type :kmet.tk.tcl/eval-failed}))
                           (str/includes? s "info patchlevel") "8.6.16"
                           :else ""))
                 :command! (fn [_ _] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (let [e (try
                (mounted [:ttk/frame {} [:ttk/toggleswitch {}]])
                nil
                (catch Exception e e))]
        (t/is (= :kmet.tk.core/missing-widget (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":ttk/toggleswitch"))
        (t/is (str/includes? (ex-message e) "ttk::toggleswitch"))
        (t/is (str/includes? (ex-message e) "8.6.16"))))))

(t/deftest the-newly-declared-options-reach-their-widgets  (with-fake [rec nil]
                                                             (let [{:keys [root]} (mounted [:ttk/frame {}
                                                                                            [:ttk/entry {:bg "#101014" :show "*" :justify :right
                                                                                                         :state :readonly}]
                                                                                            [:ttk/separator {:orient :horizontal}]
                                                                                            [:ttk/progressbar {:mode :indeterminate :length 120}]
                                                                                            [:tk/text {:undo false :selectbackground "#333333"}]])]
                                                               (t/is (some #(str/includes? % "-background \\#101014") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-show *") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-state readonly") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-orient horizontal") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-mode indeterminate") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-selectbackground \\#333333") (scripts rec)))
                                                               (t/is (some #(str/includes? % "-undo 0") (scripts rec)))
                                                               (engine/dispose-component root))))

(t/deftest a-text-font-change-patches-and-rebases-the-generated-tags
  ;; the :tk/text widget's mono font is an option like any other, and the
  ;; Tk tags it generates for styled segments derive from it
  (with-fake [rec nil]
    (let [size (atom 12)
          m (mounted (fn [_] [:tk/text {:text "hi" :font-size @size}]))]
      (reset! size 14)
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "a font change patches the text widget in place")
      (t/is (some #(re-find #"configure -font" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

;; ─── images ──────────────────────────────────────────────────────────────

(defn- script-index
  "The index of the first recorded script matching RE, or nil."
  [rec re]
  (first (keep-indexed (fn [i s] (when (re-find re s) i)) (scripts rec))))

(t/deftest an-image-description-becomes-a-widget-owned-photo
  ;; A description map creates the photo before the widget that names it
  ;; (the Tk command needs the name at creation), and disposal deletes it.
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:tk/label {:text "logo" :image {:file "logo.png"}}]])
          ss (scripts rec)
          name (some->> (keep #(second (re-find #"^image create photo (::kmet_img_\d+) -file logo\.png$" %)) ss)
                        first)]
      (t/is (some? name) "the photo is created from the file")
      (t/is (some #(str/includes? % (str "-image " name)) ss)
            "the label names the created photo")
      (t/is (< (script-index rec #"^image create photo")
               (script-index rec #"^label "))
            "the photo exists before the widget names it")
      (engine/dispose-component root)
      (t/is (some #(= (str "image delete " name) %) (scripts rec))
            "disposal deletes the photo"))))

(t/deftest an-image-name-passes-through-and-a-description-is-reused
  (with-fake [rec nil]
    (let [img (atom "::app::logo")
          m (mounted (fn [_] [:ttk/frame {} [:ttk/label {:text "x" :image @img}]]))]
      (t/is (some #(str/includes? % "-image ::app::logo") (scripts rec)))
      (t/is (not-any? #(re-find #"^image create" %) (scripts rec))
            "a name the app owns is not created")
      (reset! img {:file "a.png"})
      (rerender! m)
      (t/is (= 1 (count (scripts-like rec #"^image create photo"))))
      (rerender! m)
      (t/is (= 1 (count (scripts-like rec #"^image create photo")))
            "the same description reuses the cached photo")
      (engine/dispose-component (:root m)))))

(t/deftest image-sizing-scripts-zoom-subsample-and-fit
  ;; zoom/subsample are Tk's own integer ops; :fit asks the loaded image its
  ;; size and subsamples it to the box (never enlarging).
  (let [scripts (atom [])
        adapter {:eval (fn [s]
                         (swap! scripts conj s)
                         (cond
                           (str/includes? s "image width") "200"
                           (str/includes? s "image height") "100"
                           :else ""))
                 :command! (fn [_ _] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (let [{:keys [root]} (mounted [:ttk/frame {}
                                     [:tk/label {:image {:file "wide.png" :fit 100}}]
                                     [:tk/label {:image {:file "box.png" :fit {:width 50 :height 25}}}]
                                     [:tk/label {:image {:file "up.png" :zoom 3}}]
                                     [:tk/label {:image {:file "down.png" :subsample [2 3]}}]])]
        (t/is (some #(re-find #"copy ::kmet_img_\d+ -subsample 2 2 -shrink" %) @scripts)
              ":fit 100 subsamples the 200x100 photo in both axes")
        (t/is (some #(re-find #"copy ::kmet_img_\d+ -subsample 4 4 -shrink" %) @scripts)
              "a :fit box takes the factor of its hardest axis")
        (t/is (some #(re-find #"copy ::kmet_img_\d+ -zoom 3 3 -shrink" %) @scripts))
        (t/is (some #(re-find #"copy ::kmet_img_\d+ -subsample 2 3 -shrink" %) @scripts))
        (engine/dispose-component root)))))

(t/deftest fit-measures-what-zoom-and-subsample-produced
  ;; :fit applies after :zoom/:subsample, so its factor comes from the size
  ;; they produced, not the natural one.
  (let [scripts (atom [])
        zoomed? (atom false)
        adapter {:eval (fn [s]
                         (swap! scripts conj s)
                         (cond
                           (re-find #"-zoom " s) (do (reset! zoomed? true) "")
                           (str/includes? s "image width") (if @zoomed? "400" "200")
                           (str/includes? s "image height") (if @zoomed? "200" "100")
                           :else ""))
                 :command! (fn [_ _] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (let [{:keys [root]} (mounted [:ttk/frame {}
                                     [:tk/label {:image {:file "big.png" :zoom 2
                                                         :fit {:width 100}}}]])]
        (t/is (some #(re-find #"copy ::kmet_img_\d+ -subsample 4 4 -shrink" %) @scripts)
              ":fit 100 on the zoomed 400x200 takes factor 4")
        (engine/dispose-component root)))))

(t/deftest an-image-description-is-validated
  ;; a description that cannot be expressed fails naming the prop and, for
  ;; a typo, the key — instead of silently creating an empty or misshapen
  ;; image (a Tk bitmap has no copy subcommand to size it with).
  (with-fake [_rec nil]
    (let [err (fn [props]
                (try
                  (mounted [:ttk/frame {} [:tk/label props]])
                  nil
                  (catch Exception e e)))]
      (let [e (err {:image {:zoom 2}})]
        (t/is (= :kmet.tk.core/image-without-source (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":file"))
        (t/is (str/includes? (ex-message e) ":image")))
      (let [e (err {:image {:file "x.png" :fille "y.png"}})]
        (t/is (= :kmet.tk.core/unknown-image-key (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":fille")))
      (let [e (err {:bitmap {:file "x.xbm" :zoom 2}})]
        (t/is (= :kmet.tk.core/bitmap-cannot-be-sized (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":zoom"))))))

(t/deftest a-failed-construction-releases-the-images-it-created
  ;; the ctor script creates an image before check-options! can reject the
  ;; widget: a failed construction must not leave the photo behind
  (let [scripts (atom [])
        adapter {:eval (fn [s]
                         (swap! scripts conj s)
                         (if (str/includes? s " configure]")
                           "-text\n-cursor\n"
                           ""))
                 :command! (fn [_ _] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (t/is (thrown? Exception
                     (mounted [:ttk/frame {}
                               [:tk/label {:image {:file "x.png"} :bg "#fff"}]]))))
    (t/is (some #(re-find #"^image delete ::kmet_img_\d+$" %) @scripts)
          "the photo the failed widget created is released")))

(t/deftest a-bitmap-description-creates-a-bitmap-image
  ;; :bitmap is Tk's bitmap option, so a description there creates a bitmap
  ;; image; the photo props never do.
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:tk/checkbutton {:bitmap {:file "q.xbm"}
                                                     :selectimage {:file "s.xbm"}}]])]
      (t/is (some #(re-find #"^image create bitmap ::kmet_img_\d+ -file q\.xbm$" %) (scripts rec)))
      (t/is (some #(re-find #"^image create photo ::kmet_img_\d+ -file s\.xbm$" %) (scripts rec)))
      (engine/dispose-component root))))

(t/deftest a-background-image-description-creates-a-photo
  ;; :tile and :backgroundimage (the classic frame's Tk 9 surface options)
  ;; take a description like every other image prop.
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:tk/frame {:tile {:file "tile.png"}}])]
      (t/is (some #(re-find #"^image create photo ::kmet_img_\d+ -file tile\.png$" %) (scripts rec)))
      (t/is (some #(re-find #"-tile ::kmet_img_\d+" %) (scripts rec)))
      (engine/dispose-component root))))

(t/deftest a-changed-image-description-patches-the-widget
  (with-fake [rec nil]
    (let [img (atom {:file "a.png"})
          m (mounted (fn [_] [:ttk/frame {} [:ttk/label {:text "x" :image @img}]]))]
      (reset! img {:file "b.png"})
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "an image change is a configure, not a rebuild")
      (t/is (some #(re-find #"^\.w[\w.]+ configure -image ::kmet_img_\d+" %) (scripts rec)))
      (t/is (some #(re-find #"^image create photo ::kmet_img_\d+ -file b\.png$" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

(t/deftest canvas-image-items-own-their-photo
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:tk/canvas {:items [{:type :image :coords [10 20]
                                                         :image {:file "pic.png"}}
                                                        {:type :line :coords [0 0 9 9]}]}]])]
      (t/is (some #(re-find #"^image create photo ::kmet_img_\d+ -file pic\.png$" %) (scripts rec)))
      (t/is (some #(re-find #"create image 10 20 -image ::kmet_img_\d+ -tags i1$" %) (scripts rec)))
      (engine/dispose-component root)
      (t/is (some #(re-find #"^image delete ::kmet_img_\d+$" %) (scripts rec))))))

(t/deftest text-content-embeds-an-image-segment
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:tk/text {:content [{:text "before"}
                                                        {:image {:file "pic.png"} :pady 4}
                                                        {:text "after"}]}]])]
      (t/is (some #(re-find #"^image create photo ::kmet_img_\d+ -file pic\.png$" %) (scripts rec)))
      (t/is (some #(re-find #"image create end -image ::kmet_img_\d+ -pady 4" %) (scripts rec)))
      (engine/dispose-component root))))

;; ─── callbacks ────────────────────────────────────────────────────────────

(t/deftest click-dispatches-through-the-trampoline
  (with-fake [rec nil]
    (let [clicked (atom nil)]
      (mounted [:ttk/frame {}
                [:ttk/button {:text "Go" :on-click #(reset! clicked :yes)}]])
      (let [script (first (scripts-like rec #"-command \{kmet::cb \d+\}"))
            id (second (re-find #"kmet::cb (\d+)" script))]
        (t/is (some? id))
        (t/is (= ":yes" (tk/dispatch-callback! ["kmet::cb" id])))
        (t/is (= :yes @clicked))
        (t/is (string? (tk/dispatch-callback! ["kmet::cb" "999999"]))
              "unknown callback answers empty, not an error")))))

;; ─── dispose ──────────────────────────────────────────────────────────────

(t/deftest removing-a-callback-unbinds-it
  ;; A callback that leaves the tree must stop firing: the Tk binding is
  ;; cleared and its trampoline entry released.
  (with-fake [rec nil]
    (let [f (atom (fn [] :live))
          m (mounted (fn [_] [:ttk/frame {} [:ttk/label {:text "x" :on-click @f}]]))
          script (first (scripts-like rec #"bind "))
          path (second (re-find #"bind (\.w[\w.]+) <Button-1>" script))
          id (second (re-find #"kmet::cb (\d+)" script))]
      (t/is (= ":live" (tk/dispatch-callback! ["kmet::cb" id])))
      (reset! f nil)
      (rerender! m)
      (t/is (some #(= (str "bind " path " <Button-1> {}") %) (scripts rec))
            "the removed callback is unbound in Tk")
      (t/is (= "" (tk/dispatch-callback! ["kmet::cb" id]))
            "and released from the trampoline")
      (engine/dispose-component (:root m)))))

(t/deftest value-slots-receive-the-widget-value
  ;; :on-change/:on-submit get the entry's text — Tcl reads it when the
  ;; event fires — and :on-toggled gets the checked state as a boolean.
  (with-fake [rec nil]
    (let [typed (atom nil)
          sent (atom nil)
          m (mounted (fn [_] [:ttk/frame {}
                              [:ttk/entry {:text "abc"
                                           :on-change (fn [t] (reset! typed t) nil)
                                           :on-submit (fn [t] (reset! sent t) nil)}]]))
          id (fn [re] (second (re-find #"kmet::cb (\d+)" (first (scripts-like rec re)))))]
      (t/is (re-find #"\[(\.w[\w.]+) get\]" (first (scripts-like rec #"<KeyRelease>")))
            "the change event passes the entry's live text")
      (tk/dispatch-callback! ["kmet::cb" (id #"<KeyRelease>") "abcd"])
      (t/is (= "abcd" @typed))
      (tk/dispatch-callback! ["kmet::cb" (id #"<Return>") "abcd"])
      (t/is (= "abcd" @sent))
      (engine/dispose-component (:root m))))
  (with-fake [rec nil]
    (let [checked (atom ::none)
          m (mounted (fn [_] [:ttk/frame {}
                              [:ttk/checkbutton {:text "x" :active false
                                                 :on-toggled (fn [v] (reset! checked v) nil)}]]))
          script (first (scripts-like rec #"-command \{kmet::cb"))
          id (second (re-find #"kmet::cb (\d+)" script))]
      (t/is (re-find #"\[set ::kmet_check_" script)
            "the command reads the widget's private state variable")
      (tk/dispatch-callback! ["kmet::cb" id "1"])
      (t/is (true? @checked))
      (tk/dispatch-callback! ["kmet::cb" id "0"])
      (t/is (false? @checked))
      (engine/dispose-component (:root m)))))

(t/deftest bind-takes-chords-and-tracks-the-map
  ;; :bind binds arbitrary events by kmet chord (or a raw Tk pattern); a
  ;; chord that leaves the map is unbound again.
  (with-fake [rec nil]
    (let [hits (atom [])
          bs (atom {:ctrl+enter (fn [] (swap! hits conj :send) nil)
                    :escape (fn [] (swap! hits conj :cancel) nil)
                    "<Double-Button-1>" (fn [] nil)})
          m (mounted (fn [_] [:ttk/frame {} [:ttk/entry {:text "x" :bind @bs}]]))
          slot (fn [ev] (first (filter #(str/includes? % (str ev " ")) (scripts rec))))
          id (fn [ev] (second (re-find #"kmet::cb (\d+)" (slot ev))))]
      (t/is (some #(re-find #"<Control-Return>" %) (scripts rec)))
      (t/is (some #(str/includes? % "<Double-Button-1>") (scripts rec))
            "a Tk event pattern passes through")
      (tk/dispatch-callback! ["kmet::cb" (id "<Control-Return>")])
      (t/is (= [:send] @hits))
      (let [dropped (id "<Control-Return>")
            path (second (re-find #"bind (\.w[\w.]+) <Control-Return>" (slot "<Control-Return>")))]
        (reset! bs {:escape (fn [] nil)})
        (rerender! m)
        (t/is (some #(= (str "bind " path " <Control-Return> {}") %) (scripts rec))
              "the dropped chord is unbound")
        (t/is (= "" (tk/dispatch-callback! ["kmet::cb" dropped]))))
      (engine/dispose-component (:root m)))))

(t/deftest window-bind-binds-the-toplevel
  (with-fake [rec nil]
    (tk/run! (fn [_] [:ttk/frame {}]) {:title "k" :bind {:ctrl+q (fn [] nil)}})
    (t/is (some #(re-find #"^bind \. <Control-q> \{kmet::cb \d+\}$" %) (scripts rec)))))

(t/deftest keyword-prop-values-render-as-tcl-words
  ;; :wrap :none and :fill :both are Clojure data, not Tcl strings.
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {} [:tk/text {:text "x" :wrap :none :fill :both}]])]
      (t/is (some #(str/includes? % "-wrap none") (scripts rec)))
      (t/is (some #(str/includes? % "-fill both") (scripts rec)))
      (engine/dispose-component root))))

(t/deftest unknown-bind-chords-fail-loudly
  (with-fake [_rec nil]
    (t/is (= :kmet.tk.core/bad-chord
             (try
               (mounted [:ttk/frame {} [:ttk/label {:text "x" :bind {:hyper+unknown (fn [] nil)}}]])
               nil
               (catch Exception e (:type (ex-data e))))))))

;; ─── script-typed options ─────────────────────────────────────────────────

(t/deftest a-validate-command-answers-tk-s-boolean
  ;; Tk asks the validatecommand whether an edit is allowed, handing it %P
  ;; (the value the widget would have); the handler's answer is a Tcl
  ;; boolean — false and nil refuse the edit — and :invalidcommand is called
  ;; when the answer was no. Both are ordinary binding slots on the
  ;; trampoline.
  (with-fake [rec nil]
    (let [asked (atom [])
          invalid (atom 0)
          m (mounted (fn [_] [:ttk/frame {}
                              [:ttk/entry {:text "ab" :validate :key
                                           :validatecommand (fn [v]
                                                              (swap! asked conj v)
                                                              (when (not= v "abc") true))
                                           :invalidcommand (fn [_]
                                                             (swap! invalid inc)
                                                             nil)}]]))
          script-id (fn [option]
                      (second (re-find #"kmet::cb (\d+)"
                                       (first (scripts-like
                                               rec (re-pattern (str "configure -" option)))))))]
      (t/is (some #(re-find #"configure -validatecommand \{kmet::cb \d+ %P\}$" %)
                  (scripts rec))
            "the script asks Tk for %P")
      (t/is (some #(re-find #"configure -invalidcommand \{kmet::cb \d+ %P\}$" %)
                  (scripts rec)))
      (t/is (= "1" (tk/dispatch-callback! ["kmet::cb" (script-id "validatecommand") "abcd"]))
            "a truthy answer allows the edit")
      (t/is (= "0" (tk/dispatch-callback! ["kmet::cb" (script-id "validatecommand") "abc"]))
            "nil refuses it")
      (t/is (= ["abcd" "abc"] @asked) "the handler receives the proposed value")
      (t/is (= "" (tk/dispatch-callback! ["kmet::cb" (script-id "invalidcommand") "abc"]))
            "the invalid handler only reports")
      (t/is (= 1 @invalid))
      (engine/dispose-component (:root m)))))

(t/deftest a-scroll-command-hands-over-the-fractions
  ;; -xscrollcommand/-yscrollcommand outside the :scrollbar sugar: Tk calls
  ;; the trampoline with the two fractions itself, so the bound script reads
  ;; nothing and the handler gets them as numbers.
  (with-fake [rec nil]
    (let [seen (atom nil)
          m (mounted (fn [_] [:tk/text {:text "x" :yscrollcommand (fn [v] (reset! seen v))}]))
          script (first (scripts-like rec #"configure -yscrollcommand"))
          id (second (re-find #"kmet::cb (\d+)" script))]
      (t/is (re-find #"configure -yscrollcommand \{kmet::cb \d+\}$" script))
      (tk/dispatch-callback! ["kmet::cb" id "0.25" "0.75"])
      (t/is (= [0.25 0.75] @seen))
      (engine/dispose-component (:root m)))))

(t/deftest a-scrollbar-and-an-explicit-ycommand-are-refused
  ;; Both wire Tk's one -yscrollcommand: the scrollbar would silently win.
  (with-fake [_rec nil]
    (let [e (try (mounted [:tk/text {:text "x" :scrollbar true
                                     :yscrollcommand (fn [_])}])
                 nil
                 (catch Exception e e))]
      (t/is (= :kmet.tk.core/scrollbar-and-command (:type (ex-data e))))
      (t/is (str/includes? (ex-message e) ":yscrollcommand")))))

(t/deftest a-menu-runs-its-post-command
  ;; -postcommand asks a menu to build itself when it is posted; a combobox
  ;; takes one too (its popdown). Tk passes no value.
  (with-fake [rec nil]
    (let [posted (atom 0)
          m (mounted (fn [_] [:ttk/frame {}
                              [:tk/menu {:postcommand (fn [] (swap! posted inc))}]
                              [:ttk/combobox {:values ["a"]
                                              :postcommand (fn [] nil)}]]))]
      (let [script (first (scripts-like rec #"configure -postcommand"))
            id (second (re-find #"kmet::cb (\d+)" script))]
        (t/is (re-find #"configure -postcommand \{kmet::cb \d+\}$" script))
        (tk/dispatch-callback! ["kmet::cb" id])
        (t/is (= 1 @posted)))
      (t/is (= 2 (count (scripts-like rec #"configure -postcommand"))))
      (engine/dispose-component (:root m)))))

(t/deftest dispose-destroys-the-widget-and-releases-callbacks
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:ttk/label "bye"]
                                   [:ttk/button {:text "x" :on-click (constantly nil)}]])]
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
      (tk/run! (fn [_] [:ttk/frame {} [:ttk/label @a]]) {:title "p"}))
    (t/is (some #(re-find #"-text start" %) @scripts))
    (t/is (some #(re-find #"-text later" %) @scripts)
          "the scheduled pump re-rendered the tree")))

(t/deftest text-wrap-and-height
  (with-fake [rec nil]
    (let [m (mounted [:ttk/frame {}
                      [:tk/text {:text "one two three" :width 40 :height 1 :wrap "word"}]])
          ss (scripts rec)]
      (t/is (some #(str/includes? % "-wrap word") ss)
            "the widget wraps rather than clipping")
      (t/is (not-any? #(str/includes? % "count -displaylines") ss)
            "content that already fits costs no height round trip")
      (engine/dispose-component (:root m)))
    ;; a line longer than the widget wraps in Tk: the height must follow it
    (let [long (apply str (repeat 90 "x"))
          m (mounted [:ttk/frame {} [:tk/text {:text long :width 40 :height 1 :wrap "word"}]])
          ss (scripts rec)]
      (t/is (some #(str/includes? % "count -displaylines") ss)
            "a wrapped line re-sets -height from the display lines")
      (engine/dispose-component (:root m)))))

(t/deftest bordered-frame-uses-the-highlight-ring
  ;; -relief solid draws its border in the widget's own background, which is
  ;; invisible on a card over a similar canvas; the ring takes a colour.
  (with-fake [rec nil]
    (let [m (mounted [:ttk/frame {} [:tk/frame {:border 1 :border-color "#3b4757"} "x"]])
          ss (scripts-like rec #"^frame ")]
      (t/is (some #(str/includes? % "-highlightthickness 1") ss))
      (t/is (some #(str/includes? % "-highlightbackground \\#3b4757") ss))
      (t/is (some #(str/includes? % "-borderwidth 0") ss))
      (engine/dispose-component (:root m)))))

(t/deftest tracked-inputs-invalidate-the-mounted-body
  ;; A mounted component body that also reads tracked inputs must declare
  ;; them with tracked-deref; an untracked read of a plain atom caches the
  ;; tree and a click repaints nothing (the bug this pins).
  (with-fake [rec nil]
    (let [a (atom "one")
          bodies (atom 0)
          h (tk/mount! (fn [_]
                         (swap! bodies inc)
                         [:ttk/frame {} [:ttk/label (r/tracked-deref a)]]))]
      (t/is (= 1 @bodies))
      (reset! a "two")
      ((:pump h))
      (t/is (= 2 @bodies) "a tracked plain-atom read re-runs the body")
      (t/is (some #(re-find #"-text two" %) (scripts rec))
            "and the repaint reaches the widget")
      (tk/run-loop! h))))

(t/deftest mount-render-loop-splits-the-loop-for-the-repl
  (with-fake [rec nil]
    (let [a (atom "one")
          h (tk/mount! (fn [_] [:ttk/frame {} [:ttk/label @a]]))]
      (t/is (some #(re-find #"-text one" %) (scripts rec))
            "mount! renders the tree once")
      (let [before (count (scripts rec))]
        ((:pump h))
        (t/is (= before (count (scripts rec)))
              "an idle pump renders nothing"))
      ;; the REPL loop: edit, reload the namespace, render!
      (reset! a "two")
      (tk/render! h)
      ((:pump h))
      (t/is (some #(re-find #"-text two" %) (scripts rec))
            "render! + pump repaints with the new code")
      (tk/run-loop! h)
      (t/is (= 1 (count @(:pumps rec))) "run-loop! parks on the adapter's loop")
      (t/is (some #(= "destroy ." %) (scripts rec))
            "the loop's exit disposes the tree"))))

;; ─── the added widgets ───────────────────────────────────────────────────

(t/deftest every-tk-widget-class-is-a-tag
  ;; the classic and themed sets together cover every widget class the
  ;; interpreter has (the classic text widget hosts embedded child windows,
  ;; :tk/toplevel the window)
  (let [tags (:tags tk/tk-backend)]
    (t/is (= 37 (count tags)))
    (t/is (every? tags [:tk/button :tk/canvas :tk/checkbutton :tk/entry :tk/label
                        :tk/labelframe :tk/listbox :tk/menu :tk/menubutton :tk/message
                        :tk/panedwindow :tk/radiobutton :tk/scale :tk/scrollbar
                        :tk/spinbox :ttk/combobox :ttk/menubutton :ttk/notebook
                        :ttk/panedwindow :ttk/radiobutton :ttk/scale :ttk/scrollbar
                        :ttk/sizegrip :ttk/spinbox :ttk/toggleswitch :ttk/treeview]))
    (t/is (false? (get-in tags [:tk/menu :pack?]))
          "Tk refuses to let pack manage a menu")))

(t/deftest the-added-widgets-script-their-options
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:tk/button {:text "go" :default :active :repeatdelay 300}]
                                   [:tk/label {:text "hi" :wraplength 200}]
                                   [:tk/canvas {:scrollregion [0 0 100 100] :confine true}]
                                   [:tk/scale {:from 0 :to 10 :value 3 :orient :horizontal}]
                                   [:tk/scrollbar {:orient :vertical :position [0.2 0.4]}]
                                   [:ttk/scale {:from 0 :to 5 :value 2}]
                                   [:ttk/scrollbar {:orient :vertical :position [0.1 0.5]}]
                                   [:ttk/sizegrip {}]])]
      (t/is (some #(str/includes? % "-default active") (scripts rec)))
      (t/is (some #(str/includes? % "-repeatdelay 300") (scripts rec)))
      (t/is (some #(str/includes? % "-wraplength 200") (scripts rec)))
      (t/is (some #(str/includes? % "-scrollregion 0\\ 0\\ 100\\ 100") (scripts rec))
            "a canvas :scrollregion is one Tcl list word")
      (t/is (some #(str/includes? % "-confine 1") (scripts rec)))
      (t/is (some #(str/includes? % "-to 10") (scripts rec)))
      (t/is (some #(str/includes? % "set 0.2 0.4") (scripts rec))
            ":position sets the scrollbar's thumb")
      (engine/dispose-component root))))

(t/deftest a-checkable-widgets-state-lives-in-its-private-variable
  ;; Tk's -variable is not a prop: the tag owns one, which is the only state
  ;; a classic checkbutton can be read from (select/deselect, no query)
  (with-fake [rec nil]
    (let [active (atom false)
          m (mounted (fn [_] [:ttk/frame {} [:ttk/checkbutton {:text "agree"
                                                               :active @active}]]))]
      (t/is (some #(re-find #"-variable ::kmet_check_\S+" %) (scripts rec)))
      (t/is (some #(re-find #"set ::kmet_check_\S+ 0" %) (scripts rec))
            "an unchecked button's variable says so")
      (reset! active true)
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "flipping :active patches, it does not rebuild")
      (t/is (some #(re-find #"set ::kmet_check_\S+ 1" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

(t/deftest a-listbox-carries-items-and-a-selection
  (with-fake [rec nil]
    (let [items (atom ["alpha" "beta"])
          selected (atom [1])
          m (mounted (fn [_] [:tk/listbox {:items @items :selected @selected}]))]
      (t/is (some #(re-find #"insert end alpha beta" %) (scripts rec)))
      (t/is (some #(re-find #"selection set 1 1" %) (scripts rec)))
      (reset! items ["gamma"])
      (reset! selected [0])
      (rerender! m)
      (t/is (some #(re-find #"insert end gamma" %) (scripts rec)))
      (t/is (some #(re-find #"selection clear 0 end" %) (scripts rec)))
      (engine/dispose-component (:root m)))))

;; ─── shared variables (the variable-backed props) ────────────────────────

(t/deftest variable-backed-props-resolve-a-shared-tcl-name
  ;; A kmet variable is an object whose Tcl name is its own (::kmet_var<n>),
  ;; created lazily: the first widget that names it materializes it with the
  ;; variable's initial value.
  (with-fake [rec nil]
    (let [v (tk/variable "hi")]
      (t/is (tk/variable? v))
      (t/is (re-matches #"::kmet_var_\d+" (tk/variable-name v)))
      (t/is (empty? (scripts rec)) "creating a variable touches no Tcl")
      (let [{:keys [root]} (mounted [:ttk/frame {} [:ttk/entry {:textvariable v}]])]
        (t/is (some #(str/includes? % (str "-textvariable " (tk/variable-name v)))
                    (scripts rec)))
        (t/is (some #(= (str "set " (tk/variable-name v) " hi") %)
                    (scripts rec))
              "the first use materializes it with the initial value")
        (engine/dispose-component root)))))

(t/deftest a-checkable-widgets-shared-variable-carries-its-state
  ;; A :variable prop shares the state variable (with the app, with a radio
  ;; group): :active writes the variable's on/off value and :on-toggled
  ;; reads the widget's own -onvalue back out of it. :onvalue/:offvalue are
  ;; Tk's options, so they patch live like any other prop.
  (with-fake [rec nil]
    (let [v (tk/variable 0)
          active (atom true)
          m (mounted (fn [_] [:ttk/frame {}
                              [:ttk/checkbutton {:text "x" :variable v
                                                 :onvalue "yes" :offvalue "no"
                                                 :active @active
                                                 :on-toggled (fn [_] nil)}]]))
          name (tk/variable-name v)]
      (t/is (some #(str/includes? % (str " -variable " name)) (scripts rec)))
      (t/is (some #(str/includes? % "-onvalue yes") (scripts rec)))
      (t/is (some #(str/includes? % "-offvalue no") (scripts rec)))
      (t/is (some #(= (str "set " name " yes") %) (scripts rec))
            "an active button's variable holds its on value")
      (let [script (first (scripts-like rec #"-command \{kmet::cb"))]
        (t/is (str/includes? script (str "[set " name "]"))
              "the toggled handler reads the shared variable")
        (t/is (str/includes? script "cget -onvalue")
              "and compares it with the widget's own on value"))
      (reset! active false)
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "flipping :active patches, it does not rebuild")
      (t/is (some #(= (str "set " name " no") %) (scripts rec)))
      (engine/dispose-component (:root m)))))

(t/deftest a-radio-group-shares-one-variable-and-only-the-active-writes
  ;; Two radiobuttons naming one variable are Tk's own exclusive group: the
  ;; render writes only the active button's value, so an unselected sibling
  ;; cannot unsay the group's selection. Without a :variable each button
  ;; keeps its private variable and :active's false clears it, as before.
  (with-fake [rec nil]
    (let [v (tk/variable :none)
          selected (atom :light)
          m (mounted (fn [_]
                       [:ttk/frame {}
                        [:ttk/radiobutton {:text "Light" :variable v :value :light
                                           :active (= :light @selected)
                                           :on-toggled (fn [_] nil)}]
                        [:ttk/radiobutton {:text "Dark" :variable v :value :dark
                                           :active (= :dark @selected)
                                           :on-toggled (fn [_] nil)}]]))
          name (tk/variable-name v)
          writes (fn [] (filterv #(str/starts-with? % (str "set " name " "))
                                 (scripts rec)))]
      (t/is (= 2 (count (scripts-like rec (re-pattern (str " -variable " name)))))
            "both buttons script the group's variable")
      (t/is (= [(str "set " name " none") (str "set " name " light")] (writes))
            "the first button writes the active value, and only it")
      (doseq [script (scripts-like rec #"-command \{kmet::cb")]
        (t/is (str/includes? script (str "[set " name "]")))
        (t/is (str/includes? script "cget -value")
              "each handler compares the group's variable with its own value"))
      (reset! selected :dark)
      (rerender! m)
      (t/is (= [(str "set " name " none") (str "set " name " light")
                (str "set " name " dark")]
               (writes))
            "moving the selection writes the new value")
      (t/is (not-any? #(= (str "set " name " {}") %) (writes))
            "the now-inactive sibling never clears the group")
      (engine/dispose-component (:root m)))))

(t/deftest a-private-radio-still-clears-its-own-state
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:ttk/radiobutton {:text "x" :value :a
                                                      :active false}]])
          var (second (re-find #"-variable (::kmet_check_\S+)" (first (scripts-like rec #"ttk::radiobutton"))))]
      (t/is (some? var))
      (t/is (some #(= (str "set " var " {}") %) (scripts rec))
            "an unselected private radio clears its own variable")
      (engine/dispose-component root))))

(t/deftest an-empty-variable-name-is-no-variable
  ;; An empty Tcl name is Tk's "no variable": the tag keeps its private one
  ;; (an all-props mount asks every widget for its own cget value, which is
  ;; empty where the widget has none).
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {} [:ttk/checkbutton {:text "x" :variable ""
                                                                    :active true}]])]
      (t/is (some #(re-find #"-variable ::kmet_check_\S+" %) (scripts rec)))
      (t/is (some #(re-find #"set ::kmet_check_\S+ 1" %) (scripts rec)))
      (engine/dispose-component root))))

(t/deftest a-variable-backed-prop-change-rebuilds-the-widget
  ;; The tag wires its state (the read-back fragments, the private variable's
  ;; fallback) around the name, so a different variable is a different
  ;; widget — not a live configure.
  (with-fake [rec nil]
    (let [v (atom (tk/variable 0))
          m (mounted (fn [_] [:ttk/frame {}
                              [:ttk/checkbutton {:text "x" :active false
                                                 :variable @v}]]))]
      (reset! v (tk/variable 0))
      (rerender! m)
      (t/is (pos? (count (scripts-like rec #"^destroy "))))
      (t/is (= 2 (count (distinct (keep #(second (re-find #" -variable (\S+)" %))
                                        (scripts rec)))))
            "the rebuilt widget carries the new variable")
      (engine/dispose-component (:root m)))))

(t/deftest one-item-source-and-one-value-source
  ;; Tk's -listvariable links a listbox to a variable both ways and a text
  ;; field's -textvariable is its value, so the tags refuse a second source
  ;; rather than let the two fight.
  (with-fake [_rec nil]
    (let [e (try (mounted [:ttk/frame {}
                           [:ttk/entry {:text "a" :textvariable (tk/variable "b")}]])
                 nil
                 (catch Exception e e))]
      (t/is (= :kmet.tk.core/text-and-textvariable (:type (ex-data e))))
      (t/is (str/includes? (ex-message e) ":textvariable")))
    (let [e (try (mounted [:tk/listbox {:items ["a"]
                                        :listvariable (tk/variable ["b"])}])
                 nil
                 (catch Exception e e))]
      (t/is (= :kmet.tk.core/items-and-listvariable (:type (ex-data e))))
      (t/is (str/includes? (ex-message e) ":listvariable")))
    (with-fake [rec nil]
      (let [{:keys [root]} (mounted [:tk/listbox {:listvariable (tk/variable ["a" "b"])}])]
        (t/is (some #(re-find #"-listvariable ::kmet_var_\d+" %) (scripts rec)))
        (t/is (not-any? #(str/includes? % "insert end") (scripts rec))
              "the variable is the listbox's only item source")
        (engine/dispose-component root)))))

(t/deftest variable-watches-carry-tcl-writes-back
  ;; A watch rides `trace add variable`, so a write from any side — the
  ;; user's typing into a -textvariable widget, Tk's own toggle, the app's
  ;; variable-set! — reaches the handler with the value Tcl wrote.
  (with-fake [rec nil]
    (let [v (tk/variable "hi")
          seen (atom [])
          unwatch (tk/variable-watch! v #(swap! seen conj %))]
      (t/is (empty? (scripts rec)) "a watch before materialization touches no Tcl")
      (let [{:keys [root]} (mounted [:ttk/frame {} [:ttk/entry {:textvariable v}]])
            name (tk/variable-name v)
            trace (first (scripts-like rec #"^trace add variable "))
            id (second (re-find #"kmet::cb (\d+) \[set " trace))]
        (t/is (= (str "trace add variable " name " write {kmet::cb " id " [set " name "]}")
                 trace))
        (tk/dispatch-callback! ["kmet::cb" id "typed"])
        (t/is (= ["typed"] @seen) "the watcher receives the value Tcl wrote")
        (unwatch)
        (t/is (some #(= (str "trace remove variable " name " write {kmet::cb " id
                             " [set " name "]}")
                        %)
                    (scripts rec))
              "unwatching removes the trace")
        (t/is (= "" (tk/dispatch-callback! ["kmet::cb" id "again"]))
              "and releases the trampoline")
        (t/is (= ["typed"] @seen))
        (engine/dispose-component root))
      (tk/variable-set! v "knocked")
      (t/is (some #(= (str "set " (tk/variable-name v) " knocked") %)
                  (scripts rec)))
      (tk/variable-dispose! v)
      (t/is (some #(= (str "unset -nocomplain " (tk/variable-name v)) %)
                  (scripts rec))))))

(t/deftest a-disposed-variables-watches-are-released
  (with-fake [rec nil]
    (let [v (tk/variable 0)
          seen (atom [])]
      (tk/variable-watch! v #(swap! seen conj %))
      (tk/variable-set! v 1)
      (let [trace (first (scripts-like rec #"^trace add variable "))
            id (second (re-find #"kmet::cb (\d+)" trace))]
        (tk/variable-dispose! v)
        (t/is (some #(= (str "trace remove variable " (tk/variable-name v)
                             " write {kmet::cb " id " [set " (tk/variable-name v) "]}")
                        %)
                    (scripts rec)))
        (t/is (some #(= (str "unset -nocomplain " (tk/variable-name v)) %)
                    (scripts rec)))
        (t/is (= "" (tk/dispatch-callback! ["kmet::cb" id "again"]))
              "disposal releases every watcher it held")
        (t/is (empty? @seen)))
      ;; a disposed variable materializes again for a later widget
      (let [{:keys [root]} (mounted [:ttk/frame {} [:ttk/entry {:textvariable v}]])
            name (tk/variable-name v)]
        (t/is (= 2 (count (scripts-like rec (re-pattern (str "^set " name " 0$")))))
              "the mount after disposal sets the initial value again")
        (engine/dispose-component root)))))

(t/deftest a-treeview-takes-columns-rows-and-a-selection
  (with-fake [rec nil]
    (let [rows (atom [["a" "1"] ["b" "2"]])
          selected (atom "r1")
          m (mounted (fn [_] [:ttk/treeview {:columns [{:id :name :heading "Name" :width 120}
                                                       :count]
                                             :rows @rows
                                             :selected @selected}]))]
      (t/is (some #(re-find #"ttk::treeview \S+ -columns name\\ count" %) (scripts rec))
            "the column ids become Tk's -columns")
      (t/is (some #(re-find #"heading name -text Name" %) (scripts rec)))
      (t/is (some #(re-find #"column name -width 120" %) (scripts rec)))
      (t/is (some #(re-find #"insert \{\} end -id r1 -values a\\ 1" %) (scripts rec)))
      (t/is (some #(re-find #"insert \{\} end -id r2 -values b\\ 2" %) (scripts rec)))
      (t/is (some #(re-find #"selection set r1" %) (scripts rec)))
      (reset! rows [["c" "3"]])
      (rerender! m)
      (t/is (some #(re-find #"delete \[\S+ children \{\}\]" %) (scripts rec))
            "a rows change rebuilds the rows, not the widget")
      (t/is (zero? (count (scripts-like rec #"^destroy "))))
      (engine/dispose-component (:root m)))))

(t/deftest a-treeview-grows-a-hierarchy-with-row-tags
  (with-fake [rec nil]
    (let [rows (atom [{:id "git" :text "git" :values ["shell"] :open true
                       :tags ["done"]
                       :children [{:text "status" :values ["read"] :image "::icon"}]}
                      {:text "llm" :values ["call"]}])
          m (mounted (fn [_] [:ttk/treeview {:columns [{:id "#0" :heading "Tool" :width 200}
                                                       {:id :kind :heading "Kind"}]
                                             :show [:tree :headings]
                                             :font-family "Courier New" :font-size 11
                                             :tags {"done" {:foreground "#888888" :bold true}}
                                             :selected "git"
                                             :rows @rows}]))]
      (t/is (some #(re-find #"ttk::treeview \S+ -columns kind" %) (scripts rec))
            "only the data columns go to Tk's -columns")
      (t/is (some #(re-find #"heading #0 -text Tool" %) (scripts rec))
            "the tree column is addressed like any other")
      (t/is (some #(re-find #"column #0 -width 200" %) (scripts rec)))
      (t/is (some #(re-find #"-show tree\\ headings" %) (scripts rec))
            "a hierarchy asks for Tk's own -show")
      (t/is (some #(str/includes? % (str "tag configure done -font \\{\\{Courier\\ New\\}\\ 11\\ bold\\}"
                                         " -foreground \\#888888"))
                  (scripts rec))
            "a row tag takes Tk's tag options, its font built like a widget's")
      (t/is (some #(re-find #"insert \{\} end -id git -text git -values shell -tags done -open 1" %)
                  (scripts rec))
            "a row map scripts Tk's own item options")
      (t/is (some #(re-find #"insert git end -id git\.1 -text status -values read -image ::icon" %)
                  (scripts rec))
            "its :children insert under it, defaulting their ids under its own")
      (t/is (some #(re-find #"insert \{\} end -id r2 -text llm -values call" %) (scripts rec))
            "a top-level id still defaults to r<n>")
      (let [sets (count (scripts-like rec #"selection set git"))]
        (t/is (pos? sets) "the mount selects the row the app named")
        (reset! rows [{:id "git" :text "git" :values ["shell"]}])
        (rerender! m)
        (t/is (some #(re-find #"delete \[\S+ children \{\}\]" %) (scripts rec)))
        (t/is (zero? (count (scripts-like rec #"^destroy ")))
              "a rows change rebuilds the hierarchy, not the widget")
        (t/is (= (inc sets) (count (scripts-like rec #"selection set git")))
              "and selects the still-selected row again — the rebuild cleared Tk's")
        (reset! rows [{:text "other"}])
        (rerender! m)
        (t/is (= (inc sets) (count (scripts-like rec #"selection set git")))
              "an id the new rows no longer have is dropped, not an error"))
      (engine/dispose-component (:root m)))))

(t/deftest an-unknown-row-tag-option-is-an-error
  (with-fake [_ nil]
    (let [e (try (mounted [:ttk/frame {} [:ttk/treeview {:tags {"done" {:colour "#888888"}}}]])
                 nil
                 (catch Exception e e))]
      (t/is (= :kmet.tk.core/unknown-tag-option (:type (ex-data e))))
      (t/is (= [:colour] (:options (ex-data e))))
      (t/is (str/includes? (ex-message e) ":colour")))))

(t/deftest a-child-can-be-gridded-or-placed
  ;; Tk's other two geometry managers: a child's own :grid/:place map takes
  ;; Tk's options for it (in the option-name order every script uses), and a
  ;; container's :rowconfigure/:columnconfigure its rows and columns.
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {:width 100
                                               :columnconfigure {0 {:weight 1}
                                                                 "1" {:weight 2 :minsize 40}}}
                                   [:ttk/label {:text "hi"
                                                :grid {:row 0 :column 1 :sticky "nsew"
                                                       :padx [4 6]}}]
                                   [:ttk/label {:text "over"
                                                :place {:relx 0.5 :rely 0.5 :anchor :center}}]])]
      (t/is (some #(re-find #"^grid \S+ -column 1 -padx 4\\ 6 -row 0 -sticky nsew$" %) (scripts rec))
            "a :grid child is gridded with Tk's own options")
      (t/is (some #(re-find #"^place \S+ -anchor center -relx 0\.5 -rely 0\.5$" %) (scripts rec))
            "a :place child is placed")
      (t/is (some #(re-find #"^grid columnconfigure \S+ 0 -weight 1$" %) (scripts rec)))
      (t/is (some #(re-find #"^grid columnconfigure \S+ 1 -minsize 40 -weight 2$" %) (scripts rec)))
      (t/is (= 1 (count (scripts-like rec #"^pack ")))
            "only the container itself is packed")
      (engine/dispose-component root))))

(t/deftest two-items-cannot-share-an-id
  ;; An id is how a render addresses its item in Tk, so the same one twice
  ;; would make two items move, style and delete together — Tk itself
  ;; tolerates the duplicate, which is why the render refuses it.
  (with-fake [_ nil]
    (let [canvas-error (try (mounted [:tk/canvas {:items [{:id "n1" :type :line :coords [0 0 1 1]}
                                                          {:id "n1" :type :rect :coords [2 2 3 3]}]}])
                            nil
                            (catch Exception e e))
          tree-error (try (mounted [:ttk/treeview {:rows [{:id "r" :values [1]}
                                                          {:id "r" :values [2]}]}])
                          nil
                          (catch Exception e e))]
      (t/is (= :kmet.tk.core/duplicate-item-id (:type (ex-data canvas-error))))
      (t/is (= ["n1"] (:ids (ex-data canvas-error))))
      (t/is (= :kmet.tk.core/duplicate-item-id (:type (ex-data tree-error))))
      (t/is (str/includes? (ex-message tree-error) "\"r\"")))))

(t/deftest a-child-names-one-geometry-manager
  ;; Two managers on one child, a manager beside pack's own props, and Tk's
  ;; -in (whose master a kmet child does not have) are each refused before
  ;; any script is built — Tk's own error names the first two only when the
  ;; container is already managed.
  (with-fake [_ nil]
    (doseq [[props expected]
            [[{:grid {} :place {}} :kmet.tk.core/two-geometry-managers]
             [{:place {} :side :left} :kmet.tk.core/mixed-geometry-props]
             [{:grid {:row 0 :in ".other"}} :kmet.tk.core/geometry-master-not-supported]]]
      (let [e (try (mounted [:ttk/frame {} [:ttk/label (assoc props :text "x")]])
                   nil
                   (catch Exception e e))]
        (t/is (= expected (:type (ex-data e)))
              (str (pr-str props) " is refused with " expected))))
    (let [e (try (mounted [:ttk/notebook {} [:ttk/frame {:tab "One" :grid {:row 0}}]])
                 nil
                 (catch Exception e e))]
      (t/is (= :kmet.tk.core/geometry-manager-not-supported (:type (ex-data e)))
            "a container that places its child itself owns its geometry"))))

(t/deftest a-notebook-adds-tabs-and-a-panedwindow-panes
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {}
                                   [:ttk/notebook {}
                                    [:ttk/frame {:tab "One"}]
                                    [:ttk/frame {:tab "Two"}]]
                                   [:ttk/panedwindow {:orient :horizontal}
                                    [:ttk/frame {:weight 2}]
                                    [:ttk/frame {:weight 1}]]
                                   [:tk/panedwindow {:orient :horizontal}
                                    [:tk/frame {:minsize 40}]]])]
      (t/is (some #(re-find #"^\S+ add \S+ -text One" %) (scripts rec)))
      (t/is (some #(re-find #"-text Two" %) (scripts rec)))
      (t/is (some #(re-find #"add \S+ -weight 2" %) (scripts rec)))
      (t/is (some #(re-find #"add \S+ -weight 1" %) (scripts rec)))
      (t/is (some #(re-find #"add \S+ -minsize 40" %) (scripts rec)))
      (let [added (into #{} (keep #(second (re-find #" add (\S+) " %)) (scripts rec)))
            packed (into #{} (keep #(second (re-find #"^pack (\S+)" %)) (scripts rec)))]
        (t/is (seq added))
        (t/is (empty? (filter added packed))
              "a child of a pane container is added, never packed"))
      (engine/dispose-component root))))

(t/deftest menus-carry-their-items
  (with-fake [rec nil]
    (let [clicked (atom nil)
          menu-props {:items [{:label "Open…" :accelerator "Ctrl-O"
                               :on-click (fn [] (reset! clicked :open))}
                              :separator
                              {:label "More" :items [{:label "Deep"}]}]}
          m (mounted (fn [_] [:ttk/frame {}
                              [:tk/menu menu-props]
                              [:ttk/menubutton {:text "File"
                                                :items [{:label "Quit"
                                                         :on-click identity}]}]]))]
      (t/is (some #(re-find #"add command -accelerator Ctrl-O -label Open… -command \{kmet::cb \d+\}$"
                            %)
                  (scripts rec))
            "the item options are Tk's own, in option-name order")
      (t/is (some #(re-find #"add separator" %) (scripts rec)))
      (t/is (some #(re-find #"add cascade -label More -menu" %) (scripts rec)))
      (t/is (some #(re-find #"-tearoff 0" %) (scripts rec)))
      (t/is (some #(re-find #"add command -label Quit" %) (scripts rec)))
      (let [ids (mapv #(second (re-find #"kmet::cb (\d+)" %))
                      (scripts-like rec #"add command "))]
        (t/is (= 3 (count ids)) "one trampoline per command item")
        (t/is (nil? @clicked))
        (tk/dispatch-callback! ["kmet::cb" (first ids)])
        (t/is (= :open @clicked) "the item's handler fires on dispatch"))
      (engine/dispose-component (:root m)))))

(t/deftest menus-take-check-and-radio-items
  ;; Tk's checkbutton/radiobutton menu items need a -variable, which the tag
  ;; owns privately: a checkbutton item keeps its own, a radiobutton group
  ;; one shared by the group — which is what makes the group exclusive.
  ;; Either handler receives the new boolean; an item's other keys are Tk's
  ;; own item options, an :image created as the menu's own.
  (with-fake [rec nil]
    (let [toggled (atom nil)
          clicked #(reset! toggled %)
          checked (atom :light)
          items (fn [] [{:kind :check :label "Wrap" :checked true
                         :on-click clicked
                         :image {:data "iVBORw0KGgo=" :format :png}}
                        {:kind :radio :group :mode :value :light :label "Light"
                         :checked (= :light @checked) :on-click clicked}
                        {:kind :radio :group :mode :value :dark :label "Dark"
                         :checked (= :dark @checked) :on-click clicked}
                        {:label "Reload" :state :disabled :underline 0}])
          m (mounted (fn [_] [:tk/menu {:items (items)}]))
          [check] (scripts-like rec #"add checkbutton")
          [light dark] (scripts-like rec #"add radiobutton")
          var-of (fn [script] (second (re-find #"-variable (\S+)" script)))
          id-of (fn [script] (second (re-find #"kmet::cb (\d+)" script)))]
      (t/is (some #(re-find #"^image create photo ::kmet_img_\d+ -data" %) (scripts rec))
            "the item's image is the menu's own")
      (t/is (every? #(str/includes? check %)
                    ["add checkbutton" "-label Wrap" "-onvalue 1" "-offvalue 0"
                     "-image ::kmet_img_" "-command {kmet::cb "])
            "a checkbutton item is Tk's, with the option shapes it needs")
      (t/is (str/includes? check (str "-command {kmet::cb " (id-of check) " [set "
                                      (var-of check) "]}"))
            "and reads its own variable back to the handler")
      (t/is (some #(str/includes? % (str "set " (var-of check) " 1")) (scripts rec))
            "the render writes the item's :checked")
      (t/is (= (var-of light) (var-of dark))
            "a radio group shares one variable, which is what makes it exclusive")
      (t/is (every? #(str/includes? light %)
                    ["add radiobutton" "-label Light" "-value light" "-command {kmet::cb "]))
      (t/is (str/includes? light (str "[string equal [set " (var-of light) "] light]"))
            "a radio item's handler gets whether the group now holds its value")
      (t/is (some #(str/includes? % (str "set " (var-of light) " light")) (scripts rec))
            "the group's variable carries the render's selection")
      (t/is (some #(re-find #"add command -label Reload -state disabled -underline 0$" %)
                  (scripts rec))
            "any other key is a Tk menu-item option")
      (t/is (= [true false] (let [id (id-of check)]
                              [(do (tk/dispatch-callback! ["kmet::cb" id "1"]) @toggled)
                               (do (tk/dispatch-callback! ["kmet::cb" id "0"]) @toggled)]))
            "a check item's handler receives the new boolean")      (t/is (= [true false] (let [id (id-of light)]
                                                                                            [(do (tk/dispatch-callback! ["kmet::cb" id "1"]) @toggled)
                                                                                             (do (tk/dispatch-callback! ["kmet::cb" id "0"]) @toggled)]))
                                                                          "and so does a radio item's")
      (t/is (= :kmet.tk.core/radio-item-without-value
               (:type (ex-data (try (mounted [:tk/menu {:items [{:kind :radio :label "x"}]}])
                                    nil
                                    (catch Exception e e)))))
            "a radio item without :value has nothing for Tk to compare")
      (reset! checked :dark)
      (rerender! m)
      (t/is (some #(str/includes? % (str "set " (var-of light) " dark")) (scripts rec))
            "moving the selection rewrites the group's variable")
      (engine/dispose-component (:root m)))))

(t/deftest a-menubuttons-menu-and-its-context-menu-keep-their-own-state
  ;; Two menus on one widget are two menus: their items' private variables
  ;; must differ, or clicking one would move the other's state.
  (with-fake [rec nil]
    (let [m (mounted (fn [_] [:ttk/menubutton
                              {:text "File"
                               :items [{:kind :check :label "Wrap" :checked false}]
                               :context-menu [{:kind :check :label "Copy" :checked true}]}]))
          vars (into [] (comp (filter #(str/includes? % "add checkbutton"))
                              (map #(second (re-find #"-variable (\S+)" %))))
                     (scripts rec))]
      (t/is (= 2 (count vars)))
      (t/is (not= (first vars) (second vars))
            "each menu's check item keeps its own variable")
      (t/is (and (some #(str/includes? % (str "set " (first vars) " 1")) (scripts rec))
                 (some #(str/includes? % (str "set " (second vars) " 0")) (scripts rec)))
            "and each takes its own :checked")
      (engine/dispose-component (:root m)))))

(t/deftest a-menu-item-change-releases-the-old-trampolines
  (with-fake [rec nil]
    (let [f (atom (fn [] :first))
          m (mounted (fn [_] [:tk/menu {:items [{:label "x" :on-click @f}]}]))]
      (reset! f (fn [] :second))
      (rerender! m)
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "an items change patches the menu")
      (let [ids (mapv #(second (re-find #"kmet::cb (\d+)" %))
                      (scripts-like rec #"add command -label"))
            [old new] (mapv parse-long ids)]
        (t/is (= 2 (count ids)))
        (t/is (not= old new))
        (t/is (= "" (tk/dispatch-callback! ["kmet::cb" (str old)]))
              "the replaced item's handler is released"))
      (engine/dispose-component (:root m)))))

(t/deftest a-canvas-reconciles-its-items-by-id
  ;; A canvas item's identity is the app's :id — written as its first Tk
  ;; tag, since Tk gives items numeric ids of its own — so a re-render keeps
  ;; the items whose ids it still has: the coordinates move through Tk's
  ;; `coords` subcommand, changed options through `itemconfigure`, and only
  ;; a new, gone, retyped or reshaped item costs a create or a delete.
  (with-fake [rec nil]
    (let [items (atom [{:type :line :coords [0 0 10 10] :fill "#ff0000"}
                       {:type :text :coords [5 5] :text "hi" :anchor :w}])
          m (mounted (fn [_] [:tk/canvas {:items @items}]))]
      (t/is (some #(re-find #"create line 0 0 10 10 -fill \\#ff0000 -tags i1$" %) (scripts rec))
            "an item's id is its first Tk tag (i<n> when the render names none)")
      (t/is (some #(re-find #"create text 5 5 -anchor w -tags i2 -text hi$" %) (scripts rec)))
      (let [drawn (count (scripts rec))]
        (rerender! m)
        (t/is (= drawn (count (scripts rec)))
              "a render that changes nothing leaves the canvas alone"))
      (swap! items assoc 0 {:type :line :coords [0 0 20 20] :fill "#00ff00"})
      (rerender! m)
      (t/is (some #(re-find #"coords i1 0 0 20 20$" %) (scripts rec))
            "a kept item moves through Tk's coords subcommand")
      (t/is (some #(re-find #"itemconfigure i1 -fill \\#00ff00$" %) (scripts rec))
            "its changed options go through itemconfigure")
      (reset! items [{:id "i1" :type :line :coords [0 0 20 20] :fill "#00ff00"}
                     {:id "i3" :type :rect :coords [1 1 4 4]}])
      (rerender! m)
      (t/is (some #(re-find #"delete i2$" %) (scripts rec)) "a gone id is deleted")
      (t/is (some #(re-find #"create rect 1 1 4 4 -tags i3$" %) (scripts rec))
            "a new one is drawn")
      (swap! items assoc 0 {:id "i1" :type :rect :coords [0 0 20 20]})
      (rerender! m)
      (t/is (some #(re-find #"delete i1$" %) (scripts rec))
            "a retyped item (or one that dropped an option) is rebuilt")
      (t/is (some #(re-find #"create rect 0 0 20 20 -tags i1$" %) (scripts rec)))
      (t/is (zero? (count (scripts-like rec #"^destroy ")))
            "the widget itself is never rebuilt")
      (engine/dispose-component (:root m)))))

(t/deftest a-canvas-item-binds-each-handler-to-its-own-event
  ;; An item's handlers are Tk's item binds on the item's own tag: a click
  ;; is the button, a drag is motion with the button held, entering and
  ;; leaving are the crossings. Each carries the event's coordinates, and
  ;; each has a trampoline of its own — so changing one rebinds one.
  (with-fake [rec nil]
    (let [seen (atom {})
          note (fn [k] #(swap! seen assoc k %))
          items (atom [{:id "n1" :type :rect :coords [0 0 10 10] :fill "#fff"
                        :tags ["hot"]
                        :on-click (note :on-click)
                        :on-drag (note :on-drag)
                        :on-enter (note :on-enter)
                        :on-leave (note :on-leave)}])
          m (mounted (fn [_] [:tk/canvas {:items @items}]))
          events {:on-click "<Button-1>" :on-drag "<B1-Motion>"
                  :on-enter "<Enter>" :on-leave "<Leave>"}
          bind-script (fn [prop]
                        (last (scripts-like rec
                                            (re-pattern (str (get events prop)
                                                             " \\{kmet::cb \\d+ %x %y\\}$")))))
          callback (fn [prop] (second (re-find #"kmet::cb (\d+)" (bind-script prop))))]
      (t/is (some #(re-find #"create rect 0 0 10 10 -fill \\#fff -tags n1\\ hot$" %)
                  (scripts rec))
            "the app's own tags ride in the item's one -tags list, after its id")
      (doseq [[prop event] events]
        (t/is (bind-script prop) (str prop " is Tk's item bind for " event))
        (tk/dispatch-callback! ["kmet::cb" (callback prop) "7" "9"])
        (t/is (= [7 9] (get @seen prop))
              (str "the " prop " handler receives the event's coordinates")))
      (let [old-leave (callback :on-leave)]
        (swap! items assoc 0 (assoc (first @items) :on-leave (note :left)))
        (rerender! m)
        (t/is (not= old-leave (callback :on-leave)) "a changed handler is bound again")
        (tk/dispatch-callback! ["kmet::cb" old-leave "1" "2"])
        (t/is (= [7 9] (get @seen :on-leave))
              "and its old trampoline is released, not left dangling")
        (tk/dispatch-callback! ["kmet::cb" (callback :on-leave) "1" "2"])
        (t/is (= [1 2] (get @seen :left)) "a leave hands over its own coordinates"))
      (swap! items assoc 0 (assoc (first @items) :on-click nil))
      (rerender! m)
      (t/is (some #(re-find #"<Button-1> \{\}$" %) (scripts rec))
            "a dropped handler unbinds the item")
      (engine/dispose-component (:root m)))))

(t/deftest a-canvas-keeps-the-renders-order
  ;; A canvas draws in stacking order, so a later item is on top. Tk keeps
  ;; creation order: a render that only appends needs nothing, while one
  ;; that inserts or reorders raises the items into its own order.
  (with-fake [rec nil]
    (let [line {:type :line :coords [0 0 1 1]}
          items (atom [{:id "a" :type :line :coords [0 0 1 1]}
                       {:id "b" :type :line :coords [0 0 1 1]}])
          m (mounted (fn [_] [:tk/canvas {:items @items}]))
          ;; what one render emits, to assert on that render alone
          rendered (fn [f]
                     (let [n (count (scripts rec))]
                       (f)
                       (drop n (scripts rec))))]
      (t/is (empty? (scripts-like rec #"raise "))
            "the first render is creation order, so nothing is raised")
      (t/is (empty? (filterv #(re-find #"raise " %)
                             (rendered #(do (swap! items conj (assoc line :id "c"))
                                            (rerender! m)))))
            "appending keeps Tk's order — a new item is already on top")
      (t/is (some #(re-find #"foreach ::kmet_item b\\ a\\ c \{ .+ raise \$::kmet_item \}$" %)
                  (rendered #(do (reset! items [(assoc line :id "b") (assoc line :id "a") (assoc line :id "c")])
                                 (rerender! m))))
            "a reorder raises the items in the render's order, in one script")
      (t/is (empty? (rendered #(rerender! m)))
            "a render that keeps that order emits nothing")
      (t/is (empty? (filterv #(re-find #"raise " %)
                             (rendered #(do (swap! items conj (assoc line :id "d"))
                                            (rerender! m)))))
            "appending to a reordered render still needs no raise")
      (t/is (some #(re-find #"foreach ::kmet_item a\\ x\\ b \{ .+ raise \$::kmet_item \}$" %)
                  (rendered #(do (reset! items [(assoc line :id "a") (assoc line :id "x") (assoc line :id "b")])
                                 (rerender! m))))
            "an item inserted before an existing one is raised into place")
      ;; A render that adds several items draws them in its own order —
      ;; creation order is the stacking order, so that is what puts a later
      ;; one on top.
      (t/is (= ["p" "q" "r"]
               (mapv #(second (re-find #"-tags ([a-z]+)$" %))
                     (filterv #(re-find #" create line " %)
                              (rendered #(do (reset! items [(assoc line :id "p") (assoc line :id "q")
                                                            (assoc line :id "r")])
                                             (rerender! m))))))
            "a render's new items are created in its own order, bottom first")
      ;; A rebuilt item keeps its id, so the order needs nothing — except
      ;; that Tk creates it on top: it is raised back unless it is the last.
      (t/is (some #(re-find #"foreach ::kmet_item p\\ q\\ r" %)
                  (rendered #(do (reset! items [(assoc line :id "p" :type :rect)
                                                (assoc line :id "q") (assoc line :id "r")])
                                 (rerender! m))))
            "a rebuilt item that is not last is raised back into place")
      (t/is (empty? (filterv #(re-find #"foreach ::kmet_item" %)
                             (rendered #(do (reset! items [(assoc line :id "p" :type :rect)
                                                           (assoc line :id "q")
                                                           (assoc line :id "r" :type :rect)])
                                            (rerender! m)))))
            "rebuilding the topmost item needs no raise")
      (engine/dispose-component (:root m)))))

(t/deftest a-widget-gets-a-context-menu
  ;; Tk has no option for a popup menu: the idiom — Tk's own, from the menu
  ;; man page — binds the platform's secondary click to tk_popup. Tk 9 has
  ;; that click as <<ContextMenu>>; a Tk without it falls back to
  ;; <Button-3>, which is what the recorder's interpreter answers (it has no
  ;; virtual events).
  (with-fake [rec nil]
    (let [clicked (atom nil)
          items (atom [{:label "Copy" :on-click #(reset! clicked :copy)}
                       :separator
                       {:label "Paste" :on-click #(reset! clicked :paste)}])
          m (mounted (fn [_] [:ttk/frame {:context-menu @items}]))
          frame (second (re-find #"^ttk::frame (\S+)$" (first (scripts-like rec #"^ttk::frame "))))
          [_ menu] (re-find #"^menu (\S+) -tearoff 0$" (first (scripts-like rec #"^menu ")))]
      (t/is (str/starts-with? menu (str frame "."))
            "the menu is W's own child, so Tk destroys it with W")
      (t/is (some #(re-find #"add command -label Copy -command \{kmet::cb \d+\}$" %) (scripts rec)))
      (t/is (some #(str/includes? % (str menu " add separator")) (scripts rec)))
      (t/is (some #(str/includes? % (str "bind " frame " <Button-3> {tk_popup " menu " %X %Y}"))
                  (scripts rec))
            "the secondary click posts the menu where it was clicked")
      (let [id (second (re-find #"kmet::cb (\d+)" (first (scripts-like rec #"add command -label Copy"))))]
        (t/is (= ":copy" (tk/dispatch-callback! ["kmet::cb" id])))
        (reset! items [{:label "Reload" :on-click #(reset! clicked :reload)}])
        (rerender! m)
        (t/is (= "" (tk/dispatch-callback! ["kmet::cb" id]))
              "rebuilding the items releases the callbacks the old ones held")
        (t/is (some #(str/includes? % (str menu " delete 0 end")) (scripts rec)))
        (t/is (some #(str/includes? % "add command -label Reload") (scripts rec)))
        (reset! items nil)
        (rerender! m)
        (t/is (some #(str/includes? % (str "bind " frame " <Button-3> {}")) (scripts rec))
              "no items unbinds the event")
        (t/is (some #(str/includes? % (str "destroy " menu)) (scripts rec))
              "and destroys the menu"))
      (t/is (= 1 (count (scripts-like rec #"^ttk::frame ")))
            ":context-menu patches the widget, it does not rebuild it")
      (engine/dispose-component (:root m)))))

;; ─── rich text (:text :content segments) ─────────────────────────────────

(t/deftest text-content-segments-reuse-style-tags
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted
                          [:ttk/frame {}
                           [:tk/text {:content [{:text "plain "}
                                                {:text "red " :fg "#ff0000"}
                                                {:text "bold" :bold true}
                                                {:text "red2" :fg "#ff0000"}]}]])]
      (let [ss (scripts rec)
            configs (scripts-like rec #"tag configure")
            inserts (into [] (comp (filter #(re-find #" insert end " %))
                                   (mapcat #(str/split-lines %)))
                          ss)]
        (t/is (= 2 (count configs)) "one tag configure per distinct style")
        (t/is (= 1 (count (scripts-like rec #"-foreground \\#ff0000")))
              "the two red runs share one configured tag")
        (t/is (= 1 (count (scripts-like rec #"tag configure \S+ -font .*bold")))
              "bold uses a font description derived from the base fixed font")
        (t/is (= 4 (count inserts)))
        (t/is (re-find #"insert end plain\\ $|insert end plain$" (first inserts))
              "an unstyled segment inserts without a tag")
        (let [reds (filterv #(re-find #"insert end red" %) inserts)
              last-token (fn [s] (last (str/split (str/trim s) #" +")))]
          (t/is (= 2 (count reds)))
          (t/is (= (last-token (first reds)) (last-token (second reds))))))
      (engine/dispose-component root))))

(t/deftest text-scrollbar-is-wired
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {} [:tk/text {:text "hi" :scrollbar true}]])
          path (second (re-find #"^text (\.w[\w.]+)" (first (scripts-like rec #"^text \.w"))))
          sb (second (re-find #"^ttk::scrollbar (\.w[\w.]+) -orient vertical -command \""
                              (first (scripts-like rec #"^ttk::scrollbar "))))]
      (t/is (some true? (map #(= (str "ttk::scrollbar " sb " -orient vertical -command \""
                                      path " yview\"")
                                 %)
                             (scripts rec))))
      (t/is (some true? (map #(= (str path " configure -yscrollcommand \"" sb " set\"") %)
                             (scripts rec))))
      (t/is (some true? (map #(= (str "pack " sb " -side right -fill y") %)
                             (scripts rec))))
      (t/is (some true? (map #(= (str "pack " path " -side left -fill both -expand 1") %)
                             (scripts rec))))
      (engine/dispose-component root)
      (t/is (some #(str/includes? % (str "destroy " sb)) (scripts rec))
            "dispose destroys the sibling scrollbar"))))

;; ─── an editable text (:editable true) ───────────────────────────────────

(t/deftest an-editable-text-is-an-editor-not-a-display-surface
  ;; (:editable true) keeps -state normal (Tk's default), turns Tk's undo
  ;; on, takes :text as its value — read back first, so a value the widget
  ;; already holds is not rewritten — and binds the text slots.
  (let [scripts (atom [])
        adapter {:eval (fn [s] (swap! scripts conj s) "")
                 :command! (fn [_ _] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (let [{:keys [root]} (mounted [:ttk/frame {}
                                     [:tk/text {:editable true :text "hi"
                                                :on-change (fn [_] nil)
                                                :on-submit (fn [_] nil)}]])]
        (t/is (some #(str/includes? % "-undo 1") @scripts)
              "an editor needs undo, which Tk leaves off on its own")
        (t/is (not-any? #(str/includes? % "-state") @scripts)
              "an editable text is never disabled")
        (t/is (some #(str/includes? % " get 1.0 end-1c") @scripts)
              "the widget's own text is read first")
        (t/is (some #(str/includes? % "delete 1.0 end") @scripts))
        (t/is (some #(str/includes? % "insert 1.0 hi") @scripts)
              "the value goes in as the widget's text")
        (t/is (some #(and (str/includes? % "<KeyRelease>")
                          (str/includes? % "get 1.0 end-1c"))
                    @scripts)
              ":on-change reads the value on a key release")
        (t/is (some #(and (str/includes? % "<Return>")
                          (str/includes? % "get 1.0 end-1c")
                          (str/includes? % "; break"))
                    @scripts)
              ":on-submit hands the value over and stops Tk's own newline")
        (engine/dispose-component root)))))

(t/deftest an-editable-text-leaves-what-the-widget-holds-alone
  ;; A re-render that mirrors :on-change back into :text writes nothing at
  ;; all, so the caret stays where the user left it.
  (let [scripts (atom [])
        adapter {:eval (fn [s]
                         (swap! scripts conj s)
                         (if (str/includes? s " get 1.0 end-1c") "hi" ""))
                 :command! (fn [_ _] nil)}]
    (dsl/forget-widget-options!)
    (binding [tk/*adapter* adapter]
      (let [{:keys [root]} (mounted [:ttk/frame {} [:tk/text {:editable true :text "hi"}]])]
        (t/is (some #(str/includes? % " get 1.0 end-1c") @scripts))
        (t/is (not-any? #(str/includes? % "delete 1.0 end") @scripts)
              "the widget already holds the value: nothing is written")
        (engine/dispose-component root)))))

(t/deftest an-editable-text-refuses-rich-content
  ;; segments would have to disable the widget to render; fail naming the
  ;; prop instead of quietly turning an input into a display surface.
  (with-fake [_rec nil]
    (let [e (try (mounted [:ttk/frame {} [:tk/text {:editable true
                                                    :content [{:text "x"}]}]])
                 nil
                 (catch Exception e e))]
      (t/is (= :kmet.tk.core/editable-with-content (:type (ex-data e))))
      (t/is (= :content (:prop (ex-data e))))
      (t/is (str/includes? (ex-message e) ":content")))))

(t/deftest a-display-text-still-disables-itself
  (with-fake [rec nil]
    (let [{:keys [root]} (mounted [:ttk/frame {} [:tk/text {:text "hi"}]])]
      (t/is (some #(str/includes? % " configure -state disabled") (scripts rec))
            "without :editable the tag stays the read-only surface it was")
      (t/is (not-any? #(str/includes? % "-undo") (scripts rec)))
      (t/is (not-any? #(str/includes? % "<KeyRelease>") (scripts rec))
            "the text slots are declared for both modes but bound only with a handler")
      (engine/dispose-component root))))

;; ─── dialogs ──────────────────────────────────────────────────────────────

(t/deftest tcl-list-quotes-every-element-and-keeps-nesting
  ;; Tk's -filetypes is a list of {label extensions} pairs, so a nested
  ;; sequence must stay one element of its own — quoting the element text
  ;; does that (the whole value is quoted again where it becomes a Tcl
  ;; word, see the dialog scripts below).
  (t/is (= "2 3" (dsl/tcl-list [2 3])))
  (t/is (= "a\\ b c" (dsl/tcl-list ["a b" "c"])))
  (t/is (= "yes 1" (dsl/tcl-list [:yes true])))
  (t/is (= "Clojure\\ *.clj" (dsl/tcl-list [["Clojure" "*.clj"]]))))

(t/deftest a-message-box-scripts-tk-and-answers-with-a-button
  (let [scripts (atom [])
        adapter {:eval (fn [s] (swap! scripts conj s) "yes")}]
    (binding [tk/*adapter* adapter]
      (t/is (= :yes (tk/message-box! {:message "Save?" :detail "lossy"
                                      :type :yes-no :icon :question
                                      :title "kmet" :default :yes}))))
    (t/is (= [(str "tk_messageBox -default yes -detail lossy -icon question"
                   " -message Save? -title kmet -type yesno")]
             @scripts)))
  (binding [tk/*adapter* {:eval (fn [_] "")}]
    (t/is (nil? (tk/message-box! {:message "x"}))
          "a window closed without a button answers nothing")))

(t/deftest file-directory-and-colour-dialogs-script-tk
  (let [scripts (atom [])
        adapter {:eval (fn [s] (swap! scripts conj s) "")}]
    (binding [tk/*adapter* adapter]
      (t/is (nil? (tk/open-file! {:title "Open" :initial-dir "/tmp" :initial-file "a.txt"
                                  :file-types [["Clojure" "*.clj"]]
                                  :default-extension ".clj"})))
      (t/is (= (str "tk_getOpenFile -defaultextension .clj"
                    " -filetypes " (dsl/tcl-quote "Clojure\\ *.clj")
                    " -initialdir /tmp -initialfile a.txt -title Open")
               (last @scripts))
            "file types are Tcl list syntax, quoted into one option word")
      (t/is (nil? (tk/save-file! {:title "Save"})))
      (t/is (= "tk_getSaveFile -title Save" (last @scripts)))
      (t/is (nil? (tk/choose-directory! {:title "Pick" :must-exist true})))
      (t/is (= "tk_chooseDirectory -mustexist 1 -title Pick" (last @scripts)))
      (t/is (nil? (tk/choose-color! {:title "Colour" :initial-color "#ff5500"})))
      (t/is (= "tk_chooseColor -initialcolor \\#ff5500 -title Colour" (last @scripts)))))
  (binding [tk/*adapter* {:eval (fn [_] "C:/picked.txt")}]
    (t/is (= "C:/picked.txt" (tk/open-file! {}))
          "a chosen path comes back as it is")))

(t/deftest a-multiple-file-answer-is-read-with-tcl
  ;; Tk answers a -multiple dialog with a Tcl list whose string form braces
  ;; a path that contains spaces: the reader fetches each element with
  ;; lindex — the path's own spaces survive — and unsets its variable.
  (let [scripts (atom [])
        adapter {:eval (fn [s]
                         (swap! scripts conj s)
                         (cond
                           (str/includes? s "llength") "2"
                           (str/includes? s "lindex") (if (str/ends-with? s " 0")
                                                        "a b.txt" "c.txt")
                           :else ""))}]
    (binding [tk/*adapter* adapter]
      (t/is (= ["a b.txt" "c.txt"] (tk/open-file! {:multiple true :title "Open"}))))
    (t/is (some #(re-find #"^set ::kmet_dialog_\d+ \[tk_getOpenFile -multiple 1 -title Open\]$"
                          %)
                @scripts))
    (t/is (some #(re-find #"^unset ::kmet_dialog_\d+$" %) @scripts)
          "the dialog's private variable is cleaned up"))
  (binding [tk/*adapter* {:eval (fn [s] (if (str/includes? s "llength") "0" ""))}]
    (t/is (nil? (tk/open-file! {:multiple true}))
          "a cancelled multiple dialog answers an empty list")))

(t/deftest a-dialog-rejects-props-and-values-it-cannot-express
  (with-fake [_rec nil]
    (let [err (fn [f] (try (f) nil (catch Exception e e)))]
      (let [e (err #(tk/message-box! {:message "x" :titel "typo"}))]
        (t/is (= :kmet.tk.core/unknown-dialog-prop (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":titel"))
        (t/is (str/includes? (ex-message e) "props:")))
      (let [e (err #(tk/message-box! {:message "x" :type :yes-noo}))]
        (t/is (= :kmet.tk.core/bad-dialog-value (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":type"))
        (t/is (str/includes? (ex-message e) ":yes-noo")))
      (let [e (err #(tk/message-box! {:message "x" :type :yes-no :default :ok}))]
        (t/is (= :kmet.tk.core/bad-dialog-value (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":default")))
      (let [e (err #(tk/message-box! {:message "x" :icon :boss}))]
        (t/is (= :kmet.tk.core/bad-dialog-value (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":icon")))
      (let [e (err #(tk/open-file! {:file-types ["*.clj"]}))]
        (t/is (= :kmet.tk.core/bad-file-types (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":file-types")))
      (let [e (err #(tk/choose-directory! {:title "x" :mustexists true}))]
        (t/is (= :kmet.tk.core/unknown-dialog-prop (:type (ex-data e))))
        (t/is (str/includes? (ex-message e) ":mustexists"))))))

