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
   The registry lives in the app root's store, under the `[:app :commands]`
   owner and the `[:kmet/command <name>]` key: a registration is visible to
   every front end and outlives an engine, and it is disposed with its owner
   rather than reset by hand (scope.md §2). The extension ABI registers
   under its own owner path, so unload disposes what an extension added."
  (:require [clojure.string :as str]
            [kmet.agent.registry :as registry]
            [kmet.libs.scope :as scope]))

(def commands-owner
  "The app's command registry owner — the registry's address (scope.md §2).
   Registrations are addressed by key under this path, and `/units` reads the
   owner from the store, so they render under it."
  [:app :commands])

(defn- command-key
  "The store key addressing a command by NAME."
  [name]
  (registry/store-key :kmet/command name))

(defn- command-for
  "The command named NAME, or nil (see `kmet.agent.registry/entry` for the
   owner rule)."
  [name]
  (registry/entry :kmet/command name))

(defn- command-names
  "Every effective command name in display order: owners in
   first-registration order, each owner's commands in its own registration
   order, each name once."
  []
  (mapv first (registry/effective-entries :kmet/command)))

(defn register-command!
  "Register (or replace by :name) a slash command on NODE, owned by OWNER —
   the app's registry by default; an extension passes its own owner path
   (`kmet.agent.extensions.core/extension-owner`) so unloading it disposes
   the command with the owner (scope.md §2). NODE is the node the registration
   lands on: the app root for an app-wide registration, a child node a run
   passes for its own tree (scope.md §6).
   The store key is derived from :name, so a command is always addressed by
   its own name. Returns nil."
  ([node cmd] (register-command! node cmd commands-owner))
  ([node cmd owner]
   (scope/register! node owner (command-key (:name cmd)) cmd)
   nil))

(defn unregister-command!
  "Remove a slash command by :name from NODE, whichever owner registered it,
   or — with OWNER — only that owner's registration (an extension dropping
   one of its own; another owner's same-name command survives). Returns nil."
  ([node name] (unregister-command! node name nil))
  ([node name owner]
   (scope/deregister-key! node (command-key name) owner)
   nil))

(defn clear-commands!
  "Remove every command registered on NODE (mainly for tests). Returns nil."
  [node]
  (registry/clear! :kmet/command node))

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
  "The effective registry, in display order: each name once, from the owner
   that took it (`command-for`). Owners share the store with other registries,
   so key shape is what keeps this to commands."
  []
  (mapv command-for (command-names)))

(defn get-commands
  "All registered slash commands, or those available to FRONTEND: a command
   with no :frontend tag is available to every front end, one with a tag
   only to that front end (dispatch and autocomplete filter by the
   installed capabilities)."
  ([] (all-registered-commands))
  ([frontend]
   (filterv #(available-to? frontend %) (all-registered-commands))))

(defn find-command
  "Look up a slash command by :name, or nil. With FRONTEND, only a command
   available to that front end is returned (a nil :frontend tag means every
   front end)."
  ([name]
   (command-for name))
  ([name frontend]
   (when-let [cmd (command-for name)]
     (when (available-to? frontend cmd) cmd))))

(defn register-builtin!
  "Register CMD on NODE unless a command already took the name (extensions
   load before builtins; an extension command owns the name when taken). Used
   for the engine-portable builtins: no front-end tag, so every front end
   dispatches them. Returns nil."
  [node cmd]
  (when-not (find-command (:name cmd))
    (register-command! node cmd)))
