(ns common.zoning
  (:require [clojure.string :as s]
            [common.db :refer [!select conn]]
            [common.util :refer [five-digit-zip-code in? split-on-comma
                                 now-unix unix->minute-of-day
                                 minute-of-day->hmma unix->day-of-week]]))








(def zones-test
  [{:zone-id 0
    :zone-name "Earth"
    :rank 0
    :active true
    :gallon-choices {:0 7.5
                     :1 10
                     :2 15}
    :default-gallon-choice :2 ; NOTE, use key
    :time-choices {:0 60
                   :1 180
                   :2 300}
    :default-time-choice 180 ; NOTE, use value, not key here
    :delivery-fee {60 599
                   180 399
                   300 299}
    :tire-pressure-price 700
    :manually-closed? false
    :closed-message nil
    :zips [;;;;;;;;;;;;;;
           ;; Los Angeles
           ;;;;;;;;;;;;;;
           ;; West LA
           "90028","90038","90010","90005","90020","90004","90077","90069",
           "90046","90232","90034","90036","90294","90410","90409","90408",
           "90407","90406","90019","90035","90211","90048","90212","90067",
           "90024","90095","90016"
           ;; Santa Monica
           "90292","90291","90066","90401","90405","90404","90402","90403",
           "90272","90049","90025","90094","90064","90073","90293","90230",
           "90245","90045"
           ;; Calabasas
           "91604","91423","91403","91436","91316","91356","91364","91367",
           "91302"
           ;; Downtown LA
           "90021","90015","90014","90071","90013","90012","90017","90057",
           "90011","90089","90007"
           ;; Pasadena
           "90068","91204","90027","90039","90026"
           ;; Pasadena Scheduled Only
           "91105","91030","90041","90029","91205","91206","90042","91103",
           "91106","91104","90065","91101"
           ;; Beverly Hills
           "90210"
           ;;;;;;;;;;;;
           ;; San Diego
           ;;;;;;;;;;;;
           ;; Central SD
           "92109","92107","92106","92101","92103","92104","92116","92105",
           "92102","92110","92140"
           ;; La Jolla
           "92121","92037","92122","92117","92123","92111","92108","92126"
           ;; Encinitas
           "92024","92091","92007","92067","92075","92014","92130","92129"
           ;;;;;;;;;;;;;;;;
           ;; Orange County
           ;;;;;;;;;;;;;;;
           ;; Newport Beach
           "92625","92626","92627","92660","92617","92663","92661","92657",
           "92603","92646"
           ;; Irvine
           "92612","92614","92606","92604","92620","92618","92630"
           ;; Santa Ana
           "92705","92780","92704","92706","92707","92701","92703"
           ;;;;;;;;;;
           ;; Seattle
           ;;;;;;;;;;
           ;; North Seattle
           "98102","98112","98105","98195","98115","98103","98107","98117"
           ;; Central Seattle
           "98134","98144","98122","98104","98101","98121","98109","98119",
           "98199"
           ;; South Seattle
           "98136","98126","98106","98108","98118","98116"
           ;; Bellevue
           "98005","98004","98039","98007","98008","98006","98040"]}
   {:zone-id 1
    :zone-name "Los Angeles"
    :rank 100
    :active true
    ;; if defining :gas-price, don't also use a gas price diff in the same zone
    :gas-price {"87" 305
                "91" 329}
    :hours [[[450 1350]]
            [[450 1350]]
            [[450 1350]]
            [[450 1350]]
            [[450 1350]]
            [[450 1350]]
            [[450 1350]]]    
    :zips [;; West LA
           "90028","90038","90010","90005","90020","90004","90077","90069",
           "90046","90232","90034","90036","90294","90410","90409","90408",
           "90407","90406","90019","90035","90211","90048","90212","90067",
           "90024","90095","90016"
           ;; Santa Monica
           "90292","90291","90066","90401","90405","90404","90402","90403",
           "90272","90049","90025","90094","90064","90073","90293","90230",
           "90245","90045"
           ;; Calabasas
           "91604","91423","91403","91436","91316","91356","91364","91367",
           "91302"
           ;; Downtown LA
           "90021","90015","90014","90071","90013","90012","90017","90057",
           "90011","90089","90007"
           ;; Pasadena
           "90068","91204","90027","90039","90026"
           ;; Pasadena Scheduled Only
           "91105","91030","90041","90029","91205","91206","90042","91103",
           "91106","91104","90065","91101"]}
   {:zone-id 3445
    :zone-name "90025 Weekend Closure"
    :rank 1000
    :active true
    :hours [[[450 1350]]
            [[450 1350]]
            [[450 1350]]
            [[450 1350]]
            [[450 1350]]
            []
            []]    
    :zips ["90025"]}
   {:zone-id 88333
    :zone-name "Expensive areas"
    :rank 116
    :active true
    :gas-price-diff-percent {"87" 33.33333
                             "91" 37.5}
    :zips ["90025"]}
   {:zone-id 2343
    :zone-name "LA Outer Ring"
    :rank 10000
    :active true
    ;; when removing a time choice, be sure to consider if the default time choice still exists!
    :time-choices {:0 180
                   :1 300}
    :zips [;; Santa Monica
           "90292","90291","90066","90401","90405","90404","90402","90403",
           "90272","90049","90025","90094","90064","90073","90293","90230",
           "90245","90045"
           ;; Calabasas
           "91604","91423","91403","91436","91316","91356","91364","91367",
           "91302"
           ;; Pasadena
           "90068","91204","90027","90039","90026"
           ;; Pasadena Scheduled Only
           "91105","91030","90041","90029","91205","91206","90042","91103",
           "91106","91104","90065","91101"]}
   {:zone-id 8
    :zone-name "Pasadena Scheduled Only"
    :manually-closed? true
    :closed-message "On-demand service is not available at this location. If you would like to arrange for Scheduled Delivery, please contact: orders@purpleapp.com to coordinate your service."
    :zips [;; Pasadena Scheduled Only
           "91105","91030","90041","90029","91205","91206","90042","91103",
           "91106","91104","90065","91101"]}])


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
         (str "Sorry, today's service hours for this location are "
              (->> (hours-today hours)
                   (map #(str (minute-of-day->hmma (first %))
                              " to "
                              (minute-of-day->hmma (second %))))
                   (interpose " and ")
                   (apply str))
              ". Thank you for your business."))

     :manually-closed?
     (or (:manually-closed? trans) (:manually-closed? base))}))

(defn get-zones-with-zip
  "Get all the zone defitions that contain this ZIP and are active.
  Orders them by rank."
  [zip-code]
  (let [zones zones-test]
    (sort-by :rank (filter #(and (:active %)
                                 (in? (:zips %) zip-code))
                           zones))))

(defn get-zip-def
  "Get the ZIP definition after all transformations are applied.
  If not defined in any market, then nil."
  [zip-code] ; assumes zip-code is 5-digit version
  (reduce apply-trans
          {:zone-names [] ; starts with a fresh breadcrumb
           :zone-ids []} 
          (get-zones-with-zip zip-code)))

(clojure.pprint/pprint (get-zip-def "90025"))

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

(defn order->market-id
  [order]
  (:market-id (get-zip-def (:address_zip order))))

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
