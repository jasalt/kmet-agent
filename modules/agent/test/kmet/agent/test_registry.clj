(ns kmet.agent.test-registry
  "The registry contract, run against both backends: kmet2's atom handle (what
   the `:tools` service installs) and kmet1's scope-store handle (deleted at
   landing 4). One set of assertions, so the rules cannot drift between the
   kernel the app will use and the store it replaces."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.agent.registry :as registry]
            [kmet.agent.registry-store :as registry-store]
            [kmet.libs.scope :as scope]))

(defn- backends
  "A fresh handle per backend, so one case's registrations never leak into the
   next."
  []
  [["atom" #(registry/atom-registry)]
   ["store" #(registry-store/store-registry (scope/node))]])

(def not-here
  ::not-here)

(deftest register-entry-and-dispose
  (doseq [[kind make] (backends)]
    (testing kind
      (let [r (make)
            dispose (registry/register! r :kmet/tool "read" {:name "read"} [:app :tools])]
        (is (= {:name "read"} (registry/entry r :kmet/tool "read")))
        (is (= [["read" {:name "read"}]] (registry/entries r :kmet/tool)))
        (is (nil? (registry/entry r :kmet/tool "probe")))
        (is (true? (dispose)) "the disposer reports the removal")
        (is (nil? (registry/entry r :kmet/tool "read")))
        (is (false? (dispose)) "a stale disposer removes nothing")))))

(deftest one-owner-re-registering-replaces
  (doseq [[kind make] (backends)]
    (testing kind
      (let [r (make)]
        (registry/register! r :kmet/tool "read" {:name "read" :v 1} [:app :tools])
        (registry/register! r :kmet/tool "read" {:name "read" :v 2} [:app :tools])
        (is (= {:name "read" :v 2} (registry/entry r :kmet/tool "read")))
        (is (= 1 (count (registry/entries r :kmet/tool))))))))

(deftest the-owner-that-took-a-name-keeps-it
  (doseq [[kind make] (backends)]
    (testing kind
      (let [r (make)]
        (registry/register! r :kmet/skill "review" {:from :first} [:ext/a])
        (registry/register! r :kmet/skill "review" {:from :second} [:ext/b])
        (is (= {:from :first} (registry/entry r :kmet/skill "review"))
            "lookup reads display order, so the first owner wins")
        (is (= {:from :first} (first (vals (registry/by-owner r :kmet/skill "review"))))
            "by-owner reports each owner's own value")
        (is (= 2 (count (registry/entries r :kmet/skill))))
        (is (= [["review" {:from :first}]]
               (registry/effective-entries r :kmet/skill))
            "listing collapses to one entry per name, and lookup agrees")))))

(deftest deregister-clears-by-tag-across-owners
  (doseq [[kind make] (backends)]
    (testing kind
      (let [r (make)]
        (registry/register! r :kmet/tool "a" 1 [:app :tools])
        (registry/register! r :kmet/tool "a" 2 [:ext/x])
        (registry/register! r :kmet/command "a" :cmd [:app :commands])
        (registry/deregister! r :kmet/tool "a" [:ext/x])
        (is (= [[:app :tools]] (keys (registry/by-owner r :kmet/tool "a")))
            "an owner-scoped removal leaves the other owner's registration")
        (registry/deregister! r :kmet/tool "a" nil)
        (is (empty? (registry/entries r :kmet/tool)))
        (is (= [["a" :cmd]] (registry/entries r :kmet/command))
            "another tag's registrations survive")
        (registry/clear! r :kmet/command)
        (is (empty? (registry/entries r :kmet/command)))))))

(deftest addresses-are-tag-and-name
  (is (= [:kmet/tool "read"] (registry/store-key :kmet/tool "read")))
  (is (registry/key? :kmet/tool [:kmet/tool "read"]))
  (is (not (registry/key? :kmet/tool [:kmet/command "read"])))
  (is (not (registry/key? :kmet/tool "read"))))
