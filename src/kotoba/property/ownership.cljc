(ns kotoba.property.ownership
  "Portable ownership-claim contract for public property data.")

(def public-claims-by-parcel-query
  "Datalog query usable unchanged by Datomic and DataScript."
  '[:find ?claim ?holder ?source ?observed-at
    :in $ ?parcel
    :where
    [?claim :ownership/parcel ?parcel]
    [?claim :ownership/holder ?holder]
    [?claim :ownership/source ?source]
    [?claim :ownership/observed-at ?observed-at]
    [?claim :ownership/disclosure :public]])

(def required-claim-keys
  #{:ownership/id
    :ownership/parcel
    :ownership/holder
    :ownership/holder-kind
    :ownership/source
    :ownership/observed-at
    :ownership/licence
    :ownership/disclosure})

(def holder-kinds #{:company :public-body :nonprofit :collective :unknown})

(defn validate-claim
  "Validate a public, attributable ownership claim without performing I/O.
   Natural-person holders are intentionally outside this public contract."
  [claim]
  (cond
    (not (map? claim))
    {:ownership/valid? false :ownership/error :not-a-map}

    (not (every? #(contains? claim %) required-claim-keys))
    {:ownership/valid? false :ownership/error :missing-required-key}

    (= :natural-person (:ownership/holder-kind claim))
    {:ownership/valid? false :ownership/error :natural-person-not-permitted}

    (not (contains? holder-kinds (:ownership/holder-kind claim)))
    {:ownership/valid? false :ownership/error :unknown-holder-kind}

    (not= :public (:ownership/disclosure claim))
    {:ownership/valid? false :ownership/error :not-publicly-disclosed}

    :else
    {:ownership/valid? true}))
