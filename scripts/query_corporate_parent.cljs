(ns query-corporate-parent
  "Run a DataScript Datalog query over nbb-collected GLEIF Level 2 data."
  (:require [cljs.reader :as reader]
            [kotoba.property.datascript-runtime :as runtime]
            ["fs" :as fs]))

(def default-store "var/kotoba-property/gleif-level-2.edn")

(def query
  "[:find ?parent-lei ?parent-name ?relation-type ?observed-at
     :in $ ?child-lei
     :where
     [?relation \"corporate-relation/child-lei\" ?child-lei]
     [?relation \"corporate-relation/parent-lei\" ?parent-lei]
     [?relation \"corporate-relation/type\" ?relation-type]
     [?relation \"corporate-relation/observed-at\" ?observed-at]
     [?entity \"legal-entity/lei\" ?parent-lei]
     [?entity \"legal-entity/name\" ?parent-name]]")

(defn -main []
  (let [lei (second (drop-while #(not= "--lei" %) *command-line-args*))
        store (or (second (drop-while #(not= "--store" %) *command-line-args*)) default-store)]
    (when-not lei
      (binding [*out* *err*] (println "Usage: nbb -cp src scripts/query_corporate_parent.cljs --lei LEI"))
      (js/process.exit 2))
    (let [state (reader/read-string (.readFileSync fs store "utf8"))
          records (concat (vals (:corporate-relations state)) (vals (:lei-records state)))
          db (runtime/db records)]
      (doseq [row (js->clj (.q runtime/datasource query db lei))]
        (println (pr-str (zipmap [:legal-entity/parent-lei :legal-entity/parent-name
                                  :corporate-relation/type :corporate-relation/observed-at]
                                 row)))))))

(-main)
