;; Market

{:name "Los Angeles"
 :gas-price-index {"87" 289
                   "91" 315}
 :delivery-fee-index {60 599
                      180 399
                      300 299}
 ;; considered open if current time is between any of the pairs
 ;; times are defined as "number of minutes since beginning of day"
 :default-hours [[450 1350]]
 ;; if closed message is nil then the hardcoded message is used:
 ;; "Sorry, the service hours for this ZIP code are 7:30am to 8:30pm, Monday to Friday. Thank you for your business."
 :default-closed-message nil
 :zips {"90025" {;; applied before diff-percent
                 :gas-price-diff-fixed {"87" 0
                                        "91" 0}
                 ;; e.g., $3.59 + ($3.59 * 0.015) = $3.64  (use -1.5 to make it cheaper)
                 :gas-price-diff-percent {"87" 1.5
                                          "91" 1.5}
                 :delivery-fee-diff-fixed {60 0
                                           180 0
                                           300 0}
                 :delivery-fee-diff-percent {60 0
                                             180 0
                                             300 0}
                 ;; exclusion feature will be implemented at a later time
                 ;; :exclusions [{:circle {:lat 91.99222
                 ;;                        :lng -118.12332
                 ;;                        :radius 500}}]
                 }}

 }