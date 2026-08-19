(ns kotoba.property.gyousei-review
  "行政事業レビューシート（行政改革推進本部）の**支出先上位 10 者リスト**から、
   国の支出を受けた法人を法人番号付きで取り出す。

   ## なぜこれが要るか

   これまで「誰が交付を受けたか」は **gBizINFO 経由でしか持っていなかった**
   （ADR-2608181000 Consequences）—— つまり経済産業省が集約したものを読んでいる
   だけで、原典から組み立ててはいなかった。レビューシートは**各府省が自分の事業に
   ついて出す一次資料**で、1 事業あたり上位 10 者の 支出先・**法人番号**・支出額・
   契約方式・落札率 を持つ。

   実測 2026-08-19（`database240918.xlsx`）: 5,442 事業 × 14,298 列。

   ## 個人は取らない

   支出先には個人事業主や個人が現れうる。**法人番号を持つか、法人格の語を名前に
   持つもの以外は落とす**（落とした数は manifest に出す —— 黙って減らさない）。
   官報の清算人・PR TIMES の代表者と同じ線。

   ## 金額は百万円単位の申告値

   `支出額（百万円）` はシート上の申告値で、円に直すときは丸めが入っている。
   **円に正規化しない** —— `:grant/amount-million-jpy` として原文の単位のまま持つ。
   gBizINFO の `:grant/amount-yen` と足し合わせられないのはそのためで、
   混ぜたい者に気付かせる方が、静かに桁を間違えるより良い。"
  (:require [clojure.string :as str]))

(def dataset "gyousei-review")
(def authority-id "JP/GYOUKAKU-Review")
(def licence "行政事業レビューシート（内閣官房行政改革推進本部）— 政府標準利用規約")
(def attribution "出典：行政事業レビューシートのデータベース（内閣官房行政改革推進本部）https://www.gyoukaku.go.jp/review/database/ を加工して作成")

(def program-columns
  "事業側の列。**列文字ではなくヘッダ名で引く** —— 年度版で列が動く。"
  {"府省庁" :grant/ministry
   "事業名" :grant/title
   "事業開始年度" :program/start-year
   "会計区分" :program/account-class})

(def ^:private recipient-field-re
  ;; 「支出先上位１０者リスト-A.支払先-3-法人番号-01」のような列名。
  ;; block = A/B/C…（事業内の支出区分）、seq = 支払先の順位。
  #"^支出先上位１０者リスト-([A-Z])\.支払先-(\d+)-(.+?)-\d+$")

(defn- parse-rank
  ;; ⚠ `.cljc` に `js/parseInt` を直書きしない —— JVM 側のテストが
  ;; `No such namespace: js` で落ちる（実測 2026-08-19）。
  [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn recipient-column
  "列名 -> `{:block \"A\" :seq 3 :field :houjin-bangou}`、それ以外は nil。"
  [header]
  (when-let [[_ block seq* field] (re-find recipient-field-re (str header))]
    (let [f (case field
              "支出先" :name
              "法人番号" :houjin-bangou
              "支出額（百万円）" :amount-million-jpy
              "契約方式等" :contract-method
              "落札率" :winning-rate
              nil)]
      ;; key は `:rank`（`:seq` にすると呼び出し側で `clojure.core/seq` を
      ;; 隠す名前になりやすい）。
      (when f {:block block :rank (parse-rank seq*) :field f}))))

(def organization-marker-re
  ;; インラインの `(?i)` を書かない（JS が `Invalid group` で落ちる）。小文字化して当てる。
  ;;
  ;; ⚠ **最初の版は法人格の語だけを見て 13,669 件を落とした。** 出力を点検したら
  ;; 個人ではなく「協議会」「センター」「委員会」「法務局」「海外法人」ばかりで、
  ;; 規則が厳しすぎた（実測 2026-08-19、`--dropped-out` を足して初めて見えた）。
  ;; **落とした物を見られるようにしていなければ、13,669 件の欠落は数字のままだった。**
  #"株式会社|株式會社|有限会社|合同会社|合資会社|合名会社|相互会社|一般社団法人|公益社団法人|一般財団法人|公益財団法人|医療法人|学校法人|宗教法人|社会福祉法人|特定非営利活動法人|npo法人|独立行政法人|国立研究開発法人|国立大学法人|公立大学法人|地方独立行政法人|協同組合|事業協同組合|農業協同組合|漁業協同組合|信用金庫|信用組合|連合会|振興会|協議会|委員会|審議会|機構|公社|公団|事業団|基金|センター|研究所|研究センター|試験場|事務所|法務局|財務局|運輸局|気象台|保健所|病院|大学|高等専門学校|学校|協会|財団|社団|組合|市$|町$|村$|都$|道$|府$|県$|区$|[a-z]")

(def person-name-re
  ;; 逆向きの安全弁: **人名の形をしているものは、marker に当たっても落とす。**
  ;; 「姓 名」（漢字 2〜4 + 空白 + 漢字 1〜4）で、組織語を含まないもの。
  #"^[一-龥]{2,4}[\s　]+[一-龥]{1,4}$")

(defn organization?
  "組織だと言えるか。**法人番号が付いていれば確実**（個人には無い）。無い場合は
   名前の形で判断する —— 組織語を含み、かつ人名の形をしていないこと。"
  [name houjin-bangou]
  (boolean (and (not (str/blank? (str name)))
                (not (re-matches person-name-re (str/trim (str name))))
                (or (re-matches #"[0-9]{13}" (str houjin-bangou))
                    (re-find organization-marker-re (str/lower-case (str name)))))))

(defn- clean [s]
  (let [v (-> (str s) (str/replace #"[\s　]+" " ") str/trim)]
    (when-not (or (str/blank? v) (= "-" v) (= "－" v)) v)))

(defn recipient-record
  "1 支出先 -> 1 レコード。組織だと言えないものは nil（推測しない）。"
  [{:keys [program block rank fields fiscal-year]}]
  (let [nm (clean (:name fields))
        hb (some-> (clean (:houjin-bangou fields)) (str/replace #"[^0-9]" ""))
        hb (when (re-matches #"[0-9]{13}" (str hb)) hb)]
    (when (and nm (organization? nm hb))
      (cond-> (merge {:source/dataset dataset
                      :grant/kind "subsidy-or-contract"
                      :grant/recipient-name nm
                      :review/block block
                      :review/rank rank}
                     (select-keys program (vals program-columns)))
        fiscal-year (assoc :grant/fiscal-year fiscal-year)
        hb (assoc :company/houjin-bangou hb :company/registration-no hb)
        (clean (:amount-million-jpy fields))
        (assoc :grant/amount-million-jpy (clean (:amount-million-jpy fields)))
        (clean (:contract-method fields))
        (assoc :grant/contract-method (clean (:contract-method fields)))
        (clean (:winning-rate fields))
        (assoc :grant/winning-rate (clean (:winning-rate fields)))))))

(defn corpus-manifest
  [{:keys [observed-at record-count programs recipients-seen dropped-individuals
           with-houjin-bangou publish source-url organisations-seen queried]}]
  (cond-> {:corpus/manifest true
           :corpus/projection true
           :corpus/format :edn-lines
           :source/dataset dataset
           :source/authority authority-id
           :source/licence licence
           :source/attribution attribution
           :source/observed-at observed-at}
    publish (assoc :source/publish publish)
    source-url (assoc :source/url source-url)
    programs (assoc :projection/programs programs)
    ;; 分母 3 つ: 見た支出先、法人番号が付いたもの、個人として落としたもの。
    ;; **落とした数を出さないと「国の支出先はこれで全部」に読める。**
    recipients-seen (assoc :projection/recipients-seen recipients-seen)
    ;; 絞る前に何件が組織として残ったか、そして何番号で絞ったか。**この 2 つが
    ;; 無いと、絞った結果が「国の支出先はこれで全部」に読める。**
    organisations-seen (assoc :projection/organisations-seen organisations-seen)
    (pos? (or queried 0)) (assoc :projection/queried queried)
    with-houjin-bangou (assoc :projection/with-houjin-bangou with-houjin-bangou)
    (some? dropped-individuals) (assoc :projection/dropped-not-organisation dropped-individuals)
    record-count (assoc :corpus/record-count record-count)))
