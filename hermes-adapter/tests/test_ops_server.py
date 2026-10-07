"""Card 5a: the ops.* extension over a real WebSocket (opt-in, snapshot, incremental, privacy)."""

import json
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import unittest
from pathlib import Path

from hermes_adapter.mapping import Mapper
from hermes_adapter.ops import MAX, OpsHub
from hermes_adapter.ops_mock import MockOpsSource
from hermes_adapter.ops_schema import validate
from hermes_adapter.server import AdapterServer
from hermes_adapter.sources import HermesSource
from hermes_adapter.wsclient import WSClient

from fixture import standard
from test_server import ServerThread

ADAPTER_DIR = Path(__file__).resolve().parent.parent
# canaries assembled at runtime so no source line carries a real-looking secret or LAN address
LAN_IP = "192." + "168." + "77.5"
TOKEN = "sk-" + "ant-api03-" + "CanaryCanaryCanary0123456789"


class LiveSource:
    """A source whose data the test edits between collects (thread-safe enough for tests)."""

    def __init__(self, sid="live", interval=1.0):
        self.id = sid
        self.name = f"{sid} feed"
        self.interval = interval
        self.timeout = 2.0
        self.lock = threading.Lock()
        self.data = {
            "services": [{"id": "s1", "name": "service-1", "group": "services", "state": "up"},
                         {"id": "s2", "name": "service-2", "group": "services", "state": "up",
                          "detail": f"probe http://{LAN_IP}:8080/health ok"}],
            "jobs": [{"id": "j1", "name": "job-1", "schedule": "every 5m", "lastStatus": "ok"}],
            "usage": [{"provider": "provider-a", "window": "week", "remainingPct": 80}],
            "alerts": [{"id": "a1", "ts": 1_791_000_000, "severity": "warn", "title": "disk 91% on host-b",
                        "detail": f"token {TOKEN} seen in log"}],
        }
        self.fail = None

    def collect(self):
        with self.lock:
            if self.fail:
                raise self.fail
            return json.loads(json.dumps(self.data))

    def edit(self, fn):
        with self.lock:
            fn(self.data)


class OpsServerTest(unittest.TestCase):
    def start(self, ops):
        self.tmp = tempfile.TemporaryDirectory()
        f = standard(Path(self.tmp.name), int(time.time()))
        self.srv = AdapterServer(HermesSource(f.home).read, Mapper(), host="127.0.0.1", port=0,
                                 poll_interval=0.2, ping_interval=5, ops=ops, ops_tick=0.05)
        self.st = ServerThread(self.srv)
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(self.st.stop)

    def client(self) -> WSClient:
        c = WSClient("127.0.0.1", self.srv.port)
        self.addCleanup(c.close)
        return c

    def recv_until(self, c, pred, timeout=5.0):
        end = time.time() + timeout
        seen = []
        while time.time() < end:
            try:
                m = c.recv(timeout=max(0.05, end - time.time()))
            except OSError:
                break
            seen.append(m["type"])
            if m["type"].startswith("ops."):  # everything on the wire must satisfy the spec
                self.assertEqual(validate(m, strict=True), [], m)
            if pred(m):
                return m
        raise AssertionError(f"timed out; saw {seen}")

    def wait_collected(self, sid="live", timeout=5.0):
        end = time.time() + timeout
        while time.time() < end:
            m = self.srv.ops_model
            if m and any(s["id"] == sid and s["state"] in ("ok", "warn") for s in m["sources"]):
                return
            time.sleep(0.02)
        raise AssertionError("source never collected")

    def hello(self, c, features=None, mid="h1"):
        msg = {"type": "hello", "modVersion": "test", "protocol": 1, "client": "cli", "id": mid}
        if features is not None:
            msg["features"] = features
        c.send(msg)

    # ---- opt-in ---------------------------------------------------------------------------
    def test_opt_in_order_snapshot_ops_snapshot_ack(self):
        src = LiveSource()
        self.start(OpsHub([src]))
        self.wait_collected()
        c = self.client()
        self.hello(c, ["ops", "unknown-feature", 7])
        first, second, third = c.recv(), c.recv(), c.recv()
        self.assertEqual(first["type"], "snapshot")
        self.assertEqual(second["type"], "ops.snapshot")
        self.assertEqual(third, {"v": 1, "type": "ack", "re": "h1", "ok": True, "result": {"features": ["ops"]}})
        self.assertEqual(second["v"], 1)
        self.assertEqual([s["id"] for s in second["services"]], ["live/s1", "live/s2"])
        self.assertEqual(second["sources"][0]["state"], "ok")
        self.assertEqual(second["limits"]["services"], MAX["service"])
        for k in ("jobs", "usage", "alerts"):
            self.assertEqual(len(second[k]), 1)

    def test_without_features_no_ops_messages_ever(self):
        src = LiveSource()
        self.start(OpsHub([src]))
        plain, ops_c = self.client(), self.client()
        self.hello(plain)  # upstream-style hello: no features
        self.assertEqual(plain.recv()["type"], "snapshot")
        self.assertEqual(plain.recv(), {"v": 1, "type": "ack", "re": "h1", "ok": True})  # unchanged
        self.hello(ops_c, ["ops"], mid=None)
        self.recv_until(ops_c, lambda m: m["type"] == "ops.snapshot")
        self.wait_collected()
        src.edit(lambda d: d["services"][0].update(state="down", detail="no answer"))
        self.recv_until(ops_c, lambda m: m["type"] == "ops.service.upsert" and m["service"]["state"] == "down")
        # the plain client got nothing ops-shaped in the meantime (only base protocol, if anything)
        plain.sock.settimeout(0.6)
        got = []
        try:
            while True:
                got.append(plain.recv(timeout=0.6)["type"])
        except OSError:
            pass
        self.assertFalse([t for t in got if t.startswith("ops.")], got)

    def test_rehello_without_ops_opts_out(self):
        src = LiveSource()
        self.start(OpsHub([src]))
        self.wait_collected()
        c = self.client()
        self.hello(c, ["ops"], mid=None)
        self.recv_until(c, lambda m: m["type"] == "ops.snapshot")
        self.hello(c, [], mid="h2")
        self.assertEqual(self.recv_until(c, lambda m: m["type"] == "ack")["result"], {"features": []})
        self.assertNotIn(next(iter(self.srv.clients)), self.srv.ops_clients)

    def test_no_sources_configured_gives_empty_snapshot(self):
        self.start(None)
        c = self.client()
        self.hello(c, ["ops"])
        self.assertEqual(c.recv()["type"], "snapshot")
        snap = c.recv()
        self.assertEqual(snap["type"], "ops.snapshot")
        self.assertEqual((snap["services"], snap["jobs"], snap["usage"], snap["alerts"], snap["sources"]), ([], [], [], [], []))
        self.assertEqual(c.recv()["result"], {"features": ["ops"]})

    # ---- incremental ------------------------------------------------------------------------
    def test_incremental_upsert_and_remove_over_the_wire(self):
        src = LiveSource()
        self.start(OpsHub([src]))
        self.wait_collected()
        c = self.client()
        self.hello(c, ["ops"], mid=None)
        self.recv_until(c, lambda m: m["type"] == "ops.snapshot")

        def change(d):
            d["services"] = [d["services"][0]]
            d["alerts"][0]["state"] = "resolved"
            d["jobs"].append({"id": "j2", "name": "job-2", "lastStatus": "failed", "detail": f"connect {LAN_IP}:443 refused"})
        src.edit(change)
        rem = self.recv_until(c, lambda m: m["type"] == "ops.remove")
        self.assertEqual(rem, {"v": 1, "type": "ops.remove", "kind": "service", "id": "live/s2"})
        job = self.recv_until(c, lambda m: m["type"] == "ops.job.upsert")["job"]
        self.assertEqual((job["id"], job["lastStatus"], job["detail"]), ("live/j2", "failed", "connect [ip]:443 refused"))
        al = self.recv_until(c, lambda m: m["type"] == "ops.alert.upsert")["alert"]
        self.assertEqual(al["state"], "resolved")

    def test_source_error_status_reaches_client_filtered(self):
        src = LiveSource()
        self.start(OpsHub([src]))
        self.wait_collected()
        c = self.client()
        self.hello(c, ["ops"], mid=None)
        self.recv_until(c, lambda m: m["type"] == "ops.snapshot")
        src.fail = RuntimeError(f"GET https://bob:hunter2@{LAN_IP}/api failed: {TOKEN}")
        st = self.recv_until(c, lambda m: m["type"] == "ops.source.upsert" and m["source"]["state"] == "error")["source"]
        blob = json.dumps(st)
        for canary in (LAN_IP, "hunter2", TOKEN):
            self.assertNotIn(canary, blob)
        self.assertIn("RuntimeError", st["detail"])

    def test_slow_source_does_not_block_a_fast_one(self):
        gate = threading.Event()

        class Slow(LiveSource):
            def collect(self):
                gate.wait(3)
                return super().collect()

        slow, fast = Slow("slow", interval=60), LiveSource("fast", interval=1)
        self.start(OpsHub([slow, fast]))
        try:
            self.wait_collected("fast", timeout=2.0)  # while "slow" is still inside collect()
            states = {s["id"]: s["state"] for s in self.srv.ops_model["sources"]}
            self.assertEqual(states["slow"], "starting")
        finally:
            gate.set()
        self.wait_collected("slow", timeout=5.0)

    # ---- privacy and bounds over the wire ---------------------------------------------------
    def test_wire_carries_no_canaries(self):
        src = LiveSource()
        self.start(OpsHub([src, MockOpsSource(interval=1.0)]))
        self.wait_collected()
        self.wait_collected("mock")
        c = self.client()
        self.hello(c, ["ops"], mid=None)
        snap = self.recv_until(c, lambda m: m["type"] == "ops.snapshot")
        blob = json.dumps(snap)
        for canary in (LAN_IP, TOKEN, "192.0.2.", "pa55word", "abc123"):
            self.assertNotIn(canary, blob, canary)
        self.assertIn("[ip]", blob)
        self.assertIn("[redacted]", blob)

    def test_caps_hold_over_the_wire(self):
        src = LiveSource()
        src.data["services"] = [{"id": f"s{i}", "name": f"service-{i}", "state": "up"} for i in range(MAX["service"] + 50)]
        self.start(OpsHub([src]))
        self.wait_collected()
        c = self.client()
        self.hello(c, ["ops"], mid=None)
        snap = self.recv_until(c, lambda m: m["type"] == "ops.snapshot")
        self.assertEqual(len(snap["services"]), MAX["service"])
        self.assertEqual(snap["sources"][0]["counts"]["services"], MAX["service"] + 50)


PLUGIN = textwrap.dedent('''
    class Src:
        id = "cli"
        interval = 30
        def __init__(self, config):
            self.where = config.get("where", "?")
        def collect(self):
            return {"services": [{"id": "x", "name": "x", "state": "up", "detail": "at " + self.where}]}

    def create_sources(config):
        return [Src(config)]
''')


class CliTest(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, "-m", "hermes_adapter", *args], cwd=ADAPTER_DIR,
                              capture_output=True, text=True, timeout=60)

    def test_ops_once_mock_redacts_by_default(self):
        r = self.run_cli("--ops-mock", "--ops-once")
        self.assertEqual(r.returncode, 0, r.stderr)
        snap = json.loads(r.stdout)
        self.assertEqual(snap["type"], "ops.snapshot")
        self.assertEqual({len(snap[k]) > 0 for k in ("services", "jobs", "usage", "alerts")}, {True})
        self.assertNotIn("192.0.2.", r.stdout)
        self.assertIn("[ip]:443", r.stdout)

    def test_allow_ip_text(self):
        r = self.run_cli("--ops-mock", "--ops-once", "--allow-ip-text")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("192.0.2.15:443", r.stdout)
        self.assertNotIn("pa55word", r.stdout)  # credentials stay redacted

    def test_plugin_and_config(self):
        with tempfile.TemporaryDirectory() as d:
            plug = Path(d) / "plug.py"
            plug.write_text(PLUGIN)
            cfg = Path(d) / "cfg.json"
            cfg.write_text(json.dumps({"where": "198.51.100.7 rack 2"}))
            r = self.run_cli("--ops-plugin", str(plug), "--ops-config", str(cfg), "--ops-once")
            self.assertEqual(r.returncode, 0, r.stderr)
            snap = json.loads(r.stdout)
            self.assertEqual(snap["services"][0]["detail"], "at [ip] rack 2")
            self.assertEqual(snap["sources"][0]["id"], "cli")
            cfg.write_text("[1, 2]")
            r = self.run_cli("--ops-plugin", str(plug), "--ops-config", str(cfg), "--ops-once")
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("expected a JSON object", r.stderr)

    def test_bad_usage(self):
        r = self.run_cli("--ops-once")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("needs --ops-mock or --ops-plugin", r.stderr)
        r = self.run_cli("--ops-plugin", "/nonexistent/plugin.py", "--ops-once")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("--ops-plugin", r.stderr)
        with tempfile.NamedTemporaryFile("w", suffix=".json") as f:
            f.write("{}")
            f.flush()
            r = self.run_cli("--ops-config", f.name, "--ops-once")
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("needs at least one --ops-plugin", r.stderr)


if __name__ == "__main__":
    unittest.main()
