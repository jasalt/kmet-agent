(ns kmet.ai.http
  "Provider HTTP decorator over the single outbound-HTTP boundary
   (kmet.libs.http). Applies the request's provider-event hooks (pi:
   before_provider_headers / after_provider_response) around the transport
   call; contains no networking itself.

   The hooks are arguments (`{:on-headers f :on-response f}`, from
   `kmet.ai.llm/send-message`'s `:hooks`), not globals: this namespace knows
   nothing about who listens, so it needs no kmet.ai require at all.

   Provider implementations call ai-http/request (stream responses finished
   with ai-http/close! / ai-http/abort!); OAuth, model generation, and
   other ordinary calls use kmet.libs.http directly."
  (:require [kmet.libs.http :as http]))

(defn request
  "One provider HTTP request: the final :headers map runs through HOOKS'
   :on-headers right before the HTTP call; after the response arrives,
   :on-response fires with {:status n :headers {...}}. HOOKS may be nil or
   carry either key alone. Delegates the transport to kmet.libs.http/request
   — see its docstring for OPTS (:method defaults to :post, the provider
   streams' convention; :signal is accepted for the curl transport's
   mid-stream cancellation)."
  [url opts signal hooks]
  (let [opts (if-let [f (:on-headers hooks)]
               (update opts :headers #(or (f %) %))
               opts)
        response (http/request (assoc opts :url url
                                      :method (or (:method opts) :post)
                                      :signal signal))]
    (when-let [f (:on-response hooks)]
      (f {:status (:status response) :headers (:headers response)}))
    response))

(def close! http/close!)
(def abort! http/abort!)
