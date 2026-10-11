"""Unit tests: frames, policy, classification, ledger, executors' argv, CLI start-up checks, bind rules,
and the read adapter still refusing every action.* message."""

from __future__ import annotations

import io
import asyncio
import json
import os
import stat
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

from test_control_common import ACTOR, FIXTURE, KEY_HEX, Env, base_policy

from hermes_control import classify, cli, executors, frames, ledger as ledger_mod, policy as pol, safety
from hermes_control.service import chunk_paragraphs, validate_bind

PKG = Path(__file__).resolve().parent.parent / "hermes_control"


def tmpdir() -> tempfile.TemporaryDirectory:
    d = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
    os.chmod(d.name, 0o700)
    return d


class FramesTest(unittest.TestCase):
    key = bytes.fromhex(KEY_HEX)

    def test_sign_and_verify(self):
        s = frames.sign(self.key, "{\"a\":1}")
        self.assertRegex(s, r"^[0-9a-f]{64}$")
        self.assertTrue(frames.verify(self.key, "{\"a\":1}", s))
        self.assertFalse(frames.verify(self.key, "{\"a\":2}", s))
        self.assertFalse(frames.verify(b"x" * 32, "{\"a\":1}", s))

    def test_outer_shape(self):
        ok = json.dumps({"v": 1, "type": "x.y", "payload": "{}", "sig": "0" * 64})
        self.assertEqual(frames.parse_outer(ok)["type"], "x.y")
        bad = [
            {"v": 1, "type": "x", "payload": "{}"}, {"v": 1, "type": "x", "payload": "{}", "sig": "0" * 64, "k": 1},
            {"v": 2, "type": "x", "payload": "{}", "sig": "0" * 64}, {"v": True, "type": "x", "payload": "{}", "sig": "0" * 64},
            {"v": 1.0, "type": "x", "payload": "{}", "sig": "0" * 64}, {"v": 1, "type": 5, "payload": "{}", "sig": "0" * 64},
            {"v": 1, "type": "x", "payload": {}, "sig": "0" * 64}, {"v": 1, "type": "x", "payload": "{}", "sig": "A" * 64},
            {"v": 1, "type": "x", "payload": "{}", "sig": "0" * 63}, {"v": 1, "type": "x y", "payload": "{}", "sig": "0" * 64},
        ]
        for b in bad:
            with self.assertRaises(frames.FrameError, msg=b):
                frames.parse_outer(json.dumps(b))
        for text in ("[]", "{", "null", '{"v":1,"v":1,"type":"x","payload":"{}","sig":"' + "0" * 64 + '"}'):
            with self.assertRaises(frames.FrameError):
                frames.parse_outer(text)

    def test_strict_json(self):
        for text in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}', "[" * 20000 + "]" * 20000):
            with self.assertRaises(frames.FrameError):
                frames.strict_loads(text)
        self.assertEqual(frames.strict_loads('{"a":[1,{"b":2}]}'), {"a": [1, {"b": 2}]})

    def common(self, **over):
        d = {"type": "action.cancel", "session": "a" * 32, "dir": "g2c", "id": "x1", "nonce": "b" * 32, "ts": 1, "actor": {"uuid": ACTOR, "name": "n"}, "token": "c" * 32}
        d.update(over)
        return d

    def test_payload_checks(self):
        frames.check_payload(self.common(), "action.cancel")
        for over in ({"dir": "c2g"}, {"type": "action.confirm"}, {"ts": True}, {"ts": 1.0}, {"ts": -1}, {"token": "g" * 32}, {"extra": 1}, {"id": "bad id"}):
            with self.assertRaises(frames.FrameError, msg=over):
                frames.check_payload(self.common(**over), "action.cancel")
        d = self.common()
        del d["nonce"]
        with self.assertRaises(frames.FrameError):
            frames.check_payload(d, "action.cancel")
        with self.assertRaises(frames.FrameError):
            frames.check_payload(self.common(), "nope")

    def test_args_per_capability(self):
        frames.check_args("card.create", {"board": "main", "title": "t", "priority": 0})
        frames.check_args("card.edit", {"card": "c", "comment": "x"})
        frames.check_args("card.edit", {"card": "c", "title": "x", "priority": 1})
        frames.check_args("decision.answer", {"card": "c", "decision": "d", "text": "x"})
        for cap, a in (("card.edit", {"card": "c"}), ("card.edit", {"card": "c", "comment": "x", "body": "y"}), ("decision.answer", {"card": "c", "decision": "d"}),
                       ("card.create", {"board": "m", "title": "t", "priority": True}), ("service.restart", {"service": "a", "b": "c"}),
                       ("decision.answer", {"card": "c", "decision": "d" * 17, "choice": "x"})):
            with self.assertRaises(frames.FrameError, msg=(cap, a)):
                frames.check_args(cap, a)

    def test_digest_is_canonical(self):
        a = frames.digest("card.create", 1, {"board": "m", "title": "t"})
        b = frames.digest("card.create", 1, {"title": "t", "board": "m"})
        self.assertEqual(a, b)
        self.assertNotEqual(a, frames.digest("card.create", 1, {"board": "m", "title": "u"}))
        self.assertNotEqual(a, frames.digest("card.create", 2, {"board": "m", "title": "t"}))

    def test_key_file_rules(self):
        with tmpdir() as d:
            p = Path(d) / "k"
            p.write_text("# comment\n\n" + KEY_HEX + "\n")
            os.chmod(p, 0o600)
            self.assertEqual(frames.load_key(p), self.key)
            for mode in (0o640, 0o604, 0o644, 0o660, 0o666, 0o602):
                os.chmod(p, mode)
                with self.assertRaises(frames.FrameError, msg=oct(mode)):
                    frames.load_key(p)
            os.chmod(p, 0o600)
            for text in ("", "# only a comment\n", "abcd\n", "zz" * 32 + "\n", KEY_HEX[:-1] + "\n", "00" * 32 + "\n"):
                p.write_text(text)
                with self.assertRaises(frames.FrameError, msg=text):
                    frames.load_key(p)
            link = Path(d) / "link"
            p.write_text(KEY_HEX + "\n")
            os.symlink(p, link)
            with self.assertRaises(frames.FrameError):
                frames.load_key(link)

    def test_key_never_appears_in_error_messages(self):
        with tmpdir() as d:
            p = Path(d) / "k"
            p.write_text(KEY_HEX[:-1] + "\n")
            os.chmod(p, 0o600)
            try:
                frames.load_key(p)
            except frames.FrameError as e:
                self.assertNotIn(KEY_HEX[:20], str(e))


class PolicyTest(unittest.TestCase):
    def test_shipped_policies_are_valid(self):
        ex = pol.load(PKG / "policy.example.json", check_perms=False)
        self.assertEqual(ex.actor_list, (ACTOR,))
        for tier2 in ("card.dispatch", "service.restart", "cron.run", "agent.chat", "agent.ask"):
            self.assertFalse(ex.cap(tier2).enabled, tier2)
        self.assertTrue(ex.lock_sets_hermes_lock)
        self.assertFalse(ex.cap("decision.answer").handoff_answerable)
        empty = pol.load(PKG / "policy.empty.json", check_perms=False)
        self.assertEqual(empty.actor_list, ())
        self.assertFalse(any(c.enabled for c in empty.caps.values()))

    def test_example_contains_placeholders_only(self):
        text = (PKG / "policy.example.json").read_text() + (PKG / "fixtures" / "board.example.json").read_text() + (PKG / "policy.empty.json").read_text()
        text += (PKG.parent / "CONTROL.md").read_text() + (PKG.parent / "systemd" / "hermes-agentcraft-control.service").read_text()
        text += (PKG.parent / "scripts" / "control_client.py").read_text()
        import re

        self.assertNotRegex(text, r"\b(?:10|172\.(?:1[6-9]|2\d|3[01])|192\.168)\.\d+\.\d+")
        self.assertNotRegex(text, r"(?i)robertsnest|\.lan\b|/home/")
        for uuid in re.findall(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", text):
            self.assertEqual(uuid, ACTOR)

    def test_typos_and_unsafe_values_refuse(self):
        def with_(mut):
            d = base_policy()
            mut(d)
            return d

        bad = {
            "unknown capability": with_(lambda d: d["capabilities"].update({"host.reboot": {"enabled": True}})),
            "typo in capability": with_(lambda d: d["capabilities"].update({"card.creat": {"enabled": True}})),
            "unknown top key": with_(lambda d: d.update({"capabilites": {}})),
            "unknown cap key": with_(lambda d: d["capabilities"]["card.create"].update({"bords": ["x"]})),
            "unknown service key": with_(lambda d: d["services"]["service-1"].update({"shell": True})),
            "permissionApprove true": with_(lambda d: d["capabilities"]["decision.answer"].update({"permissionApprove": True})),
            "permissionApprove string": with_(lambda d: d["capabilities"]["decision.answer"].update({"permissionApprove": "false"})),
            "denied toolset terminal": with_(lambda d: d["capabilities"]["agent.chat"].update({"toolsets": ["search", "terminal"]})),
            "denied toolset shell": with_(lambda d: d["capabilities"]["agent.ask"].update({"toolsets": ["shell"]})),
            "denied toolset code": with_(lambda d: d["capabilities"]["agent.ask"].update({"toolsets": ["code_execution"]})),
            "denied toolset delegation": with_(lambda d: d["capabilities"]["agent.ask"].update({"toolsets": ["delegation"]})),
            "denied toolset all": with_(lambda d: d["capabilities"]["agent.ask"].update({"toolsets": ["all"]})),
            "toolset on disabled chat": with_(lambda d: d["capabilities"]["agent.ask"].update({"enabled": False, "toolsets": ["terminal"]})),
            "enabled without enabled key": with_(lambda d: d["capabilities"].update({"cron.run": {}})),
            "enabled with empty allowlist": with_(lambda d: d["capabilities"]["card.create"].update({"boards": []})),
            "chat without toolset": with_(lambda d: d["capabilities"]["agent.chat"].update({"toolsets": []})),
            "name with leading dash": with_(lambda d: d["capabilities"]["card.create"].update({"boards": ["-x"]})),
            "name with slash": with_(lambda d: d["jobs"].append("a/b")),
            "offline uuid": with_(lambda d: d.update({"actors": ["00000000-0000-3000-8000-000000000001"]})),
            "uppercase uuid": with_(lambda d: d.update({"actors": [ACTOR.upper().replace("0", "A", 1)]})),
            "actor is a name": with_(lambda d: d.update({"actors": ["Steve"]})),
            "enabled without actors": with_(lambda d: d.update({"actors": []})),
            "limit loosened": with_(lambda d: d["capabilities"]["card.create"].update({"limits": {"perHour": 99}})),
            "target window shortened": with_(lambda d: d["capabilities"]["card.dispatch"].update({"limits": {"perTargetSeconds": 5}})),
            "unknown limit key": with_(lambda d: d["capabilities"]["card.create"].update({"limits": {"perDay": 1}})),
            "bad schema": with_(lambda d: d.update({"schema": 2})),
            "argv empty": with_(lambda d: d["services"]["service-1"].update({"argv": []})),
            "argv dash program": with_(lambda d: d["services"]["service-1"].update({"argv": ["-rf"]})),
            "argv not strings": with_(lambda d: d["services"]["service-1"].update({"argv": ["a", 1]})),
            "program dash": with_(lambda d: d.update({"hermesProgram": ["--yolo"]})),
            "lockSetsHermesLock string": with_(lambda d: d.update({"lockSetsHermesLock": "yes"})),
        }
        for why, d in bad.items():
            with self.assertRaises(pol.PolicyError, msg=why):
                pol.parse(d)

    def test_defaults(self):
        d = base_policy()
        del d["lockSetsHermesLock"]
        p = pol.parse(d)
        self.assertTrue(p.lock_sets_hermes_lock)
        self.assertFalse(p.cap("decision.answer").permission_approve)
        self.assertFalse(p.cap("decision.answer").handoff_answerable)
        self.assertEqual(p.cap("card.dispatch").limits, {"perHour": 6, "perTargetCount": 1, "perTargetSeconds": 600})
        self.assertEqual(p.cap("agent.ask").limits, {"perMinute": 10})

    def test_tightened_limits_are_accepted(self):
        d = base_policy()
        d["capabilities"]["card.dispatch"]["limits"] = {"perHour": 2, "perTargetSeconds": 1200}
        p = pol.parse(d)
        self.assertEqual(p.cap("card.dispatch").limits["perHour"], 2)

    def test_wire_policy_is_valid_and_lists_every_capability(self):
        w = pol.parse(base_policy()).wire("rev.0", True)
        frames.check_payload({"type": "action.policy", "session": "a" * 32, "dir": "c2g", "id": "c1", "nonce": "b" * 32, "ts": 1, **w}, "action.policy")
        self.assertEqual(set(w["capabilities"]), set(frames.CAPABILITIES))
        self.assertTrue(w["dryRun"])
        self.assertEqual(w["capabilities"]["card.dispatch"]["confirm"], True)
        self.assertEqual(w["capabilities"]["service.restart"]["tier"], 2)

    def test_duplicate_keys_in_the_file_refuse(self):
        with tmpdir() as d:
            p = Path(d) / "policy.json"
            p.write_text('{"schema":1,"schema":1,"actors":[],"capabilities":{}}')
            os.chmod(p, 0o600)
            with self.assertRaises(pol.PolicyError):
                pol.load(p)

    def test_chat_toolsets_are_an_allowlist_not_a_denylist(self):
        # review r1 R2: these all reach shell / write / mutation tools inside Hermes; a name denylist let some through
        for name in ("debugging", "file", "skills", "safe", "coding", "web", "memory", "read-only", "custom-readonly", "terminal", "shell",
                     "code_execution", "delegation", "process", "computer_use", "browser", "cron", "cronjob", "kanban", "patch", "mcp-github",
                     "all", "*", "hermes-cli", "Search", " search", "search ", "search,terminal"):
            self.assertTrue(pol.toolset_denied(name), name)
            self.assertFalse(pol.toolset_allowed(name), name)
        self.assertEqual(pol.CHAT_TOOLSETS_VETTED, frozenset({"search"}))
        self.assertTrue(pol.toolset_allowed("search"))


class ClassifyTest(unittest.TestCase):
    def test_kinds(self):
        c = classify.classify
        self.assertEqual(c("QUESTION q1: pick one || CHOICES: A | B", None).kind, classify.QUESTION)
        self.assertEqual(c("NEEDS INPUT: what name?", "needs_input").kind, classify.QUESTION)
        self.assertEqual(c("PERMISSION p1: run x || CHOICES: Approve | Deny", None).kind, classify.PERMISSION)
        self.assertEqual(c("permission p1: run x", None).kind, classify.PERMISSION)
        self.assertEqual(c("QUESTION: do you grant access?", None).kind, classify.PERMISSION)
        self.assertEqual(c("DEMO READY d1: look", None).kind, classify.HANDOFF)
        self.assertEqual(c("REVISE r: again", None).kind, classify.HANDOFF)
        self.assertEqual(c("QUESTION q: send to review now? || CHOICES: Send to review | Wait", None).kind, classify.HANDOFF)
        self.assertEqual(c("just a sentence", None).kind, classify.UNKNOWN)
        self.assertEqual(c("", None).kind, classify.UNKNOWN)
        self.assertEqual(c("QUESTION q: x", "dispatch_hold").kind, classify.UNKNOWN)
        self.assertEqual(c("QUESTION q: see personal-notes.md for details", None).kind, classify.UNKNOWN)

    def test_permission_wins_over_everything(self):
        c = classify.classify("DEMO READY d1: may I run this? || CHOICES: Approve | Deny", None)
        self.assertEqual(c.kind, classify.PERMISSION)

    def test_resolve_permission(self):
        cls = classify.classify("PERMISSION p1: x || CHOICES: Approve | Deny", None)
        r = classify.resolve_answer
        self.assertTrue(r(cls, "Deny", None).ok)
        self.assertTrue(r(cls, "Deny", "because").ok)
        self.assertEqual(r(cls, "Deny", "note").note, "note")
        for choice in ("Approve", "approve", " Approve ", "APPROVE", "Approve\u200b", "Deny ", "deny", "DENY", "Deny\u200b", None):
            self.assertFalse(r(cls, choice, None).ok, repr(choice))
        self.assertFalse(r(cls, None, "approve it").ok)

    def test_resolve_permission_with_no_deny_option_offered(self):
        cls = classify.classify("PERMISSION p1: x || CHOICES: Approve | Later", None)
        self.assertFalse(classify.resolve_answer(cls, "Deny", None).ok)
        cls = classify.classify("PERMISSION p1: x", None)
        self.assertFalse(classify.resolve_answer(cls, "Deny", None).ok)

    def test_resolve_handoff_and_unknown(self):
        h = classify.classify("DEMO READY d1: look || CHOICES: Send to review | Revise", None)
        self.assertFalse(classify.resolve_answer(h, "Revise", None).ok)
        self.assertTrue(classify.resolve_answer(h, "Revise", None, handoff_answerable=True).ok)
        self.assertFalse(classify.resolve_answer(h, "Other", None, handoff_answerable=True).ok)
        u = classify.classify("hmm", None)
        self.assertFalse(classify.resolve_answer(u, None, "text", handoff_answerable=True).ok)

    def test_resolve_question(self):
        q = classify.classify("QUESTION q: pick || CHOICES: A | B", None)
        self.assertTrue(classify.resolve_answer(q, "A", None).ok)
        self.assertFalse(classify.resolve_answer(q, "C", None).ok)
        self.assertFalse(classify.resolve_answer(q, None, "A").ok)
        t = classify.classify("QUESTION q: name?", None)
        self.assertTrue(classify.resolve_answer(t, None, "x").ok)
        self.assertFalse(classify.resolve_answer(t, "A", None).ok)
        self.assertFalse(classify.resolve_answer(t, None, None).ok)

    def test_norm(self):
        self.assertEqual(classify.norm(" \u200bApprove\u00a0 "), "approve")
        self.assertEqual(classify.norm("\uff21pprove"), "approve")


class LedgerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tmpdir()
        self.addCleanup(self.tmp.cleanup)
        self.now = 1_700_000_000_000
        self.clock = lambda: self.now
        self.path = Path(self.tmp.name) / "ledger.sqlite3"
        self.l = self.Ledger()

    def tearDown(self):
        self.l.close()

    def Ledger(self):
        return ledger_mod.Ledger(self.path, self.clock, allow_in_repo=True)

    def test_file_is_0600_and_dir_checked(self):
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o600)

    def test_in_repo_is_refused(self):
        with self.assertRaises(safety.UnsafePath):
            ledger_mod.Ledger(Path(__file__).resolve().parent / "ledger-in-repo.sqlite3", self.clock)

    def test_nonce_lifecycle(self):
        self.assertEqual(self.l.take_nonce("a" * 32, self.now, self.now), "ok")
        self.assertEqual(self.l.take_nonce("a" * 32, self.now, self.now), "replay")
        self.now += 61_000
        self.assertEqual(self.l.take_nonce("b" * 32, self.now, self.now), "ok")
        self.assertEqual(self.l.nonce_count(), 1)  # the first one expired and was dropped only now

    def test_nonce_never_evicted_early_and_full_refuses(self):
        with mock.patch.object(ledger_mod, "MAX_NONCES", 3):
            for i in range(3):
                self.assertEqual(self.l.take_nonce(f"{i:032x}", self.now, self.now), "ok")
            self.assertEqual(self.l.take_nonce("f" * 32, self.now, self.now), "full")
            self.assertEqual(self.l.take_nonce(f"{0:032x}", self.now, self.now), "replay")
            self.now += 59_000
            self.assertEqual(self.l.take_nonce("f" * 32, self.now, self.now), "full")
            self.now += 2_000
            self.assertEqual(self.l.take_nonce("f" * 32, self.now, self.now), "ok")

    def test_restart_keeps_nonces_and_moves_the_start_forward(self):
        self.l.take_nonce("c" * 32, self.now, self.now)
        start = self.l.start_ms
        self.l.close()
        self.now += 5
        self.l = self.Ledger()
        self.assertGreater(self.l.start_ms, start)
        self.assertEqual(self.l.take_nonce("c" * 32, self.now, self.now), "replay")

    def test_claim_outcomes(self):
        self.assertEqual(self.l.claim("a", "r1", "d1", "cap", self.now)[0], "new")
        self.assertEqual(self.l.claim("a", "r1", "d1", "cap", self.now)[0], "same")
        self.assertEqual(self.l.claim("a", "r1", "d2", "cap", self.now)[0], "conflict")
        self.assertEqual(self.l.claim("b", "r1", "d2", "cap", self.now)[0], "new")

    def test_pending_becomes_unknown_at_start_and_prompted_is_voided(self):
        self.l.claim("a", "p1", "d", "cap", self.now)
        self.l.claim("a", "q1", "d", "card.dispatch", self.now, state=ledger_mod.PROMPTED)
        self.l.claim("a", "f1", "d", "cap", self.now)
        self.l.finish("a", "f1", ledger_mod.APPLIED, "applied", "", {"k": "v"}, "aud", self.now)
        self.l.put_token("t" * 32, "s", "a", "q1", "d", "rev", "prev", self.now + 60_000)
        self.l.close()
        self.now += 10
        self.l = self.Ledger()
        self.assertEqual(self.l.get_claim("a", "p1")["state"], ledger_mod.UNKNOWN)
        self.assertEqual(self.l.get_claim("a", "p1")["status"], "unknown")
        self.assertEqual(self.l.get_claim("a", "q1")["state"], ledger_mod.REFUSED)
        self.assertEqual(self.l.get_claim("a", "f1")["result"], {"k": "v"})
        self.assertEqual(self.l.open_tokens("a"), 0)

    def test_limits_count_inside_the_claim_transaction(self):
        lim = {"perHour": 2, "perTargetCount": 1, "perTargetSeconds": 600}
        self.assertEqual(self.l.claim("a", "1", "d1", "c", self.now, target="t1", limits=lim)[0], "new")
        self.assertEqual(self.l.claim("a", "2", "d2", "c", self.now, target="t1", limits=lim)[0], "limit")
        self.assertEqual(self.l.claim("a", "3", "d3", "c", self.now, target="t2", limits=lim)[0], "new")
        self.assertEqual(self.l.claim("a", "4", "d4", "c", self.now, target="t3", limits=lim)[0], "limit")
        self.now += 3_601_000
        self.assertEqual(self.l.claim("a", "5", "d5", "c", self.now, target="t1", limits=lim)[0], "new")

    def test_confirm_transaction_consumes_the_token_even_when_validation_refuses(self):
        self.l.claim("a", "r", "dg", "card.dispatch", self.now, state=ledger_mod.PROMPTED)
        self.l.put_token("t" * 32, "s", "a", "r", "dg", "rev", "prev", self.now + 60_000)
        out = self.l.consume_and_claim_dispatch("t" * 32, "a", "r", "tgt", {"perHour": 6}, self.now, lambda row: "nope")
        self.assertEqual(out, ("refused", "nope"))
        self.assertEqual(self.l.open_tokens("a"), 0)
        self.assertEqual(self.l.get_claim("a", "r")["state"], ledger_mod.PROMPTED)  # the service marks it refused afterwards
        self.assertEqual(self.l.consume_and_claim_dispatch("t" * 32, "a", "r", "tgt", {"perHour": 6}, self.now, lambda row: None)[0], "refused")

    def test_confirm_transaction_success_moves_the_claim_and_counts_the_event(self):
        self.l.claim("a", "r", "dg", "card.dispatch", self.now, state=ledger_mod.PROMPTED)
        self.l.put_token("t" * 32, "s", "a", "r", "dg", "rev", "prev", self.now + 60_000)
        lim = {"perHour": 6, "perTargetCount": 1, "perTargetSeconds": 600}
        self.assertEqual(self.l.consume_and_claim_dispatch("t" * 32, "a", "r", "tgt", lim, self.now, lambda row: None), ("ok", "r"))
        self.assertEqual(self.l.get_claim("a", "r")["state"], ledger_mod.PENDING)
        self.assertIn("per target", self.l.limit_peek("card.dispatch", "tgt", lim, self.now))

    def test_clock_meta(self):
        self.l.touch(self.now + 5)
        self.assertEqual(self.l.last_seen(), self.now + 5)
        self.l.touch(self.now)  # never moves backwards
        self.assertEqual(self.l.last_seen(), self.now + 5)


class ExecutorArgvTest(unittest.TestCase):
    def req(self, cap, args, prep=None, **pol_over):
        d = base_policy()
        d["capabilities"].update(pol_over)
        return executors.ExecRequest(cap, args, ACTOR, "Alice", "rid-1", pol.parse(d), prep or {})

    def test_program_comes_from_the_policy(self):
        d = base_policy()
        d["hermesProgram"] = ["/opt/hermes/bin/hermes", "--flag"]
        r = executors.ExecRequest("cron.run", {"job": "job-a1"}, ACTOR, "A", "r", pol.parse(d), {})
        argv, _, _ = executors.argv_for(r)
        self.assertEqual(argv, ["/opt/hermes/bin/hermes", "--flag", "cron", "run", "job-a1"])

    def test_game_text_is_never_in_program_position(self):
        for cap, args, prep in (
            ("card.create", {"board": "main", "title": "title", "body": "b"}, {}),
            ("card.edit", {"card": "c", "comment": "text"}, {"board": "main", "card": "c"}),
            ("decision.answer", {"card": "c", "decision": "d", "text": "t"}, {"board": "main", "card": "c", "note": "t"}),
            ("agent.ask", {"agent": "helper-a", "text": "text"}, {}),
        ):
            argv, _, _ = executors.argv_for(self.req(cap, args, prep))
            self.assertEqual(argv[0], "hermes")

    def test_dash_ids_are_refused(self):
        with self.assertRaises(executors.ExecRefused):
            executors.argv_for(self.req("card.dispatch", {"card": "-x", "board": "main", "profile": "builder-a"}, {"board": "main", "card": "-x"}))
        with self.assertRaises(executors.ExecRefused):
            executors.argv_for(self.req("card.edit", {"card": "c", "comment": "x"}, {"board": "-b", "card": "c"}))
        with self.assertRaises(executors.ExecRefused):
            executors.argv_for(self.req("card.create", {"board": "main", "title": " -x"}))

    def test_chat_argv_boundary(self):
        argv, stdin, timeout = executors.argv_for(self.req("agent.ask", {"agent": "helper-a", "text": "t --yolo"}))
        self.assertEqual(argv[:5], ["hermes", "--profile", "helper-a", "chat", "--oneshot"])
        for bad in executors.CHAT_FORBIDDEN_FLAGS:
            self.assertNotIn(bad, argv)
        self.assertEqual(argv[argv.index("--toolsets") + 1], "search")
        self.assertIn("t --yolo", stdin)
        self.assertNotIn("t --yolo", argv)
        self.assertEqual(argv[argv.index("--query-file") + 1], "-")

    def test_chat_check_rejects_every_forbidden_shape(self):
        good = ["hermes", "--profile", "a", "chat", "--oneshot", "--toolsets", "search", "--query-file", "-"]
        executors.check_chat_argv(good, ("search",))
        for extra in (["--yolo"], ["--resume", "abc"], ["-r", "abc"], ["--continue"], ["-c"], ["--accept-hooks"], ["--worktree"], ["--resume=abc"], ["--yolo=1"]):
            with self.assertRaises(executors.ExecRefused, msg=extra):
                executors.check_chat_argv(good + extra, ("search",))
        with self.assertRaises(executors.ExecRefused):
            executors.check_chat_argv([x for x in good if x != "--oneshot"], ("search",))
        with self.assertRaises(executors.ExecRefused):
            executors.check_chat_argv(good[:5] + ["--toolsets", "search,terminal"], ("search",))
        with self.assertRaises(executors.ExecRefused):
            executors.check_chat_argv(good[:5] + ["-t", "search", "--toolsets", "search"], ("search",))
        with self.assertRaises(executors.ExecRefused):
            executors.check_chat_argv(good[:5] + ["--toolsets", "terminal"], ("terminal",))
        with self.assertRaises(executors.ExecRefused):
            executors.check_chat_argv(good, ())

    def test_chat_toolset_comes_from_the_policy_only(self):
        r = self.req("agent.ask", {"agent": "helper-a", "text": "x"}, **{"agent.ask": {"enabled": True, "agents": ["helper-a"], "toolsets": ["search"]}})
        argv, _, _ = executors.argv_for(r)
        self.assertEqual(argv[argv.index("--toolsets") + 1], "search")

    def test_run_argv_uses_no_shell_and_kills_on_timeout(self):
        with tmpdir() as d:
            marker = Path(d) / "m"
            rc, out, err = executors.run_argv(["echo", f"; touch {marker} $(id)"], None, 5)
            self.assertEqual(rc, 0)
            self.assertIn("$(id)", out)
            self.assertFalse(marker.exists())
            t0 = time.time()
            with self.assertRaises(executors.ExecUnknown):
                executors.run_argv(["sleep", "30"], None, 1)
            self.assertLess(time.time() - t0, 8)
            with self.assertRaises(executors.ExecRefused):
                executors.run_argv(["/nonexistent/prog"], None, 1)

    def test_chunk_paragraphs(self):
        self.assertEqual(chunk_paragraphs("a\n\nb", 100), ["a\n\nb"])
        parts = chunk_paragraphs("a" * 60 + "\n\n" + "b" * 60 + "\n\n" + "c" * 60, 100)
        self.assertEqual(parts, ["a" * 60, "b" * 60, "c" * 60])
        long = chunk_paragraphs("word " * 100, 100)
        self.assertTrue(all(len(x) <= 100 for x in long))
        self.assertEqual(chunk_paragraphs("", 100), ["(no reply)"])

    def test_mock_records_and_executes_nothing(self):
        m = executors.MockExecutor()
        res = m.execute(self.req("service.restart", {"service": "service-1"}))
        self.assertEqual(res.result["dryRun"], "true")
        self.assertEqual(m.calls[0]["argv"], ["true"])
        self.assertTrue(set(executors.build(True).values()) == {next(iter(executors.build(True).values()))} or True)
        built = executors.build(True)
        self.assertEqual(len({id(x) for x in built.values()}), 1)
        self.assertTrue(all(isinstance(x, executors.MockExecutor) for x in built.values()))
        real = executors.build(False)
        self.assertFalse(any(isinstance(x, executors.MockExecutor) for x in real.values()))


class BindTest(unittest.TestCase):
    def test_bind_rules(self):
        for ok in ("127.0.0.1", "localhost", "::1", "127.0.0.2"):
            validate_bind(ok, False)
        for wild in ("0.0.0.0", "::", "", "*", "[::]", "0"):
            for flag in (False, True):
                with self.assertRaises(ValueError, msg=(wild, flag)):
                    validate_bind(wild, flag)
        with self.assertRaises(ValueError):
            validate_bind("192.0.2.10", False)
        validate_bind("192.0.2.10", True)
        with self.assertRaises(ValueError):
            validate_bind("example.com", True)


class CliStartTest(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.addCleanup(self.env.close)
        self.argv = ["serve", "--policy", str(self.env.policy_path), "--key-file", str(self.env.key_path), "--state-dir", str(self.env.state),
                     "--audit-file", str(self.env.audit_path), "--port", "0"]

    def build(self, *extra, argv=None):
        svc = cli.build(cli.parse_args((argv or self.argv) + list(extra)), clock=None)
        svc.ledger.close()
        svc.audit.close()
        return svc

    def test_good_start(self):
        svc = self.build()
        self.assertFalse(svc.dry_run)
        self.assertEqual(svc.host, "127.0.0.1")

    def test_refuses_wildcard_and_lan_without_flag(self):
        for b in ("0.0.0.0", "::"):
            with self.assertRaises(cli.StartupRefused):
                self.build("--bind", b)
            with self.assertRaises(cli.StartupRefused):
                self.build("--bind", b, "--insecure-lan-bind")
        with self.assertRaises(cli.StartupRefused):
            self.build("--bind", "192.0.2.10")
        self.assertEqual(self.build("--bind", "192.0.2.10", "--insecure-lan-bind", "--allow-peer", "192.0.2.20").host, "192.0.2.10")

    def test_fixture_needs_dry_run(self):
        with self.assertRaises(cli.StartupRefused):
            self.build("--board-fixture", str(FIXTURE))
        svc = self.build("--board-fixture", str(FIXTURE), "--dry-run")
        self.assertTrue(svc.dry_run)

    def test_policy_and_key_and_state_permissions(self):
        os.chmod(self.env.policy_path, 0o666)
        with self.assertRaises(cli.StartupRefused):
            self.build()
        os.chmod(self.env.policy_path, 0o620)
        with self.assertRaises(cli.StartupRefused):
            self.build()
        os.chmod(self.env.policy_path, 0o600)
        for mode in (0o640, 0o604, 0o644):
            os.chmod(self.env.key_path, mode)
            with self.assertRaises(cli.StartupRefused, msg=oct(mode)):
                self.build()
        os.chmod(self.env.key_path, 0o600)
        os.chmod(self.env.state, 0o770)
        with self.assertRaises(cli.StartupRefused):
            self.build()
        os.chmod(self.env.state, 0o702)
        with self.assertRaises(cli.StartupRefused):
            self.build()
        os.chmod(self.env.state, 0o700)
        self.build()

    def test_not_owned_by_the_running_uid(self):
        real = os.getuid()
        with mock.patch.object(safety.os, "getuid", return_value=real + 1):
            with self.assertRaises(cli.StartupRefused):
                self.build()
        self.build()

    def test_lock_file_dir_and_existing_lock_must_be_safe(self):
        os.chmod(self.env.state, 0o700)
        lock = self.env.state / "hermes.lock"
        lock.write_text("{}")
        os.chmod(lock, 0o666)
        with self.assertRaises(cli.StartupRefused):
            self.build()
        os.chmod(lock, 0o600)
        self.build()

    def test_policy_typos_refuse_start(self):
        for mut in (
            lambda d: d["capabilities"].update({"host.reboot": {"enabled": True}}),
            lambda d: d.update({"typo": 1}),
            lambda d: d["capabilities"]["decision.answer"].update({"permissionApprove": True}),
            lambda d: d["capabilities"]["agent.chat"].update({"toolsets": ["terminal"]}),
        ):
            d = base_policy(self.env)
            mut(d)
            self.env.write_policy(d)
            with self.assertRaises(cli.StartupRefused):
                self.build()
        self.env.write_policy(base_policy(self.env))
        self.build()

    def test_state_and_audit_inside_the_checkout_are_refused(self):
        inside = Path(__file__).resolve().parent / "state-in-repo"
        with self.assertRaises(cli.StartupRefused):
            cli.build(cli.parse_args(["serve", "--policy", str(self.env.policy_path), "--key-file", str(self.env.key_path), "--state-dir", str(inside),
                                      "--audit-file", str(self.env.audit_path)]))
        self.assertFalse(inside.exists())
        with self.assertRaises(cli.StartupRefused):
            cli.build(cli.parse_args(["serve", "--policy", str(self.env.policy_path), "--key-file", str(self.env.key_path),
                                      "--state-dir", str(self.env.state), "--audit-file", str(inside.parent / "audit.jsonl")]))

    def test_audit_file_unwritable_refuses_start(self):
        bad = self.env.cfg / "no-such-dir" / "audit.jsonl"
        with self.assertRaises(cli.StartupRefused):
            self.build("--audit-file", str(bad))

    def test_default_state_dir_is_under_the_state_home(self):
        with mock.patch.dict(os.environ, {"XDG_STATE_HOME": "/xdg-state-for-test"}, clear=False):
            os.environ.pop("AGENTCRAFT_CONTROL_STATE", None)
            self.assertEqual(str(cli.default_state_dir()), "/xdg-state-for-test/agentcraft-gtnh/control")


class CliCommandsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tmpdir()
        self.addCleanup(self.tmp.cleanup)
        self.lock = Path(self.tmp.name) / "hermes.lock"

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = cli.main(list(argv))
        return rc, out.getvalue(), err.getvalue()

    def test_lock_status_unlock(self):
        rc, out, _ = self.run_cli("lock", "--lock-file", str(self.lock), "--reason", "maintenance")
        self.assertEqual((rc, out.strip()), (0, "locked"))
        self.assertEqual(stat.S_IMODE(os.stat(self.lock).st_mode), 0o600)
        rc, out, _ = self.run_cli("lock", "--lock-file", str(self.lock), "--reason", "second")
        self.assertIn("already locked", out)
        self.assertIn("maintenance", self.lock.read_text())
        rc, out, _ = self.run_cli("status", "--lock-file", str(self.lock))
        st = json.loads(out)
        self.assertTrue(st["locked"])
        self.assertEqual(st["lockReason"], "maintenance")
        rc, _, err = self.run_cli("unlock", "--lock-file", str(self.lock))  # no tty, no --yes
        self.assertEqual(rc, 1)
        self.assertTrue(self.lock.exists())
        rc, out, _ = self.run_cli("unlock", "--lock-file", str(self.lock), "--yes")
        self.assertEqual((rc, out.strip()), (0, "unlocked"))
        self.assertFalse(self.lock.exists())
        rc, out, _ = self.run_cli("status", "--lock-file", str(self.lock))
        self.assertFalse(json.loads(out)["locked"])

    def test_check_policy(self):
        rc, out, _ = self.run_cli("check-policy", "--policy", str(PKG / "policy.example.json"))
        self.assertEqual(rc, 0)
        self.assertIn("policy ok", out)
        rc, out, _ = self.run_cli("check-policy", "--policy", str(PKG / "policy.empty.json"), "--json")
        self.assertEqual(json.loads(out)["enabled"], [])
        bad = Path(self.tmp.name) / "bad.json"
        bad.write_text(json.dumps({"schema": 1, "actors": [], "capabilities": {"card.creat": {"enabled": True}}}))
        os.chmod(bad, 0o600)
        rc, _, err = self.run_cli("check-policy", "--policy", str(bad))
        self.assertEqual(rc, 1)
        self.assertIn("card.creat", err)
        os.chmod(bad, 0o666)
        rc, _, err = self.run_cli("check-policy", "--policy", str(PKG / "policy.empty.json"), "--no-perm-check")
        self.assertEqual(rc, 0)
        rc, _, err = self.run_cli("check-policy", "--policy", str(bad))
        self.assertEqual(rc, 1)

    def test_status_shows_policy_summary(self):
        rc, out, _ = self.run_cli("status", "--lock-file", str(self.lock), "--policy", str(PKG / "policy.example.json"))
        self.assertIn("enabled", json.loads(out)["policy"])


class ReadAdapterStillReadOnlyTest(unittest.TestCase):
    """Reference test: the read adapter keeps refusing every action.* message (see also test_world_server)."""

    def test_adapter_refuses_action_messages(self):
        from hermes_adapter.mapping import Mapper
        from hermes_adapter.server import AdapterServer
        from hermes_adapter.sources import HermesSource
        from hermes_adapter.wsclient import WSClient
        from fixture import standard
        from test_server import ServerThread

        with tmpdir() as d:
            f = standard(Path(d), int(time.time()))
            srv = AdapterServer(HermesSource(f.home).read, Mapper(), host="127.0.0.1", port=0, poll_interval=0.2)
            st = ServerThread(srv)
            try:
                c = WSClient("127.0.0.1", srv.port)
                c.send({"type": "hello", "id": "h"})
                for i, t in enumerate(("action.request", "action.confirm", "action.lock", "action.challenge", "action.hello")):
                    c.send({"type": t, "id": f"a{i}", "actor": {"uuid": ACTOR, "name": "x"}, "capability": "card.create", "args": {}})
                    end = time.time() + 5
                    while True:
                        m = c.recv(max(0.1, end - time.time()))
                        if m.get("type") == "ack" and m.get("re") == f"a{i}":
                            self.assertFalse(m["ok"])
                            self.assertIn("read-only", m["error"])
                            break
                c.close()
            finally:
                fut = asyncio.run_coroutine_threadsafe(srv.stop(), st.loop)
                fut.result(5)
                async def _drain():
                    await asyncio.gather(*srv._tasks, return_exceptions=True)  # let the cancelled poll tasks finish

                asyncio.run_coroutine_threadsafe(_drain(), st.loop).result(5)
                st.loop.call_soon_threadsafe(st.loop.stop)
                st.thread.join(5)
                if not st.thread.is_alive():
                    st.loop.close()

    def test_control_code_is_not_imported_by_the_read_adapter(self):
        root = Path(__file__).resolve().parent.parent / "hermes_adapter"
        for f in root.rglob("*.py"):
            self.assertNotIn("hermes_control", f.read_text(), f.name)


if __name__ == "__main__":
    unittest.main()
