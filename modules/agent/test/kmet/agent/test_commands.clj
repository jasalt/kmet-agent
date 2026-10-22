(ns kmet.agent.test-commands
  "Tests for kmet.agent.commands — slash command registry, and the runtime
   builtins' registry-facing behavior."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.agent.commands :as commands]
            [kmet.agent.commands.builtins :as builtins]
            [kmet.agent.app-runtime :as app-runtime]
            [kmet.agent.registry :as registry]
            [kmet.agent.tools.registry :as tools-reg]
            [kmet.libs.scope :as scope]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]))

;; The builtins' registry-facing tests fill the app tree's slash registry;
;; clear the process-global registrations before and after every test.
(t/use-fixtures :each tu/with-clean-app-registries)

(t/deftest register-and-find
  (commands/register-command!
   {:name "test" :description "Test command" :handler (fn [_ _] :ok)})
  (t/is (= "test" (:name (commands/find-command "test"))))
  (t/is (nil? (commands/find-command "missing"))))

(t/deftest register-replaces-by-name
  (commands/register-command!
   {:name "dup" :description "first" :handler (fn [_ _] :one)})
  (commands/register-command!
   {:name "dup" :description "second" :handler (fn [_ _] :two)})
  (t/is (= 1 (count (filter #(= (:name %) "dup") (commands/get-commands)))))
  (t/is (= "second" (:description (commands/find-command "dup")))))

(t/deftest unregister-removes
  (commands/register-command!
   {:name "gone" :description "x" :handler (fn [_ _] nil)})
  (commands/unregister-command!  "gone")
  (t/is (nil? (commands/find-command "gone"))))

(t/deftest other-registries-share-the-store-without-interference
  ;; An owner can hold several registries (scope.md §2): a non-command key in
  ;; the same store must not break listing or clearing, and its bare keyword
  ;; key has no head to match on.
  (let [node (app-runtime/node)
        hook (fn [] nil)
        dereg (scope/register! node [:app :test] :tools/hooks hook)]
    (try
      (commands/register-command!
       {:name "only-command" :description "x" :handler (fn [_ _] nil)})
      (t/is (= ["only-command"] (mapv :name (commands/get-commands))))
      (commands/clear-commands!)
      (t/is (empty? (commands/get-commands)))
      (t/is (identical? hook (get (scope/for-key node :tools/hooks) [:app :test]))
            "the other registry kept its value")
      (finally (dereg)))))

(t/deftest an-earlier-owners-command-owns-the-name
  ;; Owners are searched in first-registration order, so an extension loaded
  ;; before the builtins keeps a name it took — the rule register-builtin!
  ;; skips on. unregister-command! and clear-commands! reach every owner.
  (let [extension-owner [:app :extensions "demo"]
        demo {:name "taken" :description "extension" :handler (fn [_ _] nil)}]
    ;; start the owner order from here: first-registration order is what the
    ;; rule reads, and an earlier test may have touched the app's owner. The
    ;; extension registers through the same call it uses in production.
    (commands/clear-commands!)
    (commands/register-command!  demo extension-owner)
    (try
      (commands/register-command!
       {:name "taken" :description "builtin" :handler (fn [_ _] nil)})
      (t/is (= "extension" (:description (commands/find-command "taken"))))
      (t/is (= ["taken"] (mapv :name (commands/get-commands)))
            "one entry per name, not one per owner")
      (commands/unregister-command!  "taken")
      (t/is (nil? (commands/find-command "taken")) "every owner's entry went")
      (finally
        (commands/clear-commands!)))))

(t/deftest the-registry-lives-in-the-app-ctx
  ;; The registry is the app root ctx's `:commands` service, not an engine
  ;; field: a registration is readable through the service handle, and
  ;; clear-commands! removes it by tag.
  (commands/register-command!
   {:name "stored" :description "x" :handler (fn [_ _] nil)})
  (t/is (identical? (:handler (commands/find-command "stored"))
                    (:handler (registry/entry (srce/get (app-runtime/ctx) :commands)
                                              :kmet/command "stored"))))
  (t/is (= 1 (count (commands/get-commands))))
  (commands/clear-commands!)
  (t/is (empty? (commands/get-commands)))
  (t/is (empty? (registry/entries (srce/get (app-runtime/ctx) :commands)
                                  :kmet/command))))

(t/deftest get-commands-keeps-registration-order
  ;; The palette and :get-commands list in registration order, and replacing a
  ;; command moves it to the end — the vector registry's order, kept by the
  ;; store's per-owner order.
  (commands/register-command!  {:name "beta" :description "x" :handler (fn [_ _] nil)})
  (commands/register-command!  {:name "alpha" :description "x" :handler (fn [_ _] nil)})
  (t/is (= ["beta" "alpha"] (mapv :name (commands/get-commands))))
  (commands/register-command!  {:name "beta" :description "again" :handler (fn [_ _] nil)})
  (t/is (= ["alpha" "beta"] (mapv :name (commands/get-commands)))))

(t/deftest handler-receives-ctx-and-args
  (let [received (atom nil)]
    (commands/register-command!
     {:name "echo"
      :description "echo args"
      :handler (fn [ctx args] (reset! received [ctx args]))})
    (let [ctx {:runtime :engine :ui :ui :frontend :interactive}]
      ((:handler (commands/find-command "echo")) ctx "hello"))
    (t/is (= [{:runtime :engine :ui :ui :frontend :interactive} "hello"] @received))))

(t/deftest units-lists-the-store-owners-and-the-registries
  ;; The /units surface renders the store owners (one flat line each, scope.md
  ;; §2, §7) and — since the registries left the store at 4c-ii — one line per
  ;; registry service from the app ctx.
  (builtins/register-runtime-commands!)
  (tools-reg/register-tool!  {:name "units-probe" :description "d"
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
      (t/is (not (str/includes? (second @got) "[:app :commands] keys="))
            "the registry addresses are no longer store owners")
      (t/is (str/includes? (second @got) ":commands keys=[")
            "the commands registry renders from the app ctx's service")
      (t/is (str/includes? (second @got) "\"units\"")
            "a registered command is one of its keys")
      (t/is (str/includes? (second @got) ":tools keys=[")
            "the tools registry renders from its service")
      (t/is (str/includes? (second @got) "\"units-probe\"")
            "a registered tool is one of its keys"))
    (finally
      (tools-reg/unregister-tool!  "units-probe"))))

(t/deftest units-renders-the-app-root-store-and-the-registries
  ;; /units walks the app root's store and reads the registry services from
  ;; the ctx; the engine's own node left the picture at 4c-ii-b, and 4d
  ;; replaces both halves with the tree renderer.
  (builtins/register-runtime-commands!)
  (let [owner [:app :probe-units]
        got (atom nil)]
    (try
      (scope/register! (app-runtime/node) owner :k/probe "v")
      ((:handler (commands/find-command "units"))
       {:ui {:info (fn [label content] (reset! got [label content]))}} nil)
      (t/is (str/includes? (second @got) "[:app :probe-units] keys=[:k/probe]"))
      (t/is (= "Units" (first @got)))
      (finally
        (scope/dispose-owner! (app-runtime/node) owner)))))

(t/deftest register-builtin-skips-a-taken-name
  (commands/register-command!
   {:name "mine" :description "extension version" :handler (fn [_ _] nil)})
  (commands/register-builtin!  {:name "mine" :description "builtin" :handler (fn [_ _] nil)})
  (commands/register-builtin!  {:name "fresh" :description "builtin" :handler (fn [_ _] nil)})
  (t/is (= "extension version" (:description (commands/find-command "mine"))))
  (t/is (= "builtin" (:description (commands/find-command "fresh"))))
  (t/is (nil? (:frontend (commands/find-command "fresh")))
        "engine-portable builtins carry no front-end tag"))

(t/deftest clear-removes-all
  (commands/register-command!
   {:name "a" :description "a" :handler (fn [_ _] nil)})
  (commands/register-command!
   {:name "b" :description "b" :handler (fn [_ _] nil)})
  (commands/clear-commands!)
  (t/is (empty? (commands/get-commands))))
