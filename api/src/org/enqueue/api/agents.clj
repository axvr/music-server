(ns org.enqueue.api.agents
  "Agents are the devices connected to a user's account that users interact
  with Enqueue through.

  An agent using EAT is automatically logged out after 400 days of inactivity."
  (:require [org.enqueue.api.db :as db]
            [org.enqueue.api.users :as users]
            [org.enqueue.api.crypto :as crypto]
            [org.enqueue.api.transit :as transit]
            [org.enqueue.api.agents.eat :as eat]
            [org.enqueue.api.config :as config]
            [org.enqueue.api.agents.middleware :refer [wrap-auth]]
            [org.enqueue.api.router.middleware :refer [wrap-async]])
  (:import [java.util UUID]
           [java.time Instant]))


;;; TODO: clean up


(defn- create-agent [user-id {:keys [name version platform idiom]}]
  ;; TODO: validate agent data.  (Spec?)
  (let [agent-id (UUID/randomUUID)]
    (db/insert!
      :agents
      {:id         agent-id
       :user-id    user-id
       :name       name
       :version    version
       :platform   platform
       :idiom      idiom
       :created-at (Instant/now)
       :access-revoked false})
    agent-id))


;; TODO: better name.
(defn- update-rk-db! [agent-id renewal-key idempotency-key version]
  (db/update! :agents [:= :id agent-id]
              {:renewal-key renewal-key
               :idempotency-key idempotency-key
               :version version
               :last-session (Instant/now)}))


(defn- reply [status message]
  {:status status
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body message})


(defn- create-tokens [agent-id version renewal-key idempotency-key signing-key]
  (if-let [agent (db/query-first
                   {:select [:user-id :renewal-key :idempotency-key :access-revoked]
                    :from [:agents]
                    :where [:= :id agent-id]})]
    (let [user-id         (:agents/user_id agent)
          access-revoked? (:agents/access_revoked agent)]
      (if access-revoked?
        (reply 403 "Access revoked")
        (if-let [rk (cond
                      ;; If idempotency key (and renewal key) was given and
                      ;; matches stored key.
                      (and renewal-key
                           idempotency-key
                           (= idempotency-key (:agents/idempotency_key agent)))
                      (:agents/idempotency_key agent)
                      ;; If renewal key was given and matches stored key.
                      (and renewal-key
                           (= renewal-key (:agents/renewal_key agent)))
                      (eat/generate-renewal-key)
                      ;; If first log in.
                      (= nil renewal-key (:agents/renewal_key agent))
                      (eat/generate-renewal-key))]
          (do
            (update-rk-db! agent-id rk idempotency-key version)
            {:status 200
             :headers {"Content-Type" transit/content-type}
             :body (transit/encode (eat/build-token-pair signing-key user-id agent-id rk))})
          (reply 409 "Renewal token already used"))))
    (reply 404 (str "No such agent"))))


(defn create
  [{:keys [email-address password]}
   {:keys [version] :as agent}
   idempotency-key
   signing-key]
  (let [user (users/find-user-by :email-address email-address)]
    (if (and user
             (crypto/valid-password? (:users/password_hash user) password))
      (let [user-id (:users/id user)
            agent-id (create-agent user-id agent)]
        (create-tokens agent-id version nil idempotency-key signing-key))
      (reply 401 "Invalid credentials"))))


(defn create-handler
  "Expects request body to be in following format:
    {:agent {:name     \"Enqueue\"
             :platform \"Web\"
             :idiom    \"Desktop\"
             :version  \"1\"}
     :credentials {:email-address \"example@example.com\"
                   :password      \"password\"}}"
  [request]
  (let [idempotency-key (get-in request [:headers "Idempotency-Key"])
        body            (transit/decode (:body request))]
    (create (:credentials body) (:agent body) idempotency-key config/signing-key)))


(defn renew
  [{:keys [agent-id renewal-key]}
   {:keys [version]}
   idempotency-key
   signing-key]
  (create-tokens agent-id version renewal-key idempotency-key signing-key))


(defn renew-handler [request]
  (let [signing-key     config/signing-key
        token           (eat/extract-token signing-key request)
        idempotency-key (get-in request [:headers "Idempotency-Key"])
        body            (transit/decode (:body request))]
    (renew token (:agent body) idempotency-key signing-key)))


(defn revoke-access [agent-id]
  (db/update! :agents [:= :id agent-id]
              {:access-revoked  true
               :renewal-key     nil
               :idempotency-key nil}))


(defn revoke-access-handler [request]
  (let [agent-id (get-in request [:token :agent-id])]
    (revoke-access agent-id)
    {:status 204}))


(def agent-routes
  [["/agents/create" {:post {:handler create-handler
                             :middleware [wrap-async]}}]
   ["/agents/renew"  {:post {:handler renew-handler
                             :middleware [wrap-async]}}]
   ["/agents/revoke" {:post {:handler revoke-access-handler
                             :middleware [wrap-async wrap-auth]}}]])
