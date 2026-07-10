(ns collect-gleif
  "Credential-free GLEIF Level 2 direct-parent collector for nbb."
  (:require [cljs.reader :as reader]
            [kotoba.property.gleif-runtime :as gleif]
            ["fs" :as fs]
            ["path" :as path]))

(def default-store "var/kotoba-property/gleif-level-2.edn")

(defn- arg-value [args option]
  (second (drop-while #(not= option %) args)))

(defn- read-state [store]
  (if (.existsSync fs store)
    (reader/read-string (.readFileSync fs store "utf8"))
    {:lei-records {} :corporate-relations {} :source-state {}}))

(defn- write-state! [store state]
  (.mkdirSync fs (.dirname path store) #js {:recursive true})
  (.writeFileSync fs store (pr-str state) "utf8"))

(defn- fetch-json [url]
  (-> (js/fetch url)
      (.then (fn [response]
               (if (.-ok response)
                 (.json response)
                 (js/Promise.reject (js/Error. (str "GLEIF HTTP " (.-status response)))))))))

(defn- collect-state [state observed-at relation entity]
  (let [relation-record (gleif/normalize-relation observed-at relation)
        entity-record (gleif/normalize-entity observed-at entity)]
    (assoc state
           :corporate-relations (assoc (:corporate-relations state)
                                       (:corporate-relation/id relation-record) relation-record)
           :lei-records (assoc (:lei-records state)
                               (:legal-entity/lei entity-record) entity-record)
           :source-state (assoc (:source-state state) gleif/source-id
                                {:retrieved-at observed-at
                                 :endpoint gleif/api-root})) ))

(defn -main []
  (let [lei (arg-value *command-line-args* "--lei")
        store (or (arg-value *command-line-args* "--store") default-store)]
    (when-not lei
      (binding [*out* *err*] (println "Usage: nbb -cp src scripts/collect_gleif.cljs --lei LEI"))
      (js/process.exit 2))
    (-> (fetch-json (str gleif/api-root "/" lei "/direct-parent-relationship"))
        (.then (fn [relation-response]
                 (let [relation (:data (js->clj relation-response :keywordize-keys true))
                       parent-lei (get-in relation [:attributes :relationship :endNode :id])]
                   (-> (fetch-json (str gleif/api-root "/" parent-lei))
                       (.then (fn [entity-response]
                                {:relation relation
                                 :entity (:data (js->clj entity-response :keywordize-keys true))}))))))
        (.then (fn [{:keys [relation entity]}]
                 (let [state (collect-state (read-state store)
                                            (.toISOString (js/Date.)) relation entity)]
                   (write-state! store state)
                   (println (pr-str {:store store
                                     :corporate-relations (count (:corporate-relations state))
                                     :lei-records (count (:lei-records state))})))))
        (.catch (fn [error]
                  (binding [*out* *err*] (println (.-message error)))
                  (js/process.exit 1))))))

(-main)
