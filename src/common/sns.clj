(ns common.sns
  (:import [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.services.sns AmazonSNSClient]
           [com.amazonaws.services.sns.model PublishRequest
            CreatePlatformEndpointRequest]))

;; copied from
;; https://github.com/pingles/clj-aws/blob/master/src/clj_aws/core.clj#L4
(defn credentials
  [access-key secret]
  (BasicAWSCredentials. access-key secret))

;; copied from
;; https://github.com/pingles/clj-aws/blob/master/src/clj_aws/sns.clj#L8
(def endpoints {:us-east "sns.us-east-1.amazonaws.com"
                :us-west "sns.us-west-1.amazonaws.com"
                :eu-west "sns.eu-west-1.amazonaws.com"
                :ap-south "sns.ap-southeast-1.amazonaws.com"
                :ap-north "sns.ap-northeast-1.amazonaws.com"})

;; copied from
;; https://github.com/pingles/clj-aws/blob/master/src/clj_aws/sns.clj#L14
(defn client
  [credentials & {:keys [region] :or {region :us-east}}]
  (doto (AmazonSNSClient. credentials)
    (.setEndpoint (region endpoints))))
