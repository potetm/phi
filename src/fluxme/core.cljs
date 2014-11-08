(ns fluxme.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan] :as a]
            [cljs-uuid.core :as uuid]
            [datascript :as d]
            [sablono.core :refer-macros [html]]))

;; Initialize

(declare conn)
(declare subscribers-map)
(declare publisher)
(declare publication)

(defn init-fluxme! [new-conn new-publisher]
  (defonce conn new-conn)
  (defonce subscribers-map (atom {}))
  (defonce publisher new-publisher)
  (defonce publication (a/pub publisher :type)))

;; Events

(defrecord Event [id type db subjects])

(defn event [type db subjects]
  (->Event
    (uuid/make-random)
    type
    db
    subjects))

(defn unsubscribe! [chan-key]
  (when-let [{:keys [chan topics]} (get @subscribers-map chan-key)]
    (doseq [topic topics]
      (a/unsub publication topic chan))
    (swap! subscribers-map dissoc chan-key)))

(defn subscribe! [event-types chan-key ch]
  (doseq [event-type event-types]
    (a/sub publication event-type ch true))
  (swap! subscribers-map assoc chan-key {:chan ch, :topics event-types}))

(defn publish! [^Event e]
  {:pre [(instance? Event e)]}
  (a/put! publisher e))

;; Render
;; React Lifecycle protocols from OM

(defprotocol IDisplayName
  (display-name [this component]))

(defprotocol IWillMount
  (will-mount [this component]))

(defprotocol IDidMount
  (did-mount [this component]))

(defprotocol IWillUnmount
  (will-unmount [this component]))

#_(defprotocol IWillUpdate
  (will-update [this next-props next-state]))

#_(defprotocol IDidUpdate
  (did-update [this prev-props prev-state]))

;; My additions

(defprotocol IFlux
  (query [this db])
  (render [this v]))

(defprotocol ISubscribe
  (subscribers [this]))

(defn dispatcher [react-component]
  (aget react-component "__fluxme_dispatcher"))

;; Trying out pure-methods from OM as well

(def pure-methods
  {:getDisplayName
   (fn []
     (this-as
       this
       (let [d (dispatcher this)]
         (when (satisfies? IDisplayName d)
           (display-name d this)))))
   :shouldComponentUpdate
   (constantly false)
   :componentWillMount
   (fn []
     (this-as
       this
       (let [d (dispatcher this)]
         (when (satisfies? IWillMount d)
           (will-mount d this)))))
   :componentDidMount
   (fn []
     (this-as
       this
       (let [d (dispatcher this)]
         (d/listen!
           conn
           ;; idea from https://gist.github.com/allgress/11348685
           (fn [tx-data]
             (let [novelty (query d (:tx-data tx-data))]
               (when-not (nil? novelty)
                 (if (coll? novelty)
                   (when (not-empty novelty)
                     (.forceUpdate this))
                   (.forceUpdate this))))))
         (when (satisfies? ISubscribe d)
           (aset this "__fluxme_subscriber_keys" (subscribers d)))
         (when (satisfies? IDidMount d)
           (did-mount d this)))))
   :componentWillUnmount
   (fn []
     (this-as
       this
       (let [d (dispatcher this)]
         (when (satisfies? ISubscribe d)
           (doseq [subscriber-key (aget this "__fluxme_subscriber_keys")]
             (unsubscribe! subscriber-key))
           (aset this "__fluxme_subscriber_keys" nil))
         (when (satisfies? IWillUnmount d)
           (will-unmount d this)))))
   ;; TODO: Make these really get the state
   #_   :componentWillUpdate
   #_   (fn [next-props next-state]
        (this-as
          this
          (let [d (dispatcher this)]
            (when (satisfies? IWillUpdate d)
              (will-update d next-props next-state)))))
   #_   :componentDidUpdate
   #_   (fn [prev-props prev-state]
        (this-as
          this
          (let [d (dispatcher this)]
            (when (satisfies? IDidUpdate d)
              (did-update d prev-props prev-state)))))})

(defn component [d]
  (let [c (js/React.createClass
            (clj->js
              (merge
                pure-methods
                {:render
                 (fn []
                   (html (render d (query d @conn))))
                 :__fluxme_dispatcher
                 d})))]
    c))

(defn mount-app [app mount-point]
  (js/React.renderComponent app mount-point))
