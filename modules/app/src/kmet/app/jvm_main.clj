(ns kmet.app.jvm-main
  "AOT entry point of the JVM artifact (`bb uberjar-jvm` →
   `target/uberjar-jvm.jar`, run with `java -jar`).

   A Clojure namespace is not a Java class, so a jar whose manifest names one
   as Main-Class fails with ClassNotFoundException however many of its sources
   are AOT-compiled: the JVM needs a real class with a static `main`. That is
   what `:gen-class` generates from `-main` — the packager compiles this
   namespace (kmet.tasks.build-jvm) and points the manifest here.

   It delegates to the app's own entry (`kmet.app.core/-main`), which parses
   the CLI once, so nothing about the command line is defined here. The bb and
   jolt hosts never load this namespace: their artifacts run
   `kmet.app.core/-main` directly."
  (:gen-class)
  (:require [kmet.app.core :as app]))

(defn -main
  "Process entry: the packaged jar's Main-Class, i.e. kmet's entry point."
  [& args]
  (apply app/-main args))
