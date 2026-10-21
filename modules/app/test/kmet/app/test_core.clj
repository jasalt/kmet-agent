(ns kmet.app.test-core
  "kmet.app.core — kmet2's entry: argument parsing, the version/help paths, and
   --dump-config over the composition (the same composition a boot would
   mount), including the errors an unreadable or malformed layer raises."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.agent.version :as version]
            [kmet.app.core :as core]))

(def sample-plugin
  "A var-addressable plugin map — the shape a `defplugin` var holds."
  {:name :app/sample :inject {:tools nil} :provide [] :apply (fn [_ _] nil)})

(defn- scratch-patch!
  "Write CONTENT to a patch file under target/ (which /tmp-less hosts have)
   and return the path."
  [content]
  (let [path (str "target/test-app-patch-" (System/nanoTime) ".edn")]
    (spit path content)
    path))

(defn- err-str [f]
  (let [w (java.io.StringWriter.)
        result (binding [*err* w] (f))]
    [result (str w)]))

(defn- error-type [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(deftest parse-args-defaults
  (let [opts (core/parse-args [])]
    (is (= :tui (:profile opts)) "the default profile")
    (is (= [] (:patch opts)))
    (is (= [] (:args opts))))
  (is (= ["hello" "world"] (:args (core/parse-args ["hello" "world"])))))

(deftest parse-args-flags
  (is (= :rpc (:profile (core/parse-args ["--profile" "rpc"]))))
  (is (= :print (:profile (core/parse-args ["--profile=print"])))
      "the --flag=value form")
  (is (= ["a.edn" "b.edn"] (:patch (core/parse-args ["--patch" "a.edn"
                                                     "--patch=b.edn"])))
      "patches stack")
  (is (true? (:dump-config? (core/parse-args ["--dump-config"]))))
  (is (true? (:version? (core/parse-args ["--version"]))))
  (is (true? (:help? (core/parse-args ["-h"])))))

(deftest parse-args-rejects-bad-flags
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--nope"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--profile"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--version=1"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["-p" "hi"])))
      "kmet1's flags are not kmet2's"))

(deftest version-prints-the-shared-string
  (with-redefs [version/kmet-version (constantly "v9.9.9")]
    (let [code (atom nil)
          out (with-out-str (reset! code (core/main ["--version"])))]
      (is (zero? @code))
      (is (= "kmet v9.9.9\n" out)))))

(deftest help-prints-kmet2-flags
  (let [out (with-out-str (is (zero? (core/main ["--help"]))))]
    (is (str/includes? out "kmet --app new"))
    (is (str/includes? out "--dump-config"))
    (is (str/includes? out "--profile"))))

(deftest dump-config-prints-the-built-in-composition
  (let [out (with-out-str (is (zero? (core/main ["--dump-config"]))))]
    (is (str/starts-with? out "profile :tui rows=0 patches=0"))
    (is (str/ends-with? out "\n")))
  (is (str/starts-with? (with-out-str (core/main ["--dump-config" "--profile" "rpc"]))
                        "profile :rpc")
      "--profile selects the composition"))

(deftest dump-config-composes-a-patch-layer
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/sample-plugin"
                   " :config {:a 1}}]\n"
                   " :patch [{:id :app/sample :config {:b {:c 2}}}]}\n"))]
    (try
      (let [out (with-out-str (is (zero? (core/main ["--dump-config" "--patch" path]))))]
        (is (str/starts-with? out "profile :tui rows=1 patches=1"))
        (is (str/includes? out ":app/sample"))
        (is (str/includes? out "inject={:tools nil}"))
        (is (str/includes? out "config={:a 1, :b {:c 2}}")
            "the row's config and the patch's deep-merge"))
      (finally (io/delete-file path true)))))

(deftest dump-config-reports-a-bad-layer
  (let [path (scratch-patch! "{:rows [] :nope 1}\n")]
    (try
      (let [[code err] (err-str #(core/main ["--dump-config" "--patch" path]))]
        (is (= 1 code))
        (is (str/includes? err "a patch file holds")))
      (finally (io/delete-file path true))))
  (let [[code err] (err-str #(core/main ["--dump-config" "--profile" "nope"]))]
    (is (= 1 code))
    (is (str/includes? err "unknown profile"))))

(deftest a-bare-run-says-what-is-missing
  (let [[code err] (err-str #(core/main ["hello"]))]
    (is (= 1 code))
    (is (str/includes? err "no runnable mode"))
    (is (str/includes? err "--dump-config"))))
