(ns fluxme.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan] :as a]
            [cljs-uuid.core :as uuid]
            [datascript :as d]
            [sablono.core :refer-macros [html]]))

;; Initialize

(declare conn)

(defonce subscribers-map (atom {}))
(defonce publisher (chan 2048))
(defonce publication (a/pub publisher :type))

(defn init-conn! [new-conn]
  (defonce conn new-conn))

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

(defprotocol IWillUpdate
  (will-update [this component next-state]))

(defprotocol IDidUpdate
  (did-update [this component prev-state]))

;; My additions

(defprotocol IFlux
  (query [this db])
  (render [this v]))

(defprotocol ISubscribe
  (subscribers [this]))

(defn get-dispatcher [react-component]
  (aget react-component "__fluxme_dispatcher"))

(defn get-subscriber-keys [react-component]
  (aget react-component "__fluxme_subscriber_keys"))

(defn set-subscriber-keys [react-component v]
  (aset react-component "__fluxme_subscriber_keys" v))

(defn get-fluxme-state-atom [react-component]
  (aget react-component "__fluxme_state"))

(defn update! [react-component dispatcher]
  (let [state-atom (get-fluxme-state-atom react-component)
        old-state @state-atom
        new-state (query dispatcher @conn)]
    (when (not= old-state new-state)
      (when (satisfies? IWillUpdate dispatcher)
        (will-update dispatcher react-component new-state))

      (reset! state-atom new-state)
      (.forceUpdate react-component)

      (when (satisfies? IDidUpdate dispatcher)
        (did-update dispatcher react-component old-state)))))

;; Trying out pure-methods from OM as well

(def pure-methods
  {:getDisplayName
   (fn []
     (this-as
       this
       (let [d (get-dispatcher this)]
         (when (satisfies? IDisplayName d)
           (display-name d this)))))
   :shouldComponentUpdate
   (constantly false)
   :componentWillMount
   (fn []
     (this-as
       this
       (let [d (get-dispatcher this)]
         (when (satisfies? IWillMount d)
           (will-mount d this)))))
   :componentDidMount
   (fn []
     (this-as
       this
       (let [d (get-dispatcher this)]
         (d/listen!
           conn
           ;; idea from https://gist.github.com/allgress/11348685
           (fn [tx-data]
             (let [novelty (query d (:tx-data tx-data))]
               (when-not (nil? novelty)
                 (if (coll? novelty)
                   (when (not-empty novelty)
                     (update! this d))
                   (update! this d))))))
         (when (satisfies? ISubscribe d)
           (set-subscriber-keys this (subscribers d)))
         (when (satisfies? IDidMount d)
           (did-mount d this)))))
   :componentWillUnmount
   (fn []
     (this-as
       this
       (let [d (get-dispatcher this)]
         (when (satisfies? ISubscribe d)
           (doseq [subscriber-key (get-subscriber-keys this)]
             (unsubscribe! subscriber-key))
           (set-subscriber-keys this nil))
         (when (satisfies? IWillUnmount d)
           (will-unmount d this)))))})

(defn component [d]
  (let [state (atom (query d @conn))
        c (js/React.createClass
            (clj->js
              (merge
                pure-methods
                {:render
                 (fn [] (html (render d @state)))
                 :__fluxme_dispatcher
                 d
                 :__fluxme_state
                 state})))]
    c))

(defn mount-app [app mount-point]
  (js/React.renderComponent app mount-point))
