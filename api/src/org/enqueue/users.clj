(ns org.enqueue.users
  (:require [org.enqueue.db :as db]
            [org.enqueue.crypto :as crypto]))


(defn- find-user-by [& {:keys [id email-address]}]
  (let [query {:select [:id :email-address]
               :from [:users]}
        where (cond id [:= :id id]
                    email-address [:= :email-address email-address])]
    (when where
      (db/execute-one! (assoc query :where where)))))


(defn- create-user [email-address password]
  (if (nil? (find-user-by :email-address email-address))
    (let [user-id (java.util.UUID/randomUUID)
          hashed-password (crypto/hash-password password)]
      (db/execute-one!
        {:insert-into [:users]
         :columns [:id :email-address :password-hash :created-at]
         :values [[user-id email-address hashed-password (java.time.Instant/now)]]})
      user-id)
    (throw (Exception. "User account with that email address already exists."))))


;; TODO: authentication tokens.
;; TODO: registration.
;; TODO: log in.
;; TODO: auth middleware.
;; TODO: email confirmation.
;; TODO: change password.
;; TODO: reset password.
;; TODO: delete account.
