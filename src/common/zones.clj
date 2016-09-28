(ns common.zones
  (:require [clojure.string :as s]
            [common.db :refer [!select conn]]
            [common.util :refer [five-digit-zip-code in? split-on-comma
                                 now-unix unix->minute-of-day
                                 minute-of-day->hmma unix->day-of-week]]))

(defn hours-today
  [hours]
  (nth hours (dec (unix->day-of-week (now-unix)))))

(defn apply-trans
  "Apply a transformation on a definition.
  (e.g., transform market definition with ZIP specific rules)."
  [base trans]
  (let [hours (or (:hours trans) (:hours base))]
    {:zone-names (conj (:zone-names base)
                       (:zone-name trans))

     ;; this makes a breadcrumb of all transformations applied
     ;; and is useful for determining if a courier is assigned to this zone
     :zone-ids (conj (:zone-ids base)
                     (:zone-id trans))

     :gallon-choices
     (or (:gallon-choices trans) (:gallon-choices base))
     
     :default-gallon-choice
     (or (:default-gallon-choice trans) (:default-gallon-choice base))
     
     :gas-price
     (if (:gas-price base)
       (into {}
             (for [[k v] (:gas-price base)
                   :let [diff-percent (or (get (:gas-price-diff-percent trans) k) 0)
                         diff-fixed (or (get (:gas-price-diff-fixed trans) k) 0)]]
               [k (if (get (:gas-price trans) k)
                    (get (:gas-price trans) k) ; static override
                    (Math/round (+ (double v) ; relative adjustment
                                   (* v (/ diff-percent 100))
                                   diff-fixed)))]))
       (:gas-price trans))
     
     :time-choices
     (or (:time-choices trans) (:time-choices base))
     
     :default-time-choice
     (or (:default-time-choice trans) (:default-time-choice base))

     ;; used to determine which zone we should check for the number of
     ;; one hour orders allowed at any point in time in that zone
     ;; compared to number of couriers online and EXPLICITLY assigned
     ;; to that zone
     :one-hour-constraining-zone-id
     (if (:constrain-num-one-hour? trans)
       (:zone-id trans)
       (if (:constrain-num-one-hour? base)
         (:zone-id base)
         (:one-hour-constraining-zone-id base)))
     
     :delivery-fee
     (if (:delivery-fee base)
       (into {}
             (for [[k v] (:delivery-fee base)
                   :let [diff-percent (or (get (:delivery-fee-diff-percent trans) k) 0)
                         diff-fixed (or (get (:delivery-fee-diff-fixed trans) k) 0)]]
               [k (if (get (:delivery-fee trans) k)
                    (get (:delivery-fee trans) k) ; static override
                    (Math/round (+ (double v) ; relative adjustment
                                   (* v (/ diff-percent 100))
                                   diff-fixed)))]))
       (:delivery-fee trans))
     
     :tire-pressure-price
     (or (:tire-pressure-price trans) (:tire-pressure-price base))

     :hours hours
     
     :closed-message
     (or (:closed-message trans)
         (:closed-message base)
         ;; TODO needs to handle empty hours better. maybe:
         ;; "Sorry, this ZIP is closed on Saturday and Sunday."
         (str "Sorry, today's service hours for this location are "
              (->> (hours-today hours)
                   (map #(str (minute-of-day->hmma (first %))
                              " to "
                              (minute-of-day->hmma (second %))))
                   (interpose " and ")
                   (apply str))
              ". Thank you for your business."))

     :manually-closed?
     (or (:manually-closed? trans) (:manually-closed? base))}))

(defn get-zones-with-zip
  "Get all the zone defitions that contain this ZIP and are active.
  Orders them by rank."
  [db-conn zip-code]
  (if-let [z (first (!select db-conn "zips" ["*"] {:zip zip-code}))]
    (map #(merge {:zone-id (:id %)
                  :zone-name (:name %)}
                 (read-string (:config %)))
         (!select db-conn "zones" ["*"] {}
                  :custom-where
                  (str "active = 1 AND "
                       "id IN (" (:zones z) ")"
                       "ORDER BY rank ASC")))
    nil))

(defn get-zip-def
  "Get the ZIP definition after all transformations are applied.
  If not defined in any market, then nil."
  [db-conn zip-code] ; assumes zip-code is 5-digit version
  (reduce apply-trans
          {:zone-names [] ; starts with a fresh breadcrumb
           :zone-ids []} 
          (get-zones-with-zip db-conn zip-code)))

;; (clojure.pprint/pprint (get-zip-def (conn) "91105"))

(defn is-open?
  [zip-def unix-time]
  (and (not (:manually-closed? zip-def))
       (some #(<= (first %)
                  (unix->minute-of-day unix-time)
                  (second %))
             (hours-today (:hours zip-def)))))

(defn is-open-now?
  "Is this ZIP open right now?"
  [zip-def]
  (is-open? zip-def (now-unix)))

(defn order->zones
  "Given an order map, get all the zones that it is within."
  [db-conn order]
  (:zone-ids (get-zip-def db-conn (:address_zip order))))

;; (defn get-zone-by-id
;;   [db-conn id]
;;   (first (!select db-conn "zones" ["*"] {:id id})))

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
