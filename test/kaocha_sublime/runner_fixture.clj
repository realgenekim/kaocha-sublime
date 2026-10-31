(ns kaocha-sublime.runner-fixture
  "A dependency-free namespace for runner-test: check! reloads it (and only it) when
  a test names one of its vars. Not a -test namespace, so no suite discovers it."
  (:require [clojure.test :refer [deftest is]]))

(def not-a-test 1)

(deftest present (is true))

(deftest ^:perf timed (is true))
