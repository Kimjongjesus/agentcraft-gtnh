"""Card 5a hardening: fuzzed ops input, scan caps, work off the event loop, stalled clients."""

import asyncio
import json
import random
import re
import string
import threading
import time
import unittest
from types import SimpleNamespace
from unittest import mock

from hermes_adapter import ops
from hermes_adapter.mapping import Mapper
from hermes_adapter.ops import (ALERT_STATES, JOB_STATUSES, KINDS, MAX, PLURAL, SERVICE_STATES, SEVERITIES, TEXT,
                                OpsHub, normalize_batch)
from hermes_adapter.server import AdapterServer

ID_RE = re.compile(r"^[a-z0-9._-]{1,24}/[a-z0-9._-]{1,64}$")
LIMITS = {
    "service": {"name": TEXT["name"], "group": TEXT["group"], "detail": TEXT["detail"]},
    "job": {"name": TEXT["name"], "schedule": TEXT["schedule"], "detail": TEXT["detail"]},
    "usage": {"provider": TEXT["provider"], "window": TEXT["window"], "detail": TEXT["detail"]},
    "alert": {"title": TEXT["title"], "detail": TEXT["alert_detail"], "source": TEXT["alert_source"]},
}
FIELDS = {
    "service": {"id", "sourceId", "name", "group", "state", "since", "detail", "cpu", "mem", "disk"},
    "job": {"id", "sourceId", "name", "lastStatus", "enabled", "schedule", "lastRun", "nextRun", "durationMs", "detail"},
    "usage": {"id", "sourceId", "provider", "window", "remainingPct", "resetsAt", "detail"},
    "alert": {"id", "sourceId", "ts", "severity", "source", "title", "state", "detail", "resolvedAt"},
}


def junk(rng: random.Random, depth: int = 0):
    """Arbitrary JSON-ish value: strings with unicode/control chars/addresses/tokens, numbers, nesting."""
    pick = rng.randrange(12 if depth < 2 else 8)
    if pick == 0:
        return None
    if pick == 1:
        return rng.choice([True, False])
    if pick == 2:
        return rng.choice([0, -1, 1, 10 ** 20, -10 ** 20, 1_791_000_000, 1_791_000_000_000, 99.95, float("nan"), float("inf")])
    if pick == 3:
        return rng.choice(["up", "DOWN", "Healthy", "warning", "ok", "failed", "running", "critical", "resolved", "open", ""])
    if pick == 4:
        return rng.choice(["2026-10-06T00:00:00Z", "2026-13-40T99:99:99", "yesterday", "1791000000", "1e309"])
    if pick in (5, 6, 7):
        alphabet = string.printable + "\u00e9\u4e2d\U0001F600\x00\x07\u202e"
        s = "".join(rng.choice(alphabet) for _ in range(rng.randrange(0, 300)))
        extras = ["192.0.2.55", "2001:db8::1", "https://u:p@192.0.2.9/x?token=abc", "sk-" + "a" * 30, "personal-x.md"]
        return s + (" " + rng.choice(extras) if rng.random() < 0.3 else "")
    if pick in (8, 9):
        return [junk(rng, depth + 1) for _ in range(rng.randrange(0, 4))]
    keys = ["id", "name", "group", "state", "since", "detail", "cpu", "mem", "disk", "schedule", "lastRun", "nextRun",
            "lastStatus", "durationMs", "enabled", "provider", "window", "remainingPct", "resetsAt", "ts", "severity",
            "source", "title", "resolvedAt", "extra", "__proto__"]
    return {rng.choice(keys): junk(rng, depth + 1) for _ in range(rng.randrange(0, 12))}


class FuzzTest(unittest.TestCase):
    def check_entity(self, kind, e):
        self.assertTrue(set(e) <= FIELDS[kind], set(e) - FIELDS[kind])
        self.assertRegex(e["id"], ID_RE)
        for f, lim in LIMITS[kind].items():
            if f in e:
                self.assertIsInstance(e[f], str)
                self.assertLessEqual(len(e[f]), lim)
                self.assertTrue(e[f].strip())
                self.assertNotRegex(e[f], r"[\x00-\x08\x0b-\x1f\x7f]")
        for f in ("cpu", "mem", "disk", "remainingPct"):
            if f in e:
                self.assertTrue(0.0 <= e[f] <= 100.0)
        for f in ("since", "lastRun", "nextRun", "resetsAt", "ts", "resolvedAt"):
            if f in e:
                self.assertIsInstance(e[f], int)
                self.assertTrue(946684800000 <= e[f] <= 32503680000000)
        if kind == "service":
            self.assertIn(e["state"], SERVICE_STATES)
        if kind == "job":
            self.assertIn(e["lastStatus"], JOB_STATUSES)
            self.assertIsInstance(e["enabled"], bool)
        if kind == "alert":
            self.assertIn(e["severity"], SEVERITIES)
            self.assertIn(e["state"], ALERT_STATES)
        blob = json.dumps(e, allow_nan=False)  # never NaN/Infinity on the wire
        for leak in ("192.0.2.55", "2001:db8::1", "u:p@", "sk-aaaa", "personal-x"):
            self.assertNotIn(leak, blob)

    def test_random_batches(self):
        rng = random.Random(5150)
        for _ in range(400):
            raw = {PLURAL[k]: [junk(rng) for _ in range(rng.randrange(0, 8))] for k in KINDS if rng.random() < 0.8}
            if rng.random() < 0.2:
                raw["warning"] = junk(rng)
            batch = normalize_batch(raw, "fz")
            for kind in KINDS:
                ids = [e["id"] for e in batch.entities[kind]]
                self.assertEqual(len(ids), len(set(ids)))
                for e in batch.entities[kind]:
                    self.check_entity(kind, e)
            self.assertLessEqual(len(batch.warning), TEXT["source_detail"])

    def test_wrong_container_types_raise_value_error_only(self):
        rng = random.Random(7)
        for _ in range(200):
            raw = junk(rng)
            try:
                normalize_batch(raw, "fz")
            except ValueError:
                pass


class ScanCapTest(unittest.TestCase):
    def test_runaway_list_is_cut_before_normalizing(self):
        calls = []
        real = ops.normalize_service

        def counting(raw, sid, ids):
            calls.append(1)
            return real(raw, sid, ids)

        items = [{"id": f"s{i}", "name": f"service-{i}", "state": "up"} for i in range(20_000)]
        with mock.patch.dict(ops.NORMALIZE, {"service": counting}):
            batch = normalize_batch({"services": items}, "big")
        self.assertEqual(len(calls), MAX["service"] * ops.SCAN_FACTOR)
        self.assertEqual(batch.rejected, 20_000 - MAX["service"] * 2)
        self.assertEqual(len(batch.entities["service"]), MAX["service"] * 2)


class OffLoopTest(unittest.TestCase):
    def test_collect_and_normalize_run_in_the_worker_thread(self):
        threads = []
        real = ops.normalize_batch

        def spy(raw, sid):
            threads.append(threading.current_thread().name)
            return real(raw, sid)

        class Src:
            id = "w"
            interval = 10

            def collect(self):
                threads.append(threading.current_thread().name)
                return {"services": [{"id": "a", "name": "a", "state": "up"}]}

        hub = OpsHub([Src()])
        with mock.patch.object(ops, "normalize_batch", spy):
            asyncio.run(hub.collect_all())
        hub.close()
        self.assertEqual(len(threads), 2)
        self.assertTrue(all(t.startswith("ops-collect") for t in threads), threads)
        self.assertEqual(hub.model()["services"][0]["id"], "w/a")


class _FakeWriter:
    def __init__(self):
        self.aborted = False
        self.transport = self

    def abort(self):
        self.aborted = True


class _FakeConn:
    def __init__(self, hang: bool):
        self.request = SimpleNamespace(peer="192.0.2.9")
        self.writer = _FakeWriter()
        self.closed = False
        self.hang = hang
        self.sent: list[str] = []

    async def send_text(self, text: str) -> None:
        if self.hang:
            await asyncio.sleep(3600)
        self.sent.append(text)

    async def ping(self) -> None:
        if self.hang:
            await asyncio.sleep(3600)


class StalledClientTest(unittest.TestCase):
    def test_a_stalled_client_is_dropped_and_the_rest_keep_getting_data(self):
        srv = AdapterServer(lambda: None, Mapper(), send_timeout=0.2)
        stuck, ok = _FakeConn(True), _FakeConn(False)
        for s in (srv.clients, srv.hello, srv.ops_clients):
            s.update({stuck, ok})
        msgs = [{"type": "ops.remove", "kind": "service", "id": f"x/{i}"} for i in range(5)]

        async def go():
            t0 = time.monotonic()
            await srv.broadcast_ops(msgs)
            await srv.broadcast([{"type": "feed.add", "item": {}}])
            return time.monotonic() - t0

        elapsed = asyncio.run(go())
        self.assertLess(elapsed, 1.5)  # one timeout, not one per message
        self.assertTrue(stuck.writer.aborted and stuck.closed)
        for s in (srv.clients, srv.hello, srv.ops_clients):
            self.assertNotIn(stuck, s)
            self.assertIn(ok, s)
        self.assertEqual(len(ok.sent), 6)

    def test_ping_loop_drops_a_stalled_client(self):
        srv = AdapterServer(lambda: None, Mapper(), send_timeout=0.1, ping_interval=0.05)
        stuck, ok = _FakeConn(True), _FakeConn(False)
        srv.clients.update({stuck, ok})

        async def go():
            task = asyncio.create_task(srv.ping_loop())
            await asyncio.sleep(0.5)
            task.cancel()

        asyncio.run(go())
        self.assertNotIn(stuck, srv.clients)
        self.assertIn(ok, srv.clients)


if __name__ == "__main__":
    unittest.main()
