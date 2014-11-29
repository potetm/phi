(ns dropdown
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [phi.core :as phi]
            [cljs.core.async :as a :refer [<!]]))

(def initial-state
  {:input-text ""
   :dropdown-state :closed
   :dropdown-items []})

(defn get-items [db path]
  (map-indexed
    (fn [index item]
      (assoc item :index index))
    (get-in db (conj path :dropdown-items))))

(defn get-selected [db path]
  (first
    (filter
      :selected
      (get-items db path))))

(defn set-dropdown-state [db path state]
  {:pre [(#{:open :closed} state)]}
  (assoc-in db (conj path :dropdown-state) state))

(defn clear-selected [db path]
  (let [selected-index (:index (get-selected db path))]
    (if selected-index
      (update-in
        db (concat path [:dropdown-items selected-index])
        assoc :selected false)
      db)))

(defn set-selected [db path index]
  (-> db
      (clear-selected path)
      (update-in (concat path [:dropdown-items index])
                 assoc :selected true)))

(defn set-selected-prev [db path]
  (let [selected-index (:index (get-selected db path))
        last-index (dec (count (get-items db path)))]
    (set-selected db path (if (or (nil? selected-index)
                                  (zero? selected-index))
                            last-index
                            (dec selected-index)))))

(defn set-selected-next [db path]
  (let [selected-index (:index (get-selected db path))
        last-index (dec (count (get-items db path)))]
    (set-selected db path (if (or (nil? selected-index)
                                  (= selected-index last-index))
                            0
                            (inc selected-index)))))

(defn get-input-text [db path]
  (get-in db (conj path :input-text)))

(defn set-input-text [db path value]
  (let []
    (-> db
        (assoc-in (conj path :input-text) value)
        (clear-selected path)
        (set-dropdown-state path :open))))

(defn get-dropdown-state [db path]
  (get-in db (conj path :dropdown-state)))

(defn set-item-chosen [db path]
  (-> db
      (set-input-text path (:value (get-selected db path)))
      (set-dropdown-state path :closed)))

(defn component [event-prefix state-path]
  (let [input-changed-event (keyword event-prefix "input-changed")
        item-selected-event (keyword event-prefix "item-selected")
        item-selected-prev-event (keyword event-prefix "item-selected-prev")
        item-selected-next-event (keyword event-prefix "item-selected-next")
        item-chosen-event (keyword event-prefix "item-chosen")]
    (phi/component
      (reify
        phi/IPhi
        (render [_ db]
          (let [input-text (get-input-text db state-path)
                items (get-items db state-path)
                dropdown-state (get-dropdown-state db state-path)]
            [:div
             [:input
              {:ref :input
               :type :text
               :value input-text
               :on-change #(phi/publish! input-changed-event {:value (.. % -target -value)})
               :on-key-down
               (fn [e]
                 (let [code (.-which e)]
                   (when (#{38 40 13} code)
                     (phi/publish!
                       (condp = code
                         38 item-selected-prev-event
                         40 item-selected-next-event
                         13 item-chosen-event)
                       {:db @phi/conn}))))}]
             (when (= :open dropdown-state)
               [:ol
                (map
                  (fn [{:keys [id index value selected]}]
                    [:li
                     {:key id
                      :class (if selected "selected" "")
                      :style {:background-color (if selected "blue" "white")}
                      :on-mouse-over #(phi/publish! item-selected-event {:index index})
                      :on-click #(phi/publish! item-chosen-event {:db @phi/conn})}
                     value])
                  items)])]))
        phi/ISubscribe
        (init-subscribers [_]
          (phi/routing-table
            (a/sliding-buffer 10)
            [[input-changed-event] (fn _input-changed [{{:keys [value]} :message}]
                                     (swap! phi/conn set-input-text state-path value))
             [item-selected-event] (fn _item-selected [{{:keys [index]} :message}]
                                     (swap! phi/conn set-selected state-path index))
             [item-chosen-event] (fn _item-chosen [_]
                                   (swap! phi/conn set-item-chosen state-path))
             [item-selected-prev-event] (fn _item-select-prev []
                                          (swap! phi/conn set-selected-prev state-path))
             [item-selected-next-event] (fn _item-select-next []
                                          (swap! phi/conn set-selected-next state-path))]))))))
