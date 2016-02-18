(ns common.couriers
  (:require [clojure.string :as s]
            [common.db :refer [mysql-escape-str !select !update]]
            [common.util :refer [in? split-on-comma]]
            [common.zones :refer [get-courier-zips]]))

(defn process-courier
  "Process a courier"
  [courier]
  (assoc courier
         :zones (if (s/blank? (:zones courier))
                  ;; courier is not assigned to any zones, blank field
                  #{}
                  (->> (:zones courier)
                       split-on-comma
                       (map #(Integer. %))
                       set))
         :timestamp_created
         (/ (.getTime
             (:timestamp_created courier))
            1000)))

(defn get-couriers
  "Gets couriers from db. Optionally add WHERE constraints."
  [db-conn & {:keys [where]}]
  (map process-courier
       (!select db-conn "couriers" ["*"] (merge {} where))))

(defn all-couriers
  "All couriers."
  [db-conn]
  (get-couriers db-conn))

(def busy-statuses ["assigned" "accepted" "enroute" "servicing"])

(defn courier-busy?
  "Is courier currently working on an order?"
  [db-conn courier-id]
  (let [orders (!select db-conn
                        "orders"
                        [:id :status]
                        {:courier_id courier-id})]
    (boolean (some #(in? busy-statuses (:status %)) orders))))

(defn set-courier-busy
  [db-conn courier-id busy]
  (!update db-conn
           "couriers"
           {:busy busy}
           {:id courier-id}))

(defn update-courier-busy [db-conn courier-id]
  "Determine if courier-id is busy and toggle the appropriate state. A courier
is considered busy if there are orders that have not been completed or cancelled
and their id matches the order's courier_id"
  (let [busy? (courier-busy? db-conn courier-id)]
    (set-courier-busy db-conn courier-id busy?)))

(defn get-by-courier
  "Gets all of a courier's assigned orders."
  [db-conn courier-id]
  (let [courier-zip-codes  (get-courier-zips
                            db-conn courier-id)
        os (!select db-conn "orders" ["*"] {}
                    :custom-where
                    (str "(courier_id = \""
                         (mysql-escape-str courier-id)
                         "\" AND target_time_start > "
                         (- (quot (System/currentTimeMillis) 1000)
                            (* 60 60 24)) ;; 24 hours
                         ") "
                         "ORDER BY target_time_end DESC"))
        customer-ids (distinct (map :user_id os))
        customers (group-by :id
                            (!select db-conn
                                     "users"
                                     [:id :name :phone_number]
                                     {}
                                     :custom-where
                                     (str "id IN (\""
                                          (s/join "\",\"" customer-ids)
                                          "\")")))
        vehicle-ids (->> os
                         ;; TODO filter this to only include orders
                         ;; from past 24 hours maybe?
                         
                         (map :vehicle_id)
                         distinct)
        vehicles (group-by :id
                           (!select db-conn
                                    "vehicles"
                                    ["*"]
                                    {}
                                    :custom-where
                                    (str "id IN (\""
                                         (s/join "\",\"" vehicle-ids)
                                         "\")")))]
    (map #(assoc %
                 :customer
                 (first (get customers (:user_id %)))
                 :vehicle
                 (first (get vehicles (:vehicle_id %))))
         os)))
