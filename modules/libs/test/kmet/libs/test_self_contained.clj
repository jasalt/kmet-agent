(ns kmet.libs.test-self-contained
  "Guards for the kmet.libs.* layer, which is extractable as a third-party
   package and whitelisted wholesale into extension SCI contexts:

   - self-contained: no requires outside kmet.libs.* (app, tui, modes, ai are
     forbidden; sibling libs like kmet.libs.yaml are allowed)
   - sandbox-safe: no static-field references (SCI has no static-field access,
     so a `Class/FIELD` in a lib is a crash in every extension), checked both at
     the source and by running the queue inside a bare SCI context."
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.concurrent]
            [kmet.tasks.source-reader :as sr]
            [sci.core :as sci]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(defn- lib-files []
  ;; recurse: src/kmet/libs/mcp/transport/*.clj is two levels down and the
  ;; one-level scan this replaces silently stopped checking nested libs
  (letfn [(walk [dir]
            (mapcat (fn [f]
                      (if (fs/directory? f) (walk f) [f]))
                    (fs/list-dir dir)))]
    (->> (walk "modules/libs/src/kmet/libs")
         (filter #(re-find #"\.clj[ca]?$" (str %)))
         (map str))))

(defn- ns-form [path]
  ;; Read the ns form itself. A regex over the file was the original
  ;; implementation and it silently checked almost nothing: `.*?` stops at
  ;; the first `)` inside the ns docstring, long before `:require`.
  (sr/read-ns-form path "self-containment guard"))

(defn- kmet-requires [path]
  (when-let [form (ns-form path)]
    (->> (for [clause (rest form)
               :when (and (seq? clause) (= :require (first clause)))
               spec (rest clause)]
           (cond
             (symbol? spec) spec
             (vector? spec) (first spec)
             (seq? spec) (first spec)))
         (map str)
         (filter #(str/starts-with? % "kmet."))
         ;; sibling-lib requires are allowed; everything else is not
         (remove #(str/starts-with? % "kmet.libs.")))))

(def ^:private static-field-re
  "A qualified symbol whose name is ALL-CAPS: `TimeUnit/MILLISECONDS`,
   `clojure.lang.PersistentQueue/EMPTY`, `Normalizer$Form/NFKC`."
  #"[a-z][\w$]*(\.[\w$]+)*/[A-Z][A-Z0-9_]*")

(defn- static-fields
  "The static-field references in the source at PATH. The file is read with
   rewrite-clj rather than text-scanned, so a mention in a docstring, a string or
   a comment is prose and does not count — the guard is about code that runs."
  [path]
  (let [found (atom #{})]
    (letfn [(walk [n]
              (when (= :token (node/tag n))
                (let [v (node/sexpr n)]
                  (when (and (symbol? v)
                             (re-matches static-field-re (str v)))
                    (swap! found conj v))))
              (when (node/inner? n)
                (run! walk (node/children n))))]
      (walk (parser/parse-string-all (slurp path))))
    (sort-by str @found)))

(deftest libs-name-no-static-fields
  ;; every kmet.libs.* namespace is whitelisted into the extension SCI context
  ;; (kmet.agent.extensions.context), and SCI resolves static *methods* but offers
  ;; no static-field access: `clojure.lang.PersistentQueue/EMPTY`,
  ;; `TimeUnit/MILLISECONDS`, `ZoneOffset/UTC` and `Normalizer$Form/NFKC` all fail
  ;; there with "Unable to resolve symbol", at the call rather than at load — so
  ;; the host runs fine and the extension breaks. Checked at the source, because
  ;; nothing in the test suite calls a library from inside a sandbox.
  (doseq [f (lib-files)
          :let [fields (static-fields f)]]
    (is (empty? fields)
        (str f " names static fields " (vec fields)
             " — SCI has no static-field access; use a static method"
             " (TimeUnit/valueOf \"MILLISECONDS\") or a plain expression"))))

(deftest libs-are-self-contained
  (doseq [f (lib-files)]
    (let [deps (kmet-requires f)]
      (is (empty? deps)
          (str f " must not require kmet.* namespaces, found: " (vec deps))))))

(deftest the-queue-runs-inside-a-sandbox
  ;; the static-field rule above is checked at the source; this is the executable
  ;; half for the primitive landing 6a added — a bare SCI context (no host classes
  ;; registered, no imports) must create a queue and hand a value over, because
  ;; extensions reach this namespace through exactly such a context.
  (let [ctx (sci/init {:namespaces
                       {'kmet.libs.concurrent
                        (into {}
                              (for [[sym v] (ns-publics 'kmet.libs.concurrent)]
                                [sym @v]))}})]
    (is (= :handed-over
           (sci/eval-string* ctx
                             "(let [q (kmet.libs.concurrent/queue)]
                                (kmet.libs.concurrent/put! q :handed-over)
                                (kmet.libs.concurrent/take! q))"))
        "put!/take! work with no host classes available")
    (is (= :kmet.libs.concurrent/closed
           (sci/eval-string* ctx
                             "(let [q (kmet.libs.concurrent/queue)]
                                (kmet.libs.concurrent/close! q)
                                (kmet.libs.concurrent/take! q))"))
        "and so does close!, marker and all")))
