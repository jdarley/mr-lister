(ns onix.web
  (:require [onix.persistence :as persistence])
  (:require   [cheshire.core :as cheshire]
              [compojure.core :refer [defroutes context GET PUT POST DELETE]]
              [compojure.route :as route]
              [compojure.handler :as handler]
              [ring.middleware.format-response :refer [wrap-json-response]]
              [ring.middleware.params :refer [wrap-params]]
              [ring.middleware.keyword-params :refer [wrap-keyword-params]]
              [clojure.data.xml :refer [element emit-str]]
              [clojure.string :refer [split]]
              [clojure.tools.logging :refer [info warn error]]
              [environ.core :refer [env]]
              [nokia.ring-utils.error :refer [wrap-error-handling error-response]]
              [nokia.ring-utils.metrics :refer [wrap-per-resource-metrics replace-outside-app
                                                replace-guid replace-mongoid replace-number]]
              [nokia.ring-utils.ignore-trailing-slash :refer [wrap-ignore-trailing-slash]]
              [metrics.ring.expose :refer [expose-metrics-as-json]]
              [metrics.ring.instrument :refer [instrument]]))


(def pokemon
"       =,                 +,++++++
       +~              =+++++++++,+  7+
       +~            +++++++++++,++++++++7
       :~,           +,+++++,+++++++++++++:  ++++++7
       ,~~     ++++++++=++++=+++++++++:+++~+++++++++++
       :~~   ++++++++++++++~~~,+++++++++++~,++++++++++++
      +~~~ +++++++++++++++,+~~~~,+++~+++++~~++++++++++++
      ,~~~I+,++++++++++++,+++~~~~++~~~~++~~~++++++,+++++,
      :~~~+++,+++++++++++++++,~~~~~~~,~~~~~~+++++++++++++
      +~~~:++++++++++++++++++~~~~~~~~~~~~:~~~++++++=+++++
      +~~~~+++~+++++++:+++++,~~~~~~~~~~~,~~~~,+++++++++~~
    +++~,~~~~~~~~+++++++++++~~~~,      ~:++++~~~~~~+++~~
   ++++~~~~~~~,~~+++++++++~~~~          ++++~~~~~~~:~~~,~
  +++++~~~~~~,~~,++:+++++~~~,            ,+~~~~~~~,~~~++++
 +,++++:~=++++~~+++++~~~~~~                  =~~~~~++++++++
 +++++++++,+++~~++++++~,~~                  I++++++++++++++
++++++++++++++~~++++++~,                     ++++++++++++~
 ,+++++++++~~~~~+++++,                        +++~~~~~~~~:
,+ ,,++~~~~  ~~~~~~~~,                        ,~~~~~~~~~~
=++ +++~,   ~~~~~~:~,~                         ?++,,~,~~,
 ++=+++~:~++++,~~~~~7                         =+++++~~~~~~
 ++++++~~++++~~,:~                           I+,++++++~~~~,
,+++++~~++++,,,~~                          ++=+++:++++:~~~
 +++++~++++~~,,~                          ++++++++++++~~~~
  ++++~,~~~,,~~:                       I+,++++++,~~~~~~~~
    ~~,,,,,,~~,                      +=+,=++++++~~~~~~~~
     7~~~~~~                        ++++++,+++=~~~~,,
                                 ++++,++++~~~~~~~~~
                                ++++++++++,~~~~~,
                               ++++++~,,~~~~~~
                                ++++,~::~~~~~
                             7+++++~~,~~~
                             +++,~~~~~~~,
                            ++++++,:~~~
                            +++,~,~~~
                             ~~~~~,~~
                               ~~~,~+
                                 ,~~,++,+++, ++~
                                    ~~,~~~~~~~~~
                                       ,~~,~~ ,
                                                            ")

(def json-content-type "application/json;charset=UTF-8")
(def text-plain-type "text/plain;charset=UTF-8")

(def ^:dynamic *version* "none")
(defn set-version! [version]
  (alter-var-root #'*version* (fn [_] version)))

(defn response [data content-type & [status]]
  {:status (or status 200)
   :headers {"Content-Type" content-type}
   :body data})

(defn status
  []
  (let [dynamo-ok (persistence/dynamo-health-check)]
    (->
     {:name "onix"
      :version *version*
      :success dynamo-ok
      :dependencies [{:name "dynamodb" :success dynamo-ok}]}
     (cheshire/generate-string)
     (response json-content-type))))

(defn- create-application
  [req]
  (let [body (cheshire/parse-string (slurp (:body req)))
        result (persistence/create-application body)]
    (response (cheshire/generate-string body) json-content-type 201)))

(defn- list-applications
  []
  (->
   (persistence/list-applications)
   (cheshire/generate-string)
   (response json-content-type)))

(defn- jsonify-values
  [map]
  (let [name (:name map)
        m (dissoc map :name)
        n (reduce (fn [r [k v]] (assoc r k (cheshire/parse-string v))) {} m)]
    (assoc n :name name)))

(defn- get-application
  "Returns the application with the given name, or '404' if it doesn't exist."
  [application-name]
  (if-let [application (persistence/get-application application-name)]
    (->
     application
     (jsonify-values)
     (doto (prn))
     (cheshire/generate-string)
     (response json-content-type))
    (error-response (str "Application named: '" application-name "' does not exist.") 404)))

(defn- put-application-metadata-item
  [application-name key req]
  (let [body (cheshire/parse-string (slurp (:body req)) true)]
    (->
     (persistence/update-application-metadata application-name key (:value body))
     (response json-content-type 201))))

;; (defn- put-application-metadata-item
;;   [application-name key req]
;;   (if-let [application (persistence/get-application application-name)]
;;     (let [body (slurp (:body req))
;;           json (cheshire/parse-string body true)]
;;       (if-let [value (:value json)]
;;         (do (->
;;              application
;;              (doto (prn))
;;              (assoc (keyword key) (cheshire/generate-string value))
;;              (persistence/create-application))
;;             (response json json-content-type))
;;         (error-response (str "No value supplied. Please supply json with key 'value' and arbitrary json as the value.") 400)))
;;     (error-response (str "Can't put data for key '" key "' because the application '" application-name "' does not exist.") 400)))

(defn- get-application-metadata-item
  "Get a piece of metadata for an application. Returns 404 if either the application or the metadata is not found"
  [application-name key]
  (if-let [application (persistence/get-application application-name)]
    (if-let [value ((keyword key) application)]
      (response {:value (cheshire/parse-string value)} json-content-type)
      (error-response (str "Can't find metadata '" key "' for application '" application-name "'.") 404))
    (error-response (str "Can't find application '" application-name "'.") 404)))

(defroutes applications-routes

  (POST "/" req
        (create-application req))

  (GET "/" []
       (list-applications))

  (GET "/:application" [application]
       (get-application application))

  (GET "/:application/:key" [application key]
       (get-application-metadata-item application key))

  (PUT "/:application/:key" [application key :as req]
       (put-application-metadata-item application key req)))

(defroutes routes
  (context
   "/1.x" []

   (GET "/ping"
        [] "pong")

   (GET "/status"
        [] (status))

   (GET "/pokemon"
        [] (response pokemon "text/plain;charset=UTF-8"))

   (context "/applications"
            [] applications-routes))

  (route/not-found (error-response "Resource not found" 404)))

(def app
  (-> routes
      (instrument)
      (wrap-error-handling)
      (wrap-ignore-trailing-slash)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-json-response)
      (wrap-per-resource-metrics [replace-guid replace-mongoid replace-number (replace-outside-app "/1.x")])
      (expose-metrics-as-json)))
