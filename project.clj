(defproject common "2.0.2-SNAPSHOT"
  :description "Common library for Purple web servers"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/java.jdbc "0.2.3"] ;; required by db.clj
                 [mysql/mysql-connector-java "5.1.18"] ;; required by db.clj
                 [c3p0/c3p0 "0.9.1.2"] ;; required by db.clj
                 [cheshire "5.4.0"] ; required by users, orders
                 [com.amazonaws/aws-java-sdk "1.9.24"]
                 [clj-time "0.8.0"] ; required by util.clj
                 [crypto-password "0.1.3"] ; required by users.clj
                 [com.draines/postal "1.11.3"] ;; required by util.clj
                 [clj-http "2.2.0"] ;; required by payment.clj
                 [clj-http-lite "0.3.0"] ;; required by vin.clj, alternative used due to 'connection reset' error thrown when retrieving https://vpic.nhtsa.dot.gov/api using clj-http
                 [com.twilio.sdk/twilio-java-sdk "5.11.0"] ;; required util.clj
                 [environ "1.0.0"] ;; required by config.clj
                 [analytics-clj "0.3.0"] ;; required by payment.clj
                 [version-clj "0.1.2"] ;; required by util.clj
                 [bouncer "1.0.0"]]
  :pedantic? false
  :java-source-paths ["src/java"]
  :profiles {:dev [{:dependencies
                    [[javax.servlet/servlet-api "2.5"]
                     [ring/ring-mock "0.3.0"]
                     [org.seleniumhq.selenium/selenium-java "2.47.1"]
                     [clj-webdriver "0.7.2"]
                     [ring "1.5.0"]
                     [pjstadig/humane-test-output "0.6.0"]]
                    :injections
                    [(require 'pjstadig.humane-test-output)
                     (pjstadig.humane-test-output/activate!)]}
                   ;; :profiles/dev
                   :profiles/local
                   ]})
