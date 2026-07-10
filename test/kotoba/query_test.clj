(ns kotoba.query-test
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as d]
            [kotoba.property.ownership :as ownership]))

(deftest public-ubo-query-runs-on-datascript
  (let [db (d/db-with (d/empty-db)
                      [{:ubo/id "companies-house-psc:00000006:abc"
                        :ubo/company-id "00000006"
                        :ubo/person-id "companies-house-psc:abc"
                        :ubo/person-name "Example Person"
                        :ubo/control :ownership-of-shares-25-to-50-percent
                        :ubo/source "companies-house-psc"
                        :ubo/observed-at "2026-07-10T00:00:00Z"
                        :ubo/disclosure :public}])]
    (is (= #{["companies-house-psc:00000006:abc"
              "Example Person"
              :ownership-of-shares-25-to-50-percent
              "companies-house-psc"
              "2026-07-10T00:00:00Z"]}
           (d/q ownership/public-ubo-by-company-query db "00000006")))))
