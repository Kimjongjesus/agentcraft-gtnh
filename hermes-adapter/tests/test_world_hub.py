"""World hub: snapshot vs incremental, sessions, source states, journal coupling, privacy on the wire."""

import json
import tempfile
import unittest
from pathlib import Path

from hermes_adapter import world
from hermes_adapter.journal import WorldJournal
from hermes_adapter.sources.factory import FactorySource, TelemetryError
from hermes_adapter.world_hub import UNAUTHORIZED_BACKOFF_S, WorldHub

from factory_fixture import T0, TOKEN, MockTelemetry, capture, machine


class FakeSource:
    id = "factory"
    name = "fake"
    interval = 30.0

    def __init__(self):
        self.next = None  # dict, or an exception to raise

    def collect(self):
        if isinstance(self.next, BaseException):
            raise self.next
        return self.next


def health(c, now=None):
    return {"protocol": "ai-factory/v2", "captureSequence": c["captureSequence"] if c else 0,
            "lastCaptureMillis": c["capturedAtMillis"] if c else None,
            "nowMillis": now if now is not None else ((c or {}).get("capturedAtMillis", T0) + 1000),
            "gregTech": True, "ae2": True}


class Clock:
    def __init__(self, t=T0 / 1000):
        self.t = t

    def __call__(self):
        return self.t


class HubTest(unittest.TestCase):
    def setUp(self):
        self.src = FakeSource()
        self.clock = Clock()
        self.dir = Path(tempfile.mkdtemp())
        self.journal = WorldJournal(self.dir / "j.sqlite3")
        self.hub = WorldHub(self.src, self.journal, clock=self.clock)

    def feed(self, c, now=None):
        self.src.next = {"health": health(c, now), "capture": c} if c else {"health": health(None)}
        self.clock.t += 30
        return self.hub.poll()

    def test_off_without_a_source(self):
        hub = WorldHub(None)
        snap = hub.snapshot_message()
        self.assertEqual(snap["source"]["state"], "off")
        self.assertEqual((snap["machines"], snap["events"]), ([], []))
        self.assertNotIn("base", snap)
        self.assertEqual(hub.poll(), [])
        self.assertFalse(hub.due())

    def test_starting_then_first_capture_is_a_full_snapshot(self):
        msgs = self.feed(None)
        self.assertEqual(self.hub.state, "starting")
        self.assertEqual(msgs[0]["source"]["detail"], "no capture yet")
        msgs = self.feed(capture(seq=1))
        types = [m["type"] for m in msgs]
        self.assertIn("world.snapshot", types)
        snap = next(m for m in msgs if m["type"] == "world.snapshot")
        self.assertEqual(snap["world"], world.VERSION)
        self.assertEqual(len(snap["machines"]), 3)
        self.assertEqual(snap["source"]["state"], "ok")
        self.assertEqual(snap["source"]["telemetry"], "ai-factory/v2")
        self.assertNotIn("PlayerOne", json.dumps(snap))
        self.assertEqual(self.journal.stats()["snapshots"]["rows"], 1)

    def test_incremental_after_the_snapshot(self):
        self.feed(capture(seq=1))
        msgs = self.feed(capture([machine(1, "idle"), machine(2, "idle"), machine(3, "maintenance")], seq=2))
        types = [m["type"] for m in msgs]
        self.assertNotIn("world.snapshot", types)
        self.assertIn("world.machine.upsert", types)
        events = [m["event"] for m in msgs if m["type"] == "world.event"]
        self.assertEqual([e["kind"] for e in events], ["machine.stopped"])
        self.assertTrue(events[0]["id"])
        self.assertEqual(events[0]["ts"], T0 + 60_000)
        self.assertEqual([e["kind"] for e in self.journal.query_events()], ["machine.stopped"])
        self.assertEqual(self.feed(capture([machine(1, "idle"), machine(2, "idle"), machine(3, "maintenance")], seq=2)), [],
                         "the same capture again changes nothing")

    def test_session_change_resends_the_snapshot_with_a_restart_event(self):
        self.feed(capture(seq=5))
        msgs = self.feed(capture(seq=1, session="sess-b", captured_at=T0 + 600_000))
        self.assertEqual(msgs[0]["type"], "world.source.upsert")
        snap = [m for m in msgs if m["type"] == "world.snapshot"]
        self.assertEqual(len(snap), 1)
        self.assertEqual(snap[0]["events"][-1]["kind"], "session.started")
        self.assertFalse([m for m in msgs if m["type"] == "world.event"], "the snapshot already carries the event")
        self.assertEqual({s for s in (self.journal.latest_snapshot()["session"],)}, {"sess-b"})

    def test_power_trend_builds_up_within_a_session(self):
        self.feed(capture(seq=1, ae_stored=100_000.0))
        self.feed(capture(seq=2, ae_stored=130_000.0))
        trend = self.hub.snapshot_message()["power"]["trend"]
        self.assertEqual(trend["samples"], 2)
        self.assertEqual(trend["aeStoredDelta"], 30_000.0)
        self.assertEqual(trend["aeStoredPerMin"], 60_000.0)
        self.feed(capture(seq=1, session="sess-z", ae_stored=5.0))
        self.assertNotIn("trend", self.hub.snapshot_message()["power"], "a new session restarts the trend")

    def test_errors_lost_and_recovered(self):
        self.feed(capture(seq=1))
        self.src.next = TelemetryError("unreachable")
        self.clock.t += 30
        msgs = self.hub.poll()
        self.assertEqual(self.hub.state, "error")
        self.assertEqual(msgs[0]["source"]["detail"], "unreachable")
        self.assertEqual([m["event"]["kind"] for m in msgs if m["type"] == "world.event"], ["source.lost"])
        self.assertEqual(self.hub.poll(), [], "a repeated failure is not re-announced")
        msgs = self.feed(capture(seq=2))
        self.assertEqual(self.hub.state, "ok")
        self.assertIn("source.ok", [m["event"]["kind"] for m in msgs if m["type"] == "world.event"])
        kinds = [e["kind"] for e in self.journal.query_events()]
        self.assertEqual(kinds, ["source.lost", "source.ok"])

    def test_unauthorized_backs_off(self):
        self.src.next = TelemetryError("unauthorized", 401)
        self.hub.poll()
        self.assertEqual(self.hub.next_due, self.clock.t + UNAUTHORIZED_BACKOFF_S)
        self.src.next = TelemetryError("busy", 503)
        self.hub.poll()
        self.assertEqual(self.hub.next_due, self.clock.t + 30)

    def test_plugin_crash_is_contained_and_not_logged_verbatim(self):
        self.src.next = RuntimeError("PlayerOne at 1,2,3 secret detail")
        with self.assertLogs("hermes_adapter.world", level="WARNING") as cm:
            msgs = self.hub.poll()
        self.assertEqual(msgs[0]["source"]["detail"], "collector failed")
        logged = "\n".join(cm.output)
        self.assertNotIn("PlayerOne", logged)
        self.assertNotIn("secret", logged)

    def test_stale_capture(self):
        c = capture(seq=1)
        self.feed(c, now=c["capturedAtMillis"] + 10 * 60_000)
        self.assertEqual(self.hub.state, "stale")
        self.assertIn("10 min old", self.hub.detail)

    def test_journal_failure_does_not_stop_the_feed(self):
        self.journal.close()
        with self.assertLogs("hermes_adapter.world", level="ERROR"):
            msgs = self.feed(capture(seq=1))
        self.assertIn("world.snapshot", [m["type"] for m in msgs])
        self.assertIsNotNone(self.hub.journal_error)
        msgs = self.feed(capture([machine(1, "idle")], seq=2))
        self.assertTrue(msgs)

    def test_logs_never_carry_names_or_coordinates(self):
        with self.assertLogs("hermes_adapter", level="DEBUG") as cm:
            self.feed(capture(seq=1, players=["PlayerOne"]))
            self.feed(capture([machine(1, "idle")], seq=2))
            self.src.next = TelemetryError("unreachable")
            self.hub.poll()
        logged = "\n".join(cm.output)
        for needle in ("PlayerOne", "104,64", "Test Machine", TOKEN):
            self.assertNotIn(needle, logged)


class EndToEndTest(unittest.TestCase):
    """Real FactorySource + mock telemetry HTTP server + journal, as the adapter wires them."""

    def test_mock_server_to_journal(self):
        mock = MockTelemetry()
        tmp = Path(tempfile.mkdtemp())
        try:
            j = WorldJournal(tmp / "journal.sqlite3")
            hub = WorldHub(FactorySource(mock.url, TOKEN, request_timeout=2), j)
            mock.capture = capture(seq=1)
            first = hub.poll()
            self.assertIn("world.snapshot", [m["type"] for m in first])
            mock.capture = capture([machine(1, "idle"), machine(2), machine(3, "maintenance"), machine(4)], seq=2)
            msgs = hub.poll()
            kinds = sorted(m["event"]["kind"] for m in msgs if m["type"] == "world.event")
            self.assertEqual(kinds, ["machine.appeared", "machine.started", "machine.stopped"])
            hub.poll()  # nothing new: health only
            self.assertEqual(mock.telemetry_requests(), 2)
            self.assertEqual(len(j.query_events(kinds=["machine."])), 3)
            self.assertEqual(j.stats()["samples"]["rows"], 2)
            hub.close()
        finally:
            mock.close()


if __name__ == "__main__":
    unittest.main()
