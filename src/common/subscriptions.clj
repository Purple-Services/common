(ns common.subscriptions
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [clj-time.format :as time-format]
            [common.config :as config]
            [common.db :refer [conn mysql-escape-str !select !update]]
            [common.users :as users]
            [common.util :refer [split-on-comma rand-str-alpha-num
                                 coerce-double time-zone cents->dollars
                                 segment-client]]
            [ardoq.analytics-clj :as segment]))

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

(defn get-with-usage
  "Get a map of the usage and allowance of the subscription for current period."
  [db-conn user]
  (when-let [subscription (get-of-user db-conn user)]
    (when (valid-subscription? user)
      (merge subscription
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
  (str "Membership Level: "
       (:name subscription)
       (when auto-renew? " (renewal)")))

(defn charge-and-update-subscription
  "Charge a user for a subscription period."
  [db-conn user subscription & {:keys [auto-renew?]}]
  (let [charge-result
        (users/charge-user db-conn
                           (:id user)
                           (:price subscription)
                           (gen-charge-description subscription auto-renew?)
                           ;; TODO perhaps instead of a random idempotency key
                           ;; it could be a hash of
                           ;; (calculate-expiration-time (:period subscription))
                           ;; concatenated with the subscription id.
                           ;; That way if the POST request was accidentally
                           ;; sent twice it will only use the first one.
                           (rand-str-alpha-num 50) ;; idempotency key
                           :metadata {:subscription_id (:id subscription)
                                      :user_id (:id user)
                                      :is_auto_renew (boolean auto-renew?)})]
    (update-payment-log db-conn user (:charge charge-result))
    (if (:success charge-result)
      (do (segment/track segment-client (:id user) "Subscription Payment"
                         {:subscription_id (:id subscription)
                          :is_auto_renew (boolean auto-renew?)
                          :amount (cents->dollars (:price subscription))
                          :revenue (cents->dollars (:price subscription))})
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
                   {:id (:id user)}))
      (do (segment/track segment-client (:id user) "Subscription Payment Failed"
                         {:subscription_id (:id subscription)
                          :is_auto_renew (boolean auto-renew?)
                          :amount (cents->dollars (:price subscription))})
          {:success false
           :message (str "Sorry, we were unable to charge your credit card. "
                         "Please go to the \"Account\" page and tap on "
                         "\"Payment Method\" to add a new card. Also, "
                         "ensure your email address is valid.")
           :message_title "Unable to Charge Card"}))))

(defn subscribe
  "Suscribe a user to certain subscription."
  [db-conn user-id subscription-id]
  (if-let [subscription (get-by-id db-conn subscription-id)]
    (charge-and-update-subscription db-conn
                                    (users/get-user-by-id db-conn user-id)
                                    subscription)
    {:success false
     :message "That subscription ID does not exist."}))

(defn expire-subscription
  "Cause a subscription to be expired."
  [db-conn user-id]
  (!update db-conn
           "users"
           {:subscription_id 0
            :subscription_auto_renew false}
           {:id user-id}))

(defn renew
  "Try to renew a user's subscription."
  [db-conn user]
  (let [result (charge-and-update-subscription db-conn
                                               user
                                               (get-of-user db-conn user)
                                               :auto-renew? true)]
    (when-not (:success result)
      (expire-subscription db-conn (:id user))
      ;; send email saying the renew failed and that they have to
      ;; add a card then tap on the Subscribe button again
      )
    result))

(defn subs-that-expire-tonight
  "Users whose subscription will expire tonight."
  [db-conn]
  (!select db-conn
           "users"
           ["*"]
           {}
           :custom-where
           (str "subscription_id != 0 AND "
                "subscription_expiration_time < "
                ;; local midnight tonight + 65 minutes (1:05am)
                (+ (/ (time-coerce/to-long
                       (org.joda.time.DateMidnight/now time-zone))
                      1000)
                   (* 60 60 24)
                   (* 60 65)))))

;; every day at 4pm Pacific time
;;   try to renew all auto_renew=1 that are going to expire at midnight that day
;;   if charge fails, send user email to notify that their subscription will expire
;;     ? every time a payment method is added, check if a subscription charge needs to be retried
;;
;; ;; every day at 10:0pm Pacific time
;; ;;   set any users.subscription_id's to 0 that have users.subscription_expiration_time less than current time

(defn renew-subs-expiring-tonight
  [db-conn]
  (->> (subs-that-expire-tonight db-conn)
       (filter :subscription_auto_renew)
       (map (partial renew db-conn))))

(defn expire-all-old-subs
  [db-conn]
  (->> (subs-that-expire-tonight db-conn)
       (filter (complement :subscription_auto_renew))
       (map (comp (partial expire-subscription db-conn) :id))))
