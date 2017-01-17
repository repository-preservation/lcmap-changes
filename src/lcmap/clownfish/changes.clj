(ns lcmap.clownfish.changes
  (:require [camel-snake-kebab.core :refer [->snake_case_keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [cheshire.core :as json]
            [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [compojure.core :refer :all]
            [langohr.exchange :as le]
            [langohr.basic :as lb]
            [lcmap.commons.tile :as tile]
            [lcmap.clownfish.db :as db]
            [lcmap.clownfish.event :refer [amqp-channel]]
            [lcmap.clownfish.html :as html]
            [lcmap.clownfish.middleware :refer [wrap-handler]]
            [mount.core :as mount :refer [defstate]]
            [qbits.hayt :as hayt]
            [ring.util.accept :refer [accept]]
            [schema.core :as schema]))

(defn allow [& verbs]
  (log/debug "explaining allow verbs")
  {:status 405
   :headers {"Allow" (str/join "," verbs)}})

;;; Request entity transformers.

(defn decode-json
  ""
  [body]
  (log/debug "req - decoding as JSON")
  (->> body
       (slurp)
       (json/decode)
       (transform-keys ->snake_case_keyword)))

(defn prepare-with
  "Request transform placeholder."
  [request]
  (log/debugf "req - prepare body: %s" (get-in request [:headers]))
  (if (= "application/json" (get-in request [:headers "content-type"]))
    (update request :body decode-json)
    request))

;;; Response entity transformers.

(defn to-html
  "Encode response body as HTML."
  [response]
  (log/debug "responding with HTML")
  (let [template-fn (:template (meta response) html/default)]
    (update response :body template-fn)))

(defn to-json
  "Encode response body as JSON."
  [response]
  (log/debug "responding with json")
  (-> response
      (update :body json/encode)
      (assoc-in [:headers "Content-Type"] "application/json")))

(def supported-types (accept "text/html" to-html
                             "application/json" to-json
                             "*/*" to-json))

(defn respond-with
  [request response]
  (supported-types request response))

;;; request handler helpers
(defn algorithm-available?
  [{:keys [algorithm]}]
  (db/execute (hayt/select :algorithms (hayt/where [[= :algorithm algorithm]
                                                    [= :enabled true]]))))

(defn source-data-available?
  [{:keys [x y]}]
  true)

(defn send-event [ticket]
  true)

(def tile-spec {:tile_x 10 :tile_y 10 :shift_x 0 :shift_y 0})

(defn build-input-url [data]
  "the url")

(defn snap [x y]
  (tile/snap x y tile-spec))

(defn stale?
  [change-result]
  false)

(defn get-change-results
  [{:keys [x y algorithm] :as data}]
   (let [[tile_x, tile_y] (snap x y)]
     (first (db/execute (hayt/select :results
                              (hayt/where [[= :tile_x tile_x]
                                           [= :tile_y tile_y]
                                           [= :algorithm algorithm]
                                           [= :x x]
                                           [= :y y]]))))))
(defn get-ticket
  [{:keys [x y algorithm] :as data}]
  (let [results (get-change-results data)
        {:keys [tile_update_ended tile_update_requested]} results]
    ())

  (select-keys
   (get-change-results data)
   [:tile_x
    :tile_y
    :algorithm
    :x
    :y
    :tile_update_requested
    :tile_update_began
    :tile_update_ended
    :inputs_url]))
       ;; where tile_update_ended > tile_update_requested

(defn create-ticket
  [{:keys [x y algorithm] :as data}]
  (let [[tile_x tile_y] (tile/snap x y tile-spec)
        ticket {:tile_x tile_x
                :tile_y tile_y
                :algorithm algorithm
                :x x
                :y y
                :tile_update_requested (time/now)
                :tile_update_began (long 0)
                :tile_update_ended (long 0)
                :inputs_url (build-input-url data)}]
    (send-event ticket)
    (db/execute (hayt/insert :results (hayt/values ticket)))
    ticket))

(defn schedule
  [{:keys [x y algorithm] :as data}]
  (or (get-ticket data)(create-ticket data)))

;;;; Request Handlers
;;; It is critical point to be made that as the code is currently structured,
;;; the parameters and return values of request handler functions
;;; control the versioned resource interface.  Care must be taken to not
;;; inadvertently alter the resource interface by changing either the
;;; parameters or returns.  Once clojure 1.9 is generally available, the
;;; interface will be able to be described via clojure.spec.  Until then,
;;; be careful!
(defn get-changes
  [{{x :x y :y a :algorithm r :refresh :or {r false}} :params}]
  (let [data    {:x x :y y :algorithm a :refresh (boolean r)}
        results (get-change-results data)]
       (if (and results (not (nil? (:result results))) (not (:refresh data)))
        {:status 200 :body (merge data {:changes results})}
        (let [src? (future (source-data-available? data))
              alg? (algorithm-available? data)
              valid? {:algorithm-available alg? :source-data-available? @src?}]
             (if (not-every? true? (vals valid?))
               {:status 422 :body (merge data valid?)}
               {:status 202 :body (merge data {:ticket (schedule data)})})))))

;;;; Resources
(defn resource
  "Handlers for changes resource."
  []
  (wrap-handler
   (context "/changes/v0-beta" request
     (GET "/" []
          (with-meta {:status 200}
            {:template html/default}))
     (GET "/:algorithm{.+}/:x{\\d.+}/:y{\\d.+}" []
          (with-meta (get-changes request)
            {:template html/default}))
     (ANY "/" []
          (with-meta (allow ["GET"])
            {:template html/default}))
     (GET "/problem/" []
          {:status 200 :body "problem resource"}))
   prepare-with respond-with))
