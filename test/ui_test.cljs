(ns ui-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [phi.core :as phi :refer-macros [add-subscriber]]
            [cljs.core.async :as a :refer [<!]]))

(enable-console-print!)

(phi/init-conn! (atom {}))

(defn my-fn-1 [e]
  "my-fn-1")

(defn my-fn-2 [e]
  "my-fn-2")

(def app
  (phi/component
    (reify
      phi/IPhi
      (render [_ db]
        [:div "HELLO"])
      phi/ISubscribe
      (init-subscribers [_]
        (phi/subscription-table
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
    (phi/unmount-app (.-body js/document))
    #_(println @phi/subscribers-map))
