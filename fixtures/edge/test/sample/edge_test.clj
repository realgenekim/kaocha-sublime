(ns sample.edge-test (:require [clojure.test :refer [deftest is]]))
(deftest no-assertions nil)
(deftest object-message (is (= 1 2) (Object.)))
