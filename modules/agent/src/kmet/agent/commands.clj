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
   rather than reset by hand (cells.md §5.1). The extension ABI registers
   under its own owner path, so unload disposes what an extension added."
  (:require [clojure.string :as str]
            [kmet.agent.runtime :as app-runtime]
            [kmet.cells.scope :as scope]))

(def commands-owner
  "The app's command registry owner: the `:commands` slot kmet.agent.runtime
   mounts at the root for it (cells.md §5.1). Registrations are addressed by
   key under this path, so they show in `/units` as that slot's keys."
  [:app :commands])

(defn- command-key
  "The store key addressing a command by NAME."
  [name]
  [:kmet/command name])

(defn- command-key?
  "True for a key the store holds a command under — other registries'
   keys (a bare keyword like :tools/hooks) share the store, so shape is
   checked before the head."
  [k]
  (and (vector? k) (= :kmet/command (first k))))

(defn- command-for
  "The command named NAME, or nil. Owners are searched in first-registration
   order, so the owner that took the name keeps it — an extension loaded
   before the builtins beats one of theirs, which is the rule
   `register-builtin!` skips on."
  [name]
  (let [node (app-runtime/node)
        by-owner (scope/for-key node (command-key name))]
    (some #(get by-owner %) (scope/owners node))))

(defn- command-names
  "Every command name on the tree, in display order: owners in
   first-registration order, each owner's commands in its own registration
   order, each name once."
  []
  (distinct
   (into []
         (keep (fn [[k _ _]] (when (command-key? k) (second k))))
         (scope/ordered-registrations (app-runtime/node)))))

(defn register-command!
  "Register (or replace by :name) a slash command, owned by OWNER — the
   app's registry by default; an extension passes its own owner path
   (`kmet.agent.extensions.core/extension-owner`) so unloading it disposes
   the command with the owner (cells.md §5.1). The store key is derived from
   :name, so a command is always addressed by its own name. Returns nil."
  ([cmd] (register-command! cmd commands-owner))
  ([cmd owner]
   (scope/register! (app-runtime/node) owner (command-key (:name cmd)) cmd)
   nil))

(defn unregister-command!
  "Remove a slash command by :name, from whichever owner registered it, or —
   with OWNER — only that owner's registration (an extension dropping one of
   its own; another owner's same-name command survives). Returns nil."
  ([name] (unregister-command! name nil))
  ([name owner]
   (scope/deregister-key! (app-runtime/node) (command-key name) owner)
   nil))

(defn clear-commands!
  "Remove every registered command (mainly for tests). Returns nil."
  []
  ;; by key, not by owner: a unit owns several registries (§5.1)
  (let [node (app-runtime/node)]
    (doseq [[k owner v] (scope/ordered-registrations node)
            :when (command-key? k)]
      (scope/deregister! node owner k v)))
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
  "Register CMD unless a command already took the name (extensions load
   before builtins; an extension command owns the name when taken). Used
   for the engine-portable builtins: no front-end tag, so every front end
   dispatches them. Returns nil."
  [cmd]
  (when-not (find-command (:name cmd))
    (register-command! cmd)))
