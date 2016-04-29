(ns common.subscriptions
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [common.config :as config]
            [common.db :refer [conn mysql-escape-str !select]]
            [common.users :as users]
            [common.util :refer [split-on-comma rand-str coerce-double]]))

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



(defn get-subscription-by-id
  "Get a subscription from DB given its ID."
  [db-conn id]
  (first (!select db-conn "subscriptions" ["*"] {:id id})))

(defn get-subscription-of-user
  "Get the subscription that the user is subscribed to. (nil if not subscribed)"
  [db-conn user]
  (get-subscription-by-id (:subscription_id user)))



(defn subscribe-user
  [db-conn user-id subscription-id]
  (let [subscription  (get-subscription-by-id subscription-id)
        charge-result (users/charge-user db-conn
                                         user-id
                                         (:price subscription)
                                         "Subscription Payment 1.12.2016"
                                         ;; need idempotency-key here
                                         :metadata {:subscription_id subscription-id
                                                    :user_id user-id})]
    ;; update payment log
    (if (:success charge-result)
      ;; update user subscription expiration and id and etc.)
  
  )

(subscribe-user )
