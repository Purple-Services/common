(ns common.coupons
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [common.config :as config]
            [common.db :refer [mysql-escape-str !select]]
            [common.users :as users]
            [common.util :refer [split-on-comma rand-str coerce-double]]))

(defn format-coupon-code
  "Format coupon code to consistent format. (Keep this idempotent!)"
  [code]
  (s/replace (s/upper-case code) #" " ""))

(defn get-coupon-by-code
  "Get a coupon from DB given its code (e.g., GAS15)."
  [db-conn code]
  (first (!select db-conn
                  "coupons"
                  ["*"]
                  {:code code})))

(defn get-license-plate-by-vehicle-id
  "Get the license of a vehicle given its id. Or nil."
  [db-conn vehicle-id]
  (some-> (!select db-conn
                   "vehicles"
                   ["license_plate"]
                   {:id vehicle-id})
          first
          :license_plate))

(defn mark-code-as-used
  "Mark a coupon as used given its code."
  [db-conn code license-plate user-id]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE coupons SET "
          "used_by_license_plates = CONCAT(used_by_license_plates, \""
          (mysql-escape-str license-plate) "\", ','), "
          "used_by_user_ids = CONCAT(used_by_user_ids, \""
          (mysql-escape-str user-id) "\", ',') "
          " WHERE code = \"" (mysql-escape-str code) "\""))))

(defn mark-code-as-unused
  "Mark a coupon as unused (available for use) given its code."
  [db-conn code vehicle-id user-id]
  (let [coupon (get-coupon-by-code db-conn code)
        license-plate (get-license-plate-by-vehicle-id db-conn vehicle-id)
        used-by-license-plates (-> (:used_by_license_plates coupon)
                                   split-on-comma
                                   set
                                   (disj (mysql-escape-str license-plate)))
        used-by-user-ids (-> (:used_by_user_ids coupon)
                             split-on-comma
                             set
                             (disj (mysql-escape-str user-id)))]
    (sql/with-connection db-conn
      (sql/do-prepared
       (str "UPDATE coupons SET "
            "used_by_license_plates = \""
            (s/join "," used-by-license-plates)
            (when (seq used-by-license-plates) ",")
            "\", used_by_user_ids = \""
            (s/join "," used-by-user-ids)
            (when (seq used-by-user-ids) ",")
            "\" WHERE code = \"" (mysql-escape-str code) "\"")))))

(defn mark-gallons-as-used
  "Use gallons from user's referral gallons. Assumes gallons are available."
  [db-conn user-id gallons]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE users SET referral_gallons = referral_gallons - "
          (coerce-double gallons)
          " WHERE id = \"" (mysql-escape-str user-id) "\""))))

(defn mark-gallons-as-unused
  "Un-use gallons from user's referral gallons. (Add gallons to their account)."
  [db-conn user-id gallons]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE users SET referral_gallons = referral_gallons + "
          (coerce-double gallons)
          " WHERE id = \"" (mysql-escape-str user-id) "\""))))

(defn apply-referral-bonus
  "Add benefits of referral to origin account."
  [db-conn user-id]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE users SET referral_gallons = referral_gallons + "
          config/referral-referrer-gallons
          " WHERE id = \"" (mysql-escape-str user-id) "\"")))
  ((resolve 'purple.users/send-push) db-conn user-id
   (str "Thank you for sharing Purple with your friend! We've added "
        config/referral-referrer-gallons
        " gallons to your account!")))

;; originally in utils.clj
(defn gen-coupon-code []
  (rand-str (remove (set [65  ;; A  - removing vowels to avoid offensive words
                          69  ;; E
                          73  ;; I
                          79  ;; O
                          85  ;; U
                          48  ;; 0  - removing nums that look like chars
                          49  ;; 1
                          79  ;; O  - removing chars that look like nums
                          73]);; I
                    (concat (range 48 58)  ;; 0-9
                            (range 65 91)))  ;; A-Z
            5))
