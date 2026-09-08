(ns sample.core-test
  (:require [clojure.test :refer [deftest is]] [sample.core :as core]))
(deftest passing (println "owned-by-passing") (is (= 42 core/value)))
(deftest failing (println "owned-by-failing") (is (= 1 2) "intentional failure"))
(deftest erroring (throw (ex-info "intentional error" {:reason :witness})))
(deftest ^:kaocha/pending pending (is false))
