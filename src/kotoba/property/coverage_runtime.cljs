(ns kotoba.property.coverage-runtime
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["fs" :as fs]))

(defn- catalog []
  (reader/read-string (.readFileSync fs "resources/property/open_data/coverage.edn" "utf8")))

(defn authority-id [source]
  (cond
    (= source "nyc-owned-properties") "US-NY-NYC/NYC-OpenData"
    (= source "gleif-level-2") "GLOBAL/GLEIF"
    (str/starts-with? source "gleif-level-1:") "GLOBAL/GLEIF"
    (= source "companies-house-psc") "GB/Companies-House"
    (= source "hmlr-uk-corporate-property") "GB-ENG-WLS/HM-Land-Registry"
    :else nil))

(defn assert-collectable! [source]
  (let [authority (authority-id source)
        entry (some #(when (= authority (:authority/id %)) %)
                    (:coverage/entries (catalog)))]
    (when-not (and entry (contains? #{:allow-login-free :governed-license}
                                    (:status entry)))
      (throw (ex-info "Source is not allowlisted for collection"
                      {:source source :authority authority
                       :status (:status entry)})))
    entry))
