import asyncio
import ipaddress
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path

from hermes_adapter.mapping import Mapper
from hermes_adapter.server import AccessPolicy, AdapterServer
from hermes_adapter.sources import HermesSource
from hermes_adapter.wsclient import WSClient, WSError

from fixture import standard


class ServerThread:
    """Runs an AdapterServer on its own event loop thread (port 0)."""

    def __init__(self, server: AdapterServer) -> None:
        self.server = server
        self.loop = asyncio.new_event_loop()
        self.ready = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        self.ready.wait(10)

    def _run(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_until_complete(self.server.start())
        self.ready.set()
        self.loop.run_forever()

    def stop(self) -> None:
        fut = asyncio.run_coroutine_threadsafe(self.server.stop(), self.loop)
        fut.result(5)
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(5)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.f = standard(Path(self.tmp.name), int(time.time()))
        src = HermesSource(self.f.home)
        self.srv = AdapterServer(src.read, Mapper(), host="127.0.0.1", port=0, poll_interval=0.2, ping_interval=0.5)
        self.st = ServerThread(self.srv)
        self.port = self.srv.port

    def tearDown(self):
        self.st.stop()
        self.tmp.cleanup()

    def client(self, **kw) -> WSClient:
        return WSClient("127.0.0.1", self.port, **kw)

    def recv_until(self, c: WSClient, pred, timeout=5.0):
        end = time.time() + timeout
        while time.time() < end:
            m = c.recv(timeout=max(0.1, end - time.time()))
            if pred(m):
                return m
        raise AssertionError("timed out")

    def test_hello_snapshot(self):
        c = self.client()
        c.send({"type": "hello", "modVersion": "test", "protocol": 1, "client": "cli", "id": "h1"})
        snap = c.recv()
        self.assertEqual(snap["type"], "snapshot")
        self.assertEqual(snap["v"], 1)
        for k in ("foreman", "agents", "tasks", "decisions", "repos", "memory", "goals", "feed", "logs"):
            self.assertIn(k, snap)
        self.assertNotIn("goal", snap)
        ack = c.recv()
        self.assertEqual(ack, {"v": 1, "type": "ack", "re": "h1", "ok": True})
        c.close()

    def test_live_upsert_when_hermes_changes(self):
        c = self.client()
        c.send({"type": "hello", "modVersion": "test", "protocol": 1})
        c.recv()
        self.f.comment("t_build", "claude-builder", "PROGRESS: now testing the bridge", at=int(time.time()))
        m = self.recv_until(c, lambda m: m["type"] == "agent.upsert" and m["agent"]["id"] == "claude-builder")
        self.assertEqual(m["agent"]["activity"], "now testing the bridge")
        self.recv_until(c, lambda m: m["type"] == "agent.log" and m["agentId"] == "claude-builder")
        c.close()

    def test_incremental_upserts_never_carry_canaries(self):
        """Review r1: the live path (agent.upsert / task.upsert / agent.log) is filtered like the snapshot."""
        c = self.client()
        c.send({"type": "hello", "modVersion": "test", "protocol": 1})
        c.recv()
        canary_note = "CANARY" + "_PRIVATE_NOTE_LINE"
        canary_secret = "SYNTHETIC" + "_SECRET_CANARY"
        now = int(time.time())
        self.f.comment("t_build", "claude-builder", f"PROGRESS: {canary_note}\nSource: personal-" + "schedule.md", at=now)
        self.f.sql("UPDATE tasks SET body=? WHERE id='t_build'", (f"deploy --password {canary_secret} now",))
        seen: list[dict] = []
        got = {"agent": None, "task": None, "log": False}
        end = time.time() + 6
        while time.time() < end and not (got["agent"] and got["task"] and got["log"]):
            try:
                m = c.recv(timeout=max(0.1, end - time.time()))
            except Exception:
                break
            seen.append(m)
            if m["type"] == "agent.upsert" and m["agent"]["id"] == "claude-builder":
                got["agent"] = m["agent"]
            elif m["type"] == "task.upsert" and m["task"]["id"] == "t_build":
                got["task"] = m["task"]
            elif m["type"] == "agent.log" and m["agentId"] == "claude-builder":
                got["log"] = True
        c.close()
        blob = json.dumps(seen)
        self.assertNotIn("CANARY", blob)
        self.assertNotIn("SYNTHETIC", blob)
        self.assertIsNotNone(got["agent"], "agent.upsert arrived")
        self.assertEqual(got["agent"]["activity"], "[withheld: mentions personal notes]")
        self.assertIsNotNone(got["task"], "task.upsert arrived")
        self.assertIn("--password [redacted]", got["task"]["description"])

    def test_mutations_are_refused(self):
        c = self.client()
        c.send({"type": "hello", "modVersion": "test", "protocol": 1})
        c.recv()
        for i, msg in enumerate([
            {"type": "goal.submit", "text": "do things"},
            {"type": "user.message", "to": "all", "text": "hi"},
            {"type": "decision.answer", "decisionId": "d1", "option": "Yes"},
            {"type": "task.action", "taskId": "t_build", "action": "cancel"},
            {"type": "agent.action", "agentId": "claude-builder", "action": "stop"},
            {"type": "repo.add", "path": "/tmp"},
        ]):
            c.send({**msg, "id": f"m{i}"})
            ack = self.recv_until(c, lambda m: m["type"] == "ack")
            self.assertEqual(ack["re"], f"m{i}")
            self.assertFalse(ack["ok"])
            self.assertIn("read-only", ack["error"])
        c.send({"type": "goal.submit", "text": "no id"})
        err = self.recv_until(c, lambda m: m["type"] == "error")
        self.assertIn("read-only", err["message"])
        c.send({"type": "diff.request", "requestId": "r1", "repoId": "x", "worktree": "y", "id": "dr"})
        d = self.recv_until(c, lambda m: m["type"] == "diff")
        self.assertEqual(d["files"], [])
        self.assertIn("error", d)
        c.close()
        # the board is untouched
        import sqlite3
        conn = sqlite3.connect(self.f.db_path)
        self.assertEqual(conn.execute("SELECT status FROM tasks WHERE id='t_build'").fetchone()[0], "running")
        conn.close()

    def test_bad_messages(self):
        c = self.client()
        c.send_frame(0x1, b"not json")
        self.assertEqual(c.recv()["type"], "error")
        c.send({"type": "nope", "id": "x1"})
        self.assertEqual(self.recv_until(c, lambda m: m["type"] == "ack")["ok"], False)
        c.close()

    def test_origin_and_host_rejected(self):
        with self.assertRaises(WSError) as e:
            self.client(extra_headers={"Origin": "null"})
        self.assertIn("401", str(e.exception))
        with self.assertRaises(WSError) as e:
            self.client(extra_headers={"Origin": "https://evil.example"})
        self.assertIn("401", str(e.exception))
        with self.assertRaises(WSError) as e:
            self.client(host_header="evil.example:7878")
        self.assertIn("401", str(e.exception))
        self.client(host_header="localhost:7878").close()


class PolicyTest(unittest.TestCase):
    def test_peer_allowlist(self):
        p = AccessPolicy()
        self.assertTrue(p.peer_ok("127.0.0.1"))
        self.assertTrue(p.peer_ok("::ffff:127.0.0.1"))
        self.assertFalse(p.peer_ok("192.0.2.20"))
        p.allowed_peers.append(ipaddress.ip_network("192.0.2.20/32"))
        self.assertTrue(p.peer_ok("192.0.2.20"))
        self.assertFalse(p.peer_ok("192.0.2.50"))

    def test_host_only(self):
        self.assertEqual(AccessPolicy.host_only("192.0.2.10:7878"), "192.0.2.10")
        self.assertEqual(AccessPolicy.host_only("[::1]:7878"), "[::1]")
        self.assertEqual(AccessPolicy.host_only("LOCALHOST"), "localhost")

    def test_wildcard_bind_refused(self):
        from hermes_adapter.__main__ import build_policy, parse_args
        with self.assertRaises(SystemExit):
            build_policy(parse_args(["--bind", "0.0.0.0"]))
        pol = build_policy(parse_args(["--bind", "192.0.2.10", "--allow-peer", "192.0.2.20"]))
        self.assertIn("192.0.2.10", pol.allowed_hosts)
        self.assertTrue(pol.peer_ok("192.0.2.20"))
        self.assertFalse(pol.peer_ok("192.0.2.99"))


if __name__ == "__main__":
    unittest.main()
