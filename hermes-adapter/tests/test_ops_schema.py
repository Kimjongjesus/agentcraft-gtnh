"""Card 5a: the ops.* validator (hermes_adapter.ops_schema) and everything we emit passing it."""

import asyncio
import json
import random
import re
import unittest
from pathlib import Path

from hermes_adapter.ops import KINDS, OpsHub, normalize_batch
from hermes_adapter.ops_mock import CYCLE, MockOpsSource
from hermes_adapter.ops_schema import validate, validate_entity

from test_ops_hardening import junk

DOC = Path(__file__).resolve().parents[2] / "docs" / "ops-protocol.md"


class FakeClock:
    def __init__(self, t=1_791_000_000.0):
        self.t = t

    def __call__(self):
        return self.t


def good_service(**kw):
    e = {"id": "src/host-a", "sourceId": "src", "name": "host-a", "group": "hosts", "state": "up"}
    e.update(kw)
    return e


class ValidatorTest(unittest.TestCase):
    def test_valid_examples(self):
        self.assertEqual(validate({"v": 1, "type": "ops.service.upsert", "service": good_service(since=1_791_000_000_000, cpu=12.5)}), [])
        self.assertEqual(validate({"type": "ops.remove", "kind": "alert", "id": "src/a1"}), [])
        self.assertEqual(validate({"type": "ops.future.thing", "x": 1}), [])  # unknown ops types are ignored

    def test_detects_violations(self):
        cases = {
            "missing": ({"type": "ops.service.upsert", "service": {"id": "src/x", "sourceId": "src", "name": "x"}}, "missing"),
            "enum": ({"type": "ops.service.upsert", "service": good_service(state="green")}, "not one of"),
            "length": ({"type": "ops.service.upsert", "service": good_service(detail="d" * 81)}, "chars > 80"),
            "id": ({"type": "ops.service.upsert", "service": good_service(id="Host A")}, "is not <source>/<id>"),
            "prefix": ({"type": "ops.service.upsert", "service": good_service(id="other/host-a")}, "sourceId"),
            "pct": ({"type": "ops.service.upsert", "service": good_service(disk=101)}, "outside 0..100"),
            "seconds": ({"type": "ops.service.upsert", "service": good_service(since=1_791_000_000)}, "epoch milliseconds"),
            "null": ({"type": "ops.service.upsert", "service": good_service(detail=None)}, "null"),
            "bool": ({"type": "ops.job.upsert", "job": {"id": "s/j", "sourceId": "s", "name": "j", "lastStatus": "ok", "enabled": "yes"}}, "boolean"),
            "resolvedAt": ({"type": "ops.alert.upsert", "alert": {"id": "s/a", "sourceId": "s", "ts": 1_791_000_000_000, "severity": "warn",
                                                                   "source": "s", "title": "t", "state": "open", "resolvedAt": 1_791_000_000_000}}, "only allowed"),
            "payload": ({"type": "ops.usage.upsert"}, "payload key"),
            "remove": ({"type": "ops.remove", "kind": "agent", "id": "x"}, "not one of"),
            "version": ({"v": 2, "type": "ops.remove", "kind": "service", "id": "x"}, "protocol version"),
            "nan": ({"type": "ops.usage.upsert", "usage": {"id": "s/u", "sourceId": "s", "provider": "p", "window": "w",
                                                           "remainingPct": float("nan")}}, "not finite"),
            "snapshot": ({"type": "ops.snapshot", "services": [good_service(), good_service()], "jobs": [], "usage": [],
                          "alerts": [], "sources": [], "limits": {"services": 1}, "ts": 1}, "limit 1"),
        }
        for name, (msg, needle) in cases.items():
            errs = validate(msg)
            self.assertTrue(any(needle in e for e in errs), f"{name}: {errs}")
        self.assertIn("fields not in the spec: ip", validate_entity("service", good_service(ip="x"), strict=True)[0])
        self.assertEqual(validate_entity("service", good_service(ip="x")), [])  # receivers ignore unknown fields


class EverythingWeEmitIsValidTest(unittest.TestCase):
    def test_mock_through_two_cycles(self):
        clock = FakeClock()
        hub = OpsHub([MockOpsSource(interval=10, clock=clock)], clock=clock)
        prev = None
        seen_types = set()
        for _ in range(2 * CYCLE):
            asyncio.run(hub.collect_all())
            model = hub.model()
            snap = json.loads(json.dumps({"v": 1, **hub.snapshot(model)}, allow_nan=False))
            self.assertEqual(validate(snap, strict=True), [])
            for msg in OpsHub.changes(prev, model):
                seen_types.add(msg["type"])
                self.assertEqual(validate(json.loads(json.dumps(msg, allow_nan=False)), strict=True), [], msg)
            prev = model
            clock.t += 10
        hub.close()
        self.assertTrue({"ops.service.upsert", "ops.alert.upsert", "ops.job.upsert", "ops.usage.upsert",
                         "ops.source.upsert", "ops.remove"} <= seen_types, seen_types)

    def test_fuzzed_normalization_output_is_valid(self):
        rng = random.Random(99)
        for _ in range(300):
            batch = normalize_batch({"services": [junk(rng) for _ in range(5)], "jobs": [junk(rng) for _ in range(5)],
                                     "usage": [junk(rng) for _ in range(5)], "alerts": [junk(rng) for _ in range(5)]}, "fz")
            for kind in KINDS:
                for e in batch.entities[kind]:
                    self.assertEqual(validate_entity(kind, e, strict=True), [], e)

    def test_doc_examples_are_valid(self):
        blocks = re.findall(r"```json\n(.*?)```", DOC.read_text(), re.S)
        checked = 0
        for b in blocks:
            msg = json.loads(b)
            if str(msg.get("type", "")).startswith("ops."):
                self.assertEqual(validate(msg, strict=True), [], msg)
                checked += 1
        self.assertGreaterEqual(checked, 2)


if __name__ == "__main__":
    unittest.main()
