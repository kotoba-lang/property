(ns kotoba.property.export-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.property :as prop]
            [kotoba.property.export :as ex]))
(deftest csv-export
  (let [csv (ex/parcels->csv [(prop/parcel "P1" :address "1 Main St")])]
    (is (re-find #"parcel_id,address,area_m2,zoning" csv))
    (is (re-find #"P1,1 Main St" csv))))
