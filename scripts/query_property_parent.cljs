(ns query-property-parent
  "Join HMLR corporate property ownership to a GLEIF direct parent in nbb DataScript."
  (:require [cljs.reader :as reader]
            [kotoba.property.datascript-runtime :as runtime]
            ["fs" :as fs]))

(def default-property-store "var/kotoba-property/gb-ubo.edn")
(def default-gleif-store "var/kotoba-property/gleif-level-2.edn")

(def query
  "[:find ?holder ?company-number ?parent-lei ?parent-name ?relation-type
     :in $ ?parcel
     :where
     [?ownership \"ownership/parcel\" ?parcel]
     [?ownership \"ownership/holder\" ?holder]
     [?ownership \"ownership/holder-id\" ?company-number]
     [?entity \"legal-entity/registered-as\" ?company-number]
     [?entity \"legal-entity/lei\" ?child-lei]
     [?relation \"corporate-relation/child-lei\" ?child-lei]
     [?relation \"corporate-relation/parent-lei\" ?parent-lei]
     [?relation \"corporate-relation/type\" ?relation-type]
     [?parent \"legal-entity/lei\" ?parent-lei]
     [?parent \"legal-entity/name\" ?parent-name]]")

(defn -main []
  (let [args *command-line-args*
        parcel (second (drop-while #(not= "--parcel" %) args))
        property-store (or (second (drop-while #(not= "--property-store" %) args))
                           default-property-store)
        gleif-store (or (second (drop-while #(not= "--gleif-store" %) args))
                        default-gleif-store)]
    (when-not parcel
      (binding [*out* *err*]
        (println "Usage: nbb -cp src scripts/query_property_parent.cljs --parcel PARCEL"))
      (js/process.exit 2))
    (let [property-state (reader/read-string (.readFileSync fs property-store "utf8"))
          gleif-state (reader/read-string (.readFileSync fs gleif-store "utf8"))
          records (concat (vals (:ownership-records property-state))
                          (vals (:corporate-relations gleif-state))
                          (vals (:lei-records gleif-state)))
          db (runtime/db records)]
      (doseq [row (js->clj (.q runtime/datasource query db parcel))]
        (println (pr-str (zipmap [:ownership/holder :ownership/company-number
                                  :legal-entity/parent-lei :legal-entity/parent-name
                                  :corporate-relation/type] row)))))))

(-main)
