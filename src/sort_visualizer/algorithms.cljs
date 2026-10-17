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

;; Every transition returns a new recording state. Frames share persistent
;; values safely, so an earlier state can be replayed or branched independently.
(defn- recorder [values]
  {:values (vec values) :frames [] :sorted []
   :comparisons 0 :writes 0 :events 0 :stride 1})

(defn- snapshot [state active note]
  (assoc (select-keys state [:values :sorted :comparisons :writes])
         :active (vec active) :note note))

(defn- capture [state active note]
  (let [state (update state :events inc)]
    (if (zero? (mod (:events state) (:stride state)))
      ;; Downsampling changes only the returned state's frame collection.
      ;; Counters include every operation and the initial frame survives.
      (let [state (if (>= (count (:frames state)) (dec max-frames))
                    (-> state
                        (update :frames #(vec (take-nth 2 %)))
                        (update :stride * 2))
                    state)]
        (update state :frames conj (snapshot state active note)))
      state)))

(defn- value-at [state i] (nth (:values state) i))

(defn- compare-values [state a b active note]
  [(capture (update state :comparisons inc) active note) (compare a b)])

(defn- compare-at [state i j]
  (compare-values state (value-at state i) (value-at state j)
                  [i j] "Compare values"))

(defn- exchange [state i j]
  (if (= i j)
    state
    (let [a (value-at state i) b (value-at state j)]
      (-> state
          (update :values assoc i b j a)
          (update :writes + 2)
          (capture [i j] "Swap values")))))

(defn- write-value [state i value note]
  (-> state
      (update :values assoc i value)
      (update :writes inc)
      (capture [i] note)))

(defn- mark [state indices]
  (-> state
      (update :sorted #(vec (sort (into (set %) indices))))
      (capture [] "Values reached their final positions")))

(defn- quick [state n]
  (letfn [(partition-run [state lo hi]
            (loop [state state i lo j lo]
              (if (= j hi)
                [(exchange state i hi) i]
                (let [[state order] (compare-at state j hi)]
                  (if (pos? order)
                    (recur state i (inc j))
                    (recur (exchange state i j) (inc i) (inc j)))))))
          (sort-run [state lo hi]
            (cond
              (> lo hi) state
              (= lo hi) (mark state [lo])
              :else (let [[state pivot] (partition-run state lo hi)]
                      (-> state
                          (mark [pivot])
                          (sort-run lo (dec pivot))
                          (sort-run (inc pivot) hi)))))]
    (sort-run state 0 (dec n))))

(defn- merge-sort [state n]
  (letfn [(sort-run [state lo hi]
            (if (<= (- hi lo) 1)
              state
              (let [mid (quot (+ lo hi) 2)
                    state (-> state (sort-run lo mid) (sort-run mid hi))
                    left (subvec (:values state) lo mid)
                    right (subvec (:values state) mid hi)]
                (loop [state state i 0 j 0 k lo]
                  (if (= k hi)
                    state
                    (let [[state take-left?]
                          (cond
                            (= j (count right)) [state true]
                            (= i (count left)) [state false]
                            :else (let [[state order]
                                        (compare-values state (nth left i) (nth right j)
                                                        [(+ lo i) (+ mid j)]
                                                        "Compare buffered values")]
                                    [state (not (pos? order))]))]
                      (if take-left?
                        (recur (write-value state k (nth left i) "Merge from the left run")
                               (inc i) j (inc k))
                        (recur (write-value state k (nth right j) "Merge from the right run")
                               i (inc j) (inc k)))))))))]
    (sort-run state 0 n)))

(defn- heap [state n]
  (letfn [(sift [state root end]
            (let [left (inc (* 2 root)) right (inc left)
                  [state largest] (if (< left end)
                                    (let [[state order] (compare-at state left root)]
                                      [state (if (pos? order) left root)])
                                    [state root])
                  [state largest] (if (< right end)
                                    (let [[state order] (compare-at state right largest)]
                                      [state (if (pos? order) right largest)])
                                    [state largest])]
              (if (= root largest)
                state
                (recur (exchange state root largest) largest end))))]
    (let [state (reduce #(sift %1 %2 n) state (range (dec (quot n 2)) -1 -1))]
      (reduce (fn [state end]
                (-> state (exchange 0 end) (mark [end]) (sift 0 end)))
              state (range (dec n) 0 -1)))))

(defn- ascending-pass
  "Compare each index pair, returning the recording state and whether it changed."
  [state pairs]
  (reduce (fn [[state changed?] [i j]]
            (let [[state order] (compare-at state i j)]
              (if (pos? order)
                [(exchange state i j) true]
                [state changed?])))
          [state false] pairs))

(defn- bubble [state n]
  (loop [state state end (dec n)]
    (if (pos? end)
      (let [[state changed?] (ascending-pass state (map #(vector % (inc %)) (range end)))
            state (mark state [end])]
        (if changed? (recur state (dec end)) state))
      state)))

(defn- selection [state n]
  (reduce (fn [state i]
            (let [[state minimum]
                  (reduce (fn [[state candidate] j]
                            (let [[state order] (compare-at state j candidate)]
                              [state (if (neg? order) j candidate)]))
                          [state i] (range (inc i) n))]
              (-> state (exchange i minimum) (mark [i]))))
          state (range n)))

(defn- insertion-gap [state n gap]
  (reduce (fn [state i]
            (loop [state state j i]
              (if (< j gap)
                state
                (let [[state order] (compare-at state (- j gap) j)]
                  (if (pos? order)
                    (recur (exchange state (- j gap) j) (- j gap))
                    state)))))
          state (range gap n)))

(defn- gnome [state n]
  (loop [state state i 1]
    (cond
      (>= i n) state
      (zero? i) (recur state (inc i))
      :else (let [[state order] (compare-at state (dec i) i)]
              (if (pos? order)
                (recur (exchange state (dec i) i) (dec i))
                (recur state (inc i)))))))

(defn- shaker [state n]
  (loop [state state lo 0 hi (dec n)]
    (if (< lo hi)
      (let [[state changed?] (ascending-pass state (map #(vector % (inc %)) (range lo hi)))
            state (mark state [hi])]
        (if changed?
          (let [[state changed?] (ascending-pass state (map #(vector (dec %) %) (range (dec hi) lo -1)))
                state (mark state [lo])]
            (if changed? (recur state (inc lo) (dec hi)) state))
          state))
      state)))

(defn- odd-even [state n]
  (loop [state state]
    (let [[state changed?] (ascending-pass state (for [start [1 0] i (range start (dec n) 2)]
                                                 [i (inc i)]))]
      (if changed? (recur state) state))))

(defn- pancake [state n]
  (letfn [(flip [state end]
            (reduce (fn [state i] (exchange state i (- end i)))
                    state (range (quot (inc end) 2))))]
    (reduce (fn [state size]
              (let [[state largest]
                    (reduce (fn [[state candidate] j]
                              (let [[state order] (compare-at state j candidate)]
                                [state (if (pos? order) j candidate)]))
                            [state 0] (range 1 size))
                    state (if (= largest (dec size))
                            state
                            (-> state (flip largest) (flip (dec size))))]
                (mark state [(dec size)])))
            state (range n 1 -1))))

(defn- bitonic [state n]
  ;; Generalized bitonic merge uses the greatest power of two below the run
  ;; length, so no sentinel bars or power-of-two input restriction is needed.
  (letfn [(merge-run [state lo length ascending?]
            (if (<= length 1)
              state
              (let [gap (loop [k 1] (if (< (* 2 k) length) (recur (* 2 k)) k))
                    state (reduce (fn [state i]
                                    (let [[state order] (compare-at state i (+ i gap))]
                                      (if (if ascending? (pos? order) (neg? order))
                                        (exchange state i (+ i gap))
                                        state)))
                                  state (range lo (+ lo (- length gap))))]
                (-> state
                    (merge-run lo gap ascending?)
                    (merge-run (+ lo gap) (- length gap) ascending?)))))
          (sort-run [state lo length ascending?]
            (if (<= length 1)
              state
              (let [half (quot length 2)]
                (-> state
                    (sort-run lo half (not ascending?))
                    (sort-run (+ lo half) (- length half) ascending?)
                    (merge-run lo length ascending?)))))]
    (sort-run state 0 n true)))

(defn- radix [state n]
  (if (zero? n)
    state
    (let [minimum (reduce min (:values state))
          maximum (- (reduce max (:values state)) minimum)]
      (loop [state state place 1]
        (if (<= place maximum)
          (let [[state buckets]
                (reduce (fn [[state buckets] [i value]]
                          (let [digit (mod (js/Math.floor (/ (- value minimum) place)) 10)]
                            [(capture state [i] (str "Place value in digit bucket " digit))
                             (update buckets digit conj value)]))
                        [state (vec (repeat 10 []))] (map-indexed vector (:values state)))
                state (reduce (fn [state [i value]]
                                (write-value state i value (str "Collect digit buckets · place " place)))
                              state (map-indexed vector (mapcat identity buckets)))]
            (recur state (* place 10)))
          state)))))

(defn- shell [state n]
  (loop [state state gap (quot n 2)]
    (if (pos? gap)
      (recur (insertion-gap state n gap) (quot gap 2))
      state)))

(defn- comb [state n]
  (loop [state state previous-gap n]
    (let [gap (max 1 (js/Math.floor (/ previous-gap 1.3)))
          [state changed?] (ascending-pass state (map #(vector % (+ % gap)) (range (- n gap))))]
      (if (or (> gap 1) changed?) (recur state gap) state))))

(defn- random-index [seed bound]
  (let [seed (mod (+ (* seed 1664525) 1013904223) 4294967296)]
    [seed (js/Math.floor (* (/ seed 4294967296) bound))]))

(defn- ordered [state n]
  (loop [state state i 1]
    (if (>= i n)
      [state true]
      (let [[state order] (compare-at state (dec i) i)]
        (if (pos? order) [state false] (recur state (inc i)))))))

(defn- bogo [state n seed]
  (if (> n 8)
    [state {:status :limited :message "Bogo sort is limited to 8 values. Reduce the array size to run it."}]
    (loop [state state seed seed attempt 0]
      (let [[state ordered?] (ordered state n)]
        (cond
          ordered? [state {:status :complete}]
          (= attempt bogo-attempt-limit)
          [state {:status :limited :message "Stopped after 256 shuffles. Bogo sort has no guaranteed completion time."}]
          :else
          (let [[state seed] (reduce (fn [[state seed] i]
                                       (let [[seed j] (random-index seed (inc i))]
                                         [(exchange state i j) seed]))
                                     [state seed] (range (dec n) 0 -1))]
            (recur (capture state [] (str "Shuffle attempt " (inc attempt)))
                   seed (inc attempt))))))))

(defn generate-trace
  "Return a deterministic, replayable sort trace. Every algorithm transforms
  persistent recording states without mutation. Counts include all operations,
  even if a long trace is sampled to max-frames snapshots. :sorted indices mark
  permanent final positions. Radix requires safe integers with a safe range.
  Invalid input or an unknown algorithm throws ex-info. Bogo limits are explicit."
  [algorithm-id values]
  (when-not (contains? algorithm-info algorithm-id)
    (throw (ex-info "Unknown sorting algorithm" {:algorithm algorithm-id})))
  (when-not (every? #(and (number? %) (js/Number.isFinite %)) values)
    (throw (ex-info "Sorting values must be finite numbers" {})))
  (let [state (recorder values) n (count values)
        state (update state :frames conj (snapshot state [] "Ready to sort"))
        radix-valid? (or (not= algorithm-id :radix)
                        (and (every? #(js/Number.isSafeInteger %) values)
                             (or (empty? values)
                                 (js/Number.isSafeInteger (- (reduce max values) (reduce min values))))))
        [state result] (cond
                         (not radix-valid?)
                         [state {:status :limited :message "Radix sort requires safe integers within a safe integer range."}]
                         (= algorithm-id :bogo) (bogo state n 42)
                         :else [(case algorithm-id
                                  :quick (quick state n)
                                  :merge (merge-sort state n)
                                  :heap (heap state n)
                                  :bubble (bubble state n)
                                  :selection (selection state n)
                                  :insertion (insertion-gap state n 1)
                                  :gnome (gnome state n)
                                  :shaker (shaker state n)
                                  :odd-even (odd-even state n)
                                  :pancake (pancake state n)
                                  :bitonic (bitonic state n)
                                  :radix (radix state n)
                                  :shell (shell state n)
                                  :comb (comb state n))
                                {:status :complete}])
        complete? (= :complete (:status result))
        state (if complete? (assoc state :sorted (vec (range n))) state)
        final-frame (snapshot state [] (if complete? "Sorting complete" (:message result)))]
    (assoc result :frames (conj (:frames state) final-frame))))
