(ns kotoba.property.press-wire-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.property.press-wire :as pw]))

;; PR TIMES の index.rdf の実物から 1 件（2026-08-19 取得）。目次の <rdf:li> も
;; 一緒に置いてある —— あれを item と数える実装は 2 倍の件数を報告する。
(def rdf
  (str "<rdf:RDF><items><rdf:Seq>"
       "<rdf:li rdf:resource=\"https://prtimes.jp/main/html/rd/p/000000136.000143568.html\" />"
       "<rdf:li rdf:resource=\"https://prtimes.jp/main/html/rd/p/000000167.000126693.html\" />"
       "</rdf:Seq></items>"
       "<item rdf:about=\"https://prtimes.jp/main/html/rd/p/000000167.000126693.html\">"
       "<title>松茸の香りと蟹の旨みを一度に堪能！</title>"
       "<link>https://prtimes.jp/main/html/rd/p/000000167.000126693.html</link>"
       "<description>[甲羅本店] 本文…</description>"
       "<dc:corp>株式会社甲羅</dc:corp>"
       "<business_form>企業・官公庁・団体</business_form>"
       "<dc:date>2026-08-19T10:00:00+09:00</dc:date>"
       "</item></rdf:RDF>"))

(deftest parses-items-not-the-table-of-contents
  (let [items (vec (pw/parse-items rdf (:prtimes pw/distributors) "2026-08-19T02:00:00Z"))]
    (testing "<rdf:li> は目次であって item ではない"
      (is (= 1 (count items))))
    (let [r (first items)]
      (is (= "株式会社甲羅" (:press/company-name r)))
      (is (= "https://prtimes.jp/main/html/rd/p/000000167.000126693.html" (:press/url r)))
      (is (= "2026-08-19T10:00:00+09:00" (:press/published-at r)))
      (is (= "prtimes" (:press/distributor r)))
      (is (= "JP/PR-TIMES" (:source/authority r)))
      (testing "本文は保存しない — 発表者の著作物であって、こちらが持つのは引用の形だけ"
        (is (not-any? #(re-find #"本文" (str %)) (vals r)))))))

(deftest release-key-is-the-url
  (testing "同じリリースを 2 度書かない鍵。フィードは最新分しか持たないので、
            貯めるのはこちら側で、鍵はリリース自身が持つ一意な値でなければならない"
    (let [r (first (pw/parse-items rdf (:prtimes pw/distributors) "x"))]
      (is (= (:press/url r) (pw/release-key r))))))

(deftest unresolved-names-are-kept
  (let [r (first (pw/parse-items rdf (:prtimes pw/distributors) "x"))]
    (testing "名寄せできたら法人番号を付ける"
      (let [linked (pw/with-company r {:houjin-bangou "1234567890123"
                                       :legal-name "株式会社甲羅" :match :exact})]
        (is (= "1234567890123" (:company/houjin-bangou linked)))
        (is (= :exact (:company/name-match linked)))))
    (testing "できなくても捨てない — 配信サイトの名乗りは登記上の商号とは限らない"
      (let [unlinked (pw/with-company r nil)]
        (is (nil? (:company/houjin-bangou unlinked)))
        (is (= "株式会社甲羅" (:press/company-name unlinked)))))))

(deftest distributors-record-why-each-one-is-used
  (let [p (:prtimes pw/distributors)]
    (is (= "https://prtimes.jp/index.rdf" (:feed-url p)))
    (testing "窓の広さを持っておく — 巡回間隔はここから決まる"
      (is (= 200 (:window-items p))))
    (testing "robots.txt で feed を禁じている配信サイトは登録しない"
      (is (nil? (:valuepress pw/distributors)))
      (is (nil? (:dreamnews pw/distributors))))))
