(ns kaocha-sublime.candidate
  "Content identity shared with affected selection and the non-JVM status reader."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files StandardCopyOption)
           (java.security MessageDigest)
           (java.util UUID)))

(defn canonical [f] (.getCanonicalPath (io/file f)))
(defn read-edn [file] (try (edn/read-string (slurp file)) (catch Exception _ nil)))
(defn sha256 [bytes]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bytes))))
(defn file-hash [file]
  (when (.isFile (io/file file))
    (with-open [in (io/input-stream file)]
      (let [md (MessageDigest/getInstance "SHA-256") buf (byte-array 16384)]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))
        (format "%064x" (BigInteger. 1 (.digest md)))))))
(defn data-hash [x]
  (binding [*print-length* nil *print-level* nil *print-meta* false *print-namespace-maps* false]
    (sha256 (.getBytes (pr-str x) "UTF-8"))))

(defn atomic-edn! [file data]
  (io/make-parents file)
  (let [tmp (io/file (str file "." (UUID/randomUUID) ".pending"))]
    (binding [*print-length* nil *print-level* nil *print-meta* false *print-namespace-maps* false]
      (spit tmp (str (pr-str data) "\n")))
    (Files/move (.toPath tmp) (.toPath (io/file file))
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))))

(defn classpath-stamps [paths]
  (into (sorted-map)
        (map (fn [p] (let [f (io/file p)] [p (when (.isFile f) [(.length f) (.lastModified f) (file-hash f)])]))) paths))

(defn files-under [root]
  (let [f (io/file root)]
    (if-not (.exists f) []
      (with-open [paths (Files/walk (.toPath f) (make-array java.nio.file.FileVisitOption 0))]
        (mapv #(.toFile ^java.nio.file.Path %) (iterator-seq (.iterator paths)))))))

(defn change-stamps
  "Linux inode/change-time observation, including directory entry changes.
  Nil means the filesystem cannot attest this boundary; never invent a clock."
  [{:keys [roots snapshot-roots config-files classpath-stamps]}]
  (try
    (let [roots (or snapshot-roots roots)
          files (distinct (concat (mapcat files-under roots)
                                   (map io/file config-files) (map io/file (keys classpath-stamps))) )]
      (into (sorted-map)
            (for [f files :when (.exists ^java.io.File f)
                  :let [attrs (Files/readAttributes (.toPath ^java.io.File f) "unix:ctime,ino"
                                                    (make-array java.nio.file.LinkOption 0))]]
              [(canonical f) [(str (.get attrs "ctime")) (str (.get attrs "ino"))]])))
    (catch Exception _ nil)))

(defn snapshot [{:keys [roots snapshot-roots config-files]}]
  (into (sorted-map)
        (for [f (distinct (concat (mapcat files-under (or snapshot-roots roots)) (map io/file config-files)))
              :when (.isFile ^java.io.File f)]
          [(canonical f) (file-hash f)])))

(defn within? [root path]
  (.startsWith (.toPath (io/file (canonical path))) (.toPath (io/file (canonical root)))))

(defn candidate-id [{:keys [workspace session generation manifest]}]
  (data-hash [workspace session generation (into (sorted-map) manifest)]))

(defn publish-session! [dir session]
  (atomic-edn! (io/file dir "SESSION") session))

;; INTENT: AGENT-CANDIDATE-006
(defn mark! [dir paths required-scope]
  (let [required-scope (case (:kind required-scope)
                         :full {:kind :full}
                         :namespaces (let [nses (:namespaces required-scope)]
                                       (when-not (and (seq nses) (every? #(and (string? %) (not (empty? %))) nses))
                                         (throw (ex-info "Missing required namespaces" {:reason :protocol-error})))
                                       {:kind :namespaces :namespaces (vec (sort (distinct nses)))})
                         (throw (ex-info "Unknown required scope" {:reason :protocol-error})))
        session (read-edn (io/file dir "SESSION"))
        _ (when-let [reason (:refused session)]
            (throw (ex-info "Runner admission refused" {:reason reason})))
        _ (when-not (and (:workspace session) (:session session) (:generation session))
            (throw (ex-info "No runner session" {:reason :identity-unproved})))
        paths (mapv canonical paths)
        paths (vec (distinct (mapcat #(if (.isDirectory (io/file %))
                                      (map canonical (filter (fn [f] (.isFile ^java.io.File f)) (file-seq (io/file %))))
                                      [%]) paths)))
        manifest (snapshot session)
        record (assoc session :schema 1 :manifest manifest :required-scope required-scope
                              :required-paths (into (sorted-map) (map (fn [p] [p (file-hash p)])) paths))
        record (assoc record :candidate (candidate-id record))
        token (data-hash [(:candidate record) required-scope (:required-paths record)])]
    (atomic-edn! (io/file dir "tokens" (str token ".edn")) record)
    token))

;; INTENT: AGENT-IMAGE-012
(defn stable-image? [before after]
  (and (some? (:observed before)) (= (:observed before) (:observed after))
       (= (:manifest before) (:manifest after))
       (= (:file-stamps before) (:file-stamps after))))

(defn changed [before after]
  (set (filter #(not= (get before %) (get after %))
               (set/union (set (keys before)) (set (keys after))))))

;; INTENT: AGENT-DEATH-008
(defn process-state [{:keys [jvm-pid process-start]}]
  (try
    (if-not (and (integer? jvm-pid) (string? process-start)) :unknown
      (let [p (ProcessHandle/of jvm-pid)]
        (if-not (and (.isPresent p) (.isAlive (.get p))) :dead
          (let [start (.startInstant (.info (.get p)))]
            (cond (not (.isPresent start)) :unknown
                  (= process-start (str (.get start))) :alive
                  :else :dead)))))
    (catch Exception _ :unknown)))
