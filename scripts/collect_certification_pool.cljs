(ns collect-certification-pool
  "gBizINFO の**届出認定 全件**から、名指しした認定を持つ法人のプールを作る。

   ## なぜ API を歩かずにこれを使うか

   会社を 1 社ずつ `/v2/hojin` で歩くと、業種で絞れない（`industry` パラメータは
   無視される、実測 2026-08-25）ので**候補の 87% を捨てるために 1 リクエストずつ
   払う**。一括ファイルは 1 回のダウンロードで、法人番号・商号・**登記住所**・
   証明日・部門が最初から入っている —— 住所のための API 呼び出しが丸ごと要らない。

   ## 認定名は intent シグナルである

   設備投資の税制優遇は、先に計画の認定を取ることを要件にしているものがある。
   だから**その認定を持っていること自体が「この会社は設備投資の優遇を取りにいった」
   という観測**になる。証明日が新しいほど、それが最近の話である。

   **これは税務助言ではないし、その企業がいま特定の税制を使えるという主張でもない。**
   ここが記録するのは『いつ・どの認定が公表されたか』だけで、適用可否は当事者と
   その顧問が決める。営業文面にこの区別を持ち込むこと。

   usage:
     nbb -cp src scripts/collect_certification_pool.cljs \\
       --names 経営力向上計画認定,事業継続力強化計画認定,ＤＸ認定制度 \\
       --since 2025-01-01 --limit 2000 --out pool.tsv [--cache /tmp/gbiz-bulk]

   出力は TSV: 法人番号 <TAB> シグナル <TAB> 商号 <TAB> 登記住所 <TAB> 部門。
   `contact_points` の `--numbers` がそのまま食える形（2 列目をシグナルとして持つ）。

   Requires GBIZINFO_TOKEN（env か Keychain `gbizinfo-api-token`）。無ければ exit 3。"
  (:require [clojure.string :as str]
            ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]))

(def argv (vec *command-line-args*))
(defn- arg [n d] (or (second (drop-while #(not= n %) argv)) d))
(defn- int-arg [n d] (let [v (arg n nil)] (if v (js/parseInt v 10) d)))

(defn- die! [code msg]
  (js/console.error msg) (set! (.-exitCode js/process) code) (throw (ex-info msg {:exit code})))

(defn- sh [args]
  (let [r (.spawnSync cp (first args) (clj->js (vec (rest args))) #js {:encoding "utf8"})]
    {:exit (or (.-status r) 1) :out (str (.-stdout r)) :err (str (.-stderr r))}))

(defn- token []
  (or (not-empty (.. js/process -env -GBIZINFO_TOKEN))
      (let [r (sh ["security" "find-generic-password" "-s" "gbizinfo-api-token" "-w"])]
        (when (zero? (:exit r)) (not-empty (str/trim (:out r)))))))

(def download-page "https://info.gbiz.go.jp/hojin/DownloadTop")
(def download-endpoint "https://info.gbiz.go.jp/hojin/Download")
(def downfile "TodokedeNinteijoho")

(defn- download! [tok cache]
  (.mkdirSync fs cache #js {:recursive true})
  (let [zip (.join path cache (str downfile ".zip"))
        cookie (.join path cache "session.txt")
        page (.join path cache "page.html")
        hdr (.join path cache "headers.txt")]
    (sh ["curl" "-s" "-c" cookie "-o" page "-A" "Mozilla/5.0" download-page])
    (let [sid (second (re-find #"jsessionid=([A-F0-9]+)" (str (.readFileSync fs page "utf8"))))]
      (sh ["curl" "-s" "-b" cookie "-o" zip "-D" hdr "-A" "Mozilla/5.0" "-X" "POST"
           (str download-endpoint ";jsessionid=" sid)
           "--data-urlencode" (str "downfile=" downfile)
           "--data-urlencode" "meta=" "--data-urlencode" "downenc=UTF-8"
           "--data-urlencode" (str "apiToken=" tok)
           "--data-urlencode" "downtype=zip" "--data-urlencode" "isZip=on"])
      ;; **status を見ない。** token 無しの POST は 200 と HTML を返す（実測）。
      ;; 添付ファイル名と ZIP magic だけが、落ちてきたものが何かを言う。
      (let [headers (if (.existsSync fs hdr) (str (.readFileSync fs hdr "utf8")) "")
            fname (second (re-find #"filename=\"([^\"]+)\"" headers))]
        (when-not fname
          (die! 3 (str "no attachment — the server answered a page, which is what it does "
                       "when the token is missing or rejected. Nothing was written.")))
        (let [head (.readFileSync fs zip)]
          (when-not (and (> (.-length head) 1) (= 0x50 (aget head 0)) (= 0x4b (aget head 1)))
            (die! 1 (str "not a zip: " fname))))
        (let [d (.join path cache (str downfile ".d"))]
          (sh ["unzip" "-o" "-q" zip "-d" d])
          (let [csvs (filter #(str/ends-with? % ".csv") (js->clj (.readdirSync fs d)))]
            (when (empty? csvs) (die! 1 "zip had no csv"))
            {:csv (.join path d (first csvs)) :filename fname}))))))

;; ── CSV ────────────────────────────────────────────────────────────────────
;; 引用符つき CSV を読む。**行で割ってから、引用が閉じていない行だけを次の行と
;; 繋ぐ。** 文字単位の状態機械にしないのは、このファイルが 30MB あるからで、
;; nbb で 1 文字ずつ回すと分単位になる（実測で断念した）。

(defn- unclosed-quote?
  "その断片で引用符が閉じていないか（\"\" のエスケープを 1 対として数える）。
   奇数なら、フィールドの途中で改行が来ている。"
  [s]
  (odd? (count (re-seq #"\"" (str s)))))

(defn- logical-lines
  "物理行 -> 論理行。引用の中の改行で行が割れないようにする。"
  [text]
  (let [lines (str/split (str/replace (str text) "\r" "") #"\n")]
    (->> (reduce (fn [{:keys [acc pending]} line]
                   (let [joined (if pending (str pending "\n" line) line)]
                     (if (unclosed-quote? joined)
                       {:acc acc :pending joined}
                       {:acc (conj! acc joined) :pending nil})))
                 {:acc (transient []) :pending nil}
                 lines)
         :acc persistent!)))

(defn- split-fields
  "1 論理行 -> フィールド。引用の外のカンマだけで割る。"
  [line]
  (loop [i 0 start 0 in-q? false out (transient [])]
    (let [n (count line)]
      (if (>= i n)
        (persistent! (conj! out (subs line start)))
        (let [c (.charAt line i)]
          (cond
            (= c "\"") (recur (inc i) start (not in-q?) out)
            (and (= c ",") (not in-q?)) (recur (inc i) (inc i) false (conj! out (subs line start i)))
            :else (recur (inc i) start in-q? out)))))))

(defn- unquote-field [s]
  (let [s (str/trim (str s))]
    (if (and (> (count s) 1) (str/starts-with? s "\"") (str/ends-with? s "\""))
      (str/replace (subs s 1 (dec (count s))) "\"\"" "\"")
      s)))

(defn- parse-csv [text]
  (->> (logical-lines text)
       (remove str/blank?)
       (mapv (fn [line] (mapv unquote-field (split-fields line))))))

(defn -main []
  (let [out (arg "--out" nil)
        cache (arg "--cache" "/tmp/gbiz-bulk")
        wanted (set (str/split (arg "--names" "経営力向上計画認定,事業継続力強化計画認定,ＤＸ認定制度") #","))
        since (arg "--since" "2025-01-01")
        limit (int-arg "--limit" 2000)
        tok (token)]
    (when-not out (die! 3 "--out is required"))
    (when-not tok (die! 3 "GBIZINFO_TOKEN not in env and not in Keychain (gbizinfo-api-token)"))
    (let [{:keys [csv filename]} (download! tok cache)
          rows (parse-csv (str (.readFileSync fs csv "utf8")))
          header (mapv #(str/replace % "﻿" "") (first rows))
          idx (into {} (map-indexed (fn [i h] [h i])) header)
          col (fn [row h] (get row (get idx h) ""))]
      (doseq [h ["法人番号" "商号または名称" "登記住所" "証明日" "名称" "部門"]]
        ;; **ヘッダで引く。列位置を契約にしない** —— 列が入れ替わったとき、
        ;; 黙って別の列を読むのではなく、ここで止まる。
        (when-not (contains? idx h) (die! 1 (str "column missing from " filename ": " h))))
      (let [matching (->> (rest rows)
                          (filter #(contains? wanted (col % "名称")))
                          (filter #(>= 0 (compare since (col % "証明日")))))
            by-company (group-by #(col % "法人番号") matching)
            ranked (->> by-company
                        (map (fn [[num rs]]
                               (let [latest (apply max-key #(col % "証明日") rs)]
                                 {:num num
                                  :name (col latest "商号または名称")
                                  :address (col latest "登記住所")
                                  :bumon (col latest "部門")
                                  :latest (col latest "証明日")
                                  :certs (str/join "|" (distinct (map #(col % "名称") rs)))
                                  :n (count rs)})))
                        ;; 認定を多く持つ順 -> 証明日が新しい順。**多い方が強いシグナル**
                        ;; （設備投資の優遇を繰り返し取りにいっている）で、新しい方が
                        ;; 「いま動いている」。日付は ISO なので文字列比較でよい。
                        (sort-by (fn [r] [(- (:n r)) (str/replace (:latest r) "-" "")])
                                 (fn [[an ad] [bn bd]]
                                   (if (= an bn) (compare bd ad) (compare an bn))))
                        (take limit)
                        vec)]
        (when (zero? (count ranked))
          (die! 2 (str "Refusing to report a pass: 0 companies matched "
                       (pr-str wanted) " since " since " in " filename)))
        (fs/writeFileSync
         out
         (str "# gBizINFO 届出認定 全件から作ったプール。生成物。手で編集しない。\n"
              "# source: " filename "  since=" since "  names=" (str/join "," wanted) "\n"
              "# 列: 法人番号 <TAB> シグナル <TAB> 商号 <TAB> 登記住所 <TAB> 部門\n"
              "# ⚠ シグナルは『いつ・どの認定が公表されたか』の記録であって、その企業が\n"
              "#   いま特定の税制を使えるという主張ではない。税務助言をしない。\n"
              "# 出典：gBizINFO（経済産業省）https://info.gbiz.go.jp/ を加工して作成\n"
              (str/join "\n"
                        (map (fn [r]
                               (str/join "\t" [(:num r)
                                               (str (:certs r) " (" (:latest r) ", " (:n r) "件)")
                                               (:name r) (:address r) (:bumon r)]))
                             ranked))
              "\n"))
        (println (str "MATCHED-ROWS\t" (count matching)))
        (println (str "COMPANIES\t" (count by-company)))
        (println (str "WRITTEN\t" (count ranked)))
        (println (str "OUT\t" out))))))

(-main)
