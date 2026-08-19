(ns kotoba.property.gyousei-review-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kotoba.property.gyousei-review :as gr]))

(deftest recipient-columns-are-read-by-name-not-letter
  ;; 列文字は年度版で動く。ヘッダ名から block（支出区分）と順位を取る。
  (is (= {:block "A" :rank 1 :field :houjin-bangou}
         (gr/recipient-column "支出先上位１０者リスト-A.支払先-1-法人番号-01")))
  (is (= {:block "C" :rank 7 :field :amount-million-jpy}
         (gr/recipient-column "支出先上位１０者リスト-C.支払先-7-支出額（百万円）-01")))
  (is (nil? (gr/recipient-column "事業名")))
  ;; 知らないフィールドは拾わない（勝手に別の意味を与えない）。
  (is (nil? (gr/recipient-column "支出先上位１０者リスト-A.支払先-1-入札者数（応募者数）-01"))))

(deftest organisation-is-decided-by-number-first
  (testing "法人番号があれば確実に組織（個人には無い）"
    (is (true? (gr/organization? "なんとか" "1234567890123"))))
  (testing "無い場合は名前の形"
    (is (true? (gr/organization? "株式会社ステージ" nil)))
    ;; ⚠ 法人格の語だけを見た最初の版は 13,669 件を落とし、点検したら個人ではなく
    ;; 協議会・センター・委員会・法務局・海外法人ばかりだった（実測 2026-08-19）。
    (is (true? (gr/organization? "萱瀬地区中山間地域所得向上対策協議会" nil)))
    (is (true? (gr/organization? "林業技能向上センター" nil)))
    (is (true? (gr/organization? "山形地方法務局" nil)))
    (is (true? (gr/organization? "Corsearch Europe" nil)) "海外法人はラテン文字で拾う"))
  (testing "人名の形は marker に当たっても落とす"
    (is (false? (gr/organization? "山田 太郎" nil)))
    (is (false? (gr/organization? "" nil)))
    (is (false? (gr/organization? nil nil)))))

(deftest a-record-keeps-the-declared-unit
  (let [r (gr/recipient-record
           {:program {:grant/ministry "内閣官房" :grant/title "内閣人事局経費"}
            :block "A" :rank 1 :fiscal-year "2024"
            :fields {:name "株式会社ステージ" :houjin-bangou "3013301015869"
                     :amount-million-jpy "1" :contract-method "一般競争入札"
                     :winning-rate "0.95"}})]
    (is (= "株式会社ステージ" (:grant/recipient-name r)))
    (is (= "3013301015869" (:company/houjin-bangou r)))
    (is (= (:company/houjin-bangou r) (:company/registration-no r)))
    ;; **円に正規化しない。** シート上の申告は百万円単位で丸めが入っており、
    ;; gBizINFO の `:grant/amount-yen` と足し合わせられないことを名前で言う。
    (is (= "1" (:grant/amount-million-jpy r)))
    (is (nil? (:grant/amount-yen r)))
    (is (= 1 (:review/rank r)))
    (is (= "内閣官房" (:grant/ministry r)))))

(deftest a-non-organisation-is-dropped-not-anonymised
  ;; 「個人イ」「媒体Ａ」のような匿名化された個人が実在する。
  (doseq [nm ["個人イ" "日系人Ｊ" "媒体Ａ" "山田 太郎"]]
    (is (nil? (gr/recipient-record {:program {} :block "A" :rank 1
                                    :fields {:name nm}}))
        (str "kept: " nm))))

(deftest manifest-carries-every-denominator
  (let [m (gr/corpus-manifest {:observed-at "2026-08-19T00:00:00Z"
                               :record-count 17808 :programs 5441
                               :recipients-seen 80319 :organisations-seen 73543
                               :dropped-individuals 6776 :with-houjin-bangou 17808
                               :queried 15212 :publish "database240918.xlsx"
                               :source-url "https://www.gyoukaku.go.jp/review/database/"})]
    (is (= 5441 (:projection/programs m)))
    (is (= 80319 (:projection/recipients-seen m)))
    (is (= 73543 (:projection/organisations-seen m)))
    (is (= 6776 (:projection/dropped-not-organisation m)) "落とした数を黙らせない")
    (is (= 15212 (:projection/queried m)))
    (is (= "database240918.xlsx" (:source/publish m)))
    (doseq [k [:source/authority :source/licence :source/attribution]]
      (is (not (str/blank? (str (get m k))))))))
