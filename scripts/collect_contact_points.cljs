(ns collect-contact-points
  "法人番号 -> 公開連絡点（問い合わせフォーム URL・窓口メール・所在地・代表者名）。

   判断は `kotoba.property.contact-point`（純 cljc）が持つ。ここは I/O だけ:
   gBizINFO を引き、robots.txt を見て、サイトを取りに行き、EDN を書く。

   ## 候補の作り方が 2 つある

     --names <file>   1 行 1 社名。gBizINFO の検索で法人番号に解決する。
                      既に名前で決めてある ICP（lead-list.md）を機械可読にする経路。
     --discover       prefecture + 従業員数レンジで gBizINFO を歩く。

   ⚠ **gBizINFO の `industry` / `business_item` パラメータは絞らない**（2026-08-25 実測。
   `industry=A`（農業）と `industry=G`（情報通信業）が同一の結果を返した）。だから
   業種の絞り込みは `--industry` でこちら側でやる —— 詳細レスポンスの `industry`
   （JSIC 大分類）を見て落とす。**サーバに絞らせたつもりで絞れていない、が一番安い
   間違いなので、ここに書いておく。**

   ## 測れなかったことを 0 件と書かない

   1 社 1 行、`:lead/status` は 5 値（`:ok` `:no-website` `:fetch-failed`
   `:no-contact-point` `:robots-disallowed`）。**失敗した社も行として残す。**
   最後に `SCANNED<TAB>n` を出し、1 件も歩けなかった run は **exit 2**
   （0 でも 1 でもない = 「答えられなかった」）で終わる。

   usage:
     nbb -cp src scripts/collect_contact_points.cljs --out <f> --names <f> [--limit N]
     nbb -cp src scripts/collect_contact_points.cljs --out <f> --discover \\
         --prefecture 13 --employee-from 10 --employee-to 300 --industry G --limit 200

   Requires GBIZINFO_TOKEN（env か Keychain `gbizinfo-api-token`）。無ければ exit 3。"
  (:require [clojure.string :as str]
            [kotoba.property.contact-point :as cp]
            ["child_process" :as cp-node]
            ["fs" :as fs]))

(def argv (vec *command-line-args*))
(defn- arg [n d] (or (second (drop-while #(not= n %) argv)) d))
(defn- flag? [n] (boolean (some #{n} argv)))
(defn- int-arg [n d] (let [v (arg n nil)] (if v (js/parseInt v 10) d)))

(defn- die! [code msg]
  (js/console.error msg)
  (set! (.-exitCode js/process) code)
  (throw (ex-info msg {:exit code})))

(defn- token []
  (or (not-empty (.. js/process -env -GBIZINFO_TOKEN))
      ;; 既知の識別子 1 件だけを狙い撃ちする（総当たり dump をしない）
      (let [r (.spawnSync cp-node "security"
                          #js ["find-generic-password" "-s" "gbizinfo-api-token" "-w"]
                          #js {:encoding "utf8"})]
        (when (zero? (or (.-status r) 1)) (not-empty (str/trim (str (.-stdout r))))))))

(defn- now [] (.toISOString (js/Date.)))
(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

;; ---------------------------------------------------------------------------
;; fetch — 落ちても例外にしない。1 社のタイムアウトで全体を止めない。

(defn- fetch-text
  "[status body] を返す。取りに行けなかったときは [nil nil]。
   **status 0 と status 404 を同じ nil に畳まない** —— 前者は測れなかった、
   後者は測って無かった。"
  ([url ms] (fetch-text url ms nil))
  ([url ms headers]
   (let [ctl (js/AbortController.)
         t (js/setTimeout #(.abort ctl) ms)]
     (-> (js/fetch url (clj->js {:signal (.-signal ctl)
                                 :redirect "follow"
                                 :headers (merge {"user-agent" cp/user-agent
                                                  "accept-language" "ja,en;q=0.8"}
                                                 headers)}))
         (.then (fn [^js res]
                  (-> (.text res)
                      (.then (fn [body] [(.-status res) body]))
                      (.catch (fn [_] [(.-status res) nil])))))
         (.catch (fn [_] [nil nil]))
         (.finally (fn [] (js/clearTimeout t)))))))

;; ---------------------------------------------------------------------------
;; gBizINFO

(def api-base "https://api.info.gbiz.go.jp/hojin/v2/hojin")

(defn- gbiz-json [url tok]
  (-> (fetch-text url 25000 {"X-hojinInfo-api-token" tok})
      (.then (fn [[status body]]
               (when (and (= 200 status) body)
                 (try (js->clj (js/JSON.parse body)) (catch :default _ nil)))))))

(defn- search-by-name [name tok]
  (-> (gbiz-json (str api-base "?name=" (js/encodeURIComponent name) "&limit=10") tok)
      (.then (fn [d] (vec (get d "hojin-infos"))))))

(defn- search-page
  "prefecture + 従業員数レンジで 1 ページ。**この 3 つは discriminate することを
   実測済み**（industry と business_item は無視される）。"
  [{:keys [prefecture employee-from employee-to page limit]} tok]
  (-> (gbiz-json (str api-base "?prefecture=" prefecture
                      "&employee_number_from=" employee-from
                      "&employee_number_to=" employee-to
                      "&page=" page "&limit=" limit)
                 tok)
      (.then (fn [d] (vec (get d "hojin-infos"))))))

(defn- detail [corporate-number tok]
  (-> (gbiz-json (str api-base "/" corporate-number) tok)
      (.then (fn [d] (first (get d "hojin-infos"))))))

;; ---------------------------------------------------------------------------
;; 1 社を観測する

(defn- probe-contact
  "homepage を起点に連絡点を探す。見つかったら {:contact-url .. :html ..}。
   robots.txt に拒まれたパスには行かない。"
  [site-url robots delay-ms]
  (let [org (cp/origin site-url)
        blocked? (fn [u] (let [p (str/replace (str u) (re-pattern (str "^" org)) "")]
                           (cp/robots-disallows? robots cp/user-agent (if (str/blank? p) "/" p))))]
    (if (blocked? site-url)
      (js/Promise.resolve {:status :robots-disallowed})
      (-> (fetch-text site-url 20000)
          (.then
           (fn [[hstatus home]]
             (if-not (and hstatus home)
               {:status :fetch-failed :note (str "homepage status=" (or hstatus "none"))}
               ;; **fetch の前に順位を決める。** 1 件目が通った時点で止めるので、
             ;; 順序が精度そのものになる（実測: 順位付け無しでは Stockmark の
             ;; `/company/information` が本物の窓口を押しのけた）。
             (let [candidates (->> (concat (cp/discover-contact-links home site-url)
                                           (map #(str org %) cp/common-contact-paths))
                                   (remove blocked?)
                                   cp/rank-contact-candidates
                                   (take 8)
                                   vec)]
                 (-> (reduce
                      (fn [p url]
                        (.then p (fn [found]
                                   (if found
                                     found
                                     (-> (sleep delay-ms)
                                         (.then (fn [] (fetch-text url 20000)))
                                         (.then (fn [[st body]]
                                                  (when (and (= 200 st) body (cp/contact-page? body))
                                                    {:contact-url url :html body}))))))))
                      (js/Promise.resolve nil)
                      candidates)
                     (.then (fn [found]
                              (if found
                                (assoc found :status :ok :home-html home)
                                {:status :no-contact-point :home-html home
                                 :note (str "probed " (count candidates) " candidate path(s)")})))))))))))) 

(defn- observe
  "registry レコード -> observation map（`cp/->record` に渡す形）。

   `fallback-url` は names ファイルが `社名<TAB>URL` で与えたもの。登記に URL が
   無い会社（実測: JAPAN AI）を `:no-website` のまま捨てないための口だが、
   **出所は分けて記録する** —— 登記の自己申告ではなく、こちらが当てた URL である。"
  [registry fallback-url delay-ms]
  (let [reg-url (cp/normalise-url (get registry "company_url"))
        site (or reg-url (cp/normalise-url fallback-url))
        url-source (if reg-url :gbizinfo :operator-supplied)]
    (if-not site
      (js/Promise.resolve {:status :no-website :observed-at (now)})
      (-> (fetch-text (str (cp/origin site) "/robots.txt") 10000)
          (.then (fn [[_ robots]] (probe-contact site (or robots "") delay-ms)))
          (.then (fn [{:keys [status contact-url html home-html note]}]
                   (let [pages (remove nil? [html home-html])
                         emails (->> pages (mapcat cp/extract-emails) distinct vec)]
                     (cond-> {:status status :observed-at (now) :note note
                              :web-url-source url-source}
                       contact-url (assoc :contact-url contact-url)
                       (seq emails) (assoc :emails emails)
                       html (assoc :solicitation-forbidden? (cp/solicitation-forbidden? html)
                                   :site-postal-code (cp/extract-site-postal-code html))))))))))

;; ---------------------------------------------------------------------------
;; 候補づくり

(defn- candidates-from-names
  "`names` は `{:name .. :url ..}` の列。`:url` は任意（登記に URL が無いとき用）。"
  [names tok delay-ms]
  (reduce (fn [p {nm :name url :url}]
            (.then p (fn [acc]
                       (-> (sleep delay-ms)
                           (.then (fn [] (search-by-name nm tok)))
                           ;; **1 件目を採らない。** 完全一致だけを採り、外れたら
                           ;; 理由を持って `:unresolved` の行として残す（実測で
                           ;; リコー -> 閉鎖子会社、ABEJA -> 別会社を掴んだ）。
                           (.then (fn [hits]
                                    (let [{:keys [hit match rejected]} (cp/resolve-hit nm hits)]
                                      (if hit
                                        (conj acc {:corporate-number (get hit "corporate_number")
                                                   :asked-name nm
                                                   :name-match match
                                                   :fallback-url url})
                                        (do (js/console.error
                                             (str "  unresolved  " nm
                                                  (when (seq rejected)
                                                    (str "  (rejected: "
                                                         (str/join ", " (map #(str (:name %) "/" (name (:reason %))) rejected))
                                                         ")"))))
                                          (conj acc {:unresolved true
                                                     :asked-name nm
                                                     :rejected-candidates (vec rejected)}))))))))))
          (js/Promise.resolve [])
          names))

(defn- candidates-from-discovery [opts tok delay-ms limit]
  (let [per-page 100]
    (letfn [(step [page acc]
              (if (>= (count acc) limit)
                (js/Promise.resolve (vec (take limit acc)))
                (-> (sleep delay-ms)
                    (.then (fn [] (search-page (assoc opts :page page :limit per-page) tok)))
                    (.then (fn [hits]
                             (if (empty? hits)
                               (js/Promise.resolve (vec acc))
                               (step (inc page)
                                     (into acc (map (fn [h] {:corporate-number (get h "corporate_number")}) hits)))))))))]
      (step 1 []))))

;; ---------------------------------------------------------------------------
;; main

(defn- industry-ok? [registry wanted]
  (or (str/blank? (str wanted))
      (let [inds (set (map str (get registry "industry")))]
        (contains? inds (str wanted)))))

(defn -main []
  (let [out (arg "--out" nil)
        tok (token)
        delay-ms (int-arg "--delay-ms" 400)
        limit (int-arg "--limit" 50)
        industry (arg "--industry" nil)
        names-file (arg "--names" nil)]
    (when-not out (die! 3 "--out is required"))
    (when-not tok (die! 3 "GBIZINFO_TOKEN not in env and not in Keychain (gbizinfo-api-token)"))
    (when-not (or names-file (flag? "--discover"))
      (die! 3 "give either --names <file> or --discover"))
    (-> (if names-file
          ;; 1 行 1 社。`社名` だけでも、`社名<TAB>URL` でもよい。
          (let [names (->> (str (fs/readFileSync names-file "utf8"))
                           str/split-lines
                           (map str/trim)
                           (remove str/blank?)
                           (remove #(str/starts-with? % "#"))
                           (map (fn [line]
                                  (let [[nm url] (str/split line #"\t" 2)]
                                    {:name (str/trim nm)
                                     :url (some-> url str/trim not-empty)})))
                           (take limit)
                           vec)]
            (js/console.error (str "resolving " (count names) " name(s) via gBizINFO search"))
            (candidates-from-names names tok delay-ms))
          (do (js/console.error "discovering candidates via gBizINFO search")
              (candidates-from-discovery {:prefecture (arg "--prefecture" "13")
                                          :employee-from (int-arg "--employee-from" 10)
                                          :employee-to (int-arg "--employee-to" 300)}
                                         tok delay-ms limit)))
        (.then
         (fn [cands]
           (js/console.error (str "candidates: " (count cands)))
           (reduce
            (fn [p {:keys [corporate-number asked-name fallback-url name-match
                           unresolved rejected-candidates]}]
              (.then p (fn [{:keys [records skipped] :as acc}]
                         (if unresolved
                           (js/Promise.resolve
                            (assoc acc :records
                                   (conj records (cp/->record {} {:status :unresolved
                                                                  :observed-at (now)
                                                                  :rejected-candidates rejected-candidates
                                                                  :note (str "asked: " asked-name)}))))
                         (-> (sleep delay-ms)
                             (.then (fn [] (detail corporate-number tok)))
                             (.then (fn [reg]
                                      (cond
                                        (nil? reg)
                                        (do (js/console.error (str "  detail unavailable: " corporate-number))
                                            (assoc acc :skipped (inc skipped)))

                                        (not (industry-ok? reg industry))
                                        (assoc acc :skipped (inc skipped))

                                        :else
                                        (-> (observe reg fallback-url delay-ms)
                                            (.then (fn [obs]
                                                     (let [reg (if (and fallback-url
                                                                        (str/blank? (str (get reg "company_url"))))
                                                                 (assoc reg "company_url" fallback-url)
                                                                 reg)
                                                           rec (cond-> (cp/->record reg (assoc obs :name-match name-match))
                                                                 asked-name (assoc :lead/asked-name asked-name))]
                                                       (js/console.error
                                                        (str "  " (name (:lead/status rec)) "  "
                                                             (:company/legal-name rec)
                                                             (when-let [u (:contact/form-url rec)] (str "  " u))))
                                                       (assoc acc :records (conj records rec))))))))))))))
            (js/Promise.resolve {:records [] :skipped 0})
            cands)))
        (.then
         (fn [{:keys [records skipped]}]
           (if (zero? (count records))
             (die! 2 (str "Refusing to report a pass: 0 companies scanned"
                          " (skipped=" skipped "). Nothing was measured."))
             (let [cov (assoc (cp/coverage records)
                              :coverage/skipped-by-filter skipped
                              :coverage/collected-at (now)
                              :source/attribution cp/attribution)]
               (fs/writeFileSync out (with-out-str
                                       (println ";; 生成物。手で編集しない。")
                                       (println ";; 再生成: nbb -cp src scripts/collect_contact_points.cljs")
                                       (println (str ";; " cp/attribution))
                                       (prn (into [cov] records))))
               (println (str "SCANNED\t" (count records)))
               (println (str "CONTACTABLE\t" (:coverage/contactable cov)))
               (println (str "FORBIDDEN\t" (:coverage/solicitation-forbidden cov)))
               (println (str "SKIPPED\t" skipped))
               (println (str "OUT\t" out))))))
        (.catch (fn [e]
                  (when-not (:exit (ex-data e))
                    (js/console.error (str "collector failed: " (.-message e)))
                    (set! (.-exitCode js/process) 2)))))))

(-main)
