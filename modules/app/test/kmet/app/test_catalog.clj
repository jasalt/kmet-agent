(ns kmet.app.test-catalog
  "kmet.app.catalog — row cards: the committed catalog, the ext/… external
   form, and the var fallback that keeps an out-of-tree profile composable."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.catalog :as catalog]))

(def test-plugin
  "A plugin map a test row can name as a source. Not a `defplugin`: the
   catalog's fallback reads a var's value, and a plain map is the shape a
   plugin var holds after `defplugin`'s validation."
  {:name :app/test-plugin
   :inject {:tools nil}
   :provide #{:x}
   :apply (fn [_ctx _cfg] nil)})

(defn- error-type [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(deftest committed-catalog-shape
  (let [cat (catalog/catalog)]
    (is (= 1 (:version cat)))
    (is (vector? (:plugins cat)))
    (is (= (:plugins cat) (catalog/entries)))
    (doseq [entry (catalog/entries)]
      (is (qualified-symbol? (:var entry)) (str "entry var: " (pr-str (:var entry))))
      (is (keyword? (:name entry)) (str "entry name: " (pr-str (:name entry))))
      (is (map? (:inject entry)) (str "entry inject: " (pr-str (:inject entry))))
      (is (vector? (:provide entry)) (str "entry provide: " (pr-str (:provide entry))))
      (is (boolean? (:config? entry))))
    (is (nil? (catalog/entry-for 'no.such/plugin)))))

(deftest external-sources-are-named-not-resolved
  (let [card (catalog/describe {:plugin :ext/grep-tool :config {}})]
    (is (= :ext/grep-tool (:id card)) "the id defaults to the source")
    (is (= :ext/grep-tool (:name card)))
    (is (:external card))
    (is (= {} (:config card))))
  (testing "an explicit :id wins"
    (is (= :grep (:id (catalog/describe {:plugin :ext/grep-tool :id :grep}))))))

(deftest a-var-source-falls-back-to-the-loaded-var
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
  (is (= :kmet.app/bad-row
         (error-type #(catalog/describe {:id :row})))
      "a row without a :plugin source is a bad row"))

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
