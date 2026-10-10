"""Adversarial tests of the control service over a real socket: handshake, frame forging, time,
replay, idempotency, tiers, audit, lock, limits, dry run, connection limits and a fuzz run."""

from __future__ import annotations

import json
import os
import random
import socket
import time
import unittest

from test_control_common import ACTOR, OTHER_ACTOR, Clock, ControlCase, Env, KEY_HEX, base_policy, control_client

from hermes_adapter.wsclient import WSClient, WSError
from hermes_control import frames, ledger as ledger_mod, service as service_mod
from hermes_control.audit import Audit, AuditError


def read_until_closed(c, timeout: float = 3.0):
    """(closed?, frames received) until the server closes the connection or the timeout passes."""
    got = []
    end = time.time() + timeout
    while time.time() < end:
        try:
            got.append(c.recv(max(0.1, min(0.5, end - time.time()))))
        except TimeoutError:
            continue
        except (WSError, OSError):
            return True, got
    return False, got


def signed(c, type_, payload: str, outer_type=None):
    c.send_raw(json.dumps({"v": 1, "type": outer_type or type_, "payload": payload, "sig": c.sign(payload)}))


def expect_error(tc: unittest.TestCase, c, timeout: float = 3.0):
    t, p = c.recv(timeout)
    tc.assertEqual(t, "error", (t, p))
    return p


class HandshakeTest(ControlCase):
    autostart = True

    def raw(self):
        c = self.env.client(handshake=False)
        t, ch = c.recv()
        self.assertEqual(t, "action.challenge")
        c.session = ch["session"]
        return c, ch

    def hello(self, c, ch, **kw):
        fields = {"challenge": ch["challenge"], "features": ["action"]}
        fields.update(kw.pop("fields", {}))
        return c.send("hello", fields, **kw)

    def test_valid_hello(self):
        c, ch = self.raw()
        self.hello(c, ch)
        types = [c.recv()[0] for _ in range(3)]
        self.assertEqual(types, ["ack", "action.policy", "action.state"])

    def test_unsigned_hello_closes(self):
        c, ch = self.raw()
        body, _ = c.build("hello", {"challenge": ch["challenge"], "features": ["action"]})
        c.send_raw(json.dumps({"v": 1, "type": "hello", "payload": body, "sig": "0" * 64}))
        closed, got = read_until_closed(c)
        self.assertTrue(closed)
        self.assertNotIn("ack", [t for t, _ in got])

    def test_hello_without_sig_key_closes(self):
        c, ch = self.raw()
        body, _ = c.build("hello", {"challenge": ch["challenge"], "features": ["action"]})
        c.send_raw(json.dumps({"v": 1, "type": "hello", "payload": body}))
        self.assertTrue(read_until_closed(c)[0])

    def test_wrong_challenge_closes(self):
        c, ch = self.raw()
        self.hello(c, ch, fields={"challenge": "0" * 32})
        closed, got = read_until_closed(c)
        self.assertTrue(closed)
        self.assertNotIn("ack", [t for t, _ in got])

    def test_wrong_session_closes(self):
        c, ch = self.raw()
        self.hello(c, ch, session="1" * 32)
        closed, got = read_until_closed(c)
        self.assertTrue(closed)
        self.assertNotIn("ack", [t for t, _ in got])

    def test_wrong_direction_closes(self):
        c, ch = self.raw()
        self.hello(c, ch, dir_="c2g")
        closed, got = read_until_closed(c)
        self.assertTrue(closed)
        self.assertNotIn("ack", [t for t, _ in got])

    def test_second_hello_closes(self):
        c = self.env.client()
        c.send("hello", {"challenge": "0" * 32, "features": ["action"]})
        closed, _ = read_until_closed(c)
        self.assertTrue(closed)

    def test_hello_without_action_feature_closes(self):
        for feats in ([], ["ops"], ["world", "ops"]):
            c, ch = self.raw()
            self.hello(c, ch, fields={"features": feats})
            closed, got = read_until_closed(c)
            self.assertTrue(closed, feats)
            self.assertNotIn("ack", [t for t, _ in got])

    def test_non_hello_first_closes_and_nothing_runs(self):
        c, ch = self.raw()
        c.request("card.create", {"board": "main", "title": "never"})
        closed, got = read_until_closed(c)
        self.assertTrue(closed)
        self.assertEqual([t for t, _ in got if t == "action.result"], [])
        self.assertEqual(self.env.hermes_calls(), [])

    def test_no_hello_times_out(self):
        original = service_mod.HELLO_TIMEOUT_S
        service_mod.HELLO_TIMEOUT_S = 0.3
        try:
            c, _ = self.raw()
            self.assertTrue(read_until_closed(c, 3)[0])
        finally:
            service_mod.HELLO_TIMEOUT_S = original


class ForgedFrameTest(ControlCase):
    def setUp(self):
        super().setUp()
        self.env.svc.bad_frame_limit = 10_000  # these tests send many bad frames on one connection
        self.c = self.env.client()

    def assert_refused_quietly(self):
        self.assertEqual(self.env.hermes_calls(), [])
        self.assertFalse([a for a in self.env.audit_lines() if a.get("decision") == "admitted"])

    def good_payload(self, **over):
        fields = {"actor": {"uuid": ACTOR, "name": "TestPlayer"}, "capability": "card.create", "tier": 1, "args": {"board": "main", "title": "forged"}}
        fields.update(over)
        body, _ = self.c.build("action.request", fields)
        return body

    def test_bad_signature(self):
        body = self.good_payload()
        self.c.send_raw(json.dumps({"v": 1, "type": "action.request", "payload": body, "sig": "f" * 64}))
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_signature_of_another_payload(self):
        body = self.good_payload()
        other = self.good_payload()
        self.c.send_raw(json.dumps({"v": 1, "type": "action.request", "payload": body, "sig": self.c.sign(other)}))
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_uppercase_hex_signature_refused(self):
        body = self.good_payload()
        self.c.send_raw(json.dumps({"v": 1, "type": "action.request", "payload": body, "sig": self.c.sign(body).upper()}))
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_missing_signature(self):
        body = self.good_payload()
        self.c.send_raw(json.dumps({"v": 1, "type": "action.request", "payload": body}))
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_outer_inner_type_mismatch(self):
        body = self.good_payload()
        signed(self.c, "action.request", body, outer_type="action.cancel")
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_outer_unknown_key_and_wrong_version(self):
        body = self.good_payload()
        self.c.send_raw(json.dumps({"v": 1, "type": "action.request", "payload": body, "sig": self.c.sign(body), "extra": 1}))
        expect_error(self, self.c)
        self.c.send_raw(json.dumps({"v": 2, "type": "action.request", "payload": body, "sig": self.c.sign(body)}))
        expect_error(self, self.c)
        self.c.send_raw(json.dumps({"v": True, "type": "action.request", "payload": body, "sig": self.c.sign(body)}))
        expect_error(self, self.c)
        self.c.send_raw(json.dumps({"v": 1, "type": "action.request", "payload": json.loads(body), "sig": self.c.sign(body)}))
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_unknown_payload_key(self):
        body = json.loads(self.good_payload())
        body["surprise"] = 1
        signed(self.c, "action.request", json.dumps(body))
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_duplicate_payload_key(self):
        body = self.good_payload()
        dup = body[:-1] + ',"tier":2}'
        signed(self.c, "action.request", dup)
        expect_error(self, self.c)
        dup2 = body[:-1] + ',"capability":"card.create"}'
        signed(self.c, "action.request", dup2)
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_duplicate_key_in_args(self):
        body = self.good_payload()
        dup = body.replace('"title":"forged"', '"title":"forged","title":"other"')
        signed(self.c, "action.request", dup)
        expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_wrong_types(self):
        cases = [
            {"tier": "1"}, {"tier": 1.0}, {"tier": True}, {"tier": None}, {"capability": 7}, {"args": []}, {"args": "x"},
            {"actor": "00000000-0000-4000-8000-000000000001"}, {"actor": {"uuid": ACTOR}},
            {"actor": {"uuid": "00000000-0000-4000-8000-00000000000A", "name": "x"}}, {"actor": {"uuid": ACTOR, "name": "x" * 17}},
            {"actor": {"uuid": ACTOR, "name": "x", "extra": 1}},
        ]
        for over in cases:
            body = self.good_payload(**over)
            signed(self.c, "action.request", body)
            p = expect_error(self, self.c)
            self.assertEqual(p["re"], "", over)
        self.assert_refused_quietly()

    def test_bool_is_not_an_int_and_float_is_not_an_int(self):
        for bad_ts in (True, 1.5, 1e12, "123", None):
            body = json.loads(self.good_payload())
            body["ts"] = bad_ts
            signed(self.c, "action.request", json.dumps(body))
            expect_error(self, self.c)
        for bad_prio in (True, 5.0, "5", -1, 101):
            body = self.good_payload(args={"board": "main", "title": "t", "priority": bad_prio})
            signed(self.c, "action.request", body)
            res = self.env.result_for(self.c, json.loads(body)["id"])
            self.assertEqual(res["status"], "refused", bad_prio)
        self.assert_refused_quietly()

    def test_bad_common_fields(self):
        base = json.loads(self.good_payload())
        for key, val in (("nonce", "short"), ("nonce", "G" * 32), ("session", "x"), ("id", ""), ("id", "a b"), ("id", "x" * 65), ("dir", "up"), ("type", "")):
            body = dict(base)
            body[key] = val
            signed(self.c, "action.request", json.dumps(body))
            expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_strings_with_control_characters_and_lone_surrogates_refused(self):
        for title in ("a\x00b", "a\x1bb", "a\u0085b"):
            body = self.good_payload(args={"board": "main", "title": title})
            signed(self.c, "action.request", body)
            self.assertEqual(self.env.result_for(self.c, json.loads(body)["id"])["status"], "refused")
        body = self.good_payload().replace("forged", "\\ud800x")
        signed(self.c, "action.request", body)
        t, p = self.c.recv(3)
        self.assertTrue(t == "error" or (t == "action.result" and p["status"] == "refused"), (t, p))
        self.assert_refused_quietly()

    def test_non_object_and_garbage_payloads(self):
        for text in ("[]", "null", "7", '"x"', "{", "", "{\"a\":NaN}", "[" * 5000):
            signed(self.c, "action.request", text)
            expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_service_to_game_types_refused_as_requests(self):
        for t in ("ack", "action.policy", "action.result", "action.prompt", "action.challenge"):
            body, _ = self.c.build(t, {"result": {"features": ["action"]}})
            signed(self.c, t, body)
            expect_error(self, self.c)
        self.assert_refused_quietly()

    def test_unauthenticated_refusals_are_audited_at_most_once_a_second(self):
        for _ in range(6):
            self.c.send_raw(json.dumps({"v": 1, "type": "action.request", "payload": "{}", "sig": "0" * 64}))
            expect_error(self, self.c)
        lines = [a for a in self.env.audit_lines() if a["event"] == "unauthenticated"]
        self.assertLessEqual(len(lines), 2)
        self.assertGreaterEqual(len(lines), 1)

    def test_eight_bad_frames_close_the_connection(self):
        self.env.svc.bad_frame_limit = 8
        for _ in range(8):
            try:
                self.c.send_raw("not json")
            except OSError:
                break
        self.assertTrue(read_until_closed(self.c)[0])


class TimeAndReplayTest(ControlCase):
    def request_at(self, c, ts, nonce=None, rid=None):
        sent = c.request("card.create", {"board": "main", "title": "timed"}, ts=ts, nonce=nonce, id_=rid)
        return sent

    def test_ts_too_old_and_too_new(self):
        c = self.env.client()
        now = int(time.time() * 1000)
        for ts in (now - 61_000, now + 61_000, now - 10**6, 0):
            self.request_at(c, ts)
            expect_error(self, c)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_ts_inside_the_window_passes(self):
        c = self.env.client()
        now = int(time.time() * 1000)
        for ts in (self.env.ledger.start_ms, now + 5_000):
            sent = self.request_at(c, ts)
            self.assertEqual(self.env.result_for(c, sent["id"])["status"], "applied")

    def test_replay_same_nonce_twice(self):
        c = self.env.client()
        n = "a1" * 16
        s1 = self.request_at(c, int(time.time() * 1000), nonce=n)
        self.assertEqual(self.env.result_for(c, s1["id"])["status"], "applied")
        self.request_at(c, int(time.time() * 1000), nonce=n)
        expect_error(self, c)
        self.assertEqual(len(self.env.hermes_calls()), 1)

    def test_replay_of_the_exact_frame(self):
        c = self.env.client()
        body, payload = c.build("action.request", {"actor": c.actor, "capability": "card.create", "tier": 1, "args": {"board": "main", "title": "once"}})
        raw = json.dumps({"v": 1, "type": "action.request", "payload": body, "sig": c.sign(body)})
        c.send_raw(raw)
        self.assertEqual(self.env.result_for(c, payload["id"])["status"], "applied")
        c.send_raw(raw)
        expect_error(self, c)
        self.assertEqual(len(self.env.hermes_calls()), 1)

    def test_nonce_cache_full_refuses_and_never_evicts(self):
        saved = ledger_mod.MAX_NONCES
        ledger_mod.MAX_NONCES = 6
        try:
            c = self.env.client()  # the hello took one slot
            first = "b2" * 16
            self.request_at(c, int(time.time() * 1000), nonce=first, rid="full-0")
            self.assertEqual(self.env.result_for(c, "full-0")["status"], "applied")
            for i in range(1, 4):
                s = self.request_at(c, int(time.time() * 1000), rid=f"full-{i}")
                self.env.result_for(c, s["id"])
            self.assertEqual(self.env.ledger.nonce_count(), 5)
            self.request_at(c, int(time.time() * 1000), rid="full-4")
            self.env.result_for(c, "full-4")
            self.assertEqual(self.env.ledger.nonce_count(), 6)
            for i in range(5, 8):
                self.request_at(c, int(time.time() * 1000), rid=f"full-{i}")
                p = expect_error(self, c)
                self.assertEqual(p["error"], "busy")
            self.assertEqual(self.env.ledger.nonce_count(), 6)
            # nothing was evicted: the first nonce is still a replay, not "full", and ts is still honoured
            self.assertEqual(self.env.ledger.take_nonce(first, int(time.time() * 1000), int(time.time() * 1000)), "replay")
            self.assertEqual(len(self.env.hermes_calls()), 5)
        finally:
            ledger_mod.MAX_NONCES = saved

    def test_restart_in_the_middle_of_a_replay_window(self):
        c = self.env.client()
        old_ts = int(time.time() * 1000)
        n = "c3" * 16
        s = self.request_at(c, old_ts, nonce=n, rid="pre-restart")
        self.assertEqual(self.env.result_for(c, "pre-restart")["status"], "applied")
        c.close()
        time.sleep(0.05)
        self.env.restart()
        self.assertGreater(self.env.ledger.start_ms, old_ts)
        self.assertEqual(self.env.ledger.take_nonce(n, old_ts, int(time.time() * 1000)), "replay")  # the ledger kept it
        c2 = self.env.client()
        # a frame stamped before the restart (still inside 60 s) is refused even with a fresh nonce
        self.request_at(c2, old_ts, rid="after-restart")
        p = expect_error(self, c2)
        self.assertEqual(p["error"], "stale")
        self.assertEqual(len(self.env.hermes_calls()), 1)

    def test_clock_jump_backwards_refuses_everything_until_restart(self):
        c = self.env.client()
        s = self.request_at(c, int(time.time() * 1000), rid="before-jump")
        self.assertEqual(self.env.result_for(c, "before-jump")["status"], "applied")
        self.env.clock.offset = -120_000
        self.request_at(c, int(time.time() * 1000) - 120_000, rid="during-jump")
        expect_error(self, c)
        self.env.clock.offset = 0  # the clock recovers, the service must not
        self.request_at(c, int(time.time() * 1000), rid="after-recovery")
        expect_error(self, c)
        self.assertEqual(len(self.env.hermes_calls()), 1)
        self.assertTrue(any(a["event"] == "clock-jump" for a in self.env.audit_lines()))
        self.env.restart()
        c2 = self.env.client()
        s = self.request_at(c2, int(time.time() * 1000), rid="after-restart")
        self.assertEqual(self.env.result_for(c2, "after-restart")["status"], "applied")

    def test_clock_jump_is_reported_in_state(self):
        c = self.env.client()
        self.env.clock.offset = -120_000
        deadline = time.time() + 4
        seen = None
        while time.time() < deadline:
            try:
                t, p = c.recv(1.0)
            except TimeoutError:
                continue
            if t == "action.state":
                seen = p
                break
        self.assertIsNotNone(seen)
        self.assertFalse(seen["armed"])
        self.assertTrue(seen["locked"])


class IdempotencyTest(ControlCase):
    def test_same_id_same_digest_returns_stored_and_runs_once(self):
        c = self.env.client()
        args = {"board": "main", "title": "idem"}
        r1 = self.env.ask(c, "card.create", args, id_="idem-a")
        r2 = self.env.ask(c, "card.create", args, id_="idem-a")
        self.assertEqual(r1["status"], "applied")
        self.assertEqual(r2["status"], "applied")
        self.assertEqual(r1["result"], r2["result"])
        self.assertEqual(len(self.env.hermes_calls()), 1)

    def test_same_id_different_digest_refused(self):
        c = self.env.client()
        self.env.ask(c, "card.create", {"board": "main", "title": "one"}, id_="idem-b")
        r2 = self.env.ask(c, "card.create", {"board": "main", "title": "two"}, id_="idem-b")
        self.assertEqual(r2["status"], "refused")
        self.assertEqual(len(self.env.hermes_calls()), 1)

    def test_same_id_is_scoped_to_the_actor(self):
        pol = base_policy(self.env)
        pol["actors"] = [ACTOR, OTHER_ACTOR]
        self.env.write_policy(pol)
        self.env.restart()
        a = self.env.client()
        b = control_client.ControlClient("127.0.0.1", self.env.port, self.env.key, OTHER_ACTOR, "Other", quiet=True)
        b.handshake()
        self.env.ask(a, "card.create", {"board": "main", "title": "A"}, id_="shared")
        r = self.env.ask(b, "card.create", {"board": "main", "title": "B"}, id_="shared")
        self.assertEqual(r["status"], "applied")
        self.assertEqual(len(self.env.hermes_calls()), 2)

    def test_pending_claim_left_by_a_crash_becomes_unknown_and_is_never_rerun(self):
        args = {"board": "main", "title": "crash"}
        dig = frames.digest("card.create", 1, args)
        outcome, _ = self.env.ledger.claim(ACTOR, "crash-1", dig, "card.create", int(time.time() * 1000))
        self.assertEqual(outcome, "new")
        self.env.restart()
        row = self.env.ledger.get_claim(ACTOR, "crash-1")
        self.assertEqual(row["state"], ledger_mod.UNKNOWN)
        c = self.env.client()
        r = self.env.ask(c, "card.create", args, id_="crash-1")
        self.assertEqual(r["status"], "unknown")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_claims_are_kept_24_hours_then_pruned_at_start(self):
        old = int(time.time() * 1000) - 25 * 3600 * 1000
        self.env.ledger.claim(ACTOR, "old-1", "d" * 64, "card.create", old)
        self.env.ledger.claim(ACTOR, "new-1", "e" * 64, "card.create", int(time.time() * 1000) - 3600 * 1000)
        self.env.restart()
        self.assertIsNone(self.env.ledger.get_claim(ACTOR, "old-1"))
        self.assertIsNotNone(self.env.ledger.get_claim(ACTOR, "new-1"))

    def test_refused_by_validation_does_not_burn_the_id(self):
        c = self.env.client()
        r1 = self.env.ask(c, "card.create", {"board": "nope", "title": "x"}, id_="retry-1")
        self.assertEqual(r1["status"], "refused")
        r2 = self.env.ask(c, "card.create", {"board": "main", "title": "x"}, id_="retry-1")
        self.assertEqual(r2["status"], "applied")


class TierCapabilityActorTest(ControlCase):
    def test_tier_mismatch_both_ways(self):
        c = self.env.client()
        r = self.env.ask(c, "card.dispatch", {"card": "t-demo-1", "board": "main", "profile": "builder-a"}, tier=1)
        self.assertEqual((r["status"], r["error"]), ("refused", "tier mismatch"))
        r = self.env.ask(c, "card.create", {"board": "main", "title": "x"}, tier=2)
        self.assertEqual((r["status"], r["error"]), ("refused", "tier mismatch"))
        r = self.env.ask(c, "service.restart", {"service": "service-1"}, tier=0)
        self.assertEqual(r["error"], "tier mismatch")
        r = self.env.ask(c, "service.restart", {"service": "service-1"}, tier=3)
        self.assertEqual(r["error"], "tier mismatch")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_unknown_and_unlisted_capabilities(self):
        c = self.env.client()
        for cap in ("host.reboot", "shell.run", "card.delete", "Card.Create", "card.create ", "", "decision.approve", "action.world.craft", "proxy.edit", "policy.edit"):
            r = self.env.ask(c, cap, {}, tier=1) if cap else None
            if r is not None:
                self.assertEqual((r["status"], r["error"]), ("refused", "unknown capability"), cap)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_disabled_capability(self):
        pol = base_policy(self.env)
        pol["capabilities"]["cron.run"]["enabled"] = False
        pol["capabilities"]["card.dispatch"]["enabled"] = False
        self.env.write_policy(pol)
        self.env.restart()
        c = self.env.client()
        r = self.env.ask(c, "cron.run", {"job": "job-a1"})
        self.assertEqual((r["status"], r["error"]), ("refused", "capability disabled"))
        r = self.env.ask(c, "card.dispatch", {"card": "t-demo-1", "board": "main", "profile": "builder-a"})
        self.assertEqual(r["error"], "capability disabled")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_capability_absent_from_the_policy_is_disabled(self):
        pol = base_policy(self.env)
        del pol["capabilities"]["service.restart"]
        self.env.write_policy(pol)
        self.env.restart()
        c = self.env.client()
        self.assertEqual(self.env.ask(c, "service.restart", {"service": "service-1"})["error"], "capability disabled")

    def test_actor_not_in_policy(self):
        c = control_client.ControlClient("127.0.0.1", self.env.port, self.env.key, OTHER_ACTOR, "Intruder", quiet=True)
        c.handshake()
        r = self.env.ask(c, "card.create", {"board": "main", "title": "x"})
        self.assertEqual((r["status"], r["error"]), ("refused", "actor not allowed"))
        self.assertEqual(self.env.hermes_calls(), [])

    def test_args_must_match_the_capability(self):
        c = self.env.client()
        cases = [
            ("card.create", {"board": "main"}), ("card.create", {"board": "main", "title": ""}), ("card.create", {"board": "main", "title": "x" * 121}),
            ("card.create", {"board": "main", "title": "x", "extra": 1}), ("card.create", {"board": "main", "title": "x", "body": "y" * 4001}),
            ("card.edit", {"card": "t-demo-1"}), ("card.edit", {"card": "t-demo-1", "comment": "a", "title": "b"}),
            ("decision.answer", {"card": "t-demo-2", "decision": "d-main-101"}), ("agent.ask", {"agent": "helper-a", "text": ""}),
            ("agent.ask", {"agent": "helper-a", "text": "x" * 2001}), ("service.restart", {"service": "service-1", "argv": ["rm"]}),
            ("cron.run", {"job": "job-a1", "extra": "x"}), ("cron.run", {}),
        ]
        for cap, args in cases:
            r = self.env.ask(c, cap, args)
            self.assertEqual(r["status"], "refused", (cap, args))
        self.assertEqual(self.env.hermes_calls(), [])

    def test_allowlists_service_job_board_profile(self):
        c = self.env.client()
        self.assertEqual(self.env.ask(c, "service.restart", {"service": "sshd"})["error"], "service not allowed")
        self.assertEqual(self.env.ask(c, "cron.run", {"job": "job-zz"})["error"], "job not allowed")
        self.assertEqual(self.env.ask(c, "card.create", {"board": "secret-board", "title": "x"})["error"], "board not allowed")
        self.assertEqual(self.env.ask(c, "card.dispatch", {"card": "t-demo-1", "board": "main", "profile": "root"})["error"], "profile not allowed")
        self.assertEqual(self.env.hermes_calls(), [])


class AuditTest(ControlCase):
    def test_audit_unwritable_refuses_and_the_executor_is_not_called(self):
        c = self.env.client()
        original = Audit.write

        def broken(self, *a, **k):
            raise AuditError("disk full")

        Audit.write = broken  # type: ignore[method-assign]
        try:
            r = self.env.ask(c, "card.create", {"board": "main", "title": "no audit no action"}, id_="noaudit-1")
        finally:
            Audit.write = original  # type: ignore[method-assign]
        self.assertEqual(r["status"], "refused")
        self.assertIn("audit", r["error"])
        self.assertEqual(self.env.hermes_calls(), [])
        row = self.env.ledger.get_claim(ACTOR, "noaudit-1")
        self.assertEqual(row["state"], ledger_mod.REFUSED)

    def test_audit_fd_failure_refuses(self):
        c = self.env.client()
        os.close(self.env.audit._fd)  # the descriptor is gone: every write fails
        r = self.env.ask(c, "card.create", {"board": "main", "title": "x"})
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_admission_record_exists_before_the_executor_runs(self):
        c = self.env.client()
        self.env.ask(c, "card.create", {"board": "main", "title": "ordered"}, id_="order-1")
        events = [a["event"] for a in self.env.audit_lines() if a.get("req") == "order-1"]
        self.assertEqual(events[:2], ["request", "outcome"])

    def test_audit_never_contains_keys_signatures_or_tokens(self):
        c = self.env.client()
        sent = c.request("card.dispatch", {"card": "t-demo-1", "board": "main", "profile": "builder-a"})
        token = None
        end = time.time() + 5
        while time.time() < end and token is None:
            t, p = c.recv(2)
            if t == "action.prompt":
                token = p["token"]
        c.send("action.confirm", {"actor": c.actor, "token": token})
        self.env.result_for(c, sent["id"])
        deadline = time.time() + 3
        text = ""
        while time.time() < deadline:
            text = self.env.audit_path.read_text()
            if '"outcome"' in text:
                break
            time.sleep(0.05)
        self.assertNotIn(KEY_HEX, text)
        self.assertNotIn(token, text)
        self.assertNotIn(c.session, text)  # sessions are not logged either
        self.assertEqual(oct(os.stat(self.env.audit_path).st_mode & 0o777), "0o600")

    def test_chat_text_is_capped_in_the_audit(self):
        c = self.env.client()
        long = "hello " * 300
        self.env.ask(c, "agent.ask", {"agent": "helper-a", "text": long[:1900]}, id_="chat-audit")
        rec = [a for a in self.env.audit_lines() if a.get("req") == "chat-audit" and a["event"] == "request"][0]
        self.assertLessEqual(len(rec["args"]["text"]), 300)

    def test_audit_rolls_and_keeps_one_file(self):
        a = Audit(self.env.cfg / "roll.jsonl", roll_bytes=2000)
        for i in range(100):
            a.write("x", n=i, pad="p" * 50)
        self.assertTrue((self.env.cfg / "roll.jsonl.1").exists())
        self.assertFalse((self.env.cfg / "roll.jsonl.2").exists())
        self.assertLess((self.env.cfg / "roll.jsonl").stat().st_size, 4000)
        self.assertEqual(oct((self.env.cfg / "roll.jsonl.1").stat().st_mode & 0o777), "0o600")


class LockTest(ControlCase):
    def test_lock_at_admission(self):
        c = self.env.client()
        self.env.lock_path.write_text("{}")
        r = self.env.ask(c, "card.create", {"board": "main", "title": "x"})
        self.assertEqual((r["status"], r["error"]), ("refused", "write lock is set"))
        self.assertEqual(self.env.hermes_calls(), [])

    def test_unreadable_lock_counts_as_locked(self):
        c = self.env.client()
        self.env.lock_path.write_text("not json at all")
        self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": "x"})["error"], "write lock is set")
        os.unlink(self.env.lock_path)
        os.mkdir(self.env.lock_path)  # a directory where the lock file should be: exists and is unreadable as a file
        self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": "x2"})["error"], "write lock is set")
        self.assertEqual(self.env.hermes_calls(), [])

    def test_lock_set_between_admission_and_execution(self):
        env = self.env
        lock_path = env.lock_path

        class LockingAudit(Audit):
            def write(self, event, **fields):
                rid = super().write(event, **fields)
                if event == "request":
                    lock_path.write_text("{}")  # the lock appears right after the admission record
                return rid

        env.stop()
        env.audit_cls = LockingAudit
        env.start()
        c = env.client()
        r = env.ask(c, "card.create", {"board": "main", "title": "raced"}, id_="race-1")
        self.assertEqual((r["status"], r["error"]), ("refused", "write lock is set"))
        self.assertEqual(env.hermes_calls(), [])
        self.assertEqual(env.ledger.get_claim(ACTOR, "race-1")["state"], ledger_mod.REFUSED)

    def test_game_lock_sets_the_hermes_lock_and_nothing_clears_it(self):
        c = self.env.client()
        c.send("action.lock", {"actor": c.actor, "reason": "panic from game"})
        deadline = time.time() + 4
        state = None
        while time.time() < deadline:
            t, p = c.recv(2)
            if t == "action.state" and p["locked"]:
                state = p
                break
        self.assertIsNotNone(state)
        self.assertEqual(state["lockReason"], "panic from game")
        self.assertFalse(state["armed"])
        self.assertTrue(self.env.lock_path.exists())
        self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": "x"})["error"], "write lock is set")
        # nothing on the wire clears it: other lock-ish messages, a second lock, an "unlock"
        for t in ("action.unlock", "action.clear", "unlock"):
            body, _ = c.build(t, {})
            signed(c, t, body)
            expect_error(self, c)
        c.send("action.lock", {"actor": c.actor, "reason": "again"})
        time.sleep(0.2)
        self.assertTrue(self.env.lock_path.exists())
        self.assertIn("panic from game", self.env.lock_path.read_text())  # an existing lock is left untouched
        self.assertFalse(hasattr(service_mod, "clear_lock"))
        self.assertEqual(self.env.hermes_calls(), [])
        self.assertTrue(any(a["event"] == "game-lock" for a in self.env.audit_lines()))

    def test_game_lock_from_unknown_actor_is_accepted_as_fail_safe(self):
        c = control_client.ControlClient("127.0.0.1", self.env.port, self.env.key, OTHER_ACTOR, "SomeOp", quiet=True)
        c.handshake()
        c.send("action.lock", {"actor": c.actor, "reason": "op pressed lock"})
        time.sleep(0.3)
        self.assertTrue(self.env.lock_path.exists())
        rec = [a for a in self.env.audit_lines() if a["event"] == "game-lock"][0]
        self.assertFalse(rec["actorKnown"])

    def test_policy_can_keep_the_game_lock_audit_only(self):
        pol = base_policy(self.env)
        pol["lockSetsHermesLock"] = False
        self.env.write_policy(pol)
        self.env.restart()
        c = self.env.client()
        c.send("action.lock", {"actor": c.actor, "reason": "audit only"})
        time.sleep(0.3)
        self.assertFalse(self.env.lock_path.exists())
        self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": "x"})["status"], "applied")

    def test_terminal_lock_is_pushed_in_action_state(self):
        c = self.env.client()
        self.env.lock_path.write_text(json.dumps({"reason": "from terminal", "by": "terminal:owner", "since": 5}))
        end = time.time() + 4
        got = None
        while time.time() < end and got is None:
            try:
                t, p = c.recv(1.0)
            except TimeoutError:
                continue
            if t == "action.state" and p["locked"]:
                got = p
        self.assertEqual((got["lockReason"], got["lockedBy"], got["since"]), ("from terminal", "terminal:owner", 5))


class RateLimitTest(ControlCase):
    def test_per_capability_limit(self):
        pol = base_policy(self.env)
        pol["capabilities"]["card.create"]["limits"] = {"perHour": 2}
        self.env.write_policy(pol)
        self.env.restart()
        c = self.env.client()
        for i in range(2):
            self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": f"c{i}"})["status"], "applied")
        r = self.env.ask(c, "card.create", {"board": "main", "title": "c3"})
        self.assertEqual(r["status"], "refused")
        self.assertIn("per hour", r["error"])
        self.assertEqual(len(self.env.hermes_calls()), 2)

    def test_default_limit_cannot_be_loosened_by_policy(self):
        pol = base_policy(self.env)
        pol["capabilities"]["card.create"]["limits"] = {"perHour": 21}
        self.env.write_policy(pol)
        with self.assertRaises(Exception):
            self.env.build()

    def test_chat_per_minute(self):
        pol = base_policy(self.env)
        pol["capabilities"]["agent.ask"]["limits"] = {"perMinute": 2}
        self.env.write_policy(pol)
        self.env.restart()
        c = self.env.client()
        for i in range(2):
            self.assertEqual(self.env.ask(c, "agent.ask", {"agent": "helper-a", "text": f"q{i}"})["status"], "applied")
        self.assertEqual(self.env.ask(c, "agent.ask", {"agent": "helper-a", "text": "q3"})["status"], "refused")

    def test_service_restart_one_per_service_per_ten_minutes(self):
        c = self.env.client()
        self.assertEqual(self.env.ask(c, "service.restart", {"service": "service-1"})["status"], "applied")
        r = self.env.ask(c, "service.restart", {"service": "service-1"})
        self.assertEqual(r["status"], "refused")
        self.assertIn("per target", r["error"])
        self.assertEqual(len(self.env.hermes_calls()), 1)

    def test_cron_one_per_job_and_per_hour_overall(self):
        pol = base_policy(self.env)
        pol["jobs"] = ["job-a1", "job-a2"]
        self.env.write_policy(pol)
        self.env.restart()
        c = self.env.client()
        self.assertEqual(self.env.ask(c, "cron.run", {"job": "job-a1"})["status"], "applied")
        self.assertEqual(self.env.ask(c, "cron.run", {"job": "job-a1"})["status"], "refused")
        self.assertEqual(self.env.ask(c, "cron.run", {"job": "job-a2"})["status"], "applied")

    def test_one_dispatch_per_card_per_ten_minutes(self):
        c = self.env.client()
        rid, prompt, res = self.env.prompt(c, {"card": "t-demo-1", "board": "main", "profile": "builder-a"})
        self.assertEqual(res["status"], "prompted")
        c.send("action.confirm", {"actor": c.actor, "token": prompt["token"]})
        self.assertEqual(self.env.result_for(c, rid)["status"], "applied")
        r = self.env.ask(c, "card.dispatch", {"card": "t-demo-1", "board": "main", "profile": "builder-a"})
        self.assertEqual(r["status"], "refused")
        self.assertIn("per target", r["error"])

    def test_dispatch_six_per_hour_overall(self):
        board = self.env.board
        for i in range(8):
            card = {"id": f"t-many-{i}", "board": "main", "title": f"card {i}", "body": "", "status": "todo", "assignee": "", "priority": 0,
                    "run": None, "block": None, "model": "m", "revision": f"r{i}"}
            board.boards["main"]["cards"][card["id"]] = card
        c = self.env.client()
        applied = 0
        for i in range(8):
            rid, prompt, res = self.env.prompt(c, {"card": f"t-many-{i}", "board": "main", "profile": "builder-a"})
            if res["status"] == "refused":
                self.assertIn("per hour", res["error"])
                continue
            c.send("action.confirm", {"actor": c.actor, "token": prompt["token"]})
            if self.env.result_for(c, rid)["status"] == "applied":
                applied += 1
        self.assertEqual(applied, 6)


class ConnectionLimitTest(ControlCase):
    def test_oversized_message_closes(self):
        c = self.env.client()
        c.send_raw("x" * 16385)
        closed, _ = read_until_closed(c)
        self.assertTrue(closed)

    def test_message_at_the_limit_is_read_and_refused_not_closed(self):
        c = self.env.client()
        c.send_raw("x" * 16384)
        expect_error(self, c)

    def test_third_connection_is_refused(self):
        a = self.env.client()
        b = self.env.client()
        third = WSClient("127.0.0.1", self.env.port)
        closed, got = read_until_closed(third, 3)
        self.assertTrue(closed)
        self.assertEqual(got, [])
        # the first two keep working
        self.assertEqual(self.env.ask(a, "card.create", {"board": "main", "title": "still alive"})["status"], "applied")
        b.close()
        time.sleep(0.2)
        c = self.env.client()  # a slot is free again
        self.assertEqual(self.env.ask(c, "cron.run", {"job": "job-a1"})["status"], "applied")

    def test_origin_header_refused(self):
        with self.assertRaises(WSError):
            WSClient("127.0.0.1", self.env.port, extra_headers={"Origin": "http://evil.example"})
        with self.assertRaises(WSError):
            WSClient("127.0.0.1", self.env.port, extra_headers={"Origin": "null"})

    def test_foreign_host_header_refused(self):
        with self.assertRaises(WSError):
            WSClient("127.0.0.1", self.env.port, host_header="control.example.net")

    def test_binary_frame_closes(self):
        c = self.env.client()
        c.ws.send_frame(0x2, b"\x00\x01")
        self.assertTrue(read_until_closed(c)[0])


class DryRunTest(ControlCase):
    dry_run = True

    def test_dry_run_is_announced_and_executes_nothing(self):
        c = self.env.client(handshake=False)
        got = c.handshake()
        self.assertTrue(got[2][1]["dryRun"])
        results = [
            self.env.ask(c, "card.create", {"board": "main", "title": "dry"}),
            self.env.ask(c, "card.edit", {"card": "t-demo-1", "comment": "dry comment"}),
            self.env.ask(c, "decision.answer", {"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}),
            self.env.ask(c, "service.restart", {"service": "service-1"}),
            self.env.ask(c, "cron.run", {"job": "job-a1"}),
            self.env.ask(c, "agent.ask", {"agent": "helper-a", "text": "hi"}),
        ]
        for r in results:
            self.assertEqual(r["status"], "applied")
            self.assertTrue(r["dryRun"])
        self.assertEqual(self.env.hermes_calls(), [])
        mock = self.env.svc.executors["card.create"]
        self.assertEqual([x["capability"] for x in mock.calls], ["card.create", "card.edit", "decision.answer", "service.restart", "cron.run", "agent.ask"])
        # chat never records the command line details
        self.assertEqual(mock.calls[-1]["argv"][1:], ["..."])

    def test_dry_run_dispatch_round_trip(self):
        c = self.env.client()
        sent = c.request("card.dispatch", {"card": "t-demo-1", "board": "main", "profile": "builder-a"})
        prompt = None
        end = time.time() + 5
        while time.time() < end and prompt is None:
            t, p = c.recv(2)
            if t == "action.prompt":
                prompt = p
        self.assertEqual(prompt["summary"]["title"], "Add the placeholder widget")
        self.assertEqual(prompt["summary"]["profile"], "builder-a")
        c.send("action.confirm", {"actor": c.actor, "token": prompt["token"]})
        res = None
        end = time.time() + 5
        while time.time() < end:
            t, p = c.recv(2)
            if t == "action.result" and p["status"] == "applied":
                res = p
                break
        self.assertTrue(res["dryRun"])
        self.assertEqual(res["re"], sent["id"])
        self.assertEqual(self.env.hermes_calls(), [])
        call = self.env.svc.executors["card.dispatch"].calls[-1]
        self.assertEqual(call["argv"][1:], ["kanban", "--board", "main", "assign", "t-demo-1", "builder-a"])


class FuzzTest(ControlCase):
    def test_fuzz_random_and_mutated_frames_run_nothing_and_do_not_crash(self):
        self.env.lock_path.write_text("{}")  # even a perfectly valid mutation can only be refused
        rng = random.Random(7)
        c = self.env.client()
        base = {"actor": {"uuid": ACTOR, "name": "Fuzz"}, "capability": "card.create", "tier": 1, "args": {"board": "main", "title": "fuzz"}}
        sent = 0

        def mutate(v, depth=0):
            r = rng.random()
            if isinstance(v, dict) and v and r < 0.5:
                k = rng.choice(list(v))
                w = dict(v)
                op = rng.choice(("del", "dup", "mut", "add"))
                if op == "del":
                    w.pop(k)
                elif op == "mut":
                    w[k] = mutate(w[k], depth + 1)
                elif op == "add":
                    w[rng.choice(("zz", "type", "id", "args", "ts"))] = rng.choice((1, "x", None, [], {}, True, 10**30))
                return w
            if isinstance(v, str):
                return rng.choice((v + "\x00", v * 50, "", v[::-1], "\ud83d\ude00", "'; DROP TABLE claims;--", "-rf", v.upper()))
            if isinstance(v, bool):
                return rng.choice((not v, 1, "true"))
            if isinstance(v, int):
                return rng.choice((v + 1, -v, 0, 2**63, 1.5, True, str(v)))
            if isinstance(v, list):
                return rng.choice(([], [v], v + v))
            return rng.choice((None, 1, "x", [], {}))

        def fresh():
            nonlocal c
            try:
                c.close()
            except Exception:  # noqa: BLE001
                pass
            c = self.env.client()

        for i in range(400):
            kind = rng.random()
            try:
                if kind < 0.25:
                    c.send_raw("".join(chr(rng.randrange(32, 0x2FF)) for _ in range(rng.randrange(0, 300))))
                elif kind < 0.45:
                    payload = json.dumps(mutate(base))
                    c.send_raw(json.dumps({"v": 1, "type": "action.request", "payload": payload, "sig": rng.choice((c.sign(payload), "0" * 64, "zz"))}))
                else:
                    fields = mutate(base)
                    body, _ = c.build(rng.choice(("action.request", "action.confirm", "action.cancel", "action.lock", "hello", "x.y")), fields if isinstance(fields, dict) else {})
                    obj = json.loads(body)
                    if rng.random() < 0.5:
                        obj = mutate(obj)
                    body = json.dumps(obj)
                    c.send_raw(json.dumps({"v": 1, "type": rng.choice(("action.request", "action.confirm", "action.cancel", "action.lock")), "payload": body, "sig": c.sign(body)}))
                sent += 1
                if i % 5 == 0:
                    try:
                        c.recv(0.05)
                    except (TimeoutError, WSError, OSError):
                        pass
            except OSError:
                fresh()
            if i % 25 == 24:
                fresh()
        self.assertGreater(sent, 300)
        self.assertEqual(self.env.hermes_calls(), [])
        self.assertFalse([a for a in self.env.audit_lines() if a.get("decision") in ("admitted", "prompted")])
        # still alive and still correct afterwards
        os.unlink(self.env.lock_path)
        fresh()
        self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": "after fuzz"})["status"], "applied")


if __name__ == "__main__":
    unittest.main()
