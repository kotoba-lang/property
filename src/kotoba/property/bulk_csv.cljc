(ns kotoba.property.bulk-csv
  "gBizINFO の一括 CSV を読む。純関数。

   ## 行で割ってから繋ぐ

   引用フィールドの中に改行が在るので、素朴に `split-lines` すると行が割れる。
   一方で**文字単位の状態機械にもしない** —— このファイルは 30MB あって、
   nbb で 1 文字ずつ回すと分単位になる（実測で断念した）。
   だから『行で割ってから、引用が閉じていない行だけ次と繋ぐ』にしている。

   ## 列はヘッダ名で引く

   列位置を契約にしない。入れ替わったときに黙って別の列を読むのではなく、
   `column-index` が nil を返して呼び手が止まれるようにする。"
  (:require [clojure.string :as str]))

(defn unclosed-quote?
  "その断片で引用符が閉じていないか（`\"\"` のエスケープも 1 個ずつ数えるので、
   対になっていれば偶数になる）。奇数なら、フィールドの途中で改行が来ている。"
  [s]
  (odd? (count (re-seq #"\"" (str s)))))

(defn logical-lines
  "物理行 -> 論理行。引用の中の改行で行が割れないようにする。"
  [text]
  (let [lines (str/split (str/replace (str text) "\r" "") #"\n")]
    (->> (reduce (fn [{:keys [acc pending]} line]
                   (let [joined (if pending (str pending "\n" line) line)]
                     (if (unclosed-quote? joined)
                       {:acc acc :pending joined}
                       {:acc (conj acc joined) :pending nil})))
                 {:acc [] :pending nil}
                 lines)
         ;; **閉じないまま終わった断片を捨てない。** 捨てると、壊れた末尾が
         ;; 「そんな行は無かった」として静かに消える。
         ((fn [{:keys [acc pending]}] (if pending (conj acc pending) acc))))))

(defn split-fields
  "1 論理行 -> フィールド。引用の外のカンマだけで割る。"
  [line]
  (let [n (count line)]
    (loop [i 0 start 0 in-q? false out []]
      (if (>= i n)
        (conj out (subs line start))
        ;; ⚠ `.charAt` を使わない。**JVM は char を、ClojureScript は 1 文字の
        ;; 文字列を返す**ので、`(= c "\"")` の答えが runtime で変わる（cljs だけで
        ;; 通り、JVM では常に false になって 1 フィールドしか出ない）。
        ;; `subs` はどちらでも文字列を返す。
        (let [c (subs line i (inc i))]
          (cond
            (= c "\"") (recur (inc i) start (not in-q?) out)
            (and (= c ",") (not in-q?)) (recur (inc i) (inc i) false (conj out (subs line start i)))
            :else (recur (inc i) start in-q? out)))))))

(def bom "\ufeff")

(defn unquote-field
  "引用符を外す。**BOM は外す前に落とす** —— 先頭に BOM が残っていると
   フィールドが `\"` で始まらず、引用符が外れないまま列名になる。"
  [s]
  (let [s (str/trim (str/replace (str s) bom ""))]
    (if (and (> (count s) 1) (str/starts-with? s "\"") (str/ends-with? s "\""))
      (str/replace (subs s 1 (dec (count s))) "\"\"" "\"")
      s)))

(defn parse
  "CSV 本文 -> 行のベクタ（1 行目がヘッダ）。BOM を落とす。"
  [text]
  (->> (logical-lines text)
       (remove str/blank?)
       (mapv (fn [line] (mapv unquote-field (split-fields line))))))

(defn column-index
  "ヘッダ行 -> `{列名 位置}`。**無い列は無いと答える**（推測しない）。"
  [header]
  (into {} (map-indexed (fn [i h] [h i])) header))

(defn getter
  "ヘッダ行 -> `(fn [row col-name] value)`。列が無ければ nil を返すので、
   呼び手が『この列が無い』と『この行では空』を区別できる。"
  [header]
  (let [idx (column-index header)]
    (fn [row col] (when-let [i (get idx col)] (get row i)))))
