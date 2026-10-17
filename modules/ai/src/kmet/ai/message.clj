(ns kmet.ai.message
  "Malli schemas for provider messages and their content blocks.

   This namespace is a boundary aid: messages cross
   the session-file and (eventually) wire boundaries, so their shape is
   described here rather than ad hoc in every caller. Converters remain in
   the loop and providers; this schema only validates."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def TextBlock
  [:map {:closed false}
   [:type [:= :text]]
   [:text :string]])

(def ThinkingBlock
  [:map {:closed false}
   [:type [:= :thinking]]
   [:thinking [:or :string :nil]]])

(def ImageBlock
  [:map {:closed false}
   [:type [:= :image]]
   [:data [:or :string :nil]]
   [:mime-type {:optional true} [:or :string :nil]]])

(def ToolCallBlock
  [:map {:closed false}
   [:type [:= :tool_call]]
   [:id [:or :string :nil]]
   [:name [:or :string :nil]]
   [:arguments {:optional true} [:or :map :nil]]])

(def ToolUseBlock
  [:map {:closed false}
   [:type [:= :tool_use]]
   [:id [:or :string :nil]]
   [:name [:or :string :nil]]
   [:input {:optional true} [:or :map :nil]]])

(def ToolResultBlock
  [:map {:closed false}
   [:type [:= :tool_result]]
   [:tool_use_id {:optional true} [:or :string :nil]]
   [:tool-call-id {:optional true} [:or :string :nil]]
   [:content [:or :string :nil [:vector :any]]]
   [:is-error {:optional true} :boolean]])

(def ContentBlock
  [:multi {:dispatch :type}
   [:text TextBlock]
   [:thinking ThinkingBlock]
   [:image ImageBlock]
   [:tool_call ToolCallBlock]
   [:tool_use ToolUseBlock]
   [:tool_result ToolResultBlock]])

(def Message
  [:map {:closed false}
   [:role keyword?]
   [:content [:or :string :nil [:vector ContentBlock]]]
   [:tool-calls {:optional true} [:or :nil [:vector :any]]]
   [:tool-results {:optional true} [:or :nil [:vector :any]]]
   [:stop-reason {:optional true} [:or :keyword :string :nil]]
   [:error-message {:optional true} [:or :string :nil]]
   [:timestamp {:optional true} [:or :string :nil]]])

(def messages-schema [:vector Message])

(defn validate-message
  [m]
  (m/validate Message m))

(defn explain-message
  [m]
  (me/humanize (m/explain Message m)))
