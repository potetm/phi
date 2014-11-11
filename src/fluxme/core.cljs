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

(defonce ^:private component-render-fns (atom #{}))

(defn init-conn! [new-conn]
  (defonce conn new-conn)
  (d/listen!
    conn
    (fn [tx-report]
      (doseq [render @component-render-fns]
        (render tx-report)))))

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

(defprotocol IUpdateForFactParts
  (fact-parts [this]
              [this props]))

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

(defn run-full-query-and-update [react-component dispatcher db]
  (let [instance-atom (get-instance-atom react-component)
        old-state (:state @instance-atom)
        new-state (query* react-component dispatcher db)]
    (when (not= old-state new-state)
      (swap! instance-atom assoc :state new-state)
      (.forceUpdate react-component))))

(defn fact-parts-match? [react-component dispatcher tx-data]
  (let [props (js->clj (.-props react-component))]
    (loop [parts (if (empty? props) (fact-parts dispatcher)
                                    (fact-parts dispatcher props))]
      (let [[e a v] (first parts)
            r (rest parts)]
        (if (loop [tx-data tx-data]
              (let [datom (first tx-data)
                    r (rest tx-data)]
                (if (and datom
                         (or (nil? e) (= e (.-e datom)))
                         (or (nil? a) (= a (.-a datom)))
                         (or (nil? v) (= v (.-v datom))))
                  true
                  (if (seq r)
                    (recur r)
                    false))))
          true
          (if (seq r)
            (recur r)
            false))))))

(defn update! [react-component dispatcher {:keys [tx-data db-after]}]
  (when (mounted? react-component)
    (if (satisfies? IUpdateForFactParts dispatcher)
      (when (fact-parts-match? react-component dispatcher tx-data)
        (run-full-query-and-update react-component dispatcher db-after))
      (run-full-query-and-update react-component dispatcher db-after))))
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
             render (partial update! this d)]
         (swap! component-render-fns conj render)
         (swap! instance-atom assoc :render-fn render)
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
         (swap! component-render-fns disj (:render-fn instance-vars))
         (swap! instance-atom dissoc :render-fn)
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

;; Idea: Add optional IUpdateForAttrs protocol and we can do a check like this on tx-data:
;; https://github.com/someteam/acha/blob/master/src-cljs/acha.cljs#L430

;; Also add optional IUpdateInAnimationFrame marker interface and we can
;; queue updates to that component to run in js/requestAnimationFrame.
;;
;; Ideally the queue will be smart enough to overwrite duplicate updates to a component


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
