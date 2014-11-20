(ns phi.core)

(defmacro add-subscriber [chan-name buf-or-n event-types & body]
  `(let [ch# (cljs.core.async/chan ~buf-or-n)
         chan-key# ~(keyword (str *ns*) (name chan-name))]
     (phi.core/unsubscribe! chan-key#)
     (phi.core/subscribe! ch# chan-key# ~event-types)
     (let [~chan-name ch#]
       (try
         ~@body
         (catch js/Error e#
           (phi.core/unsubscribe! chan-key#)
           (js/console.error
             (ex-info
               "exception thrown while executing subscriber body"
               {:subscriber chan-key#
                :buf-or-n ~buf-or-n
                :event-types ~event-types}
               e#)))))
     chan-key#))
