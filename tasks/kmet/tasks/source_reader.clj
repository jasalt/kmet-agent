(ns kmet.tasks.source-reader
  "Read source files without loading them — the architecture guards' shared
   reader (kmet.libs.test-self-contained, kmet.libs.test-scope-self-contained,
   kmet.ai.test-self-contained, kmet.loader.test-self-contained,
   kmet.test-layer-guard).

   Reader conditionals resolve to THIS host's branch — the view the running
   host loads: #{:bb :clj} on babashka, #{:jolt :clj} on Jolt, #{:clj} on the
   JVM (so :default). clojure.core/read-string's one-arity throws
   \"Conditional read not allowed\" on the JVM for any #? source, and the
   :read-cond default resolves to #{:clj} on every host, which would silently
   check the :default branch instead of bb's on babashka."
  (:require [kmet.libs.host :as host]))

(defn reader-features
  "The reader-conditional features of the running host."
  []
  (cond
    (host/jolt?) #{:jolt :clj}
    (host/babashka?) #{:bb :clj}
    :else #{:clj}))

(defn read-ns-form
  "The ns form of the source file at PATH, or nil when the first form is not
   an ns form. Throws (::unreadable, carrying PATH) when the file cannot be
   read or parsed — a guard that cannot read its file must never silently
   check nothing. CONTEXT labels the failure in its message.

   FEATURES picks the reader branches to resolve. The default is the running
   host's (reader-features); pass a set to read another host's view, and two
   disjoint sets (e.g. #{:bb :clj} and #{:jolt :clj}) when a guard must see
   every branch — `#?` picks the first matching branch per read, so one read
   can never return both."
  ([path] (read-ns-form path (reader-features) "source reader"))
  ([path context] (read-ns-form path (reader-features) context))
  ([path features context]
   (let [form (try
                (read-string {:read-cond :allow
                              :features features}
                             (slurp path))
                (catch Exception e
                  (throw (ex-info (str context " cannot read " path)
                                  {:type ::unreadable :path (str path)} e))))]
     (when (and (seq? form) (= 'ns (first form))) form))))
