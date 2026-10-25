(ns kmet.agent.test-loop-message
  (:require [clojure.test :as t]
            [kmet.app.loop.message :as message]))

(t/deftest test-loop-drop-incomplete-tool-calls
  ;; A corrupted session (assistant tool calls without their results, or
  ;; orphaned tool results) must be repaired before it reaches a provider
  ;; that requires a tool message per tool_call_id.
  (let [tc-msg (fn [& ids]
                 {:role :assistant :content [{:type :text :text "hi"}]
                  :tool-calls (mapv (fn [id] {:id id :name "bash" :arguments {}})
                                    ids)})
        tr-msg (fn [id]
                 {:role :tool :content [{:type :tool_result :tool_use_id id :content "out"}]})
        user-msg {:role :user :content [{:type :text :text "q"}]}
        healthy [(tc-msg "a") (tr-msg "a") user-msg]]
    ;; healthy conversation passes through unchanged
    (t/is (= healthy (message/drop-incomplete-tool-calls healthy)))
    ;; unanswered tool calls are dropped from their assistant message
    (t/is (= [(tc-msg "a") (tr-msg "a")]
             (message/drop-incomplete-tool-calls [(tc-msg "a" "b") (tr-msg "a")])))
    ;; orphaned tool results (no matching tool call) are dropped
    (t/is (= [(tc-msg "a") (tr-msg "a")]
             (message/drop-incomplete-tool-calls [(tr-msg "zzz") (tc-msg "a") (tr-msg "a")])))
    ;; an assistant message left without content and without tool calls is
    ;; dropped entirely
    (t/is (= []
             (message/drop-incomplete-tool-calls
              [{:role :assistant :content []
                :tool-calls [{:id "a" :name "bash" :arguments {}}]}])))
    ;; text/thinking on the assistant message survives the tool-call drop
    (t/is (= [{:role :assistant :content [] :thinking "hmm"}]
             (message/drop-incomplete-tool-calls
              [{:role :assistant :content [] :thinking "hmm"
                :tool-calls [{:id "a" :name "bash" :arguments {}}]}])))
    ;; a second batch's tool calls don't answer the first batch's orphans
    (t/is (= [(tc-msg "a") (tr-msg "a") user-msg
              {:role :assistant :content [{:type :text :text "hi"}]}]
             (message/drop-incomplete-tool-calls
              [(tc-msg "a") (tr-msg "a") user-msg (tc-msg "b")])))))

(t/deftest test-loop-block-images-noncanonical-tool-message
  ;; Defensive shape (direct filter call — such a message is normally dropped
  ;; by drop-incomplete-tool-calls before this filter runs): string content
  ;; with :images still loses the images (nothing may reach the provider)
  ;; without the content rewrite blowing up.
  (let [out (message/block-message-images
             [{:role :tool :content "plain" :images [{:data "AA"}]}])]
    (t/is (nil? (:images (first out))) "images dropped")
    (t/is (= "plain" (:content (first out))) "content left as-is")))
