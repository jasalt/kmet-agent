(ns kmet.agent.test-commands
  "Tests for kmet.agent.commands — slash command registry, and the runtime
   builtins' registry-facing behavior."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.agent.commands :as commands]
            [kmet.agent.commands.builtins :as builtins]
            [kmet.agent.runtime :as app-runtime]
            [kmet.agent.tools.registry :as tools-reg]
            [kmet.cells.scope :as scope]
            [kmet.test-utils :as tu]))

;; The builtins' registry-facing tests fill the app tree's slash registry;
;; clear the process-global registrations before and after every test.
(t/use-fixtures :each tu/with-clean-app-registries)

(t/deftest register-and-find
  (commands/register-command! (app-runtime/node)
                              {:name "test" :description "Test command" :handler (fn [_ _] :ok)})
  (t/is (= "test" (:name (commands/find-command "test"))))
  (t/is (nil? (commands/find-command "missing"))))

(t/deftest register-replaces-by-name
  (commands/register-command! (app-runtime/node)
                              {:name "dup" :description "first" :handler (fn [_ _] :one)})
  (commands/register-command! (app-runtime/node)
                              {:name "dup" :description "second" :handler (fn [_ _] :two)})
  (t/is (= 1 (count (filter #(= (:name %) "dup") (commands/get-commands)))))
  (t/is (= "second" (:description (commands/find-command "dup")))))

(t/deftest unregister-removes
  (commands/register-command! (app-runtime/node)
                              {:name "gone" :description "x" :handler (fn [_ _] nil)})
  (commands/unregister-command! (app-runtime/node) "gone")
  (t/is (nil? (commands/find-command "gone"))))

(t/deftest other-registries-share-the-store-without-interference
  ;; An owner can hold several registries (cells.md §2): a non-command key in
  ;; the same store must not break listing or clearing, and its bare keyword
  ;; key has no head to match on.
  (let [node (app-runtime/node)
        hook (fn [] nil)
        dereg (scope/register! node [:app :test] :tools/hooks hook)]
    (try
      (commands/register-command! (app-runtime/node)
                                  {:name "only-command" :description "x" :handler (fn [_ _] nil)})
      (t/is (= ["only-command"] (mapv :name (commands/get-commands))))
      (commands/clear-commands! (app-runtime/node))
      (t/is (empty? (commands/get-commands)))
      (t/is (identical? hook (get (scope/for-key node :tools/hooks) [:app :test]))
            "the other registry kept its value")
      (finally (dereg)))))

(t/deftest an-earlier-owners-command-owns-the-name
  ;; Owners are searched in first-registration order, so an extension loaded
  ;; before the builtins keeps a name it took — the rule register-builtin!
  ;; skips on. unregister-command! and clear-commands! reach every owner.
  (let [node (app-runtime/node)
        extension-owner [:app :extensions "demo"]
        demo {:name "taken" :description "extension" :handler (fn [_ _] nil)}]
    ;; start the owner order from here: first-registration order is what the
    ;; rule reads, and an earlier test may have touched the app's owner
    (scope/dispose-owner! node commands/commands-owner)
    (scope/register! node extension-owner [:kmet/command "taken"] demo)
    (try
      (commands/register-command! (app-runtime/node)
                                  {:name "taken" :description "builtin" :handler (fn [_ _] nil)})
      (t/is (= "extension" (:description (commands/find-command "taken"))))
      (t/is (= ["taken"] (mapv :name (commands/get-commands)))
            "one entry per name, not one per owner")
      (commands/unregister-command! (app-runtime/node) "taken")
      (t/is (nil? (commands/find-command "taken")) "every owner's entry went")
      (finally
        (scope/dispose-owner! node extension-owner)
        (commands/clear-commands! (app-runtime/node))))))

(t/deftest the-registry-lives-in-the-app-roots-store
  ;; The registry is the store, not an engine field: a registration is
  ;; readable through the store under the app's owner, and clear-commands!
  ;; removes it by key (an owner can hold several registries — cells.md §2).
  (commands/register-command! (app-runtime/node)
                              {:name "stored" :description "x" :handler (fn [_ _] nil)})
  (t/is (identical? (:handler (commands/find-command "stored"))
                    (:handler (get (scope/for-key (app-runtime/node)
                                                  [:kmet/command "stored"])
                                   commands/commands-owner))))
  (t/is (= 1 (count (commands/get-commands))))
  (commands/clear-commands! (app-runtime/node))
  (t/is (empty? (commands/get-commands)))
  (t/is (empty? (scope/for-key (app-runtime/node) [:kmet/command "stored"]))))

(t/deftest get-commands-keeps-registration-order
  ;; The palette and :get-commands list in registration order, and replacing a
  ;; command moves it to the end — the vector registry's order, kept by the
  ;; store's per-owner order.
  (commands/register-command! (app-runtime/node) {:name "beta" :description "x" :handler (fn [_ _] nil)})
  (commands/register-command! (app-runtime/node) {:name "alpha" :description "x" :handler (fn [_ _] nil)})
  (t/is (= ["beta" "alpha"] (mapv :name (commands/get-commands))))
  (commands/register-command! (app-runtime/node) {:name "beta" :description "again" :handler (fn [_ _] nil)})
  (t/is (= ["alpha" "beta"] (mapv :name (commands/get-commands)))))

(t/deftest handler-receives-ctx-and-args
  (let [received (atom nil)]
    (commands/register-command! (app-runtime/node)
                                {:name "echo"
                                 :description "echo args"
                                 :handler (fn [ctx args] (reset! received [ctx args]))})
    (let [ctx {:runtime :engine :ui :ui :frontend :interactive}]
      ((:handler (commands/find-command "echo")) ctx "hello"))
    (t/is (= [{:runtime :engine :ui :ui :frontend :interactive} "hello"] @received))))

(t/deftest units-lists-the-store-owners
  ;; The /units surface renders every store owner: the registries' owner paths
  ;; with their keys, one flat line each (kmet.agent.runtime; cells.md §2,
  ;; §7).
  (builtins/register-runtime-commands!)
  (tools-reg/register-tool! (app-runtime/node) {:name "units-probe" :description "d"
                                                :parameters {:type "object" :properties {}}
                                                :execute (fn [_] {:content "ok"})})
  (try
    (let [got (atom nil)]
      ((:handler (commands/find-command "units"))
       {:ui {:info (fn [label content] (reset! got [label content]))}} nil)
      (t/is (= "Units" (first @got)))
      (t/is (every? #(not (str/starts-with? % " "))
                    (str/split-lines (second @got)))
            "one flat line per owner, no indentation")
      (t/is (str/includes? (second @got) "[:app :commands] keys=")
            "the commands registry's address renders from the store")
      (t/is (str/includes? (second @got) "[:kmet/command \"units\"]")
            "the registered command shows as the registry owner's key")
      (t/is (str/includes? (second @got) "[:app :tools] keys=")
            "the tools registry's address renders from the store")
      (t/is (str/includes? (second @got) "[:kmet/tool \"units-probe\"]")
            "a registered tool shows as the tools registry owner's key"))
    (finally
      (tools-reg/unregister-tool! (app-runtime/node) "units-probe"))))

(t/deftest units-follows-the-session-engine-node
  ;; A session that runs in its own tree shows that tree, not the app root.
  (builtins/register-runtime-commands!)
  (let [mine (app-runtime/child-node)
        got (atom nil)]
    (scope/register! mine [:app :probe] :k/probe "v")
    ((:handler (commands/find-command "units"))
     {:runtime {:agent-state (atom {:node mine})}
      :ui {:info (fn [label content] (reset! got [label content]))}} nil)
    (t/is (str/includes? (second @got) "[:app :probe] keys=[:k/probe]"))))

(t/deftest register-builtin-skips-a-taken-name
  (commands/register-command! (app-runtime/node)
                              {:name "mine" :description "extension version" :handler (fn [_ _] nil)})
  (commands/register-builtin! (app-runtime/node) {:name "mine" :description "builtin" :handler (fn [_ _] nil)})
  (commands/register-builtin! (app-runtime/node) {:name "fresh" :description "builtin" :handler (fn [_ _] nil)})
  (t/is (= "extension version" (:description (commands/find-command "mine"))))
  (t/is (= "builtin" (:description (commands/find-command "fresh"))))
  (t/is (nil? (:frontend (commands/find-command "fresh")))
        "engine-portable builtins carry no front-end tag"))

(t/deftest clear-removes-all
  (commands/register-command! (app-runtime/node)
                              {:name "a" :description "a" :handler (fn [_ _] nil)})
  (commands/register-command! (app-runtime/node)
                              {:name "b" :description "b" :handler (fn [_ _] nil)})
  (commands/clear-commands! (app-runtime/node))
  (t/is (empty? (commands/get-commands))))
