(ns sort-visualizer.custom-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [sort-visualizer.custom :as custom]))

(deftest default-code-sorts-and-preserves-values
  (doseq [values [[5 1 4 2 3] [3 1 3 2 1] [1] [] [-4 2.5 0 -4]]]
    (let [{:keys [status frames]} (custom/generate-trace custom/default-code values)
          final-frame (last frames)]
      (is (= :complete status) (str "Input: " values))
      (is (= (vec (sort values)) (:values final-frame)))
      (is (= (vec (range (count values))) (:sorted final-frame)))
      (is (= values (:values (first frames))))
      (is (<= (count frames) custom/max-frames)))))

(deftest records-pure-state-transitions
  (let [{:keys [status frames]}
        (custom/generate-trace
         "(fn [state]
            (let [[state order] (compare-at state 0 1)]
              (snapshot (swap-at state 0 1) [0 1] \"Both values are sorted.\")))"
         [2 1])
        final-frame (last frames)]
    (is (= :complete status))
    (is (= 1 (:comparisons final-frame)))
    (is (= 2 (:writes final-frame)))
    (is (= [0 1] (:active (nth frames 1))))
    (is (= "Both values are sorted." (:note (nth frames 3))))
    (is (= [2 1] (:values (first frames))))))

(deftest states-and-historical-frames-are-reusable
  (let [code "(fn [state]
                (let [[compared order] (compare-at state 0 1)
                      swapped (swap-at state 0 1)
                      written (write-at state 0 99)
                      annotated (snapshot state [0] \"Independent branch\")]
                  (if (and (= 2 (size state)) (= 2 (value state 0))
                           (= swapped (swap-at state 0 1))
                           (= compared (first (compare-at state 0 1)))
                           (= [2 1] (:values state))
                           (= [2 1] (:values (first (:frames swapped))))
                           (= 0 (:comparisons state)) (= 0 (:writes state))
                           (= 0 (:operations state)) (= 1 (count (:frames state)))
                           (= 1 (:comparisons compared)) (= 0 (:writes compared))
                           (= 2 (:writes swapped)) (= 0 (:comparisons swapped))
                           (= [99 1] (:values written)) (= 1 (:writes written))
                           (= [2 1] (:values annotated))
                           (= \"Independent branch\" (:note annotated))
                           (pos? order))
                    swapped
                    nil)))"
        input [2 1]
        result (custom/generate-trace code input)]
    (is (= :complete (:status result)))
    (is (= [2 1] input))
    (is (= result (custom/generate-trace code input)))
    (is (= 3 (count (:frames result))))
    (is (= 0 (:comparisons (last (:frames result)))))
    (is (= 2 (:writes (last (:frames result)))))))

(deftest ignored-return-values-cannot-mutate-a-state
  (let [result (custom/generate-trace
                "(fn [state] (compare-at state 0 1) (swap-at state 0 1) state)" [2 1])]
    (is (= :invalid (:status result)))
    (is (re-find #"not sorted" (:message result)))
    (is (= 1 (count (:frames result))))
    (is (= [2 1] (:values (last (:frames result)))))
    (is (= 0 (:comparisons (last (:frames result)))))
    (is (= 0 (:writes (last (:frames result)))))))

(deftest invalid-programs-produce-useful-results
  (testing "Malformed, empty or non-function code"
    (doseq [code ["(fn broken [" "" "  " "nil" "42" "{:values [1]}" "[1]"]]
      (let [{:keys [status message]} (custom/generate-trace code [2 1])]
        (is (= :invalid status))
        (is (seq message)))))
  (testing "Array bounds"
    (doseq [body ["(value state -1)" "(compare-at state 0 2)" "(swap-at state 0 1.5)"
                  "(write-at state 2 1)" "(snapshot state [3] \"Outside\")"]]
      (let [{:keys [status message]}
            (custom/generate-trace (str "(fn [state] " body ")") [2 1])]
        (is (= :invalid status))
        (is (re-find #"outside the array" message)))))
  (testing "Same length is insufficient: all original values must survive"
    (let [result (custom/generate-trace "(fn [state] (write-at state 0 1))" [2 1])]
      (is (= :invalid (:status result)))
      (is (re-find #"preserve" (:message result)))))
  (testing "Only finite values are accepted"
    (doseq [code ["(fn [state] (write-at state 0 ##NaN))"
                  "(fn [state] (write-at state 0 ##Inf))"]]
      (is (= :invalid (:status (custom/generate-trace code [1])))))
    (is (= :invalid (:status (custom/generate-trace "identity" [js/NaN])))))
  (testing "Runtime errors are visible"
    (let [result (custom/generate-trace "(fn [state] (nth [] 10))" [1])]
      (is (= :invalid (:status result)))
      (is (seq (:message result)))
      (is (= [1] (:values (first (:frames result))))))))

(deftest returned-state-and-frames-are-validated
  (doseq [body ["nil" "[state]" "(:values state)"
                "(assoc state :values [##Inf])"
                "(assoc state :values [])"
                "(assoc state :active [10])"
                "(assoc state :sorted [-1])"
                "(assoc state :writes -1)"
                "(assoc state :comparisons ##NaN)"
                "(assoc state :operations 999999)"
                "(assoc state :frames [])"
                "(assoc state :frames (repeat state))"
                "(assoc state :frames (vec (repeat 20000 {})))"
                "(assoc-in state [:frames 0 :values] [0])"
                "(assoc-in state [:frames 0 :active] [99])"
                "(assoc state :note 123)"]]
    (let [result (custom/generate-trace (str "(fn [state] " body ")") [1])]
      (is (= :invalid (:status result)) body)
      (is (seq (:message result)))
      (is (= [1] (:values (first (:frames result))))))))

(deftest snapshots-bound-input-realization-and-note-length
  (is (= :invalid (:status (custom/generate-trace
                           "(fn [state] (snapshot state (repeat 0) \"Too many\"))" [1]))))
  (let [result (custom/generate-trace
                "(fn [state] (snapshot state [0] (apply str (repeat 500 \"x\"))))" [1])]
    (is (= :complete (:status result)))
    (is (= 180 (count (:note (second (:frames result))))))))

(deftest custom-code-cannot-access-mutable-references-or-javascript
  (doseq [code ["js/globalThis" "js/self" "(js/eval \"1 + 1\")" "(js* \"globalThis\")"
                "(aget value \"constructor\")" "(type value)" "(resolve 'aget)"
                "(ns-publics 'clojure.core)" "(.-constructor value)"
                "(fn [state] (atom state))" "(fn [state] (volatile! state))"
                "(fn [state] (reset! state 1))" "(fn [state] (vreset! state 1))"
                "(fn [state] (vswap! state identity))" "(fn [state] (deref state))"
                "(fn [state] (transient []))" "(fn [state] (chunk-buffer 10))"]]
    (is (= :invalid (:status (custom/generate-trace code [1]))) code)))

(deftest legacy-code-is-rejected-with-migration-help
  (is (custom/legacy-code? custom/legacy-default-code))
  (is (not (custom/legacy-code? custom/default-code)))
  (doseq [code [custom/legacy-default-code "(swap! 0 1)" "(compare! 0 1)"
                "(write! 0 1)" "(snapshot! [] \"hello\")"]]
    (let [result (custom/generate-trace code [2 1])]
      (is (= :invalid (:status result)))
      (is (re-find #"old mutable API" (:message result)))
      (is (re-find #"next-state" (:message result))))))

(deftest operation-limit-preserves-valid-partial-trace
  (doseq [operation ["(first (compare-at state 0 1))" "(swap-at state 0 1)"]]
    (let [{:keys [status frames message]}
          (custom/generate-trace
           (str "(fn [state] (reduce (fn [state _] " operation ") state (range 25000)))")
           [2 1])]
      (is (= :limited status))
      (is (= (dec custom/max-frames) (count frames)))
      (is (re-find #"limit" message))
      (is (= (dec (count frames))
             (if (= operation "(swap-at state 0 1)")
               (/ (:writes (last frames)) 2)
               (:comparisons (last frames))))))))

(deftest named-functions-work-in-a-fresh-context
  (is (= :complete (:status (custom/generate-trace
                            "(defn previous-run [state] state) previous-run" [1]))))
  (is (= :invalid (:status (custom/generate-trace "previous-run" [1])))))
