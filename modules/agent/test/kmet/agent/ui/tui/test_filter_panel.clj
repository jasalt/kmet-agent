(ns kmet.agent.ui.tui.test-filter-panel
  "Filter-panel base tests — navigation (wrap/clamp), confirm, ctrl+c
   clear/cancel, extra-key precedence, the visible window, and chrome/empty
   rendering."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.agent.keybindings :as kb]
            [kmet.agent.ui.tui.filter-panel :as fp]
            [kmet.tui.core :as core]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(defn- items [n] (mapv #(str "item-" %) (range n)))

(defn- row-node [_th _st item _idx selected?]
  [:text {:key item :padding-x 1 :padding-y 0
          :text (str (if selected? "→ " "  ") item)}])

(defn- panel
  [& {:keys [n max-visible nav extra clear-on-ctrl-c? render-empty
             on-select on-cancel search-resets? chrome]}]
  (let [dir (str (fs/create-dirs (fs/path "target" "test-filter-panel-keybindings")))]
    (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir)))
  (fp/make-panel
   (fn [st]
     (let [q (str/lower-case (:search st))]
       (if (str/blank? q)
         (:items st)
         (vec (filter #(str/includes? (str/lower-case %) q) (:items st))))))
   row-node
   :chrome (or chrome (fn [_ _] {:header [] :footer []}))
   :extra-key extra
   :state {:items (items (or n 5))}
   :max-visible (or max-visible 10)
   :nav nav
   :empty-text "No matches"
   :render-empty render-empty
   :clear-on-ctrl-c? clear-on-ctrl-c?
   :search-resets-selection? (if (nil? search-resets?) true search-resets?)
   :on-select on-select
   :on-cancel on-cancel))

(defn- press [sel key]
  (protocols/handle-input
   sel
   (case key
     "enter" "\r"
     "escape" "\u001b"
     "up" "\u001b[A"
     "down" "\u001b[B"
     "ctrl+c" "\u0003"
     "ctrl+p" (str (char 16))
     key)))

(defn- rendered [sel]
  (mapv u/strip-ansi-codes (protocols/render sel 80)))

(defn- rows
  "The rendered item rows, in order (the search input line, which echoes the
   typed text, is not a row)."
  [sel]
  (filterv #(and (str/includes? % "item-")
                 (not (str/starts-with? (str/triml %) ">")))
           (rendered sel)))

(defn- selected-row [sel]
  (first (filter #(str/includes? % "→ ") (rows sel))))

;; ─── Window and navigation ─────────────────────────────────────────────────

(t/deftest test-window-slices-to-max-visible
  (let [sel (panel :max-visible 2)]
    (t/is (= 2 (count (rows sel))) "only the window renders")
    (t/is (str/includes? (first (rows sel)) "→ item-0") "the selection leads")))

(t/deftest test-wrap-navigation
  (let [sel (panel :n 4)]
    (press sel "up")
    (t/is (str/includes? (selected-row sel) "item-3") "up wraps to the bottom")
    (press sel "down")
    (t/is (str/includes? (selected-row sel) "item-0") "down wraps to the top")))

(t/deftest test-clamp-navigation
  (let [sel (panel :n 3 :nav :clamp)]
    (press sel "up")
    (t/is (str/includes? (selected-row sel) "item-0") "up clamps at the top")
    (press sel "down")
    (press sel "down")
    (t/is (str/includes? (selected-row sel) "item-2") "down reaches the last row")
    (press sel "down")
    (t/is (str/includes? (selected-row sel) "item-2") "down clamps at the bottom")))

(t/deftest test-window-follows-selection
  (let [sel (panel :n 5 :max-visible 2)]
    (press sel "down")
    (press sel "down")
    (t/is (= ["item-1" "→ item-2"] (mapv str/trim (rows sel)))
          "the window scrolls to keep the selection visible")))

(t/deftest test-selection-helpers
  (t/is (= 1 (fp/index-of #(= % "b") ["a" "b" "c"])))
  (t/is (nil? (fp/index-of #(= % "z") ["a" "b"])))
  (t/is (= "a" (fp/item-at ["a" "b"] 0)))
  (t/is (= "b" (fp/item-at ["a" "b"] 9)) "a stale index clamps to the last item")
  (t/is (nil? (fp/item-at [] 0))))

(t/deftest test-visible-window-math
  (t/is (= [0 2] (fp/visible-window 0 5 2)))
  (t/is (= [1 3] (fp/visible-window 2 5 2)))
  (t/is (= [3 5] (fp/visible-window 4 5 2)))
  (t/is (= [0 0] (fp/visible-window 0 0 2))))

(t/deftest test-stepped-selection
  (t/is (= 1 (fp/stepped-selected :wrap 0 1 3)) "wrap: down from the top")
  (t/is (= 0 (fp/stepped-selected :wrap 2 1 3)) "wrap: down from the last")
  (t/is (= 2 (fp/stepped-selected :wrap 0 -1 3)) "wrap: up from the top")
  (t/is (= 0 (fp/stepped-selected :clamp 0 -1 3)) "clamp: up at the top")
  (t/is (= 2 (fp/stepped-selected :clamp 2 1 3)) "clamp: down at the last")
  (t/is (= 0 (fp/stepped-selected :wrap 9 1 3))
        "a stale index is clamped before the step")
  (t/is (= 0 (fp/stepped-selected :wrap 0 1 0)) "an empty list has no selection")
  (t/is (= 0 (fp/stepped-selected :clamp 0 -1 0)) "an empty list has no selection"))

;; ─── Confirm and search ────────────────────────────────────────────────────

(t/deftest test-confirm-selects-the-highlighted-item
  (let [selected (atom nil)
        sel (panel :on-select #(reset! selected %))]
    (press sel "down")
    (press sel "enter")
    (t/is (= "item-1" @selected))))

(t/deftest test-confirm-with-no-match-is-a-no-op
  (let [selected (atom ::none)
        sel (panel :on-select #(reset! selected %))]
    (doseq [c "zzz"] (press sel (str c)))
    (t/is (empty? (rows sel)))
    (press sel "enter")
    (t/is (= ::none @selected))))

(t/deftest test-type-filters-and-resets-the-selection
  (let [sel (panel)]
    (press sel "down")
    (press sel "down")
    (doseq [c "item-3"] (press sel (str c)))
    (t/is (= ["→ item-3"] (mapv #(str/replace (str/trim %) #"\s+" " ") (rows sel)))
          "the filter narrows the list")
    (t/is (str/includes? (selected-row sel) "item-3")
          "the selection returns to the top match")))

(t/deftest test-search-keeps-the-selection-when-asked
  (let [sel (panel :search-resets? false)]
    (press sel "down")
    (press sel "down")
    (press sel "i")
    (t/is (= 2 (fp/clamp-selected (:selected-idx @(:state-atom sel)) (count (rows sel))))
          "the stored index is preserved (clamped at render)")))

;; ─── Ctrl+C policies ───────────────────────────────────────────────────────

(t/deftest test-ctrl-c-cancels-by-default
  (let [cancelled (atom false)
        sel (panel :on-cancel #(reset! cancelled true))]
    (doseq [c "item"] (press sel (str c)))
    (press sel "ctrl+c")
    (t/is (true? @cancelled) "ctrl+c cancels (tui.select.cancel) even with a search")
    (t/is (= "item" (:search @(:state-atom sel))) "the search is untouched")))

(t/deftest test-ctrl-c-clears-a-search-first-when-enabled
  (let [cancelled (atom false)
        sel (panel :clear-on-ctrl-c? true :on-cancel #(reset! cancelled true))]
    (doseq [c "item-3"] (press sel (str c)))
    (t/is (= 1 (count (rows sel))) "typing filters")
    (press sel "ctrl+c")
    (t/is (false? @cancelled) "ctrl+c with a search clears it, not cancel")
    (t/is (= 5 (count (rows sel))) "the search cleared")
    (press sel "ctrl+c")
    (t/is (true? @cancelled) "ctrl+c with an empty search cancels")))

(t/deftest test-escape-cancels
  (let [cancelled (atom false)
        sel (panel :on-cancel #(reset! cancelled true))]
    (press sel "escape")
    (t/is (true? @cancelled))))

;; ─── Extra keys ────────────────────────────────────────────────────────────

(t/deftest test-extra-key-wins-over-navigation
  (let [toggled (atom false)
        sel (panel :n 3
                   :extra (fn [_st data]
                            (when (= data (str (char 16)))
                              (reset! toggled true))))]
    ;; ctrl+p is both tui.select.up and the app's provider toggle
    (press sel "ctrl+p")
    (t/is (true? @toggled) "the extra key consumed ctrl+p")
    (t/is (str/includes? (selected-row sel) "item-0")
          "generic navigation did not also fire")))

;; ─── Chrome and empty state ────────────────────────────────────────────────

(t/deftest test-chrome-renders-header-and-footer-once
  (let [calls (atom 0)
        sel (panel :chrome (fn [_th _st]
                             (swap! calls inc)
                             {:header [[:text {:padding-x 1 :padding-y 0 :text "HEADER"}]]
                              :footer [[:text {:padding-x 1 :padding-y 0 :text "FOOTER"}]]}))
        lines (rendered sel)]
    (t/is (= 1 @calls) "chrome is computed once per render")
    (t/is (some #(str/includes? % "HEADER") lines))
    (t/is (some #(str/includes? % "FOOTER") lines))))

(t/deftest test-empty-text
  (let [sel (panel :n 0)]
    (t/is (true? (boolean (some #(str/includes? % "No matches") (rendered sel)))))))

(t/deftest test-render-empty-overrides-empty-text
  (let [sel (panel :n 0 :render-empty (fn [_th _st]
                                        [:text {:padding-x 1 :padding-y 0
                                                :text "CUSTOM-EMPTY"}]))]
    (t/is (true? (boolean (some #(str/includes? % "CUSTOM-EMPTY") (rendered sel)))))
    (t/is (not-any? #(str/includes? % "No matches") (rendered sel)))))

;; ─── Dock lifetime ─────────────────────────────────────────────────────────

(t/deftest test-show-and-close
  (let [cs {:tui (core/create-tui nil)
            :dock-stack (atom [])
            :current-editor-atom (atom nil)}
        sel (panel :n 3)
        sel-atom (atom nil)]
    (fp/show-panel! cs sel-atom sel)
    (t/is (identical? sel @sel-atom))
    (t/is (= sel (:component (first @(:dock-stack cs)))))
    (fp/panel-close! cs sel-atom)
    (t/is (empty? @(:dock-stack cs)))))
