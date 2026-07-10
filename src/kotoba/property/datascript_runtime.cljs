(ns kotoba.property.datascript-runtime
  (:require ["datascript" :as datascript]))

(def datasource (.-default datascript))

(defn record->datascript [record]
  (let [result #js {}]
    (doseq [[key value] record]
      (aset result
            (if (keyword? key)
              (str (namespace key) "/" (name key))
              (str key))
            (if (keyword? value) (name value) value)))
    result))

(defn db [records]
  (.db_with datasource (.empty_db datasource)
            (to-array (map record->datascript records))))
