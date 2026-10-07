"""Card 5a: ops plugin loading (file, package, module:factory, entry point) and the mock source."""

import asyncio
import json
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock

from hermes_adapter import ops
from hermes_adapter.ops import OpsHub, load_plugin, resolve_factory
from hermes_adapter.ops_mock import CYCLE, MockOpsSource, build
from hermes_adapter.redact import ip_policy


PLUGIN_FILE = textwrap.dedent('''
    class Src:
        id = "filesrc"
        interval = 30
        def __init__(self, config):
            self.config = config
        def collect(self):
            return {"services": [{"id": "a", "name": "a", "state": "up", "detail": self.config.get("note", "")}]}

    def create_sources(config):
        return [Src(config)]
''')

PACKAGE_INIT = textwrap.dedent('''
    from .helpers import make

    def create_sources(config):
        return [make(config)]
''')

PACKAGE_HELPERS = textwrap.dedent('''
    class PkgSrc:
        id = "pkgsrc"
        def collect(self):
            return {"jobs": [{"id": "j", "name": "j", "lastStatus": "ok"}]}

    def make(config):
        return PkgSrc()
''')


class PluginLoaderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_file_plugin_gets_config(self):
        p = self.root / "plug.py"
        p.write_text(PLUGIN_FILE)
        srcs = load_plugin(str(p), {"note": "hello"})
        self.assertEqual([s.id for s in srcs], ["filesrc"])
        self.assertEqual(srcs[0].collect()["services"][0]["detail"], "hello")
        # loading the same file twice reuses the module
        self.assertIs(type(load_plugin(str(p))[0]), type(srcs[0]))

    def test_package_plugin_with_relative_imports(self):
        pkg = self.root / "my_ops"
        pkg.mkdir()
        (pkg / "__init__.py").write_text(PACKAGE_INIT)
        (pkg / "helpers.py").write_text(PACKAGE_HELPERS)
        srcs = load_plugin(str(pkg))
        self.assertEqual([s.id for s in srcs], ["pkgsrc"])
        hub = OpsHub(srcs)
        asyncio.run(hub.collect_all())
        self.assertEqual(hub.model()["jobs"][0]["id"], "pkgsrc/j")
        hub.close()

    def test_module_and_factory_spec(self):
        (self.root / "ops_mod_x.py").write_text(PLUGIN_FILE + "\nALT = create_sources\nSOURCES_ONLY = 1\n")
        sys.path.insert(0, str(self.root))
        try:
            self.assertEqual(load_plugin("ops_mod_x")[0].id, "filesrc")
            self.assertEqual(load_plugin("ops_mod_x:ALT")[0].id, "filesrc")
        finally:
            sys.path.remove(str(self.root))
            sys.modules.pop("ops_mod_x", None)

    def test_sources_constant(self):
        p = self.root / "consts.py"
        p.write_text("class S:\n    id = 'c'\n    def collect(self):\n        return {}\nSOURCES = [S()]\n")
        self.assertEqual(load_plugin(str(p))[0].id, "c")

    def test_entry_point(self):
        p = self.root / "epmod.py"
        p.write_text(PLUGIN_FILE)
        module = ops._load_module_from_path(p)

        class EP:
            name = "homelab"

            def load(self):
                return module

        with mock.patch("importlib.metadata.entry_points", return_value=[EP()]) as eps:
            self.assertEqual(load_plugin("ep:homelab")[0].id, "filesrc")
            eps.assert_called_with(group=ops.ENTRY_POINT_GROUP)
            with self.assertRaises(ValueError):
                load_plugin("ep:missing")

    def test_bad_plugins(self):
        (self.root / "nofactory.py").write_text("x = 1\n")
        (self.root / "badsrc.py").write_text("def create_sources(c):\n    return [object()]\n")
        (self.root / "notpy.txt").write_text("")
        (self.root / "emptydir").mkdir()
        for spec in ("nofactory.py", "badsrc.py", "notpy.txt", "emptydir", "missing.py"):
            with self.assertRaises(ValueError, msg=spec):
                load_plugin(str(self.root / spec))
        with self.assertRaises(ValueError):
            resolve_factory("  ")
        with self.assertRaises(ImportError):
            load_plugin("no_such_module_for_ops_tests")

    def test_factory_returning_one_source_or_none(self):
        (self.root / "one.py").write_text("class S:\n    id='one'\n    def collect(self):\n        return {}\ndef create_sources(c):\n    return S()\n")
        (self.root / "none.py").write_text("def create_sources(c):\n    return None\n")
        self.assertEqual(load_plugin(str(self.root / "one.py"))[0].id, "one")
        self.assertEqual(load_plugin(str(self.root / "none.py")), [])


class MockSourceTest(unittest.TestCase):
    def test_build_is_pure_and_cycles(self):
        self.assertEqual(build(5, 1_791_000_000.0), build(5, 1_791_000_000.0))
        states = [next(s["state"] for s in build(i, 1_791_000_000.0)["services"] if s["id"] == "service-3") for i in range(CYCLE * 2)]
        self.assertEqual(states, ["up"] * 3 + ["down"] * 3 + ["up"] * 2 + ["up"] * 3 + ["down"] * 3 + ["up"] * 2)

    def test_outage_alert_opens_and_resolves(self):
        def outage(i):
            return [a for a in build(i, 1_791_000_000.0)["alerts"] if a["title"] == "service-3 DOWN"]

        self.assertEqual(outage(0), [])
        self.assertEqual(outage(3)[0]["state"], "open")
        self.assertEqual(outage(6)[0]["state"], "resolved")
        self.assertEqual(outage(9)[0]["id"], "service-3-down-0")  # the previous outage stays visible
        self.assertEqual(outage(11)[0]["id"], "service-3-down-1")
        self.assertEqual(outage(11)[0]["state"], "open")

    def test_through_the_hub(self):
        clock = [1_791_000_000.0]
        src = MockOpsSource(interval=10, clock=lambda: clock[0])
        hub = OpsHub([src], clock=lambda: clock[0])
        asyncio.run(hub.collect_all())
        m0 = hub.model()
        self.assertEqual(len(m0["services"]), 7)
        self.assertEqual(len(m0["jobs"]), 4)
        self.assertEqual(len(m0["usage"]), 3)
        self.assertEqual(len(m0["alerts"]), 3)
        blob = json.dumps(m0)
        self.assertNotIn("192.0.2.", blob)
        self.assertNotIn("pa55word", blob)
        self.assertNotIn("abc123", blob)
        self.assertIn("[ip]", blob)
        prev, seen = m0, set()
        for _ in range(CYCLE):
            clock[0] += 10
            asyncio.run(hub.tick())
            cur = hub.model()
            for msg in OpsHub.changes(prev, cur):
                seen.add(msg["type"])
            prev = cur
        self.assertTrue({"ops.service.upsert", "ops.job.upsert", "ops.usage.upsert", "ops.alert.upsert", "ops.source.upsert"} <= seen, seen)
        hub.close()

    def test_documentation_addresses_only(self):
        with ip_policy(False):
            hub = OpsHub([MockOpsSource(clock=lambda: 1_791_000_000.0)], clock=lambda: 1_791_000_000.0)
            asyncio.run(hub.collect_all())
            blob = json.dumps(hub.model())
            hub.close()
        import re
        addrs = set(re.findall(r"\b\d{1,3}(?:\.\d{1,3}){3}\b", blob))
        self.assertTrue(addrs)
        self.assertTrue(all(a.startswith("192.0.2.") for a in addrs), addrs)
        self.assertNotIn("pa55word", blob)  # credentials go even when addresses are allowed


if __name__ == "__main__":
    unittest.main()
