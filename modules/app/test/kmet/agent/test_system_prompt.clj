(ns kmet.agent.test-system-prompt
  "The system-prompt builder as an engine-owned function: its tool table and
   skills come from its arguments, so two engines build two prompts and nothing
   is read from a process registry.

   `kmet.agent.skills/build-system-prompt` is kmet1's wrapper over this — it
   supplies the process defaults and is covered by
   `kmet.agent.test-skills`; what is pinned here is the seam itself, which is
   what kmet2's `:systemPrompt` row calls."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.agent.system-prompt :as system-prompt]))

(defn- tool
  [name snippet & [guidelines]]
  {:name name :prompt-snippet snippet :prompt-guidelines guidelines})

(defn- prompt
  [& opts]
  (apply system-prompt/build-system-prompt opts))

(t/deftest the-tool-table-comes-from-the-caller
  (let [a (prompt :tools [(tool "read" "Read a file") (tool "grep" "Search files")]
                  :skills [])
        b (prompt :tools [(tool "bash" "Run a command")] :skills [])]
    (t/is (str/includes? a "- read: Read a file"))
    (t/is (str/includes? a "- grep: Search files"))
    (t/is (not (str/includes? a "- bash:")))
    (t/is (str/includes? b "- bash: Run a command"))
    (t/is (not (str/includes? b "- read:")))
    (t/is (str/includes? a "When a turn needs several tool calls")
          "the always-on guidelines are not a tool's")))

(t/deftest no-tool-table-means-no-tools
  ;; nil is the caller's "this engine has none", not "use the process's": the
  ;; builder has no registry to fall back to.
  (let [p (prompt :skills [])]
    (t/is (str/includes? p "Available tools:\n(none)"))
    (t/is (not (str/includes? p "bash")))))

(t/deftest the-skills-block-needs-the-read-tool
  (let [skills [{:name "deploy" :description "Ship it" :location "/tmp/deploy/SKILL.md"}
                {:name "secret" :description "Hidden" :disable-model-invocation true}]
        with-read (prompt :tools [(tool "read" "Read a file")] :skills skills)
        without-read (prompt :tools [(tool "bash" "Run a command")] :skills skills)]
    (t/is (str/includes? with-read "<available_skills>"))
    (t/is (str/includes? with-read "<name>deploy</name>"))
    (t/is (not (str/includes? with-read "secret"))
          "disable-model-invocation skills are never listed")
    (t/is (not (str/includes? without-read "<available_skills>"))
          "skills load through read, so no read tool means no block")))

(t/deftest a-custom-prompt-still-carries-context-and-skills
  (let [p (prompt :custom-prompt "CUSTOM"
                  :append-prompt "APPENDED"
                  :cwd "/work"
                  :context-files [{:path "AGENTS.md" :content "rules"}]
                  :tools [(tool "read" "Read a file")]
                  :skills [{:name "deploy" :description "Ship it"}])]
    (t/is (str/starts-with? p "CUSTOM"))
    (t/is (str/includes? p "APPENDED"))
    (t/is (str/includes? p "<project_instructions path=\"AGENTS.md\">\nrules\n"))
    (t/is (str/includes? p "<name>deploy</name>"))
    (t/is (str/includes? p "Current working directory: /work"))
    (t/is (not (str/includes? p "Available tools:"))
          "a custom prompt replaces the built one, not just its first line")))
