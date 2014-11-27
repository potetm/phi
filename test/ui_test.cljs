(ns ui-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [phi.core :as phi :refer-macros [add-subscriber]]
            [cljs.core.async :as a :refer [<!]]))

(enable-console-print!)

(phi/init-conn! (atom {}))
(phi/start-debug-events!)
(phi/start-debug-conn!)

(defn my-fn-1 [e]
  (swap! phi/conn assoc :id (phi/gen-id)))

(defn my-fn-2 [e]
  (println "my-fn-2"))

(def app
  (phi/component
    (reify
      phi/IPhi
      (render [_ db]
        [:div "HELLO"])
      phi/ISubscribe
      (init-subscribers [_]
        (phi/routing-table
          [[::event] (a/sliding-buffer 10) my-fn-1
           [::event] (a/sliding-buffer 10) my-fn-2])))))

(def wrap
  (phi/component
    (reify
      phi/IPhi
      (render [_ db]
        (app db)))))

(phi/mount-app wrap (.-body js/document))

(go (<! (a/timeout 1000))
    ;(println @phi/subscribers-map)
    (phi/publish! (phi/event ::event {}))
    (<! (a/timeout 1000))
    (phi/publish! (phi/event ::event {}))
    (<! (a/timeout 1000))
    (phi/stop-debug-events!)
    (phi/stop-debug-conn!)
    (phi/publish! (phi/event ::event {}))
    #_(println @phi/subscribers-map))
