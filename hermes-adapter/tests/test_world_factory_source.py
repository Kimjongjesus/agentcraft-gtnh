"""Factory telemetry source against the mock telemetry server: token handling, HTTP hardening."""

import logging
import os
import stat
import tempfile
import time
import unittest
from pathlib import Path

from hermes_adapter.sources import factory
from hermes_adapter.sources.factory import ConfigError, FactorySource, TelemetryError
from hermes_adapter.sources.plugin import load_plugin

from factory_fixture import TOKEN, MockTelemetry, capture


class TokenAndUrlConfigTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def write(self, text, mode=0o600):
        p = self.tmp / f"token-{len(list(self.tmp.iterdir()))}.txt"
        p.write_text(text)
        os.chmod(p, mode)
        return p

    def test_token_file_first_non_comment_line(self):
        p = self.write("# comment line that is long enough to look like a token\n\n  " + TOKEN + "  \nsecond\n")
        self.assertEqual(factory.read_token_file(p), TOKEN)

    def test_token_file_must_be_private(self):
        p = self.write(TOKEN + "\n", 0o644)
        with self.assertRaisesRegex(ConfigError, "chmod 600"):
            factory.read_token_file(p)

    def test_short_missing_and_injection_tokens_refused(self):
        with self.assertRaisesRegex(ConfigError, "shorter"):
            factory.check_token("short")
        with self.assertRaises(ConfigError):
            factory.check_token("a" * 20 + "\r\nX-Evil: 1")
        with self.assertRaises(ConfigError):
            factory.check_token("a" * 20 + " b")
        with self.assertRaisesRegex(ConfigError, "not found"):
            factory.read_token_file(self.tmp / "missing.txt")
        with self.assertRaisesRegex(ConfigError, "no token line"):
            factory.read_token_file(self.write("# only a comment\n"))

    def test_url_rules(self):
        self.assertEqual(factory.check_url("http://127.0.0.1:25580")[:3], ("http", "127.0.0.1", 25580))
        self.assertTrue(factory.check_url("http://[::1]:25580/")[4])
        for bad, why in (("ftp://127.0.0.1/", "http"), ("http://user:pw@127.0.0.1/", "credentials"),
                         ("http://127.0.0.1/?a=1", "query"), ("http://192.0.2.10:25580", "loopback"), ("http://", "host")):
            with self.assertRaisesRegex(ConfigError, why, msg=bad):
                factory.check_url(bad)
        self.assertFalse(factory.check_url("http://192.0.2.10:25580", allow_remote=True)[4])

    def test_resolve_token_order_and_env(self):
        p = self.write(TOKEN + "\n")
        old = {k: os.environ.pop(k, None) for k in (factory.ENV_TOKEN, factory.ENV_TOKEN_FILE)}
        try:
            with self.assertRaisesRegex(ConfigError, "no factory token"):
                factory.resolve_token({})
            os.environ[factory.ENV_TOKEN_FILE] = str(p)
            self.assertEqual(factory.resolve_token({}), TOKEN)
            del os.environ[factory.ENV_TOKEN_FILE]
            os.environ[factory.ENV_TOKEN] = "e" * 24
            self.assertEqual(factory.resolve_token({}), "e" * 24)
            self.assertEqual(factory.resolve_token({"token_file": p}), TOKEN)
        finally:
            for k, v in old.items():
                os.environ.pop(k, None)
                if v is not None:
                    os.environ[k] = v

    def test_repr_and_logs_never_show_the_token(self):
        with self.assertLogs("hermes_adapter.world.factory", level="WARNING") as cm:
            src = FactorySource("http://192.0.2.10:25580", TOKEN, allow_remote=True)
        self.assertNotIn(TOKEN, repr(src))
        self.assertNotIn(TOKEN, "\n".join(cm.output))
        self.assertNotIn("192.0.2.10", "\n".join(cm.output), "logs name loopback/remote, not the host")
        self.assertIn("clear text", "\n".join(cm.output))


class MockServerTest(unittest.TestCase):
    def setUp(self):
        self.mock = MockTelemetry()
        self.src = FactorySource(self.mock.url, TOKEN, interval=10, request_timeout=2)

    def tearDown(self):
        self.mock.close()

    def test_collect_sends_bearer_and_fetches_capture_only_when_new(self):
        self.mock.capture = capture(seq=1)
        out = self.src.collect()
        self.assertEqual(out["capture"]["captureSequence"], 1)
        self.assertTrue(all(auth == f"Bearer {TOKEN}" for _, auth in self.mock.requests))
        self.assertEqual(self.mock.telemetry_requests(), 1)
        out = self.src.collect()
        self.assertNotIn("capture", out, "same captureSequence: health only")
        self.assertEqual(self.mock.telemetry_requests(), 1)
        self.mock.capture = capture(seq=2)
        self.assertEqual(self.src.collect()["capture"]["captureSequence"], 2)
        self.assertEqual({p for p, _ in self.mock.requests}, {"/health", "/telemetry/capture"}, "GET-only, two routes")

    def test_no_capture_yet_is_health_only(self):
        out = self.src.collect()
        self.assertNotIn("capture", out)
        self.assertIsNone(out["health"]["lastCaptureMillis"])

    def test_wrong_token_is_unauthorized(self):
        src = FactorySource(self.mock.url, "w" * 32)
        with self.assertRaises(TelemetryError) as cm:
            src.collect()
        self.assertEqual((cm.exception.detail, cm.exception.status), ("unauthorized", 401))

    def test_failure_modes_have_short_fixed_reasons(self):
        self.mock.capture = capture()
        for mode, detail in (("busy", "busy"), ("huge", "response too large"), ("badjson", "bad json"),
                             ("deep", "bad json"), ("v1", "unsupported telemetry protocol")):
            self.mock.mode = mode
            self.src.reset()
            with self.assertRaises(TelemetryError, msg=mode) as cm:
                self.src.collect()
            self.assertEqual(cm.exception.detail, detail, mode)
        self.mock.capture = None
        self.mock.mode = "ok"
        self.src.reset()
        self.mock.health_extra = {"lastCaptureMillis": 5, "captureSequence": 9}
        with self.assertRaises(TelemetryError) as cm:
            self.src.collect()
        self.assertEqual(cm.exception.detail, "no capture yet")

    def test_redirect_is_refused_and_the_token_never_reaches_the_target(self):
        other = MockTelemetry(token="o" * 32)
        try:
            self.mock.mode = "redirect"
            self.mock.redirect_to = other.url + "/health"
            with self.assertRaises(TelemetryError) as cm:
                self.src.collect()
            self.assertEqual(cm.exception.detail, "redirect refused")
            self.assertEqual(other.requests, [], "redirect target must never be contacted")
        finally:
            other.close()

    def test_unreachable_and_timeout(self):
        port = self.mock.server.server_address[1]
        self.mock.close()
        src = FactorySource(f"http://127.0.0.1:{port}", TOKEN, request_timeout=1)
        with self.assertRaises(TelemetryError) as cm:
            src.collect()
        self.assertEqual(cm.exception.detail, "unreachable")
        self.mock = MockTelemetry()
        self.mock.mode = "slow"
        src = FactorySource(self.mock.url, TOKEN, request_timeout=1)
        t0 = time.monotonic()
        with self.assertRaises(TelemetryError):
            src.collect()
        self.assertLess(time.monotonic() - t0, 2.5, "request timeout bounds a hung server")

    def test_streamed_body_is_capped_without_content_length(self):
        self.mock.capture = capture()
        self.mock.mode = "chunked"
        with self.assertRaises(TelemetryError) as cm:
            self.src.collect()
        self.assertEqual(cm.exception.detail, "response too large")

    def test_a_dripping_server_hits_the_total_deadline(self):
        self.mock.capture = capture()
        self.mock.mode = "drip"
        src = FactorySource(self.mock.url, TOKEN, request_timeout=1)
        t0 = time.monotonic()
        with self.assertRaises(TelemetryError) as cm:
            src.collect()
        self.assertEqual(cm.exception.detail, "timeout")
        self.assertLess(time.monotonic() - t0, 2.5, "a body sent byte by byte cannot hold the poll")

    def test_error_details_never_carry_response_bodies(self):
        self.mock.capture = capture()
        self.mock.mode = "badjson"
        with self.assertRaises(TelemetryError) as cm:
            self.src.collect()
        self.assertNotIn("ai-factory", str(cm.exception))


class PluginShapeTest(unittest.TestCase):
    def test_factory_module_loads_through_the_plugin_loader(self):
        mock = MockTelemetry()
        try:
            srcs = load_plugin("hermes_adapter.sources.factory", {"url": mock.url, "token": TOKEN, "interval": 12})
            self.assertEqual(len(srcs), 1)
            self.assertEqual((srcs[0].id, srcs[0].interval), ("factory", 12.0))
            self.assertTrue(callable(srcs[0].collect))
            self.assertGreaterEqual(srcs[0].timeout, srcs[0].request_timeout)
            fast = load_plugin("hermes_adapter.sources.factory", {"url": mock.url, "token": TOKEN, "interval": 1})
            self.assertEqual(fast[0].interval, factory.MIN_INTERVAL)
        finally:
            mock.close()

    def test_bad_plugins_are_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "plug.py"
            p.write_text("def create_sources(config):\n    return [object()]\n")
            with self.assertRaisesRegex(ValueError, "lacks an id"):
                load_plugin(str(p))
            q = Path(d) / "none.py"
            q.write_text("X = 1\n")
            with self.assertRaisesRegex(ValueError, "create_sources"):
                load_plugin(str(q))


if __name__ == "__main__":
    unittest.main()
