(ns org.enqueue.api.db
  (:require [org.enqueue.api.config :as config]
            [org.enqueue.api.helpers :refer [in?]]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn]
            [next.jdbc.plan :as jdbc-plan]
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


(defonce ds (init-db-conn config/db))
(defonce ^:dynamic *conn* ds)


;;; -----------------------------------------------------------
;;; DB interaction functions


(defmacro ^:private def-db-fn
  "Macro that generates a wrapper function around a next.jdbc function to
  automatically inject the DB connection source and compile HoneySQL."
  [fn-name fn-base]
  `(let [meta# (meta #'~fn-base)
         args# (:arglists meta#)
         sql-idx# (some #(let [i# (.indexOf % (symbol "sql-params"))]
                           (when-not (neg? i#) (dec i#)))
                        args#)
         fqn# (str (:ns meta#) "/" (:name meta#))]
     (defn ~fn-name
       {:doc (str "\n  This is a wrapper function around `" fqn# "` that automatically"
                  "\n  injects the DB connection source and compiles HoneySQL expressions."
                  "\n\n-------------------------\n"
                  fqn# "\n"
                  args# "\n\n  "
                  (:doc meta#))
        :arglists (->> args#
                       (filter #(in? % (symbol "connectable")))
                       (map (comp vec rest)))}
       [& params#]
       (apply
         ~fn-base
         *conn*
         (if sql-idx#
           (update (vec params#) sql-idx# #(if (map? %) (sql/format %) %))
           params#)))))


(def-db-fn exec! jdbc/execute!)
(def-db-fn exec1! jdbc/execute-one!)
(def-db-fn plan jdbc/plan)
(def-db-fn select! jdbc-plan/select!)
(def-db-fn select-one! jdbc-plan/select-one!)


(defn insert!
  "Insert a record into the specified DB table.  Values is a hash-map of
  column-names to their corresponding values."
  [table values]
  (exec1! {:insert-into [table]
           :columns     (keys values)
           :values      [(vals values)]}))


(defn update!
  "Update a value in the specified DB table.  Where is a "
  [table where changes]
  (exec1! {:update [table]
           :set    changes
           :where  where}))


(defn delete! [table where]
  (exec1! {:delete-from [table]
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
  (require 'org.enqueue.api.db)

  ;; Run migration.
  (org.enqueue.api.db/migrate)

  ;; Rollback migration.
  (org.enqueue.api.db/rollback))
