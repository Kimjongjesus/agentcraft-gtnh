"""Regression tests for review r1: excerpt-before-filter leak, flag-style secrets, cross-board joins.

All canaries are synthetic strings built for the test; nothing here is a real credential.
Every privacy assertion is made on the SERIALIZED snapshot (json.dumps of everything the adapter
would send), not only on clean().
"""

import json
import tempfile
import time
import unittest
from pathlib import Path

from hermes_adapter.mapping import Mapper, parse_decision_reason
from hermes_adapter.redact import REDACTED, WITHHELD, clean, clean_excerpt
from hermes_adapter.sources import HermesSource

from fixture import Fixture, standard

PRIVATE_CANARY = "CANARY" + "_PRIVATE_NOTE_LINE"
SECRET_CANARY = "SYNTHETIC" + "_SECRET_CANARY"
HINT = "Source: personal-" + "schedule.md"


def build(home: Path):
    return Mapper().build(HermesSource(home).read())


class FlagSecretTest(unittest.TestCase):
    """Finding 2: whitespace-separated sensitive flags and their equivalents."""

    CASES = [
        f"run deploy --password {SECRET_CANARY} now",
        f"run deploy --password '{SECRET_CANARY} two words' now",
        f'run deploy --password "{SECRET_CANARY} two words" now',
        f"curl --api-key {SECRET_CANARY} https://x",
        f"curl --api_key '{SECRET_CANARY}'",
        f"gh auth login --with-token {SECRET_CANARY}",
        f"tool --token {SECRET_CANARY}",
        f"tool -token {SECRET_CANARY}",
        f"tool --client-secret {SECRET_CANARY}",
        f"tool --auth-key {SECRET_CANARY}",
        f"tool --access-key {SECRET_CANARY}",
        f"tool --cookie {SECRET_CANARY}",
        f"tool --credentials {SECRET_CANARY}",
        f"tool --pass {SECRET_CANARY}",
        f"tool --password\t{SECRET_CANARY}",
        f"tool --password={SECRET_CANARY}",
        f'curl -H "Authorization: token {SECRET_CANARY}" https://api',
        f"curl -H 'Authorization: Basic {SECRET_CANARY}'",
        f"sshpass -p {SECRET_CANARY} ssh host",
        f"sshpass -p'{SECRET_CANARY}' ssh host",
        f"the password is {SECRET_CANARY}",
        f"password {SECRET_CANARY}",
        f"Passphrase: {SECRET_CANARY}",
        f'tool --password "{SECRET_CANARY} unterminated quote',
    ]

    def test_flags_redacted(self):
        for case in self.CASES:
            out = clean(case)
            self.assertNotIn(SECRET_CANARY, out, case)
            self.assertNotIn("SYNTHETIC", out, case)
            self.assertIn(REDACTED, out, case)

    def test_prose_stays_readable(self):
        for s in ("password reset flow works", "secret stripping is tested", "token budget exceeded",
                  "added an api-key rotation note", "Password reset flow"):
            self.assertEqual(clean(s), s, s)


class ExcerptTest(unittest.TestCase):
    """Finding 1: filtering runs on the whole source before a line is cut out."""

    def test_personal_hint_on_a_later_line(self):
        self.assertEqual(clean_excerpt(f"PROGRESS: {PRIVATE_CANARY}\n{HINT}", 48), WITHHELD)
        self.assertEqual(clean_excerpt(f"{PRIVATE_CANARY}\n\n\nsee memory/personal notes"), WITHHELD)

    def test_secret_spanning_lines(self):
        key = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + SECRET_CANARY + "\n-----END OPENSSH PRIVATE KEY-----"
        out = clean_excerpt("PROGRESS: " + key)
        self.assertNotIn(SECRET_CANARY, out)

    def test_first_line_and_prefix(self):
        import re
        self.assertEqual(clean_excerpt("\n\nPROGRESS: wiring\nmore", 48, re.compile(r"^PROGRESS:\s*")), "wiring")

    def test_decision_split_keeps_context(self):
        # hint in the CHOICES half, canary in the question half (and the reverse)
        kind, q, opts = parse_decision_reason(f"QUESTION q1: {PRIVATE_CANARY}? || CHOICES: yes | no ({HINT})")
        self.assertEqual((kind, q, opts), ("question", WITHHELD, []))
        kind, q, opts = parse_decision_reason(f"PERMISSION p1: read {HINT} || CHOICES: {PRIVATE_CANARY} | Deny")
        self.assertEqual((kind, q, opts), ("permission", WITHHELD, []))
        # a quoted secret that contains the option separator
        kind, q, opts = parse_decision_reason(f'QUESTION q2: which? || CHOICES: --api-key "{SECRET_CANARY} | x" | No')
        self.assertNotIn(SECRET_CANARY, json.dumps([q, opts]))
        self.assertIn("No", opts)


class SnapshotPrivacyTest(unittest.TestCase):
    """Findings 1 + 2 end to end: nothing reaches the serialized snapshot."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = int(time.time())
        self.f = standard(Path(self.tmp.name), self.now)

    def tearDown(self):
        self.tmp.cleanup()

    def test_multiline_comment_canary(self):
        self.f.comment("t_build", "builder-a", f"PROGRESS: {PRIVATE_CANARY}\n{HINT}", at=self.now - 5)
        model = build(self.f.home)
        self.assertNotIn("CANARY", json.dumps(model))
        a = {a["id"]: a for a in model["agents"]}["builder-a"]
        self.assertEqual(a["activity"], WITHHELD)

    def test_multiline_heartbeat_canary(self):
        # run 1 is t_build's live builder-a run in the standard fixture
        self.f.event("t_build", "heartbeat", {"note": f"{PRIVATE_CANARY}\n{HINT}"}, run_id=1, at=self.now - 1)
        model = build(self.f.home)
        self.assertNotIn("CANARY", json.dumps(model))
        self.assertEqual({a["id"]: a for a in model["agents"]}["builder-a"]["activity"], WITHHELD)

    def test_decision_canary(self):
        self.f.task("t_q", "Ask the owner", "blocked", "builder-b", block_kind="needs_input")
        self.f.event("t_q", "blocked", {"kind": "needs_input",
                                        "reason": f"QUESTION q1: {PRIVATE_CANARY}? || CHOICES: a | b ({HINT})"})
        model = build(self.f.home)
        self.assertNotIn("CANARY", json.dumps(model))
        (d,) = [d for d in model["decisions"] if d["taskId"] == "t_q"]
        self.assertEqual((d["question"], d["options"]), (WITHHELD, []))

    def test_flag_secrets_in_every_field(self):
        sec = f"deploy --password {SECRET_CANARY} --api-key '{SECRET_CANARY} b' -H \"Authorization: token {SECRET_CANARY}\""
        self.f.task("t_sec", f"Title {sec}", "blocked", "builder-b", body=sec, block_kind="needs_input",
                    result=sec, branch=f"b --token {SECRET_CANARY}")
        self.f.event("t_sec", "blocked", {"kind": "needs_input", "reason": f"PERMISSION p1: {sec} || CHOICES: Approve | Deny"})
        self.f.run("t_sec", "builder-b", status="done", ended=self.now - 5, outcome="completed", summary=sec)
        self.f.comment("t_sec", "builder-b", f"PROGRESS: {sec}")
        self.f.event("t_sec", "completed", {"summary": sec})
        model = build(self.f.home)
        blob = json.dumps(model)
        self.assertNotIn(SECRET_CANARY, blob)
        self.assertNotIn("SYNTHETIC", blob)
        t = {t["id"]: t for t in model["tasks"]}["t_sec"]
        self.assertIn(REDACTED, t["description"])
        self.assertIn("--password", t["description"])


class TwoBoardTest(unittest.TestCase):
    """Finding 3: two boards with identical rowids (run 1 / event 1 / comment 1) stay separate."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = now = int(time.time())
        f = self.f = Fixture(Path(self.tmp.name), now)
        ops = self.ops = f.add_board("ops")
        ids = []
        for board, prof, word in ((f, "builder-a", "alpha"), (ops, "reviewer-a", "beta")):
            board.task(f"t_{word}", f"{word} card", "blocked", prof, block_kind="needs_input")
            board.task("t_same", f"same id on {word}", "todo", prof)
            board.task(f"t_{word}_live", f"{word} live card", "running", prof)
            r1 = board.run(f"t_{word}", prof, status="blocked", started=now - 300, ended=now - 200, outcome="blocked")
            e1 = board.event(f"t_{word}", "blocked", {"kind": "needs_input",
                                                       "reason": f"QUESTION q1: {word} question? || CHOICES: {word}-yes | {word}-no"},
                             run_id=r1, at=now - 200)
            r2 = board.run(f"t_{word}_live", prof, started=now - 100)
            e2 = board.event(f"t_{word}_live", "heartbeat", {"note": f"{word} heartbeat note"}, run_id=r2, at=now - 10)
            c1 = board.comment(f"t_{word}_live", prof, f"PROGRESS: {word} comment", at=now - 50)
            ids.append((r1, e1, r2, e2, c1))
        # the point of the fixture: both boards really do reuse the same rowids
        self.assertEqual(ids[0], ids[1])
        self.model = build(f.home)

    def tearDown(self):
        self.tmp.cleanup()

    def test_decisions_are_distinct_and_correctly_attributed(self):
        ds = self.model["decisions"]
        self.assertEqual(len(ds), 2)
        self.assertEqual(len({d["id"] for d in ds}), 2)
        by_task = {d["taskId"]: d for d in ds}
        self.assertEqual(by_task["t_alpha"]["agentId"], "builder-a")
        self.assertEqual(by_task["t_beta"]["agentId"], "reviewer-a")
        self.assertIn("alpha question", by_task["t_alpha"]["question"])
        self.assertIn("beta question", by_task["t_beta"]["question"])
        self.assertEqual(by_task["t_alpha"]["options"], ["alpha-yes", "alpha-no"])
        self.assertTrue(all(d["status"] == "open" for d in ds))

    def test_agent_activity_stays_on_its_board(self):
        agents = {a["id"]: a for a in self.model["agents"]}
        self.assertEqual(agents["builder-a"]["taskId"], "t_alpha_live")
        self.assertEqual(agents["reviewer-a"]["taskId"], "t_beta_live")
        self.assertEqual(agents["builder-a"]["activity"], "alpha heartbeat note")
        self.assertEqual(agents["reviewer-a"]["activity"], "beta heartbeat note")

    def test_logs_and_feed_attribution(self):
        logs = {l["agentId"]: " ".join(e["text"] for e in l["entries"]) for l in self.model["logs"]}
        self.assertIn("alpha", logs["builder-a"])
        self.assertNotIn("beta", logs["builder-a"])
        self.assertIn("beta", logs["reviewer-a"])
        self.assertNotIn("alpha", logs["reviewer-a"])
        for item in self.model["feed"]:
            if "alpha" in item["text"]:
                self.assertEqual(item.get("agentId"), "builder-a", item)
            if "beta" in item["text"]:
                self.assertEqual(item.get("agentId"), "reviewer-a", item)

    def test_same_card_id_on_two_boards(self):
        tasks = {t["id"]: t for t in self.model["tasks"]}
        self.assertNotIn("t_same", tasks)
        self.assertEqual(tasks["main:t_same"]["board"], "main")
        self.assertEqual(tasks["ops:t_same"]["board"], "ops")
        self.assertEqual(tasks["main:t_same"]["assignee"], "builder-a")
        self.assertEqual(tasks["ops:t_same"]["assignee"], "reviewer-a")
        self.assertEqual(len(tasks), len(self.model["tasks"]), "task ids are unique")
        # unique ids are left alone
        self.assertEqual(tasks["t_alpha"]["board"], "main")


if __name__ == "__main__":
    unittest.main()
