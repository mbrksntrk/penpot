;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tasks
  (:require
   [app.metrics :as mtx]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(s/def ::tasks (s/map-of keyword? fn?))

(defmethod ig/pre-init-spec ::registry [_]
  (s/keys :req-un [::mtx/metrics ::tasks]))

(defmethod ig/init-key ::registry
  [_ {:keys [metrics tasks]}]
  (let [mobj (mtx/create
              {:registry (:registry metrics)
               :type :summary
               :labels ["name"]
               :quantiles []
               :name "tasks_timing"
               :help "Background task execution timing."})]
    (reduce-kv (fn [res k v]
                 (let [tname (name k)]
                   (log/debugf "registring task '%s'" tname)
                   (assoc res tname (mtx/wrap-summary v mobj [tname]))))
               {}
               tasks)))
