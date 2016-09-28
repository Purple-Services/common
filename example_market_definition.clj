;; Market

{:name "Los Angeles"
 :gallon-choices {:0 7.5
                  :1 10
                  :2 15}
 :default-gallon-choice :2 ; NOTE, use key
 :gas-price {"87" 320
             "91" 339}
 :time-choices {:0 60
                :1 180
                :2 300}
 :default-time-choice 180 ; NOTE, use value, not key here
 :delivery-fee {60 599
                180 399
                300 349}
 :tire-pressure-price 700
 ;; considered open if current time is between any of the pairs
 ;; times are defined as "number of minutes since beginning of day"
 :hours [[[450 1350]]
         [[450 1350]]
         [[450 1350]]
         [[450 1350]]
         [[450 1350]]
         [[450 1350]
          [1400 1430]]
         [[450 1350]]]
 ;; if closed message is nil then the hardcoded message is used:
 ;; "Sorry, the service hours for this ZIP code are
 ;; 7:30am to 8:30pm, Monday to Friday. Thank you for your business."
 :closed-message nil
 :manually-closed? false
 :zips {"90024" {:gallon-choices {:0 7.5
                                  :1 10
                                  :2 15
                                  :3 20}
                 :default-gallon-choice :3
                 ;; e.g., $3.59 + ($3.59 * 0.015) = $3.64
                 ;; (use e.g. -1.5 to make it less than price index)
                 :gas-price-diff-percent {"87" 1.5}
                 ;; applied before diff-percent
                 :gas-price-diff-fixed {"87" 2
                                        "91" 5}
                 :delivery-fee-diff-percent {60 0
                                             180 0
                                             300 0}
                 :delivery-fee-diff-fixed {60 25
                                           180 25
                                           300 0}
                 :manually-closed? false
                 :closed-message "Sorry, closed."
                 ;; exclusion feature will be implemented at a later time
                 ;; :exclusions [{:circle {:lat 91.99222
                 ;;                        :lng -118.12332
                 ;;                        :radius 500}}]
                 }
        "90025" {:gas-price-diff-percent {"87" 0
                                          "91" 0}
                 :gas-price-diff-fixed {"87" 0
                                        "91" 0}
                 :delivery-fee-diff-percent {60 0
                                             180 0
                                             300 0}
                 :delivery-fee-diff-fixed {60 0
                                           180 0
                                           300 0}
                 :manually-closed? false
                 :closed-message "Sorry, this ZIP closed for no reason."}}}





;; NEW VERSION:

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
