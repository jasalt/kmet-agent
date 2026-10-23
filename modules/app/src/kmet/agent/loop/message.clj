(ns kmet.agent.loop.message
  "Message constructors and wire-shape transforms for the agent loop.

   Image attachments (pi: prompt(text, images)): run-agent-turn accepts
   :images — a vector of {:type :image :data base64 :mime-type str} blocks
   attached to the initial user message; they flow to the provider as OpenAI
   image_url / Anthropic image blocks. Input hooks receive and can transform
   :images (extensions/apply-input-hooks). The :block-images setting (pi:
   images.blockImages) strips every image from the wire messages at request
   time, leaving the transcript and the session intact — see
   block-message-images.

   drop-incomplete-tool-calls repairs a corrupted conversation (tool calls
   without their results, or orphaned results) before it reaches a provider."
  (:require [clojure.string :as str]
            [kmet.ai.api.shared :as shared]))

(defn user-message
  "Build a user message map. text — string; images — optional vector of
   {:type :image :data base64 :mime-type str} blocks appended after the
   text block (pi: user message content with image attachments)."
  [text & [images]]
  {:role :user
   :content (into [{:type :text :text text}]
                  (or images []))})

(defn assistant-message [text thinking tool-calls usage]
  (let [content (if (seq text) [{:type :text :text text}] [])]
    (cond-> {:role :assistant :content content}
      (seq thinking) (assoc :thinking thinking)
      (seq tool-calls) (assoc :tool-calls tool-calls)
      usage (assoc :usage usage))))

(defn tool-result-message [tc-id tc-name result]
  (cond-> {:role :tool
           :content [{:type :tool_result
                      :tool_use_id tc-id
                      :content (:content result)}]
           :tool-name tc-name
           :is-error (:is-error result false)}
    (:images result) (assoc :images (:images result))
    (:truncation result) (assoc :truncation (:truncation result))
    (:details result) (assoc :details (:details result))))

(defn normalize-content
  "Normalize a message's :content to the canonical block vector: a string
   becomes one text block, nil an empty vector, blocks pass through."
  [content]
  (cond
    (string? content) [{:type :text :text content}]
    (nil? content) []
    :else content))

(def ^:private blocked-image-placeholder
  "Text that replaces a blocked image in a provider request (pi:
   convertToLlmWithBlockImages — \"Image reading is disabled.\")."
  "Image reading is disabled.")

(defn- append-blocked-placeholder
  "Append the blocked-image placeholder to a tool result's own content
   (string or block vector). One placeholder stands for any number of
   images, matching pi's consecutive-placeholder dedupe in
   convertToLlmWithBlockImages."
  [content]
  (cond
    (nil? content) blocked-image-placeholder
    (string? content) (if (str/blank? content)
                        blocked-image-placeholder
                        (str content "\n" blocked-image-placeholder))
    (vector? content) (conj (vec content) {:type :text :text blocked-image-placeholder})
    :else blocked-image-placeholder))

(defn- block-images-content
  "Replace every image block in a content vector with the blocked-image
   placeholder text block, collapsing consecutive placeholders (pi:
   convertToLlmWithBlockImages)."
  [content]
  (let [placeholder {:type :text :text blocked-image-placeholder}]
    (reduce (fn [acc b]
              (let [b (if (shared/image-block? b) placeholder b)]
                (if (and (= b placeholder) (= (peek acc) placeholder))
                  acc
                  (conj acc b))))
            []
            content)))

(defn block-message-images
  "pi: convertToLlmWithBlockImages — with :block-images on, strip every image
   from the wire messages, replacing it with a placeholder text (kmet carries
   user/custom images as content blocks and tool-result images in the
   message's :images; pi reaches the same set — its custom→user conversion
   happens before the filter, so custom images are caught too). Applied to
   the LLM request only: the stored context and the session keep their
   images, and the transcript still renders them. Like pi (whose
   convertToLlmWithBlockImages wrapper sits in the agent loop while
   compaction calls the plain convertToLlm), the compaction/branch-summary
   calls are NOT filtered.

   Defensive on shape: :images is dropped whatever the content (pi filters
   any toolResult image), but the placeholder is only woven into the
   canonical message shape — an extension-injected tool message carrying
   string content is stripped without a content rewrite."
  [messages]
  (mapv (fn [m]
          (case (:role m)
            (:user :custom) (if (vector? (:content m))
                              (update m :content block-images-content)
                              m)
            :tool (if (seq (:images m))
                    (cond-> (dissoc m :images)
                      (map? (first (:content m)))
                      (assoc-in [:content 0 :content]
                                (append-blocked-placeholder
                                 (-> m :content first :content))))
                    m)
            m))
        messages))

(defn drop-incomplete-tool-calls
  "Defensive repair of a corrupted conversation: strict providers (OpenAI-style,
   Mistral, Anthropic, Gemini) reject an assistant message whose tool calls are
   not all answered by tool result messages (and orphaned tool results with no
   matching tool call). Sessions can end up that way when the process dies
   between recording an assistant tool-call message and its results, or when a
   tool batch is interrupted mid-run. Drops the unmatched tool calls from their
   assistant message (keeping any text/thinking) and drops orphaned tool
   results; an assistant message left with no content at all is dropped.
   Healthy pi-style conversations never need this — pi always records a tool
   result per call (failToolCallsFromTruncatedMessage attaches error results)
   — so it only triggers on corruption.

   Returns a new message vector; the input is not mutated."
  [messages]
  (let [answered (volatile! #{})
        ;; Pass 1: which assistant tool-call ids actually have a following tool
        ;; result. Non-tool messages close the current batch; an assistant tool
        ;; message opens a new one.
        _ (loop [pending #{} ;; tool-call ids of the open batch still awaiting a result
                 msgs (seq messages)]
            (when-let [m (first msgs)]
              (let [role (:role m)]
                (cond
                  (= role :tool)
                  (let [id (-> m :content first :tool_use_id)]
                    (if (and id (contains? pending id))
                      (do (vswap! answered conj id)
                          (recur (disj pending id) (next msgs)))
                      ;; orphaned tool result — not answered
                      (recur pending (next msgs))))
                  (and (= role :assistant) (seq (:tool-calls m)))
                  ;; new batch; the previous batch's leftover pending ids die
                  (recur (into #{} (keep :id) (:tool-calls m)) (next msgs))
                  :else
                  ;; closes the current batch
                  (recur #{} (next msgs))))))]
    (into []
          (keep (fn [m]
                  (let [role (:role m)]
                    (cond
                      (= role :tool)
                      (when (contains? @answered (-> m :content first :tool_use_id))
                        m)
                      (= role :assistant)
                      (if-let [tcs (seq (:tool-calls m))]
                        (let [kept (filterv #(contains? @answered (:id %)) tcs)]
                          (if (seq kept)
                            (assoc m :tool-calls kept)
                            ;; no tool call survived — keep the message only when
                            ;; it still carries text or thinking (empty assistant
                            ;; messages are rejected by providers)
                            (when (or (seq (str/trim (or (:thinking m) "")))
                                      (some (fn [b] (seq (or (:text b) "")))
                                            (:content m)))
                              (dissoc m :tool-calls))))
                        m)
                      :else m))))
          messages)))
