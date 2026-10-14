(ns kmet.app.ui.gui.transcript
  "GUI chat transcript: one component per session message type, each
   rendering Tk widget hiccup.

   The layout mirrors the TUI transcript — mono lines, the TUI palette, the
   TUI markdown renderer — but every message is its own Tk widget (a
   read-only `:text` block of styled lines), stacked in a `:transcript`
   container so the chat scrolls as one. Components are plain hiccup fns
   (`user-message`, `assistant-message`, `tool-execution`,
   `custom-message`), dispatched by `message-component`; `chat` is the
   scrollable stack and `page` adds the header/footer around it.

   The ANSI the TUI renderers emit is converted to Tk segments here, so
   `kmet.gui.tk` needs no ANSI knowledge and the terminal renderers stay
   the single source of the styling."
  (:require [clojure.string :as str]
            [kmet.libs.reakt :as r]
            [kmet.tui.components.markdown :as tui-md]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]))

(def palette
  "The GUI's surfaces, deliberately distinct from one another: the window
   is the darkest, the transcript canvas sits above it, and every message
   component is a lighter panel with a visible border. Distinct values are
   the point — a component that fails to paint, a missing border or an
   overflowing body is obvious at a glance.

   CTX may override any of them under `:surfaces`."
  {:window    "#07090d"
   :canvas    "#10141b"
   :card      "#1f2735"
   :card-user "#243047"
   :card-tool "#1a2130"
   :border    "#4a5871"})

(defn- surface
  "PALETTE's K, overridable through CTX's :surfaces."
  [ctx k]
  (get-in ctx [:surfaces k] (get palette k)))

;; ═══════════════════════════════════════════════════════════════════════════
;; ANSI -> Tk text segments
;; ═══════════════════════════════════════════════════════════════════════════

;; The TUI renderers (theme/fg, the markdown component) emit ANSI SGR
;; strings; the Tk :text tag takes style maps. The parser below is the
;; bridge: one styled line becomes {:text … :fg "#rrggbb" …} segments. The
;; TUI's default colors (unset fg/bg) stay unset so the widget's own colors
;; show through.

(def ^:private ansi-re #"\u001b\[[0-9;]*m")

(def ^:private basic-16
  ["#000000" "#cd0000" "#00cd00" "#cdcd00" "#0000ee" "#cd00cd" "#00cdcd" "#e5e5e5"
   "#7f7f7f" "#ff0000" "#00ff00" "#ffff00" "#5c5cff" "#ff00ff" "#00ffff" "#ffffff"])

(defn- rgb-hex [r g b]
  (format "#%02x%02x%02x" r g b))

(defn- xterm-256
  "The xterm-256 palette color N as #rrggbb."
  [n]
  (cond
    (< n 16) (nth basic-16 n)
    (< n 232) (let [n (- n 16)
                    levels [0 95 135 175 215 255]]
                (rgb-hex (nth levels (quot n 36))
                         (nth levels (mod (quot n 6) 6))
                         (nth levels (mod n 6))))
    :else (let [v (+ 8 (* 10 (- n 232)))] (rgb-hex v v v))))

(defn- blend-hex
  "A toward B by t (0..1)."
  [a b t]
  (let [p (fn [s] [(parse-long (subs s 1 3)) (parse-long (subs s 3 5)) (parse-long (subs s 5 7))])
        [ar ag ab] (p a) [br bg bb] (p b)
        mix (fn [x y] (int (+ (* x (- 1 t)) (* y t))))]
    (rgb-hex (mix ar br) (mix ag bg) (mix ab bb))))

(defn- apply-sgr
  "STYLE with one SGR sequence applied. Codes that take parameters (38/48)
   consume them from CS."
  [style codes]
  (loop [cs (vec (remove str/blank? (str/split codes #";"))), st style]
    (if-let [_ (first cs)]
      (let [num (fn [i] (or (parse-long (str (nth cs i ""))) 0))
            n (num 0)]
        (cond
          (zero? n) (recur (rest cs) {})
          (= n 1) (recur (rest cs) (assoc st :bold true))
          (= n 2) (recur (rest cs) (assoc st :dim true))
          (= n 3) (recur (rest cs) (assoc st :italic true))
          (= n 4) (recur (rest cs) (assoc st :underline true))
          (= n 7) (recur (rest cs) (assoc st :inverse true))
          (= n 22) (recur (rest cs) (dissoc st :bold :dim))
          (= n 23) (recur (rest cs) (dissoc st :italic))
          (= n 24) (recur (rest cs) (dissoc st :underline))
          (= n 27) (recur (rest cs) (dissoc st :inverse))
          (<= 30 n 37) (recur (rest cs) (assoc st :fg (nth basic-16 (- n 30))))
          (= n 39) (recur (rest cs) (dissoc st :fg))
          (<= 90 n 97) (recur (rest cs) (assoc st :fg (nth basic-16 (+ 8 (- n 90)))))
          (<= 40 n 47) (recur (rest cs) (assoc st :bg (nth basic-16 (- n 40))))
          (= n 49) (recur (rest cs) (dissoc st :bg))
          (<= 100 n 107) (recur (rest cs) (assoc st :bg (nth basic-16 (+ 8 (- n 100)))))
          (= n 38) (if (= 5 (num 1))
                     (recur (drop 3 cs) (assoc st :fg (xterm-256 (num 2))))
                     (recur (drop 5 cs) (assoc st :fg (rgb-hex (num 2) (num 3) (num 4)))))
          (= n 48) (if (= 5 (num 1))
                     (recur (drop 3 cs) (assoc st :bg (xterm-256 (num 2))))
                     (recur (drop 5 cs) (assoc st :bg (rgb-hex (num 2) (num 3) (num 4)))))
          :else (recur (rest cs) st)))
      st)))

(defn- segment
  "A Tk segment for TEXT under STYLE: inverse swaps fg/bg, dim without a
   color falls back to a muted gray, dim with a color blends it toward the
   window background."
  [{:keys [fg bg dim inverse] :as style} text]
  (let [[fg bg] (if inverse [(or bg "#101014") fg] [fg bg])
        fg (cond (and dim fg) (blend-hex fg "#101014" 0.45)
                 (and dim (not fg)) "#7d8590"
                 :else fg)]
    (cond-> {:text text}
      fg (assoc :fg fg)
      bg (assoc :bg bg)
      (:bold style) (assoc :bold true)
      (:italic style) (assoc :italic true)
      (:underline style) (assoc :underline true))))

(defn- ansi-segments
  "LINE split into styled segments (ANSI SGR in, Tk style maps out)."
  [line]
  (let [codes (re-seq ansi-re line)
        texts (str/split line ansi-re)]
    (loop [i 0, style {}, out []]
      (if (< i (count texts))
        (let [t (nth texts i)]
          (recur (inc i)
                 (if-let [c (nth codes i nil)]
                   (apply-sgr style (subs c 2 (dec (count c))))
                   style)
                 (if (seq t) (conj out (segment style t)) out)))
        out))))

(defn lines->segments
  "Styled LINES -> the Tk :text :content vector (one newline segment between
   lines; blank lines stay blank)."
  [lines]
  (vec (apply concat (interpose [{:text "\n"}] (mapv ansi-segments lines)))))

(defn- token-hex
  "The #rrggbb value behind a theme token's ANSI escape, or nil."
  [t kind token]
  (let [esc (try ((if (= kind :bg) theme/get-bg-ansi theme/get-fg-ansi) t token)
                 (catch Exception _ nil))]
    (when (and esc (seq esc))
      (kind (first (ansi-segments (str esc "x")))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Line renderers (the TUI's look, one function per message type)
;; ═══════════════════════════════════════════════════════════════════════════

(defn- ellipsis [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 (max 0 (- n 1))) "…") s)))

(defn- text-of
  "The display text of a content value: a string, or content blocks
   ({:type :text :text …} / {:type :tool_result :content …})."
  [content]
  (cond
    (string? content) content
    (vector? content) (str/join "\n"
                                (keep (fn [b]
                                        (cond
                                          (string? b) b
                                          (string? (:text b)) (:text b)
                                          (string? (:content b)) (:content b)
                                          (= :image (:type b)) "[image]"
                                          :else nil))
                                      content))
    (nil? content) ""
    :else (str content)))

(defn- md-lines
  "The TUI markdown renderer's lines at WIDTH (ANSI, exactly what the
   terminal would show)."
  [ctx text]
  (protocols/render (tui-md/make-markdown (str text)
                                          :theme (theme/get-markdown-theme (:theme ctx))
                                          :padding-x 0)
                    (max 20 (:width ctx))))

(defn- cap-lines
  "LINES capped at MAX, noting what was dropped in the dim style."
  [t lines max]
  (if (<= (count lines) max)
    lines
    (concat (take max lines)
            [(theme/fg t :dim (str "  … +" (- (count lines) max) " more lines"))])))

(defn- wrap-styled
  "Wrap one styled line to the transcript width. Wrapping happens here, in
   the component, so the card holds whole display lines and its height
   stays exact; the widget's own `-wrap` is only a safety net for content
   the renderer did not produce."
  [ctx line]
  (u/wrap-text-with-ansi line (max 20 (:width ctx))))

(defn- user-lines [entry ctx]
  (md-lines ctx (text-of (:content entry))))

(defn- thinking-lines [entry ctx]
  (let [t (:theme ctx)
        th (str/trim (str (:thinking entry)))]
    (when (seq th)
      (concat [(theme/fg t :muted (theme/italic "✦ thinking"))]
              (cap-lines t (map #(theme/fg t :thinking-text (theme/italic %))
                                (wrap-styled ctx th))
                         6)
              [""]))))

(defn- tool-call-lines [entry ctx]
  (let [t (:theme ctx)]
    (mapcat (fn [{:keys [name arguments]}]
              (cap-lines t (wrap-styled ctx
                                        (str (theme/fg t :tool-title (str "→ " name " "))
                                             (theme/fg t :muted (pr-str arguments))))
                         4))
            (:tool-calls entry))))

(defn- assistant-lines [entry ctx]
  (let [text (text-of (:content entry))]
    (concat (thinking-lines entry ctx)
            (when (seq (str/trim (str text)))
              (md-lines ctx text))
            (tool-call-lines entry ctx))))

(defn- tool-lines [entry ctx]
  (let [t (:theme ctx)
        content (str/trim (text-of (:content entry)))
        lines (if (str/blank? content) ["(no output)"] (str/split-lines content))
        color (if (:is-error entry) :error :tool-output)]
    (cap-lines t (mapcat #(wrap-styled ctx (theme/fg t color %)) lines) 12)))

(defn- custom-lines [entry ctx]
  (let [t (:theme ctx)]
    (case (:role entry)
      :model-change
      [(str (theme/fg t :muted (str "⇄ model " (or (:model entry) "?")))
            (when-let [p (:provider entry)]
              (str " " (theme/fg t :dim (str "(" (name p) ")")))))]

      (cap-lines t (wrap-styled ctx (theme/fg t :muted
                                              (pr-str (dissoc entry :content :role :id
                                                              :parent-id :timestamp))))
                 4))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Components
;; ═══════════════════════════════════════════════════════════════════════════

;; Every message is a bordered card: a clickable title bar and, unless
;; collapsed, a body — each its own Tk widget. Collapse state lives in the
;; caller's CTX atom of entry ids; the title's click callback toggles it and
;; schedules a frame.

(defn- text-block
  "A read-only :text widget showing LINES, pre-wrapped to the transcript
   width by the renderers, sized to them in lines and characters (the host
   text widget scrolls it). `:wrap word` is the safety net: if a line ever
   reaches the widget longer than its width, Tk wraps it instead of
   clipping it."
  [ctx lines & [opts]]
  [:tk/text (merge {:content (lines->segments lines)
                    :height (max 1 (count lines))
                    :width (:width ctx)
                    :wrap "word"
                    :font-family (:font-family ctx)
                    :font-size (:font-size ctx)
                    :bg (or (:bg ctx) (surface ctx :canvas))
                    :fg (:fg ctx)}
                   opts)])

(defn- card-title
  "The clickable title bar of a message card: the collapse marker, the role
   glyph and label, on the card background."
  [ctx {:keys [id text fg bg collapsed?]}]
  [:ttk/label (cond-> {:text (str (if collapsed? "▸ " "▾ ") text)
                       :fg fg
                       :bg bg
                       :bold true
                       :font-family (:font-family ctx)
                       :font-size (:font-size ctx)}
                (and (:collapsed ctx) (:toggle ctx))
                (assoc :on-click (fn [] ((:toggle ctx) id))))])

(defn- card
  "A bordered message card: a distinct panel color against the transcript
   canvas, with a clickable title. The body is omitted while collapsed.

   The collapse set is read through `r/tracked-deref`: this body also reads
   tracked inputs, so without the tracked read the engine would cache the
   tree and a click would repaint nothing."
  [ctx {:keys [id title title-fg body-lines body-bg]}]
  (let [collapsed? (and (:collapsed ctx)
                        (contains? (r/tracked-deref (:collapsed ctx)) id))
        bg (or body-bg (:bg ctx) (surface ctx :card))]
    (into [:tk/frame {:border 1 :border-color (surface ctx :border)
                      :bg bg :padx 2 :pady 2}]
          [(card-title ctx {:id id :text title :fg title-fg :bg bg
                            :collapsed? collapsed?})
           (when-not collapsed?
             (text-block ctx body-lines {:bg bg :padx 6 :pady 2}))])))

(defn user-message
  "The user's message: an accent title over the markdown body, on the
   user-message background (the TUI's user box)."
  [entry ctx]
  (card ctx {:id (:id entry)
             :title "you"
             :title-fg (token-hex (:theme ctx) :fg :accent)
             :body-bg (or (token-hex (:theme ctx) :bg :user-message-bg)
                          (surface ctx :card-user))
             :body-lines (user-lines entry ctx)}))

(defn assistant-message
  "The assistant's message: title + model, then the thinking block, the
   markdown body, and the tool calls it requested."
  [entry ctx]
  (let [model (when-let [m (:model entry)] (str "  " (ellipsis m 40)))]
    (card ctx {:id (:id entry)
               :title (str "assistant" model)
               :title-fg (token-hex (:theme ctx) :fg :accent)
               :body-lines (assistant-lines entry ctx)})))

(defn tool-execution
  "A tool result: its name and the (wrapped, capped) output."
  [entry ctx]
  (card ctx {:id (:id entry)
             :title (str (or (:tool-name entry) "tool")
                         (when (:is-error entry) "  (error)"))
             :title-fg (token-hex (:theme ctx) :fg (if (:is-error entry) :error :muted))
             :body-bg (surface ctx :card-tool)
             :body-lines (tool-lines entry ctx)}))

(defn custom-message
  "Everything else (model changes, info entries, unknown roles)."
  [entry ctx]
  (card ctx {:id (:id entry)
             :title (name (or (:role entry) :entry))
             :title-fg (token-hex (:theme ctx) :fg :muted)
             :body-lines (custom-lines entry ctx)}))

(defn message-component
  "The component for a session ENTRY — its :role picks the type."
  [entry ctx]
  (case (:role entry)
    :user (user-message entry ctx)
    :assistant (assistant-message entry ctx)
    :tool (tool-execution entry ctx)
    (custom-message entry ctx)))

(defn chat
  "The scrollable chat: one card widget per session entry on the canvas
   surface, scrolled to the end once the window is mapped.

   The entries are read through `r/tracked-deref`: appending to the session
   (a streaming assistant, a new tool result) is what invalidates the
   mounted body, so the transcript repaints as the conversation grows."
  [session ctx]
  (into [:tk/transcript {:see-end true :read-only true
                         :fill :both :expand true
                         :bg (surface ctx :canvas)}]
        (map #(message-component % ctx) (r/tracked-deref (:entries session)))))

(defn page
  "The window content: session header over the scrollable chat. Pack order
   matters (Tk pack, not grid): the fixed header claims the top first, then
   the transcript takes the rest."
  [session ctx]
  (let [t (:theme ctx)
        id (str (:id session))
        cwd (str (or (get-in session [:header :cwd]) ""))
        entries (r/tracked-deref (:entries session))
        title (str (theme/fg t :accent (theme/bold "kmet"))
                   (theme/fg t :muted (str "  session "
                                           (subs id 0 (min 8 (count id)))
                                           (when (seq cwd) (str "  ·  " cwd))
                                           "  ·  " (count entries) " entries"))
                   (theme/fg t :dim "  ·  click a title to collapse"))
        rule (theme/fg t :border (apply str (repeat (:width ctx) "─")))]
    ;; :expand makes the vbox fill the toplevel, so the transcript's fill
    ;; both/expand 1 gets the whole area under the header.
    [:ttk/frame {:expand true}
     (text-block ctx [title rule] {:bg (surface ctx :canvas)})
     (chat session ctx)]))

(defn live-page
  "PAGE as a mountable component fn.

   A static hiccup vector is parsed once, so nothing a click does can reach
   the tree again; a fn root is re-run whenever an atom its body derefed
   (the session's entries, CTX's collapse set) changes, which is what makes
   the card titles live. Mount this with kmet.libs.hikap/root or
   kmet.gui.tcl/run!."
  [session ctx]
  (fn [_props] (page session ctx)))
