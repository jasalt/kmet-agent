(ns kmet.tasks.build-jvm
  "The JVM packager — `bb uberjar-jvm` → target/uberjar-jvm.jar and
   `bb dist-jvm` → dist/kmet-<version>-jvm.zip, the artifacts `java -jar`
   runs.

   kmet.tasks.build's uberjar is a different artifact, not a sibling of this
   one: it is the babashka payload `bb dist` appends to a babashka release
   binary (Main-Class kmet.agent.core, no AOT), and it is not JVM-loadable at
   all — babashka's built-in namespaces (babashka.fs, sci, http-client, …)
   are compiled into the binary, so no source file of theirs is left to copy
   into a jar.

   This packager runs under the deps.edn `:build` alias, the only place
   tools.build is on the classpath (the bb tasks shell out to
   `clojure -M:build`), and assembles one self-contained jar:

     1. stage the bundled extensions
        (kmet.tasks.build/stage-bundled-extensions!, host-neutral by design),
        so the `extensions/<root>/…` resource keys the runtime probes travel
        in the jar exactly as in the bb/jolt artifacts;
     2. copy the resolved classpath's directory roots — minus the test trees,
        tasks/ and the extension projects' src trees
        (kmet.tasks.modules/artifact-roots) — plus the staged tree;
     3. bake kmet/version.txt: what `kmet --version` reports out of a built
        artifact, since a jar is not a source run and git describe is never
        consulted from inside one;
     4. AOT-compile kmet.agent.jvm-main, assemble the jar around it, and
        smoke-test it (`--version` must answer).

   AOT is what makes the artifact usable: the namespaces are the same either
   way, but the source-only jar takes ~10s to reach its first render against
   ~2s, and starts the JVM with ~600MB instead of ~250MB. `dist!` wraps the
   jar in the release zip by the same conventions the bb/jolt packagers use
   (kmet.tasks.build/artifact-zip, pack-artifact!)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [kmet.agent.version :as version-lib]
            [kmet.tasks.build :as build]
            [kmet.tasks.modules :as modules]))

(def jar-path
  "The build artifact: one self-contained JVM uberjar, platform-independent
   (unlike the bb/jolt executables), run with `java -jar`."
  "target/uberjar-jvm.jar")

(def ^:private class-dir
  "Compiled classes and the copied classpath roots, assembled into the jar.
   Scratch under target/: `bb clean` removes it with the rest of the output."
  "target/jvm-classes")

(def ^:private main-class
  "The AOT'd :gen-class entry — a namespace is not a Java class, so the
   manifest's Main-Class must name the class :gen-class generates (see
   kmet.agent.jvm-main)."
  'kmet.agent.jvm-main)

(def ^:private dist-platform
  "The platform slot of the release zip's name. The jar runs on any OS, so
   there is nothing to cross-build and no platform to detect: the slot says
   `jvm`, where the other packagers put an os-arch pair."
  "jvm")

(def ^:private usage
  "usage: clojure -M:build -m kmet.tasks.build-jvm [--dist]")

(defn- packable-basis
  "BASIS with every lib's :paths trimmed to the roots an artifact ships
   (kmet.tasks.modules/artifact-roots). A module local root declares `src`
   AND `test` in its deps.edn — the test tree is on the classpath for the
   runner's sake — and uber explodes each lib's :paths into the jar, so the
   suite would ride along."
  [basis]
  (update basis :libs
          (fn [libs]
            (into {}
                  (map (fn [[lib {:keys [paths] :as coord}]]
                         [lib (cond-> coord
                                (seq paths)
                                (update :paths modules/artifact-roots))]))
                  libs))))

(defn- smoke!
  "Run the freshly built jar's CLI: `--list-models` must list the embedded
   catalogs and `--version` must report EXPECTED, the version baked into the
   jar. The checks a jar that assembles but cannot start — missing classes, a
   broken manifest, model data left out — fails, the same pair `bb dist
   --smoke` makes."
  [jar expected]
  (let [run (fn [& args]
              (apply p/shell {:out :string :err :string :continue true}
                     "java" "-jar" (str jar) args))]
    (println "smoke test:" "java -jar" (str jar) "--list-models")
    (let [{:keys [out exit err]} (run "--list-models")]
      (if (zero? exit)
        (println "smoke test passed:" (count (str/split-lines (str out)))
                 "models listed")
        (throw (ex-info "the JVM uberjar did not list its model catalogs"
                        {:type ::smoke-failed :exit exit :err (str err)}))))
    (println "smoke test:" "java -jar" (str jar) "--version")
    (let [{:keys [out exit err]} (run "--version")
          reported (str/trim (str out))]
      (if (and (zero? exit) (= reported expected))
        (println "smoke test passed:" reported)
        (throw (ex-info "the JVM uberjar did not report its baked version"
                        {:type ::smoke-failed :exit exit :expected expected
                         :reported reported :err (str err)}))))))

(defn build!
  "Assemble jar-path and smoke-test it. Returns {:jar PATH :version DESCRIBED}
   — the described version is the one baked into the jar, so the release zip's
   name and the baked kmet/version.txt can never disagree."
  []
  (println "building" jar-path)
  (let [ver (version-lib/checkout-version)
        staged (build/stage-bundled-extensions!)
        basis (packable-basis
               (b/create-basis {:project "deps.edn"
                                :aliases [:jvm :clj-pin]}))
        src-dirs (conj (->> (:classpath-roots basis)
                            (filter fs/directory?)
                            modules/artifact-roots)
                       staged)]
    (b/delete {:path class-dir})
    (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
    (b/write-file {:path (str (fs/path class-dir "kmet" "version.txt"))
                   :string (str ver "\n")})
    (b/compile-clj {:basis basis
                    :src-dirs src-dirs
                    :class-dir class-dir
                    :ns-compile [main-class]})
    (b/uber {:class-dir class-dir
             :uber-file jar-path
             :basis basis
             :main main-class
             ;; babashka.ffi calls into java.lang.foreign; the manifest grant
             ;; replaces the --enable-native-access flag for `java -jar`
             ;; (JDKs older than 22 ignore the attribute)
             :manifest {"Enable-Native-Access" "ALL-UNNAMED"}})
    (println "wrote" jar-path)
    (smoke! jar-path (str "kmet " ver))
    {:jar jar-path :version ver}))

(defn- posix-launcher
  "The launcher shipped beside the jar as both `kmet` and `kmet.sh`: it runs
   the packaged uberjar with the host's java, passing every argument through.
   The extensionless copy is the command; the .sh copy is the same script under
   the name `bb dist`'s Termux launchers carry, which stays recognizable when a
   transfer drops the executable bit. KMET_JAVA_OPTS carries JVM options (heap
   size, -D flags) without editing the script."
  []
  "#!/bin/sh
# kmet on the JVM — run the packaged uberjar with the host's java.
DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)
exec java $KMET_JAVA_OPTS -jar \"$DIR/kmet.jar\" \"$@\"
")

(defn- cmd-launcher
  "The same launcher for cmd.exe, shipped under both PATHEXT names (.bat and
   .cmd, one script) with CRLF endings, as cmd expects."
  []
  (str "@echo off\r\n"
       "rem kmet on the JVM - run the packaged uberjar with the host's java.\r\n"
       "java %KMET_JAVA_OPTS% -jar \"%~dp0kmet.jar\" %*\r\n"))

(defn- powershell-launcher
  "The launcher for PowerShell (Windows PowerShell 5 and pwsh). KMET_JAVA_OPTS
   is split into separate arguments, because PowerShell passes an unquoted
   variable as ONE argument — several JVM options would reach java glued
   together. The zip stamps the POSIX executable bit too, so `./kmet.ps1` works
   under pwsh on Unix."
  []
  (str "#!/usr/bin/env pwsh\r\n"
       "# kmet on the JVM - run the packaged uberjar with the host's java.\r\n"
       "$javaOpts = if ($env:KMET_JAVA_OPTS) { $env:KMET_JAVA_OPTS -split '\\s+' } else { @() }\r\n"
       "& java @javaOpts -jar (Join-Path $PSScriptRoot 'kmet.jar') @args\r\n"
       "exit $LASTEXITCODE\r\n"))

(def ^:private launchers
  "The launchers the artifact ships beside kmet.jar, by file name: the
   extensionless `kmet` command and its .sh twin (the name `bb dist`'s Termux
   launchers carry, and still recognizable when a transfer drops the executable
   bit), the cmd.exe pair under both of its PATHEXT names, and PowerShell."
  {"kmet" (posix-launcher)
   "kmet.sh" (posix-launcher)
   "kmet.bat" (cmd-launcher)
   "kmet.cmd" (cmd-launcher)
   "kmet.ps1" (powershell-launcher)})

(defn- launcher-executable?
  "Does LAUNCHER carry the POSIX executable bit in the zip? The shell and pwsh
   scripts do — `./kmet`, `./kmet.sh`, `./kmet.ps1` on Unix — the cmd.exe pair
   does not (Windows ignores the bit either way)."
  [launcher]
  (not (re-find #"\.(?:bat|cmd)$" launcher)))

(defn dist!
  "Build the jar (smoke-tested) and pack the release zip:
   dist/kmet-<version>-jvm.zip holding kmet-<version>-jvm/ with kmet.jar, the
   launchers (kmet, kmet.sh, kmet.bat, kmet.cmd, kmet.ps1) and the LICENSE —
   the layout `bb dist` / `jolt dist` pack, with `jvm` in the platform slot.
   Returns the zip path."
  []
  (let [{:keys [version]} (build!)
        staging (fs/path "target/dist" dist-platform)
        zip (build/artifact-zip :jvm (version-lib/artifact-version-of version)
                                dist-platform nil)]
    (fs/create-dirs staging)
    (doseq [[name script] launchers]
      (let [path (fs/path staging name)]
        (spit (str path) script)
        (when (and (launcher-executable? name) (not (fs/windows?)))
          (fs/set-posix-file-permissions (str path) "rwxr-xr-x"))))
    (build/pack-artifact! zip
                          {:files (into [{:name "kmet.jar" :file jar-path}]
                                        (for [[name _] launchers]
                                          {:name name
                                           :file (str (fs/path staging name))
                                           :executable? (launcher-executable? name)}))})))

(defn -main
  "tools.build entry: no argument builds and smoke-tests the jar, `--dist`
   also packs the release zip. The bb tasks `uberjar-jvm` / `dist-jvm` are the
   only callers."
  [& args]
  (let [args (set args)]
    (cond
      (contains? args "--dist") (dist!)
      (some #(contains? args %) ["-h" "--help"]) (println usage)
      (seq args) (throw (ex-info usage {:type ::usage :args (vec args)}))
      :else (build!))))
