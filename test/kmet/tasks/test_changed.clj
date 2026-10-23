(ns kmet.tasks.test-changed
  "Tests for the changed-file/require-graph helper behind `bb *-changed`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.changed :as changed]))

(deftest path->ns-mapping
  (testing "src files map to namespaces (underscores become dashes)"
    (is (= 'kmet.agent.core (changed/path->ns "modules/agent/src/kmet/agent/core.clj")))
    (is (= 'kmet.agent.ui.tui.model-selector (changed/path->ns "modules/agent/src/kmet/agent/ui/tui/model_selector.clj")))
    (is (= 'kmet.ai.api.openai-completions (changed/path->ns "modules/ai/src/kmet/ai/api/openai_completions.clj")))
    (is (= 'kmet.libs.text (changed/path->ns "modules/libs/src/kmet/libs/text.clj"))))
  (testing "test files map to test namespaces"
    (is (= 'kmet.agent.loop.test-turn (changed/path->ns "modules/agent/test/kmet/agent/loop/test_turn.clj")))
    (is (= 'kmet.libs.test-text (changed/path->ns "modules/libs/test/kmet/libs/test_text.clj"))))
  (testing "task files map through their own root (tasks/, not src/)"
    (is (= 'kmet.tasks.build (changed/path->ns "tasks/kmet/tasks/build.cljc")))
    (is (= 'kmet.tasks.generate-models
           (changed/path->ns "tasks/kmet/tasks/generate_models.clj")))
    (is (= 'kmet.tasks.test-lint (changed/path->ns "test/kmet/tasks/test_lint.clj"))))
  (testing "extension files map to their canonical namespaces"
    (is (= 'kmet.extensions.tools (changed/path->ns "extensions/tools.clj")))
    (is (= 'kmet.extensions.clojure.core
           (changed/path->ns "extensions/clojure/src/kmet/extensions/clojure/core.clj")))
    (is (= 'kmet.extensions.clojure.edit-util-test
           (changed/path->ns "extensions/clojure/test/kmet/extensions/clojure/edit_util_test.clj"))))
  (testing "Windows fs/glob separators normalize — the graph keys are /-spelled"
    (is (= 'kmet.agent.ui.tui.model-selector
           (changed/path->ns "modules\\agent\\src\\kmet\\agent\\ui\\tui\\model_selector.clj")))
    (is (= 'kmet.tasks.test-lint
           (changed/path->ns "test\\kmet\\tasks\\test_lint.clj")))))

(deftest ns-requires-extraction
  (testing "vector entries"
    (is (= '#{kmet.agent.loop kmet.ai.models kmet.agent.config}
           (changed/ns-requires
            '(ns kmet.agent.modes.print
               "doc"
               (:require [kmet.agent.loop :as agent]
                         [kmet.ai.models :as models]
                         [kmet.agent.config :as cfg]))))))
  (testing "prefix-list entries expand to full namespaces"
    (is (= '#{kmet.libs.a kmet.libs.b.c kmet.libs.d}
           (changed/ns-requires
            '(ns foo (:require (kmet.libs [a :as x] [b.c :as y] d)))))))
  (testing "non-kmet requires are ignored, non-ns forms yield nil"
    (is (= '#{} (changed/ns-requires '(ns foo (:require [clojure.string :as str])))))
    (is (nil? (changed/ns-requires '(defn foo [] 1))))))

(deftest affected-test-namespaces
  (testing "a source change reaches direct and transitive test dependents across layers"
    (let [nss (set (changed/affected-test-nss-by '[kmet.ai.models]))]
      (is (contains? nss 'kmet.ai.test-models))
      (is (contains? nss 'kmet.ai.test-provider-composer))
      (is (contains? nss 'kmet.agent.test-model-resolver))))
  (testing "changing a test namespace includes itself"
    (let [nss (set (changed/affected-test-nss-by '[kmet.tui.components.test-text]))]
      (is (contains? nss 'kmet.tui.components.test-text))))
  (testing "a -test suffix counts too (kmet.tasks.build-test mirrors its src ns)"
    (let [nss (set (changed/affected-test-nss-by '[kmet.tasks.build]))]
      (is (contains? nss 'kmet.tasks.build-test)))
    (let [nss (set (changed/affected-test-nss-by '[kmet.tasks.build-jolt]))]
      (is (contains? nss 'kmet.tasks.build-jolt-test))))
  (testing "tasks/ is a scanned root, so a change in what task code requires reaches it
            (the closure works across roots, not just src/)"
    (let [nss (set (changed/affected-test-nss-by '[kmet.libs.archive]))]
      (is (contains? nss 'kmet.tasks.build-test))
      (is (contains? nss 'kmet.tasks.build-jolt-test))))
  (testing "a dev-loop task change reaches its own test"
    (is (contains? (set (changed/affected-test-nss-by '[kmet.tasks.clean]))
                   'kmet.tasks.test-clean)))
  (testing "unrelated namespaces stay out"
    (let [nss (set (changed/affected-test-nss-by '[kmet.ai.models]))]
      (is (not (contains? nss 'kmet.tui.components.test-text)))))
  (testing "extension test namespaces now participate in root test selection"
    (let [nss (set (changed/affected-test-nss-by '[kmet.extension extensions.tools]))]
      (is (seq (filter #(str/starts-with? (str %) "kmet.extensions.") nss)))
      (is (contains? nss 'kmet.agent.extensions.test-api)))))

(deftest affected-lint-file-closure
  (testing "a changed file, its dependents, and their transitive dependencies are linted together
            (clj-kondo resolves qualified vars across the files of one invocation only)"
    (let [files (set (changed/affected-lint-files-by ["modules/libs/src/kmet/libs/process.clj"]))]
      (is (contains? files "modules/libs/src/kmet/libs/process.clj")
          "the changed file is linted")
      (is (contains? files "modules/app/src/kmet/agent/extensions/core.cljc")
          "a dependent is linted (its call sites see a changed signature)")
      (is (contains? files "modules/app/src/kmet/agent/extensions/context.cljc")
          "a dependency of the dependent is linted (its vars must resolve)")))
  (testing "unrelated namespaces stay out"
    (let [files (set (changed/affected-lint-files-by ["modules/libs/src/kmet/libs/process.clj"]))]
      (is (not (contains? files "modules/tui/src/kmet/tui/hiccup.cljc"))))))

(deftest project-files-skips-ignored-trees
  (let [files (changed/project-files ["extensions"] ["clj" "cljc" "jolt"])]
    (is (contains? (set files) "extensions/clojure/src/kmet/extensions/clojure/core.clj")
        "tracked source files are listed")
    (is (not-any? #(str/includes? % "/target/") files)
        "gitignored build output (extensions/clojure/target/) is not traversed")))

(deftest fallback-files-prunes-build-dirs
  (let [dir (fs/create-temp-dir {:dir "target" :prefix "fallback-walk-"})
        src (str (fs/file dir "src"))]
    (try
      (fs/create-dirs src)
      (fs/create-dirs (str (fs/file dir "target" "deep")))
      (spit (str (fs/file src "a.clj")) "(ns a)\n")
      (spit (str (fs/file dir "target" "deep" "b.clj")) "(ns b)\n")
      (let [files (@#'changed/fallback-files (str dir))]
        (is (some #(str/ends-with? % "src/a.clj") files)
            "source files below the root are walked")
        (is (not-any? #(str/includes? % "/target/") files)
            "build directories are pruned, not descended into"))
      (finally
        (fs/delete-tree dir)))))
