(ns kotoba.property.export-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.property :as prop]
            [kotoba.property.export :as ex]))
(deftest csv-export
  (let [csv (ex/parcels->csv [(prop/parcel "P1" :address "1 Main St")])]
    (is (re-find #"parcel_id,address,area_m2,zoning" csv))
    (is (re-find #"P1,1 Main St" csv))))
(deftest json-export-escapes-every-c0-control-character
  ;; RFC 8259 requires EVERY control character U+0000-U+001F to be
  ;; escaped, not just \ " and \n -- an operator-supplied lease tenant
  ;; field containing a raw tab or other control byte would otherwise be
  ;; copied through raw, producing invalid JSON (verified against
  ;; Python's strict json module).
  (let [ls [(prop/lease "L1" "P1" (str "Jane" (char 9) "Doe" (char 1) "Corp")
              "Landlord Inc" 1000 "2026-01-01" "2027-01-01")]
        j (ex/leases->json ls)]
    (is (str/includes? j "\"tenant\":\"Jane\\tDoe\\u0001Corp\""))))
