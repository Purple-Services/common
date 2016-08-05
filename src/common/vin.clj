(ns common.vin
  (:require [clj-http.client :as client]
            [common.config :as config]))


(defn get-info
  [vin]
  (try
    (let [resp (-> (client/get
                    (str "https://vpic.nhtsa.dot.gov/api/vehicles/decodevin/"
                         vin
                         "?format=json")
                    {:as :json
                     :content-type :json
                     :coerce :always
                     :throw-exceptions false})
                   :body
                   :Results)]
      {:success (not (seq (:errors resp)))
       :resp resp})
    (catch Exception e
      {:success false
       :resp {:error {:message "Unknown error."}}})))


(-> (client/get
     (str "https://vpic.nhtsa.dot.gov/api/vehicles/decodevin/"
          "JTHBP1BL0GA000567"
          "?format=json"))
    {:as :json
     :content-type :json
     :coerce :always
     :throw-exceptions false
     :debug true
     })
    :body
    :Results)


(clj-http.client/get "https://vpic.nhtsa.dot.gov/api/vehicles/decodevin/JTHBP1BL0GA000567?format=json"
                     {:as :json
                      :content-type :json
                      :coerce :always
                      :throw-exceptions false
                      })
