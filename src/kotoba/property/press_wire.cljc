(ns kotoba.property.press-wire
  "プレスリリース配信サイトの公開フィードを読む（いまは PR TIMES）。

   ## なぜ配信サイトか

   実測 2026-08-19: 追跡している 1,040 社のうち**自社サイトに feed を持つのは 41 社**
   （`kotoba.property.web-presence`）。日本のプレスリリースは PR TIMES 等の配信
   サイトに集まるので、残り 96% はそちらでしか拾えない。

   ## 何を保存して、何を保存しないか

   保存するのは**引用の形**だけ —— 見出し・URL・配信日時・発表者名。
   本文（`<description>`）は発表者の著作物なので**入れない**。newsfeed の
   `:news.source/rightsPolicy \"fair-use-quote\"` と同じ線を、こちらでも引く。

   ## robots.txt を読んでから来ている

   - PR TIMES: `Allow: /`（除外は like_count / releaseimage / m3u8 の 3 つだけ）。
     公式 RSS 1.0 が `https://prtimes.jp/index.rdf` に在る。
   - ValuePress: **`Disallow: /rss/`** —— feed を明示的に禁じているので**使わない**。
   - DreamNews: robots.txt が 503 で読めない → 許可を確認できないので**使わない**。
   - 共同通信 PR ワイヤー / @Press: robots.txt は歓迎的だが、autodiscovery に
     feed が無い。HTML を掻きに行くのはここでは選ばない。

   出典：PR TIMES（株式会社 PR TIMES）https://prtimes.jp/ の公開 RSS を加工して作成"
  (:require [clojure.string :as str]))

(def dataset "press-wire")

(def distributors
  {:prtimes {:id "prtimes"
             :name "PR TIMES"
             :feed-url "https://prtimes.jp/index.rdf"
             :authority "JP/PR-TIMES"
             :attribution "出典：PR TIMES（株式会社PR TIMES）https://prtimes.jp/ の公開 RSS を加工して作成"
             ;; feed は最新 200 件しか持たない。PR TIMES の 1 日の配信量はその
             ;; 数倍あるので、**1 日 1 回では取りこぼす** —— この数字が cell の
             ;; 間隔を決める（間隔を決める前に、まず窓の大きさを測る）。
             :window-items 200}})

(defn- tag [block name]
  (some-> (re-find (re-pattern (str "<" name "[^>]*>([\\s\\S]*?)</" name ">")) block)
          second
          str/trim
          (str/replace #"^<!\[CDATA\[" "")
          (str/replace #"\]\]>$" "")))

(def ^:private item-re #"<item\s+rdf:about[^>]*>([\s\S]*?)</item>")

(defn parse-items
  "PR TIMES の RDF -> レコード。**本文は取らない。**

   `<items><rdf:Seq>` の中にも `rdf:li` が並ぶが、あれは目次であって item ではない
   （`<item rdf:about=…>` だけを見る）。"
  [xml {:keys [id authority] :as distributor} observed-at]
  (for [[_ block] (re-seq item-re (str xml))
        :let [title (tag block "title")
              link (tag block "link")
              corp (tag block "dc:corp")]
        :when (and (not (str/blank? (str title))) (not (str/blank? (str link))))]
    (cond-> {:source/dataset dataset
             :source/authority authority
             :source/observed-at observed-at
             :press/distributor id
             :press/title title
             :press/url link}
      corp (assoc :press/company-name corp)
      (tag block "dc:date") (assoc :press/published-at (tag block "dc:date"))
      (tag block "business_form") (assoc :press/business-form (tag block "business_form")))))

(defn release-key
  "同じリリースを 2 度書かないための鍵。URL がリリースごとに一意なのでそれを使う
   （PR TIMES の URL は `/main/html/rd/p/<release>.<company>.html`）。"
  [r]
  (:press/url r))

(defn with-company
  "名寄せの結果を 1 件のリリースに反映する。**解決しなかったものも捨てない** ——
   配信サイトの発表者名は登記上の商号とは限らない（ブランド名・屋号・英字表記）ので、
   未解決は「この会社は存在しない」ではなく「この名前では引けない」である。"
  [r hit]
  (if hit
    (assoc r
           :company/houjin-bangou (:houjin-bangou hit)
           :company/registration-no (:houjin-bangou hit)
           :company/legal-name (:legal-name hit)
           :company/name-match (:match hit))
    r))

(defn corpus-manifest
  [{:keys [observed-at distributor record-count seen linked window-items]}]
  (cond-> {:corpus/manifest true
           :corpus/projection true
           :corpus/format :edn-lines
           :source/dataset dataset
           :source/authority (:authority distributor)
           :source/attribution (:attribution distributor)
           :source/observed-at observed-at
           :press/distributor (:id distributor)}
    window-items (assoc :corpus/window-items window-items)
    seen (assoc :projection/seen seen)
    linked (assoc :projection/linked linked)
    record-count (assoc :corpus/record-count record-count)))
