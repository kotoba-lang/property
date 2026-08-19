(ns tier-gbizinfo-projection
  "gBizINFO の全件 projection を tier に割る。

   ## なぜ割るか

   `collect_gbizinfo_zenken.cljs` の出力は 148,965 レコード / 57 MB で、その
   **84% は補助金交付の明細**（125,296 行 / 46.9 MB）である。統合クエリ面は
   projection を**毎回まるごと** DataScript に load するので、tier のサイズは
   **誰が撃つどのクエリにも一律にかかる税**になる（GLEIF の実測で 100 万 entity ≒
   5.6 GB・156 秒。ADR-2608071000）。補助金を 1 件も見ないクエリに 47 MB を
   払わせない。

   ## 何を既定に置くか

   既定 tier は「補助金の明細以外の全部」＋**会社ごとの補助金サマリ**:

     :subsidy/count / :subsidy/total-yen / :subsidy/latest-date

   「この会社は補助金を受けたことがあるか、何件、いくら」は既定で答えられて、
   **どの補助金かを聞くときだけ** `--gb-tier subsidy` で明細を足す。

   usage:
     nbb -cp src scripts/tier_gbizinfo_projection.cljs
       --in /tmp/gbizinfo-zenken.datoms.edn
       --out-dir <repo>/data"
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]))

(def argv (vec *command-line-args*))
(defn- arg [n d] (or (second (drop-while #(not= n %) argv)) d))

(defn- parse-line [l]
  (try (reader/read-string l) (catch :default _ nil)))

(defn subsidy-summary
  "補助金明細 -> 会社ごとに 1 entity。金額は文字列で来るので数に直してから足す
   （文字列連結で 1 億円が 100 万円 2 つに見えるような壊れ方をしない）。"
  [rows]
  (->> rows
       (group-by :company/houjin-bangou)
       (map (fn [[n rs]]
              (let [amounts (keep (fn [r]
                                    (let [a (:grant/amount-yen r)]
                                      (when (and a (re-matches #"\d+" (str a)))
                                        (js/parseInt (str a) 10))))
                                  rs)
                    dates (sort (keep :grant/date rs))]
                (cond-> {:source/dataset "gbizinfo"
                         :company/houjin-bangou n
                         :gbiz/summary true
                         :subsidy/count (count rs)}
                  (seq amounts) (assoc :subsidy/total-yen (str (reduce + amounts)))
                  (seq dates) (assoc :subsidy/latest-date (last dates))
                  (:company/legal-name (first rs)) (assoc :company/legal-name
                                                          (:company/legal-name (first rs)))))))
       (sort-by :company/houjin-bangou)
       vec))

(defn -main []
  (let [in (arg "--in" nil)
        out-dir (arg "--out-dir" nil)]
    (when-not (and in out-dir)
      (println "usage: tier_gbizinfo_projection.cljs --in <file> --out-dir <dir>")
      (js/process.exit 2))
    (let [lines (->> (str/split (.readFileSync fs in "utf8") #"\n") (remove str/blank?))
          parsed (keep parse-line lines)
          manifests (filterv :corpus/manifest parsed)
          records (filterv (complement :corpus/manifest) parsed)
          ;; `:grant/kind` は dataset によって keyword（:subsidy）だったり
          ;; 文字列（\"procurement\"）だったりする —— 面に載ると両方 bare string に
          ;; 正規化されるので、生の EDN を読むここでは**両方を受ける**。
          ;; 片方だけ見た最初の版は 125,296 行を 0 行として数え、既定 tier に
          ;; 57 MB 全部を残した（実測）。
          subsidy? (fn [r] (contains? #{"subsidy" :subsidy} (:grant/kind r)))
          subsidy (filterv subsidy? records)
          others (filterv (complement subsidy?) records)
          summary (subsidy-summary subsidy)
          base (first manifests)
          write! (fn [file mf recs]
                   (.writeFileSync fs (.join path out-dir file)
                                   (str (pr-str (assoc mf :corpus/record-count (count recs))) "\n"
                                        (str/join "\n" (map pr-str recs)) "\n")
                                   "utf8")
                   [(count recs) (.-size (.statSync fs (.join path out-dir file)))])]
      (when (empty? records)
        (println "no records in the input — refusing to write empty tiers")
        (js/process.exit 2))
      (let [[jn jb] (write! "gbizinfo-joined.datoms.edn"
                            (assoc base :corpus/tier :joined
                                   :corpus/contents [:procurement :certification :finance
                                                     :commendation :subsidy-summary])
                            (into others summary))
            [sn sb] (write! "gbizinfo-subsidy.datoms.edn"
                            (assoc base :corpus/tier :subsidy
                                   :corpus/contents [:subsidy-detail])
                            subsidy)]
        (println (pr-str {:joined {:records jn :mb (js/Math.round (/ jb 104857.6))}
                          :subsidy {:records sn :mb (js/Math.round (/ sb 104857.6))}
                          :companies-with-subsidies (count summary)}))))))

(-main)
