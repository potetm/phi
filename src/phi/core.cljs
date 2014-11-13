(ns phi.core
  (:refer-clojure :exclude [js->clj])
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan] :as a]
            [cljs-uuid.core :as uuid]
            [clojure.set :as set]
            [datascript :as d]
            [sablono.core :refer-macros [html]])
  (:import [goog.ui IdGenerator]))

;; Initialize

(declare conn)
(declare optimization-strategy)

(defonce subscribers-map (atom {}))
(defonce publisher (chan 2048))
(defonce publication (a/pub publisher :type))

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

(defprotocol IPhi
  (query [this db]
         [this props db])
  (render [this v]
          [this props v]))

(defprotocol ISubscribe
  (subscribers [this]))

(defprotocol IUpdateForFactParts
  (fact-parts [this]
              [this props]))

(defprotocol IUpdateInAnimationFrame)

(defn js->clj [form]
  (cljs.core/js->clj form :keywordize-keys true))

(defn get-ref [component ref]
  (aget (.-refs component) ref))

(defn get-dom-node [component]
  (.getDOMNode component))

(defn mounted? [component]
  (.isMounted component))

(defn- gen-id []
  (.getNextUniqueId (.getInstance IdGenerator)))

(defn get-dispatcher [react-component]
  (aget react-component "__phi_dispatcher"))

(defn get-instance-atom [react-component]
  (aget react-component "__phi_instance_atom"))

(defn init-instance-atom [react-component]
  (aset react-component "__phi_instance_atom" (atom {})))

(defonce ^:private component-map (atom {}))
(defonce ^:private render-queue (atom #{}))
(def ^:private render-requested false)

(defn query* [c db]
  (let [d (get-dispatcher c)
        props (js->clj (.-props c))]
    (if (empty? props)
      (query d db)
      (query d props db))))

(defn run-query-and-render [c]
  (let [instance-atom (get-instance-atom c)
        old-state (:state @instance-atom)
        new-state (query* c @conn)]
    (when (and (mounted? c)
               (not= old-state new-state))
      (swap! instance-atom assoc :state new-state)
      (.forceUpdate c))))

(defn render-all-queued []
  (let [cmap @component-map
        _ (set! render-requested false)
        queue (loop [q @render-queue]
                (if (compare-and-set! render-queue q #{})
                  q
                  (recur @render-queue)))]
    (doseq [id queue]
      (when-let [c (get cmap id)]
        (run-query-and-render c)))))

(defprotocol IOptimizeUpdates
  (-init-component [this id component])
  (-cleanup-component [this id component])
  (-get-component-ids-for-operation [this op-data]))

(defn datom->fact-parts [d]
  [[(.-e d)]
   [(.-e d) (.-a d)]
   [(.-e d) (.-a d) (.-v d)]
   [nil (.-a d)]
   [nil (.-a d) (.-v d)]
   [nil nil (.-v d)]])

(defrecord Datascript [conn fact-part-map-atom]
  IOptimizeUpdates
  (-init-component [_ id c]
    (let [d (get-dispatcher c)
          props (js->clj (.-props c))]
      (if (satisfies? IUpdateForFactParts d)
        (doseq [fp (if (empty? props)
                     (fact-parts d)
                     (fact-parts d props))]
          (swap! fact-part-map-atom update-in [fp] conj id))
        (swap! fact-part-map-atom update-in [::no-parts] conj id))))
  (-cleanup-component [_ id c]
    (let [d (get-dispatcher c)
          props (js->clj (.-props c))]
      (if (satisfies? IUpdateForFactParts d)
        (doseq [fp (if (empty? props) (fact-parts d)
                                      (fact-parts d props))]
          (swap! fact-part-map-atom update-in [fp] #(remove #{id} %)))
        (swap! fact-part-map-atom update-in [::no-parts] #(remove #{id} %)))))
  (-get-component-ids-for-operation [_ tx-data]
    (let [fpm @fact-part-map-atom]
      (distinct
        (concat
          (mapcat
            (fn [datom]
              (mapcat
                (partial get fpm)
                (datom->fact-parts datom)))
            tx-data)
          (::no-parts fpm))))))

(defrecord Associative [conn]
  IOptimizeUpdates
  (-init-component [_ id c])
  (-cleanup-component [_ id c])
  (-get-component-ids-for-operation [_ op-data]
    (keys @component-map)))

(defn run-updates! [op-data]
  (let [cm @component-map
        ids (-get-component-ids-for-operation optimization-strategy op-data)]
    (doseq [id ids
            :let [c (get cm id)
                  d (and c (get-dispatcher c))]]
      (when c
        (if-not (satisfies? IUpdateInAnimationFrame d)
          (run-query-and-render c)
          (do
            (swap! render-queue conj id)
            (when-not render-requested
              (set! render-requested true)
              (if (exists? js/requestAnimationFrame)
                (js/requestAnimationFrame render-all-queued)
                (js/setTimeout render-all-queued 16)))))))))

(defn init-datascript-conn! [new-conn]
  (defonce conn new-conn)
  (defonce optimization-strategy (->Datascript conn (atom {})))
  (d/listen!
    conn
    (fn [{:keys [tx-data]}]
      (run-updates! tx-data))))

(defn init-associative-conn! [new-conn]
  (defonce conn new-conn)
  (defonce optimization-strategy (->Associative conn))
  (add-watch conn :updates
    (fn [_conn _key _old-val new-val]
      (run-updates! new-val))))

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
         (swap! (get-instance-atom this) assoc :state (query* this @conn))
         (when (satisfies? IWillMount d)
           (will-mount d this)))))
   :componentDidMount
   (fn []
     (this-as
       this
       (let [d (get-dispatcher this)
             instance-atom (get-instance-atom this)
             id (gen-id)]
         (swap! component-map assoc id this)
         (swap! instance-atom assoc :id id)
         (-init-component optimization-strategy id this)
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
             instance-vars @instance-atom
             id (:id instance-vars)]
         (swap! component-map dissoc id)
         (swap! instance-atom dissoc :id)
         (-cleanup-component optimization-strategy id this)
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
                 :__phi_dispatcher
                 d})))]
    (fn [& [props]]
      (js/React.createElement c (clj->js props)))))

(defn mount-app [app mount-point]
  (js/React.renderComponent app mount-point))
