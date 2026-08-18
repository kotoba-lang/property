(ns kotoba.property.gbizinfo
  "Portable record shapes for gBizINFO (Gビズインフォ, 経済産業省) — the REST API
   that answers, per 法人番号, **what the state has given, bought and recorded**:
   補助金交付, 調達, 財務, 届出・認定・表彰.

   ## Why this is join-driven and not an ingest

   Everything here keys on 法人番号, which this workspace already has as
   `:company/houjin-bangou`. So the useful unit is not \"the universe\" but
   \"the companies the plane already carries\": one request per company per
   aspect, bounded by the projection we already committed.

   That is also the honest boundary against jGrants, which sits next to it:
   jGrants is the 公募 side (which subsidies exist), this is the 交付 side (who
   received one). Neither implies the other.

   ## What it does NOT replace

   gBizINFO is itself an aggregator of ministry data. Reading it is the cheap
   path to 交付実績 and 財務; it is not independence from it. Anything this
   workspace wants to keep if gBizINFO changes has to be archived when it is
   read — the same rule the NTA archives dataset exists for.

   出典：gBizINFO（経済産業省）https://info.gbiz.go.jp/ を加工して作成"
  (:require [clojure.string :as str]))

(def source-id "gbizinfo-rest-v2")
(def authority-id "JP/METI-gBizINFO")
(def dataset "gbizinfo")
(def licence "gBizINFO API・データダウンロード利用規約 (経済産業省)")
(def attribution "出典：gBizINFO（経済産業省）https://info.gbiz.go.jp/ を加工して作成")

(def api-base "https://api.info.gbiz.go.jp/hojin/v2/hojin")

(def token-header "X-hojinInfo-api-token")

(def demo-token
  "The token the public OpenAPI document itself publishes for 動作確認. It is
   not a credential of ours and must never be used for a bulk pass — the
   collector caps request count when it is in use, and a real run needs the
   operator's own token in `GBIZINFO_TOKEN`."
  "DTcLxzo1lZaUYaQPVdSRxdS4MzlXNCs4")

(def aspects
  "Aspect -> URL suffix. Each is one request per company."
  {:subsidy "subsidy"
   :procurement "procurement"
   :finance "finance"
   :certification "certification"})

(defn aspect-url [corporate-number aspect]
  (str api-base "/" corporate-number "/" (get aspects aspect)))

(def ^:private fiscal-end-re
  ;; 「第26期(自　2025年４月１日　至　2026年３月31日)」— full-width digits and
  ;; parentheses both occur, and the closing date is the one that names the
  ;; fiscal year end.
  #"至[　\s]*([0-9０-９]{4})年[　\s]*([0-9０-９]{1,2})月")

(def ^:private fullwidth-digits
  ;; Written as a table rather than arithmetic on character codes: `(int c)` in
  ;; ClojureScript takes a *string* here, not a char, and returns NaN — which
  ;; produced a silent nil month on every record until it was measured.
  {"０" "0" "１" "1" "２" "2" "３" "3" "４" "4"
   "５" "5" "６" "6" "７" "7" "８" "8" "９" "9"})

(defn- zenkaku->ascii [s]
  (when s
    (reduce-kv (fn [acc z a] (str/replace acc z a)) (str s) fullwidth-digits)))

(defn fiscal-year-end-month
  "`fiscal_year_cover_page` -> the month the fiscal year ends (1-12), or nil.

   This is the field the MK-1 leads work needed and could not get from any
   other public bulk source: 決算期 decides which tax window a customer is in.
   It is only present for companies whose filings gBizINFO carries (EDINET
   derived), so absence here is normal and must not be read as 'no fiscal
   year'."
  [cover-page]
  (when-let [m (re-find fiscal-end-re (str cover-page))]
    (let [month #?(:clj (parse-long (zenkaku->ascii (nth m 2)))
                   :cljs (js/parseInt (zenkaku->ascii (nth m 2)) 10))]
      (when (and (>= month 1) (<= month 12)) month))))

(defn- put [m k v]
  (if (or (nil? v) (and (string? v) (str/blank? v))) m (assoc m k v)))

(defn subsidy-records
  "One `/subsidy` response -> zero or more grant records. Each is its own
   entity: a company can hold dozens (苫小牧市 has 61), and folding them into
   one company entity would make counting or filtering by ministry impossible."
  [corporate-number info]
  (for [s (get info "subsidy")
        :let [title (get s "title")]
        :when (not (str/blank? (str title)))]
    (-> {:source/dataset dataset
         :grant/kind :subsidy
         :company/houjin-bangou corporate-number
         :grant/title title}
        (put :grant/date (get s "date_of_approval"))
        (put :grant/amount-yen (some-> (get s "amount") str))
        (put :grant/ministry (get s "government_departments"))
        (put :grant/target (get s "target"))
        (put :company/legal-name (get info "name")))))

(defn procurement-records
  "One `/procurement` response -> zero or more award records. Same shape as a
   subsidy under `:grant/kind :procurement`, so 'what has the state given this
   company' is one query rather than two."
  [corporate-number info]
  (for [p (get info "procurement")
        :let [title (get p "title")]
        :when (not (str/blank? (str title)))]
    (-> {:source/dataset dataset
         :grant/kind :procurement
         :company/houjin-bangou corporate-number
         :grant/title title}
        (put :grant/date (get p "date_of_order"))
        (put :grant/amount-yen (some-> (get p "amount") str))
        (put :grant/ministry (get p "government_departments"))
        (put :company/legal-name (get info "name")))))

(defn finance-record
  "One `/finance` response -> at most one company entity carrying the fiscal
   year end and the most recent revenue, or nil when gBizINFO has no filings
   for it (which is the common case outside listed companies)."
  [corporate-number info]
  (let [f (get info "finance")
        cover (get f "fiscal_year_cover_page")
        idx (get f "management_index")
        latest (last (sort-by #(str (get % "period")) (remove nil? idx)))
        month (fiscal-year-end-month cover)]
    (when (or month (seq idx))
      (cond-> {:source/dataset dataset
               :company/houjin-bangou corporate-number}
        true (put :company/legal-name (get info "name"))
        true (put :company/fiscal-year-cover cover)
        month (assoc :company/fiscal-year-end-month month)
        latest (put :company/net-sales-yen
                    (some-> (get latest "net_sales_summary_of_business_results") str))))))

(defn corpus-manifest
  [{:keys [observed-at aspects numbers record-count token-kind]}]
  (cond-> {:corpus/manifest true
           :corpus/projection true
           :corpus/format :edn-lines
           :source/dataset dataset
           :source/authority authority-id
           :source/licence licence
           :source/attribution attribution
           :source/observed-at observed-at}
    aspects (assoc :corpus/aspects (vec aspects))
    numbers (assoc :projection/number-count numbers)
    record-count (assoc :corpus/record-count record-count)
    ;; Which token produced this matters: a run on the published 動作確認 token
    ;; is a bounded sample, not a pass over the whole allowlist, and a reader
    ;; must be able to tell those apart from the artifact alone.
    token-kind (assoc :source/token token-kind)))
