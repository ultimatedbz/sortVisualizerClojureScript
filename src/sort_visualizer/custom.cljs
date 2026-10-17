(ns sort-visualizer.custom
  "A small, immutable state-in/state-out API for custom sorting code."
  (:require [clojure.string :as str]
            [sci.core :as sci]))

(def max-operations 20000)
(def max-frames 20000)

(def ^:private allowed-symbols
  ;; Immutable collection, control-flow and math operations, plus named helpers
  ;; in the fresh interpreter context. No JavaScript or mutable reference API.
  '[size value compare-at swap-at write-at snapshot
    def defn defn- fn fn* let let* letfn letfn* loop loop* recur do if if-not
    when when-not if-let when-let if-some when-some cond case case* and or not
    comment quote -> ->> as-> cond-> cond->> some-> some->>
    = not= < <= > >= compare + - * / inc dec mod rem quot max min abs
    neg? pos? zero? even? odd? number? integer? int long double boolean
    true? false? nil? some? identity constantly complement partial comp juxt
    apply reduce reduce-kv reductions reduced reduced? unreduced
    range repeat repeatedly iterate take take-while take-nth drop drop-while
    map mapv map-indexed mapcat filter filterv remove keep keep-indexed
    every? not-every? some not-any? distinct distinct? dedupe sort sort-by reverse
    count empty? empty not-empty first second last nth nthnext rest next ffirst
    nfirst fnext nnext peek pop conj cons concat into vec vector list list* set
    hash-map array-map sorted-map sorted-set get get-in assoc assoc-in dissoc
    update update-in contains? find keys vals key val select-keys merge merge-with
    zipmap frequencies group-by partition partition-all partition-by split-at
    split-with subvec butlast drop-last take-last interleave interpose
    seq sequence seq? sequential? vector? list? map? set? coll? string? keyword?
    symbol? fn? ifn? str pr-str name namespace keyword symbol])

(def legacy-default-code
  "The previous default, retained only to migrate a saved editor draft."
  ";; Find the smallest remaining value, then put it in place.\n(defn sort! []\n  (doseq [i (range (size))]\n    (let [smallest\n          (reduce (fn [best j]\n                    (if (neg? (compare! j best)) j best))\n                  i\n                  (range (inc i) (size)))]\n      (when (not= i smallest)\n        (swap! i smallest)))))\n\n(sort!)")

(def default-code
  ";; Return a function that accepts and returns immutable state.\n;; Each comparison returns [next-state ordering].\n(fn [state]\n  (reduce\n    (fn [state i]\n      (let [[state smallest]\n            (reduce (fn [[state best] j]\n                      (let [[state order] (compare-at state j best)]\n                        [state (if (neg? order) j best)]))\n                    [state i]\n                    (range (inc i) (size state)))]\n        (if (= i smallest)\n          state\n          (swap-at state i smallest))))\n    state\n    (range (size state))))")

(defn legacy-code?
  "Whether a draft references the retired mutable sorting API."
  [code]
  (and (string? code)
       (boolean (re-find #"\b(?:compare!|swap!|write!|snapshot!|sort!)(?:\s|\)|\])" code))))

(defn- finite-number? [x]
  (and (number? x) (js/Number.isFinite x)))

(defn- natural-number? [x]
  (and (finite-number? x) (integer? x) (<= 0 x)))

(defn- error-message [error]
  (or (ex-message error) (.-message error) "The custom algorithm could not run."))

(def ^:private frame-keys [:values :active :sorted :comparisons :writes :note])

(defn- frame [state]
  (select-keys state frame-keys))

(defn- fail
  ([message] (throw (ex-info message {})))
  ([message state] (throw (ex-info message {:trace-state state}))))

(defn- valid-index? [length index]
  (and (integer? index) (<= 0 index) (< index length)))

(defn- check-index [state index]
  (let [length (count (:values state))]
    (when-not (valid-index? length index)
      (fail (str "Index " (pr-str index) " is outside the array (size " length ").") state)))
  index)

(defn- valid-frame? [length candidate]
  (and (map? candidate)
       (vector? (:values candidate))
       (= length (count (:values candidate)))
       (every? finite-number? (:values candidate))
       (vector? (:active candidate))
       (<= (count (:active candidate)) (max length 2))
       (every? #(valid-index? length %) (:active candidate))
       (vector? (:sorted candidate))
       (<= (count (:sorted candidate)) length)
       (every? #(valid-index? length %) (:sorted candidate))
       (natural-number? (:comparisons candidate))
       (natural-number? (:writes candidate))
       (string? (:note candidate))
       (<= (count (:note candidate)) 180)))

(defn- validate-state
  "Check the outer state without repeatedly traversing its entire history."
  [length state]
  (when-not (and (valid-frame? length state)
                 (natural-number? (:operations state))
                 (<= (:operations state) max-operations)
                 (vector? (:frames state))
                 (pos? (count (:frames state)))
                 (< (count (:frames state)) max-frames))
    (fail "Return a valid immutable state from the sorting function. Preserve its fields and use the supplied state operations."))
  state)

(defn- validate-trace [original initial state]
  (validate-state (count original) state)
  (when-not (and (= (first (:frames state)) (frame initial))
                 (= (last (:frames state)) (frame state))
                 (every? #(valid-frame? (count original) %) (:frames state))
                 (= (:operations state) (dec (count (:frames state)))))
    (fail "The returned trace has invalid frames. Keep the state and its history together using the supplied operations."))
  state)

(defn- record
  "Append a new frame to a new state; previous states remain reusable."
  [state active note transform]
  ;; Reserve one frame for the final, verified result.
  (when (or (>= (:operations state) max-operations)
            (>= (count (:frames state)) (dec max-frames)))
    (throw (ex-info "Stopped at the 20,000-operation/frame limit. Try a smaller array or a more efficient algorithm."
                    {:type :operation-limit :trace-state state})))
  (let [next-state (-> (transform state)
                       (assoc :active active :note note)
                       (update :operations inc))]
    (update next-state :frames conj (frame next-state))))

(defn- api-bindings [length]
  {'size (fn [state]
           (count (:values (validate-state length state))))
   'value (fn [state index]
            (validate-state length state)
            (nth (:values state) (check-index state index)))
   'compare-at (fn [state i j]
                 (validate-state length state)
                 (check-index state i)
                 (check-index state j)
                 (let [order (compare (nth (:values state) i) (nth (:values state) j))]
                   [(record state [i j] (str "Compare positions " i " and " j ".")
                            #(update % :comparisons inc))
                    order]))
   'swap-at (fn [state i j]
              (validate-state length state)
              (check-index state i)
              (check-index state j)
              (let [values (:values state)]
                (record state [i j] (str "Swap positions " i " and " j ".")
                        #(-> %
                             (assoc :values (assoc values i (nth values j) j (nth values i)))
                             (update :writes + (if (= i j) 0 2))))))
   'write-at (fn [state index value]
               (validate-state length state)
               (check-index state index)
               (when-not (finite-number? value)
                 (fail "write-at needs a finite number." state))
               (record state [index] (str "Write " value " at position " index ".")
                       #(-> % (assoc-in [:values index] value) (update :writes inc))))
   'snapshot (fn [state indices note]
               (validate-state length state)
               (when-not (sequential? indices)
                 (fail "snapshot needs a vector or list of indices." state))
               ;; Only realize a bounded prefix, including for infinite input.
               (let [active (vec (take (inc length) indices))]
                 (when (> (count active) length)
                   (fail "snapshot has more indices than the array." state))
                 (when-not (every? #(valid-index? length %) active)
                   (check-index state (first (remove #(valid-index? length %) active))))
                 (when-not (string? note)
                   (fail "snapshot needs a string note." state))
                 (record state active (subs note 0 (min 180 (count note))) identity)))})

(defn- error-data [error]
  ;; SCI may wrap a host exception. Preserve an operation's immutable partial
  ;; trace when available, without holding a mutable reference to the last state.
  (loop [error error depth 0]
    (let [data (ex-data error)]
      (if (or (:trace-state data) (>= depth 8) (nil? (ex-cause error)))
        data
        (recur (ex-cause error) (inc depth))))))

(defn- run-code [code original]
  (let [initial-frame {:values original :active [] :sorted []
                       :comparisons 0 :writes 0 :note "Custom algorithm ready."}
        initial (assoc initial-frame :operations 0 :frames [initial-frame])]
    (try
      (let [sorter (sci/eval-string code {:namespaces {'user (api-bindings (count original))}
                                        :classes {}
                                        :allow allowed-symbols})]
        (when-not (fn? sorter)
          (fail "The code must return a sorting function: (fn [state] ... next-state)."))
        (let [state (validate-trace original initial (sorter initial))
              result (:values state)]
          (cond
            (not= (frequencies original) (frequencies result))
            {:frames (:frames state) :status :invalid
             :message "The algorithm changed the array's values. A sort must preserve every original value."}

            (not= result (vec (sort result)))
            {:frames (:frames state) :status :invalid
             :message "The function finished, but the array is not sorted in ascending order."}

            :else
            {:frames (conj (:frames state)
                           (assoc (frame state) :active [] :sorted (vec (range (count original)))
                                  :note "Sorted! Every original value is preserved."))
             :status :complete
             :message "Custom algorithm completed and verified."})))
      (catch :default error
        (let [data (error-data error)
              partial (:trace-state data)
              ;; Never return malformed frames, even if a program tampered with
              ;; state before passing it into one of the operations.
              frames (try
                       (:frames (validate-trace original initial partial))
                       (catch :default _ [initial-frame]))]
          {:frames frames
           :status (if (= :operation-limit (:type data)) :limited :invalid)
           :message (error-message error)})))))

(defn generate-trace
  "Run a function of immutable state in a fresh SCI context.

  The code must evaluate to (fn [state] ... next-state). The supplied operations
  return new states; compare-at returns [next-state ordering]. State is explicit
  and validated, not a security boundary. Run this in a Web Worker so the UI's
  timeout also bounds computations that do not retain instrumented operations."
  [code values]
  (try
    (when-not (and (string? code) (not (str/blank? code)))
      (fail "Add a ClojureScript sorting function: (fn [state] ... next-state)."))
    (when (legacy-code? code)
      (fail "This draft uses the old mutable API. Return (fn [state] ... next-state), use compare-at/swap-at/write-at/snapshot with state, and carry each returned state forward. Reset the example to see the new API."))
    (when-not (and (sequential? values) (every? finite-number? values))
      (fail "The array must contain only finite numbers."))
    (run-code code (vec values))
    (catch :default error
      {:frames [] :status :invalid :message (error-message error)})))
