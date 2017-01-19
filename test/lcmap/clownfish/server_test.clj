(ns lcmap.clownfish.server-test
  "Full integration tests."
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [lcmap.clownfish.server :as server]
            [lcmap.clownfish.shared :refer :all]))

(deftest changes-tests
  (with-system
    (testing "entry-point"
      (let [resp (req :get "http://localhost:5679/changes")]
        (is (= 200 (:status resp)))))
    (testing "search for an unsupported type still returns JSON"
      (let [resp (req :get "http://localhost:5679/changes"
                      :headers {"Accept" "application/foo"})]
        (is (= 200 (:status resp)))
        (is (= "application/json" (get-in resp [:headers :content-type])))))))

(deftest changes-health-resource
  (with-system
    (testing "health check"
      (let [resp (req :get "http://localhost:5679/changes/health"
                            :headers {"Accept" "*/*"})]
        (is (= 200 (:status resp)))))))
