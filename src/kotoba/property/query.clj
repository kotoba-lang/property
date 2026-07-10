(ns kotoba.property.query
  "Query local governed UBO state with DataScript."
  (:require [clojure.edn :as edn]
            [datascript.core :as d]
            [kotoba.property.ownership :as ownership]))

(def default-store "var/kotoba-property/gb-ubo.edn")

(defn- usage []
  "Usage: clojure -M:query --company COMPANY_NUMBER [--store PATH]")

(defn- parse-args [args]
  (loop [args args result {:store default-store}]
    (if-let [arg (first args)]
      (case arg
        "--company" (recur (nnext args) (assoc result :company (second args)))
        "--store" (recur (nnext args) (assoc result :store (second args)))
        (throw (ex-info (str "Unknown argument: " arg) {:usage (usage)})))
      result)))

(defn- db-from-store [path]
  (let [records (vals (:ubo-records (edn/read-string (slurp path))))]
    (d/db-with (d/empty-db (edn/read-string
                             (slurp "resources/property/open_data/datascript-schema.edn")))
               records)))

(defn -main [& args]
  (let [{:keys [company store]} (parse-args args)]
    (when-not company
      (binding [*out* *err*] (println (usage))
      (System/exit 2))
    (doseq [[ubo person-name control source observed-at]
            (sort-by second
                     (d/q ownership/public-ubo-by-company-query
                          (db-from-store store) company))]
      (println (pr-str {:ubo/id ubo :ubo/person-name person-name
                        :ubo/control control :ubo/source source
                        :ubo/observed-at observed-at})))))
