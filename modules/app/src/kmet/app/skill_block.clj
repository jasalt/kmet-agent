(ns kmet.app.skill-block
  "The `<skill>` text format a skill invocation is stored as — the reader.

   A `/skill:name args` command expands into a `<skill name=… location=…>`
   wrapper around the skill's body, with any trailing arguments after it
   (pi: agent-session _expandSkillCommand); the session stores that text, so
   replay sees the wrapper rather than the command. Reading the wrapper back is
   plain text work — no skill registry, no filesystem — which is why it lives
   here, below every front end: the TUI renders an invocation as its own
   message and re-parses the stored text, and it must not reach
   `kmet.agent.skills` to do it (that namespace owns discovery, the registry
   and the app root, and sits above the UI's module).

   Its inverse, `expand-skill-command`, does need the registry and re-reads
   the skill file, so it stays in `kmet.agent.skills`."
  (:require [clojure.string :as str]))

(defn parse
  "Parse a skill block out of message text (pi: parseSkillBlock). Returns
   {:name :location :content :user-message}, with :user-message nil when the
   text carries no trailing message, or nil when TEXT is not a skill block."
  [text]
  (when (string? text)
    (when-let [m (re-matches #"(?s)^<skill name=\"([^\"]+)\" location=\"([^\"]+)\">\n(.*?)\n</skill>(?:\n\n(.*))?$"
                             text)]
      (let [[_ name location content user-message] m
            user-message (some-> user-message str/trim)]
        {:name name
         :location location
         :content content
         :user-message (when (seq user-message) user-message)}))))
