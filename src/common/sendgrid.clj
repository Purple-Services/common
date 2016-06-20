(ns common.sendgrid
  (:require [clojure.string :as s]
            [clj-http.client :as client]
            [common.config :as config]))

(def common-opts
  {:as :json
   :content-type :json
   :coerce :always
   :throw-exceptions false
   :headers {"Authorization" (str "Bearer " config/sendgrid-api-key)}})

(defn- request
  [endpoint & [params headers]]
  (try (let [resp (-> (client/post (str config/sendgrid-api-url endpoint)
                                   (merge-with merge 
                                               common-opts
                                               {:form-params params}
                                               {:headers headers}))
                      :body)]
         {:success (not (seq (:errors resp)))
          :resp resp})
       (catch Exception e
         {:success false
          :resp {:error {:message "Unknown error."}}})))

(defn- send-email
  [to from subject payload]
  (request "mail/send"
           (merge {:personalizations [{:to [{:email to}]
                                       :subject subject}]
                   :from {:email from}}
                  payload)))

(defn send-text-email
  [to subject message & {:keys [from] :or {from config/sendgrid-default-from}}]
  (send-email to from subject {:content [{:type "text" :value message}]}))

(defn send-html-email
  [to subject message & {:keys [from template-id]
                         :or {from config/sendgrid-default-from
                              template-id config/sendgrid-default-template-id}}]
  (send-email to from subject {:content [{:type "text/html" :value message}]
                               :template_id template-id}))
