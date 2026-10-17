(ns kmet.ai.registry
  "Shared, record-agnostic provider-registry helpers used by
   kmet.ai.models and kmet.ai.image-models.

   Both registries are atoms whose value is a map of provider-id -> provider
   record, where every provider record carries a :models collection of model
   records (each with an :id). These helpers operate on that common shape and
   are deliberately record-agnostic so the two registries stay in sync without
   copy-paste. Mutators return the registered/removed provider (or nil) for
   chaining; callers that need a specific return value wrap the call.")

(defn get-entity
  "Provider record for ID, or nil when unknown."
  [reg-atom id]
  (get @reg-atom id))

(defn register-entity!
  "Upsert ENTITY (keyed by its :id) and return ENTITY."
  [reg-atom entity]
  (swap! reg-atom assoc (:id entity) entity)
  entity)

(defn unregister-entity!
  "Remove the provider for ID and return nil."
  [reg-atom id]
  (swap! reg-atom dissoc id)
  nil)

(defn clear-entities!
  "Remove every provider and return nil."
  [reg-atom]
  (reset! reg-atom {})
  nil)

(defn get-model
  "Model record for PROVIDER-ID + MODEL-ID, or nil when either is unknown."
  [reg-atom provider-id model-id]
  (when-let [models (some-> (get @reg-atom provider-id) :models)]
    (first (filter #(= model-id (:id %)) models))))
