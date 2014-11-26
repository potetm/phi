(ns phi.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [chan <!] :as a]
            [sablono.core :refer-macros [html]])
  (:import [goog.ui IdGenerator]))

;; Initialize

(declare conn)

;; Events

(defonce ^:private subscribers-map (atom {}))
(defonce ^:private publisher (chan 1024))
(defonce ^:private publication (a/pub publisher :type))

(defrecord Event [id type message])

(defn- gen-id []
  (.getNextUniqueId (.getInstance IdGenerator)))

;; Public API

(defn event [type message]
  (Event. (gen-id) type message))

(defn unsubscribe! [chan-key]
  (when-let [{:keys [chan topics]} (get @subscribers-map chan-key)]
    (doseq [topic topics]
      (a/unsub publication topic chan))
    (swap! subscribers-map dissoc chan-key)))

(defn subscribe! [ch chan-key event-types]
  (doseq [event-type event-types]
    (a/sub publication event-type ch true))
  (swap! subscribers-map assoc chan-key {:chan ch, :topics event-types}))

(defn subscribe-callback! [chan-key buf-or-n event-types callback]
  (let [c (chan buf-or-n)]
    (unsubscribe! chan-key)
    (subscribe! c chan-key event-types)
    (go-loop []
      (when-some [v (<! c)]
        (try
          (callback v)
          (catch js/Error e
            (js/console.error
              (clj->js
                {:message "Error executing subscriber"
                 :chan-key chan-key
                 :buf-or-n buf-or-n
                 :event-types event-types
                 :value v
                 :error e}))))
        (recur)))))

(defn subscription-table
  ([buf-or-n desc]
    (subscription-table
      (mapcat
        (fn [[events f]]
          [events buf-or-n f])
        (partition 2 desc))))
  ([desc]
    (doall
      (for [[events buf-or-n f] (partition 3 desc)
            ;; gensym to guarantee uniqueness; .-name to give useful feedback
            :let [n (.-name f)
                  chan-key (keyword (str (gensym))
                                    (if (seq n) n "anonymous-fn"))]]
        (do
          (subscribe-callback! chan-key buf-or-n events f)
          chan-key)))))

(defn publish! [^Event e]
  {:pre [(instance? Event e)]}
  (a/put! publisher e))

;; Helpers

(defn get-ref [component ref]
  (aget (.-refs component) ref))

(defn get-dom-node [component]
  (.getDOMNode component))

(defn get-child-node [component ref]
  (get-dom-node (get-ref component ref)))

(defn mounted? [component]
  (.isMounted component))

;; React Lifecycle protocols (obviously borrowed from om)

(defprotocol IDisplayName
  (display-name [this component]))

(defprotocol IShouldUpdate
  (should-update? [this component next-props next-db]))

(defprotocol IWillMount
  (will-mount [this component]))

(defprotocol IDidMount
  (did-mount [this component]))

(defprotocol IWillUnmount
  (will-unmount [this component]))

(defprotocol IWillUpdate
  (will-update [this component next-props next-db]))

(defprotocol IDidUpdate
  (did-update [this component prev-props prev-db]))

;; My additions

(defprotocol IPhi
  "The bare minimum protocol you must implement
   to be a phi component."
  (render [this db]
          [this db props]))

(defprotocol IPhiProps
  "The other protocol you may implement
   to be a phi component."
  (render-props [this props]))

(defprotocol ISubscribe
  (init-subscribers [this]
                    [this props]))

(defprotocol IGetSubscriberKeys
  (-get-subscriber-keys [this]))

(defprotocol ISetSubscriberKeys
  (-set-subscriber-keys [this subscribers]))

(defprotocol IGetDb
  (-get-db [this]))

(defprotocol ISetDb
  (-set-db [this db]))

(defprotocol IGetProps
  (-get-props [this]))

(defprotocol IGetDispatcher
  (-get-dispatcher [this]))

(defprotocol ITxNotify
  (-register! [this key callback])
  (-unregister! [this key]))

(defprotocol IUpdateImmediately
  "Marker protocol for indicating you want this component
   to be updated immediately after each db update.

   i.e. it will updated outside of js/requestAnimationFrame.

   Use sparingly.")

(def ^:private update-immediately (atom #{}))

(def ^:private lifecycle-methods
  {:getDisplayName
   (fn []
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IDisplayName d)
           (display-name d this)))))
   :shouldComponentUpdate
   (fn [next-props _next-state]
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (cond
           (satisfies? IShouldUpdate d) (should-update? d this next-props (aget next-props "__phi_db"))
           (satisfies? IPhiProps d) (not= (-get-props this) (aget next-props "__phi_props"))
           :else true))))
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
         (when (satisfies? IUpdateImmediately d)
           (swap! update-immediately conj this))
         (when (satisfies? ISubscribe d)
           (let [props (-get-props this)
                 sub-keys (if (seq props)
                            (init-subscribers d props)
                            (init-subscribers d))]
             (-set-subscriber-keys this sub-keys)))
         (when (satisfies? IDidMount d)
           (did-mount d this)))))
   :componentWillUnmount
   (fn []
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IUpdateImmediately d)
           (swap! update-immediately disj this))
         (when (satisfies? ISubscribe d)
           (doseq [chan-key (-get-subscriber-keys this)]
             (unsubscribe! chan-key)))
         (when (satisfies? IWillUnmount d)
           (will-unmount d this)))))
   :componentWillUpdate
   (fn [next-props _next-state]
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IWillUpdate d)
           (will-update d this (aget next-props "__phi_props") (aget next-props "__phi_db"))))))
   :componentDidUpdate
   (fn [prev-props _prev-state]
     (this-as
       this
       (let [d (-get-dispatcher this)]
         (when (satisfies? IDidUpdate d)
           (did-update d this (aget prev-props "__phi_props") (aget prev-props "__phi_db"))))))
   :render
   (fn []
     (this-as
       this
       (let [d (-get-dispatcher this)
             props (-get-props this)]
         (html
           (if (satisfies? IPhiProps d)
             (render-props d props)
             (if (empty? props)
               (render d (-get-db this))
               (render d (-get-db this) props)))))))})

(defn- specify-state-methods! [desc]
  (specify! desc
    IGetDb
    (-get-db
      ([this]
        (aget (.-props this) "__phi_db")))
    ISetDb
    (-set-db
      [this db]
      (aset (.-props this) "__phi_db" db))
    IGetProps
    (-get-props
      ([this]
        (aget (.-props this) "__phi_props")))
    IGetDispatcher
    (-get-dispatcher
      ([this]
        (aget this "__phi_dispatcher")))
    IGetSubscriberKeys
    (-get-subscriber-keys
      ([this]
        (aget this "__phi_subscriber_keys")))
    ISetSubscriberKeys
    (-set-subscriber-keys
      [this subs]
      (aset this "__phi_subscriber_keys" subs))))

(deftype Conn [conn]
  IMeta
  (-meta [_]
    (meta conn))
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
    (add-watch conn key
      (fn [_key _conn old-state new-state]
        (callback {:db-before old-state
                   :db-after new-state}))))
  (-unregister! [_ key]
    (remove-watch conn key)))

(defonce ^:private cleanup-fns (atom {}))
(defonce ^:private render-queue (atom #{}))
(def ^:private render-requested false)

(defn- render-all-queued []
  (set! render-requested false)
  (let [render-fns (loop [queue @render-queue]
                     (if (compare-and-set! render-queue queue #{})
                       queue
                       (recur @render-queue)))
        db @conn]
    (doseq [render render-fns]
      (render db))))

;; Public API

(defn component [d]
  (let [c (js/React.createClass
            (specify-state-methods!
              (clj->js
                (merge
                  lifecycle-methods
                  {:__phi_dispatcher d}))))]
    (if (satisfies? IPhiProps d)
      (fn [props]
        (c
          #js {:__phi_props props}))
      (fn [db & [props]]
        (c
          #js {:__phi_db db
               :__phi_props props})))))

(defn init-conn! [new-conn]
  (defonce conn (Conn. new-conn)))

(defn mount-app [app mount-point]
  (when (undefined? conn)
    (js/console.debug "Attempt to mount app before calling init-conn!"))
  (let [id (gen-id)
        render-from-root (fn [db]
                           (js/React.renderComponent
                             (app db)
                             mount-point))]
    (-register!
      conn id
      (fn [tx-report]
        (doseq [c @update-immediately]
          (-set-db c (:db-after tx-report))
          (.forceUpdate c))
        ;; this pattern is basically yanked from om
        (swap! render-queue conj render-from-root)
        (when-not render-requested
          (set! render-requested true)
          (if (exists? js/requestAnimationFrame)
            (js/requestAnimationFrame render-all-queued)
            (js/setTimeout render-all-queued 16)))))
    (swap! cleanup-fns assoc mount-point
           (fn []
             (-unregister! conn id)
             (swap! cleanup-fns dissoc mount-point)
             (js/React.unmountComponentAtNode mount-point)))
    (render-from-root @conn)))

(defn unmount-app [mount-point]
  (when-let [f (get @cleanup-fns mount-point)]
    (f)))
