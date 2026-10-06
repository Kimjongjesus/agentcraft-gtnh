"""Card 2 (in-world HQ): station assignments and per-agent log tails, the fields the GTNH mod's
NPC walker, desk monitors and "waiting on Eli" markers read.

Every assertion runs on the SERIALIZED snapshot (what goes on the wire) and on the incremental
messages (agent.upsert / agent.log), like the card 1 privacy tests.
"""

import json
import tempfile
import time
import unittest
from pathlib import Path

from hermes_adapter.mapping import STATIONS, Mapper, finalize_station
from hermes_adapter.server import AdapterServer
from hermes_adapter.sources import HermesSource

from fixture import FAKE_TOKEN, standard


def snapshot(home: Path, mapper: Mapper | None = None) -> dict:
    model = (mapper or Mapper()).build(HermesSource(home).read())
    return json.loads(json.dumps(model))  # exactly what is serialized


class FinalizeStationTest(unittest.TestCase):
    def test_valid_station_is_kept(self):
        for st in STATIONS:
            self.assertEqual(finalize_station({"state": "editing", "station": st, "active": True})["station"], st)

    def test_unknown_station_defaults_desk_when_working_lounge_when_idle(self):
        for state in ("thinking", "reading", "editing", "running", "testing"):
            self.assertEqual(finalize_station({"state": state, "station": "garage", "active": True})["station"], "desk")
        for state in ("idle", "done", "error", "waiting_user", "blocked"):
            self.assertEqual(finalize_station({"state": state, "station": "garage", "active": True})["station"], "lounge")
        self.assertEqual(finalize_station({"state": "editing", "active": True})["station"], "desk")
        self.assertEqual(finalize_station({"state": "idle", "station": None, "active": True})["station"], "lounge")

    def test_off_shift_is_always_lounge(self):
        self.assertEqual(finalize_station({"state": "editing", "station": "desk", "active": False})["station"], "lounge")


class StationSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = int(time.time())
        self.f = standard(Path(self.tmp.name), self.now)

    def tearDown(self):
        self.tmp.cleanup()

    def test_every_agent_has_a_protocol_station(self):
        snap = snapshot(self.f.home)
        agents = {a["id"]: a for a in snap["agents"]}
        for a in agents.values():
            self.assertIn(a["station"], STATIONS, a["id"])
        self.assertEqual(agents["claude-builder"]["station"], "desk")  # live builder run
        self.assertEqual(agents["sol-reviewer"]["station"], "user")  # needs_input block -> waiting on Eli
        self.assertEqual(agents["sol-reviewer"]["state"], "waiting_user")
        self.assertEqual(agents["cron"]["station"], "terminal")
        self.assertEqual(agents["default"]["station"], "lounge")
        # astra has no runs and no session mtime: off shift -> lounge
        self.assertFalse(agents["astra-ultimate"]["active"])
        self.assertEqual(agents["astra-ultimate"]["station"], "lounge")

    def test_reviewer_live_run_is_library(self):
        self.f.task("t_rev", "Review it", "running", "sol-reviewer")
        self.f.sql("UPDATE tasks SET status='done' WHERE id='t_wait'")
        self.f.run("t_rev", "sol-reviewer")
        agents = {a["id"]: a for a in snapshot(self.f.home)["agents"]}
        self.assertEqual((agents["sol-reviewer"]["state"], agents["sol-reviewer"]["station"]), ("reading", "library"))

    def test_mapping_without_a_station_falls_back(self):
        class NoStation(Mapper):
            def _agent(self, *a, **kw):
                agent = super()._agent(*a, **kw)
                agent["station"] = "server-room"  # not in the protocol enum
                return agent

        agents = {a["id"]: a for a in snapshot(self.f.home, NoStation())["agents"]}
        self.assertEqual(agents["claude-builder"]["station"], "desk")  # editing -> desk
        self.assertEqual(agents["sol-reviewer"]["station"], "lounge")  # waiting_user, not working -> lounge
        self.assertEqual(agents["default"]["station"], "lounge")

    def test_decision_marks_the_waiting_agent(self):
        """The "!" marker: an open decision whose agentId is the waiting agent."""
        snap = snapshot(self.f.home)
        open_d = [d for d in snap["decisions"] if d["status"] == "open"]
        self.assertEqual([d["agentId"] for d in open_d], ["sol-reviewer"])
        self.assertEqual(open_d[0]["options"], ["Yes", "No", "Later"])


class MonitorLogTailTest(unittest.TestCase):
    """Desk monitors show agent.log tails: bounded, and filtered as whole source texts."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = int(time.time())
        self.f = standard(Path(self.tmp.name), self.now)

    def tearDown(self):
        self.tmp.cleanup()

    def test_tail_is_bounded_and_ordered(self):
        for i in range(80):
            self.f.comment("t_build", "claude-builder", f"PROGRESS: step {i}", at=self.now - 1000 + i)
        logs = {l["agentId"]: l["entries"] for l in snapshot(self.f.home)["logs"]}
        tail = logs["claude-builder"]
        self.assertLessEqual(len(tail), 60)
        self.assertEqual([e["ts"] for e in tail], sorted(e["ts"] for e in tail))
        for e in tail:
            self.assertIn(e["kind"], ("text", "tool", "result", "error", "diff"))

    def test_log_lines_never_carry_canaries_snapshot(self):
        canary = "CANARY" + "_MONITOR_LINE"
        self.f.comment("t_build", "claude-builder", f"PROGRESS: fine first line\n{canary}\nsee personal-" + "notes.md", at=self.now - 5)
        self.f.comment("t_build", "claude-builder", f"ran deploy --api-key {FAKE_TOKEN[:8]}XYZ123456 ok", at=self.now - 4)
        rid = self.f.run("t_build", "claude-builder")
        self.f.event("t_build", "heartbeat", {"note": "login --password Hunter2Secret!\nthen deploy"}, run_id=rid, at=self.now - 3)
        self.f.event("t_build", "heartbeat", {"note": f"{canary} on the first line\nsource: memory/personal-" + "x.md"}, run_id=rid, at=self.now - 2)
        blob = json.dumps(snapshot(self.f.home)["logs"])
        self.assertNotIn("CANARY", blob)
        self.assertNotIn("Hunter2Secret", blob)
        self.assertNotIn("XYZ123456", blob)
        self.assertIn("[withheld: mentions personal notes]", blob)


class IncrementalStationTest(unittest.TestCase):
    """agent.upsert carries the new station; agent.log carries filtered monitor lines."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = int(time.time())
        self.f = standard(Path(self.tmp.name), self.now)
        self.src = HermesSource(self.f.home)
        self.mapper = Mapper()
        self.old = json.loads(json.dumps(self.mapper.build(self.src.read())))

    def tearDown(self):
        self.tmp.cleanup()

    def changes(self) -> list[dict]:
        new = json.loads(json.dumps(self.mapper.build(self.src.read())))
        msgs = AdapterServer.changes(self.old, new)
        self.old = new
        return json.loads(AdapterServer.encode({"type": "batch", "msgs": msgs}))["msgs"]

    def upsert(self, msgs: list[dict], aid: str) -> dict:
        found = [m["agent"] for m in msgs if m["type"] == "agent.upsert" and m["agent"]["id"] == aid]
        self.assertTrue(found, f"agent.upsert for {aid}")
        return found[-1]

    def test_station_follows_the_run(self):
        # builder finishes -> lounge (done)
        self.f.sql("UPDATE task_runs SET ended_at=?, status='done', outcome='completed' WHERE task_id='t_build'", (self.now,))
        a = self.upsert(self.changes(), "claude-builder")
        self.assertEqual((a["state"], a["station"]), ("done", "lounge"))
        # sonnet picks up a card -> desk
        self.f.task("t_new", "New work", "running", "claude-builder-sonnet")
        self.f.run("t_new", "claude-builder-sonnet")
        a = self.upsert(self.changes(), "claude-builder-sonnet")
        self.assertEqual((a["state"], a["station"]), ("editing", "desk"))
        # it blocks on a question -> user station (and the decision opens)
        self.f.sql("UPDATE task_runs SET ended_at=?, status='blocked', outcome='blocked' WHERE task_id='t_new'", (self.now,))
        self.f.sql("UPDATE tasks SET status='blocked', block_kind='needs_input' WHERE id='t_new'")
        self.f.event("t_new", "blocked", {"reason": "PERMISSION p1: reboot? || CHOICES: Approve | Deny", "kind": "needs_input"}, at=self.now)
        msgs = self.changes()
        a = self.upsert(msgs, "claude-builder-sonnet")
        self.assertEqual((a["state"], a["station"]), ("waiting_user", "user"))
        dec = [m["decision"] for m in msgs if m["type"] == "decision.upsert"]
        self.assertTrue(any(d["agentId"] == "claude-builder-sonnet" and d["status"] == "open" and d["kind"] == "permission" for d in dec))
        for m in msgs:
            if m["type"] == "agent.upsert":
                self.assertIn(m["agent"]["station"], STATIONS)

    def test_incremental_log_lines_are_filtered(self):
        canary = "CANARY" + "_LIVE_MONITOR"
        self.f.comment("t_build", "claude-builder", f"PROGRESS: ok line\n{canary} in personal-" + "x.md", at=self.now + 1)
        self.f.comment("t_build", "claude-builder", "deploy --token Sup3rS3cretT0ken now", at=self.now + 2)
        msgs = self.changes()
        logs = [m for m in msgs if m["type"] == "agent.log" and m["agentId"] == "claude-builder"]
        self.assertTrue(logs, "agent.log arrived")
        blob = json.dumps(logs)
        self.assertNotIn("CANARY", blob)
        self.assertNotIn("Sup3rS3cretT0ken", blob)
        self.assertIn("--token [redacted]", blob)


if __name__ == "__main__":
    unittest.main()
