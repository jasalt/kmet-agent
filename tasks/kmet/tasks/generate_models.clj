;; kmet.tasks.generate-models — the `bb generate-models` / `bb check-model-data`
;; task entry over the generators in src (kmet.ai.model-gen + the image half,
;; kmet.ai.image-model-gen, shared with `kmet --generate-models` so the
;; packaged binary runs the same pipelines into the user-level caches).
;; Task-only code: it sits outside src/, so neither artifact carries it (the
;; uberjar walks src/, jolt bakes only its :embed roots).
;;
;; Run via: bb generate-models   (network, regenerates src/kmet/ai/model_data
;;                               and src/kmet/ai/image_model_data)
;; Check via: bb check-model-data (offline)

(ns kmet.tasks.generate-models
  (:require [kmet.ai.image-model-gen :as image-gen]
            [kmet.ai.model-gen :as gen]))

(def data-dir gen/data-dir)
(def validate-committed! gen/validate-committed!)

(defn validate-all!
  "Offline validation of both committed catalogs (provider + image)."
  []
  (into (vec (gen/validate-committed!))
        (image-gen/validate-committed!)))

(defn -main
  "Regenerate the committed provider + image catalogs, validating each. The
   provider pipeline runs first; exits 1 when either generation fails."
  [& _]
  (when-not (:ok (gen/generate-and-write! gen/data-dir))
    (System/exit 1))
  (when-not (:ok (image-gen/generate-and-write! image-gen/data-dir))
    (System/exit 1))
  (println "All model catalogs regenerated."))
