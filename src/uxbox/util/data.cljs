(ns uxbox.util.data
  "A collection of data transformation utils."
  (:require [cljs.reader :as r]
            [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data structure manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn index-by
  "Return a indexed map of the collection
  keyed by the result of executing the getter
  over each element of the collection."
  [coll getter]
  (persistent!
   (reduce #(assoc! %1 (getter %2) %2) (transient {}) coll)))

(def ^:static index-by-id #(index-by % :id))

(defn remove-nil-vals
  "Given a map, return a map removing key-value
  pairs when value is `nil`."
  [data]
  (into {} (remove (comp nil? second) data)))

(defn without-keys
  "Return a map without the keys provided
  in the `keys` parameter."
  [data keys]
  (persistent!
   (reduce #(dissoc! %1 %2) (transient data) keys)))

(defn index-of
  "Return the first index when appears the `v` value
  in the `coll` collection."
  [coll v]
  (first (keep-indexed (fn [idx x]
                         (when (= v x) idx))
                       coll)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Numbers Parsing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nan?
  [v]
  (js/isNaN v))

(defn read-string
  [v]
  (r/read-string v))

(defn parse-int
  ([v]
   (js/parseInt v 10))
  ([v default]
   (let [v (js/parseInt v 10)]
     (if (or (not v) (nan? v))
       default
       v))))

(defn parse-float
  ([v]
   (js/parseFloat v))
  ([v default]
   (let [v (js/parseFloat v)]
     (if (or (not v) (nan? v))
       default
       v))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Other
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn classnames
  [& params]
  {:pre [(even? (count params))]}
  (str/join " " (reduce (fn [acc [k v]]
                          (if (true? v)
                            (conj acc (name k))
                            acc))
                        []
                        (partition 2 params))))
