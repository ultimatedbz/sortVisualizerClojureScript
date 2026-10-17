# Porting notes

This project rewrites [Myphz/sortvisualizer](https://github.com/Myphz/sortvisualizer) in ClojureScript. The inspected original revision was [`14191645ff254489a5c03689708989bc498e2d69`](https://github.com/Myphz/sortvisualizer/tree/14191645ff254489a5c03689708989bc498e2d69).

The goal is to preserve the sorting playground and educational content while making the result a self-contained static site. It is a functional rewrite, not a line-for-line translation of Flask templates and mutable DOM manipulation.

## Feature mapping

| Original | ClojureScript rewrite |
| --- | --- |
| Fifteen sorting algorithm pages | All fifteen algorithms in one interactive application. |
| Colored bars, shuffle, run/stop, size controls | Instrumented sorting operations displayed as replayable frames with playback controls. |
| Audible feedback | Optional browser-generated sound. |
| Algorithm descriptions and complexity tables | Rewritten explanations, three-step walkthroughs, stability information, and complexity summaries. |
| C, C++, Java, JavaScript, Python implementations | Original snippets retained as reference text, alongside complete new ClojureScript examples. |
| JavaScript custom sort editor with `eval` | ClojureScript-compatible forms evaluated by SCI inside a Web Worker. |
| `getValue`, `swap`, and `updateBox` over DOM objects | `size`, `value`, `compare!`, `swap!`, `write!`, and `snapshot!` over numeric values. |
| Separate custom-function entry point field | A program defines and invokes its own entry function. |
| Flask/Jinja pages and Python server | Reagent/React UI compiled with shadow-cljs and served as static assets. |
| Code submission through Flask, Hastebin, and Telegram | Omitted. Custom programs stay in the browser; no external submission service is used. |

## Execution and state

The original algorithms rearranged DOM elements directly and awaited small delays between mutations. The rewrite separates algorithm execution from rendering. An instrumented algorithm records values, active indices, completion state, and counters in a trace; the UI plays that trace independently.

A worker keeps algorithm evaluation away from the UI thread. This permits playback controls and protects the interface from custom code that fails to finish. Custom programs get a fresh SCI context and a small instrumented API, with operation and frame limits. A completed custom run must be sorted and preserve the input's value frequencies before it is reported as successful.

Complexity labels describe standard sorting algorithms. The visualizer necessarily uses extra storage for recorded frames. The reference ClojureScript examples favor persistent vectors for clarity, so their allocation behavior also differs from conventional in-place array implementations.

## Deliberate behavior changes

- Arrays are limited to a smaller display range than the original 10–1,000-element slider so trace storage and individual steps remain manageable. Bogo sort uses a still smaller maximum.
- Bogo sort has an attempt bound and can finish with an explicit incomplete status. The original could keep shuffling indefinitely.
- The application uses a generalized recursive bitonic merge that supports arbitrary lengths. The educational example presents the classic iterative network, which requires a power-of-two length. The displayed space bound includes the application's recursive stack; the classic iterative network has constant auxiliary space.
- The application supports signed safe integers in radix sort by offsetting digit keys. The simpler educational function accepts non-negative integers, matching the generated visualization data, and rejects other inputs.
- The custom API operates on numbers and indices instead of exposing DOM elements. The original custom JavaScript API and submitted programs are not source-compatible with the ClojureScript editor.
- The reference examples are educational functions, separate from the instrumented execution engine. The original multilingual snippets were retained as historical reference material; they were not modernized or validated as standalone programs.
- The UI is redesigned as an interactive lab. Separate Flask URLs, server-side sessions, submission endpoints, and the old documentation page are replaced by the static application and its inline/API documentation.

## Attribution and extraction

The original MIT copyright notice is retained verbatim in `LICENSE`. The original reference-code objects were extracted as text without evaluating their surrounding JavaScript. JavaScript template escapes and HTML entities were decoded to preserve the intended displayed snippets. There are five language snippets for each of the fifteen algorithms, for 75 original references in total.
