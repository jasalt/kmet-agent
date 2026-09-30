;; Image-model catalog generator (pi: packages/ai/scripts/generate-image-models.ts)
;; — fetches the OpenRouter image-capable model list and writes the committed
;; catalog (src/kmet/ai/image_model_data/image-models.edn) or, under
;; `kmet --generate-models`, the user-level cache
;; (<agent-dir>/image-models-cache/image-models.edn), which
;; kmet.ai.image-models prefers over the bundled catalog when strictly newer.
;; The pipeline is target-agnostic; the bb task (kmet.tasks.generate-models)
;; drives it together with kmet.ai.model-gen for the provider catalogs.
;;
;; Keeps models whose output modalities include "image"; input/output
;; modalities from architecture (defaulting input to [:text]); cost =
;; pricing × 1e6 with negative sentinels clamped to 0 (kmet chat openrouter
;; convention). Modalities are vectors (never lazy seqs — the deterministic
;; EDN writer would otherwise render object identity). Sorted by id through
;; the same writer as the provider catalogs, one :generated-at timestamp.
;; An empty result is a generation failure.
;;
;; Run via: bb generate-models   (network, committed catalogs)
;;          kmet --generate-models (network, user-level cache)
;; Check via: bb check-model-data (offline)

(ns kmet.ai.image-model-gen
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [kmet.ai.edn-writer :as edn-w]
            [kmet.libs.http :as http]
            [kmet.libs.json :as json]))

(def openrouter-base-url "https://openrouter.ai/api/v1")

(def data-dir
  "Committed image catalog directory, relative to the project root (bb tasks
   run from there)."
  "src/kmet/ai/image_model_data")

(defn catalog-path
  "The image catalog file inside DIR."
  [dir]
  (str (fs/path dir "image-models.edn")))

(defn- generated-at
  "UTC timestamp, second precision (ISO-8601) — the same shape as the
   provider-catalog manifest."
  []
  (str (subs (str (java.time.Instant/now)) 0 19) "Z"))

(defn- clamp-cost
  "Clamp a negative pricing sentinel to 0 (pi leaves -1e6 in the generated
   file; kmet's chat openrouter catalogs clamp, and negative rates would
   produce negative costs)."
  [v]
  (let [f (double v)]
    (if (neg? f) 0.0 f)))

(defn- modalities->keywords
  "pi's filter to the text/image modalities, deduped into a vector (the EDN
   writer renders vectors canonically; a lazy seq would serialize as object
   identity)."
  [modalities]
  (vec (distinct (keep #(case %
                          "text" :text
                          "image" :image
                          nil)
                       modalities))))

(defn parse-openrouter-image-models
  "Parse the OpenRouter /models payload into ImagesModel maps (pi
   parseOpenRouterImageModels): output must include \"image\", input
   defaults to [:text] when empty. Throws on a missing/empty model list —
   an empty catalog is never a valid generation."
  [payload]
  (let [data (when (map? payload) (:data payload))]
    (when-not (seq data)
      (throw (ex-info "OpenRouter API returned a missing or empty image model list"
                      {:type :images-generation-failed})))
    (into []
          (keep (fn [m]
                  (when (map? m)
                    (let [architecture (:architecture m)
                          input (modalities->keywords (:input_modalities architecture))
                          output (modalities->keywords (:output_modalities architecture))]
                      (when (some #{:image} output)
                        (let [pricing (:pricing m)]
                          {:id (:id m)
                           :name (:name m)
                           :api :openrouter-images
                           :provider :openrouter
                           :base-url openrouter-base-url
                           :input (if (seq input) input [:text])
                           :output output
                           :cost {:input (clamp-cost (* 1000000 (or (parse-double (or (:prompt pricing) "0")) 0)))
                                  :output (clamp-cost (* 1000000 (or (parse-double (or (:completion pricing) "0")) 0)))
                                  :cache-read (clamp-cost (* 1000000 (or (parse-double (or (:input_cache_read pricing) "0")) 0)))
                                  :cache-write (clamp-cost (* 1000000 (or (parse-double (or (:input_cache_write pricing) "0")) 0)))}}))))))
          data)))

(defn fetch-openrouter-image-models
  "GET the image-capable model list (pi fetchOpenRouterImageModels)."
  []
  (let [response (http/get (str openrouter-base-url "/models?output_modalities=image")
                           {:throw? false :timeout 30000})]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info (str "OpenRouter API returned " (:status response))
                      {:type :images-generation-failed})))
    (parse-openrouter-image-models (json/parse-string (:body response) true))))

(defn write-catalog!
  "Deterministic EDN for DIR/image-models.edn via kmet.ai.edn-writer — the
   same canonical key order / normalized numbers / escaping as the provider
   catalogs (no hand-built string buffer). Sorted by id, one :generated-at
   timestamp. Content-sensitive: when only the timestamp would change, the
   file is left untouched (bytes + mtime) instead of stamping a
   timestamp-only diff. Returns true when the file was written, false when
   it was already up to date."
  [dir models]
  (let [path (catalog-path dir)
        sorted-models (into (sorted-map) (map (fn [m] [(:id m) m]) models))
        existing (try (slurp path)
                      (catch Exception _ nil))
        old-generated (or (try (:generated-at (edn/read-string existing))
                               (catch Exception _ nil))
                          (generated-at))
        blob (fn [generated] {:schema-version 1
                              :generated-at generated
                              :provider {:id :openrouter :name "OpenRouter"}
                              :models sorted-models})
        unchanged? (and existing
                        (= existing (str (edn-w/render (blob old-generated)) "\n")))]
    (if unchanged?
      (do (println (str path " already up to date — nothing written."))
          false)
      (do (fs/create-dirs dir)
          (spit path (str (edn-w/render (blob (generated-at))) "\n"))
          (println (str "Generated " path " (" (count models) " models)"))
          true))))

(defn generate-and-write!
  "Fetch the OpenRouter image model list and write DIR/image-models.edn.
   Prints progress; an empty result writes nothing. Returns {:ok true :models
   models} or {:ok false :errors errors} — callers decide the exit code
   (`bb generate-models` and `kmet --generate-models` exit 1 on failure)."
  [dir]
  (let [models (fetch-openrouter-image-models)]
    (if (empty? models)
      (do (println "OpenRouter returned no usable image models — nothing written.")
          {:ok false :errors ["no usable image models"]})
      (do (write-catalog! dir models)
          {:ok true :models models}))))

(defn validate-committed!
  "Offline validation of the catalog at DIR/image-models.edn (default
   data-dir; the offline half of generation): parseable, provider block
   present, at least one model, every model has the required fields with
   numeric cost rates, no duplicate ids. Returns a vector of error strings
   (empty when valid)."
  ([] (validate-committed! data-dir))
  ([dir]
   (let [errors (atom [])
         data (try (edn/read-string (slurp (catalog-path dir)))
                   (catch Exception e
                     (swap! errors conj (str "unparseable: " (ex-message e)))
                     nil))]
     (when data
       (when-not (map? (:provider data))
         (swap! errors conj "missing provider block"))
       (let [models (:models data)]
         (when-not (map? models)
           (swap! errors conj "missing models map"))
         (when (and (map? models) (empty? models))
           (swap! errors conj "catalog has no models"))
         (doseq [[id m] models]
           (doseq [k [:id :name :api :provider :base-url :input :output :cost]]
             (when-not (contains? m k)
               (swap! errors conj (str id " missing " k))))
           (when (and (map? m) (contains? m :cost))
             (doseq [k [:input :output :cache-read :cache-write]]
               (when-not (number? (get-in m [:cost k]))
                 (swap! errors conj (str id " cost " k " not a number"))))))
         (let [ids (keys models)]
           (when-not (= (count ids) (count (distinct ids)))
             (swap! errors conj "duplicate model ids")))))
     @errors)))

(defn -main
  "Fetch the OpenRouter image model list, regenerate the committed catalog,
   validate. Exits 1 when generation fails."
  [& _]
  (when-not (:ok (generate-and-write! data-dir))
    (System/exit 1)))
