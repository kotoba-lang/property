(ns kotoba.property.houjin-bangou-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.property.houjin-bangou-projection :as hp]
            [kotoba.property.houjin-bangou-zenken :as hb]))

(defn- rec [m]
  (merge {:company/houjin-bangou "1000012160153"
          :company/legal-name "株式会社あい"
          :company/region "JP-13"
          :company/nta-kind "301"
          :company/nta-latest? true}
         m))

(deftest matcher-with-no-criteria-takes-everything
  (is (true? ((hp/matcher {}) (rec {})))))

(deftest matcher-criteria
  (testing "number allowlist"
    (is (true? ((hp/matcher {:numbers ["1000012160153"]}) (rec {}))))
    (is (false? ((hp/matcher {:numbers ["9999999999999"]}) (rec {})))))
  (testing "prefecture accepts both halves of the ISO code"
    (is (true? ((hp/matcher {:prefectures ["JP-13"]}) (rec {}))))
    (is (true? ((hp/matcher {:prefectures ["13"]}) (rec {}))))
    (is (false? ((hp/matcher {:prefectures ["27"]}) (rec {})))))
  (testing "kind"
    (is (true? ((hp/matcher {:kinds ["301"]}) (rec {}))))
    (is (false? ((hp/matcher {:kinds ["101"]}) (rec {})))))
  (testing "latest-only drops superseded history rows"
    (is (false? ((hp/matcher {:latest-only? true}) (rec {:company/nta-latest? false})))))
  (testing "active-only drops a closed registration record"
    (is (false? ((hp/matcher {:active-only? true}) (rec {:company/closed-at "2020-01-01"})))))
  (testing "name keys match the exact name and its form-insensitive core"
    (is (true? ((hp/matcher {:name-keys #{(hb/normalize-name "株式会社あい")}}) (rec {}))))
    (is (true? ((hp/matcher {:name-keys #{(hb/name-core "あい株式会社")}}) (rec {}))))
    (is (false? ((hp/matcher {:name-keys #{(hb/normalize-name "株式会社うえ")}}) (rec {}))))))

(deftest resolve-names-keeps-the-three-outcomes-apart
  (let [a (rec {:company/houjin-bangou "1000012160153" :company/legal-name "株式会社あい"})
        b (rec {:company/houjin-bangou "2000012160153" :company/legal-name "有限会社あい"})
        candidates (-> {} (hp/collect-candidate a) (hp/collect-candidate b))]
    (testing "an exact name beats the shared core"
      (let [r (hp/resolve-names ["株式会社あい"] candidates)]
        (is (= "1000012160153" (get-in r [:resolved "株式会社あい" :company/houjin-bangou])))
        (is (= :exact (get-in r [:resolved "株式会社あい" :company/name-match])))))
    (testing "a form-insensitive query that two entities answer resolves to NOTHING"
      (let [r (hp/resolve-names ["あい"] candidates)]
        (is (empty? (:resolved r)))
        (is (= {:level :core :count 2} (get-in r [:ambiguous "あい"])))))
    (testing "a core query only one entity answers resolves, and says how"
      (let [r (hp/resolve-names ["あい"] (hp/collect-candidate {} a))]
        (is (= :core (get-in r [:resolved "あい" :company/name-match])))))
    (testing "a name the registry has never heard of is reported by name"
      (is (= ["株式会社ぜんぜんちがう"]
             (:unmatched (hp/resolve-names ["株式会社ぜんぜんちがう"] candidates)))))))

(deftest candidate-collection-is-bounded
  (let [;; One name, many entities — which is what a common company name
        ;; actually looks like in this registry.
        many (reduce (fn [acc i]
                       (hp/collect-candidate
                        acc (rec {:company/houjin-bangou (str i)
                                  :company/legal-name "株式会社あい"})))
                     {}
                     (range (* 3 hp/max-candidates)))]
    (testing "a name thousands of entities share does not become an unbounded
              in-memory list"
      (is (= hp/max-candidates (count (get-in many [:core "あい"]))))
      (is (= hp/max-candidates (count (get-in many [:exact (hb/normalize-name "株式会社あい")])))))
    (testing "and it is still reported as ambiguous, not resolved"
      (let [r (hp/resolve-names ["株式会社あい"] many)]
        (is (empty? (:resolved r)))
        (is (= hp/max-candidates (get-in r [:ambiguous "株式会社あい" :count])))))))

(deftest projection-manifest-states-what-it-excluded
  (let [m (hp/projection-manifest {:source/publish "00_zenkoku_all_20260731"
                                   :corpus/record-count 5816535}
                                  {:numbers ["1000012160153"]
                                   :prefectures ["JP-13"]
                                   :latest-only? true}
                                  1)]
    (is (true? (:corpus/projection m)))
    (is (= 1 (:corpus/record-count m)) "the projection's own count, not the corpus's")
    (is (= 1 (:projection/number-count m)))
    (is (= ["JP-13"] (:projection/prefectures m)))
    (is (true? (:projection/latest-only m)))
    (is (= "00_zenkoku_all_20260731" (:source/publish m)) "provenance survives")))
