# Clojure Advent of Code setup

Create one independent Clojure project for a year/day and download its input once.
The setup script needs Python 3.10+ and uses only the standard library. Running the
generated project requires Java 17+ and the
[official Clojure CLI](https://clojure.org/guides/install_clojure); setup reports
missing prerequisites and does not install system software.

From the repository root, after setting the `AOC_SESSION` environment variable:

```sh
python3 advent-of-code/setup_aoc.py 2024 1
cd advent-of-code/workspaces/2024/day01
clojure -M:run
clojure -M:test
clojure -M
```

`AOC_SESSION` is the value of the `session` cookie from your signed-in
[Advent of Code](https://adventofcode.com/) browser session. Supply it using your
shell's secret input or environment manager; do not put the token in a command,
source file, or Git. On Bash, `read -rs AOC_SESSION; export AOC_SESSION` reads it
without echoing it or putting it in shell history. Unset it with
`unset AOC_SESSION` after setup. No other configuration is required.

Use `--directory /path/to/project` to select a different project directory. The
default is `workspaces/YEAR/dayDD` beside the script, independent of the current
working directory. Years 2015–2024 accept days 1–25; events from 2025 accept days
1–12 under the currently published schedule. A puzzle must already be released
(December's corresponding day at 00:00 EST, UTC−5). Future puzzles are rejected
without making a request. If the event schedule changes, update `validate_puzzle`.

The generated project contains:

```text
deps.edn                     Clojure 1.12.0, run and test aliases
src/aoc/day.clj               Pure parse-input, part-1, and part-2 stubs; I/O in -main
test/aoc/day_test.clj         A synthetic parsing test; add solution tests here
test/aoc/test_runner.clj      clojure.test runner with failing exit status
resources/input.txt          Private cached input, ignored by Git
.aoc-workspace.json          Year/day identity to prevent mixing puzzles
.gitignore                   Input, temporary downloads, secrets, and editor caches
README.md                    Commands and the puzzle's URL
```

The first Clojure command resolves its pinned Maven dependencies. The generated
test uses synthetic data and does not solve the puzzle. Open the linked puzzle in
your browser, edit `src/aoc/day.clj`, and add your own tests. Both parts initially
return `nil`. The program reads the original input bytes as UTF-8; downloading
does not trim trailing newlines.

Reruns preserve your solution, tests, `deps.edn`, and input. Only missing scaffold
files are created; a final block of required ignore patterns is kept at the end of
`.gitignore`, overriding earlier negations without removing existing rules.
A cached rerun needs neither a session token nor network
access. Use one account per workspace: changing `AOC_SESSION` does **not** replace
cached input. Choose a separate directory for another account. Empty or invalid
cache paths require manual inspection; they never trigger an automatic refetch.

## Respectful automation

This follows the [community automation guidance](https://www.reddit.com/r/adventofcode/wiki/faqs/automation/):

- Input is cached at `resources/input.txt`, and subsequent runs make zero requests.
- A conservative 15-minute interval between uncached requests is persisted in
  `$XDG_CACHE_HOME/clojure-aoc-setup/request-state.json` (default
  `~/.cache/clojure-aoc-setup`). A directory lock serializes separate processes.
  A recent request causes a clear error; the script never sleeps, loops, polls,
  submits answers, or retries automatically. This interval is this tool's policy,
  not a claim that the site requires exactly 15 minutes for input downloads.
- Every request identifies this repository and its maintainer's issue tracker
  in `User-Agent`, following the [AoC creator's request](https://www.reddit.com/r/adventofcode/comments/z9dhtd/).
  If you fork and maintain this script, change `USER_AGENT` to your contact URL.
- HTTP errors leave the cooldown active; a longer `Retry-After` response extends
  it. Authentication errors, redirects, and non-input responses are reported
  without logging the cookie or response body. No redirect is followed. The
  cookie is sent only to `https://adventofcode.com/YEAR/day/DAY/input`.
- Downloads are written atomically with private file permissions. The token is
  never written to disk. Inputs and puzzle text are not committed or included in
  fixtures, in line with the [official FAQ](https://adventofcode.com/about).

If a process was forcibly killed, its empty `request.lock` directory may remain.
Confirm that no setup process is running before removing the directory; keep the
cooldown state file. After a failed download, the editable scaffold remains, so
you can begin working and rerun later to fetch the input. To repair a genuinely
corrupt input, inspect it and remove only that input file before a deliberate
rerun after the cooldown.

## Verify without an account

```sh
python3 -m unittest discover -s advent-of-code/tests -v
```

Tests mock HTTP responses and time, use synthetic input, and never need a token
or access Advent of Code. They cover caching, idempotence, cooldown persistence,
concurrent-request refusal, cookie/redirect handling, failures, and release dates.
The project layout and aliases follow the [Clojure CLI guide](https://clojure.org/guides/deps_and_cli).
