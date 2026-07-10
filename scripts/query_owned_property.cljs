(ns query-owned-property
  "Credential-free EDN query runner for nbb-collected NYC public ownership."
  (:require [cljs.reader :as reader]
            ["fs" :as fs]))

(def default-store "var/kotoba-property/nyc-owned-properties.edn")

(defn -main []
  (let [args *command-line-args*
        parcel (second (drop-while #(not= "--parcel" %) args))
        store (or (second (drop-while #(not= "--store" %) args)) default-store)]
    (when-not parcel
      (binding [*out* *err*] (println "Usage: nbb -cp src scripts/query_owned_property.cljs --parcel PARCEL"))
      (js/process.exit 2))
    (let [state (reader/read-string (.readFileSync fs store "utf8"))]
      (doseq [[id claim] (:ownership-records state)
              :when (= parcel (:ownership/parcel claim))]
        (println (pr-str (assoc claim :ownership/id id)))))))

(-main)
