(ns kotoba.property.gbizinfo-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.property.gbizinfo :as gb]))

;; Shapes taken from live responses on 2026-08-18 (公開されている動作確認用トークン).
(def subsidy-info
  {"corporate_number" "1000020012131"
   "name" "苫小牧市"
   "subsidy" [{"date_of_approval" "2024-08-27"
               "title" "循環型社会形成推進交付金"
               "amount" "130000"
               "target" nil
               "government_departments" "環境省"}
              {"title" "" "amount" "1"}]})

(def finance-info
  {"corporate_number" "6010001096659"
   "name" "株式会社うるる"
   "finance" {"accounting_standards" nil
              "fiscal_year_cover_page" "第26期(自　2025年４月１日　至　2026年３月31日)"
              "management_index" [{"period" "3" "net_sales_summary_of_business_results" 2000000000}
                                  {"period" "4" "net_sales_summary_of_business_results" 2857535000}]
              "major_shareholders" []}})

(deftest fiscal-year-end-is-read-from-the-closing-date
  (testing "full-width digits, both parenthesis styles"
    (is (= 3 (gb/fiscal-year-end-month "第26期(自　2025年４月１日　至　2026年３月31日)")))
    (is (= 3 (gb/fiscal-year-end-month "第17期（自　2024年４月１日　至　2025年３月31日）")))
    (is (= 12 (gb/fiscal-year-end-month "第5期(自 2024年1月1日 至 2024年12月31日)"))))
  (testing "and nothing is invented when there is nothing to read — absence is
            normal here, gBizINFO carries filings only for some companies"
    (is (nil? (gb/fiscal-year-end-month nil)))
    (is (nil? (gb/fiscal-year-end-month "")))
    (is (nil? (gb/fiscal-year-end-month "第26期")))))

(deftest subsidy-records-are-one-entity-each
  (let [recs (vec (gb/subsidy-records "1000020012131" subsidy-info))]
    (testing "a blank title is not a grant"
      (is (= 1 (count recs))))
    (let [r (first recs)]
      (is (= :subsidy (:grant/kind r)))
      (is (= "1000020012131" (:company/houjin-bangou r)))
      (is (= "循環型社会形成推進交付金" (:grant/title r)))
      (is (= "130000" (:grant/amount-yen r)))
      (is (= "環境省" (:grant/ministry r)))
      (testing "a null field is absent, not nil-valued"
        (is (not (contains? r :grant/target)))))))

(deftest finance-record-carries-the-latest-period
  (let [r (gb/finance-record "6010001096659" finance-info)]
    (is (= 3 (:company/fiscal-year-end-month r)))
    (testing "the most recent period wins, not the first one in the array"
      (is (= "2857535000" (:company/net-sales-yen r))))
    (is (= "6010001096659" (:company/houjin-bangou r))))
  (testing "a company gBizINFO has no filings for produces no record at all —
            an empty finance entity would read as 'measured, and it is zero'"
    (is (nil? (gb/finance-record "1" {"finance" {"fiscal_year_cover_page" nil
                                                 "management_index" []}})))))

(deftest manifest-says-which-token-produced-it
  (let [m (gb/corpus-manifest {:observed-at "2026-08-18T00:00:00Z"
                               :aspects [:subsidy :finance]
                               :numbers 16
                               :record-count 27
                               :token-kind :published-demo})]
    (is (= "gbizinfo" (:source/dataset m)))
    (testing "a bounded sample on the published demo token and a full pass on
              the operator's token must be distinguishable from the artifact"
      (is (= :published-demo (:source/token m)))
      (is (= 16 (:projection/number-count m))))))

(deftest aspect-urls
  (is (= "https://api.info.gbiz.go.jp/hojin/v2/hojin/1000020012131/subsidy"
         (gb/aspect-url "1000020012131" :subsidy)))
  (is (= "https://api.info.gbiz.go.jp/hojin/v2/hojin/1000020012131/procurement"
         (gb/aspect-url "1000020012131" :procurement))))
