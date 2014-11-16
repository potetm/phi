(ns phi.core
  (:refer-clojure :exclude [js->clj])
  (:require [cljs.core.async :refer [chan] :as a]
            [datascript :as d]
            [sablono.core :refer-macros [html]]))

;; Initialize

(declare conn)
(def ^{:dynamic true :private true} *db*)

(declare optimization-strategy)

(defonce subscribers-map (atom {}))
(defonce publisher (chan 1024))
(defonce publication (a/pub publisher :type))

;; Events

(defrecord Event [id type db subjects])

(defn- gen-id []
  (goog/getUid))

(defn event [type db subjects]
  (->Event
    (gen-id)
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
  (render [this db v]
          [this db props v]))

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

(defprotocol IGetDb
  (-get-db [this]))

(defprotocol IGetProps
  (-get-props [this]))

(defprotocol IGetComponentState
  (-get-state [this]))

(defprotocol ISetComponentState
  (-set-state [this state]))

(defprotocol IGetDispatcher
  (-get-dispatcher [this]))

;; Trying out pure-methods from OM as well

(def lifecycle-methods
  {:getDisplayName
   (fn []
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IDisplayName d)
           (display-name d this)))))
   :componentWillMount
   (fn []
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IWillMount d)
           (will-mount d this)))))
   :componentDidMount
   (fn []
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IDidMount d)
           (did-mount d this)))))
   :componentWillUnmount
   (fn []
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IWillUnmount d)
           (will-unmount d this)))))
   :componentWillUpdate
   (fn [next-props _next-state]
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IWillUpdate d)
           (will-update d this (aget next-props "__phi_props") nil)))))
   :componentDidUpdate
   (fn [prev-props _prev-state]
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IDidUpdate d)
           (did-update d this (aget prev-props "__phi_props") nil)))))
   :render
   (fn []
     (this-as
       this
       (let [d (-get-dispatcher this)
             state (-get-state this)
             props (-get-props this)]
         (html (if (empty? props)
                 (render d (-get-db this) state)
                 (render d (-get-db this) props state))))))})

(defn- specify-state-methods! [desc]
  (specify! desc
    IGetDb
    (-get-db
      ([this]
       (aget (.-props this) "__phi_db")))
    IGetProps
    (-get-props
      ([this]
       (aget (.-props this) "__phi_props")))
    IGetComponentState
    (-get-state
      ([this]
       (aget (.-props this) "__phi_component_state")))
    IGetDispatcher
    (-get-dispatcher
      ([this]
       (aget this "__phi_dispatcher")))))

(defn query* [d props db]
  (if (empty? props)
    (query d db)
    (query d props db)))

(defn component [d]
  (let [c (js/React.createClass
            (specify-state-methods!
              (clj->js
                (merge
                  lifecycle-methods
                  {:__phi_dispatcher d}))))]
    (fn [db & [props]]
      (js/React.createElement c
                              #js {:__phi_db db
                                   :__phi_props props
                                   :__phi_component_state (query* d props db)}))))

(defprotocol ITxNotify
  (-register! [this key callback])
  (-unregister! [this key]))

(deftype IDatascriptConn [conn]
  IDeref
  (-deref [_]
    @conn)
  ITxNotify
  (-register! [_ key callback]
    (d/listen! conn key callback))
  (-unregister! [_ key]
    (d/unlisten! conn key)))

(deftype IAssociativeConn [conn]
  IDeref
  (-deref [_]
    @conn)
  IReset
  (-reset! [_ f]
    (reset! conn f))
  ISwap
  (-swap! [_ f]
    (swap! conn f))
  (-swap! [_ f a]
    (swap! conn f a))
  (-swap! [_ f a b]
    (swap! conn f a b))
  (-swap! [_ f a b xs]
    (swap! conn f a b xs))
  ITxNotify
  (-register! [_ key callback]
    (add-watch conn key callback))
  (-unregister! [_ key]
    (remove-watch conn key)))

(defn init-datascript-conn! [new-conn]
  (defonce conn (->IDatascriptConn new-conn)))

(defn init-associative-conn! [new-conn]
  (defonce conn (->IAssociativeConn new-conn)))

(defonce ^:private render-queue (atom #{}))
(def ^:private render-requested false)

(defn render-all-queued []
  (set! render-requested false)
  (let [render-fns (loop [queue @render-queue]
                     (if (compare-and-set! render-queue queue #{})
                       queue
                       (recur @render-queue)))
        db @conn]
    (doseq [render render-fns]
      (render db))))

(defn mount-app [app mount-point]
  (let [id (gen-id)
        render-from-root (fn [db]
                           (js/React.renderComponent
                             (app db)
                             mount-point))]
    (-register!
      conn id
      (fn []
        (swap! render-queue conj render-from-root)
        (when-not render-requested
          (set! render-requested true)
          (if (exists? js/requestAnimationFrame)
            (js/requestAnimationFrame render-all-queued)
            (js/setTimeout render-all-queued 16)))))
    (render-from-root @conn)))
