(ns kmet.tasks.test-plugins
  "kmet.tasks.plugins — the generated plugin catalog: what the scanner reads
   from a `srce/defplugin` declaration, what it deliberately does not read, and
   that the committed catalog is a fresh render."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.plugins :as plugins]))

(def ^:private declaration
  "(ns sample.plugin
  (:require [kmet.srce :as srce]))

(def not-a-plugin 1)

(srce/defplugin read-tool
  {:name :tool/read
   :inject [:tools]
   :provide #{:tool/read}
   :config (fn [cfg] cfg)
   :apply (fn [_ctx _cfg] nil)})

(defn helper [] :ignored)
")

(deftest scans-a-defplugin-declaration
  (let [[entry] (plugins/scan-source declaration 'sample.plugin)]
    (is (= {:var 'sample.plugin/read-tool
            :name :tool/read
            :inject {:tools nil}
            :provide [:tool/read]
            :config? true}
           entry))
    (is (= 1 (count (plugins/scan-source declaration 'sample.plugin)))
        "only the defplugin form is a declaration")))

(deftest inject-and-provide-are-normalized
  (let [[entry] (plugins/scan-source
                 "(ns s)\n(srce/defplugin p {:name :p :inject {:ui :optional} :apply (fn [c x])})"
                 's)]
    (is (= {:ui :optional} (:inject entry)))
    (is (= [] (:provide entry)) "no :provide is an empty claim")
    (is (false? (:config? entry)))))

(deftest host-branches-are-not-catalogued
  (testing "the scan is the portable view: a host-only declaration is skipped,
            and its row still composes through the loaded var"
    (is (empty? (plugins/scan-source
                 "#?(:bb (srce/defplugin bb-only {:name :bb-only :apply (fn [c x])}))"
                 's)))))

(defn- error-type [text]
  (try (plugins/scan-source text 's) nil (catch Exception e (:type (ex-data e)))))

(deftest invalid-declarations-throw
  (is (= :kmet.plugins/bad-declaration
         (error-type "(ns s)\n(srce/defplugin p {:apply (fn [c x])})"))
      "no keyword :name")
  (is (= :kmet.plugins/bad-declaration
         (error-type "(ns s)\n(srce/defplugin p (map :name :p))"))
      "the declaration is not a map literal")
  (is (= :kmet.plugins/bad-declaration
         (error-type "(ns s)\n(srce/defplugin p {:name :p :inject :bogus :apply (fn [c x])})"))
      "an :inject that is neither a vector nor a map"))

(deftest render-is-the-file-format
  (is (= (str plugins/header
              "{:version 1\n :plugins []}\n")
         (plugins/render {:version 1 :plugins []})))
  (let [text (plugins/render {:version 1
                              :plugins [{:var 'a.b/p :name :p
                                         :inject {:tools nil} :provide [:p]
                                         :config? false}]})]
    (is (str/includes? text "  {:var a.b/p :name :p :inject {:tools nil} :provide [:p] :config? false}"))
    (is (str/starts-with? text plugins/header))))

(deftest the-scan-covers-the-module-sources
  (let [files (plugins/module-sources)]
    (is (<= 150 (count files)) "the scan found the module tree")
    (is (every? #(str/starts-with? % "modules/") files))
    (is (every? #(re-find #"\.clj[ac]?$" %) files) "documented: .clj/.cljc only")))

(deftest committed-catalog-is-a-fresh-render
  (let [fresh (plugins/render (plugins/catalog))
        committed (slurp plugins/catalog-path)]
    (is (= fresh committed)
        (str "the committed plugin catalog is stale — run: bb generate-plugins ("
             plugins/catalog-path ")"))))
