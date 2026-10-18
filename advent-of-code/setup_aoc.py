#!/usr/bin/env python3
"""Create a Clojure puzzle workspace and cache one Advent of Code input."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from http.client import HTTPException
import json
import math
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


EST = timezone(timedelta(hours=-5))
COOLDOWN_SECONDS = 15 * 60
MAX_INPUT_BYTES = 10 * 1024 * 1024
USER_AGENT = (
    "github.com/ultimatedbz/sortVisualizerClojureScript/advent-of-code "
    "by ultimatedbz (contact: "
    "https://github.com/ultimatedbz/sortVisualizerClojureScript/issues)"
)
IGNORE_LINES = (
    "/resources/input.txt", "/resources/.input-*", "/.cpcache/", "/.clj-kondo/",
    "/.lsp/", "/.env", "/.env.*",
)


class SetupError(Exception):
    """An actionable error that never contains a session token or response body."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward credentials, even to another path on the same host.
        return None


def validate_puzzle(year: int, day: int, now: datetime) -> datetime:
    """Validate the published schedule without making an HTTP request."""
    if now.tzinfo is None:
        raise ValueError("now must include a timezone")
    if not 2015 <= year <= now.astimezone(EST).year:
        raise SetupError("Year must be from 2015 through the current year.")
    # The event shortened to twelve days in 2025.
    last_day = 25 if year < 2025 else 12
    if not 1 <= day <= last_day:
        raise SetupError(f"Day must be from 1 through {last_day} for {year}.")
    unlock = datetime(year, 12, day, tzinfo=EST)
    if now < unlock:
        raise SetupError(
            f"Puzzle is not released yet (unlocks {unlock.isoformat()}). "
            "Run this command after release; it does not poll."
        )
    return unlock


def validate_session(session: str | None) -> str:
    if not session:
        raise SetupError("Set AOC_SESSION to your Advent of Code session cookie value.")
    if not re.fullmatch(r"[A-Za-z0-9._~-]+", session):
        raise SetupError("AOC_SESSION must contain only the cookie value, without quotes or 'session='.")
    return session


def cache_directory(environ: dict[str, str]) -> Path:
    base = environ.get("XDG_CACHE_HOME")
    return (Path(base).expanduser() if base else Path.home() / ".cache") / "clojure-aoc-setup"


def retry_after_timestamp(value: str | None, now: float) -> float:
    """Convert either Retry-After format to an absolute time; ignore bad values."""
    if not value:
        return now
    try:
        if value.strip().isdigit():
            return now + int(value.strip())
        date = parsedate_to_datetime(value)
        if date.tzinfo is None:
            return now
        return max(now, date.timestamp())
    except (ValueError, TypeError, OverflowError):
        return now


@contextmanager
def request_lock(cache: Path):
    """Serialize requests across separate invocations; fail instead of polling."""
    cache.mkdir(parents=True, exist_ok=True)
    lock = cache / "request.lock"
    try:
        lock.mkdir()
    except FileExistsError:
        raise SetupError(
            f"Another setup request holds {lock}. If an earlier process was killed, "
            "confirm it has stopped and remove that empty directory."
        ) from None
    try:
        yield
    finally:
        lock.rmdir()


def read_next_request(cache: Path) -> float:
    path = cache / "request-state.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))["next_request_at"]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError
        return float(value)
    except FileNotFoundError:
        return 0
    except (KeyError, TypeError, ValueError):
        raise SetupError(f"Invalid cooldown state in {path}; inspect it before retrying.") from None


def write_next_request(cache: Path, timestamp: float) -> None:
    # Atomic metadata replacement also keeps an interruption from losing the cooldown.
    with tempfile.NamedTemporaryFile(mode="w", dir=cache, delete=False, encoding="utf-8") as handle:
        temporary = Path(handle.name)
        json.dump({"next_request_at": timestamp}, handle)
    try:
        temporary.replace(cache / "request-state.json")
    finally:
        temporary.unlink(missing_ok=True)


def validate_input(body: bytes, content_type: str) -> bytes:
    if not body or len(body) > MAX_INPUT_BYTES:
        raise SetupError("Input response was empty or larger than the 10 MiB limit; nothing cached.")
    if content_type.split(";", 1)[0].strip().lower() != "text/plain":
        raise SetupError("Server did not return plain-text puzzle input; check your session cookie.")
    if body.lstrip().lower().startswith((b"<!doctype html", b"<html")):
        raise SetupError("Server returned an HTML page instead of puzzle input; nothing cached.")
    try:
        body.decode("utf-8")
    except UnicodeDecodeError:
        raise SetupError("Server returned non-UTF-8 input; nothing cached.") from None
    return body


def fetch_input(year: int, day: int, session: str, cache: Path, *,
                opener=None, clock=time.time) -> bytes:
    """Make at most one request. A persisted cooldown applies even after errors."""
    with request_lock(cache):
        now = clock()
        next_request = read_next_request(cache)
        if now < next_request:
            remaining = math.ceil(next_request - now)
            raise SetupError(f"Request cooldown active; retry in at least {remaining} seconds. No request sent.")
        next_request = now + COOLDOWN_SECONDS
        write_next_request(cache, next_request)
        request = Request(
            f"https://adventofcode.com/{year}/day/{day}/input",
            headers={"User-Agent": USER_AGENT, "Accept": "text/plain"},
        )
        request.add_unredirected_header("Cookie", f"session={session}")
        try:
            with (opener or build_opener(NoRedirect())).open(request, timeout=30) as response:
                if response.status != 200:
                    raise SetupError(f"Unexpected HTTP {response.status}; no input cached.")
                body = response.read(MAX_INPUT_BYTES + 1)
                return validate_input(body, response.headers.get("Content-Type", ""))
        except HTTPError as error:
            deferred = retry_after_timestamp((error.headers or {}).get("Retry-After"), clock())
            write_next_request(cache, max(next_request, deferred))
            code = error.code
            error.close()
            if code in (400, 401, 403):
                raise SetupError("Authentication failed; refresh AOC_SESSION from your signed-in browser.") from None
            if 300 <= code < 400:
                raise SetupError("Server redirected the request; no redirect was followed. Check AOC_SESSION.") from None
            if code == 404:
                raise SetupError("Puzzle input was not found; check the year, day, and release date.") from None
            if code == 429:
                raise SetupError("Advent of Code rate-limited the request. Retry-After was saved; no automatic retry.") from None
            raise SetupError(f"Advent of Code returned HTTP {code}; no automatic retry or input cached.") from None
        except (URLError, TimeoutError, OSError, HTTPException):
            raise SetupError("Input download failed. Check your connection and retry after the cooldown.") from None


def write_new(path: Path, content: str) -> bool:
    """Only create missing files, preserving edited solutions and configuration."""
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
        return True
    except FileExistsError:
        if not path.is_file() or path.is_symlink():
            raise SetupError(f"Expected a regular file at {path}.") from None
        return False


def ensure_gitignore(project: Path) -> None:
    path = project / ".gitignore"
    if path.is_symlink():
        raise SetupError(f"Refusing to edit symbolic link {path}.")
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    # Git uses the last matching rule, so an earlier pattern cannot protect an
    # input if a later user rule negates it. Keep our complete block at the end.
    block = "# Managed by setup_aoc.py: private inputs and local files.\n" + "\n".join(IGNORE_LINES) + "\n"
    if not existing.endswith(block):
        with path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(("\n" if existing and not existing.endswith("\n") else "") + block)


def templates(year: int, day: int) -> dict[str, str]:
    """Pure project description: filenames and contents, without puzzle material."""
    return {
        "deps.edn": '''{:paths ["src" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}}
 :aliases {:run {:main-opts ["-m" "aoc.day"]}
           :test {:extra-paths ["test"]
                  :main-opts ["-m" "aoc.test-runner"]}}}
''',
        "src/aoc/day.clj": f'''(ns aoc.day
  "Advent of Code {year}, day {day}. https://adventofcode.com/{year}/day/{day}"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse-input [text]
  ;; Replace this with the representation your solution needs.
  (str/split-lines text))

(defn part-1 [input]
  ;; TODO: Return your answer without changing input.
  nil)

(defn part-2 [input]
  ;; TODO: Return your answer without changing input.
  nil)

(defn -main [& _]
  (let [resource (or (io/resource "input.txt")
                     (throw (ex-info "Missing resources/input.txt; rerun setup." {{}})))
        input (parse-input (slurp resource :encoding "UTF-8"))]
    (println "Part 1:" (part-1 input))
    (println "Part 2:" (part-2 input))))
''',
        "test/aoc/day_test.clj": '''(ns aoc.day-test
  (:require [clojure.test :refer [deftest is]]
            [aoc.day :as day]))

(deftest parse-input-test
  ;; Synthetic data, not a copied puzzle example or personal input.
  (is (= ["alpha" "beta"] (day/parse-input "alpha\\nbeta\\n"))))

;; Add tests for your solution here.
''',
        "test/aoc/test_runner.clj": '''(ns aoc.test-runner
  (:require [clojure.test :as test]
            [aoc.day-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'aoc.day-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
''',
        "README.md": f'''# Advent of Code {year}, day {day}

[Read the puzzle](https://adventofcode.com/{year}/day/{day}) in your browser.

Install Java 17+ and the [official Clojure CLI](https://clojure.org/guides/install_clojure).
Then run these commands from this directory:

```sh
clojure -M:run   # Run both parts (initially nil until implemented).
clojure -M:test  # Run the local clojure.test suite.
clojure -M      # Open a REPL.
```

In the REPL: `(require '[aoc.day :as day] :reload)`.
Edit `src/aoc/day.clj`; keep transformations pure and input reading in `-main`.
Clojure CLI downloads the pinned Clojure dependency on first use.

`resources/input.txt` is your local input cache. It is ignored by Git; do not
publish it or puzzle text. Re-running setup preserves this input, your code,
tests, and deps.edn. The session cookie is never saved in this project.
''',
    }


def scaffold(project: Path, year: int, day: int) -> None:
    project.mkdir(parents=True, exist_ok=True)
    identity = project / ".aoc-workspace.json"
    expected = {"year": year, "day": day}
    if not identity.exists():
        if (project / "resources" / "input.txt").exists():
            raise SetupError("This directory contains input without workspace metadata; use another directory to avoid mixing puzzles.")
        write_new(identity, json.dumps(expected) + "\n")
    # Another process may have won exclusive creation between exists() and
    # write_new(). Validate the actual file before touching templates or input.
    try:
        if identity.is_symlink() or json.loads(identity.read_text(encoding="utf-8")) != expected:
            raise ValueError
    except (ValueError, OSError):
        raise SetupError("This directory belongs to a different puzzle or has invalid workspace metadata; use another directory.") from None
    ensure_gitignore(project)
    for relative, content in templates(year, day).items():
        write_new(project / relative, content)
    (project / "resources").mkdir(exist_ok=True)


def cache_input(path: Path, body: bytes) -> None:
    # A temporary file plus link prevents partial files and never replaces an input.
    with tempfile.NamedTemporaryFile(prefix=".input-", dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(body)
    try:
        os.link(temporary, path)
    except FileExistsError:
        # A concurrent run completed this project; preserve its cached input.
        pass
    finally:
        temporary.unlink(missing_ok=True)


def setup(year: int, day: int, project: Path, environ: dict[str, str], *,
          now: datetime | None = None, opener=None, clock=time.time) -> bool:
    validate_puzzle(year, day, now or datetime.now(timezone.utc))
    scaffold(project, year, day)
    input_path = project / "resources" / "input.txt"
    if input_path.exists() or input_path.is_symlink():
        if input_path.is_symlink() or not input_path.is_file() or input_path.stat().st_size == 0:
            raise SetupError(f"Cached input at {input_path} must be a nonempty regular file; inspect it manually.")
        return False
    session = validate_session(environ.get("AOC_SESSION"))
    body = fetch_input(year, day, session, cache_directory(environ), opener=opener, clock=clock)
    cache_input(input_path, body)
    return True


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("year", type=int)
    parser.add_argument("day", type=int)
    parser.add_argument("--directory", type=Path, help="Exact project directory (default: workspaces/YEAR/dayDD beside this script)")
    args = parser.parse_args(argv)
    project = (args.directory or Path(__file__).resolve().parent / "workspaces" / str(args.year) / f"day{args.day:02d}").expanduser().resolve()
    try:
        downloaded = setup(args.year, args.day, project, dict(os.environ))
    except (SetupError, OSError) as error:
        print(f"Setup failed: {error}", file=sys.stderr)
        return 1
    print(f"Clojure workspace ready: {project}")
    print("Downloaded and cached your input." if downloaded else "Reused cached input; no request sent.")
    print("From that directory: clojure -M:run | clojure -M:test | clojure -M")
    if not shutil.which("clojure") or not shutil.which("java"):
        print("Prerequisite missing: install Java 17+ and Clojure CLI: https://clojure.org/guides/install_clojure")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
