(ns sort-visualizer.algorithms-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [sort-visualizer.algorithms :as algorithms]))

(def deterministic-algorithms (remove #{:bogo} algorithms/algorithm-order))

(defn permutations [xs]
  (if (empty? xs)
    [[]]
    (mapcat (fn [x] (map #(into [x] %) (permutations (remove #{x} xs)))) xs)))

(defn final-values [trace] (:values (peek (:frames trace))))

(deftest sorting-edge-cases
  (doseq [algorithm deterministic-algorithms
          values [[] [7] [2 1] [1 2] [4 4 4 4] [4 1 3 1 2 4]
                  [-5 0 -2 9 -5 3] [0 0 1 0] [9 8 7 6 5 4 3 2 1]]]
    (testing (str (name algorithm) " " values)
      (let [trace (algorithms/generate-trace algorithm values)]
        (is (= :complete (:status trace)))
        (is (= (vec (sort values)) (final-values trace)))
        (is (= values (:values (first (:frames trace)))))
        (is (= (vec (range (count values))) (:sorted (peek (:frames trace)))))))))

(deftest exhaustive-small-permutations
  (doseq [algorithm deterministic-algorithms
          values (permutations [0 1 2 3 4])]
    (is (= [0 1 2 3 4] (final-values (algorithms/generate-trace algorithm values)))
        (str algorithm " must sort " values))))

(deftest arbitrary-length-bitonic-network
  (doseq [n (range 2 40)
          values [(vec (reverse (range n)))
                  (mapv #(mod (* % 17) 13) (range n))]]
    (is (= (vec (sort values)) (final-values (algorithms/generate-trace :bitonic values)))
        (str "Bitonic network length " n))))

(deftest every-algorithm-handles-full-size-arrays
  (doseq [algorithm deterministic-algorithms
          n [17 31 64 127 128]
          values [(mapv #(mod (* % 53) n) (range n))
                  (vec (reverse (range n)))]]
    (let [trace (algorithms/generate-trace algorithm values)]
      (is (= :complete (:status trace)))
      (is (= (vec (sort values)) (final-values trace)) (str algorithm " length " n))
      (is (<= (count (:frames trace)) algorithms/max-frames)))))

(deftest trace-is-replayable-and-counts-are-monotonic
  (doseq [algorithm deterministic-algorithms]
    (let [input [6 2 5 1 4 3]
          trace (algorithms/generate-trace algorithm input)
          frames (:frames trace)]
      (is (= trace (algorithms/generate-trace algorithm input)) (str algorithm " is deterministic"))
      (is (= [6 2 5 1 4 3] input))
      (is (= 0 (:comparisons (first frames))))
      (is (= 0 (:writes (first frames))))
      (doseq [frame frames]
        (is (= (count input) (count (:values frame))))
        (is (vector? (:values frame)))
        (is (string? (:note frame)))
        (is (every? #(and (integer? %) (<= 0 %) (< % (count input)))
                    (concat (:active frame) (:sorted frame))))
        (doseq [index (:sorted frame)]
          (is (= (nth (vec (sort input)) index) (nth (:values frame) index))
              "Marked positions must already contain their final values")))
      (doseq [[before after] (partition 2 1 frames)]
        (is (<= (:comparisons before) (:comparisons after))))
      (doseq [[before after] (partition 2 1 frames)]
        (is (<= (:writes before) (:writes after))))
      ;; Exchanging algorithms preserve the multiset in every replay frame.
      ;; Merge and radix copy buffered values, so intermediate writes may repeat.
      (when-not (#{:merge :radix} algorithm)
        (is (every? #(= (frequencies input) (frequencies (:values %))) frames))))))

(deftest operation-counts-mean-real-operations
  (let [bubble (peek (:frames (algorithms/generate-trace :bubble [3 2 1])))
        selection (peek (:frames (algorithms/generate-trace :selection [3 2 1])))
        radix (peek (:frames (algorithms/generate-trace :radix [3 2 1])))]
    (is (= 3 (:comparisons bubble)))
    (is (= 6 (:writes bubble)))
    (is (= 3 (:comparisons selection)))
    (is (= 2 (:writes selection)))
    (is (= 0 (:comparisons radix)) "Radix distributes by digit without pair comparisons")
    (is (= 3 (:writes radix)))))

(deftest large-traces-stay-bounded-with-exact-final-counts
  (let [input (vec (reverse (range 128)))
        trace (algorithms/generate-trace :bubble input)
        frames (:frames trace)]
    (is (<= (count frames) algorithms/max-frames))
    (is (= input (:values (first frames))))
    (is (= (vec (range 128)) (final-values trace)))
    (is (= 8128 (:comparisons (peek frames))))
    (is (= 16256 (:writes (peek frames))))))

(deftest bogo-is-honest-bounded-and-repeatable
  (doseq [input [[] [1] [1 2 3] [2 1]]]
    (let [trace (algorithms/generate-trace :bogo input)]
      (is (= :complete (:status trace)))
      (is (= (vec (sort input)) (final-values trace)))))
  (let [input [1 2 3 4 5 6 8 7]
        trace (algorithms/generate-trace :bogo input)]
    (is (= trace (algorithms/generate-trace :bogo input)))
    (is (= :limited (:status trace)))
    (is (seq (:message trace)))
    (is (empty? (:sorted (peek (:frames trace)))))
    (is (= (frequencies input) (frequencies (final-values trace))))
    (is (<= (:writes (peek (:frames trace))) (* 2 7 algorithms/bogo-attempt-limit))))
  (let [input (vec (reverse (range 9)))
        trace (algorithms/generate-trace :bogo input)]
    (is (= :limited (:status trace)))
    (is (= input (final-values trace)))
    (is (= 0 (:writes (peek (:frames trace)))))))

(deftest radix-domain-and-numeric-validation
  (let [input [1000 -1000 10 -10 0 -1000]]
    (is (= (vec (sort input)) (final-values (algorithms/generate-trace :radix input)))))
  (doseq [input [[1.5 0.5] [js/Number.MAX_SAFE_INTEGER (- js/Number.MAX_SAFE_INTEGER)]]]
    (let [trace (algorithms/generate-trace :radix input)]
      (is (= :limited (:status trace)))
      (is (= input (final-values trace)))))
  (doseq [algorithm (remove #{:radix} deterministic-algorithms)]
    (is (= [-1.5 0 2.25] (final-values (algorithms/generate-trace algorithm [2.25 -1.5 0])))))
  (is (thrown? js/Error (algorithms/generate-trace :unknown [1 2])))
  (is (thrown? js/Error (algorithms/generate-trace :quick [js/NaN 1])))
  (is (thrown? js/Error (algorithms/generate-trace :quick [js/Infinity 1]))))
