# Sort Visualizer — ClojureScript

A sorting algorithm playground rewritten in ClojureScript from [Myphz/sortvisualizer](https://github.com/Myphz/sortvisualizer). Watch fifteen algorithms sort the same kind of data, inspect their behavior and complexity, and write your own sorting routine in the browser.

The application is a static site built with ClojureScript, Reagent, React, and shadow-cljs. Sorting runs in a Web Worker and produces an operation trace that the interface can play back. Custom code runs through [SCI](https://github.com/babashka/sci).

[Open the hosted sorting lab](https://sort-visualizer-clojurescript.ultimatedbz.chatgpt.site). It is public and requires no sign-in.

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

Custom programs receive an immutable state value and return the next state. The editor evaluates ClojureScript-compatible SCI forms; it does not compile arbitrary ClojureScript namespaces or support browser interop. Each operation returns a new value, so retain and pass its result to the next operation.

| Function | Behavior |
| --- | --- |
| `(size state)` | Return the number of values without changing the state. |
| `(value state i)` | Read the value at zero-based index `i`. |
| `(compare-at state i j)` | Return `[next-state order]`, recording a comparison. `order` is negative, zero, or positive. |
| `(swap-at state i j)` | Return a state with the two positions exchanged and a new frame. |
| `(write-at state i number)` | Return a state with a finite value at the given position and a new frame. |
| `(snapshot state [i j] "note")` | Return a state with a frame highlighting the supplied indices. |

The program must evaluate to a function of state. For example:

```clojure
(fn [state]
  (reduce
    (fn [state i]
      (let [[state smallest]
            (reduce
              (fn [[state best] j]
                (let [[state order] (compare-at state j best)]
                  [state (if (neg? order) j best)]))
              [state i]
              (range (inc i) (size state)))]
        (swap-at state i smallest)))
    state
    (range (size state))))
```

You can also define a named function with `defn` and leave its name as the final expression. Do not invoke it yourself; the worker supplies the initial state.

On completion, the app validates the returned state and trace, ascending order, and preservation of every original value, including duplicates. Invalid indices, malformed code, and incomplete sorts produce errors. State transitions carry their operation budget explicitly; pure `size` and `value` reads consume no hidden counter. A worker timeout also stops programs that loop without producing a result. Code executes locally in the browser; there is no code-submission service.

The former implicit-state API (`compare!`, `swap!`, `write!`, `snapshot!`) has been replaced. The stored original example upgrades automatically. Edited programs are preserved and receive migration guidance: accept `state`, pass it to each operation, and return the resulting state. Use `reduce` or `loop` to carry state instead of relying on `doseq` side effects.

The ClojureScript reference tab shows ordinary functions over vectors. Those examples return sorted vectors; adapt them to the instrumented API above to animate them in the custom editor.

## Source map

| Path | Purpose |
| --- | --- |
| `src/sort_visualizer/core.cljs` | UI event handlers and browser effect boundaries. |
| `src/sort_visualizer/playback.cljs` | Pure playback transitions and seeded input generation. |
| `src/sort_visualizer/algorithms.cljs` | Fifteen pure sorting algorithms and immutable trace construction. |
| `src/sort_visualizer/custom.cljs` | Pure custom-sort operations, SCI bindings, and result validation. |
| `src/sort_visualizer/worker.cljs` | Browser worker entry point. |
| `src/sort_visualizer/catalog.cljs` | Educational descriptions and ClojureScript examples. |
| `src/sort_visualizer/references.cljs` | Original multilingual reference snippets. |
| `test/sort_visualizer/` | Algorithm, interpreter, and educational example tests. |
| `docs/PORTING.md` | Feature parity and intentional differences from the original. |

Sorting, trace recording, input generation, and playback calculations operate on persistent maps and vectors. Mutable application state is committed at UI event boundaries; browser effects such as workers, timers, audio, rendering, and local storage remain explicitly effectful. An exclamation suffix identifies those effectful handlers, not ordinary algorithm transformations.

## Attribution

Based on [Sort Visualizer by Myphz](https://github.com/Myphz/sortvisualizer), copyright © 2021 Daniel, under the [MIT license](LICENSE). The original multilingual reference examples are preserved as educational source material; they are displayed as text and never executed by this site. The application logic, ClojureScript examples, and interface have been rewritten for this project.
