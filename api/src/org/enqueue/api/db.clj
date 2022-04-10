(ns org.enqueue.api.db
  (:require [org.enqueue.api.config :as config]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn]
            next.jdbc.date-time
            [honey.sql :as sql]
            ragtime.jdbc
            ragtime.repl)
  (:import [com.zaxxer.hikari HikariDataSource]))


;;; -----------------------------------------------------------
;;; DB connection pool


(defn- init-db-conn [conf]
  (let [conf (if (:username conf)
               conf
               ;; HikariCP uses :username instead of :user.
               (assoc conf :username (:user conf)))
        ^HikariDataSource ds (conn/->pool HikariDataSource conf)]
    ;; Initialise the pool and validate config.
    (when-not config/test?
      (.close (jdbc/get-connection ds)))
    ;; Close the connection pool on JVM shutdown.
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. (bound-fn [] (when ds (.close ds)))))
    ds))


(def ^:private ds (init-db-conn config/db))

(def ^:dynamic *conn* ds)


;;; -----------------------------------------------------------
;;; DB interaction functions


(def ->sql sql/format)
(def execute! (partial jdbc/execute! *conn*))
(def execute-one! (partial jdbc/execute-one! *conn*))


(defn query
  ([sql]
   (execute! (->sql sql)))
  ([sql opts]
   (execute! (->sql sql) opts)))


(defn query-first
  ([sql]
   (execute-one! (->sql sql)))
  ([sql opts]
   (execute-one! (->sql sql) opts)))


(defn insert! [table values]
  (query-first {:insert-into [table]
                :columns     (keys values)
                :values      [(vals values)]}))


(defn update! [table where changes]
  (query-first {:update [table]
                :set    changes
                :where  where}))


(defn delete! [table where]
  (query-first {:delete-from [table]
                :where       where}))


(defmacro with-transaction
  "Perform all database operations in body as a transaction."
  [& body]
  `(jdbc/with-transaction [tx# ds]
      (binding [*conn* tx#]
        ~@body)))


(defmacro with-connection
  "Perform all database operations in body using a single connection."
  [& body]
  `(with-open [conn# (jdbc/get-connection ds)]
     (binding [*conn* conn#]
       ~@body)))


;;; -----------------------------------------------------------
;;; Migrations


(def ^:private ragtime-config
  {:datastore  (ragtime.jdbc/sql-database
                 config/db
                 {:migrations-table "ragtime_migrations"})
   :migrations (ragtime.jdbc/load-resources "migrations")})


(defn migrate []
  (ragtime.repl/migrate ragtime-config))


(defn rollback []
  (ragtime.repl/rollback ragtime-config))


(comment
  (require 'org.enqueue.db)

  ;; Run migration.
  (org.enqueue.db/migrate)

  ;; Rollback migration.
  (org.enqueue.db/rollback))
