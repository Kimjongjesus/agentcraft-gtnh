"""Card 3 (task wall, library, atrium): goals per board and the read-only library.

Every privacy assertion is made on SERIALIZED output (json.dumps of the snapshot, or of the
incremental messages AdapterServer.changes() would broadcast), for both paths. Canaries are
synthetic strings built at runtime; nothing here is a real credential.
"""

import json
import tempfile
import time
import unittest
from pathlib import Path

from hermes_adapter.mapping import LIBRARY_BODY, LIBRARY_MAX, Mapper
from hermes_adapter.redact import WITHHELD
from hermes_adapter.server import AdapterServer
from hermes_adapter.sources import HermesSource

from fixture import FAKE_GH, FAKE_TOKEN, standard

PRIVATE_CANARY = "CANARY" + "_PRIVATE_NOTE_LINE"
SECRET_CANARY = "SYNTHETIC" + "_SECRET_CANARY"
HINT = "Source: personal-" + "schedule.md"


def build(home: Path) -> dict:
    return Mapper().build(HermesSource(home).read())


def snapshot_of(model: dict) -> dict:
    srv = AdapterServer(lambda: None, Mapper())
    srv.model = model
    return srv.snapshot()


class Card3Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = int(time.time())
        self.f = standard(Path(self.tmp.name), self.now)

    def tearDown(self):
        self.tmp.cleanup()

    def mem(self, model: dict) -> dict:
        return {e["id"]: e for e in model["memory"]}


class GoalTest(Card3Base):
    def test_one_goal_per_board_with_counts(self):
        m = build(self.f.home)
        (g,) = m["goals"]
        self.assertEqual(g["id"], "board-homelab")
        self.assertEqual(g["board"], "homelab")
        self.assertEqual(g["counts"], {"todo": 1, "doing": 1, "review": 0, "done": 1, "blocked": 1})
        self.assertEqual(g["total"], 4)
        self.assertEqual(g["progress"], 0.25)
        self.assertEqual(g["status"], "active")
        self.assertEqual(g["openDecisions"], 1)
        self.assertTrue(all(t["goalId"] == "board-homelab" for t in m["tasks"]))
        self.assertNotIn("ship it now", json.dumps(m["goals"]), "goals carry a decision COUNT only")

    def test_two_boards_are_separate_goals(self):
        other = self.f.add_board("ai-ops")
        other.task("t_x", "Game project: level polish", "done", "claude-builder")
        other.task("t_build", "Same id on another board", "todo")
        m = build(self.f.home)
        goals = {g["id"]: g for g in m["goals"]}
        self.assertEqual(set(goals), {"board-homelab", "board-ai-ops"})
        self.assertEqual(goals["board-ai-ops"]["counts"]["done"], 1)
        self.assertEqual(goals["board-ai-ops"]["total"], 2)
        self.assertEqual(goals["board-homelab"]["total"], 4, "board-scoped: the other board's cards do not count")
        ids = {t["id"]: t["goalId"] for t in m["tasks"]}
        self.assertEqual(ids["ai-ops:t_build"], "board-ai-ops")
        self.assertEqual(ids["homelab:t_build"], "board-homelab")

    def test_goal_upsert_on_progress(self):
        old = build(self.f.home)
        self.f.sql("UPDATE tasks SET status='done', completed_at=? WHERE id='t_build'", (self.now,))
        new = build(self.f.home)
        msgs = AdapterServer.changes(old, new)
        (g,) = [m["goal"] for m in msgs if m["type"] == "goal.upsert"]
        self.assertEqual(g["counts"]["done"], 2)
        self.assertEqual(g["progress"], 0.5)
        self.assertEqual(AdapterServer.changes(new, build(self.f.home)), [], "no change -> no messages")

    def test_snapshot_goal_is_current(self):
        snap = snapshot_of(build(self.f.home))
        self.assertEqual(snap["goal"]["id"], "board-homelab")

    def test_done_cards_older_than_the_window_count_toward_progress(self):
        # the ring is the board's overall progress, not "done in the last 3 days"
        for i in range(4):
            self.f.task(f"t_old{i}", f"Game project: old level {i}", "done", "claude-builder")
            self.f.sql("UPDATE tasks SET created_at=?, completed_at=? WHERE id=?",
                       (self.now - 40 * 86400, self.now - 30 * 86400, f"t_old{i}"))
        old = build(self.f.home)
        self.assertNotIn("homelab:t_old0", {t["id"] for t in old["tasks"]}, "old done cards stay out of the task list")
        (g,) = old["goals"]
        self.assertEqual(g["counts"], {"todo": 1, "doing": 1, "review": 0, "done": 5, "blocked": 1})
        self.assertEqual(g["total"], 8)
        self.assertEqual(g["progress"], 0.625)
        snap = snapshot_of(old)
        self.assertEqual({x["id"]: x for x in snap["goals"]}["board-homelab"]["counts"]["done"], 5, "full snapshot")
        # incremental: archiving an old done card changes the count -> one goal.upsert
        self.f.sql("UPDATE tasks SET status='archived' WHERE id='t_old0'")
        msgs = AdapterServer.changes(old, build(self.f.home))
        (g2,) = [m["goal"] for m in msgs if m["type"] == "goal.upsert"]
        self.assertEqual(g2["counts"]["done"], 4)
        self.assertEqual(g2["total"], 7)
        self.assertFalse([m for m in msgs if m["type"] == "task.upsert"], "the old card was never on the wall")


class LibrarySourceTest(Card3Base):
    def test_sources_and_kinds(self):
        self.f.comment("t_build", "claude-builder", "PLAN: 1) bridge 2) wall 3) library", at=self.now - 10)
        self.f.comment("t_build", "claude-builder", "HANDOFF: wall done, library next", at=self.now - 5)
        self.f.comment("t_build", "sol-reviewer", "PASS: looks right", at=self.now - 4)
        self.f.comment("t_build", "eli-via-intake", "ELI DECISION: keep going", at=self.now - 3)
        self.f.comment("t_build", "claude-builder", "just chatting, no prefix", at=self.now - 2)
        self.f.run("t_parent", "claude-builder-sonnet", status="done", started=self.now - 7000, ended=self.now - 6000,
                   outcome="completed", summary="Shipped the parent card cleanly")
        m = build(self.f.home)
        mem = self.mem(m)
        self.assertIn("claude-builder/plan-t_build", mem)
        self.assertIn("claude-builder/handoff-t_build", mem)
        self.assertIn("sol-reviewer/review-t_build", mem)
        self.assertIn("shared/done-t_parent", mem)
        self.assertIn("shared/board-homelab", mem)
        self.assertIn("sol-reviewer/decision-d-homelab-2", mem)
        self.assertEqual(mem["claude-builder/plan-t_build"]["body"], "PLAN: 1) bridge 2) wall 3) library")
        self.assertEqual(mem["shared/done-t_parent"]["body"], "Shipped the parent card cleanly")
        blob = json.dumps(m["memory"])
        self.assertNotIn("ELI DECISION", blob, "comments by non-profiles (Eli / intake) are not library notes")
        self.assertNotIn("just chatting", blob, "only PLAN/HANDOFF/review comments are notes")
        self.assertIn("Build the thing", mem["shared/board-homelab"]["body"])
        self.assertIn("ship it now", mem["sol-reviewer/decision-d-homelab-2"]["body"])
        for e in m["memory"]:
            for k in ("id", "scope", "title", "body", "updated"):
                self.assertIn(k, e)
            self.assertIsInstance(e["updated"], int)

    def test_newest_note_per_card_wins(self):
        self.f.comment("t_build", "claude-builder", "PLAN: first plan", at=self.now - 50)
        self.f.comment("t_build", "claude-builder", "PLAN: second plan", at=self.now - 5)
        mem = self.mem(build(self.f.home))
        self.assertEqual(mem["claude-builder/plan-t_build"]["body"], "PLAN: second plan")

    def test_bounds(self):
        for i in range(90):
            self.f.task(f"t_n{i}", f"Game project: level {i}", "todo", "claude-builder")
            self.f.comment(f"t_n{i}", "claude-builder", "PLAN: " + ("long words " * 400), at=self.now - i)
        m = build(self.f.home)
        self.assertLessEqual(len(m["memory"]), LIBRARY_MAX)
        self.assertTrue(all(len(e["body"]) <= LIBRARY_BODY for e in m["memory"]))
        self.assertTrue(all(len(e["title"]) <= 100 for e in m["memory"]))
        self.assertLessEqual(len(json.dumps(m["memory"])), LIBRARY_MAX * (LIBRARY_BODY + 600))


class LibraryPrivacyTest(Card3Base):
    """Whole-source filtering before the 1200-char cut, on the snapshot AND the incremental path."""

    def plant(self, at: int) -> None:
        # the personal-notes hint sits far beyond the display cut: excerpting first would leak line 1
        self.f.comment("t_build", "claude-builder",
                       f"PLAN: {PRIVATE_CANARY}\n" + ("filler line\n" * 300) + HINT, at=at)
        self.f.comment("t_build", "claude-builder",
                       f"HANDOFF: deploy --password {SECRET_CANARY} then use {FAKE_TOKEN} and {FAKE_GH}", at=at + 1)
        self.f.task("t_sum", "Game project: release notes", "done", "claude-builder")
        self.f.sql("UPDATE tasks SET completed_at=? WHERE id='t_sum'", (at,))
        self.f.run("t_sum", "claude-builder", status="done", started=at - 100, ended=at, outcome="completed",
                   summary=f"Done. Wrote notes; tool --api-key '{SECRET_CANARY}' and /home/aiops/.hermes/auth.json")

    def check(self, blob: str) -> None:
        for bad in (PRIVATE_CANARY, SECRET_CANARY, FAKE_TOKEN, FAKE_GH, "personal-schedule", "auth.json", "/home/aiops"):
            self.assertNotIn(bad, blob, bad)

    def test_snapshot(self):
        self.plant(self.now - 20)
        snap = snapshot_of(build(self.f.home))
        self.check(json.dumps(snap))
        mem = {e["id"]: e for e in snap["memory"]}
        self.assertEqual(mem["claude-builder/plan-t_build"]["body"], WITHHELD)
        self.assertIn("--password [redacted]", mem["claude-builder/handoff-t_build"]["body"])
        self.assertIn("[redacted]", mem["shared/done-t_sum"]["body"])

    def test_incremental(self):
        old = build(self.f.home)
        self.plant(self.now - 20)
        msgs = AdapterServer.changes(old, build(self.f.home))
        mem = {m["entry"]["id"]: m["entry"] for m in msgs if m["type"] == "memory.upsert"}
        self.assertIn("claude-builder/plan-t_build", mem)
        self.assertIn("claude-builder/handoff-t_build", mem)
        self.assertIn("shared/done-t_sum", mem)
        self.assertEqual(mem["claude-builder/plan-t_build"]["body"], WITHHELD)
        self.check(json.dumps(msgs))

    def test_tombstone_when_decision_answered(self):
        old = build(self.f.home)
        self.assertIn("sol-reviewer/decision-d-homelab-2", self.mem(old))
        self.f.sql("UPDATE tasks SET status='ready' WHERE id='t_wait'")
        self.f.event("t_wait", "unblocked", None)
        new = build(self.f.home)
        msgs = AdapterServer.changes(old, new)
        (tomb,) = [m["entry"] for m in msgs if m["type"] == "memory.upsert" and m["entry"]["id"] == "sol-reviewer/decision-d-homelab-2"]
        self.assertTrue(tomb["removed"])
        self.assertEqual(tomb["body"], "")
        self.assertNotIn("sol-reviewer/decision-d-homelab-2", {e["id"] for e in snapshot_of(new)["memory"]},
                         "tombstones never appear in a snapshot")
        again = AdapterServer.changes(new, build(self.f.home))
        self.assertFalse([m for m in again if m["type"] == "memory.upsert"], "a tombstone is sent once")
        goal = [m["goal"] for m in msgs if m["type"] == "goal.upsert"]
        self.assertEqual(goal[0]["openDecisions"], 0)


if __name__ == "__main__":
    unittest.main()
