(ns kotoba.property.kanpou-chotatsu-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.property.kanpou-pua :as pua]
            [kotoba.property.kanpou-chotatsu :as kc]))

(defn- marker
  "落札公示の項番 n を PUA 文字として作る（版面が使っているのと同じ符号）。"
  [n]
  (str (char (+ 0xE7D0 n))))

(defn- pua-digits [s]
  (str/join (map (fn [c]
                   (let [d (int c)]
                     (if (and (>= d (int \0)) (<= d (int \9)))
                       (str (char (+ 0xE88A (- d (int \0)))))
                       (str c))))
                 s)))

;; 2026-08-18 政府調達第152号 の落札公示 1 件を、版面と同じ符号で組んだもの。
(def section
  (str "次のとおり落札者等について公示します。\n"
       "令和８年８月 18 日\n"
       "契約責任者 東日本高速道路株式会社 関東支社 支社長 金田 泰明\n"
       "（埼玉県さいたま市大宮区桜木町一丁目11番地20）\n"
       "［掲載順序］\n"
       (marker 1) "品目分類番号 " (marker 2) "調達件名及び数量 " (marker 6) "落札者の氏名及び住所\n"
       "◎調達機関番号 417 ◎所在地番号 11\n"
       (marker 1) "41 " (marker 2) "長野自動車道 明科トンネル補強工事 "
       (marker 3) "購入等 " (marker 4) "一般 " (marker 5) " 8. 6. 8 "
       (marker 6) "鹿島建設株式\n会社（埼玉県さいたま市大宮区大門町二丁目118番地） "
       (marker 7) (pua-digits "7") (str (char 0xEA75)) (pua-digits "304")
       (str (char 0xEA75)) (pua-digits "000") (str (char 0xEA75)) (pua-digits "000") "円 "
       (marker 8) " 7.10.17\n"))

(deftest pua-digits-decode
  (testing "版面の数字は私用領域にある。突き合わせで確かめた写像しか使わない"
    (is (= "152" (pua/normalize (str (char 0xE88B) (char 0xE88F) (char 0xE88C)))))
    (is (= "7,304" (pua/normalize (str (char 0xE891) (char 0xEA75)
                                       (char 0xE88D) (char 0xE88A) (char 0xE88E)))))))

(deftest code-point-is-portable
  (testing "(int c) は ClojureScript では NaN を返す — 項番が値でも区切りでもない
            何かとして素通りし、出力からは空白と見分けがつかなくなる"
    (is (= 0xE7D1 (pua/code-point (first (marker 1)))))
    (is (true? (pua/marker? (first (marker 6)))))
    (is (= 6 (pua/marker-index (first (marker 6)))))))

(deftest award-row-parses
  (let [recs (vec (kc/parse-section section "2026-08-18"))]
    (is (= 1 (count recs)) "凡例の行はレコードにしない（6 と 7 が揃っていない）")
    (let [r (first recs)]
      (is (= "procurement" (:grant/kind r)) "gBizINFO の調達レコードと同じ属性")
      (testing "社名と住所は分ける。段の折り返しで社名の途中に改行が入るので、
                空白を落としてから括弧で切らないと社名に住所がくっつく"
        (is (= "鹿島建設株式会社" (:company/legal-name r)))
        (is (= "埼玉県さいたま市大宮区大門町二丁目118番地" (:company/address r))))
      (is (= "7304000000" (:grant/amount-yen r)))
      (testing "和暦 8.6.8 は令和8年6月8日"
        (is (= "2026-06-08" (:grant/date r))))
      (is (= "長野自動車道 明科トンネル補強工事" (:grant/title r)))
      (testing "発注機関は残すが、担当者の氏名は残さない"
        (is (= "東日本高速道路株式会社 関東支社" (:grant/ministry r)))
        (is (not-any? #(re-find #"金田" (str %)) (vals r))))
      (is (= "417" (:award/agency-code r))))))

(deftest a-row-without-a-price-is-not-an-award
  (testing "掲載順序の凡例も同じ項番を持つ。6 と 7 の両方が要る"
    (is (empty? (kc/parse-section (str (marker 1) "41 " (marker 6) "鹿島建設株式会社") "2026-08-18")))))

(deftest wareki-ymd
  (is (= "2026-06-08" (kc/wareki-ymd " 8. 6. 8")))
  (is (= "2025-10-17" (kc/wareki-ymd " 7.10.17")))
  (is (nil? (kc/wareki-ymd "令和8年6月8日")))
  (is (nil? (kc/wareki-ymd nil))))
