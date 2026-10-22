(ns kmet.agent.extensions.test-inject
  "`extension.edn :inject` — an extension that declares what it waits for
   mounts as a plugin: pending while the ctx has not provided it, active (and
   initialized) once it has, and its unload disposes the fiber."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [testing]]
            [kmet.agent.app-runtime :as app]
            [kmet.agent.extensions.core :as extensions]
            [kmet.srce.core :as srce]))

(defn- mount-fiber
  "The fiber of the extension mount named NAME under the app root, or nil."
  [name]
  (some (fn [ctx] (when (= name (:name ctx)) @(:fiber ctx)))
        (srce/children (app/ctx))))

(def ^:private hook-init
  "(defn init [api]
     (ext/on-input api (fn [{:keys [text]}] {:action :transform :text (str text \"+injected\")})))")

(def ^:private ns-line
  "(ns %s\n  (:require [kmet.extension :as ext]))\n")

(defn- extension-dir!
  "Write DIR/extension.edn and DIR/<entry path>.clj for a directory extension
   with NAME, MANIFEST text and INIT body."
  [dir name manifest init]
  (let [entry (str/replace name "-" "_")
        entry-ns (str entry ".main")]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/" entry))
    (spit (str dir "/extension.edn") manifest)
    (spit (str dir "/" entry "/main.clj") (str (format ns-line entry-ns) init))
    dir))

(t/deftest an-inject-extension-waits-then-activates
  (testing "pending while the dependency is absent, active once it is there"
    (let [name "inject-pending"
          entry (str (str/replace name "-" "_") ".main")
          dir (extension-dir! "target/test-ext-inject-pending" name
                              (str "{:name \"" name "\" :entry " entry
                                   " :loader [:sci] :inject [:kmet.inject-test/never]}\n")
                              hook-init)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (t/is (some #(= name (:name %)) (extensions/get-loaded-extensions))
                "a pending extension is still a loaded extension")
          (let [fiber (mount-fiber (keyword name))]
            (t/is (some? fiber) "the extension is mounted as a plugin fiber")
            (t/is (= :pending (:state fiber)) "waiting for its declared dependency")
            (t/is (contains? (:waiting-for fiber) :kmet.inject-test/never)))
          (let [loaded (some #(when (= name (:name %)) %)
                             (extensions/get-loaded-extensions))]
            (t/is (= :pending (:fiber-state loaded))
                  "the listing says the mount is pending")
            (t/is (= #{:kmet.inject-test/never} (:waiting-for loaded))
                  "and names what it waits for — what the UI shows"))
          (t/is (= "hi" (:text (extensions/apply-input-hooks "hi" :interactive)))
                "init has not run: the extension registered nothing yet"))
        (t/is (nil? (extensions/unload-extension!
                     (some #(when (= name (:name %)) %)
                           (extensions/registered-extensions))))
              "unloading a pending extension disposes its fiber")
        (t/is (not (mount-fiber (keyword name))) "the mount is gone")
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest an-inject-extension-runs-when-its-dependency-exists
  (testing "a satisfied :inject activates the mount and runs init"
    (let [name "inject-active"
          entry (str (str/replace name "-" "_") ".main")
          dir (extension-dir! "target/test-ext-inject-active" name
                              (str "{:name \"" name "\" :entry " entry
                                   " :loader [:sci] :inject [:tools]}\n")
                              hook-init)]
      (t/is (some? (srce/get (app/ctx) :tools))
            ":tools is provided by the app composition")
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (let [fiber (mount-fiber (keyword name))]
            (t/is (some? fiber))
            (t/is (= :active (:state fiber)) "the dependency is satisfied"))
          (t/is (= "hi+injected" (:text (extensions/apply-input-hooks "hi" :interactive)))
                "init ran and its registration is live"))
        (finally
          (extensions/unload-all-extensions!)
          (fs/delete-tree dir))))))

(t/deftest a-mount-that-fails-is-a-load-error
  (testing "a throwing init inside the mount is reported, not swallowed"
    (let [name "inject-broken"
          entry (str (str/replace name "-" "_") ".main")
          dir (extension-dir! "target/test-ext-inject-broken" name
                              (str "{:name \"" name "\" :entry " entry
                                   " :loader [:sci] :inject [:tools]}\n")
                              "(defn init [_api] (throw (ex-info \"boom\" {})))\n")]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (some? (:error result)) "the load fails")
          (t/is (str/includes? (str (:error result)) "boom")
                "with the mount's own error"))
        (t/is (not (some #(= name (:name %)) (extensions/get-loaded-extensions)))
              "a failed mount is not a loaded extension")
        (t/is (not (some #{[(keyword name)]} (srce/children (app/ctx))))
              "and its mount is gone")
        (finally
          (extensions/clear-extensions!)
          (fs/delete-tree dir))))))

(t/deftest an-invalid-inject-declaration-is-refused-before-evaluation
  (testing "extension.edn :inject must be keywords"
    (let [name "inject-bad"
          entry (str (str/replace name "-" "_") ".main")
          dir (extension-dir! "target/test-ext-inject-bad" name
                              (str "{:name \"" name "\" :entry " entry
                                   " :loader [:sci] :inject [\"not-a-keyword\"]}\n")
                              hook-init)]
      (try
        (let [result (extensions/load-extension! dir)]
          (t/is (some? (:error result)) "the load fails")
          (t/is (str/includes? (str (:error result)) ":inject")
                "and names the offending key"))
        (finally
          (fs/delete-tree dir))))))
