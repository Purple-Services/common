(ns common.vin
  (:require [http.async.client :as http]
            [cheshire.core :as cheshire]
            [clojure.set :refer [rename-keys]]))

(def vin-url  "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/")

(defn get-info
  "Given vin, a string vin number, return a map of the vehicle's information
  of the following format:
  {:make <make>       ; string
   :model <model>     ; string
   :model-year <year> ; string
  }"
  [vin]
  (with-open [client (http/create-client)]
    (let [response (http/GET client
                             (str vin-url
                                  vin
                                  "?format=json"))]
      (-> response
          http/await
          http/string
          (cheshire/parse-string true)
          :Results
          first
          (select-keys [:Make :Model :ModelYear])
          (rename-keys {:Make :make :Model :model :ModelYear :model-year})))))
