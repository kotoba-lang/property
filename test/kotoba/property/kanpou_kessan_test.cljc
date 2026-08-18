(ns kotoba.property.kanpou-kessan-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.property.kanpou-kessan :as kk]))

;; Verbatim pdftotext output from 官報 号外第184号 (2026-08-18), two notices:
;; one 後株 (form at the end) and one 前株 (form at the front). Both shapes are
;; ordinary, and a parser anchored to one silently loses the other.
(def section
  (str/join
   "\n"
   ["火曜日"
    ""
    "第 47 期 決 算 公 告"
    "令和８年６月 24 日"
    "福島県郡山市字外河原８番地３"
    ""
    "トヨタＬ＆Ｆ福島株式会社"
    ""
    "代表取締役社長 中澤"
    "俊"
    "貸借対照表の要旨(令和８年３月31日現在)"
    "科"
    "目"
    "金 額(千円)"
    "流 動 資 産"
    "1,318,405"
    "資"
    "本"
    "金"
    "30,000"
    "第 71 期決算公告"
    "令和８年８月18日"
    "茨城県土浦市東真鍋町９番35号"
    "株式会社本田"
    "代表取締役 岡島 正和"
    "貸借対照表の要旨(令和８年６月20日現在)"
    "流 動 資 産"
    "3,189,879"
    "資"
    "本"
    "金"
    "100,000"]))

(deftest wareki-conversion
  (is (= {:year 2026 :month 3 :day 31} (kk/wareki->date "令和８年３月31日現在")))
  (is (= {:year 2026 :month 6 :day 20} (kk/wareki->date "(令和8年6月20日現在)")))
  (testing "元年 is written 元, not 1 — parsing it as a digit yields year 0"
    (is (= {:year 2019 :month 12 :day 31} (kk/wareki->date "令和元年12月31日現在"))))
  (is (= {:year 2018 :month 3 :day 31} (kk/wareki->date "平成30年3月31日")))
  (is (nil? (kk/wareki->date "2026年3月31日")))
  (is (nil? (kk/wareki->date nil))))

(deftest both-name-shapes-parse
  (let [recs (vec (kk/parse-section section "2026-08-18"))]
    (is (= 2 (count recs)) "後株 and 前株 both, or the parser is anchored wrong")
    (let [a (first recs) b (second recs)]
      (is (= "トヨタＬ＆Ｆ福島株式会社" (:company/legal-name a)))
      (is (= 47 (:kessan/period a)))
      (is (= 3 (:company/fiscal-year-end-month a)))
      (is (= "2026-03-31" (:company/fiscal-year-end a)))
      (is (= "福島県郡山市字外河原８番地３" (:company/address a)))
      (testing "capital is printed in 千円 and must not be read as yen"
        (is (= "30000000" (:company/capital-stock-yen a))))
      (is (= "株式会社本田" (:company/legal-name b)))
      (is (= 6 (:company/fiscal-year-end-month b)))
      (testing "the representative director's name never becomes a field"
        (is (not-any? #(re-find #"岡島|中澤" (str %)) (vals b)))))))

(deftest a-block-without-a-balance-sheet-date-is-not-a-record
  (testing "the fiscal year end is the only reason this dataset exists — a row
            without one would be a company name with nothing attached"
    (is (empty? (kk/parse-section "第 5 期 決 算 公 告\n令和８年８月18日\n株式会社なにか\n" "2026-08-18")))))

(deftest split-blocks-tolerates-the-spacing-pdftotext-produces
  (is (= 2 (count (kk/split-blocks section))))
  (is (= [47 71] (mapv :period (kk/split-blocks section)))))
