(ns common.orders
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [ardoq.analytics-clj :as segment]
            [cheshire.core :refer [generate-string]]
            [clj-http.client :as client]
            [common.coupons :as coupons]
            [common.config :as config]
            [common.couriers :as couriers]
            [common.db :refer [mysql-escape-str conn !select !update !insert]]
            [common.payment :as payment]
            [common.users :as users]
            [common.subscriptions :as subscriptions]
            [common.zones :refer [get-zip-def is-open? order->zones]]
            [common.sift :as sift]
            [common.util :refer [cents->dollars cents->dollars-str in?
                                 gallons->display-str unix->DateTime
                                 minute-of-day->hmma
                                 rand-str-alpha-num coerce-double
                                 segment-client send-email send-sms
                                 unless-p only-prod only-prod-or-dev now-unix
                                 unix->fuller unix->full unix->minute-of-day
                                 compute-total-price]]))

;; Order status definitions
;; unassigned - not assigned to any courier yet
;; assigned   - assigned to a courier (usually we are skipping over this status)
;; accepted   - courier accepts this order as their current task (can be forced)
;; enroute    - courier has begun task (can't be forced; always done by courier)
;; servicing  - car is being serviced by courier (e.g., pumping gas)
;; complete   - order has been fulfilled
;; cancelled  - order has been cancelled (either by customer or system)

(defn get-all
  [db-conn]
  (!select db-conn "orders" ["*"] {} :append "ORDER BY target_time_start DESC"))

(defn get-all-unassigned
  [db-conn]
  (!select db-conn "orders" ["*"]
           {:status "unassigned"}
           :append "ORDER BY target_time_start DESC"))

(defn get-all-pre-servicing
  "All orders in a status chronologically before the Servicing status."
  [db-conn]
  (!select db-conn "orders" ["*"] {}
           :append (str "AND status IN ("
                        "'unassigned',"
                        "'assigned',"
                        "'accepted',"
                        "'enroute'"
                        ") ORDER BY target_time_start DESC")))

(defn get-all-current
  "Unassigned or in process."
  [db-conn]
  (!select db-conn "orders" ["*"] {}
           :append (str "AND status IN ("
                        "'unassigned',"
                        "'assigned',"
                        "'accepted',"
                        "'enroute',"
                        "'servicing'"
                        ") ORDER BY target_time_start DESC")))

(defn get-by-id
  "Gets an order from db by order's id."
  [db-conn id]
  (first (!select db-conn "orders" ["*"] {:id id})))

(defn update-status
  "Assumed to have been auth'd properly already."
  [db-conn order-id status]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE orders SET "
          ;; change status
          "status = \"" (mysql-escape-str status) "\", "
          ;; update event log
          "event_log = CONCAT(event_log, \""
          (mysql-escape-str status) " " (quot (System/currentTimeMillis) 1000)
          "\", '|') WHERE id = \""
          (mysql-escape-str order-id)
          "\""))))

(defn segment-props
  "Get a map of all the standard properties we track on orders via segment."
  [o & {:keys [zip-def]}]
  (assoc (select-keys o [:vehicle_id :gallons :gas_type :lat :lng
                         :address_street :address_city :address_state
                         :address_zip :license_plate :coupon_code
                         :referral_gallons_used :tire_pressure_check])
         :order_id (:id o)
         :gas_price (cents->dollars (:gas_price o))
         :service_fee (cents->dollars (:service_fee o))
         :total_price (cents->dollars (:total_price o))
         :target_time_start (unix->DateTime (:target_time_start o))
         :target_time_end (unix->DateTime (:target_time_end o))
         :market_id (:market-id zip-def)
         :submarket_id (:submarket-id zip-def)))

(defn gen-charge-description
  "Generate a description of the order (e.g., for including on a receipt)."
  [db-conn order]
  (let [vehicle (first (!select db-conn "vehicles" ["*"] {:id (:vehicle_id order)}))]
    (str (if (:is_fillup order)
           "Reliability service for full tank of fuel"
           (str "Reliability service for up to "
                (gallons->display-str (:gallons order)) " Gallons of Fuel"))
         " (" (:gas_type vehicle) " Octane)" ; assumes you're calling this when order is place (i.e., it could change)
         (when (:tire_pressure_check order) "\n+ Tire Pressure Fill-up")
         "\nVehicle: " (:year vehicle) " " (:make vehicle) " " (:model vehicle)
         " (" (:license_plate order) ")"
         "\nWhere: " (:address_street order)
         "\n" "When: " (unix->fuller (quot (System/currentTimeMillis) 1000)))))

(defn auth-charge-order
  [db-conn order]
  (users/charge-user db-conn
                     (:user_id order)
                     (:total_price order)
                     (gen-charge-description db-conn order)
                     (:id order)
                     :metadata {:order_id (:id order)}
                     :just-auth true))

(defn stamp-with-charge
  "Give it a charge object from Stripe."
  [db-conn order-id charge]
  (!update db-conn
           "orders"
           {:paid (:captured charge) ;; NOT THE SAME as (:paid charge)
            :stripe_charge_id (:id charge)
            :stripe_customer_id_charged (:customer charge)
            :stripe_balance_transaction_id (:balance_transaction charge)
            :time_paid (:created charge)
            :payment_info (-> charge
                              :source
                              (select-keys
                               [:id :brand :exp_month :exp_year :last4])
                              generate-string)}
           {:id order-id}))

(defn unpaid-balance
  [db-conn user-id]
  (reduce +
          (map :total_price
               (!select db-conn
                        "orders"
                        [:total_price]
                        {:user_id user-id
                         :status "complete"
                         :paid 0}
                        :append "AND total_price > 0")))) ; $0 order = no charge

(defn new-order-text
  [db-conn o charge-authorized?]
  (str "New order:"
       (let [unpaid-balance (unpaid-balance db-conn (:user_id o))]
         (when (pos? unpaid-balance)
           (str "\n!UNPAID BALANCE: $" (cents->dollars-str unpaid-balance))))
       "\nDue: " (unix->full (:target_time_end o))
       "\n" (:address_street o) ", " (:address_zip o)
       (when (:tire_pressure_check o) "\n+ TIRE PRESSURE CHECK")
       "\n" (:gallons o) " Gallons of " (:gas_type o)))

(defn stamp-with-refund
  "Give it a refund object from Stripe."
  [db-conn order-id refund]
  (!update db-conn
           "orders"
           {:stripe_refund_id (:id refund)}
           {:id order-id}))

(defn calc-delivery-fee
  [db-conn user zip-def time]
  (let [sub (subscriptions/get-with-usage db-conn user)
        delivery-fee (get (:delivery-fee zip-def) time)]
    (if sub
      (let [[num-free num-free-used sub-discount]
            (case time
              60  [(:num_free_one_hour sub)
                   (:num_free_one_hour_used sub)
                   (:discount_one_hour sub)]
              180 [(:num_free_three_hour sub)
                   (:num_free_three_hour_used sub)
                   (:discount_three_hour sub)]
              300 [(:num_free_five_hour sub)
                   (:num_free_five_hour_used sub)
                   (:discount_five_hour sub)])]
        (if (pos? (- num-free num-free-used))
          0
          (max 0 (+ delivery-fee sub-discount))))
      delivery-fee)))

(defn calc-cost-fixed
  "Calculate cost of order based on current prices. Returns cost in cents."
  [db-conn               ;; Database Connection
   user                  ;; 'user' map
   zip-def
   octane                ;; String
   gallons               ;; Double
   time                  ;; Integer, minutes
   tire-pressure-check   ;; Boolean
   coupon-code           ;; String
   vehicle-id            ;; String
   referral-gallons-used ;; Double
   zip-code              ;; String
   & {:keys [bypass-zip-code-check]}]
  ((comp (partial max 0) int #(Math/ceil %))
   (+ (* (get (:gas-price zip-def) octane) ; cents/gallon
         ;; number of gallons they need to pay for
         (- gallons (min gallons referral-gallons-used)))
      ;; add delivery fee (w/ consideration of subscription)
      (calc-delivery-fee db-conn user zip-def time)
      ;; add cost of tire pressure check if applicable
      (if tire-pressure-check
        config/tire-pressure-check-price
        0)
      ;; apply value of coupon code 
      (if-not (s/blank? coupon-code)
        (:value (coupons/code->value
                 db-conn
                 coupon-code
                 vehicle-id
                 (:id user)
                 zip-code
                 :bypass-zip-code-check bypass-zip-code-check))
        0))))

(defn valid-price?
  "Has the price changed while user was in checkout process?"
  [db-conn user zip-def o & {:keys [bypass-zip-code-check]}]
  (if (:is_fillup o)
    ;; variable amount of gallons, check if gas-price and service-fee are right
    (and (= (get (:gas-price zip-def) (:gas_type o))
            (:gas_price o))
         (= (calc-delivery-fee db-conn user zip-def (:time-limit o))
            (:service_fee o)))
    ;; fixed number of gallons, so we can check final price
    (= (:total_price o)
       (calc-cost-fixed db-conn
                        user
                        zip-def
                        (:gas_type o)
                        (:gallons o)
                        (:time-limit o)
                        (:tire_pressure_check o)
                        (:coupon_code o)
                        (:vehicle_id o)
                        (:referral_gallons_used o)
                        (:address_zip o)
                        :bypass-zip-code-check bypass-zip-code-check))))

(defn orders-in-zone
  [db-conn zone-id os]
  (filter #(in? (order->zones db-conn %) zone-id) os))

(defn valid-time-choice?
  "Is that Time choice (e.g., 1 hour / 3 hour) truly available?"
  [db-conn zip-def o]
  (or
   ;; "Premium" members can bypass
   (and (= 2 (:subscription_id o)) 
        (>= (:time-limit o) 60))
   ;; "Standard" members can bypass
   (and (= 1 (:subscription_id o)) (>= (:time-limit o) 180))
   ;; "Unlimited" members can bypass all
   (= 3 (:subscription_id o))
   ;; otherwise, is it offered?
   (and (in? (vals (:time-choices zip-def)) (:time-limit o))
        (or (>= (:time-limit o) 180)
            (not (:one-hour-constraining-zone-id zip-def))
            ;; Are there less one-hour orders in this zone
            ;; than connected couriers who are assigned to this zone?
            (< (->> (get-all-pre-servicing db-conn)
                    (orders-in-zone db-conn (:one-hour-constraining-zone-id zip-def))
                    (filter #(= (* 60 60) ;; only one-hour orders
                                (- (:target_time_end %)
                                   (:target_time_start %))))
                    count)
               (->> (couriers/get-all-connected db-conn)
                    (couriers/filter-by-zone
                     (:one-hour-constraining-zone-id zip-def))
                    count))))))

(defn add
  "The user-id given is assumed to have been auth'd already."
  [db-conn user-id order & {:keys [bypass-zip-code-check street-address-override]}]
  (if-let [zip-def (get-zip-def db-conn (:address_zip order))]
    (if-let [vehicle (first (!select db-conn "vehicles" ["*"] {:id (:vehicle_id order)}))]
      (let [time-limit (Integer. (:time order))
            gas-type (:gas_type vehicle)
            user (users/get-user-by-id db-conn user-id)
            referral-gallons-available (:referral_gallons user)
            curr-time-secs (quot (System/currentTimeMillis) 1000)
            o (assoc (select-keys order [:vehicle_id :special_instructions
                                         :address_city
                                         :address_state :address_zip :is_fillup
                                         :gas_price :service_fee :total_price])
                     :id (rand-str-alpha-num 20)
                     :user_id user-id
                     :status "unassigned"
                     :target_time_start curr-time-secs
                     :target_time_end (+ curr-time-secs (* 60 time-limit))
                     :time-limit time-limit
                     :gallons (when (:gallons order)
                                ;; 'when' is important to allow nil to pass through
                                (coerce-double (:gallons order)))
                     :gas_type gas-type
                     :is_top_tier (:only_top_tier vehicle)
                     :lat (coerce-double (:lat order))
                     :lng (coerce-double (:lng order))
                     :address_street (or street-address-override
                                         (:address_street order))
                     :license_plate (:license_plate vehicle)
                     ;; we'll use as many referral gallons as available
                     :referral_gallons_used (if (:is_fillup order)
                                              ;; referral gallons currently cannot
                                              ;; be applied to fillup
                                              0 
                                              (min (coerce-double (:gallons order))
                                                   referral-gallons-available))
                     :coupon_code (coupons/format-coupon-code (or (:coupon_code order) ""))
                     :subscription_id (if (subscriptions/valid? user)
                                        (:subscription_id user)
                                        0)
                     :tire_pressure_check (or (:tire_pressure_check order) false))]

        (cond
          (not (valid-price? db-conn user zip-def o :bypass-zip-code-check bypass-zip-code-check))
          (do (only-prod-or-dev
               (segment/track segment-client (:user_id o) "Request Order Failed"
                              (assoc (segment-props o :zip-def zip-def)
                                     :reason "price-changed-during-review")))
              {:success false
               :message "The price changed while you were creating your order."
               :code "invalid-price"})

          (not (valid-time-choice? db-conn zip-def o))
          (do (only-prod-or-dev
               (segment/track segment-client (:user_id o) "Request Order Failed"
                              (assoc (segment-props o :zip-def zip-def)
                                     :reason "high-demand")))
              {:success false
               :message (str "We currently are experiencing high demand and "
                             "can't promise a delivery within that time limit. Please "
                             "go back and choose a different Time option.")
               :code "invalid-time-choice"})

          (not (is-open? zip-def (:target_time_start o)))
          (do (only-prod-or-dev
               (segment/track segment-client (:user_id o) "Request Order Failed"
                              (assoc (segment-props o :zip-def zip-def)
                                     :reason "outside-service-hours")))
              {:success false
               :message (:closed-message zip-def)
               :code "outside-service-hours"})
          
          :else
          (let [auth-charge-result (if (or (zero? (:total_price o)) ; nothing to charge
                                           (users/is-managed-account? user)) ; managed account (will be charged later)
                                     {:success true}
                                     (auth-charge-order db-conn o))
                charge-authorized? (:success auth-charge-result)]
            (if (not charge-authorized?)
              (do ;; payment failed, do not allow order to be placed
                (only-prod-or-dev
                 (segment/track segment-client (:user_id o) "Request Order Failed"
                                (assoc (segment-props o :zip-def zip-def)
                                       :charge-authorized charge-authorized? ;; false
                                       :reason "failed-charge")))
                {:success false
                 :message "Sorry, we were unable to charge your credit card."
                 :code "charge-auth-failed"})
              (do ;; successful payment (or free order), place order...
                (!insert db-conn
                         "orders"
                         (assoc (select-keys o [:id :user_id :vehicle_id
                                                :status :target_time_start
                                                :target_time_end
                                                :gallons :gas_type :is_top_tier
                                                :special_instructions
                                                :lat :lng :address_street
                                                :address_city :address_state
                                                :address_zip :is_fillup :gas_price
                                                :service_fee :total_price
                                                :license_plate :coupon_code
                                                :referral_gallons_used
                                                :subscription_id
                                                :tire_pressure_check])
                                :event_log ""
                                :is_overridden_street_address
                                (not (nil? street-address-override))))
                (when-not (zero? (:referral_gallons_used o))
                  (coupons/mark-gallons-as-used db-conn
                                                (:user_id o)
                                                (:referral_gallons_used o)))
                (when-not (s/blank? (:coupon_code o))
                  (coupons/mark-code-as-used db-conn
                                             (:coupon_code o)
                                             (:license_plate o)
                                             (:user_id o)))
                (future ;; we can process the rest of this asynchronously
                  (when (and charge-authorized? (not (zero? (:total_price o))))
                    (stamp-with-charge db-conn (:id o) (:charge auth-charge-result)))

                  ;; fraud detection
                  (when (not (zero? (:total_price o)))
                    (let [c (:charge auth-charge-result)]
                      (sift/charge-authorization
                       o user
                       (if charge-authorized?
                         {:stripe-charge-id (:id c)
                          :successful? true
                          :card-last4 (:last4 (:card c))
                          :stripe-cvc-check (:cvc_check (:card c))
                          :stripe-funding (:funding (:card c))
                          :stripe-brand (:brand (:card c))
                          :stripe-customer-id (:customer c)}
                         {:stripe-charge-id (:charge (:error c))
                          :successful? false
                          :decline-reason-code (:decline_code (:error c))}))))
                  
                  (only-prod
                   (let [order-text-info (new-order-text db-conn o charge-authorized?)]
                     (client/post "https://hooks.slack.com/services/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"
                                  {:throw-exceptions false
                                   :content-type :json
                                   :form-params {:text (str order-text-info
                                                            ;; TODO
                                                            ;; "\n<https://NEED_ORDER_PAGE_LINK_HERE|View on Dashboard>"
                                                            )
                                                 :icon_emoji ":fuelpump:"
                                                 :username "New Order"}})
                     ;; (run! #(send-sms % order-text-info)
                     ;;       ["5555555555" ; put phone numbers here
                     ;;        ])
                     ))
                  
                  (only-prod-or-dev
                   (segment/track segment-client (:user_id o) "Request Order"
                                  (assoc (segment-props o :zip-def zip-def)
                                         :charge-authorized charge-authorized?))
                   ;; used by mailchimp
                   (segment/identify segment-client (:user_id o)
                                     {:email (:email user) ;; required every time
                                      :HASORDERED 1})))
                {:success true
                 :order_id (:id o)
                 :message (str "Your order has been accepted, and a courier will be "
                               "on the way soon! Please ensure that the fueling door "
                               "on your gas tank is unlocked.")})))))
      {:success false
       :message "Sorry, we don't recognize your vehicle information."
       :code "invalid-vehicle-id"})
    {:success false
     :message (str "Sorry, we are unable to deliver gas to your "
                   "location. We are rapidly expanding our service "
                   "area and hope to offer service to your "
                   "location very soon.")
     :code "outside-service-area"}))


(defn begin-route
  "This is a courier action."
  [db-conn o]
  (do (update-status db-conn (:id o) "enroute")
      (users/send-push
       db-conn
       (:user_id o)
       (str "A courier is enroute to your location. Please ensure that your"
            " fueling door is open."))))

(defn service
  "This is a courier action."
  [db-conn o]
  (do (update-status db-conn (:id o) "servicing")
      (users/send-push
       db-conn
       (:user_id o)
       "We are currently servicing your vehicle.")))

(defn after-payment
  [db-conn o]
  (do (when-not (s/blank? (:coupon_code o))
        (when-let [user-id (-> (!select db-conn "users" [:id] {:referral_code
                                                               (:coupon_code o)})
                               first ;; if this when-let fails, that means this
                               :id)] ;; is a standard coupon not referral coupon
          (coupons/apply-referral-bonus db-conn user-id)))
      (segment/track segment-client (:user_id o) "Complete Order"
                     (assoc
                      (segment-props o
                                     :zip-def
                                     (get-zip-def db-conn (:address_zip o)))
                      :revenue (cents->dollars (:total_price o))))
      (users/send-push db-conn (:user_id o)
                       (let [user (users/get-user-by-id db-conn (:user_id o))]
                         (str "Your delivery has been completed."
                              (when-not (users/is-managed-account? user)
                                (str " Share your code "
                                     (:referral_code user)
                                     " to earn free gas"
                                     (when (not (.contains (:arn_endpoint user) "GCM/Purple"))
                                       " \ue112") ; iOS gift emoji
                                     "."))
                              " Thank you!")))))

(defn resolve-fillup-gallons
  [db-conn o gallons total-price]
  (!update db-conn
           "orders"
           {:gallons gallons
            :total_price total-price}
           {:id (:id o)})
  (payment/update-stripe-charge (:stripe_charge_id o)
                                (gen-charge-description
                                 db-conn
                                 (assoc o
                                        :gallons gallons
                                        :total_price total-price))))

(defn complete
  "Completes order and charges user."
  [db-conn o & {:keys [resolved-fillup-gallons]}]
  (let [total-price (if (:is-fillup o)
                      (compute-total-price (:gas_price o)
                                           resolved-fillup-gallons
                                           (:service_fee o))
                      (:total_price o))]
    (update-status db-conn (:id o) "complete")
    (couriers/update-courier-busy db-conn (:courier_id o))
    (when (:is-fillup o)
      (resolve-fillup-gallons db-conn o resolved-fillup-gallons total-price))
    (if (or (zero? total-price)
            (s/blank? (:stripe_charge_id o)))
      ;; if was zero and was a fillup, then what?
      (after-payment db-conn o)
      (let [capture-result
            (payment/capture-stripe-charge
             (:stripe_charge_id o)
             ;; only need to adjust price if it was a fillup
             :amount (when (:is_fillup o) total-price))]
        (if (:success capture-result)
          (do (stamp-with-charge db-conn (:id o) (:charge capture-result))
              (after-payment db-conn o))
          capture-result)))))

(defn next-status
  [status]
  (get config/status->next-status status))

;; note that it takes order-id, not order
(defn accept
  [db-conn order-id]
  (do (update-status db-conn order-id "accepted")
      {:success true}))

;; note that it takes order-id, not order
(defn assign
  [db-conn order-id courier-id & {:keys [no-reassigns]}]
  (let [o (get-by-id db-conn order-id)]
    (when (or (not no-reassigns)
              (= "unassigned" (:status o)))
      (update-status db-conn order-id "assigned")
      (!update db-conn "orders" {:courier_id courier-id} {:id order-id})
      (couriers/set-courier-busy db-conn courier-id true)
      (users/send-push db-conn courier-id "You have been assigned a new order.")
      (users/text-user db-conn courier-id (new-order-text db-conn o true))
      {:success true})))

(defn cancel
  [db-conn user-id order-id & {:keys [origin-was-dashboard
                                      notify-customer
                                      suppress-user-details
                                      override-cancellable-statuses]}]
  (if-let [o (get-by-id db-conn order-id)]
    (if (in? (or override-cancellable-statuses
                 config/cancellable-statuses)
             (:status o))
      (do (update-status db-conn order-id "cancelled")
          (future
            ;; return any free gallons that may have been used
            (when (not= 0 (:referral_gallons_used o))
              (coupons/mark-gallons-as-unused db-conn
                                              (:user_id o)
                                              (:referral_gallons_used o))
              (!update db-conn
                       "orders"
                       {:referral_gallons_used 0}
                       {:id order-id}))
            ;; free up that coupon code for that vehicle
            (when-not (s/blank? (:coupon_code o))
              (coupons/mark-code-as-unused db-conn
                                           (:coupon_code o)
                                           (:vehicle_id o)
                                           (:user_id o))
              (!update db-conn
                       "orders"
                       {:coupon_code ""}
                       {:id order-id}))
            ;; let the courier know the order has been cancelled
            (when-not (s/blank? (:courier_id o))
              (couriers/update-courier-busy db-conn (:courier_id o))
              (users/send-push db-conn (:courier_id o)
                               "The current order has been cancelled."))
            ;; let the user know the order has been cancelled
            (when notify-customer
              (users/send-push
               db-conn user-id
               (str "Your order has been cancelled. If you have any questions,"
                    " please email us at info@purpleapp.com or use the Feedback"
                    " form on the left-hand menu.")))
            (when-not (s/blank? (:stripe_charge_id o))
              (let [refund-result (payment/refund-stripe-charge
                                   (:stripe_charge_id o))]
                (when (:success refund-result)
                  (stamp-with-refund db-conn
                                     order-id
                                     (:refund refund-result)))))
            (segment/track
             segment-client (:user_id o) "Cancel Order"
             (assoc (segment-props o
                                   :zip-def
                                   (get-zip-def db-conn (:address_zip o)))
                    :cancelled-by-user (not origin-was-dashboard))))
          (if suppress-user-details
            {:success true}
            (users/details db-conn user-id)))
      (cond
        (= "cancelled" (:status o)) 
        {:success false
         :message "That order has already been cancelled."
         :code "already-cancelled"}

        :else
        {:success false
         :message "Sorry, it is too late for this order to be cancelled."
         :code "too-late-to-cancel"}))
    {:success false
     :message "An order with that ID could not be found."
     :code "invalid-order-id"}))
