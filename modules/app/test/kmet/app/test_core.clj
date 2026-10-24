(ns kmet.app.test-core
  "kmet.app.core — kmet2's entry: argument parsing, the version/help paths,
   --dump-config over the composition (the same composition a boot would
   mount), and --dump-state over the tree that boot produces, including the
   errors an unreadable or malformed layer raises."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.agent.version :as version]
            [kmet.ai.models :as models]
            [kmet.app.core :as core]
            [kmet.app.test-support :as support]))

(deftest the-process-entry-loads-the-model-catalog
  ;; A run resolves its provider and model against kmet.ai.models' registry —
  ;; process-wide state that starts empty, so a process that never loads it
  ;; answers "Unknown provider" for every provider. Loading is the entry point's
  ;; work, in kmet1's order: the committed catalogs, then models.edn's layer.
  (let [calls (atom [])
        stub (fn [k] (fn [] (swap! calls conj k)))]
    (with-redefs [models/load-catalogs! (stub :catalogs)
                  models/load-models-config! (stub :config)
                  models/get-model-config-error (fn [] nil)]
      (core/load-catalog!)
      (is (= [:catalogs :config] @calls) "built-ins first, the user layer over them")))

  ;; models.edn is the user's file: a broken one keeps the built-ins and says
  ;; what is wrong on stderr, rather than failing the process
  (let [err (java.io.StringWriter.)]
    (with-redefs [models/load-catalogs! (fn [])
                  models/load-models-config! (fn [])
                  models/get-model-config-error (fn [] "bad models.edn")]
      (binding [*err* err]
        (core/load-catalog!)))
    (is (str/includes? (str err) "Warning: models.edn: bad models.edn"))))

(deftest a-process-start-loads-the-catalog-before-it-runs
  ;; The order is the point: a run may name `--model commandcode/…` in its
  ;; first argument, and the catalog has to be there when it does.
  (let [calls (atom [])]
    (with-redefs [core/load-catalog! #(swap! calls conj :catalog)
                  core/main (fn [args] (swap! calls conj args) 7)]
      (is (= 7 (core/run-process ["-p" "hi"])) "the exit code is main's")
      (is (= [:catalog ["-p" "hi"]] @calls) "the catalog loads first"))))

(def sample-plugin
  "A var-addressable plugin map — the shape a `defplugin` var holds."
  {:name :app/sample :inject {:tools nil} :provide [] :apply (fn [_ _] nil)})

(def failing-plugin
  "A plugin whose apply throws: a row that must not pass for a mount."
  {:name :app/failing :apply (fn [_ _] (throw (ex-info "boom" {})))})

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

(deftest parse-args-takes-kmet1s-one-shot-spellings
  (is (= :print (:profile (core/parse-args ["-p" "hi"])))
      "-p is print mode, as in kmet1")
  (is (= :print (:profile (core/parse-args ["--print" "hi"]))))
  (is (= :text (:format (core/parse-args ["-p" "hi"])))
      "and text is what print mode prints by default")
  (is (= {:profile :print :format :text :args ["hi"] :patch []}
         (core/parse-args ["-p" "hi"]))
      "and nothing else is set")
  (is (= :edn (:format (core/parse-args ["--mode" "edn" "hi"])))
      "--mode names print mode's output format")
  (is (= :rpc (:profile (core/parse-args ["--mode" "rpc"])))
      "--mode with any other value names a profile")
  (is (= "deepseek-v4-flash" (:model (core/parse-args ["-p" "--model"
                                                       "deepseek-v4-flash" "hi"]))))
  (is (= :deepseek (:provider (core/parse-args ["-p" "--provider" "deepseek" "hi"])))
      "a provider is a keyword, as settings spell it"))

(deftest parse-args-refuses-what-the-modes-cannot-do-yet
  ;; kmet1 reads a file into the message at parse time. Expansion is a mode's
  ;; (skills, prompt templates, @files) and has not moved, so the arg is refused
  ;; by name — sending "@tasks.md" as the prompt would answer a question nobody
  ;; asked.
  (is (= :kmet.app/unsupported-op
         (error-type #(core/parse-args ["-p" "@tasks.md"]))))
  (let [{:keys [code err]} (support/run-main ["-p" "@tasks.md"])]
    (is (= 1 code))
    (is (str/includes? err "@tasks.md reads the message from a file")
        "the refusal names the argument")
    (is (str/includes? err "--app old -p @tasks.md")
        "and where the behaviour still lives")))

(deftest parse-args-rejects-bad-flags
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--nope"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--profile"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--version=1"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--print=1"])))
      "a one-shot flag takes no value either")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--profile="])))
      "an empty value is no value")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["-c"])))
      "kmet1's flags are not kmet2's, but for the one-shot spellings")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["-p" "--profile" "print"])))
      "choosing print mode twice, two ways, is a usage error")
  (is (= :kmet.app/bad-args
         (error-type #(core/parse-args ["--dump-config" "--dump-state"])))
      "the two dumps are different views; asking for both is a usage error"))

(deftest version-prints-the-shared-string
  (with-redefs [version/kmet-version (constantly "v9.9.9")]
    (let [{:keys [code out]} (support/run-main ["--version"])]
      (is (zero? code))
      (is (= "kmet v9.9.9\n" out)))))

(deftest help-prints-kmet2-flags
  (let [{:keys [code out]} (support/run-main ["--help"])]
    (is (zero? code))
    (is (str/includes? out "kmet --app new"))
    (is (str/includes? out "--dump-config"))
    (is (str/includes? out "--dump-state"))
    (is (str/includes? out "--profile"))))

(deftest dump-config-prints-the-built-in-composition
  (let [{:keys [code out]} (support/run-main ["--dump-config"])]
    (is (zero? code))
    (is (str/starts-with? out "profile :tui rows=13 patches=0")
        (str "the bundle: the extension host, the bus, the four registries, the"
             " prompt builder, the session log and its storage, the projections,"
             " the agent registry, the loop driver and the approval seam"))
    (is (str/includes? out ":events"))
    (is (str/includes? out ":sessions"))
    (is (str/includes? out "inject={:sessionPersistence nil}")
        "a vector :inject prints in the kernel's normalized form")
    (is (str/ends-with? out "\n")))
  (let [{:keys [out]} (support/run-main ["--dump-config" "--profile" "rpc"])]
    (is (str/starts-with? out "profile :rpc")
        "--profile selects the composition")))

(deftest dump-config-composes-a-patch-layer
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/sample-plugin"
                   " :config {:a 1}}]\n"
                   " :patch [{:id :app/sample :config {:b {:c 2}}}]}\n"))]
    (try
      (let [{:keys [code out]} (support/run-main ["--dump-config" "--patch" path])]
        (is (zero? code))
        (is (str/starts-with? out "profile :tui rows=14 patches=1"))
        (is (str/includes? out ":app/sample"))
        (is (str/includes? out "inject={:tools nil}"))
        (is (str/includes? out "config={:a 1, :b {:c 2}}")
            "the row's config and the patch's deep-merge"))
      (finally (io/delete-file path true)))))

(deftest dump-config-reports-a-bad-layer
  (let [path (scratch-patch! "{:rows [] :nope 1}\n")]
    (try
      (let [{:keys [code err]} (support/run-main ["--dump-config" "--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "a patch file holds")))
      (finally (io/delete-file path true))))
  (let [{:keys [code err]} (support/run-main ["--dump-config" "--profile" "nope"])]
    (is (= 1 code))
    (is (str/includes? err "unknown profile")))
  (let [{:keys [code err]} (support/run-main ["--dump-config" "--patch"
                                              "target/definitely-missing-patch.edn"])]
    (is (= 1 code))
    (is (str/includes? err "cannot read patch file"))))

(deftest dump-state-prints-the-booted-tree
  (let [{:keys [code out err]} (support/run-main ["--dump-state"])]
    (is (zero? code))
    (is (str/starts-with? out "[:app]")
        "the root frame, at the origin of every unit path")
    (is (str/includes? out "children=13") "the thirteen composition rows")
    (is (str/includes? out ":active"))
    (is (str/includes? out "provides=[commands]"))
    (is (str/ends-with? out "\n"))
    (is (= "" err) "a dump boots and disposes; it reports nothing else")))

(deftest dump-state-composes-a-patch-layer
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/sample-plugin}]"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code out]} (support/run-main ["--dump-state" "--patch" path])]
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
      (let [{:keys [code out]} (support/run-main ["--dump-state" "--patch" path])]
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
      (let [{:keys [code err]} (support/run-main ["--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "kmet2 boot failed")))
      (finally (io/delete-file path true)))))

(deftest a-bare-run-boots-the-composition-and-says-what-is-missing
  (let [{:keys [code err]} (support/run-main ["hello"])]
    (is (= 1 code))
    (is (str/includes? err
                       (str "booted 13 plugin rows (13 fibers, services: agents"
                            " approvals commands events projections"
                            " prompts sessionPersistence sessions skills"
                            " system-prompt tools)"))
        "the boot is the point: a bare run proves the profile's rows mount")
    (is (str/includes? err "no runnable mode"))
    (is (str/includes? err "--dump-config"))
    (is (str/includes? err "--dump-state"))))

(deftest a-boot-failure-is-reported-as-a-configuration-error
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/failing-plugin}]\n"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code err]} (support/run-main ["--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "kmet2 boot failed")))
      (finally (io/delete-file path true)))))
