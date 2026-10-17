(ns sort-visualizer.catalog-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [sci.core :as sci]
            [sort-visualizer.catalog :as catalog]
            [sort-visualizer.references :as references]))

(deftest catalog-is-complete
  (is (= #{:quick :merge :heap :bubble :selection :insertion :gnome :shaker
           :odd-even :pancake :bitonic :radix :shell :comb :bogo}
         (set (map :id catalog/algorithms))))
  (doseq [algorithm catalog/algorithms]
    (testing (:name algorithm)
      (is (= 3 (count (:steps algorithm))))
      (is (= #{"C" "C++" "Java" "JavaScript" "Python"}
             (set (keys (get references/codes (:id algorithm)))))))))

(defn- example-result [algorithm input]
  (sci/eval-string
    (str (:code algorithm) "\n(" (name (:id algorithm)) "-sort "
         (pr-str input) ")")))

(deftest clojurescript-reference-examples-sort-correctly
  (doseq [algorithm catalog/algorithms
          input (case (:id algorithm)
                  :bogo [[] [1] [1 1 2 3]]
                  :bitonic [[] [1] [2 1] [7 3 5 2 4 6 1 0]
                            [3 -1 3 2 -5 2 1 0]]
                  :radix [[] [0] [9 0 13 2 10 9 100 1]
                          [3 1 3 2 2 1]]
                  [[] [1] [2 1] [7 3 5 2 4 6 1 0]
                   [3 -1 3 2 -5 2 1] [1 2 3 4] [4 3 2 1]])]
    (testing (str (:name algorithm) " on " input)
      (is (= (vec (sort input)) (example-result algorithm input))))))

(deftest specialized-examples-explain-input-restrictions
  (is (thrown? js/Error
               (example-result (catalog/by-id :bitonic) [3 2 1])))
  (is (thrown? js/Error
               (example-result (catalog/by-id :radix) [-1 2 3])))
  (is (thrown? js/Error
               (example-result (catalog/by-id :radix) [1.5 2 3]))))
