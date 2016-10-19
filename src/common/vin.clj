(ns common.vin
  (:require [clj-http.lite.client :as client]
            [cheshire.core :as cheshire]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as string]
            ))

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

(def vin-batch-url "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVINValuesBatch/")

(defn get-info-batch
  "Given a vector of vin strings, return the corresponding info"
  [vin-vec]
  (try
    (let [vin-string (string/join ";" vin-vec)
          form-params {:data vin-string
                       :format "json"}
          vin-batch-info-raw (-> (client/post
                                  (str vin-batch-url)
                                  {:as :json
                                   :content-type :json
                                   :coerce :always
                                   :throw-exceptions false
                                   :form-params form-params})
                                 :body
                                 (cheshire/parse-string true)
                                 :Results)
          vin-batch-info (->> vin-batch-info-raw
                              (map #(select-keys
                                     %
                                     [:Make :Model :ModelYear :VIN]))
                              (map #(rename-keys
                                     %
                                     {:Make :make
                                      :Model :model
                                      :ModelYear :year
                                      :VIN :vin})))]
      {:success (not (seq (:errors vin-batch-info-raw)))
       :resp vin-batch-info})
    (catch Exception e
      {:success false
       :resp {:error {:message "Unknown error."}}})))
