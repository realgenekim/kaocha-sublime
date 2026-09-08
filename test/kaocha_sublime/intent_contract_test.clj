(ns kaocha-sublime.intent-contract-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]))

(defn tags [root pattern]
  (into #{} (mapcat #(map second (re-seq pattern (slurp %))))
        (filter #(and (.isFile %) (.endsWith (.getName %) ".clj"))
                (file-seq (io/file root)))))

(deftest intent-links-in-both-directions
  (let [rows (:intents (edn/read-string (slurp "docs/intent/registry.edn")))
        known (set (map (comp name :id) rows))
        active (set (map (comp name :id) (filter #(= :active (:status %)) rows)))
        code (tags "src" #"(?m);;\s*INTENT:\s*([A-Z][A-Z0-9-]*)")
        tests (tags "test" #"(?m);;\s*INTENT-TEST:\s*([A-Z][A-Z0-9-]*)")]
    (is (= (count rows) (count known)) "Stable intent IDs are unique")
    (is (empty? (set/difference active code)) "Every active promise has code")
    (is (empty? (set/difference active tests)) "Every active promise has a test")
    (is (empty? (set/difference (set/union code tests) known)) "No unknown tags")))
