"""Card 5a: ops.* normalization, bounds, privacy and the OpsHub (snapshot + incremental)."""

import asyncio
import json
import threading
import time
import unittest

from hermes_adapter import ops
from hermes_adapter.ops import MAX, OpsHub, normalize_batch, pct, to_ms
from hermes_adapter.redact import WITHHELD, ip_policy

# canaries, assembled at runtime so no source line carries a real-looking secret or LAN address
LAN_IP = "192." + "168." + "77.5"
TOKEN = "sk-" + "ant-api03-" + "CanaryCanaryCanary0123456789"
GH = "gh" + "p_" + "CANARYcanaryCANARY0123456789abcd"
PERSONAL = "see personal-" + "health.md"
CANARIES = (LAN_IP, "10.20.30.40", "2001:db8::77", TOKEN, GH, "hunter2", "health.md", "secret-topic-canary", "owner@example.com")


class FakeClock:
    def __init__(self, t: float = 1_791_000_000.0) -> None:
        self.t = t

    def __call__(self) -> float:
        return self.t


class StaticSource:
    def __init__(self, sid="fake", data=None, interval=10.0, timeout=2.0, name=None):
        self.id = sid
        self.name = name or sid
        self.interval = interval
        self.timeout = timeout
        self.data = data if data is not None else {}
        self.calls = 0
        self.fail: Exception | None = None

    def collect(self):
        self.calls += 1
        if self.fail:
            raise self.fail
        return json.loads(json.dumps(self.data))  # a fresh copy, like a real collector


def run(coro):
    return asyncio.run(coro)


def svc(i, state="up", **kw):
    return {"id": f"s{i}", "name": f"service-{i}", "group": "services", "state": state, **kw}


class ValueHelpersTest(unittest.TestCase):
    def test_to_ms(self):
        self.assertEqual(to_ms(1_791_000_000), 1_791_000_000_000)
        self.assertEqual(to_ms(1_791_000_000_123), 1_791_000_000_123)
        self.assertEqual(to_ms(1_791_000_000.5), 1_791_000_000_500)
        self.assertEqual(to_ms("1791000000"), 1_791_000_000_000)
        self.assertEqual(to_ms("2026-10-06T00:00:00Z"), to_ms("2026-10-06T00:00:00+00:00"))
        self.assertEqual(to_ms("2026-10-06T04:00:00+04:00"), to_ms("2026-10-06T00:00:00+00:00"))
        for bad in (None, True, "", "soon", float("nan"), float("inf"), -5, 10, "3026-01-01T00:00:00+00:00", [1]):
            self.assertIsNone(to_ms(bad), bad)

    def test_pct_and_duration(self):
        self.assertEqual(pct(12.345), 12.3)
        self.assertEqual(pct(-3), 0.0)
        self.assertEqual(pct(250), 100.0)
        self.assertEqual(pct("41"), 41.0)
        for bad in (None, True, "x", float("nan"), float("-inf")):
            self.assertIsNone(pct(bad))
        self.assertEqual(ops.duration_ms(1500.7), 1500)
        self.assertIsNone(ops.duration_ms(-1))
        self.assertEqual(ops.duration_ms(10 ** 15), 30 * 86400 * 1000)


class NormalizeTest(unittest.TestCase):
    def norm(self, raw, sid="src"):
        return normalize_batch(raw, sid)

    def test_service_fields_and_unknown_fields_dropped(self):
        b = self.norm({"services": [{
            "id": "host-a", "name": "host-a", "group": "hosts", "state": "healthy", "since": 1_791_000_000,
            "detail": "x" * 200, "cpu": 101, "mem": "33.33", "disk": None, "password": "hunter2", "ip": LAN_IP,
            "nested": {"token": TOKEN},
        }]})
        s = b.entities["service"][0]
        self.assertEqual(set(s), {"id", "sourceId", "name", "group", "state", "since", "detail", "cpu", "mem"})
        self.assertEqual(s["id"], "src/host-a")
        self.assertEqual(s["sourceId"], "src")
        self.assertEqual(s["state"], "up")
        self.assertEqual(s["cpu"], 100.0)
        self.assertEqual(s["mem"], 33.3)
        self.assertEqual(len(s["detail"]), 80)
        self.assertEqual(s["since"], 1_791_000_000_000)

    def test_enums_and_defaults(self):
        b = self.norm({
            "services": [{"name": "a", "state": "on fire"}, {"name": "b", "state": "offline"}, {"name": "c", "state": "pending"}],
            "jobs": [{"name": "j1", "lastStatus": "completed"}, {"name": "j2", "lastStatus": "weird", "enabled": False},
                     {"name": "j3", "lastStatus": "claimed"}],
            "alerts": [{"id": "a1", "ts": 1_791_000_000, "title": "t", "severity": "urgent", "state": "firing"},
                       {"id": "a2", "ts": 1_791_000_000, "title": "t", "severity": "??", "state": "closed",
                        "resolvedAt": 1_791_000_100}],
        })
        self.assertEqual([s["state"] for s in b.entities["service"]], ["unknown", "down", "degraded"])
        self.assertEqual([s["group"] for s in b.entities["service"]], ["other"] * 3)
        self.assertEqual([j["lastStatus"] for j in b.entities["job"]], ["ok", "unknown", "running"])
        self.assertEqual([j["enabled"] for j in b.entities["job"]], [True, False, True])
        a1, a2 = b.entities["alert"]
        self.assertEqual((a1["severity"], a1["state"], a1["source"]), ("critical", "open", "src"))
        self.assertEqual((a2["severity"], a2["state"], a2["resolvedAt"]), ("warn", "resolved", 1_791_000_100_000))
        self.assertNotIn("resolvedAt", a1)

    def test_rejects(self):
        b = self.norm({
            "services": [{"state": "up"}, "not a dict", {"id": "x", "name": "   "}],
            "jobs": [{"schedule": "daily"}],
            "usage": [{"provider": "p"}, {"window": "w"}],
            "alerts": [{"id": "a", "title": "no ts"}, {"id": "b", "ts": 1_791_000_000}, {"ts": 1_791_000_000, "title": "no id"}],
        })
        self.assertEqual(sum(len(v) for v in b.entities.values()), 0)
        self.assertEqual(b.rejected, 9)

    def test_usage_and_job(self):
        b = self.norm({
            "usage": [{"provider": "provider-a", "window": "session", "remainingPct": 41.04, "resetsAt": "2026-10-06T05:00:00Z"},
                      {"provider": "provider-b", "window": "credit", "detail": "$4.63 of $10.00"}],
            "jobs": [{"id": "j", "name": "nightly", "schedule": "30 6 * * *", "lastRun": 1_791_000_000, "nextRun": 1_791_003_600,
                      "durationMs": 1234.9, "lastStatus": "failed", "detail": "2 failures in a row"}],
        })
        u1, u2 = b.entities["usage"]
        self.assertEqual(u1["id"], "src/provider-a-session")
        self.assertEqual(u1["remainingPct"], 41.0)
        self.assertEqual(u1["resetsAt"], to_ms("2026-10-06T05:00:00+00:00"))
        self.assertNotIn("remainingPct", u2)
        j = b.entities["job"][0]
        self.assertEqual((j["schedule"], j["durationMs"], j["lastStatus"]), ("30 6 * * *", 1234, "failed"))

    def test_ids_are_filtered_and_unique(self):
        b = self.norm({"services": [
            {"id": LAN_IP, "name": "a"}, {"id": "10.20.30.40", "name": "b"},
            {"id": "Host A", "name": "c"}, {"id": "host-a", "name": "d"}, {"id": "x" * 200, "name": "e"},
        ]})
        ids = [s["id"] for s in b.entities["service"]]
        self.assertEqual(ids[:4], ["src/ip", "src/ip-2", "src/host-a", "src/host-a-2"])
        self.assertTrue(all(len(i) <= 4 + 64 for i in ids))
        self.assertEqual(len(set(ids)), len(ids))
        blob = json.dumps(b.entities)
        self.assertNotIn("77.5", blob)
        self.assertNotIn("30.40", blob)

    def test_malformed_batches(self):
        for bad in (None, [], "x", 3):
            with self.assertRaises(ValueError):
                normalize_batch(bad, "src")
        with self.assertRaises(ValueError):
            normalize_batch({"services": {"a": 1}}, "src")
        b = normalize_batch({}, "src")
        self.assertEqual(b.entities, {k: [] for k in ops.KINDS})
        self.assertEqual(normalize_batch({"warning": f"cache old at {LAN_IP}"}, "src").warning, "cache old at [ip]")

    def test_every_string_is_filtered(self):
        """Canaries in every string field of every kind (and in unknown fields) never survive."""
        def dirty(i):
            return f"{TOKEN} {GH} https://bob:hunter2@{LAN_IP}/x {LAN_IP} 10.20.30.40 2001:db8::77 owner@example.com /home/u/.env #{i}"
        raw = {
            "services": [{"id": dirty(1), "name": dirty(2), "group": dirty(3), "detail": dirty(4), "state": "up",
                          "extra": "secret-topic-canary"}],
            "jobs": [{"id": dirty(5), "name": dirty(6), "schedule": dirty(7), "detail": dirty(8), "deliver": "secret-topic-canary"}],
            "usage": [{"id": dirty(9), "provider": dirty(10), "window": dirty(11), "detail": dirty(12), "token": TOKEN}],
            "alerts": [{"id": dirty(13), "ts": 1_791_000_000, "title": dirty(14), "detail": dirty(15), "source": dirty(16),
                        "body": "secret-topic-canary"}],
            "warning": dirty(17),
        }
        b = normalize_batch(raw, "src")
        blob = json.dumps(b.entities) + b.warning
        for c in CANARIES:
            self.assertNotIn(c, blob, c)
        self.assertEqual(sum(len(v) for v in b.entities.values()), 4)

    def test_personal_notes_withheld(self):
        b = normalize_batch({"alerts": [{"id": "a", "ts": 1_791_000_000, "title": PERSONAL, "detail": PERSONAL}],
                             "services": [{"id": "s", "name": "svc", "detail": "quote from " + PERSONAL}]}, "src")
        self.assertEqual(b.entities["alert"][0]["title"], WITHHELD[:100])
        self.assertTrue(b.entities["service"][0]["detail"].startswith("[withheld"))
        self.assertNotIn("health.md", json.dumps(b.entities))

    def test_withheld_marker_respects_short_limits(self):
        """Found by fuzzing: the 35-char marker used to bypass a 24/32-char field limit."""
        b = normalize_batch({"usage": [{"provider": PERSONAL, "window": PERSONAL}],
                             "alerts": [{"id": "a", "ts": 1_791_000_000, "title": "t", "source": PERSONAL}]}, "src")
        u, a = b.entities["usage"][0], b.entities["alert"][0]
        self.assertLessEqual(len(u["provider"]), ops.TEXT["provider"])
        self.assertLessEqual(len(a["source"]), ops.TEXT["alert_source"])
        self.assertTrue(u["provider"].startswith("[withheld") and u["provider"].endswith("\u2026"))

    def test_allow_ip_text(self):
        with ip_policy(False):
            b = normalize_batch({"services": [{"id": "s", "name": "svc", "detail": "at 192.0.2.9"}]}, "src")
        self.assertEqual(b.entities["service"][0]["detail"], "at 192.0.2.9")


class CapTest(unittest.TestCase):
    def test_service_cap_keeps_problems(self):
        items = [svc(i) for i in range(400)] + [svc(1000 + i, "down") for i in range(10)]
        hub = OpsHub([StaticSource("big", {"services": items})], clock=FakeClock())
        run(hub.collect_all())
        out = hub.model()["services"]
        self.assertEqual(len(out), MAX["service"])
        self.assertEqual(sum(1 for s in out if s["state"] == "down"), 10)
        self.assertEqual(out, sorted(out, key=lambda e: (e["group"], e["name"].lower(), e["id"])))

    def test_alert_cap_keeps_open_and_newest(self):
        items = [{"id": f"r{i}", "ts": 1_791_000_000 + i, "title": "old", "state": "resolved", "severity": "info"} for i in range(150)]
        items += [{"id": f"o{i}", "ts": 1_700_000_000 + i, "title": "open", "severity": "critical"} for i in range(5)]
        hub = OpsHub([StaticSource("al", {"alerts": items})], clock=FakeClock())
        run(hub.collect_all())
        out = hub.model()["alerts"]
        self.assertEqual(len(out), MAX["alert"])
        self.assertEqual(sum(1 for a in out if a["state"] == "open"), 5)
        self.assertEqual([a["ts"] for a in out], sorted((a["ts"] for a in out), reverse=True))

    def test_job_and_usage_caps(self):
        jobs = [{"id": f"j{i}", "name": f"j{i}", "lastStatus": "ok"} for i in range(200)] + [{"id": "bad", "name": "zz-bad", "lastStatus": "failed"}]
        usage = [{"provider": f"p{i}", "window": "w", "remainingPct": 50} for i in range(40)] + [{"provider": "zz", "window": "w", "remainingPct": 1}]
        hub = OpsHub([StaticSource("x", {"jobs": jobs, "usage": usage})], clock=FakeClock())
        run(hub.collect_all())
        m = hub.model()
        self.assertEqual(len(m["jobs"]), MAX["job"])
        self.assertIn("x/bad", [j["id"] for j in m["jobs"]])
        self.assertEqual(len(m["usage"]), MAX["usage"])
        self.assertIn("x/zz-w", [u["id"] for u in m["usage"]])


class HubTest(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.src = StaticSource("fake", {
            "services": [svc(1), svc(2, "degraded", detail="slow")],
            "jobs": [{"id": "j1", "name": "job-1", "lastStatus": "ok", "lastRun": 1_790_999_000}],
            "usage": [{"provider": "provider-a", "window": "week", "remainingPct": 50}],
            "alerts": [{"id": "a1", "ts": 1_790_999_000, "title": "disk full", "severity": "warn"}],
        })
        self.hub = OpsHub([self.src], clock=self.clock)

    def test_snapshot(self):
        run(self.hub.collect_all())
        snap = self.hub.snapshot()
        self.assertEqual(snap["type"], "ops.snapshot")
        self.assertEqual([s["id"] for s in snap["services"]], ["fake/s1", "fake/s2"])
        self.assertEqual(len(snap["jobs"]), 1)
        self.assertEqual(len(snap["usage"]), 1)
        self.assertEqual(len(snap["alerts"]), 1)
        self.assertEqual(snap["limits"], {"services": 256, "jobs": 128, "usage": 32, "alerts": 100, "sources": 16})
        src = snap["sources"][0]
        self.assertEqual((src["id"], src["state"], src["interval"]), ("fake", "ok", 10))
        self.assertEqual(src["kinds"], ["service", "job", "usage", "alert"])
        self.assertEqual(src["counts"], {"services": 2, "jobs": 1, "usage": 1, "alerts": 1})
        self.assertEqual(src["lastOk"], int(self.clock.t // 60) * 60_000)

    def test_incremental_upsert_remove_and_quiet(self):
        run(self.hub.collect_all())
        m0 = self.hub.model()
        # nothing changed -> no messages
        self.clock.t += 10
        run(self.hub.tick())
        self.assertEqual(OpsHub.changes(m0, self.hub.model()), [])
        # one service changes, one disappears, an alert resolves, a job is added
        self.src.data["services"] = [svc(1, "down", detail="no answer")]
        self.src.data["alerts"][0].update(state="resolved", resolvedAt=1_791_000_000)
        self.src.data["jobs"].append({"id": "j2", "name": "job-2", "lastStatus": "running"})
        self.clock.t += 10
        run(self.hub.tick())
        m1 = self.hub.model()
        msgs = OpsHub.changes(m0, m1)
        types = [(m["type"], m.get("kind") or next(iter(m[k]["id"] for k in m if k not in ("type",)))) for m in msgs]
        self.assertIn(("ops.remove", "service"), types)
        rem = [m for m in msgs if m["type"] == "ops.remove"]
        self.assertEqual(rem, [{"type": "ops.remove", "kind": "service", "id": "fake/s2"}])
        up = {m["type"]: m for m in msgs if m["type"] != "ops.remove"}
        self.assertEqual(up["ops.service.upsert"]["service"]["state"], "down")
        self.assertEqual(up["ops.alert.upsert"]["alert"]["state"], "resolved")
        self.assertEqual(up["ops.job.upsert"]["job"]["id"], "fake/j2")
        self.assertEqual(up["ops.source.upsert"]["source"]["counts"]["services"], 1)
        self.assertNotIn("ops.usage.upsert", up)
        # the source status is listed first so a client learns about staleness before entity changes
        self.assertEqual(msgs[0]["type"], "ops.source.upsert")
        self.assertEqual(OpsHub.changes(None, m1), [])

    def test_schedule_respects_interval(self):
        run(self.hub.tick())
        self.assertEqual(self.src.calls, 1)
        self.clock.t += 5
        run(self.hub.tick())
        self.assertEqual(self.src.calls, 1)
        self.clock.t += 5
        run(self.hub.tick())
        self.assertEqual(self.src.calls, 2)

    def test_last_ok_moves_at_most_once_a_minute(self):
        self.clock.t = 1_791_000_000.0 - (1_791_000_000.0 % 60)  # start of a minute
        run(self.hub.collect_all())
        m0 = self.hub.model()
        self.clock.t += 30
        run(self.hub.collect_all())
        self.assertEqual(OpsHub.changes(m0, self.hub.model()), [])
        self.clock.t += 31
        run(self.hub.collect_all())
        msgs = OpsHub.changes(m0, self.hub.model())
        self.assertEqual([m["type"] for m in msgs], ["ops.source.upsert"])

    def test_error_keeps_last_good_then_goes_stale(self):
        run(self.hub.collect_all())
        good = self.hub.model()
        self.src.fail = RuntimeError(f"GET https://bob:hunter2@{LAN_IP}/api failed with {TOKEN}")
        self.clock.t += 10
        run(self.hub.tick())
        m = self.hub.model()
        st = m["sources"][0]
        self.assertEqual(st["state"], "error")
        self.assertIn("RuntimeError", st["detail"])
        for c in (LAN_IP, "hunter2", TOKEN):
            self.assertNotIn(c, json.dumps(m))
        self.assertEqual(m["services"], good["services"])  # last good data retained
        # past the stale window (max(120 s, 3 x 10 s)): services become unknown, nothing else is lost
        self.clock.t += 125
        run(self.hub.tick())
        m = self.hub.model()
        self.assertEqual(m["sources"][0]["state"], "stale")
        self.assertEqual({s["state"] for s in m["services"]}, {"unknown"})
        self.assertTrue(all(s["detail"] == "no fresh data from fake" for s in m["services"]))
        self.assertEqual(len(m["jobs"]), 1)
        # recovery
        self.src.fail = None
        self.clock.t += 10
        run(self.hub.tick())
        m = self.hub.model()
        self.assertEqual(m["sources"][0]["state"], "ok")
        self.assertNotIn("detail", m["sources"][0])
        self.assertEqual([s["state"] for s in m["services"]], ["up", "degraded"])

    def test_never_succeeded_is_an_error_not_stale(self):
        self.src.fail = OSError("nope")
        run(self.hub.tick())
        self.assertEqual(self.hub.model()["sources"][0]["state"], "error")
        self.clock.t += 500
        run(self.hub.tick())
        st = self.hub.model()["sources"][0]
        self.assertEqual(st["state"], "error")
        self.assertNotIn("lastOk", st)

    def test_malformed_result_is_an_error(self):
        self.src.data = ["not", "a", "dict"]
        run(self.hub.tick())
        st = self.hub.model()["sources"][0]
        self.assertEqual(st["state"], "error")
        self.assertIn("expected a dict", st["detail"])

    def test_warning_state(self):
        self.src.data["warning"] = f"cache is 3h old ({LAN_IP})"
        run(self.hub.tick())
        st = self.hub.model()["sources"][0]
        self.assertEqual((st["state"], st["detail"]), ("warn", "cache is 3h old ([ip])"))
        self.assertEqual(len(self.hub.model()["services"]), 2)

    def test_rejected_count_is_reported(self):
        self.src.data["services"].append({"state": "up"})
        run(self.hub.tick())
        self.assertEqual(self.hub.model()["sources"][0]["rejected"], 1)

    def test_two_sources_are_namespaced(self):
        other = StaticSource("other", {"services": [svc(1, "down")]})
        hub = OpsHub([self.src, other], clock=self.clock)
        run(hub.collect_all())
        ids = sorted(s["id"] for s in hub.model()["services"])
        self.assertEqual(ids, ["fake/s1", "fake/s2", "other/s1"])

    def test_source_validation(self):
        with self.assertRaises(ValueError):
            OpsHub([StaticSource("")])
        with self.assertRaises(ValueError):
            OpsHub([StaticSource("a"), StaticSource("A")])
        with self.assertRaises(ValueError):
            OpsHub([object()])
        with self.assertRaises(ValueError):
            OpsHub([StaticSource(f"s{i}") for i in range(17)])
        hub = OpsHub([StaticSource("x", interval=0.001, timeout=1e9)])
        self.assertEqual((hub.slots[0].interval, hub.slots[0].timeout), (1.0, 300.0))

    def test_close_calls_sources(self):
        closed = []

        class C(StaticSource):
            def close(self):
                closed.append(self.id)
                raise RuntimeError("ignored")

        hub = OpsHub([C("c1"), C("c2")])
        hub.close()
        self.assertEqual(closed, ["c1", "c2"])


class TimeoutTest(unittest.TestCase):
    def test_hung_collector_times_out_and_is_not_rerun(self):
        release = threading.Event()

        class Hung(StaticSource):
            def collect(self):
                self.calls += 1
                release.wait(5)
                return {"services": [svc(1)]}

        clock = FakeClock()
        src = Hung("hung", interval=1.0, timeout=0.2)
        hub = OpsHub([src], clock=clock)

        async def scenario():
            t0 = time.monotonic()
            await hub.tick()
            self.assertLess(time.monotonic() - t0, 2.0)
            st = hub.model()["sources"][0]
            self.assertEqual(st["state"], "error")
            self.assertIn("did not finish", st["detail"])
            clock.t += 5
            await hub.tick()  # still busy: not started again
            self.assertEqual(src.calls, 1)
            release.set()
            for _ in range(50):
                if not hub.slots[0].busy:
                    break
                await asyncio.sleep(0.02)
            self.assertFalse(hub.slots[0].busy)
            clock.t += 5
            await hub.tick()
            self.assertEqual(src.calls, 2)
            self.assertEqual(hub.model()["sources"][0]["state"], "ok")

        try:
            asyncio.run(scenario())
        finally:
            release.set()
            hub.close()


if __name__ == "__main__":
    unittest.main()
