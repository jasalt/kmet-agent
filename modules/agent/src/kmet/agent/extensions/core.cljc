(ns kmet.agent.extensions.core
  "Extension runtime: discovers, loads, reloads and unloads Clojure
   extensions. The extension contract lives in kmet.extension — extensions
   depend on that namespace plus the shared library layers kmet.tui.*
   (the generic TUI layer; the port of pi's @earendil-works/pi-tui) and
   kmet.libs.* (generic, self-contained utilities) — all shared by
   reference; this runtime wires the api capabilities to the registries
   and the interactive/loop surfaces.

   An extension is a .clj file defining (defn init [api]) in its namespace,
   a directory containing an extension.edn manifest, or a .jar/.zip archive
   with the same layout at its root:
     {:name \"my-ext\" :entry my.ext.main :loader [:jolt :sci]}
   The manifest lists the initial namespace (:entry, a symbol) and the
   loader backends the extension supports (:loader — a non-empty vector of
   :sci/:jolt). The host picks its own preference among the declared kinds
   (`select-loader-kind`): babashka loads :sci, Jolt prefers its native
   :jolt loader and falls back to :sci; an extension declaring none of the
   host's backends is skipped, not failed. A manifest without :loader (a
   legacy extension) is treated as [:sci :jolt] with a warning; a
   single-file extension implicitly supports both. Everything else is
   required from there. Bundled extensions resolve
   through a descriptor instead of a path (kmet.agent.extensions.bundled): a
   checkout's real artifact root, or — in a built artifact — a resource
   tree under extensions/<root>. On Jolt with embedded loader roots, a
   resource-directory descriptor carries an `embed:<prefix>` root and a
   single-file descriptor maps its namespace directly to the exact embedded
   key; both load through the native backend. Older Jolt releases fall back
   to SCI. Each extension evaluates in its own isolated
   context: a fork of one shared SCI base carrying the injected host layers
   and the seeded classes (see shared-context) on babashka, or a set of real
   Jolt namespaces served by the runtime's native loader when the manifest
   offers :jolt there. Internal namespaces are served from the
   extension artifact (dir or jar) by strict ns-path lookup,
   declared libraries from its deps.edn (resolved in-process via
   clojure.tools.deps, bundled with babashka) — so
   different extensions can use different versions of the same library, and
   unloading an extension releases everything it pulled in. Loading goes
   through kmet.loader.core: each extension gets a Loader on the selected
   backend (kmet.loader.sci-loader / kmet.loader.jolt-loader) whose source
   provider is that artifact / deps / bundled lookup, and the backend's own
   requires route back through the loader — so nested requires share the
   link table, unload closes the
   loader, and the loader itself stays out of the shared set (host
   machinery, not contract). Optional
   (defn shutdown [api]) runs on unload, which also disposes everything
   the extension registered: what lives in the app tree is owned by
   `[:app :extensions <name>]` (one LIFO dispose), and what lives outside it
   — event subscriptions, aliases, shortcuts, model providers — deregisters
   through the fns the extension recorded.

   Extensions load at startup (core.clj), are re-loaded by /reload, and can
   be unloaded/reloaded at runtime via unload-extension! /
   reload-extensions!."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.ai.models :as models]
            [kmet.agent.commands :as commands]
            [kmet.agent.event-bus :as event-bus]
            [kmet.agent.extensions.context :as context]
            [kmet.agent.prompts :as prompts]
            [kmet.agent.registry :as registry]
            [kmet.agent.app-runtime :as app-runtime]
            [kmet.agent.session.core :as session]
            [kmet.agent.skills :as skills]
            [kmet.agent.tools.core :as tools]
            [kmet.agent.tools.bash :as bash-tool]
            [kmet.agent.config :as cfg]
            [kmet.agent.debug :as debug]
            [kmet.libs.hikap.alias :as alias]
            [kmet.libs.host :as host]
            [kmet.loader.core :as loader]
            ;; Jolt only: pulls the fixed bundled extension set into the
            ;; app's require closure (jolt dist AOT-compiles it). bb/JVM
            ;; load the same namespace in host-requires! on the first
            ;; extension load (its requires resolve to the bb ports there).
            #?(:jolt [kmet.agent.extensions.libs])
            #?(:jolt [kmet.loader.jolt-loader :as loader-jolt])
            [kmet.extension]
            [kmet.libs.scope :as scope]))

;; ─── Provider-event bridges (pi: context / context_with_system /
;; ─── before_provider_request / before_provider_headers /
;; ─── after_provider_response) ──────────────────────────────────────────
;; The ai layer exposes injectable hooks (kmet.ai.api.shared) — it cannot
;; depend on kmet.agent (the event bus). These bridges translate bus events
;; into hook results. The bus returns the LAST non-nil handler result;
;; pi chains handler results (each handler sees the previous one's
;; replacement) — kmet's approximation: handlers see the original event
;; and the last non-nil result wins. before-provider-headers handlers
;; return the replacement header map (Clojure maps are immutable — the
;; return value IS the mutation; pi mutates in place).

(defn- emit-context-events
  "Run the two request-time context phases (pi: runner.emitContext): the
   :context handlers see the conversation only — the leading system message
   belongs to kmet and is re-attached to a replacement, so a pruning handler
   cannot drop the prompt — then the :context-with-system handlers see the
   full transcript and their output is used verbatim. kmet's bus keeps the
   last non-nil result, so the leading-system check in the second phase is
   against that result (pi checks each handler's)."
  [messages]
  (let [messages (vec messages)
        head (when (= :system (:role (first messages))) (first messages))
        visible (filterv #(not= :system (:role %)) messages)
        context-result (event-bus/emit-event! {:type :context :messages visible})
        context-replaced (:messages context-result)
        messages (if (and context-replaced (not= context-replaced visible))
                   (into (if head [head] []) context-replaced)
                   messages)
        result (event-bus/emit-event! {:type :context-with-system
                                       :messages messages})
        system-replaced (:messages result)]
    (cond
      (nil? system-replaced) messages

      ;; Providers read the prompt from the leading system message; losing it
      ;; is never intended. Reported and honored, as pi does.
      (and head (not= :system (:role (first system-replaced))))
      (do (binding [*out* *err*]
            (println "Warning: a :context-with-system handler removed the leading system message; the request has no prompt."))
          system-replaced)

      :else system-replaced)))

(defn provider-hooks
  "The provider-event bridges, as `kmet.ai.llm/send-message` opts (pi: the
   context / before_provider_request / before_provider_headers /
   after_provider_response events). The runtime reads this once per attempt
   and merges it into the request opts, so a handler installed mid-run
   reaches the next request while an in-flight one keeps the hooks it
   started with — the hooks are arguments, not installed globals."
  []
  {:on-context emit-context-events
   :on-payload (fn [payload]
                 (let [result (event-bus/emit-event! {:type :before-provider-request
                                                      :payload payload})]
                   (if (some? result) result payload)))
   :on-headers (fn [headers]
                 (let [result (event-bus/emit-event! {:type :before-provider-headers
                                                      :headers headers})]
                   (if (map? result) result headers)))
   :on-response (fn [{:keys [status headers]}]
                  (event-bus/emit-event! {:type :after-provider-response
                                          :status status
                                          :headers headers}))})

;; ─── Extension records ────────────────────────────────────────────────────
(defrecord Extension [name path kind loader-kind bundled? artifact entry-ns loader jars api deregister-fns initialized? jar-files])

(defn- extension-dir-of
  "The extension's own directory: for a dir extension :path IS the
   directory; for a single-file extension it's the file, so the dir is its
   parent. nil for jar and bundled resource extensions — a jar has no
   directory and a bundled resource artifact has no stable one (state goes
   under the agent dir); resources are accessed via io/resource."
  [ext]
  (when-not (contains? #{:jar :resource-dir :resource-file} (:kind ext))
    (str (if (fs/directory? (:path ext))
           (:path ext)
           (fs/parent (:path ext))))))

;; ─── Registries (the storage; api capabilities wire into these) ──────────
(defonce ^:private extensions (atom []))
;; Renderers live in the app root's store under the registering extension's
;; owner ([:app :extensions <name>], scope.md §2), addressed by
;; [:kmet/entry-renderer <custom-type>] / [:kmet/message-renderer
;; <custom-type>]: a type holds one renderer, so a re-registration replaces
;; it, and the registration is disposed with its owner rather than reset by
;; hand.
(defn- entry-renderer-key
  "The store key addressing an entry renderer by CUSTOM-TYPE."
  [custom-type]
  (registry/store-key :kmet/entry-renderer custom-type))

(defn- message-renderer-key
  "The store key addressing a message renderer by CUSTOM-TYPE."
  [custom-type]
  (registry/store-key :kmet/message-renderer custom-type))

;; Flags register under the extension owner like the renderers, addressed by
;; [:kmet/flag <name>]; the --ext-* values themselves are process argv, so
;; they live in the global below rather than in an engine (see get-flag).
(defn- flag-key
  "The store key addressing a CLI flag by NAME."
  [name]
  (registry/store-key :kmet/flag name))

;; The parsed --ext-* argv values by flag name, as set once at startup
;; (kmet.agent.core). Process state: the values are the process's command
;; line, not a per-engine registration.
(defonce ^:private cli-flag-values (atom {}))

(def ^:private headless-binding
  "The surface of a front end with no UI: pi's default mode (:print), no
   capabilities (every ui-call is inert) and no runtime capabilities."
  {:mode :print :has-ui false :ui {} :runtime {}})
(defonce ^:private extension-binding (atom headless-binding))
(defonce ^:private session-atom (atom nil))
(defonce ^:private context-sink-atom (atom nil))
(defonce ^:private entry-sink-atom (atom nil))

;; ─── Input / before-agent-start hooks (pi: pi.on('input') / ──────────────
;; ─── 'before_agent_start'; applied by modes.interactive + app.loop) ──────

(defn extension-owner
  "The owner path an extension's app-tree registrations belong to — what its
   `init(api)` installs in the host's node (scope.md §2). A registration is
   disposed with its owner, and unload deregisters what the extension added."
  [extension-name]
  [:app :extensions extension-name])

(def themes-owner
  "The app's theme registry owner — the registry's address, read by `/units`
   from the store. Themes live in the TUI layer's own store, so the
   registration under this path is the host's record of one and the teardown
   that removes it (scope.md §2)."
  [:app :themes])

(defn- theme-key
  "The store key addressing a loaded theme by NAME."
  [name]
  (registry/store-key :kmet/theme name))

(defonce ^:private hook-seq (atom 0))

(defn- hook-key
  "A fresh key for one hook: a hook is not addressed by name and one owner may
   hold several of a kind, so each registration gets its own key."
  [kind]
  [:kmet/hook kind (swap! hook-seq inc)])

(defn- hook-key? [kind k]
  (and (vector? k) (= :kmet/hook (first k)) (= kind (second k))))

(defn- register-app-hook!
  "Register HOOK for OWNER (an `extension-owner` path) on NODE and
   return the store's deregister fn. Hooks are read from the tree rather than
   copied into each engine, so one registered before an engine exists reaches
   every engine and stays gone once deregistered. NODE is the node the
   registration lands on: the app root for an app-wide registration, a child
   node a run passes for its own tree (scope.md §6)."
  [node owner kind hook]
  (scope/register! node owner (hook-key kind) hook))

(defn- hooks-of
  "Every KIND hook on the app tree, in the order the store reports it: owners
   in first-registration order, each owner's in its own registration order."
  [kind]
  (into []
        (keep (fn [[k _ v]] (when (hook-key? kind k) v)))
        (scope/ordered-registrations (app-runtime/node))))

(defn register-input-hook!
  "Register OWNER's input hook (extension api: on-input). Fires for agent
   messages submitted from the interactive input path. Hook:
   (fn [{:keys [text source streaming-behavior images]}]) returning
   {:action :handled} to consume, {:action :transform :text ... :images ...}
   to rewrite, or nil. Returns a deregister fn."
  [node owner hook]
  (register-app-hook! node owner :input hook))

(defn register-before-agent-start-hook!
  "Register OWNER's before-agent-start hook (extension api:
   on-before-agent-start). Hook: (fn [{:keys [prompt system-prompt]}]) returning
   a map with :system-prompt and/or :message, or nil. Returns a deregister
   fn."
  [node owner hook]
  (register-app-hook! node owner :before-agent-start hook))

(defn apply-input-hooks
  "Run all input hooks in registration order over text and images
   (pi: emitInput). Returns {:action :handled} | {:action :transform ...}
   | {:action :pass ...}."
  [text source & [{:keys [streaming-behavior images]}]]
  (let [initial-images (or images [])]
    (loop [hooks (hooks-of :input)
           current text
           current-images initial-images]
      (if-let [hook (first hooks)]
        (let [result (try
                       (hook {:text current :source source
                              :streaming-behavior streaming-behavior
                              :images current-images})
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "Warning: input hook error:" (ex-message e)))
                         nil))]
          (cond
            (= :handled (:action result)) {:action :handled}
            (= :transform (:action result))
            (recur (next hooks) (:text result current)
                   (if (contains? result :images) (:images result) current-images))
            :else (recur (next hooks) current current-images)))
        (if (and (= current text) (= current-images initial-images))
          {:action :pass :text text :images current-images}
          {:action :transform :text current :images current-images})))))

(defn apply-before-agent-start-hooks
  "Run all before-agent-start hooks in registration order.
   Returns {:system-prompt string-or-nil :messages [msg ...]}."
  [prompt system-prompt]
  (loop [hooks (hooks-of :before-agent-start)
         current-prompt system-prompt
         messages []]
    (if-let [hook (first hooks)]
      (let [result (try
                     (hook {:prompt prompt :system-prompt current-prompt})
                     (catch Exception e
                       (binding [*out* *err*]
                         (println "Warning: before-agent-start hook error:" (ex-message e)))
                       nil))]
        (recur (next hooks)
               (if (and result (contains? result :system-prompt))
                 (:system-prompt result)
                 current-prompt)
               (if (and result (:message result))
                 (conj messages (:message result))
                 messages)))
      {:system-prompt (when (not= current-prompt system-prompt) current-prompt)
       :messages messages})))

;; ─── Renderers + tool hooks (extension api) ───────────────────────────────

(defn register-entry-renderer!
  "Register OWNER's renderer for a custom entry type (extension api:
   register-entry-renderer!). RENDERER — (fn [entry]) returning a chat
   message map (or bare component) or nil. Returns a deregister fn."
  [node owner custom-type renderer]
  (scope/register! node owner (entry-renderer-key custom-type) renderer))

(defn get-entry-renderer [custom-type]
  (registry/entry (app-runtime/registry) :kmet/entry-renderer custom-type))

(defn register-message-renderer!
  "Register OWNER's renderer for a custom MESSAGE type (extension api:
   register-message-renderer!). Returns a deregister fn."
  [node owner custom-type renderer]
  (scope/register! node owner (message-renderer-key custom-type) renderer))

(defn get-message-renderer [custom-type]
  (registry/entry (app-runtime/registry) :kmet/message-renderer custom-type))

(defn register-tool-call-hook!
  "Register OWNER's tool-call hook (extension api: on-tool-call): (fn [ctx])
   → nil | {:block true :reason} | {:args transformed}. Returns a deregister
   fn."
  [node owner hook]
  (register-app-hook! node owner :tool-call hook))

(defn register-tool-result-hook!
  "Register OWNER's tool-result hook (extension api: on-tool-result):
   (fn [ctx]) → nil | {:content ... :is-error ...} overrides. Returns a
   deregister fn."
  [node owner hook]
  (register-app-hook! node owner :tool-result hook))

(defn get-tool-call-hooks
  "Every tool-call hook on the app tree (extension api: on-tool-call), owners
   in first-registration order, each owner's in its own."
  []
  (hooks-of :tool-call))

(defn get-tool-result-hooks
  "Every tool-result hook on the app tree (extension api: on-tool-result),
   owners in first-registration order, each owner's in its own."
  []
  (hooks-of :tool-result))

;; ─── Shortcuts + markdown transformers (extension api) ────────────────────

(declare ui-call)

(defn register-shortcut!
  "Register a keyboard shortcut (extension api: register-shortcut! — pi:
   registerShortcut). KEY-ID is a raw key string (\"ctrl+alt+x\", \"f5\", …);
   opts: {:description str :handler (fn [ctx])}. The interactive mode
   installs it as a priority editor action — checked before every builtin
   app binding (pi: onExtensionShortcut runs first) — and registers the
   keybinding definition on the global manager (key-hints resolve, user
   overrides apply). The last registration of the same key wins (pi: last
   extension wins). Returns a deregister fn; no-op headless."
  [key-id & [{:keys [description handler]}]]
  (or (ui-call :register-shortcut! key-id {:description description :handler handler})
      ;; headless: no-op dereg (unload must never NPE on it)
      (fn [] nil)))

(defn register-markdown-transformer!
  "Register OWNER's markdown transformer (extension api:
   register-markdown-transformer! — pi: registerMarkdownTransformer):
   (fn [markdown {:keys [message-type is-streaming available-width]}])
   → string, applied to user/assistant message markdown before rendering,
   in registration order. Transformers must be idempotent (they re-run per
   render — streaming chunks re-transform the accumulated text). A
   transformer that throws is skipped (pi: keep the current markdown and
   continue). Returns a deregister fn."
  [node owner transformer]
  (register-app-hook! node owner :markdown-transformer transformer))

(defn get-markdown-transformers
  "Registered markdown transformers in registration order (owners in
   first-registration order, each owner's in its own)."
  []
  (hooks-of :markdown-transformer))

(defn apply-markdown-transformers
  "Apply the registered markdown transformers to MARKDOWN in registration
   order (pi: applyMarkdownTransformers); a transformer that throws is
   skipped and the chain continues with the current markdown. CTX:
   {:message-type :user|:assistant :is-streaming bool :available-width int}."
  [markdown ctx]
  (reduce (fn [acc t]
            (try
              (let [r (t acc ctx)]
                (if (string? r) r acc))
              (catch Exception _ acc)))
          markdown
          (hooks-of :markdown-transformer)))

;; ─── CLI flags (extension api: register-flag! / get-flag) ─────────────────

(defn register-flag!
  "Register OWNER's CLI flag (extension api: register-flag!). NAME — without
   the leading --; opts: :type (:boolean|:string) :default. Returns a
   deregister fn."
  [node owner name & [{:keys [type default]}]]
  (scope/register! node owner (flag-key name)
                   {:type (or type :string) :default default}))

(defn set-cli-flags! [flag-map]
  (reset! cli-flag-values (or flag-map {}))
  nil)

(defn get-flag
  "Value of a registered CLI flag: the argv value coerced by the registered
   :type, falling back to :default. nil for unregistered flags."
  [name]
  (when-let [{:keys [type default]} (registry/entry (app-runtime/registry) :kmet/flag name)]
    (let [raw (get @cli-flag-values name)]
      (case type
        :boolean (let [v (if (nil? raw) default raw)]
                   (if (string? v)
                     (not (contains? #{"false" "0" ""} v))
                     (boolean v)))
        :string (or (when (string? raw) raw) default)
        raw))))

;; ─── Extension binding (front-end kind + capability map) ─────────────────
;;
;; One binding per process (pi: ExtensionRunner's uiContext + mode): the
;; front-end kind, whether a UI port is available (ctx.has-ui), and the
;; capability map the front end supplies (see the headless-binding
;; default). The runtime binds the headless defaults at bootstrap and
;; unbinds at dispose!; a front end with its own port re-binds after its
;; widgets exist (kmet.agent.modes.interactive installs its registry through
;; bind-extensions!).

(defn bind-extensions!
  "Bind the front-end extension surface (pi: AgentSession.bindExtensions):
   MODE is the front-end kind (:interactive | :print | :json | :gui |
   :rpc), :has-ui tells extensions whether a UI port is available
   (ctx.has-ui), and :ui is the capability map (nil/absent = a portless
   front end; ui-call stays inert). A bind replaces the whole front-end
   surface — absent keys fall back to the headless defaults, not to the
   previous binding; runtime capabilities (bind-runtime-capabilities!) are
   left alone. Use set-ui-registry! for a port-only swap. Returns nil."
  [{:keys [mode has-ui ui]}]
  (swap! extension-binding merge {:mode (or mode :print)
                                  :has-ui (boolean has-ui)
                                  :ui (or ui {})})
  nil)

(defn unbind-extensions!
  "Restore the headless binding. Returns nil."
  []
  (reset! extension-binding headless-binding)
  nil)

(defn install-front-end!
  "Install the front-end extension surface for NODE (the tree's [:app :ui]
   owner) — the same map bind-extensions! takes (:mode, :has-ui, :ui). The
   capability map is the owner's registration, the tree's copy of the port
   that /units reports; the process binding stays the no-node read cache, and
   node disposal restores the headless defaults. A later port-only swap
   (`set-ui-registry!`) updates the binding, not the registration. A front
   end with its own widgets installs here once they exist. Returns nil."
  [node {:keys [ui] :as surface}]
  (when node
    (scope/register! node [:app :ui] :kmet/ui-port (or ui {}))
    (scope/add-teardown! node [:app :ui] unbind-extensions!))
  (bind-extensions! surface)
  nil)

(defn extension-mode
  "The bound front-end kind — ctx.mode (pi: ExtensionMode)."
  []
  (:mode @extension-binding))

(defn extension-has-ui?
  "Whether the bound front end has a UI port — ctx.has-ui."
  []
  (:has-ui @extension-binding))

(defn set-ui-registry!
  "Install (or replace) the capability map, preserving the bound
   front-end kind. Prefer bind-extensions! at a front-end install point;
   this is the port-only swap tests and incremental installs use."
  [registry]
  (swap! extension-binding assoc :ui registry)
  nil)

(defn clear-ui-registry!
  "Remove the bound capability map, preserving the front-end kind."
  []
  (swap! extension-binding assoc :ui {})
  nil)

(defn ui-capability
  "The bound fn for CAPABILITY, or nil when the front end has not
   installed it (headless/print)."
  [capability]
  (get (:ui @extension-binding) capability))

(defn ui-call
  "Dispatch a UI capability call through the bound map. No-op (nil) when
   the front end has not installed the capability (headless/print)."
  [capability & args]
  (when-let [f (ui-capability capability)]
    (apply f args)))

;; ─── Runtime capabilities (pi: bindCore) ───────────────────────────────────
;;
;; Agent control the runtime owns for every front end — the runtime installs
;; it at bootstrap (session-runtime/runtime-capabilities) and clears it at
;; dispose!; the front end's UI port is a separate binding above. These are
;; the capabilities that must work headless: an extension sends a message or
;; switches the model in print/json mode just as in the TUI.

(defn bind-runtime-capabilities!
  "Install the runtime-owned extension capabilities (pi: bindCore):
   :set-model, :get-thinking-level, :set-thinking-level,
   :send-user-message, :send-message!, :get-active-tools,
   :set-active-tools. Returns nil."
  [capabilities]
  (swap! extension-binding assoc :runtime (or capabilities {}))
  nil)

(defn runtime-capability
  "The runtime capability fn for CAPABILITY, or nil when the runtime has
   not installed it."
  [capability]
  (get (:runtime @extension-binding) capability))

(defn runtime-call
  "Dispatch a runtime-owned capability call. Nil when the runtime has not
   installed CAPABILITY (bare test/library use without an engine)."
  [capability & args]
  (when-let [f (runtime-capability capability)]
    (apply f args)))

;; ─── UI requests (the port's async answer protocol) ─────────────────────

(def ui-request-defaults
  "Default answer per :ui-request :kind — what a front end without a
   terminal auto-answers with (pi: noOpUIContext). Used by the headless
   port and as the fallback when no port is bound."
  {:select nil
   :confirm false
   :input nil
   :editor nil
   :custom nil})

(def request-pending
  "Port return marker: the request was forwarded to an asynchronous client
   (or an in-process dialog that has not resolved yet) and is answered
   later with kmet.agent.session.runtime/respond!."
  ::pending)

(defn ui-request-default
  "The degraded default answer for request kind KIND (nil for an unknown
   kind)."
  [kind]
  (get ui-request-defaults kind))

(defn headless-ui-port
  "The degraded capability map a front end without a terminal binds (pi:
   noOpUIContext): only the capabilities that must answer rather than stay
   inert are present — :custom resolves to nil, the getters return neutral
   values, :set-theme reports the missing UI, :on-terminal-input returns a
   no-op unsubscriber, and :request auto-answers by kind. Inert setters
   (notify, set-status, set-widget, the working indicator, …) are omitted:
   ui-call treats an absent capability as a nil no-op — the
   headless-binding default. The runtime adds the live :build-context
   capability on top."
  []
  {:custom (fn [_factory _opts] (doto (promise) (deliver nil)))
   :on-terminal-input (fn [_handler] (fn [] nil))
   :get-editor-text (fn [] "")
   :set-theme (fn [_theme-or-name] {:success false :error "UI not available"})
   :get-tools-expanded (fn [] false)
   :get-tool-display-mode (fn [] :collapsed)
   :request (fn [request] (ui-request-default (:kind request)))})

(declare api-session read-model-facades)

(defn- default-extension-context
  "Default extension context (pi: ExtensionContext): the front end's
   :build-context capability overrides these per call. Every key is a
   value or zero-arg fn so handlers always receive a callable map. Built
   per call so it can reference the session facades (defined below) — the
   fns read the live session atom at call time."
  []
  {:mode (extension-mode)
   :has-ui (extension-has-ui?)
   :cwd (System/getProperty "user.dir")
   :model nil
   :scoped-models []
   ;; pi: ctx.modelRegistry — read-only registry facades (same fns as the
   ;; extension api's :models map, without the tracked register/unregister)
   :models read-model-facades
   :thinking-level nil
   :is-idle (fn [] true)
   :has-pending-messages (fn [] false)
   :signal (fn [] nil)
   :abort (fn [] nil)
   :shutdown (fn [] (System/exit 0))
   :get-context-usage (fn [] nil)
   :compact (fn [& _] nil)
   :get-system-prompt (fn [] nil)
   :get-system-prompt-options (fn [] nil)
   :wait-for-idle (fn [] nil)
   :reload (fn [] nil)
   :new-session (fn [& _] {:cancelled true})
   :fork (fn [& _] {:cancelled true})
   :navigate-tree (fn [& _] {:cancelled true})
   :switch-session (fn [& _] {:cancelled true})
   :is-project-trusted (fn [] false)
   ;; pi: ctx.sessionManager — read facades over the live session (set by
   ;; the interactive mode / headless tests via set-session!)
   :session (api-session)})

(defn build-extension-context
  "Build the extension context (pi: ExtensionContext) for the current
   runtime: the front end's live :build-context capability merged over the
   headless default. Returns a fresh map per call — the fns capture live
   state at call time. Used for command handlers (replacing the internal
   CoreState leak) and event handler ctx args.

   The bound front-end kind is authoritative: a :build-context capability
   cannot drift :mode/:has-ui away from bind-extensions!.

   Session control fns (new-session/fork/navigate-tree/switch-session/
   reload) run the interactive flows synchronously — call them from
   user-initiated command handlers, never from agent-loop event handlers
   (pi restricts these to the command ctx; they can re-enter the run loop)."
  []
  (assoc (merge (default-extension-context) (ui-call :build-context))
         :mode (extension-mode)
         :has-ui (extension-has-ui?)))

(defn wrap-event-handler
  "Adapt an extension event handler to the bus: handlers always receive
   (event ctx) — pi parity, ctx built fresh per event. Fixed arity-2
   contract (no legacy shim): a handler that takes fewer args fails fast
   with an ArityException, which the bus logs as a handler error."
  [handler]
  (fn [event]
    (handler event (build-extension-context))))

(defn ui-notify [message & [type]] (ui-call :notify message type))
(defn ui-custom [factory & [opts]]
  (ui-call :custom factory opts))
(defn ui-chat-info [label content] (ui-call :chat-info label content))
(defn ui-on-terminal-input [handler] (ui-call :on-terminal-input handler))
(defn ui-set-status [key text] (ui-call :set-status key text))
(defn ui-set-widget [key content & [{:keys [placement]}]] (ui-call :set-widget key content {:placement placement}))
(defn ui-set-footer [factory] (ui-call :set-footer factory))
(defn ui-set-header [factory] (ui-call :set-header factory))
(defn ui-set-title [title] (ui-call :set-title title))
(defn ui-set-editor-text [text] (ui-call :set-editor-text text))
(defn ui-get-editor-text [] (ui-call :get-editor-text))
(defn ui-paste-to-editor [text] (ui-call :paste-to-editor text))
(defn ui-set-working-indicator [options] (ui-call :set-working-indicator options))
(defn ui-set-working-message [message] (ui-call :set-working-message message))
(defn ui-set-working-visible [visible?] (ui-call :set-working-visible visible?))
(defn ui-set-hidden-thinking-label [label] (ui-call :set-hidden-thinking-label label))
(defn ui-set-editor-component [factory] (ui-call :set-editor-component factory))
(defn ui-add-autocomplete-provider [factory] (ui-call :add-autocomplete-provider factory))
(defn ui-set-theme [theme-or-name] (ui-call :set-theme theme-or-name))
(defn ui-get-tools-expanded [] (ui-call :get-tools-expanded))
(defn ui-set-tools-expanded [expanded?] (ui-call :set-tools-expanded expanded?))
(defn ui-get-tool-display-mode [] (ui-call :get-tool-display-mode))
(defn ui-set-tool-display-mode [mode] (ui-call :set-tool-display-mode mode))
(defn ui-reset! [] (ui-call :reset))

;; ─── Agent control (dispatches through the runtime capabilities; api) ──────

(defn set-model [model] (runtime-call :set-model model))
(defn get-thinking-level [] (runtime-call :get-thinking-level))
(defn set-thinking-level [level] (runtime-call :set-thinking-level level) nil)
(defn send-user-message
  "Extension api: send-user-message (pi: sendUserMessage) — send text to
   the agent. OPTIONS: {:deliver-as :steer | :follow-up (queue while
   streaming), :expand-prompt-templates? bool (pi:
   expandPromptTemplates — extension commands execute immediately and
   consume the message, then skill commands and prompt templates expand;
   kmet defaults to no expansion)."
  [text & [{:keys [deliver-as expand-prompt-templates?]}]]
  (runtime-call :send-user-message text {:deliver-as deliver-as
                                         :expand-prompt-templates? expand-prompt-templates?})
  nil)

(declare append-custom-message!)

(defn send-message!
  "Extension api: send-message! (pi: sendCustomMessage). MESSAGE:
   {:custom-type :content :display :details} — appended to the session as a
   custom_message entry and injected into the agent context (sent to the LLM
   as a user message; rendered in the chat when :display). OPTIONS:
   {:trigger-turn bool :deliver-as :steer | :follow-up | :next-turn}.
   :next-turn queues the message for the next prompt. While the agent is
   running, :trigger-turn (default true) queues it into the run — :steer
   into the current turn, :follow-up after it — and :trigger-turn false
   defers it to the end of the turn, so it cannot land between a tool call
   and its result. While idle, :trigger-turn starts a run with the message
   as its prompt; without it the message is appended without running.
   Headless (no runtime capabilities): persists + injects via the sinks,
   without queueing. Returns nil."
  [message & [opts]]
  (if (nil? (runtime-call :send-message! message opts))
    ;; no runtime capability (bare library/test use): persist + inject
    ;; through the sinks
    (append-custom-message! (or (:custom-type message) :custom)
                            (:content message)
                            (if (nil? (:display message)) true (:display message))
                            (:details message))
    nil)
  nil)
(defn get-active-tools [] (runtime-call :get-active-tools))
(defn set-active-tools [names] (runtime-call :set-active-tools names) nil)

;; ─── Model / session facades (extension api: models / session) ───────────

(defn get-all-models [] (models/get-models))
(defn get-available-models [] (models/get-available))
(defn find-model [provider-id model-id] (models/get-model provider-id model-id))
(defn has-configured-auth [model] (models/has-configured-auth model))
(defn get-provider-auth-status [provider-id] (models/get-provider-auth-status provider-id))
(defn get-api-key-and-headers [model] (models/get-api-key-and-headers model))
(defn get-registered-provider-config [provider-id] (models/get-registered-provider-config provider-id))
(defn get-registered-provider-ids [] (models/get-registered-provider-ids))
(defn register-provider! [provider-id config] (models/register-provider-config! provider-id config))
(defn unregister-provider! [provider-id] (models/unregister-provider-config! provider-id))

(def ^:private read-model-facades
  "Read-only model registry facades shared by the extension api's :models map
   and the context's :models (pi: ctx.modelRegistry reads)."
  {:get-all get-all-models
   :get-available get-available-models
   :find find-model
   :has-configured-auth has-configured-auth
   :get-provider-auth-status get-provider-auth-status
   :get-api-key-and-headers get-api-key-and-headers
   :get-registered-provider-config get-registered-provider-config
   :get-registered-provider-ids get-registered-provider-ids})

(defn- exec
  "Execute a shell command and return {:exit n :out str :err str}
   (extension api: exec). Options: :dir :env :timeout-ms. The child runs in
   the runtime cwd unless :dir overrides it (pi: options?.cwd ?? cwd) — the
   session's project after a switch; headless falls back to the process cwd."
  [command args & [{:keys [dir env timeout-ms]}]]
  (let [dir (or dir (:cwd (build-extension-context)))
        p (proc/process (concat [command] args)
                        (cond-> {:out :string :err :string}
                          dir (assoc :dir dir)
                          env (assoc :env env)
                          timeout-ms (assoc :timeout timeout-ms)))]
    {:exit (:exit @p) :out (:out @p) :err (:err @p)}))

(defn set-session!
  "Point the no-node session cache at SESSION without registering it on a
   tree: the node-less and testing primitive — engines install through
   install-session!."
  [session] (reset! session-atom session) nil)
(defn get-session [] @session-atom)
(defn set-context-sink!
  "Point the no-node context-sink cache at F without registering it: the
   node-less primitive — engines install through install-context-sink!."
  [f] (reset! context-sink-atom f) nil)
(defn set-entry-sink!
  "Point the no-node entry-sink cache at F without registering it: the
   node-less primitive — front ends install through install-entry-sink!."
  [f] (reset! entry-sink-atom f) nil)

(defn clear-runtime!
  "Teardown-only: drop the mode-bound runtime sinks (live session and the
   extension append sinks) so a stopped mode is no longer reachable through
   this namespace. Idempotent."
  []
  (reset! session-atom nil)
  (reset! context-sink-atom nil)
  (reset! entry-sink-atom nil)
  nil)

(defn- cache-surface!
  "Register VALUE under OWNER/KEY on NODE and install it in CACHE, the
   no-node read cache. The registration is what the store reports and what
   node disposal drops; the owner's teardown clears CACHE
   only while it still holds VALUE, so an outgoing registration can never
   clear a newer install (a session switch, a front end's rebind —
   scope.md §2).

   Two degenerate shapes install the cache alone: NODE nil (a node-less
   engine, e.g. a test double) has no tree to own the value, and VALUE nil
   (a context-less run) has no live surface to own. Returns nil."
  [node owner k cache v]
  (when node
    (when-let [prev @cache]
      (scope/deregister! node owner k prev))
    (when (some? v)
      (scope/register! node owner k v)
      (scope/add-teardown! node owner
                           (fn []
                             (when (identical? v @cache) (reset! cache nil))))))
  (reset! cache v)
  nil)

(defn install-session!
  "Install SESSION as the extension-facing live session for NODE (the
   tree's [:app :session] owner): the process cache `get-session` reads and
   the registration /units reports. Re-install after a session switch.
   Returns nil."
  [node session]
  (cache-surface! node [:app :session] :kmet/session session-atom session))

(defn install-context-sink!
  "Install SINK, a fn receiving a custom message map, for NODE's
   [:app :session] owner (`append-custom-message!` reads it). Returns nil."
  [node sink]
  (cache-surface! node [:app :session] :kmet/context-sink context-sink-atom sink))

(defn install-entry-sink!
  "Install SINK, a fn receiving an appended custom entry, for NODE's
   [:app :session] owner (`append-custom-entry!` reads it). Returns nil."
  [node sink]
  (cache-surface! node [:app :session] :kmet/entry-sink entry-sink-atom sink))

(defn append-custom-entry!
  "Append a custom entry (extension state, never in LLM context) to the live
   session (extension api: session :append-entry!). Returns the entry id."
  [custom-type & [data]]
  (when-let [sess @session-atom]
    (let [entry (session/append-custom-entry! sess custom-type data)]
      (when-let [sink @entry-sink-atom]
        (sink entry))
      (:id entry))))

(defn append-custom-message!
  "Append a custom message that participates in LLM context (extension api:
   session :append-message!). Returns the entry id."
  [custom-type content display & [details]]
  (when-let [sess @session-atom]
    (let [entry (session/append-custom-message-entry! sess custom-type
                                                      content display details)]
      (when-let [sink @context-sink-atom]
        (sink {:role :custom
               :custom-type custom-type
               :content content
               :display display
               :details details}))
      (:id entry))))

(defn get-custom-entries [custom-type]
  (if-let [sess @session-atom]
    (session/get-custom-entries sess custom-type)
    []))

(defn get-all-custom-entries [custom-type]
  (if-let [sess @session-atom]
    (session/get-all-custom-entries sess custom-type)
    []))

(defn get-branch-entries
  ([]
   (if-let [sess @session-atom]
     (session/get-branch sess)
     []))
  ([from-id]
   (if-let [sess @session-atom]
     (session/get-branch sess from-id)
     [])))

(defn get-leaf-id []
  (when-let [sess @session-atom]
    @(:leaf-id sess)))

(defn get-entry [entry-id]
  (when-let [sess @session-atom]
    (session/get-entry sess entry-id)))

(defn set-label! [entry-id label]
  (when-let [sess @session-atom]
    (session/set-label! sess entry-id label)))

(defn get-label [entry-id]
  (when-let [sess @session-atom]
    (session/get-label sess entry-id)))

;; ─── Extension API construction ──────────────────────────────────────────

(defn- track-deregister!
  "Record a deregister fn on the extension; unload runs them all."
  [ext f]
  (swap! (:deregister-fns ext) conj f))

(defn- api-ui
  "The :ui capability map — dispatches through the runtime registry, so it
   is inert before the layout exists and in headless mode. Only host-owned
   bridges live here: mounting extension-built components (:custom), the
   flash (:notify), chat-history info messages (:chat-info), and
   integrations with host layout/editor/status state. Extensions build
   their own components with the shared kmet.tui.* layer
   (pi: ctx.ui.custom hosting extension-built pi-tui components) — the api
   carries no host-built dialogs or theme lookups (kmet.tui.theme is
   shared directly).

   TRACK records the deregister fns of the registrations the front end holds
   (a terminal-input listener; that registry lives in the UI, outside the app
   tree) so unload removes exactly what this extension added. The rest of the
   map is calls and live UI state, which `/reload`'s `ui-reset!` restores
   (extension.md: unload is registration-complete, not screen-complete)."
  [track]
  {:notify ui-notify
   :custom ui-custom
   :chat-info ui-chat-info
   :on-terminal-input (fn [handler] (track (ui-on-terminal-input handler)))
   :set-status ui-set-status
   :set-widget ui-set-widget
   :set-footer ui-set-footer
   :set-header ui-set-header
   :set-title ui-set-title
   :set-editor-text ui-set-editor-text
   :get-editor-text ui-get-editor-text
   :paste-to-editor ui-paste-to-editor
   :set-working-indicator ui-set-working-indicator
   :set-working-message ui-set-working-message
   :set-working-visible ui-set-working-visible
   :set-hidden-thinking-label ui-set-hidden-thinking-label
   :set-editor-component ui-set-editor-component
   :add-autocomplete-provider ui-add-autocomplete-provider
   :set-theme ui-set-theme
   :get-tools-expanded ui-get-tools-expanded
   :set-tools-expanded ui-set-tools-expanded
   :get-tool-display-mode ui-get-tool-display-mode
   :set-tool-display-mode ui-set-tool-display-mode})

(defn- api-models
  "The :models capability map — ctx.models facades. TRACK records deregister
   fns so unload removes exactly what this extension added (provider
   registrations leak across unload/reload otherwise — they live in the
   global extension-providers atom, unlike ephemeral UI state which
   reload resets in bulk)."
  [track]
  (assoc read-model-facades
         :register-provider! (fn [provider-id config]
                               (let [result (register-provider! provider-id config)]
                                 ;; track only after a successful register — a broken
                                 ;; config throws without touching stored state
                                 (track (fn [] (unregister-provider! provider-id)))
                                 result))
         :unregister-provider! unregister-provider!))

(defn- api-session
  "The :session capability map — live session facades (pi:
   ctx.sessionManager — getBranch/getLeafId/getEntry included)."
  []
  {:append-entry! append-custom-entry!
   :append-message! append-custom-message!
   :get-entries get-custom-entries
   :get-all-entries get-all-custom-entries
   :get-branch get-branch-entries
   :get-leaf-id get-leaf-id
   :get-entry get-entry
   :set-label! set-label!
   :get-label get-label
   :set-name! (fn [name]
                (when-let [sess @session-atom]
                  (session/append-session-info! sess (session/sanitize-session-name name))))
   :get-name (fn [] (when-let [sess @session-atom] (session/get-session-name sess)))})

(declare loader-aware loader-aware-map)

(defn- create-extension-api
  "Build the api map for an extension, its app-tree capabilities bound to
   NODE — the app root at load time (extensions load before any engine), so a
   child node can build the same surface for its own run later. A
   registration that lives in the app tree (commands, tools, tool sources,
   skills, prompts, hooks, renderers, flags) lands under the extension's own
   owner path, so unload disposes it with one LIFO dispose (scope.md §2);
   the registrations that live outside the tree (event subscriptions, aliases,
   shortcuts, model providers) record their deregister fn on the extension
   instead."
  [node ext]
  (let [track (fn [f] (track-deregister! ext f) f)
        name (:name ext)]
    {:extension-name name
     :extension-path (:path ext)
     ;; the extension's own directory (dir ext = the dir itself, file ext
     ;; = the file's parent) — nil for jar extensions, which have no
     ;; directory (use io/resource instead)
     :extension-dir (extension-dir-of ext)
     ;; host agent dir (KMET_CODING_AGENT_DIR-aware; pi: getAgentDir) —
     ;; extensions keep their configs/caches under it
     :agent-dir (cfg/get-agent-dir)
     :register-command! (fn [cmd]
                          ;; the handler is stored under :extension-handler so
                          ;; the runner can pass it the extension context
                          ;; (pi: handler(args, ctx)) while builtin commands
                          ;; keep receiving CoreState. Registered under the
                          ;; extension's own owner, so unload disposes it
                          ;; (scope.md §2).
                          (let [cmd (loader-aware-map
                                     ext (assoc cmd :extension-handler
                                                (:handler cmd)))]
                            (commands/register-command! node cmd (extension-owner name))))
     :unregister-command! (fn [cmd-name]
                            (commands/unregister-command! node cmd-name (extension-owner name)))
     ;; sanitized — other extensions must not see :handler/:extension-handler
     :get-commands #(mapv (fn [c] (select-keys c [:name :description]))
                          (commands/get-commands))
     :register-tool! (fn [tool]
                       (tools/register-tool! node (loader-aware-map ext tool)
                                             (extension-owner name)))
     :unregister-tool! (fn [tool-name]
                         (tools/unregister-tool! node tool-name (extension-owner name)))
     ;; sandbox-only tools: contributed into the run_code tool's surface, never
     ;; the model's tool set (mcp-adapter's MCP catalog — run_code.md T2)
     :register-tool-source! (fn [id tools-fn]
                              (tools/register-tool-source!
                               node id tools-fn (extension-owner name)))
     :unregister-tool-source! (fn [id]
                                (tools/unregister-tool-source!
                                 node id (extension-owner name)))
     ;; pi: createBashTool — the bash tool constructor with its options
     ;; (spawnHook / exposeSessionEnvironment / commandPrefix / shellPath /
     ;; operations). The built-in bash tool is the same constructor with
     ;; default options; registering the result under a name replaces it.
     :create-bash-tool bash-tool/create-tool
     :get-all-tools #(vals (tools/get-all-tools))
     :get-active-tools get-active-tools
     :set-active-tools set-active-tools
     :on-event (fn [event-type handler]
                 (let [dereg (event-bus/on-event
                              event-type (extension-owner name)
                              (wrap-event-handler (loader-aware ext handler)))]
                   (track dereg)
                   dereg))
     :emit-event! event-bus/emit-event!
     ;; The hooks, renderers and flags live in the app tree under the
     ;; extension's own owner, so the store's disposal is their teardown and
     ;; they are NOT tracked: the tracked list stays for what lives outside
     ;; the tree (subscriptions, aliases, shortcuts, model providers).
     :on-input (fn [hook]
                 (register-input-hook! node (extension-owner name) (loader-aware ext hook)))
     :on-before-agent-start (fn [hook]
                              (register-before-agent-start-hook!
                               node (extension-owner name) (loader-aware ext hook)))
     :on-tool-call (fn [hook]
                     (register-tool-call-hook! node (extension-owner name)
                                               (loader-aware ext hook)))
     :on-tool-result (fn [hook]
                       (register-tool-result-hook! node (extension-owner name)
                                                   (loader-aware ext hook)))
     :register-flag! (fn [flag-name & [opts]]
                       (register-flag! node (extension-owner name) flag-name opts))
     :get-flag get-flag
     :register-shortcut! (fn [key-id & [opts]]
                           (let [dereg (register-shortcut!
                                        key-id
                                        (cond-> opts
                                          (:handler opts) (update :handler #(loader-aware ext %))))]
                             (track dereg)
                             dereg))
     :register-markdown-transformer! (fn [transformer]
                                       (register-markdown-transformer!
                                        node
                                        (extension-owner name)
                                        (loader-aware ext transformer)))
     :register-entry-renderer! (fn [custom-type renderer]
                                 (register-entry-renderer!
                                  node (extension-owner name) custom-type renderer))
     :register-message-renderer! (fn [custom-type renderer]
                                   (register-message-renderer!
                                    node (extension-owner name) custom-type renderer))
     ;; tag aliases (kmet.libs.hikap.alias): the registry is process-global, so the
     ;; registration MUST be tracked — unload removes exactly this fn's
     ;; registration (a later re-registration by another owner survives)
     :register-alias! (fn [alias-kw f]
                        (let [dereg (alias/register! alias-kw f)]
                          (track dereg)
                          dereg))
     :register-skill! (fn [raw-content & [opts]]
                        ;; The extension reads its own bundled SKILL.md via
                        ;; io/resource and hands the content over, so jarred
                        ;; skills need no filesystem path. Registered under the
                        ;; extension's owner, so unload disposes it.
                        (skills/register-extension-skill!
                         node raw-content
                         (assoc opts
                                :extension name
                                :owner (extension-owner name))))
     :register-prompt! (fn [prompt & [opts]]
                         (prompts/register-prompt-template!
                          node
                          (assoc (merge opts prompt)
                                 :extension name
                                 :owner (extension-owner name))))
     :set-model set-model
     :get-thinking-level get-thinking-level
     :set-thinking-level set-thinking-level
     :send-user-message send-user-message
     :send-message! send-message!
     :exec exec
     :ui (api-ui track)
     :models (api-models track)
     :session (api-session)}))

;; ─── Load orchestration + entry points ──────────────────────────────────
;; The isolation machinery (jars, class seeding, SCI base, loaders,
;; evaluation) lives in kmet.agent.extensions.context; the functions here
;; resolve extensions, wire the API, and register/unregister them.

(declare unload-extension!)

(defn- loader-aware
  "Wrap HANDLER so that whenever it runs, EXT's context is in effect.
   Native-Jolt handlers run with the loader's ambient tier pointing at
   EXT's context (`clojure.java.io/resource` and `RT.baseLoader` resolve
   through the extension's own loader — the native equivalent of the SCI
   path's injected artifact-scoped resource fn); SCI handlers on Jolt run
   with SCI's *out*/*err* bound (with-sci-io), and on babashka are already
   closures over a context whose io vars are bound, so those are identity.

   Everything an extension *registers* is wrapped here, because those
   callbacks run long after the load, invoked by the app, with nothing else
   to tell them which context they belong to. Renderer factories are the one
   exception: they are stored and compared by identity (see
   register-entry-renderer!), so wrapping them would break that contract —
   they read no resources at registration."
  [_ext handler]
  #?(:jolt (if (fn? handler)
             (case (:loader-kind _ext)
               :jolt (fn [& args]
                       (loader-jolt/with-loader* @(:loader _ext)
                         (fn [] (apply handler args))))
               :sci (fn [& args] (context/with-sci-io #(apply handler args))))
             handler)
     :default handler))

(defn- loader-aware-map
  "M with every fn value wrapped by `loader-aware`. The map-shaped
   registrations (commands, tools) carry callbacks besides their main one —
   :get-argument-completions, :render-call/:render-result, :title,
   :prepare-arguments, … — and the app invokes each of them long after the
   load, so each needs the ambient binding as much as the handler does.
   Non-fn values (:description, :parameters, :render-shell :self) pass
   through; a record stays a record (the reduce walks the map itself)."
  [ext m]
  (reduce-kv (fn [acc k v]
               (cond-> acc (fn? v) (assoc k (loader-aware ext v))))
             m m))

(defn- extension-var
  "The value of VAR-NAME in ENTRY-NS of EXT's context, or nil. Backend-
   agnostic: a :var request through the loader (the SCI backend answers from
   its context, the Jolt native backend from the namespace link it installed
   when the namespace loaded). A missing var, or an unloaded loader, is nil."
  [ext entry-ns var-name]
  (when-let [l @(:loader ext)]
    (try
      (loader/load l {:kind :var :name (str entry-ns "/" var-name)})
      (catch Throwable _ nil))))

(defn- load-extension*
  "Load RESOLVED — the map resolve-extension / resolve-extension-descriptor
   answers — with PATH naming it in results and errors. See load-extension!
   for the contract; the descriptor wrapper below shares this body. A name
   already in the registry (D11: the bundled layer ranks last, so a user's
   own copy loads first) is returned skipped, and no second context is
   built."
  [resolved path]
  (let [{:keys [name kind artifact entry-ns file declared-loaders legacy-loader?
                bundled?]} resolved
        loader-kind (if (context/resource-kind? kind)
                      (context/forced-loader-kind resolved declared-loaders)
                      (context/select-loader-kind declared-loaders))]
    (if (some #(= name (:name %)) @extensions)
      ;; same-name dedupe: the earlier copy wins, the loaders print the note
      {:extension name
       :path path
       :error nil
       :skipped true
       :reason :duplicate-name}
      (if-not loader-kind
        {:extension name
         :path path
         :error nil
         :skipped true
         :reason :unsupported-loader
         :declared-loaders declared-loaders
         :available-loaders (context/host-loader-preference)}
        (let [ext (map->Extension
                   {:name name
                    :path (:path resolved)
                    :kind kind
                    :loader-kind loader-kind
                    :bundled? (boolean bundled?)
                    :artifact artifact
                    :entry-ns (atom nil)
                    :loader (atom nil)
                    :jars (atom [])
                    :api (atom nil)
                    :deregister-fns (atom [])
                    :initialized? (atom false)
                    :jar-files (atom {})})]
          (try
            (when legacy-loader?
              (binding [*out* *err*]
                (println "Warning: extension" name
                         "has no :loader in extension.edn — assuming"
                         (pr-str context/default-loaders))))
            (let [jar? (context/jar-artifact? artifact)
                  deps (when artifact
                         (if jar?
                           (:deps (edn/read-string
                                   (or (context/jar-entry-source (:root artifact) "deps.edn") "{}")))
                           (context/deps-of-root artifact)))
                  jar-info (when jar? (context/jar-namespaces (:root artifact)))
                  owns-ns? (if artifact
                             (fn [ns-sym] (context/artifact-owns-ns? artifact jar-info ns-sym))
                             (constantly false))
                  deps-resolver (context/make-deps-resolver deps (:jars ext))
            ;; Force the closure resolution HERE, in host scope: during the
            ;; context's SCI eval, require/requiring-resolve run through the
            ;; loader's source provider (bb hosts the interpreter), and
            ;; clojure.tools.deps internally loads its dep-extension
            ;; namespaces via requiring-resolve — resolving lazily from
            ;; inside the eval re-enters this resolver until the stack
            ;; overflows. Resolved up front, the provider only reads the
            ;; cache.
                  _ (when deps-resolver (deps-resolver))
            ;; Single-file extensions have no artifact: the file itself is
            ;; the entry namespace, served as a literal source (its ns is
            ;; read here so the loader knows what to ask for).
                  file-source (when-not artifact (slurp file))
                  file-ns (when-not artifact
                            (or (some-> (context/ns-form-of-source file-source) second)
                                (throw (ex-info (str "Extension " name
                                                     " file does not start with (ns ...)")
                                                {:path path}))))
                  l (context/create-loader loader-kind ext artifact owns-ns? deps-resolver
                                           (when-not artifact
                                             {file-ns {:file (str file) :source file-source}}))]
              ;; bb-only: a native bb-bundled lib's Maven copy is served to
              ;; SCI contexts (dep-source wins over the host classpath) and
              ;; fails there on classes the GraalVM image does not expose;
              ;; the bundled implementation is not injected either — use a
              ;; kmet.libs.* seam or a pure-Clojure alternative. Libs whose
              ;; bundled port replaces the artifact wholesale were dropped
              ;; from the closure already (bundled-artifacts).
              #?(:bb
                 (doseq [lib (keys deps)
                         :let [lib (str lib)]
                         :when (and (contains? context/bb-bundled-libs lib)
                                    (not (contains? context/bundled-artifacts lib)))]
                   (binding [*out* *err*]
                     (println "Warning: extension" (:name ext) "pins" lib
                              "which babashka bundles natively — its Maven copy"
                              "cannot run under SCI and the bundled one is not"
                              "shared with extension contexts; use the relevant"
                              "kmet.libs.* seam."))))
              (reset! (:loader ext) l)
              (if artifact
                (do
                  (when-not (:source (context/artifact-source artifact entry-ns))
                    (throw (ex-info (str "extension.edn :entry not found: " entry-ns)
                                    {:path path :entry entry-ns})))
            ;; the source provider validates the entry's ns form (strict
            ;; layout + allowed requires) at locate, before anything
            ;; evaluates
                  (loader/load l {:kind :ns :name (str entry-ns)})
                  (reset! (:entry-ns ext) entry-ns))
                (do
                  (loader/load l {:kind :ns :name (str file-ns)})
                  (reset! (:entry-ns ext) file-ns)))
              (let [init-var (extension-var ext @(:entry-ns ext) 'init)]
                (when-not init-var
                  (throw (ex-info (str "Extension " (:name ext)
                                       " does not define an init fn")
                                  {:path path})))
                (let [api (create-extension-api (app-runtime/node) ext)]
                  (reset! (:api ext) api)
                  ((loader-aware ext (deref init-var)) api)
                  (reset! (:initialized? ext) true))))
            (swap! extensions conj ext)
            (debug/log "extension loaded: " name " kind=" (clojure.core/name kind)
                       " loader=" (clojure.core/name loader-kind)
                       " bundled=" (boolean bundled?))
            {:extension (:name ext) :error nil
             :loader-kind loader-kind
             :bundled (boolean bundled?)}
            (catch Exception e
              (unload-extension! ext)
              {:extension nil
               :path path
               :error (or (ex-message e)
                          (str "load failed: " (.getName (class e))))})))))))

(defn load-extension!
  "Load a single extension from PATH (.clj file, dir with extension.edn,
   or .jar/.zip archive with the same layout at its root). Each extension
   evaluates in its own isolated context; deps.edn jars are served only to
   that context, so different extensions may pin different versions of the
   same library. Calls the extension's init with its api. A successful load
   returns {:extension NAME :error nil :loader-kind :sci/:jolt}; an
   extension whose declared :loader this host does not offer is returned
   skipped ({:extension NAME :skipped true :reason :unsupported-loader
   :declared-loaders [...] :available-loaders [...]}); a name that is
   already loaded is returned skipped with :reason :duplicate-name. On
   failure everything is rolled back and {:extension nil :path PATH :error
   MSG} is returned (PATH names what failed — the result map has no
   extension name to report)."
  [path]
  (try
    (load-extension* (context/resolve-extension path) path)
    (catch Exception e
      {:extension nil
       :path path
       :error (or (ex-message e)
                  (str "load failed: " (.getName (class e))))})))

(defn load-extension-descriptor!
  "Load a single bundled extension from DESCRIPTOR
   (kmet.agent.extensions.bundled/artifacts). Same contract as
   load-extension!: in a source checkout the descriptor resolves to the
   real extensions/ path and loads exactly as load-extension! would; in a
   built artifact the files come from the extensions/ resource prefix. On
   Jolt, directory descriptors use their native `embed:<prefix>` root and
   single-file descriptors map their namespace to the exact embedded key
   (embedded roots are part of the Jolt floor; see forced-loader-kind)."
  [descriptor]
  (let [path (:path descriptor)]
    (try
      (load-extension* (context/resolve-extension-descriptor descriptor) path)
      (catch Exception e
        {:extension nil
         :path path
         :error (or (ex-message e)
                    (str "load failed: " (.getName (class e))))}))))

(defn unload-extension!
  "Unload an extension in two steps (scope.md §6): shutdown (if
   initialized), then dispose what it registered — the deregister fns recorded
   by the registrations that live outside the tree, in reverse registration
   order, then one LIFO dispose of its `[:app :extensions <name>]` owner, both
   reporting a failing cleanup through the scope's reporter — and drop its
   isolated context: namespaces and jars become unreachable, nothing global is
   touched. Expects the Extension record (from the registry /
   reload-extensions!); nil is a no-op — the load result map carries only its
   :extension name, and (deref nil) would otherwise surface as a cryptic NPE."
  [ext]
  (when ext
    (when (and @(:initialized? ext) @(:entry-ns ext))
      (when-let [shutdown (extension-var ext @(:entry-ns ext) 'shutdown)]
        (try ((loader-aware ext (deref shutdown)) @(:api ext))
             (catch Exception e
               (binding [*out* *err*]
                 (println "Warning: extension shutdown error:" (ex-message e)))))))
    ;; outside the tree first: a handler still subscribed while the store's
    ;; registrations go would see a half-unloaded extension
    (app-runtime/dispose-deregisters! @(:deregister-fns ext))
    (app-runtime/dispose-owner! (extension-owner (:name ext)))
    (when-let [l @(:loader ext)]
      (loader/unload! l))
    (reset! (:loader ext) nil)
    (when-let [jar-files (:jar-files ext)]
      #?(:jolt (reset! jar-files {})
         :default (let [jars @jar-files]
                    (doseq [[_ jf] jars]
                      (try (.close ^java.io.Closeable jf) (catch Exception _)))
                    (reset! jar-files {}))))
    (reset! (:jars ext) [])
    (swap! extensions (fn [exts] (remove #(identical? % ext) exts)))
    nil))

(defn unload-all-extensions!
  "Unload every loaded extension (reverse order)."
  []
  (doseq [ext (reverse @extensions)]
    (unload-extension! ext))
  nil)

(defn get-loaded-extensions
  "Loaded extensions as {:name str :path str :kind :file/:dir/:jar
   :loader-kind :sci/:jolt :entry-ns symbol :extension-dir str-or-nil
   :bundled bool} maps (extension-dir = the extension's own directory; nil
   for jar and bundled resource extensions)."
  []
  (mapv (fn [ext] {:name (:name ext) :path (:path ext)
                   :kind (:kind ext)
                   :loader-kind (:loader-kind ext)
                   :entry-ns @(:entry-ns ext)
                   :bundled (boolean (:bundled? ext))
                   :extension-dir (extension-dir-of ext)})
        @extensions))

(defn extension-jars
  "Jar paths of the named loaded extension (its deps.edn closure), or nil."
  [name]
  (some-> (first (filter #(= name (:name %)) @extensions))
          :jars deref))

(defn- load-contributed-themes!
  "Load DIR's theme files and register them under OWNER. The theme store is
   the TUI layer's own (`kmet.tui.theme`, shared by reference), outside the
   app tree, so the registration is the host's record of the theme and a
   teardown that removes it from the store when the owner goes — guarded by
   the value it registered, so a re-application's earlier teardown (holding
   the replaced value) and another contributor's later registration both
   survive it (scope.md §2)."
  [node owner dir]
  (let [owner (or owner themes-owner)]
    (doseq [{:keys [name] :as theme} (context/load-themes-from-dir! dir)]
      (scope/add-teardown! node owner
                           (fn [] (context/unregister-theme! name theme)))
      (scope/register! node owner (theme-key name) theme))))

(defn- apply-contributed-paths!
  "Apply the resource paths one contributor contributed: skill and prompt
   directories load into their registries, theme directories into the theme
   store. OWNER is the contributor — an extension's owner path, or nil for a
   host handler, which lands in the app's own registries. Loading under an
   owner is what makes re-application idempotent (the registries replace per
   owner and key) and unload complete (disposing the owner takes the
   resources with it)."
  [node owner {:keys [skill-paths prompt-paths theme-paths]}]
  (doseq [p skill-paths] (skills/load-skills-from-dir node p owner))
  (doseq [p prompt-paths] (prompts/load-prompt-templates-from-dir node p owner))
  (doseq [p theme-paths] (load-contributed-themes! node owner p)))

(defn discover-resources!
  "pi: extendResourcesFromExtensions — fire the :resources-discover event
   (payload {:cwd :reason}) after :session-start and apply every handler's
   contributed resource paths: :skill-paths / :prompt-paths load into the
   skills/prompts registries, :theme-paths into the theme store (pi:
   resourceLoader.extendResources → updateSkills/Prompts/ThemesFromPaths;
   paths are directories). ALL handler results are collected (pi collects
   each handler's contribution — see emit-event-collect!), each applied under
   the owner that contributed it, so re-discovery after the next session
   start replaces the same names instead of duplicating them (pi's mergePaths
   dedup, done by the store rather than a path set) and unloading an
   extension takes its resources with it. A throwing handler is skipped (its
   paths are lost). Returns the collected
   {:skill-paths [...] :prompt-paths [...] :theme-paths [...]}."
  [node reason]
  (let [contributions (event-bus/emit-event-collect!
                       {:type :resources-discover
                        :cwd (str (fs/cwd))
                        :reason reason})]
    (reduce (fn [acc [owner result]]
              (apply-contributed-paths! node owner result)
              (cond-> acc
                (seq (:skill-paths result))
                (update :skill-paths into (:skill-paths result))
                (seq (:prompt-paths result))
                (update :prompt-paths into (:prompt-paths result))
                (seq (:theme-paths result))
                (update :theme-paths into (:theme-paths result))))
            {:skill-paths [] :prompt-paths [] :theme-paths []}
            contributions)))

(defn clear-extensions!
  "Unload all extensions (used by /reload and tests). The resources an
   extension contributed were registered under its owner, so disposing that
   owner already took them: nothing else needs resetting."
  []
  (unload-all-extensions!))

(defn registered-extensions
  "Currently loaded Extension records (the registry vector)."
  []
  @extensions)

(defn extension-artifact-paths
  "Extension artifact paths inside container DIR (a directory): top-level
   .clj files, .jar/.zip archives, and subdirectories containing
   extension.edn (a directory without the manifest is an extension's own
   layout, not an extension). Sorted by path. [] for a missing dir."
  [dir]
  (let [d (io/file dir)]
    (if-not (fs/directory? d)
      []
      (->> (fs/list-dir d)
           (sort-by str)
           (keep (fn [entry]
                   (let [path (str entry)
                         lower (str/lower-case path)]
                     (cond
                       (and (fs/regular-file? entry) (str/ends-with? path ".clj")) path
                       (and (fs/regular-file? entry)
                            (or (str/ends-with? lower ".jar")
                                (str/ends-with? lower ".zip"))) path
                       (and (fs/directory? entry)
                            (fs/exists? (io/file (str entry) "extension.edn"))) path
                       :else nil))))
           vec))))

(defn- report-load-result!
  "Print the warning/note for a load RESULT (the load functions write
   nothing themselves); returns RESULT. A :duplicate-name skip names the
   earlier copy; an :unsupported-loader skip names the declared and
   available backends; an error is a warning."
  [label result]
  (when (and result (:error result))
    (binding [*out* *err*]
      (println "Warning: Failed to load extension" label ":"
               (:error result))))
  (when (:skipped result)
    (binding [*out* *err*]
      (if (= :duplicate-name (:reason result))
        (println "Note: extension" (:extension result) "from" label
                 "is already loaded — skipping (the earlier copy wins)")
        (println "Note: extension" (:extension result) "declares loaders"
                 (pr-str (:declared-loaders result)) "but" (host/runtime-name)
                 "offers" (pr-str (:available-loaders result)) "— skipping"))))
  result)

(defn load-extension-paths!
  "Load extensions from explicit artifact paths (.clj/.jar/.zip files or
   extension.edn directories — the package-resource unit, pi: package
   extensions load). Returns the list of per-extension {:extension name
   :error} results (:skipped true for an extension whose declared loaders
   this host does not offer, or whose name is already loaded); failures
   and skips are also printed as notes."
  [paths]
  (mapv (fn [path] (report-load-result! path (load-extension! path)))
        paths))

(defn load-extension-descriptors!
  "Load bundled extension DESCRIPTORS
   (kmet.agent.extensions.bundled/artifacts) — load-extension-paths!'s
   descriptor counterpart: the same result maps and notes, with :path the
   descriptor's synthetic identity."
  [descriptors]
  (mapv (fn [descriptor]
          (report-load-result! (:path descriptor)
                               (load-extension-descriptor! descriptor)))
        descriptors))

(defn load-extensions-from-dir
  "Load all extensions in DIR (a container): top-level .clj files, .jar/.zip
   archives, and subdirectories containing extension.edn. Returns the list of
   per-extension {:extension name :error} results; failures are also printed
   as warnings."
  [dir]
  (let [d (io/file dir)]
    (when (fs/directory? d)
      (load-extension-paths! (extension-artifact-paths (str d))))))

(defn reload-extensions!
  "Unload all loaded extensions, then load from DIRS. Returns the list of
   per-extension {:extension name :error} results. Extension UI (widgets,
   custom footer/header/editor, dialogs) is reset first (pi: reload calls
   resetExtensionUI before reloading)."
  [dirs]
  (ui-call :reset)
  (unload-all-extensions!)
  ;; EAGER (vec around the mapcat): loading is a side effect, and a lazy
  ;; result means a caller that ignores the return value never loads
  ;; anything. bb's apply/concat realizes the seq anyway; jolt defers,
  ;; so the same code silently loaded nothing there.
  (vec (mapcat load-extensions-from-dir dirs)))