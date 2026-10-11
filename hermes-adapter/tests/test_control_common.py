"""Shared test harness for the control service (not a test of its own).

Builds a throwaway environment (0700 temp dirs, key file, policy file, ledger, audit, lock, fixture
board, a fake ``hermes`` script that records its argv) and runs a real ControlService on a thread.
The real ``hermes`` is never called.
"""

from __future__ import annotations

import asyncio
import copy
import importlib.util
import json
import os
import stat
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
ADAPTER = HERE.parent
sys.path.insert(0, str(ADAPTER))

from hermes_adapter.wsclient import WSClient  # noqa: E402
from hermes_adapter.wsserver import UpgradeRequest  # noqa: E402,F401
from hermes_control import board as board_mod  # noqa: E402
from hermes_control import executors, frames, policy as pol  # noqa: E402
from hermes_control.audit import Audit  # noqa: E402
from hermes_control.ledger import Ledger  # noqa: E402
from hermes_control.lock import LockFile  # noqa: E402
from hermes_control.service import ControlService  # noqa: E402

_spec = importlib.util.spec_from_file_location("control_client", ADAPTER / "scripts" / "control_client.py")
control_client = importlib.util.module_from_spec(_spec)  # type: ignore[arg-type]
_spec.loader.exec_module(control_client)  # type: ignore[union-attr]
ControlClient = control_client.ControlClient

_opened: list = []
_orig_ws_init = WSClient.__init__


def _tracking_init(self, *a, **k):  # sockets opened by a test are closed in ControlCase.tearDown (no ResourceWarnings)
    _orig_ws_init(self, *a, **k)
    _opened.append(self)


WSClient.__init__ = _tracking_init  # type: ignore[method-assign]

ACTOR = "00000000-0000-4000-8000-000000000001"
OTHER_ACTOR = "00000000-0000-4000-8000-000000000002"
FIXTURE = ADAPTER / "hermes_control" / "fixtures" / "board.example.json"
KEY_HEX = "ab" * 8 + "cd" * 8 + "ef" * 8 + "01" * 8  # 64 hex chars, deliberately fake

FAKE_HERMES = """#!/usr/bin/env python3
import json, os, sys
log = os.environ.get("FAKE_HERMES_LOG")
stdin = ""
if "--query-file" in sys.argv or "--body-file" in sys.argv:
    stdin = sys.stdin.read()
if log:
    with open(log, "a") as f:
        f.write(json.dumps({"argv": sys.argv[1:], "stdin": stdin}) + "\\n")
mode = os.environ.get("FAKE_HERMES_MODE", "")
if mode == "fail":
    sys.stderr.write("boom")
    sys.exit(3)
if mode == "sleep":
    import time
    time.sleep(30)
args = sys.argv[1:]
if "chat" in args:
    print(os.environ.get("FAKE_HERMES_REPLY", "Hello from the fake agent."))
elif "create" in args:
    print(json.dumps({"id": "t_new1"}))
"""


def now_ms() -> int:
    return int(time.time() * 1000)


class Clock:
    """Wall clock with an adjustable offset (ms)."""

    def __init__(self) -> None:
        self.offset = 0

    def __call__(self) -> int:
        return int(time.time() * 1000) + self.offset


def base_policy(env: "Env | None" = None, **over: Any) -> dict[str, Any]:
    prog = [str(env.fake)] if env else ["hermes"]
    svc_argv = [str(env.fake), "restart-service-1"] if env else ["true"]
    d: dict[str, Any] = {
        "schema": 1,
        "actors": [ACTOR],
        "lockSetsHermesLock": True,
        "hermesProgram": prog,
        "capabilities": {
            "decision.answer": {"enabled": True, "boards": ["main"], "permissionApprove": False, "handoffAnswerable": False},
            "card.create": {"enabled": True, "boards": ["main"]},
            "card.edit": {"enabled": True, "boards": ["main"]},
            "card.dispatch": {"enabled": True, "boards": ["main"], "profiles": ["builder-a"]},
            "agent.chat": {"enabled": True, "agents": ["helper-a"], "toolsets": ["search"], "timeoutSeconds": 20},
            "agent.ask": {"enabled": True, "agents": ["helper-a"], "toolsets": ["search"], "timeoutSeconds": 20},
            "service.restart": {"enabled": True},
            "cron.run": {"enabled": True},
        },
        "services": {"service-1": {"argv": svc_argv, "timeoutSeconds": 20}},
        "jobs": ["job-a1"],
    }
    d.update(over)
    return d


class ServerThread:
    def __init__(self, svc: ControlService) -> None:
        self.svc = svc
        self.loop = asyncio.new_event_loop()
        self.ready = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        self.ready.wait(10)

    def _run(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_until_complete(self.svc.start())
        self.ready.set()
        self.loop.run_forever()

    def call(self, coro_fn, *a):
        async def go():
            return coro_fn(*a) if not asyncio.iscoroutinefunction(coro_fn) else await coro_fn(*a)

        return asyncio.run_coroutine_threadsafe(go(), self.loop).result(10)

    def stop(self) -> None:
        try:
            asyncio.run_coroutine_threadsafe(self.svc.stop(), self.loop).result(10)
        except Exception:  # noqa: BLE001
            pass
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(5)
        if not self.thread.is_alive():
            self.loop.close()


class _GateLifted:
    """TEST ONLY mixin. Stands in for a future Hermes that offers conditional board changes and a verified
    chat tool boundary, so the request flows behind the live gate (argv shape, decisions, confirm tokens,
    chat framing, limits) stay tested with the fake ``hermes``. Production never uses it: the gate itself
    is tested with :func:`executors.build` in test_control_review_r1.py."""

    def unavailable(self, capability: str, args: dict[str, Any]) -> str | None:
        return None

    def unavailable_caps(self) -> frozenset[str]:
        return frozenset()


class GateLiftedBoardExecutor(_GateLifted, executors.BoardExecutor):
    pass


class GateLiftedChatExecutor(_GateLifted, executors.ChatExecutor):
    pass


def gate_lifted_executors() -> dict[str, executors.Executor]:
    b, ch = GateLiftedBoardExecutor(), GateLiftedChatExecutor()
    m = executors.build(False)
    for cap in m:
        if isinstance(m[cap], executors.BoardExecutor):
            m[cap] = b
        elif isinstance(m[cap], executors.ChatExecutor):
            m[cap] = ch
    return m


class Env:
    """One throwaway control environment. ``start()`` runs the service; ``restart()`` simulates a new process.

    ``production_executors``: False (default for the flow tests) lifts the live gate with the TEST ONLY
    executors above; True uses exactly what the CLI builds (``executors.build``).
    """

    def __init__(self, policy: dict[str, Any] | None = None, dry_run: bool = False, board: Any = None, clock: Clock | None = None,
                 audit_cls: type[Audit] = Audit, production_executors: bool = False) -> None:
        self.production_executors = production_executors
        self._tmp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.root = Path(self._tmp.name)
        os.chmod(self.root, 0o700)
        self.cfg = self.root / "cfg"
        self.cfg.mkdir(mode=0o700)
        self.state = self.root / "state"
        self.state.mkdir(mode=0o700)
        self.fake = self.root / "fake-hermes"
        self.fake.write_text(FAKE_HERMES)
        os.chmod(self.fake, 0o700)
        self.log = self.root / "hermes-argv.log"
        os.environ["FAKE_HERMES_LOG"] = str(self.log)
        os.environ.pop("FAKE_HERMES_MODE", None)
        os.environ.pop("FAKE_HERMES_REPLY", None)
        self.key_path = self.cfg / "key"
        self.key_path.write_text("# test key, not a secret\n" + KEY_HEX + "\n")
        os.chmod(self.key_path, 0o600)
        self.key = bytes.fromhex(KEY_HEX)
        self.policy_path = self.cfg / "policy.json"
        self.policy_dict = policy if policy is not None else base_policy(self)
        self.write_policy(self.policy_dict)
        self.audit_path = self.cfg / "audit.jsonl"
        self.lock_path = self.state / "hermes.lock"
        self.clock = clock or Clock()
        self.dry_run = dry_run
        self.board = board if board is not None else board_mod.FixtureBoardReader(FIXTURE)
        self.audit_cls = audit_cls
        self.st: ServerThread | None = None
        self.svc: ControlService
        self.ledger: Ledger
        self.audit: Audit

    # ---- files ---------------------------------------------------------------------------------
    def write_policy(self, d: dict[str, Any]) -> None:
        self.policy_path.write_text(json.dumps(d, indent=1))
        os.chmod(self.policy_path, 0o600)

    def hermes_calls(self) -> list[dict[str, Any]]:
        if not self.log.exists():
            return []
        return [json.loads(x) for x in self.log.read_text().splitlines() if x.strip()]

    def audit_lines(self) -> list[dict[str, Any]]:
        if not self.audit_path.exists():
            return []
        return [json.loads(x) for x in self.audit_path.read_text().splitlines() if x.strip()]

    # ---- service ---------------------------------------------------------------------------------
    def build(self) -> ControlService:
        self.ledger = Ledger(self.state / "ledger.sqlite3", self.clock, allow_in_repo=True)
        self.audit = self.audit_cls(self.audit_path, self.clock)
        exmap = executors.build(self.dry_run) if (self.dry_run or self.production_executors) else gate_lifted_executors()
        self.svc = ControlService(
            key=self.key, policy=pol.load(self.policy_path), policy_path=self.policy_path, ledger=self.ledger, audit=self.audit,
            lock=LockFile(self.lock_path), reader=self.board, executor_map=exmap, clock=self.clock,
            dry_run=self.dry_run, host="127.0.0.1", port=0,
        )
        return self.svc

    def start(self) -> "Env":
        self.build()
        self.st = ServerThread(self.svc)
        self.port = self.svc.port
        return self

    def stop(self) -> None:
        if self.st:
            self.st.stop()
            self.st = None
        try:
            self.ledger.close()
        except Exception:  # noqa: BLE001
            pass
        try:
            self.audit.close()
        except Exception:  # noqa: BLE001
            pass

    def restart(self) -> "Env":
        self.stop()
        time.sleep(0.01)
        return self.start()

    def close(self) -> None:
        self.stop()
        self._tmp.cleanup()

    def client(self, handshake: bool = True, **kw: Any) -> ControlClient:
        c = ControlClient("127.0.0.1", self.port, self.key, ACTOR, "TestPlayer", quiet=True, **kw)
        if handshake:
            c.handshake()
        return c

    def result_for(self, c: ControlClient, rid: str, timeout: float = 5.0) -> dict[str, Any]:
        """Read frames until the action.result for ``rid`` arrives and return its payload."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            t, p = c.recv(max(0.1, deadline - time.time()))
            if t == "action.result" and p["re"] == rid:
                return p
        raise AssertionError("no result")

    def prompt(self, c: ControlClient, args: dict[str, Any], **kw: Any) -> tuple[str, dict[str, Any] | None, dict[str, Any]]:
        """Send a card.dispatch request; return (request id, the action.prompt payload or None, the action.result payload)."""
        sent = c.request("card.dispatch", args, **kw)
        prompt = None
        deadline = time.time() + 5
        while time.time() < deadline:
            t, p = c.recv(max(0.1, deadline - time.time()))
            if t == "action.prompt" and p["re"] == sent["id"]:
                prompt = p
            if t == "action.result" and p["re"] == sent["id"]:
                return sent["id"], prompt, p
        raise AssertionError("no result")

    def ask(self, c: ControlClient, cap: str, args: dict[str, Any], wait_final: bool = True, **kw: Any) -> dict[str, Any]:
        sent = c.request(cap, args, **kw)
        res = self.result_for(c, sent["id"])
        while wait_final and res["status"] == "queued" and res["error"] == "":
            res = self.result_for(c, sent["id"])
        res["_id"] = sent["id"]
        return res


class ControlCase(unittest.TestCase):
    """Base class: a fresh Env per test (override ``policy()`` / ``dry_run``)."""

    dry_run = False
    production_executors = False
    autostart = True

    def policy(self, env: Env) -> dict[str, Any]:
        return base_policy(env)

    def board(self, env: Env) -> Any:
        """Override to serve another board (a FixtureBoardReader)."""
        return None

    def setUp(self) -> None:
        self.env = Env(dry_run=self.dry_run, production_executors=self.production_executors)
        b = self.board(self.env)
        if b is not None:
            self.env.board = b
        self.env.policy_dict = self.policy(self.env)
        self.env.write_policy(self.env.policy_dict)
        if self.autostart:
            self.env.start()
        self.addCleanup(self.env.close)

    def tearDown(self) -> None:
        for c in list(_opened):
            try:
                c.sock.close()
            except Exception:  # noqa: BLE001
                pass
        _opened.clear()


class HarnessSmokeTest(ControlCase):
    def test_handshake_and_state(self):
        c = self.env.client(handshake=False)
        got = c.handshake()
        self.assertEqual([t for t, _ in got], ["action.challenge", "ack", "action.policy", "action.state"])
        self.assertTrue(got[3][1]["armed"])
        self.assertFalse(got[2][1]["dryRun"])
        c.close()


if __name__ == "__main__":
    unittest.main()
