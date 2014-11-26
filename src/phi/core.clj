(ns phi.core)

(defmacro add-subscriber [chan-name buf-or-n event-types & body]
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
                :event-types ~event-types
                :error e#})))))
     chan-key#))
