(ns kmet.app.test-core
  "kmet.app.core — kmet2's entry: argument parsing, the version/help paths,
   --dump-config over the composition (the same composition a boot would
   mount), and --dump-state over the tree that boot produces, including the
   errors an unreadable or malformed layer raises."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.agent.version :as version]
            [kmet.app.core :as core]))

(def sample-plugin
  "A var-addressable plugin map — the shape a `defplugin` var holds."
  {:name :app/sample :inject {:tools nil} :provide [] :apply (fn [_ _] nil)})

(def failing-plugin
  "A plugin whose apply throws: a row that must not pass for a mount."
  {:name :app/failing :apply (fn [_ _] (throw (ex-info "boom" {})))})

(defn- run-main
  "The `{:code :out :err}` of kmet2's entry on ARGS — `main` returns the exit
   code and prints, so a command is read without forking a process."
  [args]
  (let [code (atom nil)
        err (java.io.StringWriter.)
        out (binding [*err* err]
              (with-out-str (reset! code (core/main args))))]
    {:code @code :out out :err (str err)}))

(def pending-plugin
  "A plugin whose dependency nothing provides: a row that renders as `:pending`."
  {:name :app/pending :inject {:nothing nil} :apply (fn [_ _] nil)})

(defn- scratch-patch!
  "Write CONTENT to a patch file under target/ (which /tmp-less hosts have)
   and return the path."
  [content]
  (let [path (str "target/test-app-patch-" (System/nanoTime) ".edn")]
    (io/make-parents path)
    (spit path content)
    path))

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
  (is (true? (:dump-state? (core/parse-args ["--dump-state"]))))
  (is (true? (:version? (core/parse-args ["--version"]))))
  (is (true? (:help? (core/parse-args ["-h"])))))

(deftest parse-args-rejects-bad-flags
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--nope"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--profile"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--version=1"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--profile="])))
      "an empty value is no value")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["-p" "hi"])))
      "kmet1's flags are not kmet2's")
  (is (= :kmet.app/bad-args
         (error-type #(core/parse-args ["--dump-config" "--dump-state"])))
      "the two dumps are different views; asking for both is a usage error"))

(deftest version-prints-the-shared-string
  (with-redefs [version/kmet-version (constantly "v9.9.9")]
    (let [{:keys [code out]} (run-main ["--version"])]
      (is (zero? code))
      (is (= "kmet v9.9.9\n" out)))))

(deftest help-prints-kmet2-flags
  (let [{:keys [code out]} (run-main ["--help"])]
    (is (zero? code))
    (is (str/includes? out "kmet --app new"))
    (is (str/includes? out "--dump-config"))
    (is (str/includes? out "--dump-state"))
    (is (str/includes? out "--profile"))))

(deftest dump-config-prints-the-built-in-composition
  (let [{:keys [code out]} (run-main ["--dump-config"])]
    (is (zero? code))
    (is (str/starts-with? out "profile :tui rows=12 patches=0")
        (str "the bundle: the bus, the four registries, the prompt builder, the"
             " session log and its storage, the projections, the agent registry"
             " and the approval seam"))
    (is (str/includes? out ":events"))
    (is (str/includes? out ":sessions"))
    (is (str/includes? out "inject={:sessionPersistence nil}")
        "a vector :inject prints in the kernel's normalized form")
    (is (str/ends-with? out "\n")))
  (let [{:keys [out]} (run-main ["--dump-config" "--profile" "rpc"])]
    (is (str/starts-with? out "profile :rpc")
        "--profile selects the composition")))

(deftest dump-config-composes-a-patch-layer
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/sample-plugin"
                   " :config {:a 1}}]\n"
                   " :patch [{:id :app/sample :config {:b {:c 2}}}]}\n"))]
    (try
      (let [{:keys [code out]} (run-main ["--dump-config" "--patch" path])]
        (is (zero? code))
        (is (str/starts-with? out "profile :tui rows=13 patches=1"))
        (is (str/includes? out ":app/sample"))
        (is (str/includes? out "inject={:tools nil}"))
        (is (str/includes? out "config={:a 1, :b {:c 2}}")
            "the row's config and the patch's deep-merge"))
      (finally (io/delete-file path true)))))

(deftest dump-config-reports-a-bad-layer
  (let [path (scratch-patch! "{:rows [] :nope 1}\n")]
    (try
      (let [{:keys [code err]} (run-main ["--dump-config" "--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "a patch file holds")))
      (finally (io/delete-file path true))))
  (let [{:keys [code err]} (run-main ["--dump-config" "--profile" "nope"])]
    (is (= 1 code))
    (is (str/includes? err "unknown profile")))
  (let [{:keys [code err]} (run-main ["--dump-config" "--patch"
                                      "target/definitely-missing-patch.edn"])]
    (is (= 1 code))
    (is (str/includes? err "cannot read patch file"))))

(deftest dump-state-prints-the-booted-tree
  (let [{:keys [code out err]} (run-main ["--dump-state"])]
    (is (zero? code))
    (is (str/starts-with? out "[:app]")
        "the root frame, at the origin of every unit path")
    (is (str/includes? out "children=12") "the twelve composition rows")
    (is (str/includes? out ":active"))
    (is (str/includes? out "provides=[commands]"))
    (is (str/ends-with? out "\n"))
    (is (= "" err) "a dump boots and disposes; it reports nothing else")))

(deftest dump-state-composes-a-patch-layer
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/sample-plugin}]"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code out]} (run-main ["--dump-state" "--patch" path])]
        (is (zero? code))
        (is (str/includes? out "[:app :app/sample]")
            "the patched row mounts, so the tree shows it"))
      (finally (io/delete-file path true)))))

(deftest dump-state-renders-a-row-that-could-not-mount
  ;; The booted view is worth most exactly when a row does not mount, so the
  ;; dump is a diagnostic boot: the failure is a line in the tree, not an exit
  ;; code that hides which row and why.
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/failing-plugin}"
                   "         {:plugin kmet.app.test-core/pending-plugin}]"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code out]} (run-main ["--dump-state" "--patch" path])]
        (is (zero? code))
        (is (str/includes? out "[:app :app/failing]"))
        (is (str/includes? out ":failed"))
        (is (str/includes? out "error=\"boom\""))
        (is (str/includes? out "[:app :app/pending]"))
        (is (str/includes? out ":pending"))
        (is (str/includes? out "waiting-for=[nothing]"))
        (is (str/includes? out "[:app :tools]")
            "the rows that did mount are still in the tree"))
      (finally (io/delete-file path true)))))

(deftest a-bare-run-still-fails-hard-on-a-row-that-could-not-mount
  ;; The diagnostic option belongs to the dump: a boot that is meant to run
  ;; reports the same composition as an error.
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/pending-plugin}]"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code err]} (run-main ["--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "kmet2 boot failed")))
      (finally (io/delete-file path true)))))

(deftest a-bare-run-boots-the-composition-and-says-what-is-missing
  (let [{:keys [code err]} (run-main ["hello"])]
    (is (= 1 code))
    (is (str/includes? err
                       (str "booted 12 plugin rows (12 fibers, services: agents"
                            " approvals commands events projections prompts"
                            " sessionPersistence sessions skills system-prompt"
                            " tools)"))
        "the boot is the point: a bare run proves the profile's rows mount")
    (is (str/includes? err "no runnable mode"))
    (is (str/includes? err "--dump-config"))
    (is (str/includes? err "--dump-state"))))

(deftest a-boot-failure-is-reported-as-a-configuration-error
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/failing-plugin}]\n"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code err]} (run-main ["--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "kmet2 boot failed")))
      (finally (io/delete-file path true)))))
