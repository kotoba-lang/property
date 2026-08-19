(ns kotoba.property.press-wire
  "プレスリリース配信サイトの公開フィードを読む（いまは PR TIMES）。

   ## なぜ配信サイトか

   実測 2026-08-19: 追跡している 1,040 社のうち**自社サイトに feed を持つのは 41 社**
   （`kotoba.property.web-presence`）。日本のプレスリリースは PR TIMES 等の配信
   サイトに集まるので、残り 96% はそちらでしか拾えない。

   ## フィードは窓ではなく標本

   `index.rdf` は 200 件を返すが、それは「最新 200 件」ではない —— 数分あけて
   取り直すと 129〜165 件が入れ替わり、1 回が張る期間も 6.8 日〜28 日と動く。
   したがって**全リリースを網羅することはできない**し、「1 日 N 件」という率も
   このフィードからは出せない。貯まるのは増え続ける標本である。

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
             ;; **窓ではなく標本である。** 実測 2026-08-19:
             ;;   - 5 秒あけた 2 回の取得は 200/200 完全一致（瞬間的には安定）
             ;;   - 数分あけると 129〜165 件が入れ替わる
             ;;   - 1 回の取得が張る期間は 6.8 日だったり 28 日だったりする
             ;; つまり `index.rdf` は「最新 200 件」ではなく**直近 1 ヶ月ほどからの
             ;; 回転する標本**で、ここから「1 日あたり何件」は出せない。巡回間隔を
             ;; 窓の広さから導けないので、**礼儀と逓減する取り分**で日次にしている。
             ;; 毎回およそ 130〜165 件が新規なので、貯めれば標本は増え続ける。
             :sample-items 200}})

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
  [{:keys [observed-at distributor record-count seen linked sample-items]}]
  (cond-> {:corpus/manifest true
           :corpus/projection true
           :corpus/format :edn-lines
           :source/dataset dataset
           :source/authority (:authority distributor)
           :source/attribution (:attribution distributor)
           :source/observed-at observed-at
           :press/distributor (:id distributor)}
    sample-items (assoc :corpus/sample-items sample-items)
    seen (assoc :projection/seen seen)
    linked (assoc :projection/linked linked)
    record-count (assoc :corpus/record-count record-count)))
