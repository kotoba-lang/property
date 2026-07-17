(ns kotoba.hmlr-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.property.hmlr :as hmlr]))

(deftest normalize-corporate-hmlr-row
  (is (= {:ownership/id "hmlr-uk-corporate-property:AB123:00000006"
          :ownership/parcel "GB-HMLR:AB123"
          :ownership/holder "Example Holdings Ltd"
          :ownership/holder-id "00000006"
          :ownership/holder-kind :company
          :ownership/source "hmlr-uk-corporate-property"
          :ownership/observed-at "2026-07-10"
          :ownership/licence "HMLR dataset-specific licence"
          :ownership/disclosure :public}
         (hmlr/normalize-row
          "hmlr-uk-corporate-property" "2026-07-10"
          {"Title Number" "AB123"
           "Company Registration No. (Proprietor)" "00000006"
           "Proprietor Name (Company)" "Example Holdings Ltd"}))))
