(ns org.enqueue.api.core
  (:require [clojure.main            :as main]
            [clojure.set             :as set]
            [clojure.string          :as str]
            [io.pedestal.http        :as http]
            [io.pedestal.http.route  :as route]
            [io.pedestal.interceptor :as interceptor]
            [org.enqueue.api.clients :as clients]
            [org.enqueue.api.config  :as config]
            [org.enqueue.api.docs    :as docs]
            [org.enqueue.api.transit.interceptors :refer [transit-in-interceptor]]
            [org.enqueue.api.users   :as users])
  (:import [org.eclipse.jetty.server.handler.gzip GzipHandler]))

(def root
  {:name ::index
   :enter
   #(assoc % :response {:status 301
                        :headers {"Location" "/docs"}})})

(def privacy-headers
  (interceptor/interceptor
    {:name  ::privacy-headers
     :leave #(assoc-in % [:response :headers "Permissions-Policy"] "interest-cohort=()")}))

;; https://github.com/pedestal/pedestal/blob/master/samples/servlet-filters-gzip/src/gzip/service.clj
(defn- context-configurator
  [context]
  (let [gzip-handler (GzipHandler.)]
    (.setGzipHandler context gzip-handler)
    context))

(defn build-routes []
  (route/expand-routes
    (set/union
     #{["/" :get root]}
     users/routes
     clients/routes
     docs/routes)))

(if config/prod?
  (do
    (defonce route-atom (atom (build-routes)))
    (defn routes [] @route-atom))
  (defn routes [] (build-routes)))

(defn- start-nrepl-server
  "Start an nREPL server if the required dependencies are in the class."
  []
  (if (try
        (require 'org.enqueue.api.nrepl)
        true
        (catch Exception _ false))
    (let [start! (ns-resolve 'org.enqueue.api.nrepl 'start!)]
      (start! {}))
    (throw (ex-info "Cannot start nREPL server, API not started with `:nrepl` alias." {}))))


(defonce server (atom nil))

(defn start
  [{:keys [port join?]
    :or   {port  (:port config/server)
           join? (:join? config/server true)}}]
  ;; TODO: disallow multiple instances.
  (swap! server
          ;; TODO: on-shutdown!
         (fn [_]
           ;; TODO: configure TLS, HSTS header + automatic redirect.
           ;; - <http://pedestal.io/reference/service-map>
           ;; - <https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/strict-transport-security>
           (-> {::http/routes routes
                ::http/type   :jetty
                ::http/join?  join?
                ::http/port   port
                ::http/resource-path "public"
                ::http/allowed-origins (get-in config/server [:origins :cors])
                ::http/secure-headers
                {:hsts-settings "max-age=31536000; includeSubdomains"
                 :frame-options-settings "deny"
                 :content-type-settings "nosniff"
                 :xss-protection-settings "1; mode=block"
                 :download-options-settings "noopen"
                 :cross-domain-policies-settings "none"
                 :content-security-policy-settings
                 (str "default-src 'self' "
                      (str/join " " (get-in config/server [:origins :xss])))}
                ::http/container-options {:context-configurator context-configurator}}
               http/default-interceptors
               (update ::http/interceptors conj
                       privacy-headers
                       (transit-in-interceptor))
               http/create-server
               http/start))))

(defn stop []
  (swap! server #(and (http/stop %) nil)))

(defn run
  ([]
   (run {}))
  ([{:keys [nrepl? server?]
     :or {server? true}
     :as opts}]
   (when config/prod?
     ;; Disable assertions on production environment.
     (set! *assert* false))
   (when nrepl?
     ;; Start local nREPL server for editors to connect to.
     (start-nrepl-server))
   (if server?
     (start opts)
     (main/repl))))
