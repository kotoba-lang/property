(ns kotoba.property.export
  "Operator-facing export for a real-estate-agency actor.

  Renders parcels, listings and leases to CSV and JSON for tenancy audit and
  downstream reporting. Pure data → text: no network."
  (:require [clojure.string :as str]
            [kotoba.property :as prop]))

(defn- csv-cell [v]
  (let [s (str (if (nil? v) "" v))]
    (if (re-find #"[\",\n]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- csv-row [vals] (str/join "," (map csv-cell vals)))

(defn- json-str [v]
  (-> (str (if (nil? v) "" v))
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn parcels->csv [parcels]
  (str/join "\n"
    (cons (csv-row ["parcel_id" "address" "area_m2" "zoning"])
          (for [p parcels]
            (csv-row [(:parcel/id p)
                      (or (:parcel/address p) "")
                      (or (:parcel/area-m2 p) "")
                      (name (or (:parcel/zoning p) :unspecified))])))))

(defn listings->csv [listings]
  (str/join "\n"
    (cons (csv-row ["listing_id" "parcel" "type" "price" "currency"])
          (for [l listings]
            (csv-row [(:listing/id l)
                      (:listing/parcel l)
                      (name (:listing/type l))
                      (:listing/price l)
                      (:listing/currency l)])))))

(defn leases->csv [leases]
  (str/join "\n"
    (cons (csv-row ["lease_id" "parcel" "tenant" "landlord" "rent" "start" "end" "overlap"])
          (for [l leases]
            (csv-row [(:lease/id l)
                      (:lease/parcel l)
                      (or (:lease/tenant l) "")
                      (or (:lease/landlord l) "")
                      (:lease/rent l)
                      (:lease/start l)
                      (:lease/end l)
                      (if (some #(prop/term-overlaps? l %) (remove #{l} leases)) "yes" "no")])))))

(defn parcels->json [parcels]
  (str "["
       (str/join ","
                 (for [p parcels]
                   (str "{\"parcel_id\":\"" (json-str (:parcel/id p)) "\","
                        "\"address\":\"" (json-str (:parcel/address p)) "\","
                        "\"area_m2\":" (or (:parcel/area-m2 p) 0) ","
                        "\"zoning\":\"" (name (or (:parcel/zoning p) :unspecified)) "\"}")))
       "]"))

(defn leases->json [leases]
  (str "["
       (str/join ","
                 (for [l leases]
                   (str "{\"lease_id\":\"" (json-str (:lease/id l)) "\","
                        "\"parcel\":\"" (json-str (:lease/parcel l)) "\","
                        "\"tenant\":\"" (json-str (:lease/tenant l)) "\","
                        "\"rent\":" (or (:lease/rent l) 0) ","
                        "\"start\":\"" (json-str (:lease/start l)) "\","
                        "\"end\":\"" (json-str (:lease/end l)) "\"}")))
       "]"))
