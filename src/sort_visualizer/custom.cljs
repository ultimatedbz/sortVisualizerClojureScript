(ns sort-visualizer.custom
  "A deliberately small, instrumented API for running custom sorting code."
  (:require [clojure.string :as str]
            [sci.core :as sci]))

(def max-operations 20000)
(def max-frames 20000)

(def ^:private allowed-symbols
  ;; Keep JavaScript interop and reflection out of the DSL. An allowlist also
  ;; prevents indirect access through aget, type, resolve, or namespace maps.
  '[size value compare! swap! write! snapshot!
    def defn defn- fn fn* let let* letfn letfn* loop loop* recur do if if-not
    when when-not if-let when-let if-some when-some cond case case* and or not
    doseq dotimes for while comment quote -> ->> as-> cond-> cond->> some-> some->>
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
    symbol? fn? ifn? str pr-str name namespace keyword symbol
    atom deref reset! volatile! vreset! vswap!
    chunked-seq? chunk-first chunk-rest chunk-next chunk-cons chunk-append
    chunk-buffer chunk chunked-next chunked-rest unchecked-inc unchecked-inc-int])

(def default-code
  ";; Find the smallest remaining value, then put it in place.\n(defn sort! []\n  (doseq [i (range (size))]\n    (let [smallest\n          (reduce (fn [best j]\n                    (if (neg? (compare! j best)) j best))\n                  i\n                  (range (inc i) (size)))]\n      (when (not= i smallest)\n        (swap! i smallest)))))\n\n(sort!)")

(defn- finite-number? [x]
  (and (number? x) (js/Number.isFinite x)))

(defn- error-message [error]
  (or (ex-message error) (.-message error) "The custom algorithm could not run."))

(defn generate-trace
  "Evaluate a fresh SCI context and return immutable frames.

  Only the instrumented functions below can inspect or change the working array.
  No JavaScript globals or browser APIs are supplied to SCI. Call this in a Web
  Worker: the UI also imposes a timeout for code that never calls our API."
  [code values]
  (let [frames (atom [])
        limited? (atom false)]
    (try
      (when-not (and (string? code) (not (str/blank? code)))
        (throw (ex-info "Add a ClojureScript sorting function and call it." {})))
      (when-not (and (sequential? values) (every? finite-number? values))
        (throw (ex-info "The array must contain only finite numbers." {})))
      (let [original (vec values)
            length (count original)
            operations (atom 0)
            state (atom {:values original
                         :active []
                         :sorted []
                         :comparisons 0
                         :writes 0
                         :note "Custom algorithm ready."})
            stop! (fn []
                    (reset! limited? true)
                    (throw (ex-info "Stopped at the 20,000-operation limit. Try a smaller array or a more efficient algorithm."
                                    {:type :operation-limit})))
            tick! (fn []
                    (when (> (swap! operations inc) max-operations)
                      (stop!)))
            check-index! (fn [i]
                           (when-not (and (integer? i) (<= 0 i) (< i length))
                             (throw (ex-info (str "Index " (pr-str i) " is outside the array (size " length ").")
                                             {:index i})))
                           i)
            record! (fn [active note]
                      ;; Leave space for the final verified frame.
                      (when (>= (count @frames) (dec max-frames))
                        (stop!))
                      (swap! state assoc :active (vec active) :note note)
                      (swap! frames conj @state)
                      nil)
            bindings {'size (fn [] (tick!) length)
                      'value (fn [i]
                               (tick!)
                               (nth (:values @state) (check-index! i)))
                      'compare! (fn [i j]
                                  (tick!)
                                  (check-index! i)
                                  (check-index! j)
                                  (swap! state update :comparisons inc)
                                  (record! [i j] (str "Compare positions " i " and " j "."))
                                  (compare (nth (:values @state) i)
                                           (nth (:values @state) j)))
                      'swap! (fn [i j]
                               (tick!)
                               (check-index! i)
                               (check-index! j)
                               (let [current (:values @state)
                                     a (nth current i)
                                     b (nth current j)]
                                 (swap! state #(-> %
                                                   (assoc :values (assoc current i b j a))
                                                   (update :writes + (if (= i j) 0 2))))
                                 (record! [i j] (str "Swap positions " i " and " j "."))))
                      'write! (fn [i value]
                                (tick!)
                                (check-index! i)
                                (when-not (finite-number? value)
                                  (throw (ex-info "write! needs a finite number." {})))
                                (swap! state #(-> %
                                                  (assoc-in [:values i] value)
                                                  (update :writes inc)))
                                (record! [i] (str "Write " value " at position " i ".")))
                      'snapshot! (fn [indices note]
                                   (tick!)
                                   (when-not (sequential? indices)
                                     (throw (ex-info "snapshot! needs a vector or list of indices." {})))
                                   ;; Realize only a bounded prefix, even for lazy input.
                                   (let [active (vec (take (inc length) indices))]
                                     (when (> (count active) length)
                                       (throw (ex-info "snapshot! has more indices than the array." {})))
                                     (doseq [i active] (check-index! i))
                                     (when-not (string? note)
                                       (throw (ex-info "snapshot! needs a string note." {})))
                                     (record! active (subs note 0 (min 180 (count note))))))}]
        (swap! frames conj @state)
        (sci/eval-string code {:namespaces {'user bindings}
                               :classes {}
                               :allow allowed-symbols})
        (let [result (:values @state)]
          (cond
            @limited?
            {:frames @frames
             :status :limited
             :message "Stopped at the 20,000-operation limit. Try a smaller array or a more efficient algorithm."}

            (not= (frequencies original) (frequencies result))
            {:frames @frames
             :status :invalid
             :message "The algorithm changed the array's values. A sort must preserve every original value."}

            (not= result (vec (sort result)))
            {:frames @frames
             :status :invalid
             :message "The function finished, but the array is not sorted in ascending order."}

            :else
            (do
              (swap! state assoc :active [] :sorted (vec (range length))
                     :note "Sorted! Every original value is preserved.")
              (swap! frames conj @state)
              {:frames @frames
               :status :complete
               :message "Custom algorithm completed and verified."}))))
      (catch :default error
        {:frames @frames
         :status (if @limited? :limited :invalid)
         :message (if @limited?
                    "Stopped at the 20,000-operation limit. Try a smaller array or a more efficient algorithm."
                    (error-message error))}))))
