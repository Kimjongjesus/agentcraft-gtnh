"""Regression tests for the independent security review of the control service (findings F1, F2, F3, F6,
F7, F9, F10) and for the QA-only ``--dev-offline-actors`` allowance.

Everything runs against a real loopback service with mock (dry-run) executors or the fake ``hermes`` script;
the real ``hermes`` is never called. "Queued work" tests occupy the four worker threads so that an admitted
request sits between admission and the executor while the test changes the world.
"""

from __future__ import annotations

import asyncio
import errno
import json
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

from test_control_common import ACTOR, FIXTURE, OTHER_ACTOR, ControlCase, Env, ServerThread, base_policy, control_client

from hermes_control import board as board_mod
from hermes_control import cli, executors, ledger as ledger_mod, policy as pol
from hermes_control.audit import Audit, AuditError

ARGS = {"card": "t-demo-1", "board": "main", "profile": "builder-a"}
FAKE_TOKEN = "sk-" + "ant-api03-" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"  # built at runtime: not a real key
V3_ACTOR = "00000000-0000-3000-8000-000000000003"


def signed(c, type_, payload: str) -> None:
    c.send_raw(json.dumps({"v": 1, "type": type_, "payload": payload, "sig": c.sign(payload)}))


class QueuedCase(ControlCase):
    """Dry-run service plus helpers to park work between admission and the executor."""

    dry_run = True

    def calls(self) -> list[dict]:
        return list(self.env.svc.executors["card.dispatch"].calls)

    def block_workers(self) -> threading.Event:
        release = threading.Event()
        self.addCleanup(release.set)
        for _ in range(4):
            self.env.svc._pool.submit(lambda: release.wait(10))
        return release

    def wait_claim(self, rid: str, state: str = "pending") -> None:
        end = time.time() + 5
        while time.time() < end:
            row = self.env.ledger.get_claim(ACTOR, rid)
            if row and row["state"] == state:
                return
            time.sleep(0.01)
        self.fail(f"claim {rid} never reached {state}")

    def confirmed_dispatch_waiting(self, c):
        """A confirmed card.dispatch that is queued behind the blocked workers. Returns (request id, release event)."""
        release = self.block_workers()
        rid, prompt, _ = self.env.prompt(c, ARGS)
        c.send("action.confirm", {"actor": c.actor, "token": prompt["token"]})
        self.wait_claim(rid)
        return rid, release

    def queued_request(self, c, cap, args, rid):
        release = self.block_workers()
        c.request(cap, args, id_=rid)
        self.wait_claim(rid)
        return release

    def result_after(self, c, rid, release):
        release.set()
        return self.env.result_for(c, rid)


# ---- F1: board state re-validated right before the executor ---------------------------------------------------------


class F1BoardRecheckTest(QueuedCase):
    def test_dispatch_unchanged_card_still_applies(self):
        c = self.env.client()
        rid, release = self.confirmed_dispatch_waiting(c)
        self.assertEqual(self.result_after(c, rid, release)["status"], "applied")
        self.assertEqual(len(self.calls()), 1)

    def test_dispatch_card_changed_and_started_after_confirm_is_refused(self):
        c = self.env.client()
        rid, release = self.confirmed_dispatch_waiting(c)
        card = self.env.board.boards["main"]["cards"]["t-demo-1"]
        card["body"], card["status"], card["run"] = "NEW WORK NOT IN THE APPROVED SUMMARY", "running", 9
        card["revision"] = board_mod.revision(card)
        r = self.result_after(c, rid, release)
        self.assertEqual((r["status"], r["error"]), ("refused", "card changed since the prompt"))
        self.assertEqual(self.calls(), [])

    def test_dispatch_card_body_changed_only_is_refused(self):
        c = self.env.client()
        rid, release = self.confirmed_dispatch_waiting(c)
        card = self.env.board.boards["main"]["cards"]["t-demo-1"]
        card["body"] = "different body"
        card["revision"] = board_mod.revision(card)
        self.assertEqual(self.result_after(c, rid, release)["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_dispatch_card_assigned_by_someone_else_is_refused(self):
        c = self.env.client()
        rid, release = self.confirmed_dispatch_waiting(c)
        card = self.env.board.boards["main"]["cards"]["t-demo-1"]
        card["assignee"] = "builder-z"
        card["revision"] = board_mod.revision(card)
        self.assertEqual(self.result_after(c, rid, release)["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_dispatch_card_gone_is_refused(self):
        c = self.env.client()
        rid, release = self.confirmed_dispatch_waiting(c)
        del self.env.board.boards["main"]["cards"]["t-demo-1"]
        self.assertEqual(self.result_after(c, rid, release)["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_dispatch_running_with_unchanged_revision_field_set_is_still_refused(self):
        # defence in depth: the status / run checks do not rely on the revision alone
        c = self.env.client()
        rid, release = self.confirmed_dispatch_waiting(c)
        card = self.env.board.boards["main"]["cards"]["t-demo-1"]
        card["status"] = "running"  # revision deliberately NOT recomputed (a reader that fingerprints less)
        r = self.result_after(c, rid, release)
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_decision_replaced_by_a_new_permission_decision_is_refused(self):
        c = self.env.client()
        release = self.queued_request(c, "decision.answer", {"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}, "dec-new")
        dec = self.env.board.boards["main"]["decisions"]["t-demo-2"]
        dec["id"], dec["event"] = "d-main-999", "d-main-999"
        dec["reason"] = "PERMISSION p1: new permission || CHOICES: Approve | Deny"
        r = self.result_after(c, "dec-new", release)
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_decision_reclassified_in_place_to_a_permission_is_refused(self):
        c = self.env.client()
        release = self.queued_request(c, "decision.answer", {"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}, "dec-inplace")
        dec = self.env.board.boards["main"]["decisions"]["t-demo-2"]
        dec["reason"] = "PERMISSION p1: now a permission || CHOICES: Option A | Approve | Deny"  # same id, same event, same Option A
        r = self.result_after(c, "dec-inplace", release)
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_decision_with_changed_options_is_refused(self):
        c = self.env.client()
        release = self.queued_request(c, "decision.answer", {"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}, "dec-opts")
        dec = self.env.board.boards["main"]["decisions"]["t-demo-2"]
        dec["reason"] = "QUESTION q1: Which placeholder option should the widget use? || CHOICES: Option A | Option Z"
        self.assertEqual(self.result_after(c, "dec-opts", release)["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_decision_closed_before_the_executor_is_refused(self):
        c = self.env.client()
        release = self.queued_request(c, "decision.answer", {"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}, "dec-closed")
        del self.env.board.boards["main"]["decisions"]["t-demo-2"]
        r = self.result_after(c, "dec-closed", release)
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_decision_unchanged_still_applies(self):
        c = self.env.client()
        release = self.queued_request(c, "decision.answer", {"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}, "dec-same")
        self.assertEqual(self.result_after(c, "dec-same", release)["status"], "applied")
        self.assertEqual(len(self.calls()), 1)

    def test_permission_deny_still_applies_when_unchanged(self):
        c = self.env.client()
        release = self.queued_request(c, "decision.answer", {"card": "t-demo-4", "decision": "d-main-103", "choice": "Deny"}, "dec-deny")
        self.assertEqual(self.result_after(c, "dec-deny", release)["status"], "applied")

    def test_card_edit_started_running_before_the_executor_is_refused(self):
        c = self.env.client()
        release = self.queued_request(c, "card.edit", {"card": "t-demo-2", "title": "New title"}, "edit-race")
        card = self.env.board.boards["main"]["cards"]["t-demo-2"]
        card["status"], card["run"] = "running", 12
        card["revision"] = board_mod.revision(card)
        r = self.result_after(c, "edit-race", release)
        self.assertEqual((r["status"], r["error"]), ("refused", "card is running: only a comment is allowed"))
        self.assertEqual(self.calls(), [])

    def test_card_edit_comment_is_still_fine_on_a_running_card(self):
        c = self.env.client()
        release = self.queued_request(c, "card.edit", {"card": "t-demo-2", "comment": "a note"}, "edit-comment")
        card = self.env.board.boards["main"]["cards"]["t-demo-2"]
        card["status"], card["run"] = "running", 12
        card["revision"] = board_mod.revision(card)
        self.assertEqual(self.result_after(c, "edit-comment", release)["status"], "applied")


# ---- F2: queued work re-authorised against the CURRENT policy -------------------------------------------------------


class F2QueuedPolicyTest(QueuedCase):
    def reload(self, mutate) -> None:
        p = self.env.policy_dict
        mutate(p)
        self.env.write_policy(p)
        self.assertTrue(self.env.st.call(self.env.svc.reload_policy))

    def test_reload_disabling_dispatch_wins_against_a_confirmed_queued_dispatch(self):
        c = self.env.client()
        rid, release = self.confirmed_dispatch_waiting(c)
        self.reload(lambda p: p["capabilities"]["card.dispatch"].update({"enabled": False}))
        r = self.result_after(c, rid, release)
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_reload_removing_the_actor_wins_against_queued_work(self):
        c = self.env.client()
        release = self.queued_request(c, "card.create", {"board": "main", "title": "queued"}, "queued-1")
        self.reload(lambda p: p.update({"actors": [OTHER_ACTOR]}))
        r = self.result_after(c, "queued-1", release)
        self.assertEqual(r["status"], "refused")
        self.assertEqual(self.calls(), [])

    def test_any_reload_refuses_work_admitted_under_the_old_revision(self):
        c = self.env.client()
        release = self.queued_request(c, "card.create", {"board": "main", "title": "queued"}, "queued-2")
        self.reload(lambda p: None)  # same content, new revision
        r = self.result_after(c, "queued-2", release)
        self.assertEqual((r["status"], r["error"]), ("refused", "policy changed since the request was admitted"))
        self.assertEqual(self.calls(), [])

    def test_reauthorize_checks_names_against_the_current_policy(self):
        # direct unit check of the name allowlists (revision equal, so only the names can fail)
        svc = self.env.svc
        pl = svc.policy
        base = dict(actor_uuid=ACTOR, actor_name="T", req_id="r", policy=pl, revision=svc.revision)
        svc._reauthorize(executors.ExecRequest(capability="card.dispatch", args=dict(ARGS), prep={"board": "main", "card": "t-demo-1"}, **base))
        for cap, args, prep in (
            ("card.dispatch", {**ARGS, "profile": "nobody"}, {"board": "main"}),
            ("card.create", {"board": "other", "title": "x"}, {}),
            ("service.restart", {"service": "sshd"}, {}),
            ("cron.run", {"job": "job-zz"}, {}),
            ("agent.ask", {"agent": "nobody", "text": "hi"}, {}),
        ):
            with self.assertRaises(executors.ExecRefused, msg=cap):
                svc._reauthorize(executors.ExecRequest(capability=cap, args=args, prep=prep, **base))

    def test_reload_is_serialised_with_the_authorise_and_start_step(self):
        svc = self.env.svc
        before = svc.revision
        svc._auth_mu.acquire()
        released = False
        try:
            t = threading.Thread(target=lambda: self.env.st.call(svc.reload_policy), daemon=True)
            t.start()
            time.sleep(0.4)
            self.assertTrue(t.is_alive(), "the reload must wait for the authorise-and-start step")
            self.assertEqual(svc.revision, before)
            svc._auth_mu.release()
            released = True
            t.join(5)
            self.assertFalse(t.is_alive())
            self.assertNotEqual(svc.revision, before)
        finally:
            if not released:
                svc._auth_mu.release()


# ---- F3: the game panic lock latches in memory before it is persisted -----------------------------------------------


class F3LockLatchTest(QueuedCase):
    def send_lock(self, c, reason="panic"):
        c.send("action.lock", {"actor": c.actor, "reason": reason})
        end = time.time() + 5
        while time.time() < end:
            t, p = c.recv(2)
            if t == "action.state" and p["locked"]:
                return p
        self.fail("no locked state")

    def test_persistence_failure_still_locks_admission(self):
        c = self.env.client()
        other = self.env.client()
        with mock.patch.object(self.env.svc.lock, "set", side_effect=OSError(errno.ENOSPC, "no space")):
            st = self.send_lock(c)
            self.assertFalse(st["armed"])
            self.assertFalse(self.env.lock_path.exists())
            for conn, title in ((c, "same connection"), (other, "other connection")):
                r = self.env.ask(conn, "card.create", {"board": "main", "title": title})
                self.assertEqual((r["status"], r["error"]), ("refused", "write lock is set"), title)
        self.assertEqual(self.calls(), [])
        events = [a["event"] for a in self.env.audit_lines()]
        self.assertIn("game-lock", events)
        self.assertIn("game-lock-persist-failed", events)

    def test_persistence_failure_still_locks_queued_work_in_the_worker(self):
        c = self.env.client()
        release = self.queued_request(c, "card.create", {"board": "main", "title": "queued"}, "latch-queued")
        with mock.patch.object(self.env.svc.lock, "set", side_effect=OSError(errno.EACCES, "denied")):
            self.send_lock(c)
        r = self.result_after(c, "latch-queued", release)
        self.assertEqual((r["status"], r["error"]), ("refused", "write lock is set"))
        self.assertEqual(self.calls(), [])

    def test_nothing_from_the_network_clears_the_latch(self):
        c = self.env.client()
        with mock.patch.object(self.env.svc.lock, "set", side_effect=OSError(errno.ENOSPC, "no space")):
            self.send_lock(c)
            for t in ("action.unlock", "action.clear", "unlock"):
                body, _ = c.build(t, {})
                signed(c, t, body)
                self.assertEqual(c.recv(3)[0], "error")
            self.env.st.call(self.env.svc.poll_state)  # the periodic tick (retries the write, still failing)
            self.assertTrue(self.env.st.call(self.env.svc.lock_is_set))
            r = self.env.ask(c, "card.create", {"board": "main", "title": "x"})
            self.assertEqual(r["status"], "refused")

    def test_latch_is_written_when_the_disk_recovers_and_terminal_unlock_then_clears_it(self):
        c = self.env.client()
        with mock.patch.object(self.env.svc.lock, "set", side_effect=OSError(errno.ENOSPC, "no space")):
            self.send_lock(c)
        self.env.st.call(self.env.svc.poll_state)  # disk is fine again: the retry persists the lock
        self.assertTrue(self.env.lock_path.exists())
        self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": "x"})["status"], "refused")
        os.unlink(self.env.lock_path)  # what `python3 -m hermes_control unlock` does
        self.assertEqual(self.env.ask(c, "card.create", {"board": "main", "title": "y"})["status"], "applied")

    def test_restart_clears_a_latch_that_never_reached_the_disk(self):
        c = self.env.client()
        with mock.patch.object(self.env.svc.lock, "set", side_effect=OSError(errno.ENOSPC, "no space")):
            self.send_lock(c)
        self.env.restart()
        c2 = self.env.client()
        self.assertEqual(self.env.ask(c2, "card.create", {"board": "main", "title": "after restart"})["status"], "applied")

    def test_normal_game_lock_still_writes_the_file(self):
        c = self.env.client()
        st = self.send_lock(c, "ordinary panic")
        self.assertEqual(st["lockReason"], "ordinary panic")
        self.assertTrue(self.env.lock_path.exists())
        rec = [a for a in self.env.audit_lines() if a["event"] == "game-lock"][0]
        self.assertTrue(rec["hermesLockSet"])


# ---- F6: audit writes are complete or refused -----------------------------------------------------------------------


class F6AuditWriteTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.tmp.cleanup)
        os.chmod(self.tmp.name, 0o700)
        self.audit = Audit(Path(self.tmp.name) / "audit.jsonl")
        self.addCleanup(self.audit.close)
        self.real_write = os.write

    def lines(self):
        return self.audit.path.read_bytes().splitlines()

    def test_short_writes_are_looped_until_the_record_is_complete(self):
        fd = self.audit._fd
        seen = []

        def short(f, data):
            if f != fd:
                return self.real_write(f, data)
            n = self.real_write(f, data[:7])
            seen.append(n)
            return n

        with mock.patch("hermes_control.audit.os.write", side_effect=short):
            rid = self.audit.write("probe", note="x" * 100)
        self.assertGreater(len(seen), 5)
        [line] = self.lines()
        rec = json.loads(line)
        self.assertEqual((rec["id"], rec["event"]), (rid, "probe"))

    def test_zero_progress_is_an_audit_error_and_the_partial_record_is_taken_back(self):
        fd = self.audit._fd
        calls = []

        def stall(f, data):
            if f != fd:
                return self.real_write(f, data)
            calls.append(len(data))
            return self.real_write(f, data[:5]) if len(calls) == 1 else 0

        with mock.patch("hermes_control.audit.os.write", side_effect=stall):
            with self.assertRaises(AuditError):
                self.audit.write("probe", note="y" * 50)
        self.assertEqual(self.audit.path.read_bytes(), b"")  # no half record left behind
        self.audit.write("after", note="z")
        [line] = self.lines()
        self.assertEqual(json.loads(line)["event"], "after")

    def test_a_partial_record_that_cannot_be_removed_does_not_poison_the_next_line(self):
        fd = self.audit._fd
        calls = []

        def stall(f, data):
            if f != fd:
                return self.real_write(f, data)
            calls.append(1)
            return self.real_write(f, data[:5]) if len(calls) == 1 else 0

        with mock.patch("hermes_control.audit.os.write", side_effect=stall), mock.patch("hermes_control.audit.os.ftruncate", side_effect=OSError(errno.EPERM, "no")):
            with self.assertRaises(AuditError):
                self.audit.write("probe", note="y" * 50)
        self.audit.write("after", note="z")
        good = []
        for line in self.lines():
            try:
                good.append(json.loads(line)["event"])
            except ValueError:
                pass
        self.assertEqual(good, ["after"])

    def test_eintr_is_retried(self):
        fd = self.audit._fd
        state = {"n": 0}

        def flaky(f, data):
            if f == fd:
                state["n"] += 1
                if state["n"] == 1:
                    raise InterruptedError()
            return self.real_write(f, data)

        with mock.patch("hermes_control.audit.os.write", side_effect=flaky):
            self.audit.write("probe")
        self.assertGreaterEqual(state["n"], 2)
        self.assertEqual(json.loads(self.lines()[0])["event"], "probe")

    def test_fsync_runs_once_after_the_whole_record(self):
        fd = self.audit._fd
        events = []
        real_fsync = os.fsync

        def short(f, data):
            n = self.real_write(f, data[:10]) if f == fd else self.real_write(f, data)
            if f == fd:
                events.append(("write", n))
            return n

        def sync(f):
            if f == fd:
                events.append(("fsync", 0))
            return real_fsync(f)

        with mock.patch("hermes_control.audit.os.write", side_effect=short), mock.patch("hermes_control.audit.os.fsync", side_effect=sync):
            self.audit.write("probe", note="q" * 60)
        total = len(self.audit.path.read_bytes())
        self.assertEqual(events[-1], ("fsync", 0))
        self.assertEqual([e for e in events if e[0] == "fsync"], [("fsync", 0)])
        self.assertEqual(sum(n for k, n in events if k == "write"), total)


class F6AuditAdmissionTest(QueuedCase):
    def test_incomplete_admission_record_refuses_the_action(self):
        c = self.env.client()
        fd = self.env.audit._fd
        real = os.write
        state = {"cut": 0}

        def cut(f, data):
            if f == fd and b'"decision":"admitted"' in data:
                state["cut"] += 1
                return real(f, data[:1]) if state["cut"] == 1 else 0
            return real(f, data)

        with mock.patch("hermes_control.audit.os.write", side_effect=cut):
            r = self.env.ask(c, "card.create", {"board": "main", "title": "short admission"}, id_="short-audit")
        self.assertEqual((r["status"], r["error"]), ("refused", "audit log unavailable"))
        self.assertEqual(self.calls(), [])
        self.assertEqual(self.env.ledger.get_claim(ACTOR, "short-audit")["state"], ledger_mod.REFUSED)
        raw = self.env.audit_path.read_text()
        self.assertNotIn('"decision":"admitted"', raw)
        for line in raw.splitlines():
            json.loads(line)  # no invalid fragment either


# ---- F7: lock state goes through the privacy filter ----------------------------------------------------------------


class F7LockStatePrivacyTest(QueuedCase):
    def locked_state(self, c):
        end = time.time() + 5
        while time.time() < end:
            t, p = c.recv(2)
            if t == "action.state" and p["locked"]:
                return p
        self.fail("no locked state")

    def test_lock_reason_and_locked_by_are_redacted_before_the_caps(self):
        c = self.env.client()
        # the credential starts before the 200-character cap: slicing first would leave an unmatched prefix of it
        reason = "x" * 185 + " " + FAKE_TOKEN + " contact ops@example.com"
        self.env.lock_path.write_text(json.dumps({"reason": reason, "by": "terminal:" + "ops@example.com", "since": 5}))
        self.env.st.call(self.env.svc.poll_state)
        st = self.locked_state(c)
        for field in ("lockReason", "lockedBy"):
            self.assertNotIn("sk-ant", st[field])
            self.assertNotIn("AbCdEf", st[field])
            self.assertNotIn("example.com", st[field])
        self.assertLessEqual(len(st["lockReason"]), 200)
        self.assertLessEqual(len(st["lockedBy"]), 64)
        self.assertEqual(st["since"], 5)

    def test_lock_state_in_the_handshake_is_redacted_too(self):
        self.env.lock_path.write_text(json.dumps({"reason": "see " + FAKE_TOKEN, "by": "terminal:owner", "since": 1}))
        c = self.env.client(handshake=False)
        got = c.handshake()
        st = got[3][1]
        self.assertTrue(st["locked"])
        self.assertNotIn("sk-ant", st["lockReason"])
        self.assertEqual(st["lockedBy"], "terminal:owner")

    def test_ordinary_structural_values_survive(self):
        c = self.env.client()
        c.send("action.lock", {"actor": c.actor, "reason": "panic from game"})
        st = self.locked_state(c)
        self.assertEqual(st["lockReason"], "panic from game")
        self.assertTrue(st["lockedBy"].startswith("game:TestPlayer/00000000"))


# ---- F9: strict nonce expiry ----------------------------------------------------------------------------------------


class FrozenClock:
    def __init__(self, t: int):
        self.t = t

    def __call__(self) -> int:
        return self.t


class F9NonceBoundaryTest(unittest.TestCase):
    def test_exact_boundary_replay_of_a_lock_frame_is_refused(self):
        clk = FrozenClock(int(time.time() * 1000))
        env = Env(dry_run=True, clock=clk).start()
        self.addCleanup(env.close)
        c = env.client()
        self.addCleanup(c.close)
        body, payload = c.build("action.lock", {"actor": c.actor, "reason": "captured panic"}, ts=clk.t)
        signed(c, "action.lock", body)
        self.assertTrue(c.recv(3)[1]["locked"])
        os.unlink(env.lock_path)  # the owner clears it from the terminal
        env.st.call(env.svc.poll_state)
        self.assertFalse(c.recv(3)[1]["locked"])  # the unlocked state push
        clk.t = payload["ts"] + 60_000  # last millisecond the timestamp window still accepts
        signed(c, "action.lock", body)  # the identical signed frame
        t, p = c.recv(3)
        self.assertEqual(t, "error")  # nonce replay
        time.sleep(0.2)
        self.assertFalse(env.lock_path.exists())
        self.assertFalse(env.st.call(env.svc.lock_is_set))
        # one millisecond later the timestamp window itself refuses the frame
        clk.t = payload["ts"] + 60_001
        signed(c, "action.lock", body)
        self.assertEqual(c.recv(3)[0], "error")
        self.assertFalse(env.lock_path.exists())

    def test_ledger_keeps_a_nonce_through_its_expiry_millisecond(self):
        tmp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(tmp.cleanup)
        os.chmod(tmp.name, 0o700)
        clk = FrozenClock(1_000_000)
        led = ledger_mod.Ledger(Path(tmp.name) / "l.sqlite3", clk, allow_in_repo=True)
        self.addCleanup(led.close)
        self.assertEqual(led.take_nonce("n1", 1_000_000, 1_000_000), "ok")
        exp = 1_000_000 + ledger_mod.NONCE_WINDOW_MS
        self.assertEqual(led.take_nonce("n1", 1_000_000, exp), "replay")  # still ts-valid
        led.prune(exp)
        self.assertEqual(led.nonce_count(), 1)  # prune keeps it too
        self.assertEqual(led.take_nonce("n1", 1_000_000, exp + 1), "ok")  # gone one millisecond later

    def test_startup_prune_keeps_a_nonce_expiring_this_millisecond(self):
        tmp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(tmp.cleanup)
        os.chmod(tmp.name, 0o700)
        path = Path(tmp.name) / "l.sqlite3"
        led = ledger_mod.Ledger(path, FrozenClock(1_000), allow_in_repo=True)
        self.assertEqual(led.take_nonce("keep", 1_000, 1_000), "ok")
        led.close()
        led2 = ledger_mod.Ledger(path, FrozenClock(1_000 + ledger_mod.NONCE_WINDOW_MS), allow_in_repo=True)
        self.addCleanup(led2.close)
        self.assertEqual(led2.nonce_count(), 1)
        led2.close()
        led3 = ledger_mod.Ledger(path, FrozenClock(1_000 + ledger_mod.NONCE_WINDOW_MS + 1), allow_in_repo=True)
        self.addCleanup(led3.close)
        self.assertEqual(led3.nonce_count(), 0)


# ---- F10: handshake ordering ----------------------------------------------------------------------------------------


class F10HandshakeOrderTest(ControlCase):
    autostart = True

    def install(self, hook):
        svc = self.env.svc
        orig = svc.send

        async def send(conn, type_, fields):
            hook(svc, conn, type_)
            return await orig(conn, type_, fields)

        self.env.st.call(lambda: setattr(svc, "send", send))

    def test_a_broadcast_scheduled_at_the_ack_boundary_cannot_interleave(self):
        states = []

        def hook(svc, conn, type_):
            states.append((type_, conn.state))
            if type_ == "ack":
                asyncio.ensure_future(svc.broadcast_state())  # the ordinary state broadcaster wakes up right now

        self.install(hook)
        c = self.env.client(handshake=False)
        _, ch = c.recv()
        c.session = ch["session"]
        c.send("hello", {"challenge": ch["challenge"], "features": ["action"]})
        order = [c.recv(3)[0] for _ in range(4)]
        self.assertEqual(order, ["ack", "action.policy", "action.state", "action.state"])  # the broadcast comes last, after the handshake
        self.assertEqual([s for t, s in states if t in ("ack", "action.policy")], ["handshaking", "handshaking"])

    def test_broadcasters_skip_a_connection_that_is_still_handshaking(self):
        seen = []

        def hook(svc, conn, type_):
            if type_ == "action.state":
                seen.append(conn.state)

        self.install(hook)
        c = self.env.client()
        self.assertEqual(seen, ["handshaking"])
        self.assertEqual([x.state for x in self.env.svc.conns], ["ready"])
        c.close()

    def test_a_lock_set_during_the_handshake_is_still_delivered(self):
        def hook(svc, conn, type_):
            if type_ == "ack":
                svc.lock.set("lock during handshake", "terminal:owner", 7)  # appears before the state frame is built

        self.install(hook)
        c = self.env.client(handshake=False)
        _, ch = c.recv()
        c.session = ch["session"]
        c.send("hello", {"challenge": ch["challenge"], "features": ["action"]})
        got = [c.recv(3) for _ in range(3)]
        self.assertEqual([t for t, _ in got], ["ack", "action.policy", "action.state"])
        self.assertTrue(got[2][1]["locked"])

    def test_uncontended_order_is_unchanged(self):
        c = self.env.client(handshake=False)
        got = c.handshake()
        self.assertEqual([t for t, _ in got], ["action.challenge", "ack", "action.policy", "action.state"])


# ---- QA allowance: --dev-offline-actors -----------------------------------------------------------------------------


class DevOfflinePolicyTest(unittest.TestCase):
    def test_v3_is_refused_by_default_and_allowed_with_the_flag(self):
        d = base_policy()
        d["actors"] = [V3_ACTOR]
        with self.assertRaises(pol.PolicyError):
            pol.parse(d)
        self.assertEqual(pol.parse(d, allow_offline=True).actors, frozenset({V3_ACTOR}))

    def test_the_flag_allows_only_version_3_with_a_valid_variant(self):
        for bad in ("00000000-0000-2000-8000-000000000003", "00000000-0000-5000-8000-000000000003", "00000000-0000-3000-c000-000000000003", "Steve"):
            d = base_policy()
            d["actors"] = [bad]
            with self.assertRaises(pol.PolicyError, msg=bad):
                pol.parse(d, allow_offline=True)

    def test_v4_still_fine_with_the_flag(self):
        d = base_policy()
        self.assertEqual(pol.parse(d, allow_offline=True).actors, frozenset({ACTOR}))


class DevOfflineCliTest(unittest.TestCase):
    def setUp(self):
        d = base_policy()
        d["actors"] = [V3_ACTOR]
        self.env = Env(policy=d)
        self.addCleanup(self.env.close)
        self.argv = ["serve", "--policy", str(self.env.policy_path), "--key-file", str(self.env.key_path), "--state-dir", str(self.env.state),
                     "--audit-file", str(self.env.audit_path), "--port", "0"]

    def build(self, *extra):
        svc = cli.build(cli.parse_args(self.argv + list(extra)), clock=None)
        svc.ledger.close()
        svc.audit.close()
        return svc

    def test_without_the_flag_a_v3_actor_is_refused_at_start(self):
        with self.assertRaises(cli.StartupRefused):
            self.build("--dry-run")

    def test_the_flag_needs_dry_run(self):
        with self.assertRaises(cli.StartupRefused) as cm:
            self.build("--dev-offline-actors")
        self.assertIn("--dry-run", str(cm.exception))

    def test_the_flag_needs_a_loopback_bind(self):
        with self.assertRaises(cli.StartupRefused) as cm:
            self.build("--dry-run", "--dev-offline-actors", "--bind", "192.0.2.10", "--insecure-lan-bind")
        self.assertIn("loopback", str(cm.exception))
        for b in ("127.0.0.1", "localhost", "::1"):
            self.assertTrue(self.build("--dry-run", "--dev-offline-actors", "--bind", b).dev_offline_actors, b)

    def test_flag_with_dry_run_on_loopback_is_accepted(self):
        svc = self.build("--dry-run", "--dev-offline-actors")
        self.assertTrue(svc.dry_run)
        self.assertTrue(svc.dev_offline_actors)
        self.assertEqual(svc.policy.actors, frozenset({V3_ACTOR}))

    def test_flag_is_off_by_default(self):
        d = base_policy()
        self.env.write_policy(d)
        self.assertFalse(self.build("--dry-run").dev_offline_actors)

    def test_help_marks_it_qa_only(self):
        ap_help = []
        import io
        from contextlib import redirect_stdout

        buf = io.StringIO()
        with redirect_stdout(buf), self.assertRaises(SystemExit):
            cli.parse_args(["serve", "--help"])
        ap_help.append(buf.getvalue())
        self.assertIn("QA ONLY", ap_help[0])


class DevOfflineServiceTest(ControlCase):
    autostart = False

    def policy(self, env):
        d = base_policy(env)
        d["actors"] = [V3_ACTOR]
        return d

    def start_dev(self):
        argv = ["serve", "--policy", str(self.env.policy_path), "--key-file", str(self.env.key_path), "--state-dir", str(self.env.state),
                "--audit-file", str(self.env.audit_path), "--port", "0", "--dry-run", "--dev-offline-actors", "--board-fixture", str(FIXTURE)]
        svc = cli.build(cli.parse_args(argv), clock=None)
        self.env.svc, self.env.ledger, self.env.audit = svc, svc.ledger, svc.audit
        self.env.st = ServerThread(svc)
        self.env.port = svc.port

    def test_policy_keeps_dry_run_true_audit_records_the_allowance_and_a_v3_actor_works(self):
        self.start_dev()
        c = control_client.ControlClient("127.0.0.1", self.env.port, self.env.key, V3_ACTOR, "OfflinePlayer", quiet=True)
        got = c.handshake()
        self.assertTrue(got[2][1]["dryRun"])
        self.assertEqual(got[2][1]["actors"], [V3_ACTOR])
        r = self.env.ask(c, "card.create", {"board": "main", "title": "dev qa"})
        self.assertEqual(r["status"], "applied")
        self.assertTrue(r["dryRun"])
        start = [a for a in self.env.audit_lines() if a["event"] == "start"][0]
        self.assertTrue(start["devOfflineActors"])
        self.assertTrue(start["dryRun"])

    def test_reload_keeps_accepting_v3_actors_under_the_flag(self):
        self.start_dev()
        self.assertTrue(self.env.st.call(self.env.svc.reload_policy))
        self.assertEqual(self.env.svc.policy.actors, frozenset({V3_ACTOR}))


if __name__ == "__main__":
    unittest.main()
