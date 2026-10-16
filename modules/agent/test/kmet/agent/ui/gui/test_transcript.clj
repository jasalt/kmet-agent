(ns kmet.agent.ui.gui.test-transcript
  "Tests for the GUI transcript components: one bordered card per message
   type with a clickable title, and the scrollable chat composition."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.agent.ui.gui.transcript :as transcript]
            [kmet.tui.theme :as theme]))

(defn- ctx [& [opts]]
  (merge {:width 80 :font-size 12 :theme theme/dark-theme
          :bg "#101014" :fg "#d7dbe0"}
         opts))

(defn- session [entries]
  {:id "1a135a01deadbeef" :header {:cwd "/tmp/x"} :entries (atom entries)})

(defn- children [el] (vec (drop 2 el)))
(defn- title-el [el] (first (children el)))
(defn- body-el [el] (second (children el)))
(defn- body-segments [el] (:content (second (body-el el))))
(defn- body-text [el] (apply str (map :text (body-segments el))))
(defn- segment-for [el s] (first (filter #(= s (:text %)) (body-segments el))))

(t/deftest user-message-is-a-bordered-card
  (let [el (transcript/user-message
            {:id "m1" :role :user :content [{:type :text :text "hello world"}]}
            (ctx))
        [tag props] el]
    (t/is (= :tk/frame tag))
    (t/is (= 1 (:border props)) "the card is bordered")
    (t/is (re-matches #"#[0-9a-f]{6}" (str (:border-color props)))
          "the border has its own colour (solid relief would be invisible)")
    (t/is (re-matches #"#[0-9a-f]{6}" (str (:bg props))))
    (t/is (not= (:bg props) (transcript/palette :canvas))
          "a component panel is distinct from the canvas it sits on")
    (t/is (= :ttk/label (first (title-el el))))
    (t/is (str/includes? (:text (second (title-el el))) "you"))
    (t/is (= :tk/text (first (body-el el))))
    (t/is (str/includes? (body-text el) "hello world"))
    (t/is (= (count (body-segments el)) 1) "one markdown line")))

(t/deftest title-click-toggles-the-body
  (let [collapsed (atom #{"m1"})
        toggled (atom nil)
        c (ctx {:collapsed collapsed
                :toggle (fn [id] (reset! toggled id))})
        collapsed-el (transcript/tool-execution
                      {:id "m1" :role :tool :tool-name "bash"
                       :content [{:type :tool_result :content "out"}]}
                      c)
        expanded-el (transcript/tool-execution
                     {:id "m2" :role :tool :tool-name "bash"
                      :content [{:type :tool_result :content "out"}]}
                     c)]
    (t/is (nil? (body-el collapsed-el)) "a collapsed card has no body widget")
    (t/is (str/starts-with? (:text (second (title-el collapsed-el))) "▸"))
    (t/is (str/starts-with? (:text (second (title-el expanded-el))) "▾"))
    (t/is (= :tk/text (first (body-el expanded-el))))
    (let [click (get (second (title-el expanded-el)) :on-click)]
      (t/is (fn? click))
      (click)
      (t/is (= "m2" @toggled) "the title's click callback toggles its entry"))))

(t/deftest assistant-message-carries-thinking-markdown-and-tool-calls
  (let [entry {:id "a1" :role :assistant
               :model "deepseek/x"
               :thinking (str/join "\n" (repeat 10 "pondering"))
               :content [{:type :text :text "some **bold** text"}]
               :tool-calls [{:name "bash" :arguments {:command "ls"}}]}
        el (transcript/assistant-message entry (ctx))
        content (body-text el)]
    (t/is (str/includes? (:text (second (title-el el))) "assistant"))
    (t/is (str/includes? (:text (second (title-el el))) "deepseek/x"))
    (t/is (str/includes? content "✦ thinking"))
    (t/is (str/includes? content "more lines") "long thinking is truncated")
    (t/is (str/includes? content "bold"))
    (t/is (some #(and (= "bold" (:text %)) (:bold %)) (body-segments el))
          "markdown emphasis survives as a bold segment")
    (t/is (str/includes? content "→ bash")
          "tool calls render in the assistant body")))

(t/deftest tool-execution-shows-and-truncates-output
  (let [long (str/join "\n" (map #(str "line " %) (range 20)))
        el (transcript/tool-execution
            {:id "t1" :role :tool :tool-name "bash"
             :content [{:type :tool_result :content long}]}
            (ctx))
        content (body-text el)]
    (t/is (str/includes? (:text (second (title-el el))) "bash"))
    (t/is (str/includes? content "line 0"))
    (t/is (str/includes? content "+8 more lines"))))

(t/deftest tool-error-uses-the-error-color
  (let [entry {:id "t1" :role :tool :tool-name "bash" :is-error true
               :content [{:type :tool_result :content "boom"}]}
        ok (transcript/tool-execution (assoc entry :is-error false) (ctx))
        err (transcript/tool-execution entry (ctx))]
    (t/is (str/includes? (:text (second (title-el err))) "(error)"))
    (t/is (some? (:fg (segment-for err "boom"))))
    (t/is (not= (:fg (segment-for ok "boom")) (:fg (segment-for err "boom")))
          "the error variant colors the output differently")))

(t/deftest custom-message-covers-other-roles
  (let [el (transcript/custom-message {:id "c1" :role :model-change
                                       :model "x/y" :provider :p}
                                      (ctx))]
    (t/is (str/includes? (:text (second (title-el el))) "model-change"))
    (t/is (str/includes? (body-text el) "⇄ model x/y"))))

(t/deftest long-lines-wrap-instead-of-truncating
  (let [long (str/join " " (repeat 40 "word"))
        el (transcript/tool-execution
            {:id "t1" :role :tool :tool-name "bash"
             :content [{:type :tool_result :content long}]}
            (ctx))
        content (body-text el)]
    (t/is (str/includes? content "word word"))
    (t/is (not (str/includes? content "…"))
          "a long line wraps instead of being truncated")
    (t/is (every? #(<= (count (:text %)) (:width (ctx))) (body-segments el))
          "every segment fits the component width, so Tk never clips it")))

(t/deftest chat-is-a-scroll-container-with-one-card-per-entry
  (let [s (session [{:id "m1" :role :user :content [{:type :text :text "hi"}]}
                    {:id "m2" :role :assistant :content [{:type :text :text "hey"}]}
                    {:id "m3" :role :tool :tool-name "read"
                     :content [{:type :tool_result :content "x"}]}])
        el (transcript/chat s (ctx))
        kids (children el)]
    (t/is (= :ui/transcript (first el)))
    (t/is (true? (:see-end (second el))) "the chat scrolls to the end once mapped")
    (t/is (= 3 (count kids)))
    (t/is (every? #(= :tk/frame (first %)) kids))
    (t/is (every? #(= 1 (:border (second %))) kids))
    (t/is (= (transcript/palette :canvas) (:bg (second el)))
          "the transcript scrolls over the canvas surface")))

(t/deftest page-wraps-header-and-chat
  (let [s (session [{:id "m1" :role :user :content [{:type :text :text "hi"}]}])
        el (transcript/page s (ctx))
        kids (children el)]
    (t/is (= :ttk/frame (first el)))
    (t/is (= 2 (count kids)))
    (t/is (= :tk/text (first (first kids))))
    (t/is (= :ui/transcript (first (second kids))))
    (t/is (str/includes? (apply str (map :text (:content (second (first kids))))) "kmet"))
    (t/is (str/includes? (apply str (map :text (:content (second (first kids))))) "1a135a01"))))
