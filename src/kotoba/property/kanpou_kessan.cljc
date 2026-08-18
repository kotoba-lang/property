(ns kotoba.property.kanpou-kessan
  "官報の会社決算公告から、**非上場企業の決算期**を読む。

   ## なぜこれが要るか

   決算期はどの税制の窓に居るかを決めるのに、公開バルクがどこにも無い。
   gBizINFO の財務は EDINET 由来なので上場・有報提出会社だけ、法人番号 registry
   にも invoice registry にも事業年度は無い。残る公開経路は 1 つだけ ——
   **会社法 440 条の決算公告**で、官報に毎日載る。

   実測 2026-08-18 の号外 1 号だけで **152 件**（貸借対照表の要旨 185 箇所）。
   1 日あたりこの規模なので、年 4 万社程度が流れる。

   ## 3 つの制約を先に書く

   1. **無料で読めるのは直近 90 日だけ。** 過去分は買うしかないので、これは
      「今から貯める」データであって「今すぐ揃う」データではない。cell が
      毎日回して初めて意味を持つ、この workspace で最初の本当に日次な仕事。
   2. **公告の履行率が低い。** 義務ではあるが中小の大半は出していない。だから
      カバレッジは母集団の一部で、**出していない会社と決算期が無い会社は
      区別できない**（不在を 0 と読まない）。
   3. **法人番号が載っていない。** 商号と所在地しか無いので、法人番号への接続は
      名寄せになる —— それは `houjin-bangou-projection/resolve-names` の仕事で、
      同名 2 社を解決しない規律もそこが持つ。

   ## 代表者名は落とす

   公告には代表取締役の氏名が載る。`gbizinfo/basic-record` と同じ理由で捨てる。

   出典：官報（国立印刷局）https://kanpou.npb.go.jp/ を加工して作成"
  (:require [clojure.string :as str]))

(def source-id "kanpou-kessan-koukoku")
(def authority-id "JP/NPB-Kanpou")
(def dataset "kanpou-kessan")
(def licence "官報（国立印刷局）— 公告そのものは公表物")
(def attribution "出典：官報（国立印刷局）https://kanpou.npb.go.jp/ を加工して作成")

(def ^:private fullwidth-digits
  {"０" "0" "１" "1" "２" "2" "３" "3" "４" "4"
   "５" "5" "６" "6" "７" "7" "８" "8" "９" "9"})

(defn- parse-int
  "Portable digits->long. `js/parseInt` does not exist on the JVM, and this
   namespace is .cljc because the parser has to be testable without Node."
  [s]
  (when (and s (re-matches #"\d+" (str s)))
    #?(:clj (Long/parseLong (str s))
       :cljs (js/parseInt (str s) 10))))

(defn normalize-digits [s]
  (when s (reduce-kv (fn [acc z a] (str/replace acc z a)) (str s) fullwidth-digits)))

(def ^:private era-base
  ;; 元年 = base + 1. Only the eras a current 公告 can carry.
  {"令和" 2018 "平成" 1988 "昭和" 1925})

(defn wareki->date
  "「令和８年６月20日現在」-> {:year 2026 :month 6 :day 20}, or nil.

   `元年` is written as 元, not 1, and a 決算日 in the first year of an era is
   exactly the kind of row that would otherwise parse as year 0."
  [s]
  (when-let [m (re-find #"(令和|平成|昭和)\s*(元|[0-9０-９]{1,2})\s*年\s*([0-9０-９]{1,2})\s*月\s*([0-9０-９]{1,2})\s*日"
                        (str s))]
    (let [[_ era y mo d] m
          y (if (= "元" y) 1 (parse-int (normalize-digits y)))
          mo (parse-int (normalize-digits mo))
          d (parse-int (normalize-digits d))]
      (when (and (get era-base era) y mo d (<= 1 mo 12) (<= 1 d 31))
        {:year (+ (get era-base era) y) :month mo :day d}))))

(defn- iso-date [{:keys [year month day]}]
  (when (and year month day)
    (str year "-" (when (< month 10) "0") month "-" (when (< day 10) "0") day)))

(def ^:private header-re
  ;; pdftotext spaces the headline out («第 71 期決算公告», «第 47 期 決 算 公 告»),
  ;; so every character between the digits and 告 has to tolerate whitespace.
  #"第\s*([0-9０-９]+)\s*期\s*決\s*算\s*公\s*告")

(def ^:private corporate-form-re
  ;; 前株 and 後株 are both ordinary: 株式会社本田 and トヨタＬ＆Ｆ福島株式会社
  ;; are the same kind of name with the form at opposite ends, and a pattern
  ;; anchored to the end silently drops every 前株 company — measured, that was
  ;; 130 of 153 blocks in one day's issue.
  #"(株式会社|有限会社|合同会社|合名会社|合資会社|相互会社|信用金庫|信用組合|協同組合|農business)")

(def ^:private form-token-re
  #"(株式会社|有限会社|合同会社|合名会社|合資会社|相互会社|信用金庫|信用組合|協同組合)")

(def ^:private representative-re #"代表|理事長|組合長|会長|社長")

(def ^:private address-re
  #"(都|道|府|県|市|区|郡|町|村)")

(def ^:private page-furniture-re
  ;; The two-column layout interleaves running heads into the text stream.
  #"^(官|報|火曜日|月曜日|水曜日|木曜日|金曜日|土曜日|日曜日|\(号外第.*|令和\s+年.*|\s*)$")

(def ^:private header-split-re
  ;; The same pattern with no capture group. `clojure.string/split` on a regex
  ;; WITH a group keeps the group in the output under ClojureScript and drops it
  ;; under Clojure — measured here: identical code returned 153 blocks in nbb
  ;; and 0 on the JVM. So the split and the group live in separate patterns.
  #"第\s*(?:[0-9０-９]+)\s*期\s*決\s*算\s*公\s*告")

(defn split-blocks
  "Whole-section text -> [{:period n :text s}], one per 決算公告 headline."
  [text]
  (let [periods (map second (re-seq header-re text))
        bodies (rest (str/split text header-split-re))]
    (->> (map vector periods bodies)
         (keep (fn [[period body]]
                 (when-let [n (parse-int (normalize-digits period))]
                   {:period n :text body}))))))

(defn- clean-lines [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(re-matches page-furniture-re %))))

(defn block->record
  "One block -> a company record, or nil when the two fields that make it worth
   keeping — the company name and the balance-sheet date — are not both there."
  [{:keys [period text]} published-at]
  (let [lines (vec (clean-lines text))
        bs-idx (or (first (keep-indexed (fn [i l] (when (re-find #"貸借対照表の要旨" l) i)) lines))
                   (count lines))
        head (subvec lines 0 (min bs-idx (count lines)))
        ;; The name is the last form-bearing line before the balance sheet that
        ;; is not the representative's line; the address is the line before it.
        name-idx (last (keep-indexed (fn [i l]
                                       (when (and (re-find form-token-re l)
                                                  (not (re-find representative-re l))
                                                  (<= (count l) 40))
                                         i))
                                     head))
        name (when name-idx (nth head name-idx))
        address (when (and name-idx (pos? name-idx))
                  (let [a (nth head (dec name-idx))]
                    (when (and (re-find address-re a) (>= (count a) 5)) a)))
        bs-date (some->> text
                         (re-find #"貸借対照表の要旨\s*[（(]([^）)]*)[）)]")
                         second
                         wareki->date)
        capital (some-> (re-find #"資\s*本\s*金\s*([\d,]+)" text) second (str/replace "," ""))]
    (when (and name bs-date)
      (cond-> {:source/dataset dataset
               :company/legal-name name
               :kessan/period period
               ;; The whole point of the dataset: the same attribute gBizINFO
               ;; fills for listed companies, filled here for unlisted ones.
               :company/fiscal-year-end-month (:month bs-date)
               :company/fiscal-year-end (iso-date bs-date)}
        address (assoc :company/address address)
        published-at (assoc :kessan/published-at published-at)
        ;; 千円単位で刷られるので、そのまま円として読ませない。
        capital (assoc :company/capital-stock-yen (str (* 1000 (parse-int capital))))))))

(defn parse-section
  "pdftotext output of the 決算公告 pages -> records."
  [text published-at]
  (keep #(block->record % published-at) (split-blocks text)))

(defn corpus-manifest
  [{:keys [observed-at issues record-count window-days]}]
  (cond-> {:corpus/manifest true
           :corpus/projection true
           :corpus/format :edn-lines
           :source/dataset dataset
           :source/authority authority-id
           :source/licence licence
           :source/attribution attribution
           :source/observed-at observed-at}
    issues (assoc :corpus/issues (vec issues))
    window-days (assoc :corpus/window-days window-days)
    record-count (assoc :corpus/record-count record-count)))
