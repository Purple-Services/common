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
