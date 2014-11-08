(ns fluxme.event
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan] :as a]
            [cljs-uuid.core :as uuid]))

(defonce subscribers (atom {}))
(defonce publisher (chan 2048))
(defonce publication (a/pub publisher :type))

(defrecord Event [id type db subjects])

(defn event [type db subjects]
      (->Event
        (uuid/make-random)
        type
        db
        subjects))

(defn unsubscribe! [chan-key]
      (when-let [{:keys [chan topics]} (get @subscribers chan-key)]
                (doseq [topic topics]
                       (a/unsub publication topic chan))
                (swap! subscribers dissoc chan-key)))

(defn subscribe! [event-types chan-key ch]
      (doseq [event-type event-types]
             (a/sub publication event-type ch true))
      (swap! subscribers assoc chan-key {:chan ch, :topics event-types}))

(defn publish! [^Event e]
      {:pre [(instance? Event e)]}
      (a/put! publisher e))
