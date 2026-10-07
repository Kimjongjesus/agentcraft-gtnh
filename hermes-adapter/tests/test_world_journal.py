"""World journal: diffed events, samples, snapshots, retention, privacy, read-only query CLI."""

import contextlib
import io
import json
import os
import sqlite3
import stat
import tempfile
import unittest
from pathlib import Path

from hermes_adapter import journal as jmod
from hermes_adapter import world
from hermes_adapter.journal import JournalError, Retention, WorldJournal, parse_time

from factory_fixture import T0, capture, machine

DAY = 86_400_000


def ev(kind, ts, subject="base", severity="info", text="t", data=None):
    e = {"kind": kind, "ts": ts, "subject": subject, "severity": severity, "text": text}
    if data:
        e["data"] = data
    return e


class JournalTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.path = self.dir / "state" / "world.sqlite3"

    def open(self, **kw):
        return WorldJournal(self.path, Retention(**kw) if kw else None)

    def test_file_is_private_and_outside_the_repo(self):
        j = self.open()
        j.close()
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        repo = jmod._repo_root()
        if repo is not None:
            with self.assertRaisesRegex(JournalError, "git checkout"):
                WorldJournal(repo / "hermes-adapter" / "world.sqlite3")
            self.assertFalse((repo / "hermes-adapter" / "world.sqlite3").exists())

    def test_loose_permissions_are_tightened(self):
        self.path.parent.mkdir(parents=True)
        self.path.touch()
        os.chmod(self.path, 0o644)
        self.open().close()
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)

    def test_record_and_query_events_with_filters(self):
        j = self.open()
        m1 = world.normalize(capture(seq=1))
        j.record(m1, [ev("machine.appeared", T0 + 1, "dim0:a@1,2,3"), ev("power.low", T0 + 2, "power", "warn")], T0 + 2)
        j.record(None, [ev("source.lost", T0 + 3, "source", "warn"), ev("machine.stopped", T0 + 4, "dim0:a@1,2,3")], T0 + 4,
                 session="sess-a")
        self.assertEqual([e["kind"] for e in j.query_events()], ["machine.appeared", "power.low", "source.lost", "machine.stopped"])
        self.assertEqual([e["kind"] for e in j.query_events(kinds=["machine."])], ["machine.appeared", "machine.stopped"])
        self.assertEqual([e["kind"] for e in j.query_events(kinds=["power.low", "source."])], ["power.low", "source.lost"])
        self.assertEqual([e["kind"] for e in j.query_events(min_severity="warn")], ["power.low", "source.lost"])
        self.assertEqual(len(j.query_events(subject="dim0:a@1,2,3")), 2)
        self.assertEqual([e["ts"] for e in j.query_events(since_ms=T0 + 2, until_ms=T0 + 3)], [T0 + 2, T0 + 3])
        self.assertEqual(j.query_events(newest_first=True, limit=1)[0]["kind"], "machine.stopped")
        self.assertEqual({e["session"] for e in j.query_events()}, {"sess-a"})
        self.assertEqual(j.query_events(kinds=["machine%"]), [], "LIKE wildcards are escaped")

    def test_snapshots_are_periodic_and_per_session_and_keep_private_data(self):
        j = self.open(snapshot_every_s=600)
        for i in range(25):  # 25 captures 30 s apart = 12 minutes
            j.record(world.normalize(capture(seq=i + 1)), [], T0 + i * 30_000)
        self.assertEqual(j.stats()["snapshots"]["rows"], 2)
        j.record(world.normalize(capture(seq=1, session="sess-b")), [], T0 + 26 * 30_000)
        snap = j.latest_snapshot()
        self.assertEqual((snap["session"], snap["reason"]), ("sess-b", "session"))
        self.assertEqual(snap["model"]["private"]["playersInScope"], ["PlayerOne"])
        older = j.latest_snapshot(at_ms=T0 + 60_000)
        self.assertEqual(older["session"], "sess-a")
        self.assertIsNone(j.latest_snapshot(at_ms=T0 - 1))
        self.assertEqual(j.stats()["samples"]["rows"], 26)

    def test_power_trend_is_downsampled(self):
        j = self.open()
        for i in range(100):
            j.record(world.normalize(capture(seq=i + 1, ae_stored=1000.0 * i)), [], T0 + i * 30_000)
        pts = j.trend(T0, T0 + 100 * 30_000, max_points=10)
        self.assertLessEqual(len(pts), 10)
        self.assertEqual(sum(p["samples"] for p in pts), 100)
        self.assertLess(pts[0]["ae_stored"], pts[-1]["ae_stored"], "rising AE storage shows as a rising trend")
        self.assertEqual(pts[0]["running"], 1)

    def test_retention_by_age_and_row_caps(self):
        j = self.open(max_age_days=2, snapshot_max_age_days=1, max_events=50, prune_every_s=1e9)
        now = T0 + 10 * DAY
        j.record(world.normalize(capture(seq=1)), [ev("machine.started", now - 5 * DAY + i) for i in range(10)], now - 5 * DAY)
        j.record(world.normalize(capture(seq=2)), [ev("machine.started", now - DAY // 2 + i) for i in range(80)], now - DAY // 2)
        out = j.prune(now)
        st = j.stats()
        self.assertEqual(st["events"]["rows"], 50)
        self.assertEqual(st["samples"]["rows"], 1)
        self.assertEqual(st["snapshots"]["rows"], 1)
        self.assertEqual(out["events"], 40)
        self.assertGreaterEqual(st["events"]["oldest"], now - DAY // 2 + 30, "the newest events survive the cap")

    def test_size_cap_drops_oldest_snapshots_first(self):
        j = self.open(snapshot_every_s=0, max_bytes=300_000, prune_every_s=1e9)
        big = [machine(i, parts=30, name=f"Machine number {i} " + "abcdefghij" * 5) for i in range(400)]
        for i in range(12):
            j.record(world.normalize(capture(big, seq=i + 1)), [ev("machine.started", T0 + i)], T0 + i * 1000)
        before = j.stats()
        j.prune(T0 + 20_000)
        after = j.stats()
        self.assertLessEqual(after["bytes"], 300_000)
        self.assertLess(after["snapshots"]["rows"], before["snapshots"]["rows"])
        self.assertEqual(after["snapshots"]["newest"], before["snapshots"]["newest"], "the newest snapshot is kept")
        self.assertEqual(after["events"]["rows"], 12, "events go only after snapshots")

    def test_a_reader_sees_new_rows_while_the_adapter_writes(self):
        w = self.open()
        w.record(world.normalize(capture(seq=1)), [ev("machine.started", T0)], T0)
        r = WorldJournal(self.path, readonly=True)
        self.assertEqual(len(r.query_events()), 1)
        w.record(world.normalize(capture(seq=2)), [ev("machine.stopped", T0 + 30_000)], T0 + 30_000)
        self.assertEqual([e["kind"] for e in r.query_events()], ["machine.started", "machine.stopped"])
        self.assertEqual(r.stats()["samples"]["rows"], 2)
        r.close()
        w.close()

    def test_record_is_thread_safe(self):
        import threading

        j = self.open()
        errors = []

        def writer(n):
            try:
                for i in range(25):
                    j.record(None, [ev("machine.started", T0 + n * 1000 + i, subject=f"m{n}")], T0, session="s")
            except Exception as e:  # pragma: no cover - reported below
                errors.append(e)

        threads = [threading.Thread(target=writer, args=(n,)) for n in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(errors, [])
        self.assertEqual(j.stats()["events"]["rows"], 100)

    def test_readonly_open_cannot_write(self):
        j = self.open()
        j.record(world.normalize(capture()), [ev("x.y", T0)], T0)
        ro = WorldJournal(self.path, readonly=True)
        self.assertEqual(len(ro.query_events()), 1)
        with self.assertRaises(JournalError):
            ro.record(None, [ev("x.z", T0)], T0)
        with self.assertRaises(sqlite3.Error):
            ro._db.execute("DELETE FROM events")
        ro.close()
        j.close()
        with self.assertRaisesRegex(JournalError, "no world journal"):
            WorldJournal(self.dir / "nope.sqlite3", readonly=True)

    def test_another_database_is_never_written(self):
        import hashlib

        self.path.parent.mkdir(parents=True)
        db = sqlite3.connect(self.path)
        db.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY, title TEXT)")
        db.execute("INSERT INTO tasks VALUES ('t1', 'someone else''s data')")
        db.commit()
        db.close()
        os.chmod(self.path, 0o644)
        before = hashlib.sha256(self.path.read_bytes()).hexdigest()
        with self.assertRaisesRegex(JournalError, "another SQLite database"):
            self.open()
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).hexdigest(), before)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o644, "not even its permissions change")
        self.assertFalse(Path(str(self.path) + "-wal").exists())

    def test_a_file_that_is_not_sqlite_is_refused_cleanly(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_bytes(b"this is not a database at all " * 100)
        with self.assertRaisesRegex(JournalError, "not a world journal"):
            self.open()
        with self.assertRaisesRegex(JournalError, "not a world journal"):
            WorldJournal(self.path, readonly=True)

    def test_newer_schema_is_refused(self):
        self.open().close()
        db = sqlite3.connect(self.path)
        db.execute("PRAGMA user_version=99")
        db.commit()
        db.close()
        with self.assertRaisesRegex(JournalError, "newer"):
            self.open()

    def test_public_views_drop_names_and_coordinates(self):
        e = {"kind": "machine.problem", "subject": "dim0:multimachine.x@10,64,-20", "text": "X: ME channel inactive at 12, 63,-20",
             "session": "s", "data": {"x": 10, "y": 64, "z": -20, "dim": 0, "from": "running", "problem": "bus at 1,2,3"}}
        pe = jmod.public_event(e)
        self.assertNotIn("10,64,-20", json.dumps(pe))
        self.assertEqual(pe["text"], "X: ME channel inactive at [pos]")
        self.assertEqual(pe["data"], {"from": "running", "problem": "bus at [pos]"})
        pm = jmod.public_model(world.normalize(capture([machine(1, "problem", problem="hatch at 5,6,7")], players=["PlayerOne"])))
        text = json.dumps(pm)
        self.assertNotIn("PlayerOne", text)
        self.assertNotIn('"x": 104', text)
        self.assertNotIn("5,6,7", text)
        self.assertNotIn("center", pm["base"]["scope"])
        raw = capture()
        raw["baseId"] = "Test World/configured:dim0:100,64,-20:r64"
        self.assertEqual(jmod.public_model(world.normalize(raw))["base"]["id"], "Test_World_configured:dim0:[pos]:r64")

    def test_reported_player_names_are_scrubbed_from_public_text(self):
        raw = capture([machine(1, name="PlayerOne's EBF", problem="playerone left the door open")], players=["PlayerOne"])
        m = world.normalize(raw)
        self.assertEqual(m["private"]["playersInScope"], ["PlayerOne"])
        mm = m["machines"][0]
        self.assertEqual((mm["name"], mm["problem"]), ("[player]'s EBF", "[player] left the door open"))
        self.assertNotIn("playerone", json.dumps(world.public(m)).lower())


class CliTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.path = self.dir / "w.sqlite3"
        j = WorldJournal(self.path)
        a = world.normalize(capture(seq=1, captured_at=T0))
        b = world.normalize(capture([machine(1, "idle"), machine(2), machine(3, "maintenance")], seq=2, captured_at=T0 + 30_000))
        events = [dict(e, ts=T0 + 30_000) for e in world.change_events(a, b)]
        j.record(a, [], T0)
        j.record(b, events, T0 + 30_000)
        j.close()

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = jmod.main(["--db", str(self.path), *args])
        return code, out.getvalue(), err.getvalue()

    def test_query_text_and_json(self):
        code, out, _ = self.run_cli("query", "--since", str(T0 - 1))
        self.assertEqual(code, 0)
        self.assertIn("machine.stopped", out)
        self.assertIn("machine.started", out)
        self.assertIn("(2 events)", out)
        code, out, _ = self.run_cli("query", "--since", str(T0 - 1), "--kind", "machine.stopped", "--json")
        data = json.loads(out)
        self.assertEqual([e["kind"] for e in data], ["machine.stopped"])
        self.assertEqual(data[0]["data"]["x"], 104)

    def test_public_flag_hides_coordinates_and_players(self):
        _, out, _ = self.run_cli("query", "--since", str(T0 - 1), "--json", "--public")
        self.assertNotIn("104,64,-20", out)
        _, out, _ = self.run_cli("snapshot", "--json", "--public")
        self.assertNotIn("PlayerOne", out)
        _, out, _ = self.run_cli("snapshot")
        self.assertIn("@ 104,64,-20", out)

    def test_trend_stats_and_bad_input(self):
        code, out, _ = self.run_cli("trend", "--since", str(T0 - 1), "--until", str(T0 + 60_000), "--json")
        self.assertEqual(code, 0)
        self.assertEqual(sum(p["samples"] for p in json.loads(out)), 2)
        code, out, _ = self.run_cli("stats")
        self.assertEqual(json.loads(out)["events"]["rows"], 2)
        code, _, err = self.run_cli("query", "--since", "yesterday-ish")
        self.assertEqual(code, 2)
        self.assertIn("cannot read time", err)
        err2 = io.StringIO()
        with contextlib.redirect_stderr(err2):
            code = jmod.main(["--db", str(self.dir / "absent.sqlite3"), "stats"])
        self.assertEqual(code, 2)
        self.assertIn("no world journal", err2.getvalue())

    def test_cli_query_never_writes(self):
        before = self.path.stat().st_mtime_ns
        self.run_cli("query", "--since", "30d")
        self.run_cli("stats")
        self.assertEqual(self.path.stat().st_mtime_ns, before)

    def test_parse_time(self):
        now = T0
        self.assertEqual(parse_time("2h", now), now - 7_200_000)
        self.assertEqual(parse_time("90s", now), now - 90_000)
        self.assertEqual(parse_time("1.5d", now), now - 129_600_000)
        self.assertEqual(parse_time("now", now), now)
        self.assertEqual(parse_time("1790000000", now), 1_790_000_000_000)
        self.assertEqual(parse_time(str(T0), now), T0)
        self.assertEqual(parse_time("2026-10-06T00:00:00Z"), 1_791_244_800_000)
        with self.assertRaises(ValueError):
            parse_time("soon")


if __name__ == "__main__":
    unittest.main()
