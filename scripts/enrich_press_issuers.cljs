(ns enrich-press-issuers
  "名寄せできなかった発表者について、リリースページの会社概要から**所在地だけ**を
   取り、名前+住所の照合リストを作る。

   ## なぜ住所が要るか

   配信フィードには住所が無いので、名前だけの照合が上限になる（実測 2026-08-19:
   192 名中 119 = 62%）。同名 2 社は**ほとんどの場合、県が違う**ので、住所が 1 つ
   あれば `houjin-bangou-projection/resolve-names` が分けられる。

   ## 1 発表者につき 1 ページしか取らない

   未解決の発表者だけを対象に、その発表者のリリースを 1 本だけ取る。同じ会社の
   リリースを何本も取っても住所は増えない。取得間隔も空ける —— 相手のサーバに
   対する礼儀であり、この収集の速さは要件ではない。

   ## 代表者名は取らない

   会社概要ブロックには「代表者：…」が並ぶが、`press-wire/issuer-address` は
   所在地しか返さない。公表物であっても個人名は持ち歩かない。

   usage:
     nbb -cp src scripts/enrich_press_issuers.cljs --records <press-wire.edn>
       --out <name-address.tsv> [--limit N] [--delay-ms 800]"
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [kotoba.property.press-wire :as pw]
            ["fs" :as fs]))

(def argv (vec *command-line-args*))
(defn- arg [n d] (or (second (drop-while #(not= n %) argv)) d))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- fetch-page [url ms]
  (let [ctl (js/AbortController.)
        t (js/setTimeout #(.abort ctl) ms)]
    (-> (js/fetch url #js {:signal (.-signal ctl)
                           :redirect "follow"
                           :headers #js {"User-Agent" "kotoba-press-wire/1.0 (+https://github.com/kotoba-lang/property)"}})
        (.then (fn [res] (js/clearTimeout t)
                 (if (.-ok res) (.text res) nil)))
        (.catch (fn [_] (js/clearTimeout t) nil)))))

(defn unresolved-issuers
  "法人番号の付いていない発表者 -> [名前 リリース URL] 1 本。

   **鍵は名前ではなく PR TIMES の企業 ID**（`press-wire/issuer-company-id`）。
   同じ会社が表記を揺らして出しても 1 回しか取りに行かない。"
  [records]
  (reduce (fn [acc r]
            (let [n (:press/company-name r)
                  url (:press/url r)
                  id (pw/issuer-company-id url)]
              (if (or (str/blank? (str n))
                      (:company/houjin-bangou r)
                      (nil? id)
                      (contains? acc id))
                acc
                (assoc acc id [n url]))))
          {}
          records))

(defn -main []
  (let [records-file (arg "--records" nil)
        out (arg "--out" nil)
        limit (js/parseInt (arg "--limit" "0") 10)
        delay-ms (js/parseInt (arg "--delay-ms" "800") 10)]
    (when-not (and records-file out)
      (println "usage: enrich_press_issuers.cljs --records <file> --out <tsv> [--limit N] [--delay-ms 800]")
      (js/process.exit 2))
    (let [records (->> (str/split (.readFileSync fs records-file "utf8") #"\n")
                       (remove str/blank?)
                       rest
                       (keep #(try (reader/read-string %) (catch :default _ nil)))
                       vec)
          pending (unresolved-issuers records)
          pairs (cond->> (vec (vals pending)) (pos? limit) (take limit))
          state (atom {:fetched 0 :with-address 0})
          rows (atom [])]
      (println (str "  " (count pending) " unresolved issuer(s) by company-id, fetching " (count pairs)))
      (-> (reduce
           (fn [p [nm url]]
             (.then p (fn [_]
                        (-> (sleep delay-ms)
                            (.then (fn [_] (fetch-page url 20000)))
                            (.then (fn [html]
                                     (swap! state update :fetched inc)
                                     (when-let [addr (and html (pw/issuer-address html))]
                                       (swap! state update :with-address inc)
                                       (swap! rows conj (str nm "\t" addr)))
                                     (when (zero? (mod (:fetched @state) 25))
                                       (println (pr-str (assoc @state :of (count pairs)))))))))))
           (js/Promise.resolve nil)
           pairs)
          (.then
           (fn [_]
             (let [{:keys [fetched with-address]} @state]
               (.writeFileSync fs out (str/join "\n" @rows) "utf8")
               (println (pr-str {:out out :issuers (count pairs)
                                 :fetched fetched :with-address with-address
                                 :hit-rate (when (pos? fetched)
                                             (str (js/Math.round (* 100 (/ with-address fetched))) "%"))}))
               ;; 1 件も住所が取れなかったのは「会社概要が無い」ではなく、
               ;; ほぼ確実にページの形が変わったか、こちらが弾かれている。
               (when (and (pos? fetched) (zero? with-address))
                 (js/console.error "enrich-press-issuers: fetched pages but found no address at all — that is a failure here, not a fact about them")
                 (js/process.exit 2)))))
          (.catch (fn [e]
                    (js/console.error (str "enrich-press-issuers failed: " (.-message e)))
                    (js/process.exit 1)))))))

(-main)
