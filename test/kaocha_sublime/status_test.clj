(ns kaocha-sublime.status-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kaocha-sublime.reap :as reap]
            [kaocha-sublime.receipt-test :refer [temp-dir]])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files)
           (java.nio.file.attribute FileTime)
           (java.time Instant)))

(defn status [dir & args]
  (let [r (apply shell/sh "bin/kaocha-status" "--legacy" "--dir" dir args)]
    (is (= 0 (:exit r)) (:err r))
    (is (= "" (:err r)))
    (is (= 1 (count (str/split-lines (:out r)))))
    (str/trim-newline (:out r))))

(defn put! [dir file data]
  (let [f (io/file dir file)] (spit f (pr-str data)) (str f)))

(def base-receipt
  {:run-id "r1" :started "2026-09-08T10:00:00Z" :finished "2026-09-08T10:00:10Z"
   :trigger {:files [] :at "2026-09-08T10:00:00Z"} :auto-followup? false
   :totals {:tests 7 :pass 9 :fail 1 :error 2}
   :affected {:selected 2 :total 3 :closure ['app.a 'app.b]}})

(defn publish! [dir r]
  (let [path (put! dir (str (:run-id r) ".edn") r)]
    (put! dir "LATEST" {:path path :run-id (:run-id r) :finished (:finished r)})
    path))

;; INTENT-TEST: STATUS-POINTER-001
(deftest pointer-is-the-only-verdict-entrance
  (let [dir (temp-dir)]
    (is (= (str "KAOCHA-STATUS NONE dir=" dir) (status dir)))
    (put! dir "unpointed.edn" base-receipt)
    (is (= (str "KAOCHA-STATUS NONE dir=" dir " reason=no-latest-pointer") (status dir)))
    (let [path (publish! dir base-receipt)]
      (put! dir "zzz-newer.edn" (assoc base-receipt :run-id "decoy"))
      (is (= (str "KAOCHA-STATUS DONE id=r1 finished=2026-09-08T10:00:10Z tests=7 pass=9 fail=1 error=2 closure=2/3 covers=unknown receipt=" path)
             (status dir))))
    (put! dir "LATEST" {:path "absent.edn" :run-id "absent" :finished "2026-09-08T11:00:00Z"})
    (is (str/starts-with? (status dir) "KAOCHA-STATUS NONE "))))

;; INTENT-TEST: STATUS-IDENTITY-002
(deftest save-identity-state-space
  (doseq [running? [false true]
          [stamp stale?] [["2026-09-08T09:59:59Z" false]
                           ["2026-09-08T10:00:00Z" false]
                           ["2026-09-08T10:00:01Z" true]
                           ["2026-09-08T10:00:11Z" true]]]
    (let [dir (temp-dir) save (io/file dir "save.clj")]
      (publish! dir base-receipt)
      (spit save "; save")
      (Files/setLastModifiedTime (.toPath save) (FileTime/from (Instant/parse stamp)))
      (when running? (put! dir "r2.edn.tmp" {:run-id "r2" :started "2026-09-08T10:00:00Z" :status :running}))
      (doseq [since [stamp (str save)]]
        (is (str/starts-with? (status dir "--since" since)
                              (str "KAOCHA-STATUS " (cond stale? "STALE" running? "RUNNING" :else "DONE")))
            (str [running? stamp since]))))))

(deftest automatic-followup-keeps-save-identity
  (let [dir (temp-dir) parent (publish! dir base-receipt)
        followup (assoc base-receipt :run-id "r2" :started "2026-09-08T10:00:11Z"
                        :finished "2026-09-08T10:00:12Z" :auto-followup? true :followup-of parent)]
    (publish! dir followup)
    (is (str/includes? (status dir) "DONE id=r2 "))
    (is (str/includes? (status dir "--since" "2026-09-08T09:59:59Z") "DONE id=r1 "))
    (is (str/includes? (status dir "--since" "2026-09-08T10:00:01Z") "STALE "))
    (publish! dir (assoc followup :trigger {:files ["new-save.clj"] :at "2026-09-08T10:00:11Z"}))
    (is (str/includes? (status dir "--since" "2026-09-08T10:00:11Z") "DONE id=r2 "))))

;; INTENT-TEST: STATUS-CLOSURE-005
(deftest closure-subset-state-space
  (let [dir (temp-dir)]
    (publish! dir base-receipt)
    (doseq [[ns-set answer] [["app.a" "yes"] ["app.b,app.a" "yes"]
                             ["app.a,outside" "no"] ["outside" "no"]]]
      (is (str/includes? (status dir "--ns" ns-set) (str " covers=" answer " "))))
    (publish! dir (dissoc base-receipt :affected))
    (is (str/includes? (status dir "--ns" "app.a") " covers=unknown "))))

;; INTENT-TEST: STATUS-CRASH-004
(deftest reaped-run-never-resurrects-old-green
  (let [dir (temp-dir)]
    (publish! dir base-receipt)
    (put! dir "dead.edn.tmp" {:run-id "dead" :started "2026-09-08T11:00:00Z"
                             :status :running :jvm-pid 999999999})
    (is (str/includes? (status dir) "RUNNING "))
    (let [r (shell/sh "bin/kaocha-reap" "--dir" dir)] (is (= 0 (:exit r)) (:err r)))
    (is (str/starts-with? (status dir) "KAOCHA-STATUS NONE "))
    (is (not (.exists (io/file dir "dead.edn.tmp"))))
    (publish! dir (assoc base-receipt :run-id "recovered" :started "2026-09-08T12:00:00Z"
                        :finished "2026-09-08T12:00:01Z"))
    (is (str/includes? (status dir) "DONE id=recovered "))))

(deftest reaper-preserves-live-and-unknown-owners
  (let [dir (temp-dir) process (ProcessHandle/current)
        r {:run-id "live" :started "2026-09-08T11:00:00Z" :status :running
           :jvm-pid (.pid process) :process-start (str (.get (.startInstant (.info process))))}]
    (put! dir "live.edn.tmp" r)
    (is (= 0 (reap/reap! dir)))
    (is (str/includes? (status dir) "RUNNING "))
    ;; A different process start means this PID was recycled. Reaping that old
    ;; reservation must not signal the current process (this test's JVM).
    (put! dir "live.edn.tmp" (assoc r :process-start "2000-01-01T00:00:00Z"))
    (is (= 1 (reap/reap! dir)))
    (is (.isAlive process))
    (put! dir "unknown.edn.tmp" (dissoc r :jvm-pid :process-start))
    (is (= 0 (reap/reap! dir)))
    (is (str/includes? (status dir) "RUNNING "))))

(deftest unusual-paths-still-produce-one-physical-line
  (let [dir (temp-dir) save (io/file dir "space % line\nfile.clj")]
    (publish! dir base-receipt)
    (spit save "; edited")
    (Files/setLastModifiedTime (.toPath save) (FileTime/from (Instant/parse "2026-09-08T11:00:00Z")))
    (is (= (str "KAOCHA-STATUS STALE latest=2026-09-08T10:00:10Z newer_edit="
                dir "/space%20%25%20line%0Afile.clj@2026-09-08T11:00:00Z")
           (status dir "--since" (str save))))))
