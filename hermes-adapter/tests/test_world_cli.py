"""CLI wiring of the world source: flags, --world-once against the mock server, refusals."""

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path

from hermes_adapter import __main__ as cli
from hermes_adapter import journal as jmod

from factory_fixture import TOKEN, MockTelemetry, capture


def run(argv):
    out, err = io.StringIO(), io.StringIO()
    code = None
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        try:
            code = cli.main(argv)
        except SystemExit as e:
            code = e.code
    return code, out.getvalue(), err.getvalue()


class WorldCliTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.token_file = self.tmp / "token.txt"
        self.token_file.write_text("# telemetry token\n" + TOKEN + "\n")
        os.chmod(self.token_file, 0o600)
        self.mock = MockTelemetry()

    def tearDown(self):
        self.mock.close()

    def test_no_world_flags_means_no_world_source(self):
        args = cli.parse_args([])
        self.assertIsNone(cli.build_world(args))

    def test_world_once_prints_a_world_snapshot(self):
        self.mock.capture = capture(seq=3, players=["PlayerOne"])
        code, out, _ = run(["--factory-url", self.mock.url, "--factory-token-file", str(self.token_file), "--world-once"])
        self.assertEqual(code, 0)
        snap = json.loads(out)
        self.assertEqual((snap["v"], snap["type"], snap["world"]), (1, "world.snapshot", 1))
        self.assertEqual(snap["source"]["state"], "ok")
        self.assertEqual(len(snap["machines"]), 3)
        self.assertNotIn("PlayerOne", out)
        self.assertNotIn(TOKEN, out)
        self.assertEqual(self.mock.telemetry_requests(), 1)
        self.assertFalse(any(p.endswith(".sqlite3") for p in os.listdir(self.tmp)), "--world-once keeps no journal")

    def test_world_once_with_a_wrong_token_fails_cleanly(self):
        self.mock.token = "x" * 32
        code, out, err = run(["--factory-url", self.mock.url, "--factory-token-file", str(self.token_file), "--world-once"])
        self.assertEqual(code, 1)
        snap = json.loads(out)
        self.assertEqual((snap["source"]["state"], snap["source"]["detail"]), ("error", "unauthorized"))
        self.assertNotIn(TOKEN, out + err)

    def test_refusals_are_explained_without_secrets(self):
        loose = self.tmp / "loose.txt"
        loose.write_text(TOKEN + "\n")
        os.chmod(loose, 0o644)
        for argv, why in (
            (["--factory-token-file", str(self.tmp / "missing.txt"), "--world-once"], "not found"),
            (["--factory-token-file", str(loose), "--world-once"], "chmod 600"),
            (["--factory-url", "http://192.0.2.10:25580", "--factory-token-file", str(self.token_file), "--world-once"], "not loopback"),
            (["--factory-url", "http://user:pw@127.0.0.1:1", "--factory-token-file", str(self.token_file), "--world-once"], "credentials"),
        ):
            code, out, err = run(argv)
            self.assertIsInstance(code, str, argv)
            self.assertIn(why, code, argv)
            self.assertNotIn(TOKEN, code + out + err)

    def test_journal_inside_the_checkout_is_refused(self):
        repo = jmod._repo_root()
        if repo is None:
            self.skipTest("not running from a git checkout")
        code, _, _ = run(["--factory-url", self.mock.url, "--factory-token-file", str(self.token_file),
                          "--journal", str(repo / "journal.sqlite3"), "--once"])
        self.assertIsInstance(code, str)
        self.assertIn("git checkout", code)
        self.assertFalse((repo / "journal.sqlite3").exists())

    def test_world_plugin_flag_loads_a_custom_source(self):
        plug = self.tmp / "myplug.py"
        plug.write_text(
            "class S:\n"
            "    id = 'custom'\n"
            "    name = 'custom source'\n"
            "    interval = 10\n"
            "    def collect(self):\n"
            "        return {'health': {'protocol': 'ai-factory/v2', 'lastCaptureMillis': None}}\n"
            "def create_sources(config):\n"
            "    return [S()]\n")
        code, out, _ = run(["--world-plugin", str(plug), "--world-once"])
        self.assertEqual(code, 0)
        snap = json.loads(out)
        self.assertEqual((snap["source"]["id"], snap["source"]["state"]), ("custom", "starting"))


if __name__ == "__main__":
    unittest.main()
