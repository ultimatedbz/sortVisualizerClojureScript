(ns sort-visualizer.playback
  "Pure input generation and playback transitions. Clocks and seeds are inputs."
  (:require [sort-visualizer.references :as references]))

(defn next-seed [seed]
  ;; Park–Miller's product stays within JavaScript's exact integer range.
  (mod (* 16807 (max 1 (mod seed 2147483647))) 2147483647))

(defn make-values [n distribution seed]
  (let [ordered (vec (range 1 (inc n)))]
    (case distribution
      "reversed" (vec (reverse ordered))
      "nearly" (loop [values ordered seed seed remaining (max 2 (quot n 6))]
                 (if (or (zero? n) (zero? remaining))
                   values
                   (let [seed (next-seed seed) i (mod seed n) j (min (dec n) (inc i))]
                     (recur (assoc values i (values j) j (values i)) seed (dec remaining)))))
      "few" (loop [values [] seed seed]
              (if (= n (count values))
                values
                (let [seed (next-seed seed)]
                  (recur (conj values (* 8 (inc (mod seed 6)))) seed))))
      (loop [values ordered seed seed i (dec n)]
        (if (<= i 0)
          values
          (let [seed (next-seed seed) j (mod seed (inc i))]
            (recur (assoc values i (values j) j (values i)) seed (dec i))))))))

(defn clear-trace [state]
  (-> state
      (update :request-id (fnil inc 0))
      (assoc :frames nil :index 0 :playing? false :generating? false :clock nil
             :error nil :result-status nil :result-message nil)))

(defn regenerate
  ([state seed] (regenerate state seed {}))
  ([state seed settings]
   (let [state (merge (clear-trace state) settings)]
     (assoc state :values (make-values (:count state) (:distribution state) seed)))))

(defn select-algorithm [state id seed]
  (let [n (if (= id :bogo) (min 8 (:count state)) (max 8 (:count state)))
        language (:language state)]
    (regenerate state seed
                {:algorithm id :count n
                 :language (if (or (= language "ClojureScript")
                                   (get-in references/codes [id language]))
                             language "ClojureScript")})))

(defn update-code [state code]
  (assoc (if (= :custom (:algorithm state)) (clear-trace state) state) :code code))

(defn prepare [state]
  (assoc (clear-trace state) :generating? true))

(defn pending? [state id]
  (and (:generating? state) (= id (:request-id state))))

(defn final-index [state]
  (max 0 (dec (count (:frames state)))))

(defn current-frame [state]
  (or (get (:frames state) (:index state))
      {:values (:values state) :active [] :sorted [] :comparisons 0 :writes 0
       :note "Choose an algorithm, then press Start sort."}))

(defn accept-trace [state id result mode now]
  (if-not (pending? state id)
    state
    (let [frames (when (seq (:frames result)) (:frames result))
          status (keyword (:status result))
          last-index (max 0 (dec (count frames)))
          playing? (and (= mode :play) (pos? last-index) (not= :invalid status))]
      (assoc state :frames frames :index (if (= mode :step) (min 1 last-index) 0)
             :generating? false :playing? playing?
             :clock (when playing? {:last now :credit 0})
             :error (when (= :invalid status) (:message result))
             :result-status status :result-message (:message result)))))

(defn reject-trace [state id message]
  (if (pending? state id)
    (assoc state :generating? false :playing? false :clock nil :error message)
    state))

(defn pause [state]
  (assoc state :playing? false :clock nil))

(defn play [state now]
  (if (and (not (:generating? state)) (pos? (final-index state))
           (not= :invalid (:result-status state)))
    (assoc state :playing? true :clock {:last now :credit 0}
           :index (if (= (:index state) (final-index state)) 0 (:index state)))
    state))

(defn step [state]
  (if (:generating? state)
    state
    (assoc (pause state) :index (min (final-index state) (inc (:index state))))))

(defn restart [state]
  (assoc (pause state) :index 0))

(defn scrub [state index]
  (assoc (pause state) :index (max 0 (min (final-index state) index))))

(defn tick [state now]
  (if-not (:playing? state)
    state
    (let [{:keys [last credit]} (:clock state)
          credit (+ (or credit 0) (* (/ (max 0 (- now (or last now))) 1000) (:speed state)))
          steps (js/Math.floor credit)
          index (min (final-index state) (+ (:index state) steps))
          playing? (< index (final-index state))]
      (assoc state :index index :playing? playing?
             :clock (when playing? {:last now :credit (- credit steps)})))))
