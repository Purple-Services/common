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


(clj-http.client/get
 "https://vpic.nhtsa.dot.gov/api/vehicles/decodevinvalues/JTHBA1D29G5032349?format=json"
 {:throw-exceptions false
  :as :json
  :headers {"Host" "vpic.nhtsa.dot.gov"
            "Upgrade-Insecure-Requests" "1"
            "User-Agent" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36"
            "Accept" "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            "Accept-Encoding" "gzip, deflate, sdch, br"
            "Accept-Language" "en-US,en;q=0.8,de;q=0.6"
            "Cache-Control" "max-age=0"
            "Connection" "keep-alive"}})
