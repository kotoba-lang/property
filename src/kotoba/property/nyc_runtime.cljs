(ns kotoba.property.nyc-runtime)

(def source-id "nyc-owned-properties")
(def endpoint "https://data.cityofnewyork.us/resource/ynxg-k94i.json")

(defn normalize-record
  "Turn a NYC-owned-property API row into the portable ownership contract."
  [observed-at record]
  (let [bbl (:bbl record)
        agency (:agency record)]
    (when (and bbl agency)
      {:ownership/id (str source-id ":" bbl)
       :ownership/parcel (str "US-NY-NYC:BBL:" bbl)
       :ownership/holder (str "City of New York / " agency)
       :ownership/holder-id (str "US-NY-NYC:agency:" agency)
       :ownership/holder-kind :public-body
       :ownership/source source-id
       :ownership/observed-at observed-at
       :ownership/licence "NYC Open Data Terms of Use"
       :ownership/disclosure :public})))
