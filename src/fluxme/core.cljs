(ns fluxme.core
  (:refer-clojure :exclude [js->clj])
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
  (will-update [this component next-props next-state]))

(defprotocol IDidUpdate
  (did-update [this component prev-props prev-state]))

;; My additions

(defprotocol IFlux
  (query [this db]
         [this props db])
  (render [this v]
          [this props v]))

(defprotocol ISubscribe
  (subscribers [this]))

(defn js->clj [form]
  (cljs.core/js->clj form :keywordize-keys true))

(defn get-ref [component ref]
  (aget (.-refs component) ref))

(defn get-dom-node [component]
  (.getDOMNode component))

(defn mounted? [component]
  (.isMounted component))

(defn get-dispatcher [react-component]
  (aget react-component "__fluxme_dispatcher"))

(defn get-instance-atom [react-component]
  (aget react-component "__fluxme_instance_atom"))

(defn init-instance-atom [react-component]
  (aset react-component "__fluxme_instance_atom" (atom {})))

(defn query* [react-component dispatcher db]
  (let [props (js->clj (.-props react-component))]
    (if (empty? props)
      (query dispatcher db)
      (query dispatcher props db))))

(defn update! [react-component dispatcher db]
  (when (mounted? react-component)
    (let [instance-atom (get-instance-atom react-component)
          old-state (:state @instance-atom)
          new-state (query* react-component dispatcher db)]
      (when (not= old-state new-state)
        (swap! instance-atom assoc :state new-state)
        (.forceUpdate react-component)))))

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
         (init-instance-atom this)
         (swap! (get-instance-atom this) assoc :state (query* this d @conn))
         (when (satisfies? IWillMount d)
           (will-mount d this)))))
   :componentDidMount
   (fn []
     (this-as
       this
       (let [d (get-dispatcher this)
             instance-atom (get-instance-atom this)
             k (str (uuid/make-random))]
         (d/listen! conn k #(update! this d (:db-after %)))
         (swap! instance-atom assoc :tx-listen-key k)
         (when (satisfies? ISubscribe d)
           (swap! instance-atom assoc :subscriber-keys (subscribers d)))
         (when (satisfies? IDidMount d)
           (did-mount d this)))))
   :componentWillUnmount
   (fn []
     (this-as
       this
       (let [d (get-dispatcher this)
             instance-atom (get-instance-atom this)
             instance-vars @instance-atom]
         (d/unlisten! conn (:tx-listen-key instance-vars))
         (swap! instance-atom dissoc :tx-listen-key)
         (when (satisfies? ISubscribe d)
           (doseq [subscriber-key (:subscriber-keys instance-vars)]
             (unsubscribe! subscriber-key))
           (swap! instance-atom dissoc :subscriber-keys))
         (when (satisfies? IWillUnmount d)
           (will-unmount d this)))))
   :componentWillUpdate
   (fn [next-props _next-state]
     (this-as
       this
       (let [d (get-dispatcher this)]
         (when (satisfies? IWillUpdate d)
           (will-update d this (js->clj next-props) (:state @(get-instance-atom this)))))))
   :componentDidUpdate
   (fn [prev-props _prev-state]
     (this-as
       this
       (let [d (get-dispatcher this)]
         (when (satisfies? IDidUpdate d)
           (did-update d this (js->clj prev-props) (:state @(get-instance-atom this)))))))})

(defn component [d]
  (let [c (js/React.createClass
            (clj->js
              (merge
                pure-methods
                {:render
                 (fn []
                   (this-as
                     this
                     (let [instance-state @(get-instance-atom this)
                           props (js->clj (.-props this))]
                       (html (if (empty? props)
                               (render d (:state instance-state))
                               (render d props (:state instance-state)))))))
                 :__fluxme_dispatcher
                 d})))]
    (fn [& [props]]
      (js/React.createElement c (clj->js props)))))

(defn mount-app [app mount-point]
  (js/React.renderComponent app mount-point))
