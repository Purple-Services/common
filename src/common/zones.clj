(ns common.zones
  (:require [clojure.string :as s]
            [common.db :refer [!select]]
            [common.util :refer [five-digit-zip-code in?
                                 split-on-comma]]))

(def zones (atom nil))

(defn get-all-zones-from-db
  "Get all zones from the database."
  [db-conn]
  (!select db-conn "zones" ["*"] {}))

(defn order->zone-id
  "Determine which zone the order is in; gives the zone id."
  [order]
  (let [zip-code (five-digit-zip-code (:address_zip order))]
    (:id (first (filter #(in? (:zip_codes %) zip-code)
                        @zones)))))

;; moved from dispatch.clj
(defn get-zone-by-zip-code
  "Given a zip code, return the corresponding zone."
  [zip-code]
  (-> (filter #(= (:id %) (order->zone-id {:address_zip zip-code})) @zones)
      first))

(defn get-fuel-prices
  "Given a zip code, return the fuel prices for that zone."
  [zip-code]
  (-> zip-code
      (get-zone-by-zip-code)
      :fuel_prices
      (read-string)))

(defn get-service-fees
  "Given a zip-code, return the service fees for that zone."
  [zip-code]
  (-> zip-code
      (get-zone-by-zip-code)
      :service_fees
      (read-string)))

(defn get-service-time-bracket
  "Given a zip-code, return the service time bracket for that zone."
  [zip-code]
  (-> zip-code
      (get-zone-by-zip-code)
      :service_time_bracket
      (read-string)))

;; This is only considering the time element. They could be disallowed
;; for other reasons.
(defn get-one-hour-orders-allowed
  "Given a zip-code, return the time in minutes that one hour orders are
  allowed."
  [zip-code]
  (-> zip-code
      (get-service-time-bracket)
      first
      (+ 90)))

(defn courier-assigned-zones
  "Given a courier-id, return a set of all zones they are assigned to"
  [db-conn courier-id]
  (let [zones (:zones (first
                       (!select db-conn
                                "couriers"
                                [:zones]
                                {:id courier-id})))]
    (if (nil? (seq zones))
      (set zones) ; the empty set
      (set
       (map read-string
            (split-on-comma zones))))))

(defn get-courier-zips
  "Given a courier-id, get all of the zip-codes that a courier is assigned to"
  [db-conn courier-id]
  (let [courier-zones (filter #(contains?
                                (courier-assigned-zones db-conn courier-id)
                                (:id %))
                              @zones)
        zip-codes (apply concat (map :zip_codes courier-zones))]
    (set zip-codes)))

(defn get-zctas-for-zips
  "Given a string of comma-seperated zips and db-conn, return a list of
  zone/coordinates maps."
  [db-conn zips]
  (let [in-clause (str "("
                       (s/join ","
                               (map #(str "'" % "'")
                                    (split-on-comma zips)))
                       ")")]
    (!select db-conn "zctas" ["*"] {}
             :custom-where (str "zip in " in-clause))))
