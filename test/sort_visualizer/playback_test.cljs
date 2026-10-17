(ns sort-visualizer.playback-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [sort-visualizer.playback :as playback]))

(def ready
  {:algorithm :quick :count 8 :distribution "random" :values [8 7 6 5 4 3 2 1]
   :language "ClojureScript" :frames nil :index 0 :playing? false :generating? false
   :request-id 0 :clock nil :speed 10 :code "example"})

(def trace
  {:status :complete :message "Sorted."
   :frames [{:values [3 2 1] :comparisons 0 :writes 0}
            {:values [2 3 1] :comparisons 1 :writes 2}
            {:values [2 1 3] :comparisons 2 :writes 4}
            {:values [1 2 3] :comparisons 3 :writes 6}]})

(defn loaded [mode]
  (let [state (playback/prepare ready)]
    (playback/accept-trace state (:request-id state) trace mode 1000)))

(deftest generated-inputs-are-repeatable
  (doseq [distribution ["random" "reversed" "nearly" "few"]
          n [0 1 8 48 128]]
    (let [values (playback/make-values n distribution 42)]
      (is (= values (playback/make-values n distribution 42)))
      (is (= n (count values)))
      (if (= "few" distribution)
        (is (every? #{8 16 24 32 40 48} values))
        (is (= (vec (range 1 (inc n))) (vec (sort values)))))))
  (is (not= (playback/make-values 48 "random" 42)
            (playback/make-values 48 "random" 43)))
  (is (= [4 3 2 1] (playback/make-values 4 "reversed" 42))))

(deftest transitions-preserve-prior-states
  (let [before (loaded :play)
        original (pr-str before)
        advanced (playback/tick before 1100)
        stepped (playback/step before)
        regenerated (playback/regenerate before 12)]
    (is (= original (pr-str before)))
    (is (= 0 (:index before)))
    (is (= 1 (:index advanced) (:index stepped)))
    (is (:playing? advanced))
    (is (false? (:playing? stepped)))
    (is (nil? (:frames regenerated)))
    (is (= (:frames trace) (:frames before)))))

(deftest pause-resume-does-not-count-time-spent-paused
  (let [playing (loaded :play)
        fractional (playback/tick playing 1050)
        paused (playback/pause fractional)
        resumed (playback/play paused 9000)]
    (is (= 0 (:index fractional)))
    (is (= 0.5 (get-in fractional [:clock :credit])))
    (is (= paused (playback/tick paused 8000)))
    (is (nil? (:clock paused)))
    (is (= 0 (:index (playback/tick resumed 9050))))
    (is (= 1 (:index (playback/tick resumed 9100))))))

(deftest ticks-accumulate-fractions-and-stop-at-the-end
  (let [playing (loaded :play)
        first-tick (playback/tick playing 1050)
        next-tick (playback/tick first-tick 1100)
        ended (playback/tick next-tick 3000)]
    (is (= 1 (:index next-tick)))
    (is (= 3 (:index ended)))
    (is (false? (:playing? ended)))
    (is (nil? (:clock ended)))
    (is (= ended (playback/tick ended 4000)))
    (is (= [1 2 3] (:values (playback/current-frame ended))))
    (let [replayed (playback/play ended 5000)]
      (is (= 0 (:index replayed)))
      (is (:playing? replayed))
      (is (= 1 (:index (playback/tick replayed 5100)))))))

(deftest step-restart-and-scrub-pause-and-clamp
  (let [playing (loaded :play)
        stepped (playback/step playing)
        end (playback/scrub playing 99)
        restarted (playback/restart end)]
    (is (= 1 (:index stepped)))
    (is (false? (:playing? stepped)))
    (is (nil? (:clock stepped)))
    (is (= 3 (:index end)))
    (is (= 3 (:index (playback/step end))))
    (is (false? (:playing? end)))
    (is (= 0 (:index (playback/scrub playing -1))))
    (is (= 0 (:index restarted)))
    (is (false? (:playing? restarted)))
    (is (= (:frames trace) (:frames restarted)))
    (is (= (:values ready) (:values (playback/current-frame ready)))))
  (let [prepared (playback/prepare ready)]
    (is (= prepared (playback/step prepared)))
    (is (= prepared (playback/play prepared 1000)))))

(deftest canceled-and-completed-requests-ignore-late-results
  (let [first-request (playback/prepare ready)
        id (:request-id first-request)
        canceled (playback/clear-trace first-request)
        replacement (playback/prepare canceled)
        complete (playback/accept-trace first-request id trace :play 1000)]
    (is (playback/pending? first-request id))
    (is (not (playback/pending? canceled id)))
    (doseq [state [canceled replacement complete]]
      (is (= state (playback/accept-trace state id trace :step 2000)))
      (is (= state (playback/reject-trace state id "Late error"))))
    (is (not= id (:request-id replacement)))
    (let [failed (playback/reject-trace first-request id "Worker failed")]
      (is (= "Worker failed" (:error failed)))
      (is (false? (:generating? failed)))
      (is (= failed (playback/accept-trace failed id trace :play 2000))))))

(deftest trace-result-controls-starting-mode-and-validity
  (is (= 1 (:index (loaded :step))))
  (is (false? (:playing? (loaded :step))))
  (let [prepared (playback/prepare ready)
        id (:request-id prepared)]
    (doseq [frames [nil [] [(first (:frames trace))]]]
      (let [state (playback/accept-trace prepared id (assoc trace :frames frames) :play 1000)]
        (is (= 0 (:index state)))
        (is (false? (:playing? state)))
        (is (= state (playback/play state 2000)))))
    (let [invalid (playback/accept-trace prepared id (assoc trace :status "invalid" :message "Not sorted") :play 1000)]
      (is (= "Not sorted" (:error invalid)))
      (is (false? (:playing? invalid)))
      (is (= invalid (playback/play invalid 2000))))))

(deftest changing-inputs-and-code-invalidates-work
  (let [pending (playback/prepare ready)
        changed (playback/regenerate pending 42 {:count 12 :distribution "reversed"})
        bogo (playback/select-algorithm changed :bogo 42)
        quick (playback/select-algorithm (assoc bogo :count 2) :quick 42)
        custom (assoc pending :algorithm :custom)]
    (is (= (vec (range 12 0 -1)) (:values changed)))
    (is (false? (:generating? changed)))
    (is (not (playback/pending? changed (:request-id pending))))
    (is (= 8 (:count bogo) (count (:values bogo))))
    (is (= 8 (:count quick) (count (:values quick))))
    (is (false? (:generating? (playback/update-code custom "new code"))))
    (is (= "new code" (:code (playback/update-code custom "new code"))))
    (is (:generating? (playback/update-code pending "new code")))))
