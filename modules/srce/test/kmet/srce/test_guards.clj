(ns kmet.srce.test-guards
  "Guard: the kernel is rank-0 self-contained and the retired store stays gone.

   kmet.test-module-deps owns the rank ordering between modules; this owns what
   a rank-1 module may name at all — the kernel may require kmet.libs.*,
   kmet.loader.*, kmet.reakt.* and its own subnamespaces, nothing above it, and
   never `kmet.libs.scope` (deleted at landing 4e, so the name is a tripwire
   now rather than a dependency)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private kernel-sources
  (->> (fs/glob "modules/srce/src" "**/*.{clj,cljc}")
       (map str)
       sort))

(defn- required-namespaces
  "The names a source file requires, read from its ns form."
  [file]
  (let [text (slurp file)
        ns-form (or (some-> (re-find #"\(ns\s[^\n]+\n([\s\S]*?)\n\n" text) second)
                    (some-> (re-find #"\(ns\s[^\n]+\n([\s\S]*)$" text) second)
                    "")]
    (->> (re-seq #"\[([a-z][a-z0-9.\-]*)" ns-form)
         (map (comp symbol second)))))

(deftest kernel-sources-are-real
  (is (seq kernel-sources) "the guard found the kernel sources")
  (is (every? #(str/ends-with? % ".clj") kernel-sources)))

(deftest kernel-requires-only-rank-zero-code
  (testing "no app/ui/tui/tk name, or anything else above rank 0"
    (doseq [f kernel-sources
            :let [kmet-deps (filter #(str/starts-with? (str %) "kmet.")
                                    (required-namespaces f))
                  above (remove #(or (str/starts-with? (str %) "kmet.srce")
                                     (str/starts-with? (str %) "kmet.libs.")
                                     (str/starts-with? (str %) "kmet.loader.")
                                     (str/starts-with? (str %) "kmet.reakt."))
                                kmet-deps)]
            :when (seq above)]
      (is false (str f " requires above rank 0: " (vec above))))))

(deftest kernel-does-not-require-the-retired-store
  (doseq [f kernel-sources]
    (is (not (str/includes? (slurp f) "libs.scope"))
        (str f " must not name the retired store"))))
