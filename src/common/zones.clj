(ns common.zones
  (:require [common.db :refer [!select conn]]
            [common.util :refer [five-digit-zip-code in? split-on-comma
                                 now-unix unix->minute-of-day
                                 minute-of-day->hmma unix->day-of-week]]
            [clojure.string :as s]
            [bouncer.core :as bouncer]
            [bouncer.validators :as v]))

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

     :market-id (if (= 100 (:zone-rank trans))
                  (:zone-id trans)
                  (:market-id base))

     :submarket-id (if (= 1000 (:zone-rank trans))
                     (:zone-id trans)
                     (:submarket-id base))

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
         (when hours
           (if (empty? (hours-today hours))
             "Sorry, this location is closed today. Thank you for your business."
             (str "Sorry, today's service hours for this location are "
                  (->> (hours-today hours)
                       (map #(str (minute-of-day->hmma (first %))
                                  " to "
                                  (minute-of-day->hmma (second %))))
                       (interpose " and ")
                       (apply str))
                  ". Thank you for your business."))))

     :manually-closed?
     (or (:manually-closed? trans) (:manually-closed? base))}))

(defn get-zones-with-zip
  "Get all the zone definitions that contain this ZIP and are active.
  Orders them by rank."
  [db-conn zip-code]
  (if-let [z (first (!select db-conn "zips" ["*"] {:zip zip-code}))]
    (map #(merge (read-string (:config %))
                 {:zone-id (:id %)
                  :zone-name (:name %)
                  :zone-rank (:rank %)})
         (!select db-conn "zones" ["*"] {}
                  :custom-where
                  (str "active = 1 AND "
                       "id IN (" (:zones z) ")" ; todo: sql injection?
                       "ORDER BY rank ASC")))
    nil))

(defn nil-if-invalid
  [zip-def]
  (when (bouncer/valid?
         zip-def
         :zone-names [v/required vector? (partial every? string?)]
         :zone-ids [v/required vector? (partial every? integer?)]
         :gallon-choices [v/required map?]
         :default-gallon-choice [v/required #(contains?
                                              (:gallon-choices zip-def) %)]
         [:gas-price "87"] [v/required integer? [v/in-range [0 5000]]]
         [:gas-price "91"] [v/required integer? [v/in-range [0 5000]]]
         :time-choices [v/required map?]
         ;; This isn't currently supported in the mobile app.
         ;; Also, the Dashboard currently allows the user to remove the 180 time
         ;; choice even though 180 is hardcoded as the default-time-choice.
         ;; :default-time-choice [v/required
         ;;                       #(in? (vals (:time-choices zip-def)) %)]
         :delivery-fee [v/required
                        (comp (partial every? integer?) keys)
                        (comp (partial every? integer?) vals)
                        (comp (partial every? #(v/in-range % [0 50000]))
                              vals)
                        ;; fee is defined for every time choice offered
                        #(every? (comp (partial contains? %) val)
                                 (:time-choices zip-def))]
         :tire-pressure-price [v/required integer? [v/in-range [0 50000]]]
         :hours [v/required
                 vector?
                 (partial every?
                          (partial every?
                                   #(and (vector? %)
                                         (= 2 (count %))
                                         (every? integer? %)
                                         (every? (fn [x]
                                                   (v/in-range x [0 1440]))
                                                 %)
                                         (<= (first %) (second %)))))]
         :closed-message [v/required string?]
         :one-hour-constraining-zone-id #(or (integer? %) (nil? %)))
    zip-def))

(defn get-zip-def-not-validated
  [db-conn zip-code]
  (reduce apply-trans
          {:zone-names [] ; starts with a fresh breadcrumb
           :zone-ids []} 
          (get-zones-with-zip db-conn zip-code)))

(defn get-zip-def
  "Get the ZIP definition after all transformations are applied.
  If not defined in any market, then nil."
  [db-conn zip-code] ; assumes zip-code is 5-digit version
  (nil-if-invalid (get-zip-def-not-validated db-conn zip-code)))

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

;;;; REPL snippet
;;;; Are there any zips that aren't fully defined?
;; (println
;;  (let [db-conn (conn)]
;;    (remove (comp (partial get-zip-def db-conn) :zip)
;;            (!select db-conn "zips" ["*"] {}))))
