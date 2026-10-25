(ns kmet.app.skill-block
  "The `<skill>` text format a skill invocation is stored as — the reader.

   A `/skill:name args` command expands into a `<skill name=… location=…>`
   wrapper around the skill's body, trailing arguments after it (pi:
   agent-session _expandSkillCommand); the session stores that text, so replay
   sees the wrapper rather than the command. Reading it back is plain text work
   — no registry, no filesystem — so it lives below every front end: the TUI
   re-parses a stored invocation to render it as its own message, and must not
   reach `kmet.app.skills` (discovery, the registry and the app root) to do
   it. Its inverse, `expand-skill-command`, does need those, and stays there."
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
