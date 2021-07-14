(ns org.enqueue.api.core
  (:require
    [ring.adapter.jetty                :refer [run-jetty]]
    [ring.middleware.params            :refer [wrap-params]]
    [ring.middleware.multipart-params  :refer [wrap-multipart-params]]
    [ring.middleware.resource          :refer [wrap-resource]]
    [ring.middleware.content-type      :refer [wrap-content-type]]
    [ring.middleware.not-modified      :refer [wrap-not-modified]]
    [org.enqueue.api.router            :refer [router fallback-routes]]
    [org.enqueue.api.router.middleware :refer [wrap-ignore-trailing-slash
                                               wrap-security-headers
                                               wrap-async]]
    [org.enqueue.api.users             :refer [user-routes]]))


(defn home-handler [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (str "<title>Enqueue API</title>"
              "<h1>Enqueue API</h1>"
              "<p>Your digital music collection, anywhere.</p>")})


(defn- build-route-map []
  (concat
    [["/" {:get {:handler home-handler
                 :middleware [wrap-async]}}]]
    user-routes
    fallback-routes))


(def cors-origins #{"https://www.enqueue.org"
                    "https://enqueue.org"
                    "https://api.enqueue.org"})


(def xss-origins #{"enqueue.org" "*.enqueue.org"})


(def app-handler
  (-> build-route-map
      (router cors-origins)
      (wrap-security-headers xss-origins)
      (wrap-resource "public")
      wrap-content-type
      wrap-not-modified
      wrap-params
      wrap-multipart-params
      wrap-ignore-trailing-slash))


(defn run [{:keys [port]}]
  ;; TODO: other Jetty options (doc run-jetty).
  (run-jetty
    #'app-handler
    {:port port
     :async? true
     :send-server-version? false}))
