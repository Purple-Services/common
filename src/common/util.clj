(ns common.util
  (:import [com.amazonaws.services.sns.model PublishRequest
            CreatePlatformEndpointRequest]
           [com.twilio.sdk TwilioRestClient]
           [org.apache.http.message BasicNameValuePair]
           [java.util ArrayList])
  (:require [clojure.string :as s]
            [clojure.walk :refer [postwalk]]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [clj-time.format :as time-format]
            [postal.core :as postal]
            [common.config :as config]
            [common.db :refer [conn]]
            [common.sns :as sns]
            [ardoq.analytics-clj :as segment]
            [version-clj.core :as version]
            [clj-http.client :as client]))

(defmacro !
  "Keeps code from running during compilation."
  [& body]
  `(when-not *compile-files*
     ~@body))

(defmacro only-prod
  "Only run this code when in production mode."
  [& body]
  `(when (= config/db-user "purplemasterprod")
     ~@body))

(defmacro only-prod-or-dev
  "Only run this code when in production mode."
  [& body]
  `(when (or (= config/db-user "purplemasterprod")
             (= config/db-user "purplemaster"))
     ~@body))

(defmacro catch-notify
  "A try catch block that emails @celwell exceptions."
  [& body]
  `(try ~@body
        (catch Exception e#
          (log-error (str e#)))))

(defmacro unless-p
  "Use x unless the predicate is true for x, then use y instead."
  [pred x y]
  `(if-not (~pred ~x)
     ~x
     ~y))

(defn in? 
  "true if seq contains elm"
  [seq elm]  
  (boolean (some #(= elm %) seq)))

(defn not-nil-vec
  [k v]
  (when-not (nil? v) [k v]))

(defn ver=
  "Same as =, but works on version number strings (e.g., 2.13.0)."
  [x y]
  (= 0 (version/version-compare x y)))

(defn ver<
  "Same as <, but works on version number strings (e.g., 2.13.0)."
  [x y]
  (= -1 (version/version-compare x y)))

(defn ver>
  "Same as <, but works on version number strings (e.g., 2.13.0)."
  [x y]
  (= 1 (version/version-compare x y)))

(defn five-digit-zip-code
  [zip-code]
  (subs zip-code 0 5))

(defn cents->dollars
  "Integer of cents -> Double of dollars."
  [x]
  (if (zero? x)
    0
    (double (/ x 100))))

(defn cents->dollars-str
  "To display an integer of cents as string in dollars with a decimal."
  [x]
  (let [y (str x)]
    (->> (split-at (- (count y) 2) y)
         (interpose ".")
         flatten
         (apply str))))

(defn gallons->display-str
  "Given gallons (Double), convert to string with 2 decimal places or less."
  [gallons]
  (.format (java.text.DecimalFormat. "#.##") gallons))

(defn now-unix
  "Current UNIX time as a Number."
  []
  (quot (System/currentTimeMillis) 1000))

(def time-zone (time/time-zone-for-id "America/Los_Angeles"))

(defn unix->DateTime
  [x]
  (time-coerce/from-long (* 1000 x)))

(defn unix->format
  "Convert integer unix time to formatted date string using supplied formatter."
  [t formatter]
  (time-format/unparse
   (time-format/with-zone formatter time-zone)
   (unix->DateTime t)))

(def full-formatter (time-format/formatter "M/d h:mm a"))
(defn unix->full
  "Convert integer unix time to formatted date string."
  [t]
  (unix->format t full-formatter))

(def fuller-formatter (time-format/formatter "M/d/y h:mm a"))
(defn unix->fuller
  "Convert integer unix time to formatted date string (M/d/y h:mm a)."
  [t]
  (unix->format t fuller-formatter))

(def hour-formatter (time-format/formatter "H"))
(defn unix->hour-of-day
  "Convert integer unix time to integer hour of day 0-23."
  [t]
  (Integer. (unix->format t hour-formatter)))

(def minute-formatter (time-format/formatter "m"))
(defn unix->minute-of-hour
  "Convert integer unix timestamp to integer minute of hour."
  [t]
  (Integer. (unix->format t minute-formatter)))

(def hmma-formatter (time-format/formatter "h:mm a"))
(defn unix->hmma
  "Convert integer unix timestamp to formatted date string (h:mm a)."
  [t]
  (unix->format t hmma-formatter))

(def day-of-week-formatter (time-format/formatter "e"))
(defn unix->day-of-week
  "Convert integer unix time to integer day of week 1 (mon) - 7 (sun)."
  [t]
  (Integer. (unix->format t day-of-week-formatter)))

(defn unix->minute-of-day
  "How many minutes (int) since beginning of day?"
  [x]
  (+ (* (unix->hour-of-day x) 60)
     (unix->minute-of-hour x)))

(defn minute-of-day->hmma
  "Convert number of minutes since the beginning of today to a unix timestamp."
  [m]
  (unix->hmma
   (+ (* m 60)
      (-> (time/date-time 1976) ;; it'll be wrong day but same hmma
          (time/from-time-zone time-zone)
          time-coerce/to-long
          (quot 1000)))))

(defn coerce-double
  "Coerces various inputs regardless of type into a Double. (nil is 0.0)"
  [x]
  (Double/parseDouble (str (or x 0))))

(defn map->java-hash-map
  "Recursively convert Clojure PersistentArrayMap to Java HashMap."
  [m]
  (postwalk #(unless-p map? % (java.util.HashMap. %)) m))

(defn rand-str
  [ascii-codes length]
  (apply str (repeatedly length #(char (rand-nth ascii-codes)))))

(defn rand-str-alpha-num
  [length]
  (rand-str (concat (range 48 58)  ;; 0-9
                    (range 65 91)  ;; A-Z
                    (range 97 123) ;; a-z
                    )
            length))

(defn split-on-comma [x] (s/split x #","))

(defn user-first-name
  "The first segment of their name before any spaces."
  [full-name]
  (first (s/split full-name #" ")))

(defn user-last-name
  "Everything in their full name except their first name."
  [full-name]
  (s/trim (subs full-name (count (user-first-name full-name)))))

(defn new-auth-token []
  (rand-str-alpha-num 128))

(defn get-event-time
  "Get time of event from event log as unix timestamp Integer.
  If event hasn't occurred yet, then nil."
  [event-log event]
  (some-> event-log
          (#(unless-p s/blank? % nil))
          (s/split #"\||\s")
          (->> (apply hash-map))
          (get event)
          (Integer.)))

(defn send-email
  [message-map]
  (try (postal/send-message config/email
                            (assoc message-map
                                   :from (str "Purple Services Inc <"
                                              config/email-from-address
                                              ">")))
       {:success true}
       (catch Exception e
         {:success false
          :message "Message could not be sent to that address."})))

(defn log-error
  "Currently sends emails to @celwell for his inspection. Only for rare and important errors."
  [message]
  (only-prod (send-email {:to "chris@purpleapp.com"
                          :subject "Purple - Error"
                          :body message})))

;; could this be an atom that is set to nil and initilized later?
(when-let [segment-write-key (System/getProperty "SEGMENT_WRITE_KEY")]
  (def segment-client (segment/initialize
                       segment-write-key)))

;; Amazon SNS (Push Notifications)
(when-let [aws-access-key-id (System/getProperty "AWS_ACCESS_KEY_ID")]
  (def sns-client
    (sns/client (sns/credentials aws-access-key-id
                                 (System/getProperty "AWS_SECRET_KEY"))))
  (.setEndpoint sns-client "https://sns.us-west-2.amazonaws.com"))

(defn sns-create-endpoint
  [client device-token user-id sns-app-arn]
  (try
    (let [req (CreatePlatformEndpointRequest.)]
      ;; (.setCustomUserData req "no custom data")
      (.setToken req device-token)
      (.setPlatformApplicationArn req sns-app-arn)
      (.getEndpointArn (.createPlatformEndpoint client req)))
    (catch Exception e
      (log-error (str "AWS SNS Create Endpoint Exception: "
                      (.getMessage e) "\n\nuser-id: " user-id))
      "" ;; return empty endpoint arn
      )))

(defn sns-publish
  [client target-arn message]
  (try
    (let [req (PublishRequest.)
          is-gcm? (.contains target-arn "GCM/Purple")]
      (.setMessage req (if is-gcm?
                         (str "{\"GCM\": \"{ "
                              "\\\"data\\\": { \\\"message\\\": \\\""
                              message
                              "\\\" } }\"}")
                         message))
      (when is-gcm? (.setMessageStructure req "json"))
      (.setTargetArn req target-arn)
      (.publish client req))
    (catch Exception e
      (log-error (str "AWS SNS Publish Exception: "
                      (.getMessage e)
                      "\n\ntarget-arn: " target-arn
                      "\nmessage: " message)))))

;; Twilio (SMS & Phone Calls)
(when config/twilio-account-sid
  (def twilio-client (TwilioRestClient. config/twilio-account-sid
                                        config/twilio-auth-token))
  (def twilio-sms-factory (.getMessageFactory (.getAccount twilio-client)))
  (def twilio-call-factory (.getCallFactory (.getAccount twilio-client))))

(defn send-sms
  [to-number message]
  (try (.create twilio-sms-factory
                (ArrayList. [(BasicNameValuePair. "Body" message)
                             (BasicNameValuePair. "To" to-number)
                             (BasicNameValuePair. "From" config/twilio-from-number)]))
       (catch Exception e
         (log-error (str "Purple - Twilio Exception Caught"
                         "\n" e
                         "\nTo-number: " to-number
                         "\nMessage: " message)))))

(defn make-call
  [to-number call-url]
  (catch-notify
   (.create
    twilio-call-factory
    (ArrayList. [(BasicNameValuePair. "Url" call-url)
                 (BasicNameValuePair. "To" to-number)
                 (BasicNameValuePair. "From" config/twilio-from-number)]))))

(defn timestamp->unix-epoch
  "Convert a java.sql.Timestamp timestamp to unix epoch seconds"
  [timestamp]
  (/ (.getTime timestamp) 1000))

(defn convert-timestamp
  "Replace :timestamp_created value in m with unix epoch seconds"
  [m]
  (update m :timestamp_created timestamp->unix-epoch))

(defn convert-timestamps
  "Replace the :timestamp_created value with unix epoch seconds in each map of
  vector"
  [v]
  (map convert-timestamp v))

;; TODO - consider caching with a db persistent hashmap
;; or just an in-memory memoization (i would imagine an exact latlng is unlikely
;; to be repeated outside of with an hour)
(defn reverse-geocode
  "Get 5-digit ZIP given lat lng. nil on failure"
  [lat lng]
  (try
    (let [body (:body (clj-http.client/get
                       "https://maps.googleapis.com/maps/api/geocode/json"
                       {:as :json
                        :content-type :json
                        :coerce :always
                        :query-params {:latlng (str lat "," lng)
                                       :key config/api-google-server-api-key}}))
          ;; _ (clojure.pprint/pprint body)
          street-number (some->> body
                                 :results
                                 (filter #(in? (:types %) "street_address"))
                                 first
                                 :address_components
                                 (filter #(in? (:types %) "street_number"))
                                 first
                                 :short_name)
          street-name (or (some->> body
                                   :results
                                   (filter #(in? (:types %) "street_address"))
                                   first
                                   :address_components
                                   (filter #(in? (:types %) "route"))
                                   first
                                   :short_name)
                          (some->> body
                                   :results
                                   (filter #(in? (:types %) "route"))
                                   first
                                   :address_components
                                   (filter #(in? (:types %) "route"))
                                   first
                                   :short_name))]
      (when (= "OK" (:status body))
        {:street (str street-number (when street-number " ") street-name)
         :zip (some->> body
                       :results
                       (filter #(in? (:types %) "postal_code"))
                       first
                       :address_components
                       (filter #(in? (:types %) "postal_code"))
                       first
                       :short_name)}))
    (catch Exception e
      (log-error (str e))
      nil)))

(defn geocode
  "Get lat lng when given street address & zip code. nil on failure"
  [street zip]
  (try
    (let [body (:body (clj-http.client/get
                       "https://maps.googleapis.com/maps/api/geocode/json"
                       {:as :json
                        :content-type :json
                        :coerce :always
                        :query-params {:address (str street ", " zip)
                                       :key config/api-google-server-api-key}}))
          ;; _ (clojure.pprint/pprint body)
          ]
      (when (= "OK" (:status body))
        {:lat (-> body :results first :geometry :location :lat)
         :lng (-> body :results first :geometry :location :lng)}))
    (catch Exception e
      (log-error (str e))
      nil)))

(defn compute-total-price
  "Compute total price given the final amount of gallons (after referral
  applied) and delivery (after subscription applied) and gas price."
  [gas-price gallons delivery-fee]
  ((comp (partial max 0) int #(Math/ceil %))
   (+ (* gas-price gallons)
      delivery-fee)))
