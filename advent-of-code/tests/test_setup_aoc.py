"""No real cookies, puzzle material, or network calls are used by these tests."""

from datetime import datetime, timezone
from email.message import Message
from email.utils import format_datetime
from http.client import IncompleteRead, RemoteDisconnected
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError


SPEC = importlib.util.spec_from_file_location("setup_aoc", Path(__file__).resolve().parents[1] / "setup_aoc.py")
aoc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(aoc)

NOW = datetime(2026, 10, 17, tzinfo=timezone.utc)
TIMESTAMP = NOW.timestamp()
SYNTHETIC = b"alpha\nbeta\n"


class Response(io.BytesIO):
    def __init__(self, body=SYNTHETIC, content_type="text/plain; charset=utf-8", status=200):
        super().__init__(body)
        self.status = status
        self.headers = {"Content-Type": content_type}


def http_error(code, retry_after=None):
    headers = Message()
    if retry_after:
        headers["Retry-After"] = retry_after
    return HTTPError("https://adventofcode.com/2024/day/1/input", code,
                     "synthetic failure", headers, io.BytesIO(b"not logged"))


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / "project"
        self.cache = self.root / "cache" / "clojure-aoc-setup"
        self.environment = {"AOC_SESSION": "synthetic-cookie", "XDG_CACHE_HOME": str(self.root / "cache")}
        self.opener = Mock()
        self.opener.open.return_value = Response()

    def setup(self, **overrides):
        options = {"now": NOW, "opener": self.opener, "clock": lambda: TIMESTAMP}
        options.update(overrides)
        return aoc.setup(2024, 1, self.project, self.environment, **options)

    def test_creates_project_and_downloads_once(self):
        self.assertTrue(self.setup())
        self.assertEqual(SYNTHETIC, (self.project / "resources/input.txt").read_bytes())
        self.assertEqual(1, self.opener.open.call_count)
        self.assertEqual({"year": 2024, "day": 1}, json.loads((self.project / ".aoc-workspace.json").read_text()))
        self.assertIn('"1.12.0"', (self.project / "deps.edn").read_text())
        self.assertIn("aoc.test-runner", (self.project / "deps.edn").read_text())
        self.assertEqual(0o600, (self.project / "resources/input.txt").stat().st_mode & 0o777)
        self.assertEqual([], list((self.project / "resources").glob(".input-*")))

    def test_request_scope_cookie_contact_and_timeout(self):
        self.setup()
        args, kwargs = self.opener.open.call_args
        request = args[0]
        self.assertEqual("https://adventofcode.com/2024/day/1/input", request.full_url)
        self.assertEqual("GET", request.get_method())
        self.assertEqual("session=synthetic-cookie", request.unredirected_hdrs["Cookie"])
        self.assertNotIn("Cookie", request.headers)
        self.assertIn("/issues", request.get_header("User-agent"))
        self.assertEqual(30, kwargs["timeout"])
        self.assertIsNone(aoc.NoRedirect().redirect_request(request, None, 302, "", {}, "https://example.com/"))

    def test_cached_rerun_preserves_edits_and_needs_no_token(self):
        self.setup()
        edited = {"src/aoc/day.clj": "; user solution\n", "deps.edn": "{:paths [\"custom\"]}\n",
                  "test/aoc/day_test.clj": "; user tests\n"}
        for name, text in edited.items():
            (self.project / name).write_text(text)
        (self.project / ".gitignore").write_text("/my-files/\n")
        self.environment.pop("AOC_SESSION")
        self.assertFalse(self.setup())
        self.assertEqual(1, self.opener.open.call_count)
        for name, text in edited.items():
            self.assertEqual(text, (self.project / name).read_text())
        ignored = (self.project / ".gitignore").read_text()
        self.assertIn("/my-files/", ignored)
        self.assertIn("/resources/input.txt", ignored)

    def test_changed_token_still_uses_existing_workspace_input(self):
        self.setup()
        self.environment["AOC_SESSION"] = "another-synthetic-cookie"
        self.assertFalse(self.setup())
        self.assertEqual(1, self.opener.open.call_count)

    def test_ignore_rules_follow_negations_and_are_idempotent(self):
        self.project.mkdir()
        path = self.project / ".gitignore"
        original = "/my-files/\n/resources/input.txt\n!/resources/input.txt\n"
        path.write_text(original)
        aoc.ensure_gitignore(self.project)
        protected = path.read_text()
        self.assertTrue(protected.startswith(original))
        self.assertEqual(list(aoc.IGNORE_LINES), protected.splitlines()[-len(aoc.IGNORE_LINES):])
        aoc.ensure_gitignore(self.project)
        self.assertEqual(protected, path.read_text())

    def test_generated_runner_explicitly_decodes_utf8(self):
        self.assertIn('(slurp resource :encoding "UTF-8")', aoc.templates(2024, 1)["src/aoc/day.clj"])

    def test_no_session_value_is_saved_anywhere(self):
        self.setup()
        for path in self.root.rglob("*"):
            if path.is_file():
                self.assertNotIn(b"synthetic-cookie", path.read_bytes())

    def test_release_boundaries_and_schedule(self):
        before = datetime(2025, 12, 1, 4, 59, 59, tzinfo=timezone.utc)
        at = datetime(2025, 12, 1, 5, tzinfo=timezone.utc)
        with self.assertRaisesRegex(aoc.SetupError, "not released"):
            aoc.validate_puzzle(2025, 1, before)
        self.assertEqual(at, aoc.validate_puzzle(2025, 1, at))
        aoc.validate_puzzle(2024, 25, NOW)
        aoc.validate_puzzle(2025, 12, NOW)
        for year, day in [(2014, 1), (2027, 1), (2025, 13), (2024, 0), (2024, 26), (2026, 1)]:
            with self.subTest(year=year, day=day), self.assertRaises(aoc.SetupError):
                aoc.validate_puzzle(year, day, NOW)

    def test_unreleased_puzzle_does_not_scaffold_or_request(self):
        with self.assertRaisesRegex(aoc.SetupError, "not released"):
            aoc.setup(2026, 1, self.project, self.environment, now=NOW, opener=self.opener)
        self.assertFalse(self.project.exists())
        self.opener.open.assert_not_called()

    def test_missing_or_malformed_token_does_not_make_request(self):
        for session in [None, "", "session=secret", "secret;other=oops", "secret\r\nInjected: true"]:
            with self.subTest(session=session):
                with self.assertRaises(aoc.SetupError) as caught:
                    aoc.validate_session(session)
                self.assertNotIn("secret", str(caught.exception))
        self.environment.pop("AOC_SESSION")
        with self.assertRaisesRegex(aoc.SetupError, "Set AOC_SESSION"):
            self.setup()
        self.opener.open.assert_not_called()
        self.assertTrue((self.project / "src/aoc/day.clj").is_file())

    def test_cooldown_persists_across_projects_and_does_not_sleep(self):
        self.setup()
        with self.assertRaisesRegex(aoc.SetupError, "900 seconds"):
            aoc.setup(2024, 2, self.root / "second", self.environment,
                      now=NOW, opener=self.opener, clock=lambda: TIMESTAMP)
        self.assertEqual(1, self.opener.open.call_count)
        self.opener.open.return_value = Response()
        self.assertTrue(aoc.setup(2024, 2, self.root / "second", self.environment,
                                 now=NOW, opener=self.opener, clock=lambda: TIMESTAMP + 900))
        self.assertEqual(2, self.opener.open.call_count)

    def test_parallel_request_is_refused_and_lock_is_released(self):
        with aoc.request_lock(self.cache):
            with self.assertRaisesRegex(aoc.SetupError, "Another setup request"):
                self.setup()
        self.opener.open.assert_not_called()
        self.assertFalse((self.cache / "request.lock").exists())

    def test_http_errors_do_not_cache_or_retry_and_keep_cooldown(self):
        for status in [302, 400, 401, 403, 404, 429, 500, 503]:
            with self.subTest(status=status):
                cache = self.root / str(status)
                opener = Mock()
                opener.open.side_effect = http_error(status)
                with self.assertRaises(aoc.SetupError):
                    aoc.fetch_input(2024, 1, "synthetic-cookie", cache,
                                    opener=opener, clock=lambda: TIMESTAMP)
                self.assertEqual(1, opener.open.call_count)
                self.assertEqual(TIMESTAMP + 900, aoc.read_next_request(cache))
                self.assertFalse((cache / "request.lock").exists())

    def test_retry_after_seconds_and_http_date_are_respected(self):
        for retry in ["3600", format_datetime(datetime.fromtimestamp(TIMESTAMP + 3600, timezone.utc))]:
            with self.subTest(retry=retry):
                if (self.cache / "request-state.json").exists():
                    (self.cache / "request-state.json").unlink()
                self.opener.open.side_effect = http_error(429, retry)
                with self.assertRaisesRegex(aoc.SetupError, "Retry-After"):
                    self.setup()
                self.assertEqual(TIMESTAMP + 3600, aoc.read_next_request(self.cache))
                self.assertFalse((self.project / "resources/input.txt").exists())

    def test_bad_retry_after_is_ignored(self):
        for value in [None, "", "nonsense", "-5"]:
            self.assertEqual(TIMESTAMP, aoc.retry_after_timestamp(value, TIMESTAMP))

    def test_network_error_does_not_echo_unsafe_details(self):
        self.opener.open.side_effect = URLError("synthetic-cookie")
        with self.assertRaises(aoc.SetupError) as caught:
            self.setup()
        self.assertNotIn("synthetic-cookie", str(caught.exception))
        self.assertEqual(1, self.opener.open.call_count)
        self.assertEqual(TIMESTAMP + 900, aoc.read_next_request(self.cache))
        self.assertFalse((self.project / "resources/input.txt").exists())

    def test_interrupted_http_responses_are_sanitized(self):
        for number, failure in enumerate([IncompleteRead(b"synthetic-cookie"), RemoteDisconnected("synthetic-cookie")]):
            with self.subTest(failure=type(failure).__name__):
                opener = Mock()
                opener.open.side_effect = failure
                with self.assertRaises(aoc.SetupError) as caught:
                    aoc.fetch_input(2024, 1, "synthetic-cookie", self.root / f"interrupted-{number}",
                                    opener=opener, clock=lambda: TIMESTAMP)
                self.assertNotIn("synthetic-cookie", str(caught.exception))
                self.assertEqual(1, opener.open.call_count)

    def test_non_input_responses_are_never_cached(self):
        cases = [(b"", "text/plain"), (b"<html>login</html>", "text/html"),
                 (b"<html>login</html>", "text/plain"), (b"\xff", "text/plain"),
                 (b"x" * (aoc.MAX_INPUT_BYTES + 1), "text/plain")]
        for number, (body, content_type) in enumerate(cases):
            with self.subTest(number=number):
                opener = Mock()
                opener.open.return_value = Response(body, content_type)
                project = self.root / f"invalid-{number}"
                env = {**self.environment, "XDG_CACHE_HOME": str(self.root / f"cache-{number}")}
                with self.assertRaises(aoc.SetupError):
                    aoc.setup(2024, 1, project, env, now=NOW, opener=opener, clock=lambda: TIMESTAMP)
                self.assertFalse((project / "resources/input.txt").exists())

    def test_metadata_prevents_mixing_puzzles_in_same_directory(self):
        self.setup()
        with self.assertRaisesRegex(aoc.SetupError, "different puzzle"):
            aoc.setup(2024, 2, self.project, self.environment, now=NOW, opener=self.opener)
        self.assertEqual(1, self.opener.open.call_count)

    def test_concurrent_identity_creation_is_revalidated_before_scaffolding(self):
        real_write_new = aoc.write_new

        def competing_write(path, content):
            if path.name == ".aoc-workspace.json":
                # Simulate another process winning creation after exists().
                path.write_text(json.dumps({"year": 2024, "day": 2}) + "\n")
                return False
            return real_write_new(path, content)

        with patch.object(aoc, "write_new", side_effect=competing_write):
            with self.assertRaisesRegex(aoc.SetupError, "different puzzle"):
                self.setup()
        self.opener.open.assert_not_called()
        self.assertFalse((self.project / "src").exists())
        self.assertFalse((self.project / ".gitignore").exists())

    def test_unknown_existing_input_is_not_misidentified(self):
        (self.project / "resources").mkdir(parents=True)
        (self.project / "resources/input.txt").write_bytes(SYNTHETIC)
        with self.assertRaisesRegex(aoc.SetupError, "without workspace metadata"):
            self.setup()
        self.opener.open.assert_not_called()

    def test_empty_cache_requires_manual_repair_without_refetch(self):
        aoc.scaffold(self.project, 2024, 1)
        (self.project / "resources/input.txt").touch()
        with self.assertRaisesRegex(aoc.SetupError, "nonempty regular file"):
            self.setup()
        self.opener.open.assert_not_called()

    def test_cache_publication_never_overwrites_existing_input(self):
        self.project.mkdir()
        target = self.project / "input.txt"
        target.write_bytes(b"already-present\n")
        aoc.cache_input(target, SYNTHETIC)
        self.assertEqual(b"already-present\n", target.read_bytes())
        self.assertEqual([], list(self.project.glob(".input-*")))

    def test_malformed_cooldown_state_does_not_skip_throttling(self):
        self.cache.mkdir(parents=True)
        for contents in ["broken", "{}", '{"next_request_at": "tomorrow"}', '{"next_request_at": NaN}']:
            (self.cache / "request-state.json").write_text(contents)
            with self.subTest(contents=contents), self.assertRaisesRegex(aoc.SetupError, "Invalid cooldown"):
                self.setup()
        self.opener.open.assert_not_called()


if __name__ == "__main__":
    unittest.main()
