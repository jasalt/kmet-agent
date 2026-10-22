(ns kmet.app.test-profile
  "kmet.app.profile — the composition model: bundle stacking, patch by row id,
   config deep-merge, insert/remove, the errors a bad layer must raise, and the
   --dump-config rendering."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.profile :as profile]))

;; Rows arrive described (kmet.app.catalog/describe fills :id/:name from the
;; source); these fixtures are already cards.
(def read-row
  {:id :tool/read :plugin 'kmet.app.test/read :name :tool/read
   :inject {} :provide [] :config {}})

(def write-row
  {:id :tool/write :plugin 'kmet.app.test/write :name :tool/write
   :inject {} :provide [] :config {}})

(def bash-row
  {:id :tool/bash :plugin 'kmet.app.test/bash :name :tool/bash
   :inject {:tools nil} :provide [:tool/bash]
   :config {:shell {:args ["-c"]}}})

(defn- error-type
  "The :type of the exception F throws, or nil."
  [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(defn- ids [composed]
  (mapv :id (:rows composed)))

(deftest layers-apply-in-order
  (let [table {:bundles {:bundle/a {:rows [read-row]}
                         :bundle/b {:rows [write-row]}}
               :profiles {:p {:bundles [[:bundle/a {}] [:bundle/b {}]]
                              :rows [bash-row]
                              :patch []}}}
        layers (profile/layers table :p)]
    (is (= 3 (count layers)) "one layer per bundle, then the profile's own")
    (is (= [:tool/read :tool/write :tool/bash]
           (ids (profile/compose layers)))
        "bundles in stack order, profile rows last")))

(deftest patch-merges-config-by-row-id
  (let [composed (profile/compose
                  [{:rows [bash-row]}
                   {:patch [{:id :tool/bash
                             :config {:shell {:env {"A" "1"}} :timeout 100}}]}])]
    (is (= {:shell {:args ["-c"] :env {"A" "1"}} :timeout 100}
           (:config (first (:rows composed))))
        "config values deep-merge; the row is otherwise untouched")
    (is (= 1 (:patches composed)))))

(deftest patch-removes-a-row
  (is (= [:tool/read]
         (ids (profile/compose [{:rows [read-row write-row]}
                                {:patch [{:id :tool/write :remove true}]}])))))

(deftest patch-inserts-by-anchor
  (testing "an unknown id with a :plugin inserts"
    (is (= [:tool/read :tool/ls :tool/write]
           (ids (profile/compose [{:rows [read-row write-row]}
                                  {:patch [{:id :tool/ls :plugin 'kmet.app.test/ls
                                            :after :tool/read}]}]))))
    (is (= [:tool/ls :tool/read]
           (ids (profile/compose [{:rows [read-row]}
                                  {:patch [{:id :tool/ls :plugin 'kmet.app.test/ls
                                            :before :tool/read}]}]))))
    (is (= [:tool/read :tool/ls]
           (ids (profile/compose [{:rows [read-row]}
                                  {:patch [{:id :tool/ls :plugin 'kmet.app.test/ls}]}]))))))

(deftest patch-replaces-a-source
  (let [row (first (:rows (profile/compose
                           [{:rows [read-row]}
                            {:patch [{:id :tool/read :plugin 'kmet.app.test/read2
                                      :name :tool/read2}]}])))]
    (is (= 'kmet.app.test/read2 (:plugin row)))
    (is (= :tool/read2 (:name row)))))

(deftest bad-patches-throw
  (testing "an unknown row targeted by a config-only patch"
    (is (= :kmet.app/unknown-row
           (error-type #(profile/compose
                         [{:patch [{:id :tool/nope :config {}}]}])))))
  (testing "an unknown insert anchor"
    (is (= :kmet.app/unknown-row
           (error-type #(profile/compose
                         [{:patch [{:id :tool/ls :plugin 'x/y :after :tool/nope}]}])))))
  (testing "a no-op patch"
    (is (= :kmet.app/bad-patch
           (error-type #(profile/compose [{:patch [{:id :tool/read}]}])))))
  (testing "an unknown patch key"
    (is (= :kmet.app/bad-patch
           (error-type #(profile/compose
                         [{:patch [{:id :tool/read :config {} :nope 1}]}])))))
  (testing "removing a row and changing its config at once"
    (is (= :kmet.app/bad-patch
           (error-type #(profile/compose
                         [{:patch [{:id :tool/read :remove true :config {}}]}])))))
  (testing "after and before together"
    (is (= :kmet.app/bad-patch
           (error-type #(profile/compose
                         [{:patch [{:id :tool/ls :plugin 'x/y
                                    :after :tool/read :before :tool/write}]}])))))
  (testing "an anchor on a patch that replaces an existing row"
    (is (= :kmet.app/bad-patch
           (error-type #(profile/compose
                         [{:rows [read-row]}
                          {:patch [{:id :tool/read :config {:a 1}
                                    :after :tool/write}]}])))))
  (testing "removing a row and replacing its source at once"
    (is (= :kmet.app/bad-patch
           (error-type #(profile/compose
                         [{:patch [{:id :tool/read :remove true
                                    :plugin 'x/y}]}]))))))

(deftest composition-errors-throw
  (testing "duplicate ids across stacked bundles"
    (is (= :kmet.app/duplicate-row
           (error-type #(profile/compose [{:rows [read-row]}
                                          {:rows [read-row]}])))))
  (testing "a row without an id"
    (is (= :kmet.app/row-without-id
           (error-type #(profile/compose [{:rows [{:plugin 'x/y}]}]))))))

(deftest layers-name-errors
  (is (= :kmet.app/unknown-profile
         (error-type #(profile/layers {:profiles {}} :nope))))
  (is (= :kmet.app/unknown-bundle
         (error-type #(profile/layers {:bundles {}
                                       :profiles {:p {:bundles [[:nope {}]]}}}
                                      :p))))
  (is (= :kmet.app/bad-bundle-entry
         (error-type #(profile/layers {:bundles {:bundle/a {:rows []}}
                                       :profiles {:p {:bundles [:bundle/a]}}}
                                      :p)))
      "a bundle entry is [:bundle/name layer?], not a bare keyword"))

(deftest a-bundle-stack-can-patch-its-bundle
  (testing "the [:bundle/x extra] second element is a layer applied right after"
    (is (= {:shell {:args ["-c"]} :timeout 5}
           (:config (first (:rows
                            (profile/compose
                             (profile/layers
                              {:bundles {:bundle/a {:rows [bash-row]}}
                               :profiles {:p {:bundles [[:bundle/a
                                                         {:patch [{:id :tool/bash
                                                                   :config {:timeout 5}}]}]]}}}
                              :p)))))))))

(deftest builtin-table-is-complete
  (let [table (profile/builtin)]
    (is (= #{:tui :print :rpc :server} (set (keys (:profiles table)))))
    (is (contains? (:bundles table) :bundle/minimal))
    (is (= [{:id :events :plugin 'kmet.app.events/plugin}]
           (:rows (profile/compose (profile/layers table :rpc))))
        "the bundle carries the rows the landed plugins add, for every profile")))

(deftest render-prints-the-composition
  (let [text (profile/render :tui
                             {:rows [bash-row
                                     {:id :ext/grep-tool :plugin :ext/grep-tool
                                      :name :ext/grep-tool :external true}]
                              :patches 2})]
    (is (str/starts-with? text "profile :tui rows=2 patches=2\n"))
    (is (str/includes? text ":tool/bash"))
    (is (str/includes? text "kmet.app.test/bash"))
    (is (str/includes? text "inject={:tools nil} provide=[:tool/bash] config={:shell {:args [\"-c\"]}}"))
    (is (str/includes? text "external") "an :ext/… row names its source and stops")
    (is (not (str/includes? text "name=:tool/bash")) "the id is the name")
    (is (str/ends-with? text "\n"))))
