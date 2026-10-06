import hashlib
import json
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path

from hermes_adapter.mapping import Mapper, parse_decision_reason
from hermes_adapter.sources import HermesSource

from fixture import FAKE_GH, FAKE_TOKEN, standard


def build(home: Path, **kw):
    src = HermesSource(home, **kw)
    return Mapper().build(src.read()), src


class MappingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = int(time.time())
        self.f = standard(Path(self.tmp.name), self.now)
        self.model, self.src = build(self.f.home)
        self.agents = {a["id"]: a for a in self.model["agents"]}
        self.tasks = {t["id"]: t for t in self.model["tasks"]}

    def tearDown(self):
        self.tmp.cleanup()

    def test_agents_are_profiles_plus_cron(self):
        self.assertEqual(
            sorted(self.agents),
            sorted(["default", "claude-builder", "sol-reviewer", "claude-builder-sonnet", "astra-ultimate", "cron"]),
        )
        self.assertEqual(self.agents["default"]["role"], "lead")
        self.assertEqual(self.agents["claude-builder"]["name"], "Opus Builder")
        for a in self.agents.values():
            self.assertRegex(a["color"], r"^#[0-9A-F]{6}$")
            self.assertLessEqual(len(a["activity"]), 48)

    def test_running_builder_is_editing_with_progress_line(self):
        a = self.agents["claude-builder"]
        self.assertEqual(a["state"], "editing")
        self.assertEqual(a["station"], "desk")
        self.assertEqual(a["taskId"], "t_build")
        self.assertEqual(a["activity"], "wiring the websocket bridge")

    def test_blocked_needs_input_is_waiting_user(self):
        a = self.agents["sol-reviewer"]
        self.assertEqual(a["state"], "waiting_user")
        self.assertEqual(a["station"], "user")
        self.assertTrue(a["activity"].startswith("Question:"))

    def test_idle_and_cron(self):
        self.assertEqual(self.agents["astra-ultimate"]["state"], "idle")
        self.assertEqual(self.agents["cron"]["station"], "terminal")
        self.assertTrue(self.agents["cron"]["activity"].startswith("next nest.ops.daily"))

    def test_task_mapping_and_deps(self):
        t = self.tasks["t_build"]
        self.assertEqual(t["status"], "doing")
        self.assertEqual(t["assignee"], "claude-builder")
        self.assertEqual(t["deps"], ["t_parent"])
        self.assertEqual(t["priority"], 80)
        self.assertEqual(t["createdBy"], "user")
        self.assertEqual(t["ci"], "unknown")
        self.assertEqual(self.tasks["t_parent"]["status"], "done")
        self.assertEqual(self.tasks["t_wait"]["status"], "blocked")
        self.assertIn("ship it now", self.tasks["t_wait"]["blockedReason"])
        self.assertNotIn("t_scratch", self.tasks, "scratch boards are skipped by default")

    def test_decisions(self):
        (d,) = self.model["decisions"]
        self.assertEqual(d["status"], "open")
        self.assertEqual(d["kind"], "question")
        self.assertEqual(d["agentId"], "sol-reviewer")
        self.assertEqual(d["options"], ["Yes", "No", "Later"])
        self.assertEqual(parse_decision_reason("PERMISSION p1: rm x on host || CHOICES: Approve | Deny")[0], "permission")

    def test_decision_answered_after_unblock(self):
        self.f.sql("UPDATE tasks SET status='ready' WHERE id='t_wait'")
        self.f.event("t_wait", "unblocked", None)
        model, _ = build(self.f.home)
        (d,) = model["decisions"]
        self.assertEqual(d["status"], "answered")
        self.assertNotEqual({a["id"]: a for a in model["agents"]}["sol-reviewer"]["state"], "waiting_user")

    def test_no_secrets_or_personal_notes_anywhere(self):
        blob = json.dumps(self.model)
        for bad in (FAKE_TOKEN, FAKE_GH, "hunter2", "auth.json", "personal-schedule", "personal-health",
                    "private details", "SECRET PROMPT", "discord:", "/home/aiops"):
            self.assertNotIn(bad, blob, bad)
        self.assertEqual(self.tasks["t_personal"]["description"], "[withheld: mentions personal notes]")
        # card 3: the library exists now, but only from already-filtered board text, never memory files
        kinds = {e["kind"] for e in self.model["memory"]}
        self.assertTrue(kinds <= {"plan", "handoff", "review", "summary", "overview", "decision"}, kinds)

    def test_logs_and_feed(self):
        logs = {l["agentId"]: l["entries"] for l in self.model["logs"]}
        texts = [e["text"] for e in logs["claude-builder"]]
        self.assertTrue(any("claimed t_build" in t for t in texts))
        self.assertTrue(any("wiring the websocket bridge" in t for t in texts))
        self.assertTrue(all(len(v) <= 60 for v in logs.values()))
        kinds = [f["text"] for f in self.model["feed"]]
        self.assertTrue(any(t.startswith("Opus Builder picked up") for t in kinds))
        self.assertTrue(any("cron nest.ops.daily ran: ok" in t for t in kinds))
        ts = [f["ts"] for f in self.model["feed"]]
        self.assertEqual(ts, sorted(ts))

    def test_error_and_done_states(self):
        self.f.sql("UPDATE task_runs SET status='crashed', ended_at=?, outcome='crashed' WHERE task_id='t_build'", (self.now - 60,))
        model, _ = build(self.f.home)
        self.assertEqual({a["id"]: a for a in model["agents"]}["claude-builder"]["state"], "error")
        self.f.run("t_build", "claude-builder", status="done", started=self.now - 50, ended=self.now - 10, outcome="completed")
        model, _ = build(self.f.home)
        self.assertEqual({a["id"]: a for a in model["agents"]}["claude-builder"]["state"], "done")

    def test_reviewer_run_reads_in_library(self):
        self.f.run("t_wait", "sol-reviewer")
        model, _ = build(self.f.home)
        a = {a["id"]: a for a in model["agents"]}["sol-reviewer"]
        self.assertEqual((a["state"], a["station"]), ("reading", "library"))

    def test_source_is_read_only(self):
        digest = hashlib.sha256(self.f.db_path.read_bytes()).hexdigest()
        for _ in range(3):
            self.src.read()
        self.assertEqual(digest, hashlib.sha256(self.f.db_path.read_bytes()).hexdigest())
        from hermes_adapter.sources import _ro_connect
        conn = _ro_connect(self.f.db_path)
        with self.assertRaises(sqlite3.OperationalError):
            conn.execute("UPDATE tasks SET title='x'")
        conn.close()

    def test_board_filter(self):
        model, _ = build(self.f.home, boards=["routing-scratch"])
        self.assertEqual([t["id"] for t in model["tasks"]], ["t_scratch"])


if __name__ == "__main__":
    unittest.main()
