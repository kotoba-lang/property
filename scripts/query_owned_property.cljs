(ns query-owned-property
  "Credential-free EDN query runner for nbb-collected NYC public ownership."
  (:require [cljs.reader :as reader]
            [kotoba.property.datascript-runtime :as runtime]
            ["fs" :as fs]))

(def default-store "var/kotoba-property/nyc-owned-properties.edn")

(def query
  "[:find ?id ?holder ?source ?observed-at
     :in $ ?parcel
     :where
     [?claim \"ownership/parcel\" ?parcel]
     [?claim \"ownership/id\" ?id]
     [?claim \"ownership/holder\" ?holder]
     [?claim \"ownership/source\" ?source]
     [?claim \"ownership/observed-at\" ?observed-at]
     [?claim \"ownership/disclosure\" \"public\"]]")

(defn -main []
  (let [args *command-line-args*
        parcel (second (drop-while #(not= "--parcel" %) args))
        store (or (second (drop-while #(not= "--store" %) args)) default-store)]
    (when-not parcel
      (binding [*out* *err*] (println "Usage: nbb -cp src scripts/query_owned_property.cljs --parcel PARCEL"))
      (js/process.exit 2))
    (let [state (reader/read-string (.readFileSync fs store "utf8"))
          db (runtime/db (vals (:ownership-records state)))]
      (doseq [row (js->clj (.q runtime/datasource query db parcel))]
        (println (pr-str (zipmap [:ownership/id :ownership/holder :ownership/source
                                  :ownership/observed-at] row)))))))

(-main)
