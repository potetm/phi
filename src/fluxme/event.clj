(ns fluxme.event)

(defmacro defsubscriber [chan-name buf-or-n event-types & body]
  `(let [ch# (cljs.core.async/chan ~buf-or-n)
         chan-key# ~(keyword (str *ns*) (name chan-name))]
     (blog.reactive-design.event/unsubscribe! chan-key#)
     (blog.reactive-design.event/subscribe! ~event-types chan-key# ch#)
     (let [~chan-name ch#]
       (try
         ~@body
         (catch js/Error e#
           (blog.reactive-design.event/unsubscribe! chan-key#)
           (js/console.error
             (ex-info
               "exception thrown while executing subscriber body"
               {:subscriber  chan-key#
                :buf-or-n    ~buf-or-n
                :event-types ~event-types}
               e#)))))))
