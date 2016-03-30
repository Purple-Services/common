(ns common.zones
  (:require [clojure.string :as s]
            [common.db :refer [!select conn]]
            [common.util :refer [five-digit-zip-code in?
                                 split-on-comma]]))

(defn process-zones
  "Process a col of zones for use on the server"
  [zones]
  (map #(update-in % [:zip_codes] split-on-comma) zones))

(defn get-all-zones-from-db
  "Get all zones from the database."
  [db-conn]
  (!select db-conn "zones" ["*"] {}))

(defn get-zones
  "Get the all zones from the database and process them."
  [db-conn]
  (process-zones (get-all-zones-from-db db-conn)))

(defn get-zone-by-zip-code
  "Given a zip code, return the corresponding zone."
  [zip-code]
  (let [zip-code (five-digit-zip-code zip-code)]
    (-> (!select (conn) "zones" ["*"] {}
                 :custom-where
                 (str "`zip_codes` LIKE '%" zip-code "%'"))
        process-zones
        first)))

(defn order->zone-id
  "Determine which zone the order is in; give the zone id."
  [order]
  (-> (get-zone-by-zip-code (:address_zip order))
      :id))

(defn zip-in-zones?
  "Determine whether or not zip-code can be found in zones."
  [zip-code]
  (boolean (get-zone-by-zip-code zip-code)))

(defn get-fuel-prices
  "Given a zip code, return the fuel prices for that zone."
  [zip-code]
  (let [putative-zone (get-zone-by-zip-code zip-code)]
    (when putative-zone
      (-> putative-zone
          :fuel_prices
          (read-string)))))

(defn get-service-fees
  "Given a zip-code, return the service fees for that zone."
  [zip-code]
  (let [putative-zone (get-zone-by-zip-code zip-code)]
    (when putative-zone
      (-> putative-zone
          :service_fees
          (read-string)))))

(defn get-service-time-bracket
  "Given a zip-code, return the service time bracket for that zone."
  [zip-code]
  (let [putative-zone (get-zone-by-zip-code zip-code)]
    (when putative-zone
      (-> putative-zone
          :service_time_bracket
          (read-string)))))

;; This is only considering the time element. They could be disallowed
;; for other reasons.
(defn get-one-hour-orders-allowed
  "Given a zip-code, return the time in minutes that one hour orders are
  allowed."
  [zip-code]
  (let [putative-bracket (get-service-time-bracket zip-code)]
    (when putative-bracket
      (-> putative-bracket
          first
          (+ 0) ;; for 1 1/2 hour delay: (+ 90)
          ))))

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
                              (get-zones db-conn))
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
