# Sort Visualizer — ClojureScript

A sorting algorithm playground rewritten in ClojureScript from [Myphz/sortvisualizer](https://github.com/Myphz/sortvisualizer). Watch fifteen algorithms sort the same kind of data, inspect their behavior and complexity, and write your own sorting routine in the browser.

The application is a static site built with ClojureScript, Reagent, React, and shadow-cljs. Sorting runs in a Web Worker and produces an operation trace that the interface can play back. Custom code runs through [SCI](https://github.com/babashka/sci).

[Open the hosted sorting lab](https://sort-visualizer-clojurescript.ultimatedbz.chatgpt.site) (owner-private, requires your sign-in).

## Explore

- Quick, merge, heap, bubble, selection, insertion, gnome, shaker, odd-even, pancake, bitonic, radix, shell, comb, and bogo sort.
- Adjustable array size and playback speed, shuffling, sound, and operation counters.
- Algorithm explanations, complexity summaries, and complete ClojureScript reference functions.
- The original C, C++, Java, JavaScript, and Python reference snippets, retained with attribution.
- A custom ClojureScript editor with an instrumented sorting API and result validation.

The visualizer uses small arrays so individual operations remain readable. Bogo sort is deliberately bounded; reaching its limit is an incomplete experiment, not a successful sort. Complexity labels describe conventional implementations of each algorithm, excluding animation trace storage and persistent-vector allocations in educational examples. Shell and comb sort performance depends on the gap sequence.

## Run locally

Install **Node.js 20 or newer** and a **JDK 21 or newer**. The launcher uses `JAVA_HOME`, a standard Java installation, or a supported Homebrew OpenJDK installation. Maven dependencies are downloaded on the first build.

```sh
git clone https://github.com/ultimatedbz/sortVisualizerClojureScript.git
cd sortVisualizerClojureScript
npm install
npm run dev
```

Open [localhost:8080](http://localhost:8080). Development builds reload when ClojureScript source changes.

```sh
npm test          # Compile and run the ClojureScript tests
npm run build    # Create a production static site in dist/
npm run preview  # Serve dist/ at http://127.0.0.1:4173
```

Deploy the contents of `dist/` to a static web host. No Python server, database, credentials, or backend service is required.

## Write a custom sort

Custom programs operate on the current array through six supplied functions. The editor evaluates ClojureScript-compatible SCI forms; it does not compile arbitrary ClojureScript namespaces or support browser interop.

| Function | Behavior |
| --- | --- |
| `(size)` | Return the number of values. |
| `(value i)` | Read the value at zero-based index `i`. |
| `(compare! i j)` | Compare two values, record a frame, and return a negative number, zero, or a positive number. |
| `(swap! i j)` | Swap two positions and record a frame. This editor binding is an array operation, not the usual atom function. |
| `(write! i number)` | Write a finite number and record a frame. |
| `(snapshot! [i j] "note")` | Record a frame highlighting the supplied indices with a short explanation. |

Define a function and call it at the end of the program:

```clojure
(defn sort! []
  (doseq [i (range (size))]
    (let [smallest
          (reduce (fn [best j]
                    (if (neg? (compare! j best)) j best))
                  i
                  (range (inc i) (size)))]
      (when (not= i smallest)
        (swap! i smallest)))))

(sort!)
```

On completion, the app checks ascending order and preservation of every original value, including duplicates. Invalid indices, malformed code, and incomplete sorts produce errors. A worker timeout and operation limits stop runaway programs. Code executes locally in the browser; there is no code-submission service.

The ClojureScript reference tab shows ordinary functions over vectors. Those examples return sorted vectors; adapt them to the instrumented API above to animate them in the custom editor.

## Source map

| Path | Purpose |
| --- | --- |
| `src/sort_visualizer/core.cljs` | Reagent UI, playback, and worker coordination. |
| `src/sort_visualizer/algorithms.cljs` | Fifteen instrumented sorting algorithms. |
| `src/sort_visualizer/custom.cljs` | SCI bindings, bounds, and result validation. |
| `src/sort_visualizer/worker.cljs` | Browser worker entry point. |
| `src/sort_visualizer/catalog.cljs` | Educational descriptions and ClojureScript examples. |
| `src/sort_visualizer/references.cljs` | Original multilingual reference snippets. |
| `test/sort_visualizer/` | Algorithm, interpreter, and educational example tests. |
| `docs/PORTING.md` | Feature parity and intentional differences from the original. |

## Attribution

Based on [Sort Visualizer by Myphz](https://github.com/Myphz/sortvisualizer), copyright © 2021 Daniel, under the [MIT license](LICENSE). The original multilingual reference examples are preserved as educational source material; they are displayed as text and never executed by this site. The application logic, ClojureScript examples, and interface have been rewritten for this project.
