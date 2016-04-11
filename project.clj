(defproject common "1.0.4-SNAPSHOT"
  :description "Common library for Purple web servers"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/java.jdbc "0.2.3"] ;; required by db.clj
                 [mysql/mysql-connector-java "5.1.18"] ;; required by db.clj
                 [c3p0/c3p0 "0.9.1.2"] ;; required by db.clj
                 [cheshire "5.4.0"] ; required by users, orders
                 [com.amazonaws/aws-java-sdk "1.9.24"] ;; this will be used by clj-aws below instead of its default aws version
                 [clj-aws "0.0.1-SNAPSHOT"] ;; required by util.clj
                 [clj-time "0.8.0"] ; required by util.clj
                 [crypto-password "0.1.3"] ; required by users.clj
                 [com.draines/postal "1.11.3"] ;; required by util.clj
                 [clj-http "1.0.1"] ;; required by payment.clj - must be above com.twilio.sdk as it pulls in an outdated version of org.apache.http.impl.client.HttpClients that is not compatible with analytics-clj
                 [com.twilio.sdk/twilio-java-sdk "4.2.0"] ;; required util.clj
                 [environ "1.0.0"] ;; required by config.clj
                 [analytics-clj "0.3.0"] ;; required by payment.clj
                 [version-clj "0.1.2"] ;; required by util.clj
                 ]
  :pendantic? :warn
  :java-source-paths ["src/java"]
  )
