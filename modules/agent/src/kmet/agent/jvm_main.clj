(ns kmet.agent.jvm-main
  "AOT entry point of the JVM artifact (`bb uberjar-jvm` →
   `target/uberjar-jvm.jar`, run with `java -jar`).

   A Clojure namespace is not a Java class, so a jar whose manifest names one
   as Main-Class fails with ClassNotFoundException however many of its sources
   are AOT-compiled: the JVM needs a real class with a static `main`. That is
   what `:gen-class` generates from `-main` — the packager compiles this
   namespace (kmet.tasks.build-jvm) and points the manifest here.

   It only delegates, so the CLI stays defined once, in kmet.agent.core. The
   bb and jolt hosts never load this namespace: their artifacts run
   kmet.agent.core/-main directly."
  (:gen-class)
  (:require [kmet.agent.core :as core]))

(defn -main
  "Process entry: the packaged jar's Main-Class, i.e. kmet's CLI."
  [& args]
  (apply core/-main args))
