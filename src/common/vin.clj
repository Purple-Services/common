(ns common.vin
  (:require [clj-http.lite.client :as client]
            [cheshire.core :as cheshire]
            [clojure.set :refer [rename-keys]]))

(def vin-url  "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/")

(defn get-info
  "Given a vin string, return the corresponding make, model and year."
  [vin]
  (try
    (let [resp (-> (client/get
                    (str vin-url vin "?format=json")
                    {:as :json
                     :content-type :json
                     :coerce :always
                     :throw-exceptions false})
                   :body
                   (cheshire/parse-string true)
                   :Results
                   first
                   (select-keys [:Make :Model :ModelYear])
                   (rename-keys {:Make :make
                                 :Model :model
                                 :ModelYear :year}))]
      {:success (not (seq (:errors resp)))
       :resp resp})
    (catch Exception e
      {:success false
       :resp {:error {:message "Unknown error."}}})))
