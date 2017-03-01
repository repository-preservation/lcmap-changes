(ns lcmap.clownfish.results
  (:require [clojure.tools.logging :as log]
            [digest]
            [lcmap.clownfish.db :as db]
            [lcmap.clownfish.algorithm :as alg]
            [lcmap.clownfish.state :refer [tile-specs]]
            [lcmap.clownfish.ticket :as ticket]
            [lcmap.commons.numbers :refer [numberize]]
            [lcmap.commons.tile :refer [snap]]
            [qbits.hayt :as hayt]))

(defn retrieve
  "Returns change results or nil"
  [{:keys [x y algorithm] :as data}]
  (let [[tile_x, tile_y] (snap x y (first tile-specs))]
    (->> (hayt/where [[= :tile_x tile_x]
                      [= :tile_y tile_y]
                      [= :algorithm algorithm]
                      [= :x x]
                      [= :y y]])
         (hayt/select :results)
         (db/execute)
         (first))))

(defn save
  "Saves algorithm results"
  [{:keys [x y algorithm inputs_md5 result result_md5 result_ok result_produced] :as data}]
  (let [[tile_x, tile_y] (snap (int x) (int y) (first tile-specs))
        change-result {:tile_x (int tile_x)
                       :tile_y (int tile_y)
                       :x (int x)
                       :y (int y)
                       :algorithm algorithm
                       :inputs_md5 inputs_md5
                       :result result
                       :result_md5 result_md5
                       :result_ok result_ok
                       :result_produced result_produced}]
    (db/execute (hayt/insert :results (hayt/values change-result)))
    change-result))

;;; TODO - replace with implementation
(defn source-data-available?
  [{:keys [x y]}]
  true)

(defn schedule
  "Schedules algorithm execution while preventing duplicates"
  [{:keys [x y algorithm refresh] :as data}]
  (log/infof "scheduling: %s" data)
  (or (and (not refresh)
           (retrieve data))
      (ticket/create data)))

(defn get-results
  "HTTP request handler to get algorithm results"
  [algorithm x y {{r :refresh :or [r false]} :params :as req}]
  (log/tracef "get-changes :: params - %s" req)
  (let [data    {:x (numberize x)
                 :y (numberize y)
                 :algorithm algorithm
                 :refresh (Boolean/valueOf r)}
        results (retrieve data)]
    (log/tracef "get-changes results: %s" results)
    (if (and results (not (nil? (:result results))) (not (:refresh data)))
      (do (log/infof "returning results for %s" (dissoc data :refresh))
          {:status 200 :body (merge data results)})
      (let [src?   (future (source-data-available? data))
            alg?   (alg/available? data)
            valid? {:algorithm-available alg? :source-data-available @src?}]
        (if (not-every? true? (vals valid?))
          {:status 422 :body (merge data valid?)}
          {:status 202 :body (merge data valid? (schedule data))})))))
