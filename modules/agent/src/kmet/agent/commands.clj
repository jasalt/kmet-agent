(ns kmet.agent.commands
  "Slash command registry. Commands are maps:
     {:name string
      :description string
      :argument-hint optional string
      :frontend optional :interactive|:gui|… — nil means every front end
      :get-argument-completions optional (fn [arg-prefix] -> items)
      :handler (fn [ctx args] ...)}
   Handlers receive the command context: :runtime (the
   kmet.agent.session.runtime engine), :ui (the front end's command-UI value)
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

(defonce pending-command-registry (atom []))

(defn- current-command-registry
  "The active engine's command registry atom, or the pre-bootstrap pending
   buffer when no engine is active."
  []
  (or (some-> (do (require 'kmet.agent.session.runtime)
                  ((resolve 'kmet.agent.session.runtime/default-engine)))
              :commands)
      pending-command-registry))

(defn register-command!
  "Register (or replace by :name) a slash command. Returns nil."
  [cmd]
  (swap! (current-command-registry) (fn [cmds] (conj (remove #(= (:name %) (:name cmd)) cmds) cmd)))
  nil)

(defn unregister-command!
  "Remove a slash command by :name. Returns nil."
  [name]
  (swap! (current-command-registry) (fn [cmds] (remove #(= (:name %) name) cmds)))
  nil)

(defn clear-commands!
  "Remove all registered commands (mainly for tests). Returns nil."
  []
  (reset! (current-command-registry) [])
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

(defn- all-registered-commands
  "The commands currently registered; register-command! replaces by
   :name, so the atom already has unique names."
  []
  @(current-command-registry))

(defn get-commands
  "All registered slash commands, or those available to FRONTEND: a command
   with no :frontend tag is available to every front end, one with a tag
   only to that front end (dispatch and autocomplete filter by the
   installed capabilities)."
  ([] (into [] (all-registered-commands)))
  ([frontend]
   (filterv #(available-to? frontend %) (all-registered-commands))))

(defn find-command
  "Look up a slash command by :name, or nil. With FRONTEND, only a command
   available to that front end is returned (a nil :frontend tag means every
   front end)."
  ([name]
   (first (filter #(= (:name %) name) (all-registered-commands))))
  ([name frontend]
   (first (filter #(and (= (:name %) name) (available-to? frontend %))
                  (all-registered-commands)))))

(defn register-builtin!
  "Register CMD unless a command already took the name (extensions load
   before builtins; an extension command owns the name when taken). Used
   for the engine-portable builtins: no front-end tag, so every front end
   dispatches them. Returns nil."
  [cmd]
  (when-not (find-command (:name cmd))
    (register-command! cmd)))
