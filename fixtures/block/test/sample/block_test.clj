(ns sample.block-test (:require [clojure.test :refer [deftest is]]))
(deftest blocks-until-killed
  (spit (System/getenv "BLOCK_READY") (str (.pid (java.lang.ProcessHandle/current))))
  (Thread/sleep 600000)
  (is true))
