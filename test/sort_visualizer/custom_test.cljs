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

(deftest records-instrumented-operations
  (let [{:keys [status frames]}
        (custom/generate-trace
         "(compare! 0 1) (swap! 0 1) (snapshot! [0 1] \"Both values are sorted.\")"
         [2 1])
        final-frame (last frames)]
    (is (= :complete status))
    (is (= 1 (:comparisons final-frame)))
    (is (= 2 (:writes final-frame)))
    (is (= [0 1] (:active (nth frames 1))))
    (is (= "Both values are sorted." (:note (nth frames 3))))
    (is (= [2 1] (:values (first frames))))))

(deftest invalid-programs-produce-useful-results
  (testing "Malformed or empty code"
    (doseq [code ["(defn broken [" "" "  "]]
      (let [{:keys [status message]} (custom/generate-trace code [2 1])]
        (is (= :invalid status))
        (is (seq message)))))
  (testing "Array bounds"
    (doseq [code ["(value -1)" "(compare! 0 2)" "(swap! 0 1.5)" "(write! 2 1)"
                  "(snapshot! [3] \"Outside\")"]]
      (let [{:keys [status message]} (custom/generate-trace code [2 1])]
        (is (= :invalid status))
        (is (re-find #"outside the array" message)))))
  (testing "Unsorted result"
    (let [result (custom/generate-trace "(size)" [2 1])]
      (is (= :invalid (:status result)))
      (is (re-find #"not sorted" (:message result)))))
  (testing "Same length is insufficient: all original values must survive"
    (let [result (custom/generate-trace "(write! 0 1)" [2 1])]
      (is (= :invalid (:status result)))
      (is (re-find #"preserve" (:message result)))))
  (testing "Only finite values are accepted"
    (is (= :invalid (:status (custom/generate-trace "(write! 0 ##NaN)" [1]))))
    (is (= :invalid (:status (custom/generate-trace "(write! 0 ##Inf)" [1]))))
    (is (= :invalid (:status (custom/generate-trace "(size)" [js/NaN]))))))

(deftest custom-code-has-no-javascript-global-access
  (doseq [code ["js/globalThis" "js/self" "(js/eval \"1 + 1\")" "(js* \"globalThis\")"
                "(aget value \"constructor\")" "(type value)" "(resolve 'aget)"
                "(ns-publics 'clojure.core)" "(.-constructor value)"]]
    (is (= :invalid (:status (custom/generate-trace code [1]))))))

(deftest operation-limit-stops-recording
  (let [{:keys [status frames message]}
        (custom/generate-trace "(dotimes [_ 25000] (compare! 0 1))" [2 1])]
    (is (= :limited status))
    (is (<= (count frames) custom/max-frames))
    (is (re-find #"limit" message))))

(deftest interpreter-context-is-fresh-for-each-run
  (is (= :complete (:status (custom/generate-trace "(def previous-run 1)" [1]))))
  (is (= :invalid (:status (custom/generate-trace "previous-run" [1])))))
