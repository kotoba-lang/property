(ns kotoba.property.query
  "Query local governed UBO state with DataScript."
  (:require [clojure.edn :as edn]
            [datascript.core :as d]
            [kotoba.property.ownership :as ownership]))

(def default-store "var/kotoba-property/gb-ubo.edn")

(defn- usage []
  "Usage: clojure -M:query (--company COMPANY_NUMBER | --parcel GB-HMLR:TITLE) [--store PATH]")

(defn- parse-args [args]
  (loop [args args result {:store default-store}]
    (if-let [arg (first args)]
      (case arg
        "--company" (recur (nnext args) (assoc result :company (second args)))
        "--parcel" (recur (nnext args) (assoc result :parcel (second args)))
        "--store" (recur (nnext args) (assoc result :store (second args)))
        (throw (ex-info (str "Unknown argument: " arg) {:usage (usage)})))
      result)))

(defn- db-from-store [path]
  (let [state (edn/read-string (slurp path))
        records (concat (vals (:ownership-records state))
                        (vals (:ubo-records state)))]
    (d/db-with (d/empty-db (edn/read-string
                             (slurp "resources/property/open_data/datascript-schema.edn")))
               records)))

(defn -main [& args]
  (let [{:keys [company parcel store]} (parse-args args)]
    (when-not (or company parcel)
      (binding [*out* *err*] (println (usage))
      (System/exit 2)))
    (let [db (db-from-store store)
          rows (if parcel
                 (d/q ownership/public-ownership-and-ubo-by-parcel-query db parcel)
                 (d/q ownership/public-ubo-by-company-query db company))]
      (doseq [row (sort-by second rows)]
        (println
         (pr-str
          (if parcel
            (zipmap [:ownership/holder :ownership/holder-id :ubo/person-name
                     :ubo/control :ubo/source :ubo/observed-at] row)
            (zipmap [:ubo/id :ubo/person-name :ubo/control :ubo/source
                     :ubo/observed-at] row))))))))
