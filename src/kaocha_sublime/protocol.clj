(ns kaocha-sublime.protocol
  "Agent grammar v1: ASCII JSON and cooperative, non-printing evidence previews.
  Shared by the runner and the Babashka status entrance."
  (:require [clojure.string :as str]
            [clojure.data :as data]))

(defn- escape-char [c]
  (case c
    \" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"
    (let [n (int c)] (if (<= 32 n 126) (str c) (format "\\u%04x" n)))))

(defn encoded-within? [s cap]
  (and (string? s) (<= (+ 2 (count s)) cap)
       (loop [i 0 size 2]
         (cond (> size cap) false
               (= i (count s)) true
               :else (let [c (.charAt ^String s i) n (int c)]
                       (recur (inc i) (+ size (cond (contains? #{\" \\ \newline \return \tab} c) 2
                                                  (<= 32 n 126) 1 :else 6))))))))

(defn json [x]
  (cond
    (nil? x) "null"
    (true? x) "true"
    (false? x) "false"
    (string? x) (str "\"" (apply str (map escape-char x)) "\"")
    (keyword? x) (json (name x))
    (integer? x) (str x)
    (map? x) (str "{" (str/join "," (map (fn [[k v]] (str (json (name k)) ":" (json v))) x)) "}")
    (vector? x) (str "[" (str/join "," (map json x)) "]")
    :else (throw (ex-info "Unsupported protocol data" {:reason :protocol-error}))))

(defn- scalar? [x]
  (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x)
      (contains? #{Long Integer Short Byte Double Float java.math.BigInteger
                   java.math.BigDecimal clojure.lang.BigInt clojure.lang.Ratio} (class x))))

(defn- safe-map? [x]
  (contains? #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap} (class x)))

(defn- safe-sequential? [x]
  (contains? #{clojure.lang.PersistentVector clojure.lang.PersistentList
               clojure.lang.PersistentList$EmptyList} (class x)))

(defn- large-number? [x]
  (cond
    (instance? java.math.BigInteger x) (> (.bitLength ^java.math.BigInteger x) 512)
    (instance? clojure.lang.BigInt x) (> (.bitLength (.toBigInteger ^clojure.lang.BigInt x)) 512)
    (instance? java.math.BigDecimal x) (or (> (.precision ^java.math.BigDecimal x) 128)
                                          (> (Math/abs (long (.scale ^java.math.BigDecimal x))) 1024))
    (ratio? x) (or (large-number? (numerator x)) (large-number? (denominator x)))
    :else false))

;; INTENT: AGENT-ENCODER-009
(defn preview
  "Render without print-method, IPrintWithWriter, or lazy realization. The cap
  includes JSON quotes and escaping. Node/depth/time limits are cooperative;
  unsafe objects are opaque, never handed to arbitrary print implementations."
  [value cap]
  (let [out (StringBuilder.) used (volatile! 2) nodes (volatile! 0)
        cut (volatile! false) unavailable (volatile! false)
        deadline (+ (System/nanoTime) 10000000)
        append! (fn [s]
                  (loop [i 0]
                    (when (< i (count s))
                      (let [c (.charAt ^String s i) n (count (escape-char c))]
                        (if (> (+ @used n) cap)
                          (vreset! cut true)
                          (do (.append out c) (vswap! used + n) (recur (inc i))))))))]
    (letfn [(walk [x depth]
              (vswap! nodes inc)
              (cond
                (or @cut (> @nodes 128) (> depth 6) (> (System/nanoTime) deadline))
                (vreset! cut true)
                (string? x) (do (append! "\"") (append! x) (append! "\""))
                (keyword? x) (do (append! ":")
                                 (when-let [n (namespace x)] (append! n) (append! "/"))
                                 (append! (name x)))
                (symbol? x) (do (when-let [n (namespace x)] (append! n) (append! "/"))
                                (append! (name x)))
                (large-number? x)
                (do (vreset! cut true) (append! "<large-number>"))
                (scalar? x) (append! (if (nil? x) "nil" (str x)))
                (safe-map? x)
                (do (append! "{")
                    (loop [entries (seq x) i 0]
                      (when (and entries (not @cut))
                        (when (pos? i) (append! ", "))
                        (walk (key (first entries)) (inc depth)) (append! " ")
                        (walk (val (first entries)) (inc depth))
                        (recur (next entries) (inc i))))
                    (append! "}"))
                (safe-sequential? x)
                (do (append! (if (vector? x) "[" "("))
                    (loop [xs (seq x) i 0]
                      (when (and xs (not @cut))
                        (when (pos? i) (append! " "))
                        (walk (first xs) (inc depth))
                        (recur (next xs) (inc i))))
                    (append! (if (vector? x) "]" ")")))
                :else (do (vreset! unavailable true)
                          (append! (str "<opaque:" (.getName (class x)) ">")))))]
      (walk value 0)
      {:text (str out) :truncated? @cut :unavailable? @unavailable})))

(defn text-preview [s cap]
  ;; A text field has no EDN string quotes inside its JSON quotes.
  (let [p (preview s (- cap 4))]
    (assoc p :text (when (string? s)
                     (let [t (:text p)]
                       (cond-> (subs t (min 1 (count t)))
                         (and (not (:truncated? p)) (> (count t) 1))
                         (subs 0 (- (count t) 2))))))))

(defn- finite-data [value]
  (let [nodes (volatile! 0) deadline (+ (System/nanoTime) 10000000)]
    (letfn [(walk [x depth]
              (vswap! nodes inc)
              (when (or (> @nodes 128) (> depth 6) (> (System/nanoTime) deadline))
                (throw (ex-info "Diff input budget" {})))
              (cond
                (scalar? x) (if (or (large-number? x)
                                    (and (string? x) (> (count x) 256))
                                    (and (or (symbol? x) (keyword? x))
                                         (> (+ (count (name x)) (count (namespace x))) 256)))
                              (throw (ex-info "Diff scalar budget" {})) x)
                (safe-map? x)
                (do (when (> (count x) 128) (throw (ex-info "Diff map budget" {})))
                    (into (sorted-map-by #(compare (str (type %1) ":" %1) (str (type %2) ":" %2)))
                          (map (fn [[k v]]
                                 (when-not (scalar? k) (throw (ex-info "Unsafe diff key" {})))
                                 [(walk k (inc depth)) (walk v (inc depth))])) x))
                (safe-sequential? x)
                (do (when (> (count x) 128) (throw (ex-info "Diff sequence budget" {})))
                    (mapv #(walk % (inc depth)) x))
                :else (throw (ex-info "Opaque diff input" {}))))]
      (try {:value (walk value 0)} (catch Exception _ nil)))))

(defn difference-preview [expected actual]
  (let [left (finite-data expected) right (finite-data actual)]
    (when (and left right)
      ;; clojure.data owns the difference calculation. This only selects up to
      ;; three paths from its finite result for the inline display.
      (let [expected (:value left) actual (:value right)
            [a b _] (data/diff expected actual)
            paths (letfn [(paths [a b path]
                            (if (and (or (map? a) (nil? a)) (or (map? b) (nil? b))
                                     (or (map? a) (map? b)))
                              (mapcat (fn [k] (paths (get a k) (get b k) (conj path k)))
                                      (sort-by #(str (type %) ":" %)
                                               (distinct (concat (keys a) (keys b)))))
                              [path]))]
                    (take 4 (paths a b [])))
            entries (mapv (fn [p] {:path p :expected (get-in expected p :missing)
                                         :actual (get-in actual p :missing)}) (take 3 paths))
            p (preview entries 512)]
        (cond-> p (> (count paths) 3) (assoc :truncated? true))))))

(def common-keys #{:v :id :mode :candidate})
(def record-keys
  {:start (into common-keys #{:session :trigger :trigger_omitted :closure :active})
   :fail (into common-keys #{:event :kind :test :file :line :context :expr :expected :actual :diff :truncated :unavailable})
   :done (into common-keys #{:outcome :tests :assertions :pass :fail :error :pending :skipped :closure
                             :coverage :wall_ms :auto_followup :parent :shown :omitted :receipt :sha256})
   :status #{:v :state :token :id :identity :elapsed_ms :phase :reason :active :receipt}})
(def prefixes {:start "RUN-START " :fail "FAIL " :done "RUN-DONE " :status "STATUS "})

(defn status [state reason & [fields]]
  (merge {:v 1 :state state :token nil :id nil :identity :unknown :elapsed_ms nil
          :phase :unknown :reason reason :active nil :receipt nil} fields))

(defn- uint? [x] (and (integer? x) (<= 0 x)))
(defn- nullable? [pred x] (or (nil? x) (pred x)))
(defn- absolute? [x] (and (encoded-within? x 8192) (.isAbsolute (java.io.File. x))))
(defn- closure? [x]
  (nullable? #(and (map? %) (= #{:selected :total} (set (keys %)))
                   (uint? (:selected %)) (uint? (:total %)) (<= (:selected %) (:total %))) x))

(defn valid-record? [kind r]
  (and (= 1 (:v r)) (= (record-keys kind) (set (keys r)))
       (every? #(nullable? (fn [s] (and (string? s) (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,199}" s)))) %)
               (map r [:id :token :parent]))
       (every? #(nullable? (fn [s] (and (string? s) (boolean (re-matches #"[a-f0-9]{64}" s)))) %)
               (map r [:candidate :sha256]))
       (or (= kind :status) (and (string? (:id r)) (contains? #{:watch :probe :gate} (:mode r))))
       (case kind
         :status (and (contains? #{:marked :waiting :running :late :timeout :killed :superseded :uncovered :unavailable :refused} (:state r))
                      (contains? #{:none :save-unobserved :over-p95 :deadline :process-dead :candidate-changed :scope-missing
                                   :no-receipt :identity-unproved :heap-cap :protocol-error} (:reason r))
                      (contains? #{:exact :unknown} (:identity r))
                      (contains? #{:queued :load :run :unknown} (:phase r))
                      (nullable? uint? (:elapsed_ms r))
                      (every? #(nullable? absolute? %) (map r [:active :receipt])))
         :start (and (string? (:session r)) (<= (count (:session r)) 200)
                     (boolean (re-matches #"[A-Za-z0-9._-]+" (:session r)))
                     (vector? (:trigger r)) (<= (count (:trigger r)) 4)
                     (every? #(encoded-within? % 512) (:trigger r)) (<= (count (json (:trigger r))) 512)
                     (uint? (:trigger_omitted r)) (closure? (:closure r)) (absolute? (:active r)))
         :fail (and (uint? (:event r)) (pos? (:event r)) (contains? #{:fail :error :load} (:kind r))
                    (every? #(nullable? (fn [s] (encoded-within? s 2048)) %) (map r [:test :file :context :expr :expected :actual :diff]))
                    (nullable? #(and (integer? %) (pos? %)) (:line r))
                    (every? (fn [[k cap]] (nullable? #(encoded-within? % cap) (get r k)))
                            [[:context 160] [:expr 160] [:expected 256] [:actual 256] [:diff 512]])
                    (every? #(and (vector? %) (= (count %) (count (distinct %)))
                                  (every? #{:context :expr :expected :actual :diff} %))
                            (map r [:truncated :unavailable])))
         :done (and (contains? #{:pass :fail :error :incomplete} (:outcome r))
                    (contains? #{:complete :partial :unknown} (:coverage r))
                    (every? #(nullable? uint? %) (map r [:tests :assertions :pass :fail :error :pending :skipped]))
                    (or (every? nil? (map r [:assertions :pass :fail :error]))
                        (and (every? uint? (map r [:assertions :pass :fail :error]))
                             (= (:assertions r) (+ (:pass r) (:fail r) (:error r)))))
                    (if (= :pass (:outcome r))
                      (and (= :complete (:coverage r)) (= 0 (:fail r) (:error r) (:pending r))) true)
                    (closure? (:closure r)) (uint? (:wall_ms r)) (boolean? (:auto_followup r))
                    (uint? (:shown r)) (<= (:shown r) 20) (uint? (:omitted r))
                    (absolute? (:receipt r)) (string? (:sha256 r)))
         false)))

(defn record-line [kind record]
  (when-not (valid-record? kind record)
    (throw (ex-info "Invalid agent record" {:reason :protocol-error})))
  (let [line (str (prefixes kind) (json record) "\n")]
    (when (> (count line) (if (= kind :fail) 2048 8192))
      (throw (ex-info "Oversize agent record" {:reason :protocol-error})))
    line))

;; INTENT: AGENT-ACCEPT-007
(defn exit-code [{:keys [identity coverage outcome]}]
  (cond (not= identity :exact) 2
        (contains? #{:fail :error} outcome) 1
        (and (= coverage :complete) (= outcome :pass)) 0
        :else 2))

(defn outcome [{:keys [tests fail error pending]} coverage]
  (cond (pos? (or error 0)) :error (pos? (or fail 0)) :fail
        (or (not= coverage :complete) (pos? (or pending 0)) (zero? (or tests 0))) :incomplete
        :else :pass))

(defn done-record [receipt path digest]
  (let [totals (:totals receipt) coverage (:coverage receipt)]
    (merge {:v 1 :id (:run-id receipt) :mode (:mode receipt) :candidate (:candidate receipt)
            :outcome (outcome totals coverage) :closure (:closure receipt) :coverage coverage
            :wall_ms (long (:wall-ms receipt)) :auto_followup (boolean (:auto-followup? receipt))
            :parent (:parent receipt) :shown (get-in receipt [:failure-budget :shown] 0)
            :omitted (get-in receipt [:failure-budget :omitted] 0) :receipt (str path) :sha256 digest}
           (select-keys (merge {:tests nil :assertions nil :pass nil :fail nil :error nil :pending nil :skipped nil}
                               totals) [:tests :assertions :pass :fail :error :pending :skipped]))))

