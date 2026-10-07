"""ops.* (card 5a) and world.* (card G1) side by side: one adapter, two independent opt-ins, one
shared plugin loader with separate entry-point groups."""

import contextlib
import io
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from hermes_adapter import __main__ as cli
from hermes_adapter import ops
from hermes_adapter.mapping import Mapper
from hermes_adapter.ops import OpsHub
from hermes_adapter.server import FEATURES, AdapterServer
from hermes_adapter.sources import HermesSource
from hermes_adapter.sources import plugin as world_plugin
from hermes_adapter.world_hub import WorldHub
from hermes_adapter.wsclient import WSClient

from factory_fixture import capture, machine
from fixture import standard
from test_ops_server import LiveSource
from test_server import ServerThread
from test_world_server import SteppedSource

WORLD_PLUGIN = (
    "class S:\n"
    "    id = 'custom'\n"
    "    interval = 10\n"
    "    def collect(self):\n"
    "        return {'health': {'protocol': 'ai-factory/v2', 'lastCaptureMillis': None}}\n"
    "def create_sources(config):\n"
    "    return [S()]\n"
)


class BothFeaturesServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        f = standard(Path(self.tmp.name), int(time.time()))
        src = SteppedSource([capture(seq=1), capture(seq=1),
                             capture([machine(1, "idle"), machine(2, "idle"), machine(3, "maintenance")], seq=2)])
        src.interval = 0.6
        self.live = LiveSource(interval=0.3)
        self.srv = AdapterServer(HermesSource(f.home).read, Mapper(), host="127.0.0.1", port=0, poll_interval=0.5,
                                 ping_interval=5, ops=OpsHub([self.live]), ops_tick=0.05,
                                 world=WorldHub(src), world_tick=0.05)
        self.st = ServerThread(self.srv)
        self.addCleanup(self.st.stop)

    def client(self):
        c = WSClient("127.0.0.1", self.srv.port)
        self.addCleanup(c.close)
        return c

    def until(self, c, pred, timeout=8.0):
        seen = []
        end = time.time() + timeout
        while time.time() < end:
            try:
                m = c.recv(timeout=max(0.05, end - time.time()))
            except OSError:
                break
            seen.append(m)
            if pred(m):
                return seen
        raise AssertionError(f"timed out; saw {[m['type'] for m in seen]}")

    def drain(self, c, seconds):
        out = []
        end = time.time() + seconds
        while time.time() < end:
            try:
                out.append(c.recv(timeout=max(0.05, end - time.time())))
            except OSError:
                break
        return out

    def test_both_features_known(self):
        self.assertEqual(set(FEATURES), {"ops", "world"})

    def test_one_snapshot_then_ops_then_world_then_ack(self):
        c = self.client()
        c.send({"type": "hello", "id": "h1", "protocol": 1, "features": ["world", "ops"]})
        msgs = [c.recv(), c.recv(), c.recv(), c.recv()]
        self.assertEqual([m["type"] for m in msgs], ["snapshot", "ops.snapshot", "world.snapshot", "ack"])
        self.assertEqual(msgs[3]["result"], {"features": ["ops", "world"]})
        # both streams then reach the same connection
        seen = self.until(c, lambda m: m["type"] == "world.event")
        self.live.edit(lambda d: d["services"][0].update(state="down"))
        seen += self.until(c, lambda m: m["type"].startswith("ops.") and m["type"] != "ops.snapshot"
                           and "service" in json.dumps(m) and "down" in json.dumps(m))
        self.assertEqual([m["type"] for m in seen].count("snapshot"), 0, "the base snapshot is sent once")

    def test_each_opt_in_is_independent(self):
        w, o, plain = self.client(), self.client(), self.client()
        w.send({"type": "hello", "id": "w", "protocol": 1, "features": ["world"]})
        o.send({"type": "hello", "id": "o", "protocol": 1, "features": ["ops"]})
        plain.send({"type": "hello", "id": "p", "protocol": 1})
        self.until(w, lambda m: m["type"] == "world.event")
        self.live.edit(lambda d: d["jobs"][0].update(lastStatus="failed"))
        time.sleep(1.0)
        w_types = {m["type"].split(".")[0] for m in self.drain(w, 0.8)}
        o_types = [m["type"] for m in self.drain(o, 0.8)]
        p_types = [m["type"] for m in self.drain(plain, 0.8)]
        self.assertNotIn("ops", w_types)
        self.assertTrue(any(t.startswith("ops.") for t in o_types))
        self.assertFalse(any(t.startswith("world.") for t in o_types))
        self.assertFalse(any(t.startswith(("ops.", "world.")) for t in p_types))

    def test_rehello_keeps_one_feature_and_drops_the_other(self):
        c = self.client()
        c.send({"type": "hello", "id": "h1", "protocol": 1, "features": ["ops", "world"]})
        self.until(c, lambda m: m["type"] == "ack" and m["re"] == "h1")
        c.send({"type": "hello", "id": "h2", "protocol": 1, "features": ["ops"]})
        seen = self.until(c, lambda m: m["type"] == "ack" and m["re"] == "h2")
        self.assertEqual([m["type"] for m in seen], ["snapshot", "ops.snapshot", "ack"])
        self.assertEqual((len(self.srv.ops_clients), len(self.srv.world_clients)), (1, 0))


class SharedLoaderTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "wplug.py").write_text(WORLD_PLUGIN)

    def test_world_loader_is_the_ops_loader_with_its_own_group(self):
        spec = str(self.root / "wplug.py")
        self.assertEqual(world_plugin.load_plugin(spec)[0].id, "custom")
        module = ops._load_module_from_path(self.root / "wplug.py", "world")

        class EP:
            name = "mine"

            def load(self):
                return module

        with mock.patch("importlib.metadata.entry_points", return_value=[EP()]) as eps:
            self.assertEqual(world_plugin.load_plugin("ep:mine")[0].id, "custom")
            eps.assert_called_with(group=world_plugin.ENTRY_POINT_GROUP)
            ops.load_plugin("ep:mine")
            eps.assert_called_with(group=ops.ENTRY_POINT_GROUP)
        self.assertNotEqual(world_plugin.ENTRY_POINT_GROUP, ops.ENTRY_POINT_GROUP)

    def test_errors_name_the_plugin_kind(self):
        (self.root / "nofactory.py").write_text("x = 1\n")
        with self.assertRaisesRegex(ValueError, "^world plugin"):
            world_plugin.load_plugin(str(self.root / "nofactory.py"))
        with self.assertRaisesRegex(ValueError, "^ops plugin"):
            ops.load_plugin(str(self.root / "nofactory.py"))

    def test_same_file_loads_as_separate_modules_per_kind(self):
        a = ops._load_module_from_path(self.root / "wplug.py", "world")
        b = ops._load_module_from_path(self.root / "wplug.py")
        self.assertIsNot(a, b)
        self.assertTrue(a.__name__.startswith("_agentcraft_world_plugin_"))
        self.assertTrue(b.__name__.startswith("_agentcraft_ops_plugin_"))


class CliTest(unittest.TestCase):
    def run_cli(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = cli.main(argv)
            except SystemExit as e:
                code = e.code
        return code, out.getvalue()

    def test_ops_and_world_flags_together(self):
        root = Path(tempfile.mkdtemp())
        (root / "wplug.py").write_text(WORLD_PLUGIN)
        args = cli.parse_args(["--ops-mock", "--world-plugin", str(root / "wplug.py")])
        self.assertTrue(args.ops_mock)
        code, out = self.run_cli(["--ops-mock", "--world-plugin", str(root / "wplug.py"), "--world-once"])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["type"], "world.snapshot")
        code, out = self.run_cli(["--ops-mock", "--world-plugin", str(root / "wplug.py"), "--ops-once"])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["type"], "ops.snapshot")

    def test_world_plugin_error_is_filtered(self):
        root = Path(tempfile.mkdtemp())
        ip = "192." + "168." + "9.9"
        (root / "bad.py").write_text(f"def create_sources(c):\n    return ['{ip}']\n")
        code, _ = self.run_cli(["--world-plugin", str(root / "bad.py"), "--world-once"])
        self.assertIsInstance(code, str)
        self.assertIn("world source", code)
        self.assertNotIn(ip, code)


if __name__ == "__main__":
    unittest.main()
