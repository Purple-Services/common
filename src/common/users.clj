(ns common.users
  (:require [clojure.set :refer [join]]
            [clojure.string :as s]
            [clojure.walk :refer [keywordize-keys]]
            [cheshire.core :refer [parse-string]]
            [ardoq.analytics-clj :as segment]
            [crypto.password.bcrypt :as bcrypt]
            [common.config :as config]
            [common.util :refer [in? only-prod segment-client send-email
                                 send-sms sns-publish sns-client log-error]]
            [common.db :refer [conn mysql-escape-str !select !update]]
            [common.couriers :as couriers]
            [common.payment :as payment]))

(def safe-authd-user-keys
  "The keys of a user map that are safe to send out to auth'd user."
  [:id :type :email :name :phone_number :referral_code
   :referral_gallons :is_courier])

(defn valid-session?
  [db-conn user-id token]
  (boolean (seq (!select db-conn "sessions" [:id]
                         {:user_id user-id :token token}))))

(defn get-user
  "Gets a user from db. Optionally add WHERE constraints."
  [db-conn & {:keys [where]}]
  (first (!select db-conn "users" ["*"] (merge {} where))))

;; probably should be renamed to "get-by-id", like some of the other namespaces
;; since we are already contextualized in "users"
(defn get-user-by-id
  "Gets a user from db by user-id."
  [db-conn user-id]
  (get-user db-conn :where {:id user-id}))

(defn get-users-by-ids
  "Gets multiple users by a list of ids."
  [db-conn ids]
  (if (seq ids)
    (!select db-conn
             "users"
             ["*"]
             {}
             :custom-where
             (str "id IN (\""
                  (->> ids
                       (map mysql-escape-str)
                       (interpose "\",\"")
                       (apply str))
                  "\")"))
    []))

(defn include-user-data
  "Enrich a coll of maps that have :id's of users (e.g., couriers), with user
  data."
  [db-conn m]
  (join m
        (map #(select-keys % safe-authd-user-keys)
             (get-users-by-ids db-conn (map :id m)))
        {:id :id}))

(defn get-users-vehicles
  "Gets all of a user's vehicles."
  [db-conn user-id]
  (!select db-conn
           "vehicles"
           [:id :user_id :year :make :model :color :gas_type :license_plate
            :photo :timestamp_created]
           {:user_id user-id
            :active 1}))

(defn get-users-cards
  "We cache the card info as JSON in the stripe_cards column."
  [user]
  (let [default-card (:stripe_default_card user)]
    (map #(assoc % :default (= default-card (:id %)))
         (keywordize-keys (parse-string (:stripe_cards user))))))

(defn auth-native?
  "Is password correct for this user map?"
  [user auth-key]
  (bcrypt/check auth-key (:password_hash user)))

(defn valid-email?
  "Syntactically valid email address?"
  [email]
  (boolean (re-matches #"^\S+@\S+\.\S+$" email)))

(defn valid-password?
  "Only for native users."
  [password]
  (boolean (re-matches #"^.{6,100}$" password)))

(defn update-user-metadata
  [db-conn user-id app-version os]
  (do (segment/identify segment-client user-id
                        ;; TODO this is happening a lot
                        {:app_version app-version}) 
      (!update db-conn
               "users"
               (filter (comp not nil? val)
                       {:app_version app-version
                        :os os})
               {:id user-id})))

(defn get-by-user
  "Gets all of a user's orders."
  [db-conn user-id]
  (!select db-conn
           "orders"
           ["*"]
           {:user_id user-id}
           :append "ORDER BY target_time_start DESC"))

(defn details
  [db-conn user-id & {:keys [user-meta]}]
  (if-let [user (get-user-by-id db-conn user-id)]
    (do (when (and user-meta
                   (or (not= (:app_version user-meta) (:app_version user))
                       (not= (:os user-meta) (:os user))))
          (update-user-metadata db-conn
                                user-id
                                (:app_version user-meta)
                                (:os user-meta)))
        {:success true
         :user (assoc (select-keys user safe-authd-user-keys)
                      :has_push_notifications_set_up
                      (not (s/blank? (:arn_endpoint user))))
         :vehicles (into [] (get-users-vehicles db-conn user-id))
         :saved_locations (merge {:home {:displayText ""
                                         :googlePlaceId ""}
                                  :work {:displayText ""
                                         :googlePlaceId ""}}
                                 (parse-string (:saved_locations user) true))
         :cards (into [] (get-users-cards user))
         :orders (into [] (if (:is_courier user)
                            (couriers/get-by-courier db-conn (:id user))
                            (get-by-user db-conn (:id user))))
         :system {:referral_referred_value config/referral-referred-value
                  :referral_referrer_gallons config/referral-referrer-gallons}})
    {:success false
     :message "User could not be found."}))

(defn charge-user
  "Charges user amount (an int in cents) using default payment method."
  [db-conn user-id amount description idempotency-key
   & {:keys [metadata  ;; any metadata you want to include in the Stripe charge
             just-auth ;; don't capture the yet
             ]}]
  (let [user (get-user-by-id db-conn user-id)
        customer-id (:stripe_customer_id user)]
    (if (s/blank? customer-id)
      (do (log-error "Error auth'ing charge on user: no payment method is set up.")
          {:success false})
      (payment/charge-stripe-customer customer-id
                                      amount
                                      description
                                      (:email user)
                                      (not just-auth)
                                      idempotency-key
                                      metadata))))

(defn send-push
  "Sends a push notification to user."
  [db-conn user-id message]
  (let [user (get-user-by-id db-conn user-id)]
    (when-not (s/blank? (:arn_endpoint user))
      (sns-publish sns-client
                   (:arn_endpoint user)
                   message))
    {:success true}))

(defn text-user
  "Sends an SMS message to user."
  [db-conn user-id message]
  (let [user (get-user-by-id db-conn user-id)]
    (only-prod (send-sms (:phone_number user)
                         message))
    {:success true}))

(defn send-feedback
  [text & {:keys [user_id]}]
  (let [user (when user_id
               (get-user-by-id (conn) user_id))]
    (send-email {:to "chris@purpledelivery.com"
                 :cc (into []
                           (only-prod ["joe@purpledelivery.com"
                                       "bruno@purpledelivery.com"
                                       "rachel@purpledelivery.com"]))
                 :subject "Purple Feedback Form Response"
                 :body (if user
                         (str "From User ID: " user_id "\n\n"
                              "Name: " (:name user) "\n\n"
                              "Email: " (:email user) "\n\n"
                              text)
                         text)})))
