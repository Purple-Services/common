(ns common.util
  (:import [com.amazonaws.services.sns.model PublishRequest]
           [com.twilio.sdk TwilioRestClient]
           [org.apache.http.message BasicNameValuePair]
           [java.util ArrayList])
  (:require [clojure.string :as s]
            [clojure.walk :refer [postwalk]]
            [clj-aws.core :as aws]
            [clj-aws.sns :as sns]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [clj-time.format :as time-format]
            [postal.core :as postal]
            [common.config :as config]
            [ardoq.analytics-clj :as segment]))

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

(defmacro catch-notify
  "A try catch block that emails me exceptions."
  [& body]
  `(try ~@body
        (catch Exception e#
          (only-prod (send-email {:to "chris@purpledelivery.com"
                                  :subject "Purple - Exception Caught"
                                  :body (str e#)})))))

(defmacro unless-p
  "Use x unless the predicate is true for x, then use y instead."
  [pred x y]
  `(if-not (~pred ~x)
     ~x
     ~y))

(defn in? 
  "true if seq contains elm"
  [seq elm]  
  (some #(= elm %) seq))

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

(def time-zone (time/time-zone-for-id "America/Los_Angeles"))

(defn unix->DateTime
  [x]
  (time-coerce/from-long (* 1000 x)))

(def full-formatter (time-format/formatter "M/d h:mm a"))

(defn unix->full
  "Convert integer unix timestamp to formatted date string."
  [x]
  (time-format/unparse
   (time-format/with-zone full-formatter time-zone)
   (unix->DateTime x)))

(defn map->java-hash-map
  "Recursively convert Clojure PersistentArrayMap to Java HashMap."
  [m]
  (postwalk #(unless-p map? % (java.util.HashMap. %)) m))

(defn unix->DateTime
  [x]
  (time-coerce/from-long (* 1000 x)))

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

(defn new-auth-token []
  (rand-str-alpha-num 128))

;; could this be an atom that is set to nil and initilized later?
(! (def segment-client (segment/initialize
                        (System/getProperty "SEGMENT_WRITE_KEY"))))

;; Amazon SNS (Push Notifications)
(! (do
     (def aws-creds (aws/credentials (System/getProperty "AWS_ACCESS_KEY_ID")
                                     (System/getProperty "AWS_SECRET_KEY")))
     (def sns-client (sns/client aws-creds))
     (.setEndpoint sns-client "https://sns.us-west-2.amazonaws.com")))


(defn send-email [message-map]
  (try (postal/send-message config/email
                            (assoc message-map
                                   :from (str "Purple Services Inc <"
                                              config/email-from-address
                                              ">")))
       {:success true}
       (catch Exception e
         {:success false
          :message "Message could not be sent to that address."})))

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
      (only-prod (send-email {:to "chris@purpledelivery.com"
                              :subject "Purple - Error"
                              :body (str "AWS SNS Publish Exception: "
                                         (.getMessage e)
                                         "\n\n"
                                         "target-arn: "
                                         target-arn
                                         "\nmessage: "
                                         message)})))))

;; Twilio (SMS & Phone Calls)
(! (do
     (def twilio-client (TwilioRestClient. config/twilio-account-sid
                                           config/twilio-auth-token))
     (def twilio-sms-factory (.getMessageFactory (.getAccount twilio-client)))
     (def twilio-call-factory (.getCallFactory (.getAccount twilio-client)))))

(defn send-sms
  [to-number message]
  (catch-notify
   (.create
    twilio-sms-factory
    (ArrayList. [(BasicNameValuePair. "Body" message)
                 (BasicNameValuePair. "To" to-number)
                 (BasicNameValuePair. "From" config/twilio-from-number)]))))

(defn timestamp->unix-epoch
  "Convert a java.sql.Timestamp timestamp to unix epoch seconds"
  [timestamp]
  (/ (.getTime timestamp) 1000))

(defn convert-timestamp
  "Replace :timestamp_created value in m with unix epoch seconds"
  [m]
  (assoc m :timestamp_created (timestamp->unix-epoch (:timestamp_created m))))

(defn convert-timestamps
  "Replace the :timestamp_created value with unix epoch seconds in each map of
  vector"
  [v]
  (map convert-timestamp v))
