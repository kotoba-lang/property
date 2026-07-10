(ns kotoba.ownership-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.property.ownership :as ownership]))

(def public-claim
  {:ownership/id "example:1"
   :ownership/parcel "example-parcel:1"
   :ownership/holder "Example public authority"
   :ownership/holder-kind :public-body
   :ownership/source "example-source"
   :ownership/observed-at "2026-07-10"
   :ownership/licence "Example public-data licence"
   :ownership/disclosure :public})

(deftest validate-public-claim-test
  (is (= {:ownership/valid? true}
         (ownership/validate-claim public-claim)))
  (is (= :natural-person-not-permitted
         (:ownership/error
          (ownership/validate-claim
           (assoc public-claim :ownership/holder-kind :natural-person)))))
  (is (= :not-publicly-disclosed
         (:ownership/error
          (ownership/validate-claim
           (assoc public-claim :ownership/disclosure :not-published))))))

(deftest query-contract-test
  (is (= :find (first ownership/public-claims-by-parcel-query)))
  (is (some #{'[?claim :ownership/disclosure :public]}
            ownership/public-claims-by-parcel-query)))
