(ns common.subscriptions
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [common.config :as config]
            [common.db :refer [mysql-escape-str !select]]
            [common.users :as users]
            [common.util :refer [split-on-comma rand-str coerce-double]]))


;; subscription payment

;; Initial Payment
;; make sure can't have initial payment unless legitimate need to pay

;; Auto-renew Payment
;; auto-renew



;; Users Table
;; "subscription_level"
;;  0 - None
;;  1 - "Regular membership"
;;  2 - "Premium membership"
;;  3 - "B2B unlimited deliveries"

;; "subscription_expiration_time"
;; 123456789
;;   - timestamp exactly 30 days from time of initial payment, and pushed forward 30 days every auto-renew
;;     - get current day number and round up to midnight then add 30 days, so it expires/auto-renews at midnight on the 31st day (PST)

;; "subscription_auto_renew"
;; true
;; false


;; subscription level definitions...

;; ?table? - subscriptions
;; max free 1 hours
;; max free 3 hours
;; 1 hour fee (for after max free used) (should this be a percentage or a discount instead? (watch out for negative amounts))
;; 3 hour fee


;; counters - reset to 0 every 
;; 1 hour orders
;; 3 hour orders
;; tire pressure check


;; on orders table row say which subscription was used?
;; ability to unuse a subscription counter if the order is canceled



(defn get-coupon-by-code
  "Get a coupon from DB given its code (e.g., GAS15)."
  [db-conn code]
  (first (!select db-conn
                  "coupons"
                  ["*"]
                  {:code code})))
