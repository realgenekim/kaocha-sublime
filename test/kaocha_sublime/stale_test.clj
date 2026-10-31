(ns kaocha-sublime.stale-test
  "The stale-image checks (LOOP-STALE-REFER-011, LOOP-STALE-REPAIR-012) against transient
  fixture namespaces under test/ (deleted in finally, so the warm check's source
  fingerprint is unchanged). Each check! names an absent var, so it stops at the focus
  refusal after a complete reload and stale check: no nested Kaocha run."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kaocha-sublime.runner :as runner]
            [kaocha-sublime.stale :as stale]))

(def ^:private files
  {:home (io/file "test/kaocha_sublime/stale_home.clj")
   :core (io/file "test/kaocha_sublime/stale_core.clj")
   :outsider (io/file "test/kaocha_sublime/stale_outsider.clj")})

(defn- write! [k text] (spit (files k) text))

(defn- cleanup! []
  (doseq [f (vals files)] (.delete ^java.io.File f))
  (doseq [n '[kaocha-sublime.stale-outsider kaocha-sublime.stale-core kaocha-sublime.stale-home]]
    (when (find-ns n) (remove-ns n))
    (dosync (commute @#'clojure.core/*loaded-libs* disj n))))

(defn- call [sym] ((ns-resolve (symbol (namespace sym)) (symbol (name sym)))))

(defn- check [ns-name & [opts]]
  (let [err (java.io.StringWriter.)
        r (binding [*err* err] (runner/check! [(str ns-name "/absent")] (or opts {})))]
    (assoc r ::stderr (str err))))

(def ^:private home-v1 "(ns kaocha-sublime.stale-home)\n(defn other [] 1)\n")
(def ^:private core-v1
  "(ns kaocha-sublime.stale-core (:require [kaocha-sublime.stale-home]))\n(defn render [] :core-old)\n(defn draw [] (render))\n")
;; The move (clj-surgeon in the field): render now lives in stale-home, stale-core refers it.
(def ^:private home-v2 "(ns kaocha-sublime.stale-home)\n(defn other [] 1)\n(defn render [] :home)\n")
(def ^:private core-v2
  "(ns kaocha-sublime.stale-core (:require [kaocha-sublime.stale-home :refer [render]]))\n(defn draw [] (render))\n")

(defn- move! []
  (write! :home home-v1)
  (write! :core core-v1)
  (is (= "focus" (:stage (check 'kaocha-sublime.stale-core))) "v1 loads clean")
  (is (= :core-old (call 'kaocha-sublime.stale-core/draw)))
  (write! :home home-v2)
  (write! :core core-v2))

;; INTENT-TEST: LOOP-STALE-REFER-011
(deftest a-refused-refer-after-a-move-is-reported-and-never-a-pass
  (try
    (move!)
    (let [r (check 'kaocha-sublime.stale-core {:repair-stale false})]
      (is (= "fail" (:status r)) "never a pass")
      (is (= "stale-image" (:stage r)))
      (is (true? (:jvm-usable r)) "the image is wrong, not broken: the JVM stays warm")
      (is (= :core-old (call 'kaocha-sublime.stale-core/draw))
          "the field case: the warm JVM still runs the old body, which a pass would have blessed")
      (is (= :core-old (with-redefs-fn {(resolve 'kaocha-sublime.stale-home/render) (fn [] :stub)}
                         #(call 'kaocha-sublime.stale-core/draw)))
          "and with-redefs on the new var never reaches it")
      (let [[refer :as refers] (get-in r [:stale :refers])]
        (is (= 1 (count refers)))
        (is (= {:ns "kaocha-sublime.stale-core" :sym "render" :class "refused"
                :lib "kaocha-sublime.stale-home" :home "render"
                :expected "#'kaocha-sublime.stale-home/render"
                :defined-at "test/kaocha_sublime/stale_core.clj:2"}
               (dissoc refer :clojure-said)))
        (is (str/starts-with? (:clojure-said refer)
                              "REJECTED: attempt to replace interned var #'kaocha-sublime.stale-core/render with #'kaocha-sublime.stale-home/render")
            "Clojure's own sentence is attached to the entry"))
      (is (= [{:kind "stale" :test "kaocha-sublime.stale-core/render" :at "test/kaocha_sublime/stale_core.clj:1"}]
             (mapv #(select-keys % [:kind :test :at]) (:failures r)))
          "one failures entry per stale mapping, at the ns form")
      (is (= (str "the :refer of render from kaocha-sublime.stale-home did not take: kaocha-sublime.stale-core interned"
                  " render itself (Clojure REJECTED the refer), so its code still calls the old definition (was at"
                  " test/kaocha_sublime/stale_core.clj:2). Repair: check again (the old definition is now unmapped),"
                  " test-focus --repair-stale, or the cold gate.")
             (:message (first (:failures r)))))
      (is (some #(= {:ns "kaocha-sublime.stale-core" :var "render" :at "test/kaocha_sublime/stale_core.clj:2" :unmapped true} %)
                (get-in r [:stale :defs]))
          "the stale definition is listed with its old location")
      (is (number? (get-in r [:stale :check-ms])) "the cost is a receipt fact")
      (is (str/includes? (::stderr r) "REJECTED: attempt to replace interned var")
          "the REJECTED line still reaches stderr")
      (let [again (check 'kaocha-sublime.stale-core)]
        (is (= "focus" (:stage again))
            "the reload removed the old definition, so the next check's refer takes: repaired, legitimately")
        (is (= :home (call 'kaocha-sublime.stale-core/draw)))))
    (finally (cleanup!))))

;; INTENT-TEST: LOOP-STALE-REPAIR-012
(deftest repair-unmaps-reloads-and-passes-only-when-the-recheck-is-clean
  (try
    (testing "a refused refer inside the closure is repaired in the same JVM"
      (move!)
      (let [r (check 'kaocha-sublime.stale-core {:repair-stale true})]
        (is (= "focus" (:stage r)) "the re-check was clean, so the check went on (to the absent-var refusal)")
        (is (= [{:ns "kaocha-sublime.stale-core" :var "render" :now "#'kaocha-sublime.stale-home/render"}]
               (mapv #(select-keys % [:ns :var :now]) (get-in r [:stale :repaired]))))
        (is (empty? (get-in r [:stale :refers])))
        (is (= :home (call 'kaocha-sublime.stale-core/draw)) "the code now calls the moved function")
        (is (= :stub (with-redefs-fn {(resolve 'kaocha-sublime.stale-home/render) (fn [] :stub)}
                       #(call 'kaocha-sublime.stale-core/draw)))
            "and with-redefs reaches it")))
    (testing "a referrer outside the closure cannot be repaired by this check: still fail"
      (write! :home "(ns kaocha-sublime.stale-home)\n(defn other [] 1)\n(defn render [] :home)\n(defn helper [] 2)\n")
      (write! :outsider "(ns kaocha-sublime.stale-outsider (:require [kaocha-sublime.stale-home :refer [helper]]))\n(defn use-it [] (helper))\n")
      (is (= "focus" (:stage (check 'kaocha-sublime.stale-outsider))))
      (write! :home home-v2)                ; helper deleted
      (let [r (check 'kaocha-sublime.stale-home {:repair-stale true})]
        (is (= "fail" (:status r)))
        (is (= "stale-image" (:stage r)))
        (is (nil? (get-in r [:stale :repaired])) "nothing inside the closure to repair")
        (is (= [{:ns "kaocha-sublime.stale-outsider" :sym "helper" :refers "#'kaocha-sublime.stale-home/helper"
                 :home "kaocha-sublime.stale-home" :in-closure? false}]
               (get-in r [:stale :referrers])))
        (is (str/includes? (:message (first (:failures r))) "name kaocha-sublime.stale-outsider in the check")))
      (testing "and naming it reloads it, which fails exactly as a cold JVM does"
        (let [r (check 'kaocha-sublime.stale-outsider)]
          (is (= "reload" (:stage r)))
          (is (str/includes? (get-in r [:reload-failure :message]) "helper does not exist")))))
    (finally (cleanup!))))

;; INTENT-TEST: LOOP-STALE-REFER-011
(deftest a-referrer-outside-the-closure-holding-a-stale-var-is-named-with-in-closure-false
  (try
    (write! :home "(ns kaocha-sublime.stale-home)\n(defn helper [] 2)\n")
    (write! :outsider "(ns kaocha-sublime.stale-outsider (:require [kaocha-sublime.stale-home :refer [helper]]))\n(defn use-it [] (helper))\n")
    (check 'kaocha-sublime.stale-outsider)
    (write! :home home-v1)
    (let [r (check 'kaocha-sublime.stale-home)]
      (is (= "stale-image" (:stage r)))
      (is (= [false] (mapv :in-closure? (get-in r [:stale :referrers]))))
      (is (= 2 (call 'kaocha-sublime.stale-outsider/use-it)) "the old body is still what the outsider runs"))
    (finally (cleanup!))))

;; INTENT-TEST: LOOP-STALE-REFER-011
(deftest an-unreferenced-stale-definition-warns-and-keeps-the-verdict
  (try
    (write! :home "(ns kaocha-sublime.stale-home)\n(defn other [] 1)\n(defn unused [] 3)\n")
    (check 'kaocha-sublime.stale-home)
    (write! :home home-v1)
    (let [r (check 'kaocha-sublime.stale-home)]
      (is (= "focus" (:stage r)) "nobody maps it: listed, verdict unchanged")
      (is (= [{:ns "kaocha-sublime.stale-home" :var "unused" :at "test/kaocha_sublime/stale_home.clj:3" :unmapped true}]
             (get-in r [:stale :defs]))))
    (finally (cleanup!))))

;; INTENT-TEST: LOOP-STALE-REFER-011
(deftest declared-refers-cover-refer-all-use-rename-and-prefix-lists
  (is (= [{:lib 'a.b :local 'x :home 'x } {:lib 'a.b :local 'z :home 'y }
          {:lib 'a.c :all true :exclude #{} :rename {} }
          {:lib 'p.q :local 'u :home 'u }
          {:lib 'u.v :all true :exclude #{'w} :rename {} }
          {:lib 'u.o :local 'k :home 'k }]
         (map #(dissoc % :line)
              (stale/declared-refers
               '(ns n (:require [a.b :as b :refer [x y] :rename {y z}] [a.c :refer :all] a.d
                           [p [q :refer [u]] [r]] :reload)
               (:use [u.v :exclude [w]] [u.o :only [k]])
               (:import (java.io File))))))))

;; INTENT-TEST: LOOP-STALE-REFER-011
(deftest a-local-definition-the-file-still-has-is-not-a-stale-refer
  ;; Cold: refer, then the defn replaces it (WARNING). Warm: REJECTED, then the defn
  ;; redefines the local var. Same end state: not stale.
  (try
    (write! :home home-v2)
    (write! :core "(ns kaocha-sublime.stale-core (:require [kaocha-sublime.stale-home :refer [render]]))\n(defn render [] :local)\n")
    (binding [*err* (java.io.StringWriter.)] (require 'kaocha-sublime.stale-home 'kaocha-sublime.stale-core))
    (let [r (check 'kaocha-sublime.stale-core)]
      (is (= "focus" (:stage r)))
      (is (empty? (get-in r [:stale :refers]))))
    (finally (cleanup!))))

;; INTENT-TEST: LOOP-STALE-REFER-011
(deftest the-rejected-stderr-line-is-captured-attached-and-still-forwarded
  (let [out (java.io.StringWriter.) buffer (java.io.StringWriter.)
        line "REJECTED: attempt to replace interned var #'a.core/f with #'a.render/f in a.core, you must ns-unmap first"]
    (binding [*err* (stale/tee-writer out buffer)]
      (.println (clojure.lang.RT/errPrintWriter) line)
      (.flush ^java.io.Writer *err*))
    (is (= (str line \newline) (str out) (str/replace (str buffer) "\r" "")) "forwarded and kept")
    (is (= {:rejected [{:ns "a.core" :kept "a.core/f" :wanted "a.render/f" :line line}] :warnings []}
           (stale/reload-lines (str buffer))))))
