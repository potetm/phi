(ns phi.core)

(defmacro add-subscriber
  "Add a subscriber to the global publisher. Allows for more fine
   grained control than routing-table.

   chan-name - the symbol that will be bound to the subscribed channel
   buf-or-n - same buf-or-n used on cljs.core.async
   event-types - a vector of event types that will be subscribed to

   Returns the name of the chan-key stored in the subscriber-map.

   Example usage:

   (add-subscriber my-subscriber 10 [:my-event-type]
     (go-loop []
       (when-some [v (<! my-subscriber)]
         (... do stuff)
         (recur))"
  [chan-name buf-or-n event-types & body]
  `(let [ch# (cljs.core.async/chan ~buf-or-n)
         chan-key# ~(keyword (str (gensym)) (name chan-name))]
     (phi.core/unsubscribe! chan-key#)
     (phi.core/subscribe! ch# chan-key# ~event-types)
     (let [~chan-name ch#]
       (try
         ~@body
         (catch js/Error e#
           (phi.core/unsubscribe! chan-key#)
           (js/console.error
             (cljs.core/clj->js
               {:message "exception thrown while executing subscriber body"
                :chan-key chan-key#
                :buf-or-n ~buf-or-n
                :event-types ~event-types}))
           (js/console.error (.-stack e#)))))
     chan-key#))
