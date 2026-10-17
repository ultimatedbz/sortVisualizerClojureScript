(ns sort-visualizer.catalog)

;; Complexity describes conventional array algorithms; animation traces and
;; persistent-vector allocations in the educational examples are excluded.
(def algorithms
  [
   {:id :quick
    :name "Quick Sort"
    :family "Divide & conquer"
    :summary "Choose a pivot and partition the array around it. Small partitions become sorted independently."
    :best "O(n log n)"
    :average "O(n log n)"
    :worst "O(n²)"
    :space "O(log n) average"
    :stable? false
    :steps ["Choose the last element as the pivot." "Move smaller values to its left and larger values to its right." "Repeat on both partitions until every pivot is in place."]
    :code "(defn quick-sort [values]
  (letfn [(swap-at [a i j]
            (assoc a i (a j) j (a i)))
          (partition-at [a lo hi]
            (let [pivot (a hi)
                  [a boundary]
                  (reduce (fn [[v boundary] j]
                            (if (< (v j) pivot)
                              [(swap-at v boundary j) (inc boundary)]
                              [v boundary]))
                          [a lo] (range lo hi))]
              [(swap-at a boundary hi) boundary]))
          (sort-range [a lo hi]
            (if (>= lo hi)
              a
              (let [[a pivot] (partition-at a lo hi)]
                (-> a
                    (sort-range lo (dec pivot))
                    (sort-range (inc pivot) hi)))))]
    (sort-range (vec values) 0 (dec (count values)))))"}
   {:id :merge
    :name "Merge Sort"
    :family "Divide & conquer"
    :summary "Split the array into smaller halves, then merge those sorted halves. A predictable, stable sort with extra storage."
    :best "O(n log n)"
    :average "O(n log n)"
    :worst "O(n log n)"
    :space "O(n)"
    :stable? true
    :steps ["Divide the array into two halves." "Sort each half recursively." "Merge both halves by repeatedly taking their smallest remaining value."]
    :code "(defn merge-sort [values]
  (letfn [(merge-parts [left right]
            (loop [left (seq left) right (seq right) out []]
              (cond
                (nil? left) (into out right)
                (nil? right) (into out left)
                (<= (first left) (first right))
                (recur (next left) right (conj out (first left)))
                :else
                (recur left (next right) (conj out (first right))))))]
    (let [a (vec values) middle (quot (count a) 2)]
      (if (< (count a) 2)
        a
        (merge-parts (merge-sort (subvec a 0 middle))
                     (merge-sort (subvec a middle)))))))"}
   {:id :heap
    :name "Heap Sort"
    :family "Selection"
    :summary "Build a max heap, where every parent is at least as large as its children. Move the largest value to the end, then repair the heap."
    :best "O(n log n)"
    :average "O(n log n)"
    :worst "O(n log n)"
    :space "O(1)"
    :stable? false
    :steps ["Arrange the values into a max heap." "Swap the root, the largest value, with the last unsorted value." "Shrink the heap and restore its ordering."]
    :code "(defn heap-sort [values]
  (letfn [(swap-at [a i j]
            (assoc a i (a j) j (a i)))
          (sink [a root size]
            (let [left (inc (* 2 root))
                  right (inc left)
                  largest (reduce (fn [best child]
                                    (if (and (< child size)
                                             (> (a child) (a best)))
                                      child best))
                                  root [left right])]
              (if (= root largest)
                a
                (recur (swap-at a root largest) largest size))))]
    (let [n (count values)
          heap (reduce #(sink %1 %2 n) (vec values)
                       (range (dec (quot n 2)) -1 -1))]
      (reduce (fn [a end]
                (sink (swap-at a 0 end) 0 end))
              heap (range (dec n) 0 -1)))))"}
   {:id :bubble
    :name "Bubble Sort"
    :family "Exchange"
    :summary "Compare adjacent values and swap them when they are out of order. Each pass bubbles the largest remaining value to the end."
    :best "O(n)"
    :average "O(n²)"
    :worst "O(n²)"
    :space "O(1)"
    :stable? true
    :steps ["Compare each neighboring pair from left to right." "Swap the pair if the left value is larger." "Repeat over the shorter unsorted region; stop when a pass makes no swaps."]
    :code "(defn bubble-sort [values]
  (loop [a (vec values) end (dec (count values))]
    (if (<= end 0)
      a
      (let [[a changed?]
            (reduce (fn [[v changed?] i]
                      (if (> (v i) (v (inc i)))
                        [(assoc v i (v (inc i)) (inc i) (v i)) true]
                        [v changed?]))
                    [a false] (range end))]
        (if changed? (recur a (dec end)) a)))))"}
   {:id :selection
    :name "Selection Sort"
    :family "Selection"
    :summary "Find the smallest value in the unsorted region and move it to the front. Simple to follow, with relatively few swaps."
    :best "O(n²)"
    :average "O(n²)"
    :worst "O(n²)"
    :space "O(1)"
    :stable? false
    :steps ["Scan the unsorted region to find its minimum." "Swap that minimum with the first unsorted value." "Move the boundary forward and repeat."]
    :code "(defn selection-sort [values]
  (reduce
    (fn [a i]
      (let [minimum (reduce (fn [best j]
                              (if (< (a j) (a best)) j best))
                            i (range (inc i) (count a)))]
        (assoc a i (a minimum) minimum (a i))))
    (vec values)
    (range (count values))))"}
   {:id :insertion
    :name "Insertion Sort"
    :family "Insertion"
    :summary "Grow a sorted prefix by placing each new value where it belongs. Especially effective when the array is already nearly sorted."
    :best "O(n)"
    :average "O(n²)"
    :worst "O(n²)"
    :space "O(1)"
    :stable? true
    :steps ["Take the next value from the unsorted region." "Shift larger values in the sorted prefix one position to the right." "Insert the chosen value into the gap."]
    :code "(defn insertion-sort [values]
  (reduce
    (fn [a i]
      (let [value (a i)]
        (loop [a a j i]
          (if (and (pos? j) (> (a (dec j)) value))
            (recur (assoc a j (a (dec j))) (dec j))
            (assoc a j value)))))
    (vec values)
    (range 1 (count values))))"}
   {:id :gnome
    :name "Gnome Sort"
    :family "Exchange"
    :summary "Walk forward while neighboring values are ordered. After a swap, step backward until the displaced value is in the right place."
    :best "O(n)"
    :average "O(n²)"
    :worst "O(n²)"
    :space "O(1)"
    :stable? true
    :steps ["Compare the current value with its left neighbor." "Step forward if they are in order; otherwise swap and step backward." "Continue walking until you reach the end of the array."]
    :code "(defn gnome-sort [values]
  (loop [a (vec values) i 1]
    (cond
      (>= i (count a)) a
      (or (zero? i) (<= (a (dec i)) (a i)))
      (recur a (inc i))
      :else
      (recur (assoc a i (a (dec i)) (dec i) (a i))
             (dec i)))))"}
   {:id :shaker
    :name "Shaker Sort"
    :family "Exchange"
    :summary "Bubble sort in both directions. A forward pass places the maximum, and a backward pass places the minimum."
    :best "O(n)"
    :average "O(n²)"
    :worst "O(n²)"
    :space "O(1)"
    :stable? true
    :steps ["Sweep right, swapping out-of-order neighbors." "Sweep left to move the smallest remaining value to the front." "Tighten both boundaries and repeat until a pass makes no swaps."]
    :code "(defn shaker-sort [values]
  (letfn [(pass [a indices]
            (reduce (fn [[v changed?] i]
                      (if (> (v i) (v (inc i)))
                        [(assoc v i (v (inc i)) (inc i) (v i)) true]
                        [v changed?]))
                    [a false] indices))]
    (loop [a (vec values) lo 0 hi (dec (count values))]
      (if (>= lo hi)
        a
        (let [[a forward?] (pass a (range lo hi))
              [a backward?] (pass a (range (- hi 2) (dec lo) -1))]
          (if (or forward? backward?)
            (recur a (inc lo) (dec hi))
            a))))))"}
   {:id :odd-even
    :name "Odd-Even Sort"
    :family "Exchange"
    :summary "Alternate between disjoint odd-indexed and even-indexed neighboring pairs. Repeated compare-and-swap phases eventually order the array."
    :best "O(n)"
    :average "O(n²)"
    :worst "O(n²)"
    :space "O(1)"
    :stable? true
    :steps ["Compare and swap neighboring pairs starting at index zero." "Repeat for the pairs starting at index one." "Continue alternating until neither phase changes the array."]
    :code "(defn odd-even-sort [values]
  (loop [a (vec values)]
    (let [[a changed?]
          (reduce
            (fn [state start]
              (reduce (fn [[v changed?] i]
                        (if (> (v i) (v (inc i)))
                          [(assoc v i (v (inc i)) (inc i) (v i)) true]
                          [v changed?]))
                      state (range start (dec (count a)) 2)))
            [a false] [0 1])]
      (if changed? (recur a) a))))"}
   {:id :pancake
    :name "Pancake Sort"
    :family "Selection"
    :summary "Sort using only prefix reversals. Bring the largest remaining value to the front, then flip it into its final position."
    :best "O(n²)"
    :average "O(n²)"
    :worst "O(n²)"
    :space "O(1)"
    :stable? false
    :steps ["Find the largest value in the unsorted prefix." "Flip the prefix ending at that value to bring it to the front." "Flip the entire unsorted prefix, then shorten it by one."]
    :code "(defn pancake-sort [values]
  (letfn [(flip [a end]
            (into (vec (reverse (subvec a 0 end)))
                  (subvec a end)))]
    (loop [a (vec values) end (count values)]
      (if (< end 2)
        a
        (let [largest (apply max-key a (range end))]
          (recur (if (= largest (dec end))
                   a
                   (-> a (flip (inc largest)) (flip end)))
                 (dec end)))))))"}
   {:id :bitonic
    :name "Bitonic Sort"
    :family "Sorting network"
    :summary "Build and merge bitonic sequences with compare-and-swap operations. The visualizer supports any length; the classic reference network uses powers of two."
    :best "O(n log² n)"
    :average "O(n log² n)"
    :worst "O(n log² n)"
    :space "O(log n)"
    :stable? false
    :steps ["Create small sequences alternating between ascending and descending order." "Compare values at decreasing power-of-two distances." "Double the network size until the entire array is ascending."]
    :code "(defn bitonic-sort [values]
  (let [n (count values)]
    (assert (or (zero? n) (zero? (bit-and n (dec n))))
            \"Bitonic sort requires a power-of-two length\")
    (reduce
      (fn [a size]
        (reduce
          (fn [a gap]
            (reduce
              (fn [a i]
                (let [j (bit-xor i gap)
                      ascending? (zero? (bit-and i size))]
                  (if (and (> j i)
                           (if ascending? (> (a i) (a j))
                                          (< (a i) (a j))))
                    (assoc a i (a j) j (a i))
                    a)))
              a (range n)))
          a (take-while pos? (iterate #(quot % 2) (quot size 2)))))
      (vec values)
      (take-while #(<= % n) (iterate #(* % 2) 2)))))"}
   {:id :radix
    :name "Radix Sort"
    :family "Distribution"
    :summary "Group integers by one digit at a time, preserving order within each group. Here d is the number of digits and b is the base, ten. The visualizer offsets negative values."
    :best "O(d(n + b))"
    :average "O(d(n + b))"
    :worst "O(d(n + b))"
    :space "O(n + b)"
    :stable? true
    :steps ["Distribute values into buckets using their least significant digit." "Collect the buckets in digit order, preserving order within each bucket." "Move to the next digit and repeat until all digits are processed."]
    :code "(defn radix-sort [values]
  (assert (every? #(and (integer? %) (<= 0 %)) values)
          \"Radix sort requires non-negative integers\")
  (let [largest (reduce max 0 values)]
    (loop [a (vec values) place 1]
      (if (> place largest)
        a
        (let [buckets (reduce (fn [b value]
                                (update b (mod (quot value place) 10)
                                        conj value))
                              (vec (repeat 10 [])) a)]
          (recur (vec (mapcat identity buckets)) (* place 10)))))))"}
   {:id :shell
    :name "Shell Sort"
    :family "Insertion"
    :summary "Insertion sort with gaps lets values travel long distances early. Halving the gap eventually produces a normal insertion-sort pass."
    :best "O(n log n)"
    :average "Gap-dependent"
    :worst "O(n²)"
    :space "O(1)"
    :stable? false
    :steps ["Choose an initial gap of half the array length." "Perform insertion sort on values separated by that gap." "Halve the gap until the final gap-one pass completes."]
    :code "(defn shell-sort [values]
  (loop [a (vec values) gap (quot (count values) 2)]
    (if (zero? gap)
      a
      (recur
        (reduce
          (fn [a i]
            (let [value (a i)]
              (loop [a a j i]
                (if (and (>= j gap) (> (a (- j gap)) value))
                  (recur (assoc a j (a (- j gap))) (- j gap))
                  (assoc a j value)))))
          a (range gap (count a)))
        (quot gap 2)))))"}
   {:id :comb
    :name "Comb Sort"
    :family "Exchange"
    :summary "Compare values across a shrinking gap to move small values away from the end quickly. Finish with adjacent comparisons until no swaps remain."
    :best "O(n log n)"
    :average "Gap-dependent"
    :worst "O(n²)"
    :space "O(1)"
    :stable? false
    :steps ["Start with a gap spanning the array." "Shrink the gap by about 1.3 and swap out-of-order pairs." "At gap one, repeat until a full pass makes no swaps."]
    :code "(defn comb-sort [values]
  (loop [a (vec values) gap (count values)]
    (let [gap (max 1 (int (/ gap 1.3)))
          [a changed?]
          (reduce (fn [[v changed?] i]
                    (let [j (+ i gap)]
                      (if (> (v i) (v j))
                        [(assoc v i (v j) j (v i)) true]
                        [v changed?])))
                  [a false] (range (- (count a) gap)))]
      (if (and (= gap 1) (not changed?))
        a
        (recur a gap)))))"}
   {:id :bogo
    :name "Bogo Sort"
    :family "Randomized"
    :summary "Shuffle and hope. Bogo sort checks random permutations until one is sorted; the visualizer limits this experiment to keep it usable."
    :best "O(n)"
    :average "O(n · n!)"
    :worst "Unbounded"
    :space "O(1)"
    :stable? false
    :steps ["Check whether every neighboring pair is ordered." "If not, shuffle the entire array." "Try again, subject to the visualizer’s attempt limit."]
    :code "(defn bogo-sort [values]
  ;; Deliberately bounded: this algorithm has no finite worst case.
  (loop [a (vec values) attempts 0]
    (cond
      (every? (fn [[left right]] (<= left right))
              (partition 2 1 a)) a
      (>= attempts 256)
      (throw (ex-info \"Bogo sort reached its attempt limit\"
                      {:attempts attempts :values a}))
      :else (recur (shuffle a) (inc attempts)))))"}
   ])

(defn by-id [id]
  (first (filter #(= id (:id %)) algorithms)))
