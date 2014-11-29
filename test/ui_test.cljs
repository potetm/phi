(ns ui-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [phi.core :as phi :refer-macros [add-subscriber]]
            [dropdown :as d]
            [cljs.core.async :as a :refer [<!]]))

(enable-console-print!)

(phi/init-conn!
  (atom
    (merge {}
           {:dropdown-a d/initial-state
            :dropdown-b d/initial-state})))

(phi/start-debug-events!)
(phi/start-debug-conn!)

(defn search-for-items-a [{{:keys [value]} :message}]
  (go (<! (a/timeout 500))
      (swap! phi/conn assoc-in [:dropdown-a :dropdown-items]
             [{:id 1
               :value (str value " Test 1")}
              {:id 2
               :value (str value " Test 2")}
              {:id 3
               :value (str value " Test 3")}
              {:id 4
               :value (str value " Test 4")}])))

(defn search-for-items-b [{{:keys [value]} :message}]
  (go (<! (a/timeout 100))
      (swap! phi/conn assoc-in [:dropdown-b :dropdown-items]
             [{:id 5
               :value (str value " Test 5")}
              {:id 6
               :value (str value " Test 6")}
              {:id 7
               :value (str value " Test 7")}
              {:id 8
               :value (str value " Test 8")}])))

(phi/routing-table
  (a/sliding-buffer 10)
  [[:dropdown-a/input-changed] search-for-items-a
   [:dropdown-b/input-changed] search-for-items-b])

(def dropdown-a (d/component "dropdown-a" [:dropdown-a]))
(def dropdown-b (d/component "dropdown-b" [:dropdown-b]))

(def app
  (phi/component
    (reify
      phi/IPhi
      (render [_ db]
        [:div
         (dropdown-a db)
         (dropdown-b db)]))))

(phi/mount-app app (.-body js/document))
