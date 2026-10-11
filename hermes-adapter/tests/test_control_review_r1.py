"""Regression tests for review r1 of the control service: R1 (live service refuses what the Hermes CLI cannot
do conditionally), R2 (chat toolset allowlist), R3 (execution-start boundary), R5 (game tag on title edits,
priority-only edits refused).

Nothing here calls a real ``hermes``: the fake script from ``test_control_common`` records its argv, and the
direct-drive tests patch ``executors.run_argv`` / ``subprocess.Popen`` so a spawn they do not expect fails the
test. Placeholder data only. Synchronisation is by ``threading.Event``; the only waits are short negative
checks ("the reload has NOT finished while the worker is inside the boundary").
"""

from __future__ import annotations

import contextlib
import copy
import dataclasses
import threading
import unittest
from unittest import mock

from test_control_common import ACTOR, ControlCase, base_policy

from hermes_control import classify, executors, frames, policy as pol
from hermes_control.lock import set_lock
from hermes_control.service import Locked

NAME = "TestPlayer"
TAG = executors.tag(NAME, ACTOR)

# the live-unavailable requests: (capability, args)
UNAVAILABLE = [
    ("decision.answer", {"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}),
    ("card.dispatch", {"card": "t-demo-1", "board": "main", "profile": "builder-a"}),
    ("card.edit", {"card": "t-demo-1", "title": "New title"}),
    ("card.edit", {"card": "t-demo-1", "body": "New body"}),
    ("card.edit", {"card": "t-demo-1", "priority": 5}),
    ("card.edit", {"card": "t-demo-1", "title": "T", "body": "B", "priority": 5}),
    ("agent.chat", {"agent": "helper-a", "conversation": "conv1", "text": "hello"}),
    ("agent.ask", {"agent": "helper-a", "text": "hello"}),
]
# a minimal valid (args, prep) per capability, for driving an executor directly
SAMPLE = {
    "decision.answer": ({"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}, {"board": "main", "card": "t-demo-2", "choice": "Option A", "note": ""}),
    "card.create": ({"board": "main", "title": "New thing"}, {}),
    "card.edit": ({"card": "t-demo-1", "comment": "hello"}, {"board": "main", "card": "t-demo-1"}),
    "card.dispatch": ({"card": "t-demo-1", "board": "main", "profile": "builder-a"}, {"board": "main", "card": "t-demo-1"}),
    "agent.chat": ({"agent": "helper-a", "conversation": "conv1", "text": "hello"}, {}),
    "agent.ask": ({"agent": "helper-a", "text": "hello"}, {}),
    "service.restart": ({"service": "service-1"}, {}),
    "cron.run": ({"job": "job-a1"}, {}),
}


class Sentinel(Exception):
    pass


def final(env, c, rid):
    """The final action.result for a request (skips an interim 'queued')."""
    while True:
        res = env.result_for(c, rid)
        if not (res["status"] == "queued" and res["error"] == ""):
            return res


# ---- shared helpers --------------------------------------------------------------------------------------------------


class DirectCase(ControlCase):
    """A built service WITHOUT a server: tests call ``svc._guarded`` / an executor directly from the test thread."""

    autostart = False

    def setUp(self) -> None:
        super().setUp()
        self.env.build()
        self.svc = self.env.svc

    def req(self, cap, args=None, prep=None, name=NAME):
        a, p = SAMPLE[cap] if args is None else (args, prep or {})
        return executors.ExecRequest(cap, dict(a), ACTOR, name, "req-1", self.svc.policy, dict(p if prep is None else prep), self.svc.revision)

    def use_production(self) -> dict:
        self.svc.executors = executors.build(False)
        return self.svc.executors

    def policy_without(self, *caps) -> dict:
        d = copy.deepcopy(self.env.policy_dict)
        for c in caps:
            d["capabilities"][c]["enabled"] = False
        return d

    @contextlib.contextmanager
    def no_spawn(self, run_argv_allowed=True):
        """Popen fails if reached (run_argv too unless allowed: it is where the guard is entered); afterwards no
        process was spawned and the fake hermes never ran."""
        real = executors.run_argv
        with mock.patch.object(executors, "run_argv", wraps=real if run_argv_allowed else None,
                               side_effect=None if run_argv_allowed else AssertionError("run_argv was called")) as ra, \
                mock.patch.object(executors.subprocess, "Popen", side_effect=AssertionError("Popen was called")) as po:
            yield
        self.assertEqual(po.call_count, 0)
        if not run_argv_allowed:
            self.assertEqual(ra.call_count, 0)
        self.assertEqual(self.env.hermes_calls(), [])


# ---- R1 ------------------------------------------------------------------------------------------------------------


class R1LiveServiceTest(ControlCase):
    """Exactly the executors the CLI builds (``executors.build(False)``)."""

    production_executors = True

    def test_unavailable_requests_are_refused_and_nothing_runs(self):
        c = self.env.client()
        for cap, args in UNAVAILABLE:
            with self.subTest(cap=cap, args=sorted(args)):
                r = self.env.ask(c, cap, args)
                want = executors.NO_CHAT_BOUNDARY if cap.startswith("agent.") else executors.NO_CONDITIONAL_CHANGE
                self.assertEqual(r["status"], "refused")
                self.assertEqual(r["error"], want)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_dispatch_is_refused_before_a_confirm_prompt(self):
        c = self.env.client()
        _rid, prompt, res = self.env.prompt(c, {"card": "t-demo-1", "board": "main", "profile": "builder-a"})
        self.assertIsNone(prompt)
        self.assertEqual((res["status"], res["error"]), ("refused", executors.NO_CONDITIONAL_CHANGE))
        self.assertEqual(self.env.hermes_calls(), [])

    def test_comment_and_create_still_run_live(self):
        c = self.env.client()
        r = self.env.ask(c, "card.edit", {"card": "t-demo-1", "comment": "a plain comment"})
        self.assertEqual((r["status"], r["error"]), ("applied", ""))
        r = self.env.ask(c, "card.create", {"board": "main", "title": "A new card"})
        self.assertEqual((r["status"], r["error"]), ("applied", ""))
        calls = self.env.hermes_calls()
        self.assertEqual(len(calls), 2)
        self.assertIn("comment", calls[0]["argv"])
        self.assertIn("create", calls[1]["argv"])

    def test_handshake_policy_shows_the_unavailable_capabilities_disabled(self):
        got = self.env.client(handshake=False).handshake()
        wire = dict(got)["action.policy"]["capabilities"]
        for cap in ("decision.answer", "card.dispatch", "agent.chat", "agent.ask"):
            self.assertFalse(wire[cap]["enabled"], cap)
        for cap in ("card.edit", "card.create", "service.restart", "cron.run"):
            self.assertTrue(wire[cap]["enabled"], cap)

    def test_audit_lists_what_is_live_unavailable(self):
        rows = [a for a in self.env.audit_lines() if a["event"] == "live-unavailable"]
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["capabilities"], ["agent.ask", "agent.chat", "card.dispatch", "decision.answer"])

    def test_policy_disabled_capability_is_not_listed_as_live_unavailable(self):
        d = self.env.policy_dict
        d["capabilities"]["agent.chat"]["enabled"] = False
        self.env.write_policy(d)
        self.env.restart()
        rows = [a for a in self.env.audit_lines() if a["event"] == "live-unavailable"]
        self.assertEqual(rows[-1]["capabilities"], ["agent.ask", "card.dispatch", "decision.answer"])


class R1DryRunTest(ControlCase):
    dry_run = True

    def test_dry_run_keeps_every_capability_and_writes_no_live_unavailable_entry(self):
        c = self.env.client(handshake=False)
        wire = dict(c.handshake())["action.policy"]["capabilities"]
        self.assertTrue(all(w["enabled"] for w in wire.values()))
        self.assertNotIn("live-unavailable", [a["event"] for a in self.env.audit_lines()])
        r = self.env.ask(c, "card.edit", {"card": "t-demo-1", "priority": 5})  # refused for its own reason, not the live gate
        self.assertEqual(r["error"], executors.PRIORITY_ALONE)
        r = self.env.ask(c, "agent.ask", {"agent": "helper-a", "text": "hello"})
        self.assertEqual(r["status"], "applied")


class R1UnitTest(unittest.TestCase):
    def test_live_unavailable_table(self):
        for cap, args in UNAVAILABLE:
            self.assertTrue(executors.live_unavailable(cap, args), (cap, args))
        for cap, args in (("card.create", {"board": "main", "title": "x"}), ("card.edit", {"card": "c", "comment": "x"}),
                          ("service.restart", {"service": "s"}), ("cron.run", {"job": "j"})):
            self.assertIsNone(executors.live_unavailable(cap, args), cap)

    def test_every_capability_has_an_executor_class_and_the_mock_has_none_unavailable(self):
        self.assertEqual(set(executors.BY_CAPABILITY), set(frames.CAPABILITIES))
        m = executors.MockExecutor()
        self.assertEqual(m.unavailable_caps(), frozenset())
        for cap, args in UNAVAILABLE:
            self.assertIsNone(m.unavailable(cap, args))
        for cls in set(executors.BY_CAPABILITY.values()):
            self.assertEqual(cls().unavailable_caps(), executors.LIVE_UNAVAILABLE_CAPS, cls.__name__)

    def test_every_executor_class_refuses_before_run_argv(self):
        entered = []

        @contextlib.contextmanager
        def guard():
            entered.append(1)
            yield

        for cls in sorted(set(executors.BY_CAPABILITY.values()), key=lambda k: k.__name__):
            for cap, args in UNAVAILABLE:
                with self.subTest(cls=cls.__name__, cap=cap, args=sorted(args)):
                    prep = {"board": "main", "card": args.get("card", "t-demo-1"), "choice": args.get("choice", ""), "note": ""}
                    req = executors.ExecRequest(cap, args, ACTOR, NAME, "r1", pol.parse(base_policy()), prep, "rev", start_guard=guard)
                    with mock.patch.object(executors, "run_argv", side_effect=AssertionError("run_argv called")) as ra, \
                            mock.patch.object(executors.subprocess, "Popen", side_effect=AssertionError("Popen called")) as po:
                        with self.assertRaises(executors.ExecRefused) as cm:
                            cls().execute(req)
                    self.assertEqual((ra.call_count, po.call_count), (0, 0))
                    self.assertIn(str(cm.exception), (executors.NO_CONDITIONAL_CHANGE, executors.NO_CHAT_BOUNDARY))
        self.assertEqual(entered, [])


class R1BoundaryInterleavingTest(DirectCase):
    """The board changes between admission (prep) and the mutation. Production executors must never reach the
    CLI; with the gate lifted (test-only executors) the start boundary's own re-check refuses."""

    def prep_for(self, cap, args):
        return self.svc.prepare(cap, self.svc.policy.cap(cap), args)[0]

    def scenarios(self):
        """(name, req, patcher context, lifted-gate refusal pattern)"""
        reader = self.env.board
        a_dec = {"card": "t-demo-2", "decision": "d-main-101", "choice": "Option A"}
        dec_req = self.req("decision.answer", a_dec, self.prep_for("decision.answer", a_dec))
        orig_dec = reader.decision("main", "t-demo-2", "d-main-101")
        halt = {**orig_dec, "event": "evt-replacement", "reason": "PERMISSION p9: run the placeholder command || CHOICES: Approve | Deny"}
        self.assertEqual(classify.classify(orig_dec["reason"], orig_dec.get("blockKind")).kind, classify.QUESTION)
        self.assertEqual(classify.classify(halt["reason"], None).kind, classify.PERMISSION)

        a_dis = {"card": "t-demo-1", "board": "main", "profile": "builder-a"}
        dis_req = self.req("card.dispatch", a_dis, self.prep_for("card.dispatch", a_dis))
        card1 = reader.card("main", "t-demo-1")
        a_edit = {"card": "t-demo-1", "title": "New title"}
        edit_req = self.req("card.edit", a_edit, self.prep_for("card.edit", a_edit))
        return [
            ("replacement permission halt", dec_req, mock.patch.object(reader, "decision", return_value=halt), "the decision changed"),
            ("decision revision gone", dec_req, mock.patch.object(reader, "decision", return_value=None), "not open any more"),
            ("changed dispatch revision", dis_req, mock.patch.object(reader, "card", return_value={**card1, "revision": "changed-rev"}), "card changed since the prompt"),
            ("dispatch target turned running", dis_req, mock.patch.object(reader, "card", return_value={**card1, "status": "running", "run": 3}), "no longer dispatchable"),
            ("edited card turned running", edit_req, mock.patch.object(reader, "card", return_value={**card1, "status": "running", "run": 3}), "card is running"),
        ]

    def test_production_executors_never_run_anything(self):
        prod = self.use_production()
        for name, req, patcher, _pat in self.scenarios():
            for how in ("guarded", "executor"):
                with self.subTest(scenario=name, how=how), patcher, self.no_spawn(run_argv_allowed=False):
                    with self.assertRaises(executors.ExecRefused) as cm:
                        if how == "guarded":
                            self.svc._guarded(prod[req.capability], req)
                        else:
                            prod[req.capability].execute(req)
                    self.assertEqual(str(cm.exception), executors.NO_CONDITIONAL_CHANGE)
                    self.assertFalse(req.started)

    def test_start_boundary_refuses_when_the_gate_is_lifted(self):
        for name, req, patcher, pat in self.scenarios():
            with self.subTest(scenario=name), patcher, self.no_spawn():
                with self.assertRaisesRegex(executors.ExecRefused, pat):
                    self.svc._guarded(self.svc.executors[req.capability], req)
                self.assertFalse(req.started)

    def test_unchanged_board_still_runs_when_the_gate_is_lifted(self):  # the refusals above are the checks, not the harness
        a = {"card": "t-demo-1", "board": "main", "profile": "builder-a"}
        req = self.req("card.dispatch", a, self.prep_for("card.dispatch", a))
        res = self.svc._guarded(self.svc.executors["card.dispatch"], req)
        self.assertEqual(res.status, "applied")
        self.assertTrue(req.started)
        self.assertEqual(len(self.env.hermes_calls()), 1)


# ---- R2 ------------------------------------------------------------------------------------------------------------


class R2ToolsetAllowlistTest(unittest.TestCase):
    def policy(self, cap, toolsets):
        d = base_policy()
        d["capabilities"][cap]["toolsets"] = toolsets
        return d

    def test_non_vetted_toolsets_make_the_policy_fail(self):
        for cap in ("agent.ask", "agent.chat"):
            for ts in (["debugging"], ["file"], ["skills"], ["safe"], ["coding"], ["web"], ["custom-name"], ["search", "terminal"], ["Search"], ["search "]):
                with self.subTest(cap=cap, toolsets=ts), self.assertRaises(pol.PolicyError):
                    pol.parse(self.policy(cap, ts))

    def test_even_a_disabled_capability_cannot_carry_an_unvetted_toolset(self):
        d = self.policy("agent.ask", ["debugging"])
        d["capabilities"]["agent.ask"]["enabled"] = False
        with self.assertRaises(pol.PolicyError):
            pol.parse(d)

    def test_search_loads_and_duplicates_are_refused(self):
        p = pol.parse(self.policy("agent.ask", ["search"]))
        self.assertEqual(p.cap("agent.ask").toolsets, ("search",))
        self.assertEqual(pol.CHAT_TOOLSETS_VETTED, frozenset({"search"}))
        with self.assertRaises(pol.PolicyError):
            pol.parse(self.policy("agent.ask", ["search", "search"]))

    def test_load_from_a_file_refuses_too(self):
        from test_control_common import Env

        env = Env()
        self.addCleanup(env.close)
        d = base_policy(env)
        d["capabilities"]["agent.ask"]["toolsets"] = ["debugging"]
        env.write_policy(d)
        with self.assertRaises(pol.PolicyError):
            pol.load(env.policy_path)

    def test_check_chat_argv_refuses_non_vetted_toolsets(self):
        base = ["hermes", "--profile", "helper-a", "chat", "--oneshot", "--quiet", "--query-file", "-", "--toolsets"]
        executors.check_chat_argv([*base, "search"], ("search",))
        for bad in ("debugging", "file", "search,terminal"):
            with self.subTest(bad=bad):
                with self.assertRaises(executors.ExecRefused):
                    executors.check_chat_argv([*base, bad], tuple(bad.split(",")))
        with self.assertRaises(executors.ExecRefused):  # argv and policy disagree
            executors.check_chat_argv([*base, "search"], ("search", "debugging"))

    def test_argv_for_refuses_a_policy_object_that_bypasses_the_parser(self):
        p = pol.parse(base_policy())
        cp = dataclasses.replace(p.cap("agent.ask"), toolsets=("debugging",))
        p2 = dataclasses.replace(p, caps={**p.caps, "agent.ask": cp})
        req = executors.ExecRequest("agent.ask", {"agent": "helper-a", "text": "hi"}, ACTOR, NAME, "r1", p2)
        with self.assertRaises(executors.ExecRefused):
            executors.argv_for(req)



class R2ReloadTest(DirectCase):
    def test_reload_with_an_unvetted_toolset_keeps_the_old_policy(self):
        before = self.svc.revision
        d = copy.deepcopy(self.env.policy_dict)
        d["capabilities"]["agent.ask"]["toolsets"] = ["coding"]
        self.env.write_policy(d)
        self.assertFalse(self.svc.reload_policy())
        self.assertEqual(self.svc.revision, before)
        self.assertIn("policy-reload-failed", [a["event"] for a in self.env.audit_lines()])


# ---- R3 ------------------------------------------------------------------------------------------------------------


class R3StartBoundaryTest(DirectCase):
    def after_precheck(self, hook):
        """Run ``hook()`` after _guarded's cheap pre-check, before the executor reaches the boundary."""
        real = executors.argv_for

        def wrapper(req):
            hook()
            return real(req)

        return mock.patch.object(executors, "argv_for", side_effect=wrapper)

    def test_control_run_without_interference_applies(self):
        req = self.req("card.create")
        res = self.svc._guarded(self.svc.executors["card.create"], req)
        self.assertEqual(res.status, "applied")
        self.assertTrue(req.started)
        self.assertEqual(len(self.env.hermes_calls()), 1)

    def test_reload_winning_just_before_launch_refuses(self):
        req = self.req("card.create")

        def reload_disabling():
            self.env.write_policy(self.policy_without("card.create"))
            self.assertTrue(self.svc.reload_policy())

        with self.after_precheck(reload_disabling):
            with self.assertRaisesRegex(executors.ExecRefused, "policy changed"):
                self.svc._guarded(self.svc.executors["card.create"], req)
        self.assertFalse(req.started)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_reload_that_changes_nothing_relevant_still_refuses_queued_work(self):  # revision equality, not "still allowed"
        req = self.req("card.create")

        def reload_same():
            self.assertTrue(self.svc.reload_policy())

        with self.after_precheck(reload_same):
            with self.assertRaisesRegex(executors.ExecRefused, "policy changed"):
                self.svc._guarded(self.svc.executors["card.create"], req)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_in_memory_latch_before_the_boundary_refuses(self):
        req = self.req("card.create")

        def latch():  # the same step on_lock takes, under the same mutex
            with self.svc._auth_mu:
                self.svc._latch_lock("panic", "game:TestPlayer/00000000", self.svc.now())

        with self.after_precheck(latch):
            with self.assertRaises(Locked):
                self.svc._guarded(self.svc.executors["card.create"], req)
        self.assertFalse(req.started)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_lock_file_before_the_boundary_refuses(self):
        req = self.req("card.create")
        with self.after_precheck(lambda: set_lock(self.svc.lock, "terminal lock", "test")):
            with self.assertRaises(Locked):
                self.svc._guarded(self.svc.executors["card.create"], req)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_lock_before_the_cheap_precheck_refuses_without_entering_the_executor(self):
        with self.svc._auth_mu:
            self.svc._latch_lock("panic", "game:x", self.svc.now())
        with mock.patch.object(self.svc.executors["card.create"], "execute", side_effect=AssertionError("executor entered")):
            with self.assertRaises(Locked):
                self.svc._guarded(self.svc.executors["card.create"], self.req("card.create"))

    def test_frozen_service_refuses_at_the_boundary(self):
        req = self.req("card.create")

        def freeze():
            self.svc.frozen = "clock"

        with self.after_precheck(freeze):
            with self.assertRaises(Locked):
                self.svc._guarded(self.svc.executors["card.create"], req)
        self.assertEqual(self.env.hermes_calls(), [])

    def test_a_request_admitted_under_no_revision_is_refused(self):
        req = self.req("card.create")
        req.revision = ""
        with self.assertRaisesRegex(executors.ExecRefused, "policy changed"):
            self.svc._guarded(self.svc.executors["card.create"], req)

    # ---- every executor class enters the guard exactly once, before it starts anything ----------------------------------

    def maps(self):
        yield "live", executors.build(False)
        yield "gate-lifted", self.svc.executors
        yield "mock", executors.build(True)

    @staticmethod
    def runnable(name, ex, cap):
        args, _ = SAMPLE[cap]
        return not ex.unavailable(cap, args)

    def test_guard_raising_means_nothing_started(self):
        @contextlib.contextmanager
        def boom():
            raise Sentinel()
            yield  # pragma: no cover

        seen = 0
        for name, exmap in self.maps():
            for cap, ex in exmap.items():
                if not self.runnable(name, ex, cap):
                    continue
                seen += 1
                with self.subTest(map=name, cap=cap), self.no_spawn():
                    req = self.req(cap)
                    req.start_guard = boom
                    with self.assertRaises(Sentinel):
                        ex.execute(req)
                    if isinstance(ex, executors.MockExecutor):
                        self.assertEqual(ex.calls, [])
        self.assertGreaterEqual(seen, 8 + 8 + 4)  # mock + gate-lifted: all 8; live: the 4 that can run

    def test_guard_is_entered_once_before_the_start_and_left_after_it(self):
        for name, exmap in self.maps():
            for cap, ex in exmap.items():
                if not self.runnable(name, ex, cap):
                    continue
                with self.subTest(map=name, cap=cap):
                    events: list = []
                    before = len(ex.calls) if isinstance(ex, executors.MockExecutor) else 0

                    class FakePopen:
                        returncode = 0
                        pid = 1

                        def __init__(self, *a, **k):
                            events.append("start")

                        def communicate(self, data=None, timeout=None):
                            return b'{"id": "t_x"}', b""

                    @contextlib.contextmanager
                    def guard():
                        events.append("enter")
                        yield
                        events.append("exit" if not isinstance(ex, executors.MockExecutor) else f"exit:{len(ex.calls) - before}")

                    req = self.req(cap)
                    req.start_guard = guard
                    with mock.patch.object(executors.subprocess, "Popen", FakePopen):
                        res = ex.execute(req)
                    self.assertEqual(res.status, "applied")
                    if isinstance(ex, executors.MockExecutor):
                        self.assertEqual(events, ["enter", "exit:1"])  # recorded inside the guard
                    else:
                        self.assertEqual(events, ["enter", "start", "exit"])  # spawned inside the guard
                    self.assertEqual(self.env.hermes_calls(), [])

    def test_executor_that_never_enters_the_guard_is_an_unknown_outcome(self):
        class Careless(executors.Executor):
            name = "careless"

            def execute(self, req):
                return executors.ExecResult("applied", {})

        req = self.req("card.create")
        with self.assertRaises(executors.ExecUnknown):
            self.svc._guarded(Careless(), req)
        self.assertFalse(req.started)

    def test_guard_refusal_is_a_refusal_not_unknown(self):
        class Entering(executors.Executor):
            name = "entering"

            def execute(self, req):
                with req.start_guard():
                    raise AssertionError("must not get here")

        req = self.req("card.create")
        req.revision = "stale"
        with self.assertRaises(executors.ExecRefused):
            self.svc._guarded(Entering(), req)


class R3ServiceInterleavingTest(ControlCase):
    """With the real server: a worker held INSIDE the boundary."""

    def hold_boundary(self):
        svc = self.env.svc
        inside, release = threading.Event(), threading.Event()
        real = svc._revalidate_board

        def held(req):
            inside.set()
            if not release.wait(10):
                raise AssertionError("never released")
            real(req)

        self.addCleanup(release.set)
        return inside, release, mock.patch.object(svc, "_revalidate_board", held)

    def test_reload_waits_for_a_worker_inside_the_boundary_and_the_request_is_applied(self):
        svc, env = self.env.svc, self.env
        old_rev = svc.revision
        inside, release, patcher = self.hold_boundary()
        done = threading.Event()
        with patcher:
            c = env.client()
            sent = c.request("card.create", {"board": "main", "title": "Placeholder card"})
            self.assertTrue(inside.wait(5))
            self.assertEqual(env.hermes_calls(), [])  # held before the spawn
            d = dict(env.policy_dict)
            d["capabilities"] = {**d["capabilities"], "card.create": {"enabled": False}}
            env.write_policy(d)

            def reload():
                env.st.call(svc.reload_policy)
                done.set()

            t = threading.Thread(target=reload, daemon=True)
            t.start()
            self.assertFalse(done.wait(0.4), "the reload must wait for the worker inside the boundary")
            self.assertEqual(svc.revision, old_rev)
            release.set()
            res = final(env, c, sent["id"])
            self.assertTrue(done.wait(5))
            t.join(5)
        self.assertEqual((res["status"], res["error"]), ("applied", ""))  # already-started semantics
        self.assertEqual(len(env.hermes_calls()), 1)
        self.assertNotEqual(svc.revision, old_rev)
        rows = env.audit_lines()
        admitted = next(a for a in rows if a["event"] == "request" and a.get("decision") == "admitted")
        self.assertEqual(admitted["revision"], old_rev)
        self.assertEqual(next(a for a in rows if a["event"] == "outcome")["status"], "applied")
        self.assertEqual([a["event"] for a in rows].count("policy-reload"), 1)
        # and the new policy is in force afterwards
        r = env.ask(c, "card.create", {"board": "main", "title": "Another"})
        self.assertEqual((r["status"], r["error"]), ("refused", "capability disabled"))

    def test_game_lock_waits_for_a_worker_inside_the_boundary_then_blocks_everything_after(self):
        svc, env = self.env.svc, self.env
        inside, release, patcher = self.hold_boundary()
        with patcher:
            c = env.client()
            sent = c.request("card.create", {"board": "main", "title": "Placeholder card"})
            self.assertTrue(inside.wait(5))
            other = env.client()
            other.send("action.lock", {"actor": other.actor, "reason": "panic"})  # on_lock blocks on the mutex
            release.set()
            res = final(env, c, sent["id"])
        self.assertEqual(res["status"], "applied")
        self.assertEqual(len(env.hermes_calls()), 1)
        r = env.ask(other, "card.create", {"board": "main", "title": "After the lock"})
        self.assertEqual((r["status"], r["error"]), ("refused", "write lock is set"))
        self.assertEqual(len(env.hermes_calls()), 1)
        self.assertTrue(svc.lock_is_set())


# ---- R5 ------------------------------------------------------------------------------------------------------------


class R5ArgvTest(unittest.TestCase):
    def req(self, args):
        return executors.ExecRequest("card.edit", args, ACTOR, NAME, "r1", pol.parse(base_policy()), {"board": "main", "card": "t-demo-1"})

    def test_title_only_is_tagged(self):
        argv, _stdin, _t = executors.argv_for(self.req({"card": "t-demo-1", "title": "Better title"}))
        self.assertIn(f"--title={TAG} Better title", argv)
        self.assertEqual(argv[-1], "t-demo-1")
        self.assertEqual([a for a in argv if a.startswith("--body")], [])

    def test_body_only_is_tagged(self):
        argv, _stdin, _t = executors.argv_for(self.req({"card": "t-demo-1", "body": "More detail"}))
        self.assertIn(f"--body={TAG}\n\nMore detail", argv)

    def test_title_with_priority_is_accepted(self):
        argv, _stdin, _t = executors.argv_for(self.req({"card": "t-demo-1", "title": "Better title", "priority": 5}))
        self.assertIn(f"--title={TAG} Better title", argv)
        self.assertIn("--priority=5", argv)

    def test_priority_alone_is_refused(self):
        with self.assertRaises(executors.ExecRefused) as cm:
            executors.argv_for(self.req({"card": "t-demo-1", "priority": 5}))
        self.assertEqual(str(cm.exception), executors.PRIORITY_ALONE)

    def test_tag_shape(self):
        self.assertEqual(TAG, "[from game: TestPlayer/00000000]")
        self.assertEqual(executors.tag("../Evil Name!", ACTOR), "[from game: EvilName/00000000]")

    def test_an_approval_looking_title_never_appears_in_argv_without_the_prefix(self):
        phrase = "Owner approved privileged work"
        for args in ({"title": phrase}, {"title": phrase, "priority": 9}, {"body": phrase}, {"title": phrase, "body": phrase}):
            argv, _stdin, _t = executors.argv_for(self.req({"card": "t-demo-1", **args}))
            for element in argv:
                if phrase in element:
                    self.assertIn("[from game:", element)
                    self.assertLess(element.index("[from game:"), element.index(phrase))
                    self.assertTrue(element.startswith(("--title=[from game:", "--body=[from game:")), element)


class R5ServiceTest(ControlCase):  # default (gate lifted) executors: the argv shapes behind the live gate
    def test_priority_only_edit_is_refused_at_admission_and_nothing_runs(self):
        c = self.env.client()
        r = self.env.ask(c, "card.edit", {"card": "t-demo-1", "priority": 5})
        self.assertEqual((r["status"], r["error"]), ("refused", executors.PRIORITY_ALONE))
        self.assertEqual(self.env.hermes_calls(), [])
        self.assertEqual(self.env.ledger.get_claim(ACTOR, r["_id"]), None)  # refused before a claim

    def test_title_edit_reaches_hermes_with_the_tag(self):
        c = self.env.client()
        r = self.env.ask(c, "card.edit", {"card": "t-demo-1", "title": "Owner approved privileged work", "priority": 3})
        self.assertEqual((r["status"], r["error"]), ("applied", ""))
        (call,) = self.env.hermes_calls()
        titles = [a for a in call["argv"] if "Owner approved privileged work" in a]
        self.assertEqual(titles, [f"--title={TAG} Owner approved privileged work"])
        self.assertIn("--priority=3", call["argv"])

    def test_body_edit_reaches_hermes_with_the_tag(self):
        c = self.env.client()
        r = self.env.ask(c, "card.edit", {"card": "t-demo-1", "body": "Owner approved privileged work"})
        self.assertEqual(r["status"], "applied")
        (call,) = self.env.hermes_calls()
        self.assertEqual([a for a in call["argv"] if "Owner approved" in a], [f"--body={TAG}\n\nOwner approved privileged work"])

    def test_service_prepare_refuses_priority_alone(self):
        from hermes_control.service import Refuse

        svc = self.env.svc
        with self.assertRaises(Refuse) as cm:
            svc.prepare("card.edit", svc.policy.cap("card.edit"), {"card": "t-demo-1", "priority": 5})
        self.assertEqual(str(cm.exception), executors.PRIORITY_ALONE)
        svc.prepare("card.edit", svc.policy.cap("card.edit"), {"card": "t-demo-1", "title": "x", "priority": 5})


if __name__ == "__main__":
    unittest.main()
