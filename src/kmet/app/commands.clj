(ns kmet.app.commands
  "Slash command registry. Commands are maps:
     {:name string
      :description string
      :argument-hint optional string
      :frontend optional :interactive|:gui|… — nil means every front end
      :get-argument-completions optional (fn [arg-prefix] -> items)
      :handler (fn [ctx args] ...)}
   Handlers receive the command context (engine.md §10.3): :runtime (the
   kmet.app.session-runtime engine), :ui (the front end's UI value) and
   :frontend (the front-end kind). Engine work calls the runtime; dialogs
   and view work go through the front end. A handler returns nil or a small
   result ({:message s} / {:stop true}); it never returns an intent for the
   caller to interpret.
   The registry is global so builtins, skills, and extensions can all
   register commands; the editor's autocomplete provider reads it live.")

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

(defn get-commands
  "All registered slash commands, or those available to FRONTEND: a command
   with no :frontend tag is available to every front end, one with a tag
   only to that front end (dispatch and autocomplete filter by the
   installed capabilities)."
  ([] @registry)
  ([frontend]
   (filterv #(or (nil? (:frontend %)) (= frontend (:frontend %))) @registry)))

(defn find-command
  "Look up a slash command by :name, or nil. With FRONTEND, only a command
   available to that front end is returned (a nil :frontend tag means every
   front end)."
  ([name]
   (first (filter #(= (:name %) name) @registry)))
  ([name frontend]
   (first (filter #(and (= (:name %) name)
                        (or (nil? (:frontend %)) (= frontend (:frontend %))))
                  @registry))))
