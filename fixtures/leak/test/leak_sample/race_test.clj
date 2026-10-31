(ns leak-sample.race-test
  "The pdfreader field case (2026-10-31, RENDER-THREAD-001/002) in miniature: the first
  test waits until its background work has STARTED, not until it has finished, and
  returns; the work lands inside the next test, which fails on code it never touched."
  (:require [clojure.test :refer [deftest is]]))

(def side (atom :clean))

(deftest a-leaker
  (let [called (promise)]
    (future (deliver called true) (Thread/sleep 150) (reset! side :leaked))
    (deref called 1000 nil)
    (is true)))

(deftest b-victim
  (Thread/sleep 300)
  (is (= :clean @side) "the previous image stays up"))
