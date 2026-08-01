(ns kotoba.property.gleif-golden-copy-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.property.gleif-golden-copy :as gc]))

(def header-cells
  ["LEI" "Entity.LegalName" "Entity.LegalAddress.City" "Entity.LegalAddress.Region"
   "Entity.LegalAddress.Country" "Entity.LegalJurisdiction" "Entity.EntityCategory"
   "Entity.EntityStatus" "Entity.LegalForm.EntityLegalFormCode"
   "Entity.RegistrationAuthority.RegistrationAuthorityID"
   "Entity.RegistrationAuthority.RegistrationAuthorityEntityID"
   "Registration.RegistrationStatus" "Registration.LastUpdateDate"])

(def sel (gc/selection (gc/header-index header-cells)))

(def ctx {:observed-at "2026-08-01T10:00:00Z"
          :publish "20260801-0800-gleif-goldencopy-lei2"
          :content-sha256 "abc123"})

(deftest parses-plain-row
  (let [rec (gc/row->record "5493001KJTIIGC8Y1R12,BLOOMBERG FINANCE L.P.,NEW YORK,US-NY,US,US-DE,GENERAL,ACTIVE,XTIQ,RA000602,4171691,ISSUED,2026-05-01" sel)]
    (is (= "5493001KJTIIGC8Y1R12" (:company/lei rec)))
    (is (= "BLOOMBERG FINANCE L.P." (:company/legal-name rec)))
    (is (= "US-DE" (:company/jurisdiction rec)))
    (is (= "US" (:company/country rec)))
    (is (= "4171691" (:company/registration-no rec)))
    (testing "provenance lives in the corpus manifest, not on every record"
      (is (not-any? #(= "source" (namespace %)) (keys rec))))))

(deftest manifest-carries-the-shared-provenance
  (let [m (gc/corpus-manifest (assoc ctx :source-archive "/x/golden-copy.csv.zip"
                                     :record-count 3391413))]
    (is (true? (:corpus/manifest m)))
    (is (= "gleif-lei" (:source/dataset m)))
    (is (= "GLOBAL/GLEIF" (:source/authority m)))
    (is (= "CC0 1.0 (GLEIF)" (:source/licence m)))
    (is (= "abc123" (:source/content-sha256 m)))
    (is (= 3391413 (:corpus/record-count m)))))

(deftest quoted-field-keeps-its-commas
  ;; A legal name with a comma is the single most common quoted field in the
  ;; Golden Copy; splitting on bare commas shifts every later column by one and
  ;; silently files the entity under the wrong jurisdiction.
  (let [rec (gc/row->record "549300ABCDEFGHIJ1234,\"ACME HOLDINGS, INC.\",TOKYO,JP-13,JP,JP,GENERAL,ACTIVE,T4H4,RA000582,0123456789012,ISSUED,2026-05-01" sel)]
    (is (= "ACME HOLDINGS, INC." (:company/legal-name rec)))
    (is (= "JP" (:company/jurisdiction rec)))
    (is (= "0123456789012" (:company/registration-no rec)))))

(deftest doubled-quote-is-a-literal-quote
  (let [cells (gc/parse-csv-line "549300ABCDEFGHIJ1234,\"THE \"\"BIG\"\" COMPANY\",LONDON,,GB,GB,GENERAL,ACTIVE,H0PO,RA000585,01234567,ISSUED,2026-05-01")]
    (is (= "THE \"BIG\" COMPANY" (nth cells 1)))
    (is (= "GB" (nth cells 5)))))

(deftest blank-cells-become-nil-and-are-dropped
  (let [rec (gc/row->record "549300ABCDEFGHIJ1234,SOMECO,,,DE,DE,,ACTIVE,,,,ISSUED," sel)]
    (is (nil? (:company/city rec)))
    (is (not (contains? rec :company/city)))
    (is (not (contains? rec :company/entity-category)))
    (is (= "ACTIVE" (:company/entity-status rec)))))

(deftest row-without-lei-is-rejected
  ;; Returning a blank-LEI entity would create an unjoinable ghost company in
  ;; the query plane, which is worse than dropping the row and counting it.
  (is (nil? (gc/row->record ",NO LEI HERE,,,,,,,,,,," sel))))

(deftest quote-count-detects-an-unterminated-line
  (testing "a field containing a newline arrives as two lines with odd quote counts"
    (is (odd? (gc/quote-count "549300X,\"MULTI")))
    (is (even? (gc/quote-count "549300X,\"MULTI LINE\",TOKYO")))))

(deftest record-start-separates-records-from-wrapped-fragments
  (is (gc/record-start? "\"001GPB6A9XPE8XJICC14\",\"Fidelity Advisor Fund\",\"en\""))
  (testing "the header row is not a record start"
    (is (not (gc/record-start? "\"LEI\",\"Entity.LegalName\",\"Entity.LegalName.xmllang\""))))
  (testing "the tail of an address split across two physical lines is not"
    (is (not (gc/record-start? "Chiyoda-ku\",\"Tokyo\",\"JP\"")))
    (is (not (gc/record-start? "\"001GPB6A9XPE8XJICC1\",")))))

(deftest publish-id-strips-the-file-suffix
  (is (= "20260801-0800-gleif-goldencopy-lei2"
         (gc/publish-id "20260801-0800-gleif-goldencopy-lei2-golden-copy.csv"))))

(deftest header-index-tolerates-column-reordering
  ;; CDF revisions add and move columns; a fixed-offset reader would break
  ;; silently on the next revision.
  (let [reordered ["Entity.LegalName" "LEI" "Entity.LegalJurisdiction"]
        s (gc/selection (gc/header-index reordered))
        rec (gc/row->record "SOMECO,549300ABCDEFGHIJ1234,JP" s)]
    (is (= "549300ABCDEFGHIJ1234" (:company/lei rec)))
    (is (= "SOMECO" (:company/legal-name rec)))
    (is (= "JP" (:company/jurisdiction rec)))))
