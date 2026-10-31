(ns leak-sample.drained-test
  "The fix for the field case: an :each fixture that drains the background work, inside
  the leak detector's window."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(def pending (atom 0))

(defn drain [t]
  (t)
  (while (pos? @pending) (Thread/sleep 5)))

(use-fixtures :each drain)

(deftest starts-and-drains
  (swap! pending inc)
  (future (try (Thread/sleep 100) (finally (swap! pending dec))))
  (is true))
