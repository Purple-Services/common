(ns common.subscriptions
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [clj-time.format :as time-format]
            [overtone.at-at :as at-at]
            [common.config :as config]
            [common.db :refer [conn mysql-escape-str !select !update]]
            [common.users :as users]
            [common.util :refer [split-on-comma rand-str-alpha-num
                                 coerce-double time-zone]]))

;;;;
;;;; Payments
;;;;
;; Initial Payment
;; make sure can't have initial payment unless legitimate need to pay
;;
;; Auto-renew Payment
;; auto-renew

;;;;
;;;; "users" table
;;;;
;; "subscription_id"
;;  0 - None
;;  1 - "Regular membership"
;;  2 - "Premium membership"
;;  3 - "B2B unlimited deliveries"
;;
;; "subscription_expiration_time"
;; e.g., 123456789
;;   - timestamp exactly 30 days from time of initial payment, and pushed forward 30 days every auto-renew
;;     - get current day number and round up to midnight then add 30 days, so it expires/auto-renews at midnight on the 31st day (PST)
;;
;; "subscription_period_start_time"
;;
;;
;; "subscription_auto_renew"
;; true or false
;;
;; "subscription_payment_log" - edn
;; [{:paid true
;;   :stripe_charge_id ch_6473812674826374832
;;   :stripe_customer_id_charged cus_87d7d87d8dd
;;   :stripe_balance_transaction_id txf878d97ffdffffff
;;   :time_paid 1235612223
;;   :amount_paid 1499}]

;;;;
;;;; "subscriptions" table
;;;;  holds definitions of each type of subscription
;;;;
;; "id" - e.g.,                           1
;; "name" - e.g.,                         "Regular Membership"
;; "price" - e.g.,                        799
;; "period" - e.g.,                       2592000
;; "num_free_one_hour" - e.g.,            0
;; "num_free_three_hour" - e.g.,          3
;; "num_free_tire_pressure_check" - e.g., 1
;; "discount_one_hour" - e.g.,            -200 (save $2 for orders after num_free_one_hour)
;; "discount_three_hour" - e.g.,          -455

;;;;
;;;; "orders" table
;;;;
;; "tire_pressure_check" - true or false (whether or not courier is supposed to do a tire pressure check)
;; "subscription_id" - subscription id that was used on this order - e.g., 1 - i.e., the subscription_id that the user had at the time they made the order
;; "subscription_discount" - the total discount due to the subscription used (if any) - e.g., -200 OR e.g., -399 (one of the free deliveries)


;; availability check logic changes
;; check if user has a current subscription that is not expired
;;   if so,
;;      get that subscription from "subscriptions" table to get its details
;;      SELECT from "orders" table to determine the remaining num_free_* counts
;;        e.g., SELECT tire_pressure_check
;;              WHERE status=complete AND
;;              subscription_id = users.subscription_id AND
;;              timestamp_created BETWEEN [subscription_expiration_time - [subscription's period (e.g., 2592000)]], // in "e.g.,": number of seconds in 30 days (susceptible to a DST bug? (probably 1 hour off at worst))
;;                                        [subscription_expiration_time]
;;          we check for where subscription_id and compare to current user subscription_id to naturally handle the case of a subscription upgrade or downgrade
;;
;; NOTES: fine to grab this info dynamically as described above, or should keep counters in their own table or this could be a map of some sort in the "users" table?

;; ? any error if they buy a subscription before their first order and then use a coupon code on their order

;; need some way to hide 3 hour orders 



(defn get-by-id
  "Get a subscription from DB given its ID."
  [db-conn id]
  (first (!select db-conn "subscriptions" ["*"] {:id id})))

(defn get-by-ids
  "Get subscriptions from DB given their IDs."
  [db-conn ids]
  (!select db-conn "subscriptions" ["*"]
           {}
           :custom-where
           (str "id IN (" (s/join "," ids) ")")))

(defn get-of-user
  "Get the subscription that the user is subscribed to. (nil if not subscribed)"
  [db-conn user]
  (get-by-id db-conn (:subscription_id user)))

(defn valid-subscription?
  "User's subscription is not expired?"
  [user]
  (> (or (:subscription_expiration_time user) 0)
     (quot (System/currentTimeMillis) 1000)))

(defn get-usage
  "Get a map of the usage and allowance of the subscription for current period."
  [db-conn user]
  (when-let [subscription (get-of-user db-conn user)]
    (when (valid-subscription? user)
      (merge (select-keys subscription [:num_free_one_hour :num_free_three_hour
                                        :num_free_tire_pressure_check
                                        :discount_one_hour :discount_three_hour])
             (reduce
              (fn [a b]
                (let [target-time-diff (- (:target_time_end b) (:target_time_start b))]
                  (cond-> a
                    (= target-time-diff (* 60 60 1))
                    (update :num_free_one_hour_used inc)

                    (= target-time-diff (* 60 60 3))
                    (update :num_free_three_hour_used inc)

                    (:tire_pressure_check b)
                    (update :num_free_tire_pressure_check_used inc))))
              {:num_free_one_hour_used 0
               :num_free_three_hour_used 0
               :num_free_tire_pressure_check_used 0}
              (!select db-conn "orders"
                       [:target_time_start :target_time_end :tire_pressure_check]
                       {}
                       :custom-where
                       (str "user_id = \"" (mysql-escape-str (:id user)) "\""
                            ;; might as well check subscription id, though it is implied
                            " AND subscription_id = " (:id subscription)
                            " AND status != \"cancelled\""
                            " AND target_time_start > "
                            (:subscription_period_start_time user))))))))

;; (get-usage (conn) (users/get-user-by-id (conn) "z5kZavElDQPcmlYzxYLr"))

(defn update-payment-log
  "Update the subscription payment log for this user with a new charge."
  [db-conn
   user    ;; user map
   charge] ;; "charge object" map from Stripe response
  (!update db-conn "users"
           {:subscription_payment_log
            (str   ;; append new entry to existing payment log
             (conj (or (edn/read-string (:subscription_payment_log user)) [])
                   (-> charge
                       (select-keys [:amount :created :captured :id :customer
                                     :balance_transaction :source])
                       (update-in [:source] select-keys [:exp_month :exp_year
                                                         :id :brand :last4]))))}
           {:id (:id user)}))

;; Round up to midnight tonight locally, then add the 'period' num seconds.
(defn calculate-expiration-time
  "Calculate the Unix timestamp for when the subscription should expire."
  [period]
  (+ (/ (time-coerce/to-long (org.joda.time.DateMidnight/now time-zone)) 1000)
     (* 60 60 24) ;; now we're at local midnight for tonight
     period))

(defn set-auto-renew
  "Set auto-renew on (true) or off (false) for a user given user-id."
  [db-conn user-id value]
  (!update db-conn
           "users"
           {:subscription_auto_renew value}
           {:id user-id}))

(defn gen-charge-description
  [subscription auto-renew?]
  (str "Membership Level: " (:name subscription) (when auto-renew?
                                                   " (renewal)")))

(defn charge-and-update-subscription
  "Charge a user for a subscription period."
  [db-conn user subscription & {:keys [auto-renew?]}]
  (let [charge-result
        (users/charge-user db-conn
                           (:id user)
                           (:price subscription)
                           (gen-charge-description subscription auto-renew?)
                           (rand-str-alpha-num 50) ;; idempotency key
                           :metadata {:subscription_id (:id subscription)
                                      :user_id (:id user)
                                      :is_auto_renew (boolean auto-renew?)})]
    (update-payment-log db-conn user (:charge charge-result))
    (if (:success charge-result)
      (!update db-conn
               "users"
               {:subscription_id (:id subscription)
                :subscription_expiration_time (calculate-expiration-time
                                               (:period subscription))
                ;; this is different from expiration_time - the period,
                ;; because expiration time is set relative to midnight tonight
                :subscription_period_start_time (quot (System/currentTimeMillis)
                                                      1000)
                :subscription_auto_renew true}
               {:id (:id user)})
      {:success false
       :message (str "Sorry, we were unable to charge your credit card. "
                     "Please go to the \"Account\" page and tap on "
                     "\"Payment Method\" to add a new card.")
       :message_title "Unable to Charge Card"})))

(defn subscribe
  "Suscribe a user to certain subscription."
  [db-conn user-id subscription-id]
  (if-let [subscription (get-by-id db-conn subscription-id)]
    (charge-and-update-subscription db-conn
                                    (users/get-user-by-id db-conn user-id)
                                    subscription)
    {:success false
     :message "That subscription ID does not exist."}))

(defn renew
  "Try to renew a user's subscription."
  [db-conn user]
  (charge-and-update-subscription db-conn
                                  user
                                  (get-of-user db-conn user)
                                  :auto-renew? true))

;; (subscribe (conn) "z5kZavElDQPcmlYzxYLr" 2)
;; (set-auto-renew (conn) "3N4teHdxCpqNcFzSnpKY" true)
;; (renew (conn) (users/get-user-by-id (conn) "3N4teHdxCpqNcFzSnpKY"))

;; (def job-pool (at-at/mk-pool))
