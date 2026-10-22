(ns kmet.app.test-catalog
  "kmet.app.catalog — row cards read from the plugin map itself, and the
   `:ext/…` form whose card the extension host supplies."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.catalog :as catalog]))

(def not-a-plugin
  "A var a row can name that is not a plugin map."
  {:name "not a keyword"})

(def test-plugin
  "A plugin map a test row can name as a source — the shape a `defplugin` var
   holds (a qualified var symbol is all a row carries)."
  {:name :app/test-plugin
   :inject {:tools nil}
   :provide #{:x}
   :apply (fn [_ctx _cfg] nil)})

(defn- error-type [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(deftest external-sources-are-named-not-resolved
  (let [card (catalog/describe {:plugin :ext/grep-tool :config {}})]
    (is (= :ext/grep-tool (:id card)) "the id defaults to the source")
    (is (= :ext/grep-tool (:name card)))
    (is (:external card))
    (is (= {} (:config card))))
  (testing "an explicit :id wins"
    (is (= :grep (:id (catalog/describe {:plugin :ext/grep-tool :id :grep}))))))

(deftest a-var-source-is-read-from-the-plugin-map
  (let [card (catalog/describe {:plugin 'kmet.app.test-catalog/test-plugin})]
    (is (= :app/test-plugin (:id card)) "the id defaults to the plugin's :name")
    (is (= :app/test-plugin (:name card)))
    (is (= {:tools nil} (:inject card)))
    (is (= #{:x} (:provide card))))
  (testing "an explicit :id wins over the plugin name"
    (is (= :read (:id (catalog/describe {:plugin 'kmet.app.test-catalog/test-plugin
                                         :id :read}))))))

(deftest unknown-sources-throw
  (is (= :kmet.app/unknown-plugin
         (error-type #(catalog/describe {:plugin 'no.such/plugin}))))
  (is (= :kmet.app/unknown-plugin
         (error-type #(catalog/describe {:plugin 'kmet.app.test-catalog/not-a-plugin})))
      "a var that is not a plugin map is not a card")
  (is (= :kmet.app/bad-row
         (error-type #(catalog/describe {:id :row})))
      "a row without a :plugin source is a bad row")
  (is (= :kmet.app/bad-row
         (error-type #(catalog/describe {:plugin :tool/bash})))
      "a keyword source is an :ext/… id, never a registry key"))

(deftest describe-layer-describes-rows-and-insert-patches
  (let [layer (catalog/describe-layer
               {:rows [{:plugin 'kmet.app.test-catalog/test-plugin}]
                :patch [{:id :tool/read :config {:a 1}}
                        {:id :tool/ls :plugin 'kmet.app.test-catalog/test-plugin
                         :after :tool/read}]})
        [config-patch insert-patch] (:patch layer)]
    (is (= :app/test-plugin (-> layer :rows first :id)))
    (is (= {:id :tool/read :config {:a 1}} config-patch)
        "a config-only patch is not a row")
    (is (= :app/test-plugin (:name insert-patch))
        "an insert patch's new row is described")))
