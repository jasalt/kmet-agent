(ns kmet.app.test-system-prompt
  "The `:systemPrompt` row: a builder over the `:skills` registry, provided as a
   value, filling the one input a service owns."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.agent.registry :as registry]
            [kmet.app.registries :as registries]
            [kmet.app.system-prompt :as system-prompt]
            [kmet.srce.core :as srce]))

(defn- mounted
  "The root ctx with the row over the skills registry."
  []
  (let [ctx (srce/root {:name :app})]
    (srce/plugin! ctx registries/skills)
    (srce/plugin! ctx system-prompt/plugin)
    ctx))

(def ^:private read-tool
  "The prompt carries skills only when a read tool is in the table: a skill is
   loaded on demand through it (pi: hasRead)."
  {:name "read"})

(defn- register-skill!
  ([ctx name] (register-skill! ctx name nil))
  ([ctx name description]
   (registry/register! (srce/get ctx :skills) :kmet/skill
                       name
                       {:name name
                        :description (or description (str "The " name " skill."))
                        :location (str "skills/" name "/SKILL.md")}
                       :test)))

(deftest the-row-mounts-over-the-skills-registry
  (let [ctx (srce/root {:name :app})]
    (srce/plugin! ctx registries/skills)
    (let [row (srce/plugin! ctx system-prompt/plugin)]
      (is (= :active (:state @row)))
      (is (= #{:system-prompt} (:provides @row)))
      (is (fn? (srce/get ctx :system-prompt)) "the service value is the builder")
      (is (string? ((srce/get ctx :system-prompt) :cwd "/work" :tools [read-tool]))
          "and it answers the builder's keyword options")
      (srce/dispose! row)
      (is (nil? (srce/get ctx :system-prompt)) "the entry goes with the fiber"))))

(deftest the-builder-fills-skills-from-the-registry
  (let [ctx (mounted)
        builder (srce/get ctx :system-prompt)]
    (register-skill! ctx "clojure-edit" "Structure-aware Clojure edits.")
    (testing "a read tool in the table means the skills section"
      (let [prompt (builder :cwd "/work" :tools [read-tool])]
        (is (str/includes? prompt "<available_skills>"))
        (is (str/includes? prompt "<name>clojure-edit</name>"))
        (is (str/includes? prompt "Structure-aware Clojure edits."))))
    (testing "and no read tool means none"
      (is (not (str/includes? (builder :cwd "/work" :tools []) "<available_skills>"))))
    (testing "the list is read per call, not captured at mount"
      (registry/deregister! (srce/get ctx :skills) :kmet/skill "clojure-edit" :test)
      (is (not (str/includes? (builder :cwd "/work" :tools [read-tool])
                              "clojure-edit"))))))

(deftest a-caller-that-names-skills-keeps-them
  (let [ctx (mounted)
        builder (srce/get ctx :system-prompt)]
    (register-skill! ctx "from-the-registry")
    (testing "an explicit empty list is no skills, not the defaults"
      (is (not (str/includes? (builder :cwd "/work" :tools [read-tool] :skills [])
                              "from-the-registry"))))
    (testing "an explicit list replaces the registry's"
      (let [prompt (builder :cwd "/work"
                            :tools [read-tool]
                            :skills [{:name "mine" :description "Mine only."
                                      :location "skills/mine/SKILL.md"}])]
        (is (str/includes? prompt "<name>mine</name>"))
        (is (not (str/includes? prompt "from-the-registry")))))))
