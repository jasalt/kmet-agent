(ns kmet.agent.test-commands
  "Tests for kmet.agent.commands — slash command registry, and the runtime
   builtins' registry-facing behavior."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.agent.commands :as commands]
            [kmet.agent.commands.builtins :as builtins]
            [kmet.agent.runtime :as app-runtime]
            [kmet.cells.core :as cells]
            [kmet.cells.scope :as scope]))

(t/use-fixtures :each
  (fn [f]
    (commands/clear-commands!)
    (f)
    (commands/clear-commands!)))

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
  (commands/unregister-command! "gone")
  (t/is (nil? (commands/find-command "gone"))))

(t/deftest the-registry-lives-in-the-app-roots-store
  ;; The registry is the store, not an engine field: a registration is
  ;; readable through the store under the app's owner, and clear-commands!
  ;; removes it by key (a unit owns several registries — cells.md §5.1).
  (commands/register-command!
   {:name "stored" :description "x" :handler (fn [_ _] nil)})
  (t/is (identical? (:handler (commands/find-command "stored"))
                    (:handler (get (scope/for-key (app-runtime/node)
                                                  [:kmet/command "stored"])
                                   commands/commands-owner))))
  (t/is (= 1 (count (commands/get-commands))) "in registration order")
  (commands/clear-commands!)
  (t/is (empty? (commands/get-commands)))
  (t/is (empty? (scope/for-key (app-runtime/node) [:kmet/command "stored"]))))

(t/deftest handler-receives-ctx-and-args
  (let [received (atom nil)]
    (commands/register-command!
     {:name "echo"
      :description "echo args"
      :handler (fn [ctx args] (reset! received [ctx args]))})
    (let [ctx {:runtime :engine :ui :ui :frontend :interactive}]
      ((:handler (commands/find-command "echo")) ctx "hello"))
    (t/is (= [{:runtime :engine :ui :ui :frontend :interactive} "hello"] @received))))

(t/deftest units-lists-the-mounted-tree
  ;; The /units surface renders kmet.cells' snapshot of the app root, which
  ;; carries the builtin units (kmet.agent.runtime).
  (builtins/register-runtime-commands!)
  (let [got (atom nil)]
    ((:handler (commands/find-command "units"))
     {:ui {:info (fn [label content] (reset! got [label content]))}} nil)
    (t/is (= "Units" (first @got)))
    (t/is (str/includes? (second @got) ":loop-guard"))))

(t/deftest units-follows-the-session-engine-node
  ;; A session that runs in its own tree shows that tree, not the app root
  ;; (cells.md §5.6).
  (builtins/register-runtime-commands!)
  (let [mine (cells/mount! (app-runtime/runtime)
                           [{:id :probe :name :kmet/loop-guard}])
        got (atom nil)]
    ((:handler (commands/find-command "units"))
     {:runtime {:agent-state (atom {:node mine})}
      :ui {:info (fn [label content] (reset! got [label content]))}} nil)
    (t/is (str/includes? (second @got) ":probe"))))

(t/deftest register-builtin-skips-a-taken-name
  (commands/register-command!
   {:name "mine" :description "extension version" :handler (fn [_ _] nil)})
  (commands/register-builtin! {:name "mine" :description "builtin" :handler (fn [_ _] nil)})
  (commands/register-builtin! {:name "fresh" :description "builtin" :handler (fn [_ _] nil)})
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
