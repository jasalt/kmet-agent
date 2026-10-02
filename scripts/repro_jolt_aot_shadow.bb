;; Repro: Jolt's AOT recovery recompiles a namespace after the old artifact
;; has already interned its vars, so a def that shadows clojure.core gets
;; picked up by the analyzer for earlier forms too — and the poisoned
;; artifact is written back to the cache, breaking every later process.
;;
;; loader.ss aot-safe-load-or-recompile's recover! path (corrupt/incomplete
;; artifact, or a stale assumption) deletes the bad .so/.scm and calls
;; aot-compile-and-cache — in the SAME process, after the artifact's defs
;; ran. The analyzer's resolve-global then resolves an unqualified (get ...)
;; to the namespace's own later defn get instead of clojure.core/get, and
;; the new artifact bakes that in.
;;
;; Fixture: shadow-ns/f calls (get {:a 1} :a); a later defn get shadows
;; core get. A clean compile (and bb) gives (f) => 1. After a partial load +
;; recover, (f) => [:shadow {:a 1} :a] and the cache stays poisoned.
;;
;; Reproduces through v0.8.15 (verified v0.8.15-77-ga1f6b669). Fixed on jolt
;; main in 0bbc15a0 (PR #1220); on v0.8.15-101-g748ddc29 the clean compile
;; is correct and no truncation tail reproduces, so this exits 1 ("not
;; reproduced"). Kept as the acceptance check for removing kmet's
;; clojure.core/get workaround once a tagged release carries the fix
;; (jolt-bugs.md). Run: bb scripts/repro_jolt_aot_shadow.bb

(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.string :as str])

(def work "target/jolt-aot-shadow")
(def prog "(require 'shadow-ns) (println :f (pr-str (shadow-ns/f)))")

(defn sh [& args]
  (let [{:keys [out err]} (apply proc/shell {:dir work :continue true
                                             :out :string :err :string}
                                 args)]
    (str out err)))

(defn cache-files []
  (fs/glob (fs/path (fs/home) ".jolt/aot-cache") "**/shadow-ns-*"))

(defn clean-cache! []
  (doseq [f (cache-files)] (fs/delete-if-exists f)))

(defn run-f []
  (sh "jolt" "-e" prog))

(defn wait-for [pred]
  ;; the .so sidecar is published asynchronously after the run
  (loop [n 0]
    (if-let [x (first (filter pred (cache-files)))]
      (str x)
      (if (< n 100)
        (do (Thread/sleep 100) (recur (inc n)))
        (do (println "timed out waiting for the AOT cache artifact")
            (System/exit 1))))))

(defn wait-for-so [] (wait-for #(str/ends-with? (str %) ".so")))

(when (fs/exists? work) (fs/delete-tree work))
(fs/create-dirs (fs/path work "src"))
(fs/spit (fs/path work "deps.edn") "{:paths [\"src\"]}\n")
(fs/spit (fs/path work "src/shadow_ns.clj")
         (str "(ns shadow-ns)\n\n"
              "(defn f []\n  (get {:a 1} :a))\n\n"
              "(defn get\n  [url opts]\n  [:shadow url opts])\n"))

(clean-cache!)
(let [out (run-f)]
  (println "clean compile, expected :f 1 =>" (pr-str (last (str/split-lines out))))
  (when-not (str/includes? out ":f 1")
    (println "unexpected clean-compile result; aborting")
    (System/exit 1)))

(let [so (wait-for-so)
      good-bytes (fs/read-all-bytes so)]
  (fs/copy so (fs/path work "good.so"))
  (fs/copy (str/replace so #"\.so$" ".scm") (fs/path work "good.scm"))

  (println "clean artifact:" so)
  (println "now truncating the cached .so and re-running in fresh processes...")

  (loop [[tail & more] [16 48 80 112 144 176 224 320 384 448]]
    (when-not tail
      (println "not reproduced with any truncation tail")
      (clean-cache!)
      (System/exit 1))
    (clean-cache!)
    (fs/copy (fs/path work "good.so") so)
    (fs/copy (fs/path work "good.scm") (str/replace so #"\.so$" ".scm"))
    (fs/write-bytes so (byte-array (take (- (count good-bytes) tail) good-bytes)))
    (let [out (run-f)]
      (if (str/includes? out "[:shadow")
        (let [_ (wait-for-so)
              again (run-f)]
          (println (format "REPRODUCED (tail %d): partial load + in-process recompile => %s"
                           tail (pr-str (last (str/split-lines out)))))
          (println (format "poisoned cache persists in a fresh process => %s"
                           (pr-str (last (str/split-lines again)))))
          (clean-cache!)
          (System/exit 0))
        (recur more)))))
