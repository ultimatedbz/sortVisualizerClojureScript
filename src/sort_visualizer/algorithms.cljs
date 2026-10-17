(ns sort-visualizer.algorithms)

(def algorithm-order
  [:quick :merge :heap :bubble :selection :insertion :gnome :shaker
   :odd-even :pancake :bitonic :radix :shell :comb :bogo])

(def algorithm-info
  {:quick {:name "Quick sort" :complexity "O(n log n)" :worst "O(n²)" :space "O(log n)" :stable false
           :description "Partition around a pivot, then recursively sort the smaller regions."}
   :merge {:name "Merge sort" :complexity "O(n log n)" :worst "O(n log n)" :space "O(n)" :stable true
           :description "Split the array in half, then merge sorted halves back together."}
   :heap {:name "Heap sort" :complexity "O(n log n)" :worst "O(n log n)" :space "O(1)" :stable false
          :description "Build a max heap and repeatedly move its largest value to the end."}
   :bubble {:name "Bubble sort" :complexity "O(n²)" :worst "O(n²)" :space "O(1)" :stable true
            :description "Compare neighbors and bubble the largest unsorted value to the right."}
   :selection {:name "Selection sort" :complexity "O(n²)" :worst "O(n²)" :space "O(1)" :stable false
               :description "Find the smallest remaining value and place it at the front."}
   :insertion {:name "Insertion sort" :complexity "O(n²)" :worst "O(n²)" :space "O(1)" :stable true
               :description "Grow a sorted prefix by moving each new value into its place."}
   :gnome {:name "Gnome sort" :complexity "O(n²)" :worst "O(n²)" :space "O(1)" :stable true
           :description "Walk forward through ordered neighbors and step backward after a swap."}
   :shaker {:name "Cocktail shaker sort" :complexity "O(n²)" :worst "O(n²)" :space "O(1)" :stable true
            :description "Bubble in both directions, moving large values right and small values left."}
   :odd-even {:name "Odd-even sort" :complexity "O(n²)" :worst "O(n²)" :space "O(1)" :stable true
              :description "Alternate comparisons of odd and even neighboring pairs."}
   :pancake {:name "Pancake sort" :complexity "O(n²)" :worst "O(n²)" :space "O(1)" :stable false
             :description "Flip prefixes to move the largest remaining value to its final position."}
   :bitonic {:name "Bitonic sort" :complexity "O(n log² n)" :worst "O(n log² n)" :space "O(log n)" :stable false
             :description "Build ascending and descending runs, then compare across a sorting network. Any array length is supported."}
   :radix {:name "Radix sort" :complexity "O(d(n + 10))" :worst "O(d(n + 10))" :space "O(n + 10)" :stable true
           :description "Distribute integers into digit buckets, from the least significant digit upward."}
   :shell {:name "Shell sort" :complexity "Gap dependent" :worst "O(n²)" :space "O(1)" :stable false
           :description "Run insertion sort across shrinking gaps, ending with adjacent values."}
   :comb {:name "Comb sort" :complexity "O(n²)" :worst "O(n²)" :space "O(1)" :stable false
          :description "Compare values across a shrinking gap to move small values left sooner."}
   :bogo {:name "Bogo sort" :complexity "O(n × n!) expected" :worst "Unbounded" :space "O(1)" :stable false
          :description "Shuffle until sorted. This demonstration stops after 256 attempts and allows at most 8 values."}})

(def max-frames 6000)
(def bogo-attempt-limit 256)

;; All mutation stays inside one invocation. Public traces are persistent values,
;; and seeded bogo shuffles make identical input traces reproducible.
(defn- recorder [values]
  {:values (atom (vec values)) :frames (atom []) :sorted (atom [])
   :comparisons (atom 0) :writes (atom 0) :events (atom 0) :stride (atom 1)})

(defn- snapshot [r active note]
  {:values @(:values r) :active (vec active) :sorted @(:sorted r)
   :comparisons @(:comparisons r) :writes @(:writes r) :note note})

(defn- capture! [r active note]
  (let [event (swap! (:events r) inc)]
    (when (zero? (mod event @(:stride r)))
      ;; Downsample older snapshots before increasing the capture interval.
      ;; Counters still include every operation and the initial frame survives.
      (when (>= (count @(:frames r)) (dec max-frames))
        (swap! (:frames r) #(vec (take-nth 2 %)))
        (swap! (:stride r) * 2))
      (swap! (:frames r) conj (snapshot r active note)))))

(defn- value-at [r i] (nth @(:values r) i))

(defn- compare-values! [r a b active note]
  (swap! (:comparisons r) inc)
  (capture! r active note)
  (compare a b))

(defn- compare-at! [r i j]
  (compare-values! r (value-at r i) (value-at r j) [i j] "Compare values"))

(defn- exchange! [r i j]
  (when (not= i j)
    (let [a (value-at r i) b (value-at r j)]
      (swap! (:values r) assoc i b j a)
      (swap! (:writes r) + 2)
      (capture! r [i j] "Swap values"))))

(defn- write! [r i value note]
  (swap! (:values r) assoc i value)
  (swap! (:writes r) inc)
  (capture! r [i] note))

(defn- mark! [r indices]
  (swap! (:sorted r) #(vec (sort (into (set %) indices))))
  (capture! r [] "Values reached their final positions"))

(defn- quick! [r n]
  (letfn [(partition! [lo hi]
            (loop [i lo j lo]
              (if (= j hi)
                (do (exchange! r i hi) i)
                (if (not (pos? (compare-at! r j hi)))
                  (do (exchange! r i j) (recur (inc i) (inc j)))
                  (recur i (inc j))))))
          (sort! [lo hi]
            (when (<= lo hi)
              (if (= lo hi)
                (mark! r [lo])
                (let [pivot (partition! lo hi)]
                  (mark! r [pivot])
                  (sort! lo (dec pivot))
                  (sort! (inc pivot) hi)))))]
    (sort! 0 (dec n))))

(defn- merge! [r n]
  (letfn [(sort! [lo hi]
            (when (> (- hi lo) 1)
              (let [mid (quot (+ lo hi) 2)]
                (sort! lo mid)
                (sort! mid hi)
                (let [left (subvec @(:values r) lo mid)
                      right (subvec @(:values r) mid hi)]
                  (loop [i 0 j 0 k lo]
                    (when (< k hi)
                      (if (or (= j (count right))
                              (and (< i (count left))
                                   (not (pos? (compare-values! r (nth left i) (nth right j)
                                                              [(+ lo i) (+ mid j)]
                                                              "Compare buffered values")))))
                        (do (write! r k (nth left i) "Merge from the left run")
                            (recur (inc i) j (inc k)))
                        (do (write! r k (nth right j) "Merge from the right run")
                            (recur i (inc j) (inc k))))))))))]
    (sort! 0 n)))

(defn- heap! [r n]
  (letfn [(sift! [root end]
            (let [left (inc (* 2 root)) right (inc left)
                  largest (if (and (< left end) (pos? (compare-at! r left root))) left root)
                  largest (if (and (< right end) (pos? (compare-at! r right largest))) right largest)]
              (when (not= root largest)
                (exchange! r root largest)
                (sift! largest end))))]
    (doseq [i (range (dec (quot n 2)) -1 -1)] (sift! i n))
    (doseq [end (range (dec n) 0 -1)]
      (exchange! r 0 end)
      (mark! r [end])
      (sift! 0 end))))

(defn- bubble! [r n]
  (loop [end (dec n)]
    (when (pos? end)
      (let [changed? (atom false)]
        (doseq [i (range end)]
          (when (pos? (compare-at! r i (inc i)))
            (exchange! r i (inc i))
            (reset! changed? true)))
        (mark! r [end])
        (when @changed? (recur (dec end)))))))

(defn- selection! [r n]
  (doseq [i (range n)]
    (let [minimum (reduce (fn [candidate j]
                           (if (neg? (compare-at! r j candidate)) j candidate))
                         i (range (inc i) n))]
      (exchange! r i minimum)
      (mark! r [i]))))

(defn- insertion-gap! [r n gap]
  (doseq [i (range gap n)]
    (loop [j i]
      (when (and (>= j gap) (pos? (compare-at! r (- j gap) j)))
        (exchange! r (- j gap) j)
        (recur (- j gap))))))

(defn- gnome! [r n]
  (loop [i 1]
    (when (< i n)
      (if (or (zero? i) (not (pos? (compare-at! r (dec i) i))))
        (recur (inc i))
        (do (exchange! r (dec i) i) (recur (dec i)))))))

(defn- shaker! [r n]
  (loop [lo 0 hi (dec n)]
    (when (< lo hi)
      (let [changed? (atom false)]
        (doseq [i (range lo hi)]
          (when (pos? (compare-at! r i (inc i)))
            (exchange! r i (inc i))
            (reset! changed? true)))
        (mark! r [hi])
        (when @changed?
          (reset! changed? false)
          (doseq [i (range (dec hi) lo -1)]
            (when (pos? (compare-at! r (dec i) i))
              (exchange! r (dec i) i)
              (reset! changed? true)))
          (mark! r [lo])
          (when @changed? (recur (inc lo) (dec hi))))))))

(defn- odd-even! [r n]
  (loop []
    (let [changed? (atom false)]
      (doseq [start [1 0] i (range start (dec n) 2)]
        (when (pos? (compare-at! r i (inc i)))
          (exchange! r i (inc i))
          (reset! changed? true)))
      (when @changed? (recur)))))

(defn- pancake! [r n]
  (letfn [(flip! [end]
            (doseq [i (range (quot (inc end) 2))]
              (exchange! r i (- end i))))]
    (doseq [size (range n 1 -1)]
      (let [largest (reduce (fn [candidate j]
                             (if (pos? (compare-at! r j candidate)) j candidate))
                           0 (range 1 size))]
        (when (not= largest (dec size))
          (flip! largest)
          (flip! (dec size)))
        (mark! r [(dec size)])))))

(defn- bitonic! [r n]
  ;; Generalized bitonic merge uses the greatest power of two below the run
  ;; length, so no sentinel bars or power-of-two input restriction is needed.
  (letfn [(merge-run! [lo length ascending?]
            (when (> length 1)
              (let [gap (loop [k 1] (if (< (* 2 k) length) (recur (* 2 k)) k))]
                (doseq [i (range lo (+ lo (- length gap)))]
                  (let [order (compare-at! r i (+ i gap))]
                    (when (if ascending? (pos? order) (neg? order))
                      (exchange! r i (+ i gap)))))
                (merge-run! lo gap ascending?)
                (merge-run! (+ lo gap) (- length gap) ascending?))))
          (sort! [lo length ascending?]
            (when (> length 1)
              (let [half (quot length 2)]
                (sort! lo half (not ascending?))
                (sort! (+ lo half) (- length half) ascending?)
                (merge-run! lo length ascending?))))]
    (sort! 0 n true)))

(defn- radix! [r n]
  (when (pos? n)
    (let [minimum (reduce min @(:values r))
          maximum (- (reduce max @(:values r)) minimum)]
      (loop [place 1]
        (when (<= place maximum)
          (let [buckets (reduce (fn [buckets [i value]]
                                  (let [digit (mod (js/Math.floor (/ (- value minimum) place)) 10)]
                                    (capture! r [i] (str "Place value in digit bucket " digit))
                                    (update buckets digit conj value)))
                                (vec (repeat 10 [])) (map-indexed vector @(:values r)))]
            (doseq [[i value] (map-indexed vector (mapcat identity buckets))]
              (write! r i value (str "Collect digit buckets · place " place))))
          (recur (* place 10)))))))

(defn- shell! [r n]
  (loop [gap (quot n 2)]
    (when (pos? gap)
      (insertion-gap! r n gap)
      (recur (quot gap 2)))))

(defn- comb! [r n]
  (loop [previous-gap n]
    (let [gap (max 1 (js/Math.floor (/ previous-gap 1.3))) changed? (atom false)]
      (doseq [i (range (- n gap))]
        (when (pos? (compare-at! r i (+ i gap)))
          (exchange! r i (+ i gap))
          (reset! changed? true)))
      (when (or (> gap 1) @changed?) (recur gap)))))

(defn- bogo! [r n]
  (if (> n 8)
    {:status :limited :message "Bogo sort is limited to 8 values. Reduce the array size to run it."}
    (let [seed (atom 42)
          random-index! (fn [bound]
                          (swap! seed #(mod (+ (* % 1664525) 1013904223) 4294967296))
                          (js/Math.floor (* (/ @seed 4294967296) bound)))
          ordered? (fn []
                     (loop [i 1]
                       (or (>= i n)
                           (and (not (pos? (compare-at! r (dec i) i)))
                                (recur (inc i))))))]
      (loop [attempt 0]
        (cond
          (ordered?) {:status :complete}
          (= attempt bogo-attempt-limit)
          {:status :limited :message "Stopped after 256 shuffles. Bogo sort has no guaranteed completion time."}
          :else
          (do (doseq [i (range (dec n) 0 -1)] (exchange! r i (random-index! (inc i))))
              (capture! r [] (str "Shuffle attempt " (inc attempt)))
              (recur (inc attempt))))))))

(defn generate-trace
  "Return a deterministic, replayable sort trace. Counts include all operations,
  even if a long trace is sampled to max-frames snapshots. :sorted indices mark
  permanent final positions. Radix requires safe integers with a safe range.
  Invalid input or an unknown algorithm throws ex-info. Bogo limits are explicit."
  [algorithm-id values]
  (when-not (contains? algorithm-info algorithm-id)
    (throw (ex-info "Unknown sorting algorithm" {:algorithm algorithm-id})))
  (when-not (every? #(and (number? %) (js/Number.isFinite %)) values)
    (throw (ex-info "Sorting values must be finite numbers" {})))
  (let [r (recorder values) n (count values)
        radix-valid? (or (not= algorithm-id :radix)
                         (and (every? #(js/Number.isSafeInteger %) values)
                              (or (empty? values)
                                  (js/Number.isSafeInteger (- (reduce max values) (reduce min values))))))]
    (swap! (:frames r) conj (snapshot r [] "Ready to sort"))
    (let [result (if-not radix-valid?
                   {:status :limited :message "Radix sort requires safe integers within a safe integer range."}
                   (if (= algorithm-id :bogo)
                     (bogo! r n)
                     (do (case algorithm-id
                           :quick (quick! r n)
                           :merge (merge! r n)
                           :heap (heap! r n)
                           :bubble (bubble! r n)
                           :selection (selection! r n)
                           :insertion (insertion-gap! r n 1)
                           :gnome (gnome! r n)
                           :shaker (shaker! r n)
                           :odd-even (odd-even! r n)
                           :pancake (pancake! r n)
                           :bitonic (bitonic! r n)
                           :radix (radix! r n)
                           :shell (shell! r n)
                           :comb (comb! r n))
                         {:status :complete})))]
      (when (= :complete (:status result)) (reset! (:sorted r) (vec (range n))))
      (swap! (:frames r) conj (snapshot r [] (if (= :complete (:status result))
                                              "Sorting complete" (:message result))))
      (assoc result :frames @(:frames r)))))
