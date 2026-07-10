(ns collect-nyc
  "Credential-free NYC-owned property collector for nbb."
  (:require [cljs.reader :as reader]
            [kotoba.property.nyc-runtime :as nyc]
            ["fs" :as fs]
            ["path" :as path]))

(def default-store "var/kotoba-property/nyc-owned-properties.edn")

(defn- arg-value [args option default]
  (or (second (drop-while #(not= option %) args)) default))

(defn- read-state [store]
  (if (.existsSync fs store)
    (reader/read-string (.readFileSync fs store "utf8"))
    {:ownership-records {} :source-state {}}))

(defn- write-state! [store state]
  (.mkdirSync fs (.dirname path store) #js {:recursive true})
  (.writeFileSync fs store (pr-str state) "utf8"))

(defn- collect-state [state observed-at rows]
  (let [claims (keep #(nyc/normalize-record observed-at %) rows)
        retained (into {}
                       (remove (fn [[_ claim]]
                                 (= nyc/source-id (:ownership/source claim))))
                       (:ownership-records state))]
    (assoc state
           :ownership-records (into retained (map (juxt :ownership/id identity) claims))
           :source-state (assoc (:source-state state) nyc/source-id
                                {:retrieved-at observed-at
                                 :record-count (count claims)
                                 :endpoint nyc/endpoint})) ))

(defn -main []
  (let [args *command-line-args*
        limit (js/parseInt (arg-value args "--limit" "500") 10)
        store (arg-value args "--store" default-store)]
    (-> (js/fetch (str nyc/endpoint "?$limit=" limit))
        (.then (fn [response]
                 (if (.-ok response)
                   (.json response)
                   (js/Promise.reject (js/Error. (str "NYC Open Data HTTP " (.-status response)))))))
        (.then (fn [rows]
                 (let [state (collect-state (read-state store)
                                            (.toISOString (js/Date.))
                                            (js->clj rows :keywordize-keys true))]
                   (write-state! store state)
                   (println (pr-str {:store store
                                     :ownership-records (count (:ownership-records state))
                                     :source nyc/source-id})))))
        (.catch (fn [error]
                  (binding [*out* *err*] (println (.-message error)))
                  (js/process.exit 1))))))

(-main)
