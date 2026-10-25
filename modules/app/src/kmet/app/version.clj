(ns kmet.app.version
  "kmet's checkout version — jolt's rule (jolt tools/version.sh), so the base
   version kmet stamps on its artifact zips (`kmet-<ver>-<platform>.zip` from
   babashka, `kmetj-<ver>-<platform>-<link>.zip` from jolt,
   `kmetc-<ver>-jvm.zip` from the JVM packager) and reports from
   `kmet --version` reads like the compiler's:

     v0.8.0                on a release tag
     v0.8.0-56-g63374117   56 commits past it, at that sha (-dirty with edits)
     dev-g63374117         no release tag reachable (a shallow clone)
     dev                   not a git checkout

   Release tags only (`v` + digit): a rolling `vnightly` tag is the NEAREST
   tag from main, so a bare `git describe --tags` would answer it. The no-tag
   case is prefixed rather than left as the bare sha — a sha like 0a1b2c3
   would read as version 0 to anything parsing leading digits, while
   dev-g0a1b2c3 reads as a version with no number, which no floor applies to.

   One definition on purpose (jolt's version-smoke.sh enforces the same for
   its side): the packagers, `kmet --version`, and any future consumer call
   here — none re-derives the rule inline.

   The three assembly functions (`baked-version`, `source-run?`,
   `kmet-version`) live here as well: both apps print the same version string
   and the file sits in the `app` module, below both entries."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- git-out
  "Output of `git ARGS` run in DIR (nil = the process cwd), trimmed; nil when
   git is missing or the command fails."
  [dir & args]
  (try
    (let [{:keys [out exit]} (apply p/shell (cond-> {:out :string :err :suppress}
                                              dir (assoc :dir (str dir)))
                                    "git" args)]
      (when (zero? exit) (not-empty (str/trim out))))
    (catch Exception _ nil)))

(defn- normalize-describe
  "What a `git describe --always --dirty` result means as a version: a
   `v<digit>` release tag stands as-is, anything else (a bare sha — no release
   tag was reachable) is prefixed `dev-g`, and no result at all is `dev`."
  [described]
  (cond
    (nil? described) "dev"
    (re-find #"^v[0-9]" described) described
    :else (str "dev-g" described)))

(defn checkout-version
  "The version of the git checkout at DIR (nil = the process's working
   directory), by jolt's rule — see the namespace docstring for the shapes."
  ([] (checkout-version nil))
  ([dir]
   (normalize-describe (git-out dir "describe" "--tags" "--always" "--dirty"
                                "--match" "v[0-9]*"))))

(defn artifact-version-of
  "The artifact form of DESCRIBED, a checkout-version result: the leading `v`
   stripped. Kept as the one place the rule lives, so a caller already holding
   a describe result — the JVM packager bakes one into the jar and names the
   release zip from it — never describes the checkout twice."
  [described]
  (str/replace described #"^v" ""))

(defn artifact-version
  "checkout-version with the leading `v` stripped — the base version artifact
   names carry."
  ([] (artifact-version nil))
  ([dir] (artifact-version-of (checkout-version dir))))

;; ── what this kmet is ─────────────────────────────────────────────────────

(defn baked-version
  "The version a packager baked in (kmet/version.txt in the uberjar/embedded
   store), or nil — a source checkout has no such resource."
  []
  (some-> (io/resource "kmet/version.txt") slurp str/trim not-empty))

(def ^:private source-marker
  "Classpath key of a kmet namespace source — the file source-run? stats. It
   exists in every mode (sources ship in the jars and embedded stores), so
   only the URL scheme tells a checkout from a packaged image. It must name a
   real namespace: a stale path answers nil and pins `kmet --version` to `dev`
   in every source run. kmet.app.core is the process bootstrap, so every
   artifact carries it."
  "kmet/app/core.clj")

(defn source-run?
  "True when kmet's own source files resolve off disk — a `bb start` /
   `jolt start` / `bb start-jvm` run from the checkout, as opposed to a
   packaged jar or embedded image."
  []
  (some-> (io/resource source-marker) str (str/starts-with? "file:")))

(defn kmet-version
  "The version this kmet is: the baked kmet/version.txt when one is packaged
   (bb dist writes it into the uberjar, jolt dist embeds it), else the
   checkout's describe — a source run answers the same rule from git. A
   packaged binary missing its bake (a bare `jolt build` without the packager)
   says `dev` rather than describe whatever repository the process happens to
   sit in. Both entries print this string."
  []
  (or (baked-version)
      (when (source-run?) (checkout-version))
      "dev"))
