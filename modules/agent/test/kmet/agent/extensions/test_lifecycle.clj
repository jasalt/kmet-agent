(ns kmet.agent.extensions.test-lifecycle
  "Extension lifecycle against the real runtime: load a single file or a manifest,
   per-extension ownership of registrations and resource paths, and rollback when
   loading or init fails."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.agent.extension :as ext]
            [kmet.libs.host :as host]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.runtime :as rt]
            [kmet.agent.commands :as commands]
            [kmet.agent.event-bus :as event-bus]
            [kmet.agent.prompts :as prompts]
            [kmet.agent.skills :as skills]
            [kmet.agent.tools.core :as tools]
            [kmet.libs.hikap.alias :as alias]
            [kmet.tui.theme :as theme]
            [kmet.libs.scope :as scope]))

;; ─── Load / unload / reload lifecycle (real runtime) ─────────────────────

(t/deftest test-load-single-file-extension
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (t/is (some #(= "hello_ext.clj" (:name %)) (extensions/get-loaded-extensions)))
    (testing "registrations live in the real registries"
      (t/is (some? (commands/find-command "hello-ext")))
      (t/is (some? (tools/get-tool "hello-ext-tool")))
      (t/is (some? (extensions/get-flag "ext-hello")))
      (t/is (fn? (alias/lookup :hello-ext/chip))
            "the extension's tag alias registered through the api"))
    (testing "unload removes everything"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions)))
      (t/is (nil? (commands/find-command "hello-ext")))
      (t/is (nil? (tools/get-tool "hello-ext-tool")))
      (t/is (nil? (extensions/get-flag "ext-hello")))
      (t/is (nil? (alias/lookup :hello-ext/chip))
            "the alias was deregistered with the extension"))))

(t/deftest test-load-manifest-extension
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-dir")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "multi-file: helper ns + entry ns load isolated; tool from helper works"
      (t/is (some? (tools/get-tool "multi-ext-tool")))
      (t/is (= "multi-ok" (:content (tools/execute-tool "multi-ext-tool" {}))))
      (if (host/jolt?)
        ;; The native backend keeps a context's namespaces in the process-global
        ;; registry — context-private there: the root hides them and unload
        ;; unmaps them (loader.md §9 Phase 2 records the one-registry gap) — so
        ;; the host-invisibility claim is the SCI backends' alone.
        (t/is (some? (find-ns 'multi-ext.main)))
        (do (t/is (nil? (find-ns 'multi-ext.main))
                  "extension namespaces never enter the global registry")
            (t/is (nil? (find-ns 'multi-ext.helper))))))
    (testing "unload removes the tool"
      (extensions/unload-all-extensions!)
      (t/is (nil? (tools/get-tool "multi-ext-tool")))
      (t/is (nil? (find-ns 'multi-ext.main))
            "unload unmaps the native backend's context namespaces")
      (t/is (empty? (extensions/get-loaded-extensions))))))

(t/deftest test-extension-registrations-are-owned-by-the-extension
  ;; P5c: what init(api) registers lands on [:app :extensions <name>], so
  ;; unload is one LIFO dispose of that owner instead of a tracked deregister
  ;; per registration (scope.md §2). Only what lives outside the store —
  ;; here an event subscription — stays on the extension's own list.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-owned-registrations"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/owned_ext"))
    (spit (str dir "/extension.edn")
          "{:name \"owned-ext\" :entry owned-ext.main :loader [:sci :jolt]}\n")
    (spit (str dir "/owned_ext/main.clj")
          (str "(ns owned-ext.main\n  (:require [kmet.agent.extension :as ext]))\n"
               "(defn init [api]\n"
               "  (ext/register-command! api {:name \"owned-cmd\" :description \"d\"\n"
               "                              :handler (fn [_ _] nil)})\n"
               "  (ext/register-tool! api {:name \"owned-tool\" :label \"T\"\n"
               "                           :description \"d\" :parameters []\n"
               "                           :execute (fn [_] {:content \"ok\"})})\n"
               "  (ext/register-tool-source! api :owned-source (fn [] {}))\n"
               "  (ext/register-skill! api \"---\\nname: owned-skill\\ndescription: d\\n---\\nbody\")\n"
               "  (ext/register-prompt! api {:name \"owned-prompt\" :content \"body\"})\n"
               "  (ext/register-flag! api \"owned-flag\")\n"
               "  (ext/on-event api :session-start (fn [_ _] nil)))\n"))
    (try
      (let [result (extensions/load-extension! dir)
            owner [:app :extensions "owned-ext"]]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (doseq [k [[:kmet/command "owned-cmd"]
                   [:kmet/tool "owned-tool"]
                   [:kmet/tool-source :owned-source]
                   [:kmet/skill "owned-skill"]
                   [:kmet/prompt "owned-prompt"]
                   [:kmet/flag "owned-flag"]]]
          (t/is (= [owner] (vec (keys (scope/for-key (rt/node) k))))
                (str k " is owned by the extension")))
        (t/is (= 1 (count @(:deregister-fns (first (extensions/registered-extensions)))))
              "only the event subscription stays on the tracked list")
        (extensions/unload-all-extensions!)
        (t/is (nil? (commands/find-command "owned-cmd")))
        (t/is (nil? (tools/get-tool "owned-tool")))
        (t/is (nil? (skills/get-skill "owned-skill")))
        (t/is (nil? (prompts/get-prompt-template "owned-prompt")))
        (t/is (empty? (scope/for-key (rt/node) [:kmet/flag "owned-flag"]))
              "one dispose took everything the extension registered"))
      (finally
        (extensions/clear-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-a-terminal-input-listener-is-tracked
  ;; The front end's listener registry lives outside the app tree, so its
  ;; unsubscriber is what records on the extension and unload runs it
  ;; (scope.md §2 — tracked is the outside-the-tree half).
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-terminal-input"
        handlers (atom [])
        unsubscribed (atom [])]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (spit (str dir "/ext.clj")
          (str "(ns term-ext (:require [kmet.agent.extension :as ext]))\n"
               "(defn init [api]\n"
               "  (ext/ui-on-terminal-input api (fn [_data] nil)))\n"))
    (extensions/bind-extensions!
     {:mode :interactive :has-ui true
      :ui {:on-terminal-input (fn [handler]
                                (swap! handlers conj handler)
                                (fn [] (swap! unsubscribed conj :unsub)))}})
    (try
      (t/is (nil? (:error (extensions/load-extension! (str dir "/ext.clj")))))
      (t/is (= 1 (count @handlers)) "the listener reached the front end's registry")
      (extensions/unload-all-extensions!)
      (t/is (= [:unsub] @unsubscribed) "unload ran the unsubscriber it recorded")
      (finally
        (extensions/clear-extensions!)
        (extensions/unbind-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-contributed-resource-paths-are-owned-by-the-contributor
  ;; P5c: a :resources-discover contribution loads under the contributor's
  ;; owner, so re-discovery replaces instead of duplicating (no applied-path
  ;; set) and unload takes the skills, prompts and themes with it.
  (extensions/clear-extensions!)
  (event-bus/clear-event-listeners!)
  (let [dir "target/test-ext-resource-owner"
        skill-dir (str dir "/skills")
        prompt-dir (str dir "/prompts")
        theme-dir (str dir "/themes")
        owner [:app :extensions "res-owner"]]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/res_owner"))
    (fs/create-dirs skill-dir)
    (fs/create-dirs prompt-dir)
    (fs/create-dirs theme-dir)
    (spit (str dir "/extension.edn")
          "{:name \"res-owner\" :entry res-owner.main :loader [:sci :jolt]}\n")
    (spit (str dir "/res_owner/main.clj")
          (str "(ns res-owner.main\n  (:require [kmet.agent.extension :as ext]))\n"
               "(defn init [api]\n"
               "  (ext/on-event api :resources-discover\n"
               "    (fn [_ _]\n"
               (pr-str {:skill-paths [skill-dir]
                        :prompt-paths [prompt-dir]
                        :theme-paths [theme-dir]})
               ")))\n"))
    (spit (str skill-dir "/SKILL.md")
          "---\nname: res-owned-skill\ndescription: From res-owner\n---\nbody")
    (spit (str prompt-dir "/res-owned-prompt.md") "Prompt body from res-owner")
    (spit (str theme-dir "/res-owned-theme.edn")
          (pr-str {:name "res-owned-theme"
                   :colors (into {} (map (fn [k] [(name k) "#000000"])
                                         (concat theme/FG-TOKENS theme/BG-TOKENS)))}))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result))))
      (t/is (= {:skill-paths [skill-dir] :prompt-paths [prompt-dir]
                :theme-paths [theme-dir]}
               (extensions/discover-resources! (rt/node) :startup))
            "the collected paths are reported")
      (doseq [k [[:kmet/skill "res-owned-skill"]
                 [:kmet/prompt "res-owned-prompt"]
                 [:kmet/theme "res-owned-theme"]]]
        (t/is (= [owner] (vec (keys (scope/for-key (rt/node) k))))
              (str k " is owned by the contributor")))
      (t/is (some? (theme/get-theme-by-name "res-owned-theme")))
      (extensions/discover-resources! (rt/node) :startup)
      (t/is (= 1 (count (filter #(= "res-owned-skill" (:name %)) (skills/get-skills)))))
      (t/is (= 1 (count (filter #(= "res-owned-prompt" (:name %))
                                (prompts/get-prompt-templates)))))
      (t/is (= 1 (count (filter (fn [[k _ _]] (= [:kmet/theme "res-owned-theme"] k))
                                (scope/ordered-registrations (rt/node)))))
            "a second discovery re-registers in place")
      (extensions/unload-all-extensions!)
      (t/is (nil? (skills/get-skill "res-owned-skill")))
      (t/is (nil? (prompts/get-prompt-template "res-owned-prompt")))
      (t/is (nil? (theme/get-theme-by-name "res-owned-theme"))
            "the owner's teardown removed the theme from the theme store")
      (t/is (empty? (scope/for-key (rt/node) [:kmet/theme "res-owned-theme"])))
      (finally
        (extensions/clear-extensions!)
        (event-bus/clear-event-listeners!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-resource-in-a-late-callback
  ;; Every fn an extension registers runs in its own context whenever the app
  ;; calls it — including the fns inside a registration map that are not the
  ;; handler. The manifest fixture's :get-argument-completions reads a file
  ;; bundled at the extension's own root: on the SCI backend the injected
  ;; io/resource answers it, on Jolt the callback's ambient loader does.
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-dir")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (let [cmd (commands/find-command "multi-ext-cmd")]
      (t/is (some? cmd) "the command registered")
      (t/is (= [{:value "from-own-root"}]
               ((:get-argument-completions cmd) ""))
            "the later callback resolves the extension's own resource"))
    (extensions/unload-all-extensions!)))
