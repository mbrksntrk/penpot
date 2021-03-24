;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.worker
  "Async tasks abstraction (impl)."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.util.async :as aa]
   [app.util.log4j :refer [update-thread-context!]]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   org.eclipse.jetty.util.thread.QueuedThreadPool
   java.util.concurrent.ExecutorService
   java.util.concurrent.Executors
   java.util.concurrent.Executor))

(s/def ::executor #(instance? Executor %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::name ::us/string)
(s/def ::min-threads ::us/integer)
(s/def ::max-threads ::us/integer)
(s/def ::idle-timeout ::us/integer)

(defmethod ig/pre-init-spec ::executor [_]
  (s/keys :opt-un [::min-threads ::max-threads ::idle-timeout ::name]))

(defmethod ig/prep-key ::executor
  [_ cfg]
  (merge {:min-threads 0
          :max-threads 256
          :idle-timeout 60000
          :name "worker"}
         cfg))

(defmethod ig/init-key ::executor
  [_ {:keys [min-threads max-threads idle-timeout name]}]
  (doto (QueuedThreadPool. (int max-threads)
                           (int min-threads)
                           (int idle-timeout))
    (.setStopTimeout 500)
    (.setName name)
    (.start)))

(defmethod ig/halt-key! ::executor
  [_ instance]
  (.stop ^QueuedThreadPool instance))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare event-loop-fn)
(declare instrument-tasks)

(s/def ::queue ::us/string)
(s/def ::parallelism ::us/integer)
(s/def ::batch-size ::us/integer)
(s/def ::tasks (s/map-of string? fn?))
(s/def ::poll-interval ::dt/duration)

(defmethod ig/pre-init-spec ::worker [_]
  (s/keys :req-un [::executor
                   ::mtx/metrics
                   ::db/pool
                   ::batch-size
                   ::name
                   ::poll-interval
                   ::queue
                   ::tasks]))

(defmethod ig/prep-key ::worker
  [_ cfg]
  (merge {:batch-size 2
          :name "worker"
          :poll-interval (dt/duration {:seconds 5})
          :queue "default"}
         cfg))

(defmethod ig/init-key ::worker
  [_ {:keys [pool poll-interval name queue] :as cfg}]
  (log/infof "starting worker '%s' on queue '%s'" name queue)
  (let [close-ch (a/chan 1)
        poll-ms  (inst-ms poll-interval)]
    (a/go-loop []
      (let [[val port] (a/alts! [close-ch (event-loop-fn cfg)] :priority true)]
        (cond
          ;; Terminate the loop if close channel is closed or
          ;; event-loop-fn returns nil.
          (or (= port close-ch) (nil? val))
          (log/infof "stop condition found; shutdown worker: '%s'" name)

          (db/pool-closed? pool)
          (do
            (log/info "worker eventloop is aborted because pool is closed")
            (a/close! close-ch))

          (and (instance? java.sql.SQLException val)
               (contains? #{"08003" "08006" "08001" "08004"} (.getSQLState ^java.sql.SQLException val)))
          (do
            (log/error "connection error, trying resume in some instants")
            (a/<! (a/timeout poll-interval))
            (recur))

          (and (instance? java.sql.SQLException val)
               (= "40001" (.getSQLState ^java.sql.SQLException val)))
          (do
            (log/debug "serialization failure (retrying in some instants)")
            (a/<! (a/timeout poll-ms))
            (recur))

          (instance? Exception val)
          (do
            (log/errorf val "unexpected error ocurried on polling the database (will resume in some instants)")
            (a/<! (a/timeout poll-ms))
            (recur))

          (= ::handled val)
          (recur)

          (= ::empty val)
          (do
            (a/<! (a/timeout poll-ms))
            (recur)))))

    (reify
      java.lang.AutoCloseable
      (close [_]
        (a/close! close-ch)))))


(defmethod ig/halt-key! ::worker
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))

;; --- INSTRUMENTATION

(defn- instrument!
  [registry]
  (mtx/instrument-vars!
   [#'submit-task]
   {:registry registry
    :type :counter
    :labels ["name"]
    :name "tasks_submit_total"
    :help "A counter of task submissions."
    :wrap (fn [rootf mobj]
            (let [mdata (meta rootf)
                  origf (::original mdata rootf)]
              (with-meta
                (fn [conn params]
                  (let [tname (:name params)]
                    (mobj :inc [tname])
                    (origf conn params)))
                {::original origf})))})

  (mtx/instrument-vars!
   [#'app.worker/run-task]
   {:registry registry
    :type :summary
    :quantiles []
    :name "tasks_checkout_timing"
    :help "Latency measured between scheduld_at and execution time."
    :wrap (fn [rootf mobj]
            (let [mdata (meta rootf)
                  origf (::original mdata rootf)]
              (with-meta
                (fn [tasks item]
                  (let [now (inst-ms (dt/now))
                        sat (inst-ms (:scheduled-at item))]
                    (mobj :observe (- now sat))
                    (origf tasks item)))
                {::original origf})))}))

;; --- SUBMIT

(s/def ::task  (s/or :str ::us/string :kwd ::us/keyword))
(s/def ::delay (s/or :int ::us/integer
                     :duration dt/duration?))
(s/def ::queue ::task)
(s/def ::conn some?)
(s/def ::priority ::us/integer)
(s/def ::max-retries ::us/integer)

(s/def ::submit-options
  (s/keys :req [::task ::conn]
          :opt [::delay ::queue ::priority ::max-retries]))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, clock_timestamp() + ?)
   returning id")

(defn- extract-props
  [options]
  (persistent!
   (reduce-kv (fn [res k v]
                (cond-> res
                  (not (qualified-keyword? k))
                  (assoc! k v)))
              (transient {})
              options)))

(defn submit!
  [{:keys [::task ::delay ::queue ::priority ::max-retries ::conn]
    :or {delay 0 queue :default priority 100 max-retries 3}
    :as options}]
  (us/verify ::submit-options options)
  (let [duration  (dt/duration delay)
        interval  (db/interval duration)
        props     (-> options extract-props db/tjson)
        id        (uuid/next)]
    (log/debugf "submit task '%s' to be executed in '%s'" (name task) (str duration))
    (db/exec-one! conn [sql:insert-new-task id (name task) props (name queue) priority max-retries interval])
    id))

;; --- RUNNER


(def ^:private
  sql:mark-as-retry
  "update task
      set scheduled_at = clock_timestamp() + ?::interval,
          modified_at = clock_timestamp(),
          error = ?,
          status = 'retry',
          retry_num = retry_num + ?
    where id = ?")

(def default-delay
  (dt/duration {:seconds 10}))

(defn- mark-as-retry
  [conn {:keys [task error inc-by delay]
         :or {inc-by 1 delay default-delay}}]
  (let [explain (ex-message error)
        delay   (db/interval delay)
        sqlv    [sql:mark-as-retry delay explain inc-by (:id task)]]
    (db/exec-one! conn sqlv)
    nil))

(defn- mark-as-failed
  [conn {:keys [task error]}]
  (let [explain (ex-message error)]
    (db/update! conn :task
                {:error explain
                 :modified-at (dt/now)
                 :status "failed"}
                {:id (:id task)})
    nil))

(defn- mark-as-completed
  [conn {:keys [task] :as cfg}]
  (let [now (dt/now)]
    (db/update! conn :task
                {:completed-at now
                 :modified-at now
                 :status "completed"}
                {:id (:id task)})
    nil))

(defn- decode-task-row
  [{:keys [props] :as row}]
  (when row
    (cond-> row
      (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props)))))

(defn- handle-task
  [tasks {:keys [name] :as item}]
  (let [task-fn (get tasks name)]
    (if task-fn
      (task-fn item)
      (log/warnf "no task handler found for '%s'" (pr-str name)))
    {:status :completed :task item}))

(defn get-error-context
  [error item]
  (let [edata (ex-data error)]
    {:id      (uuid/next)
     :data    edata
     :params  item}))

(defn- handle-exception
  [error item]
  (let [edata (ex-data error)]
    (if (and (< (:retry-num item)
                (:max-retries item))
             (= ::retry (:type edata)))
      (cond-> {:status :retry :task item :error error}
        (dt/duration? (:delay edata))
        (assoc :delay (:delay edata))

        (= ::noop (:strategy edata))
        (assoc :inc-by 0))

      (let [cdata (get-error-context error item)]
        (update-thread-context! cdata)
        (log/errorf error "unhandled exception on task (id: '%s')" (:id cdata))
        (if (>= (:retry-num item) (:max-retries item))
          {:status :failed :task item :error error}
          {:status :retry :task item :error error})))))

(defn- run-task
  [{:keys [tasks]} item]
  (try
    (log/debugf "started task '%s/%s/%s'" (:name item) (:id item) (:retry-num item))
    (handle-task tasks item)
    (catch Exception e
      (handle-exception e item))
    (finally
      (log/debugf "finished task '%s/%s/%s'" (:name item) (:id item) (:retry-num item)))))

(def sql:select-next-tasks
  "select * from task as t
    where t.scheduled_at <= now()
      and t.queue = ?
      and (t.status = 'new' or t.status = 'retry')
    order by t.priority desc, t.scheduled_at
    limit ?
      for update skip locked")

(defn- event-loop-fn*
  [{:keys [pool executor batch-size] :as cfg}]
  (db/with-atomic [conn pool]
    (let [queue (:queue cfg)
          items (->> (db/exec! conn [sql:select-next-tasks queue batch-size])
                     (map decode-task-row)
                     (seq))
          cfg  (assoc cfg :conn conn)]

      (if (nil? items)
        ::empty
        (let [proc-xf (comp (map #(partial run-task cfg %))
                            (map #(px/submit! executor %)))]
          (->> (into [] proc-xf items)
               (map deref)
               (run! (fn [res]
                       (case (:status res)
                         :retry (mark-as-retry conn res)
                         :failed (mark-as-failed conn res)
                         :completed (mark-as-completed conn res)))))
          ::handled)))))

(defn- event-loop-fn
  [{:keys [executor] :as cfg}]
  (aa/thread-call executor #(event-loop-fn* cfg)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scheduler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare schedule-task)
(declare synchronize-schedule)

(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::id ::us/string)
(s/def ::cron dt/cron?)
(s/def ::props (s/nilable map?))
(s/def ::task keyword?)

(s/def ::scheduled-task-spec
  (s/keys :req-un [::id ::cron ::task]
          :opt-un [::props]))

(s/def ::schedule (s/coll-of (s/nilable ::scheduled-task-spec)))

(defmethod ig/pre-init-spec ::scheduler [_]
  (s/keys :req-un [::executor ::db/pool ::schedule ::tasks]))

(defmethod ig/init-key ::scheduler
  [_ {:keys [schedule tasks] :as cfg}]
  (let [scheduler (Executors/newScheduledThreadPool (int 1))
        schedule  (->> schedule
                       (filter some?)
                       (map (fn [{:keys [task] :as item}]
                              (let [f (get tasks (name task))]
                                (when-not f
                                  (ex/raise :type :internal
                                            :code :task-not-found
                                            :hint (str/fmt "task %s not configured" task)))
                                (-> item
                                    (dissoc :task)
                                    (assoc :fn f))))))
        cfg       (assoc cfg
                         :scheduler scheduler
                         :schedule schedule)]

    (synchronize-schedule cfg)
    (run! (partial schedule-task cfg)
          (filter some? schedule))

    (reify
      java.lang.AutoCloseable
      (close [_]
        (.shutdownNow ^ExecutorService scheduler)))))

(defmethod ig/halt-key! ::scheduler
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))

(def sql:upsert-scheduled-task
  "insert into scheduled_task (id, cron_expr)
   values (?, ?)
       on conflict (id)
       do update set cron_expr=?")

(defn- synchronize-schedule-item
  [conn {:keys [id cron]}]
  (let [cron (str cron)]
    (log/infof "initialize scheduled task '%s' (cron: '%s')" id cron)
    (db/exec-one! conn [sql:upsert-scheduled-task id cron cron])))

(defn- synchronize-schedule
  [{:keys [pool schedule]}]
  (db/with-atomic [conn pool]
    (run! (partial synchronize-schedule-item conn) schedule)))

(def sql:lock-scheduled-task
  "select id from scheduled_task where id=? for update skip locked")

(defn exception->string
  [error]
  (with-out-str
    (.printStackTrace ^Throwable error (java.io.PrintWriter. *out*))))

(defn- execute-scheduled-task
  [{:keys [executor pool] :as cfg} {:keys [id] :as task}]
  (letfn [(run-task [conn]
            (try
              (when (db/exec-one! conn [sql:lock-scheduled-task id])
                (log/debugf "executing scheduled task '%s'" id)
                ((:fn task) task))
              (catch Throwable e
                e)))

          (handle-task []
            (db/with-atomic [conn pool]
              (let [result (run-task conn)]
                (when (ex/exception? result)
                  (log/errorf result "unhandled exception on scheduled task '%s'" id)))))]

    (try
      (px/run! executor handle-task)
      (finally
        (schedule-task cfg task)))))

(defn- ms-until-valid
  [cron]
  (s/assert dt/cron? cron)
  (let [now  (dt/now)
        next (dt/next-valid-instant-from cron now)]
    (inst-ms (dt/duration-between now next))))

(defn- schedule-task
  [{:keys [scheduler] :as cfg} {:keys [cron] :as task}]
  (let [ms (ms-until-valid cron)]
    (px/schedule! scheduler ms (partial execute-scheduled-task cfg task))))
