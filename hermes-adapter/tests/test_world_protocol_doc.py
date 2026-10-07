"""docs/world-protocol.md and the code describe the same wire: message types, event kinds, states,
limits. Also: every kind and message the code emits is in that vocabulary."""

import ast
import re
import unittest
from pathlib import Path

from hermes_adapter import world

HERE = Path(__file__).resolve().parent
DOC = (HERE.parent.parent / "docs" / "world-protocol.md").read_text()
SRC = "\n".join((HERE.parent / "hermes_adapter" / name).read_text() for name in ("world.py", "world_hub.py"))


def section(title):
    m = re.search(rf"^#+ [^\n]*{re.escape(title)}[^\n]*\n(.*?)(?=^#+ |\Z)", DOC, re.M | re.S)
    assert m, title
    return m.group(1)


class ProtocolDocTest(unittest.TestCase):
    def test_message_types_match(self):
        documented = set(re.findall(r"`(world\.[a-z0-9.]+)`", section("Messages")))
        self.assertEqual(documented, set(world.MESSAGE_TYPES))

    def test_event_kinds_match(self):
        listed = re.search(r"^Kinds: (.*?)\.\n\n", DOC, re.M | re.S).group(1)
        documented = set(re.findall(r"`([a-z0-9.]+)`", listed))
        self.assertEqual(documented, set(world.EVENT_KINDS))

    def test_code_emits_only_documented_kinds_and_types(self):
        tree = ast.parse(SRC)
        kinds, types = set(), set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and getattr(node.func, "attr", getattr(node.func, "id", "")) == "_ev":
                first = node.args[0]
                if isinstance(first, ast.Constant):
                    kinds.add(first.value)
            if isinstance(node, ast.Dict):
                for k, v in zip(node.keys, node.values):
                    if isinstance(k, ast.Constant) and k.value == "type":
                        if isinstance(v, ast.Constant) and str(v.value).startswith("world."):
                            types.add(v.value)
                        elif isinstance(v, ast.JoinedStr):
                            types.update(t for t in world.MESSAGE_TYPES if t.endswith(".upsert"))
        self.assertTrue(kinds)
        self.assertLessEqual(kinds, set(world.EVENT_KINDS))
        self.assertLessEqual(types, set(world.MESSAGE_TYPES))

    def test_states_and_limits_match(self):
        machine = section("WorldMachine")
        for s in world.MACHINE_STATES:
            self.assertIn(f"`{s}`", machine)
        source = section("WorldSource")
        for s in world.SOURCE_STATES:
            self.assertIn(f"`{s}`", source)
        for s in world.COVERAGE_STATES:
            self.assertIn(f"`{s}`", section("Coverage"))
        bounds = section("Bounds")
        for key in ("machines", "parts", "maintenance", "top", "palette", "events", "eventsPerCapture"):
            self.assertIn(str(world.LIMITS[key]), bounds, key)
        self.assertIn(f"`world.snapshot.world` is `{world.VERSION}`", DOC)
        self.assertIn(f"{world.SETTLE_MS // 1000} s", DOC)
        self.assertIn(f"{int(world.POWER_LOW_PCT)} %", DOC)
        self.assertIn(f"{int(world.POWER_RECOVERED_PCT)} %", DOC)


if __name__ == "__main__":
    unittest.main()
