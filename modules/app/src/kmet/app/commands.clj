(ns kmet.app.commands
  "Slash command registry. Commands are maps:
     {:name string
      :description string
      :argument-hint optional string
      :frontend optional :interactive|:gui|… — nil means every front end
      :get-argument-completions optional (fn [arg-prefix] -> items)
      :handler (fn [ctx args] ...)}
   Handlers receive the command context: :runtime (the
   kmet.app.session-runtime engine), :ui (the front end's command-UI value)
   and :frontend (the front-end kind). Engine work calls the runtime; output
   and view work go through the UI value: the shared capabilities are
   :message (assistant transcript line), :info (fn [label content]),
   :warning, :status, :notify and :set-title — an engine-portable command
   uses only those and :runtime. A front end's own commands may read more
   from its value (the interactive mode puts its CoreState under :state).
   Handlers return nil; they act through the runtime and the UI value, never
   through an intent for the caller to interpret.
   The registry is global so builtins, skills, and extensions can all
   register commands; the editor's autocomplete provider reads it live."
  (:require [clojure.string :as str]))

(defonce ^:private registry (atom []))

(defn register-command!
  "Register (or replace by :name) a slash command. Returns nil."
  [cmd]
  (swap! registry (fn [cmds] (conj (remove #(= (:name %) (:name cmd)) cmds) cmd)))
  nil)

(defn unregister-command!
  "Remove a slash command by :name. Returns nil."
  [name]
  (swap! registry (fn [cmds] (remove #(= (:name %) name) cmds)))
  nil)

(defn clear-commands!
  "Remove all registered commands (mainly for tests). Returns nil."
  []
  (reset! registry [])
  nil)

(defn command-line?
  "True when TEXT is a single-line command line: slash commands and bash
   bang-lines dispatch only when the whole input is one line (a pasted
   block whose first line starts with / or ! is a message, not a
   command)."
  [text]
  (not (str/includes? text "\n")))

(defn parse-command-line
  "Split a single-line slash-command TEXT into {:name NAME :args ARGS}, or
   nil when TEXT does not start with / or spans lines. NAME is the command
   without the slash; ARGS is the trimmed remainder (\"\" when absent)."
  [text]
  (when (and (str/starts-with? text "/") (command-line? text))
    (let [space (str/index-of text " ")]
      {:name (if space (subs text 1 space) (subs text 1))
       :args (if space (str/trim (subs text (inc space))) "")})))

(defn- available-to?
  "True when CMD runs on FRONTEND (a nil :frontend tag means every
   front end)."
  [frontend cmd]
  (or (nil? (:frontend cmd)) (= frontend (:frontend cmd))))

(defn get-commands
  "All registered slash commands, or those available to FRONTEND: a command
   with no :frontend tag is available to every front end, one with a tag
   only to that front end (dispatch and autocomplete filter by the
   installed capabilities)."
  ([] @registry)
  ([frontend]
   (filterv #(available-to? frontend %) @registry)))

(defn find-command
  "Look up a slash command by :name, or nil. With FRONTEND, only a command
   available to that front end is returned (a nil :frontend tag means every
   front end)."
  ([name]
   (first (filter #(= (:name %) name) @registry)))
  ([name frontend]
   (first (filter #(and (= (:name %) name) (available-to? frontend %))
                  @registry))))

(defn register-builtin!
  "Register CMD unless a command already took the name (extensions load
   before builtins; an extension command owns the name when taken). Used
   for the engine-portable builtins: no front-end tag, so every front end
   dispatches them. Returns nil."
  [cmd]
  (when-not (find-command (:name cmd))
    (register-command! cmd)))
