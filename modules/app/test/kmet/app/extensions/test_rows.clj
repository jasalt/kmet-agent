(ns kmet.app.extensions.test-rows
  "kmet.app.extensions.rows — an `:ext/<id>` row resolves an installed
   extension by name before boot, loads it on mount with the row's config, and
   unloads it with the row."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.core :as core]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.extensions.rows :as rows]
            [kmet.app.packages :as packages]
            [kmet.app.runtime :as runtime]))

(def ^:private dir "target/test-extension-rows")

(defn- write-extension!
  "Write single-file extension FILE under DIR and return its path."
  [file content]
  (let [path (str dir "/" file)]
    (fs/create-dirs (fs/parent path))
    (spit path content)
    path))

(defn- item
  [path]
  {:path path :enabled true :metadata {}})

(defn- with-installed
  "Run F with the installed extensions replaced by PATHS, so resolution never
   reads the developer's real extensions."
  [paths f]
  (with-redefs [packages/resolve-configured-packages
                (constantly {:extensions (mapv item paths)})]
    (f)))

(defn- probe!
  []
  (write-extension! "probe_ext.clj"
                    (str "(ns probe-ext)\n"
                         "(def plugin {:name :ext/probe-row\n"
                         "             :apply (fn [_ api] nil)})\n")))

(defn- composed
  "The TUI composition plus ROW appended as a layer."
  [row]
  (let [path (str dir "/layer.edn")]
    (spit path (pr-str {:rows [row] :patch []}))
    (core/compose-config {:profile :tui :patch [path]})))

(deftest resolves-by-manifest-name-and-file-stem
  (let [manifest "test/fixtures/ext-dir"
        file "test/fixtures/ext-single/hello_ext.clj"]
    (with-installed [manifest file]
      (fn []
        (is (= "multi-ext" (:id (rows/entry :ext/multi-ext)))
            "a manifest extension answers to its :name")
        (is (= "hello_ext" (:id (rows/entry :ext/hello_ext)))
            "a single file answers to its basename without .clj")
        (is (nil? (rows/entry :ext/not-installed)))
        (is (nil? (rows/entry :tool/read)) "only :ext/… sources are looked up")))))

(deftest a-named-extension-resolves-when-settings-disable-it
  (let [file "test/fixtures/ext-single/hello_ext.clj"]
    (with-redefs [packages/resolve-configured-packages
                  (constantly {:extensions [{:path file :enabled false
                                             :metadata {}}]})]
      (is (= "hello_ext" (:id (rows/entry :ext/hello_ext)))
          "naming it is the composition's choice, not settings'"))))

(deftest first-installed-copy-wins
  (let [first (write-extension! "a/probe_ext.clj" "(ns a)\n")
        second (write-extension! "b/probe_ext.clj" "(ns b)\n")]
    (with-installed [first second]
      (fn []
        (is (= first (:path (:item (rows/entry :ext/probe_ext))))
            "pi precedence: the earlier installed copy is the row's source")))))

(deftest a-row-loads-on-mount-and-unloads-on-dispose
  (extensions/clear-extensions!)
  (let [path (probe!)]
    (with-installed [path]
      (fn []
        (let [booted (runtime/boot (composed {:plugin :ext/probe_ext
                                              :config {:mode :test}}) {})]
          (try
            (is (= ["probe_ext.clj"] (mapv :name (extensions/registered-extensions))))
            (is (= {:mode :test} (:config (extensions/extension-api "probe_ext.clj")))
                "the row's :config arrives as (:config api)")
            (testing "the world loader does not load it a second time"
              (is (empty? (packages/load-extensions! nil))
                  "the registry's path dedupe skips a loaded extension")
              (is (= 1 (count (extensions/registered-extensions)))))
            (finally
              (runtime/shutdown! booted)
              (is (empty? (extensions/registered-extensions))
                  "disposing the row unloads the extension it loaded"))))))))

(deftest a-load-failure-fails-the-row
  (extensions/clear-extensions!)
  (let [path (write-extension! "broken_ext.clj" "(ns broken-ext)\n(defn init [api] nil)\n")]
    (with-installed [path]
      (fn []
        (let [booted (runtime/boot (composed {:plugin :ext/broken_ext})
                                   {:diagnose? true})]
          (try
            (is (= [:failed] (mapv :state (:failed booted))))
            (is (str/includes? (str (:error (first (:failed booted)))) "did not load")
                "the row reports the extension's load failure")
            (is (empty? (extensions/registered-extensions)))
            (finally (runtime/shutdown! booted))))))))

(deftest a-bundled-descriptor-row-loads-through-the-descriptor
  ;; A bundled item carries its descriptor in metadata; the row must load it
  ;; through `load-extension-descriptor!`, not as a plain path.
  (extensions/clear-extensions!)
  (let [path (probe!)]
    (with-redefs [packages/resolve-configured-packages
                  (constantly
                   {:extensions
                    [{:path path :enabled true
                      :metadata {:display-name "probe"
                                 :artifact {:name "probe" :kind :file
                                            :root path :path path
                                            :bundled? true}}}]})]
      (let [booted (runtime/boot (composed {:plugin :ext/probe
                                            :config {:from :descriptor}}) {})]
        (try
          (is (= ["probe_ext.clj"] (mapv :name (extensions/registered-extensions))))
          (is (= {:from :descriptor}
                 (:config (extensions/extension-api "probe_ext.clj")))
              "the descriptor row's :config reaches the api too")
          (finally
            (runtime/shutdown! booted)
            (is (empty? (extensions/registered-extensions)))))))))
