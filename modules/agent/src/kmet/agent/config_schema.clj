(ns kmet.agent.config-schema
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Config
  [:map {:closed false}
   [:provider {:optional true} [:or :keyword :string :nil]]
   [:model {:optional true} [:or :string :nil]]
   [:theme {:optional true} [:or :string :nil]]
   [:loop-guard {:optional true}
    [:map {:closed false}
     [:enabled {:optional true} :boolean]
     [:threshold {:optional true} [:or :nil :int]]]]
   [:images {:optional true}
    [:map {:closed false}
     [:block-images {:optional true} :boolean]]]
   [:http-idle-timeout-ms {:optional true} [:or :nil :int]]
   [:http-total-timeout-ms {:optional true} [:or :nil :int]]])

(defn validate-config?
  [c]
  (m/validate Config c))

(defn explain-config
  [c]
  (me/humanize (m/explain Config c)))