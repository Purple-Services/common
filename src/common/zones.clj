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

(defn get-zone-by-zip-code
  "Given a zip code, return the corresponding zone."
  [zip-code]
  (-> (filter #(= (:id %) (order->zone-id {:address_zip zip-code})) @zones)
      first))

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
