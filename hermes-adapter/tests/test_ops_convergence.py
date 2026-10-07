"""Card 5a: snapshot + incremental ops.* messages always reconstruct the adapter's exact model.

A reference receiver (hermes_adapter.ops_mirror) consumes what a client would get on the wire
while sources change randomly (entities added, changed, removed, sources failing, going stale and
recovering, caps overflowing). After every step the mirror must equal the adapter's model, both
for a client connected from the start and for one that joins mid-stream with a fresh snapshot.
"""

import asyncio
import json
import random
import unittest

from hermes_adapter.ops import KINDS, MAX, PLURAL, OpsHub
from hermes_adapter.ops_mirror import ENTITY_KINDS, OpsMirror
from hermes_adapter.ops_mock import CYCLE, MockOpsSource
from hermes_adapter.ops_schema import validate


class Clock:
    def __init__(self, t=1_791_000_000.0):
        self.t = t

    def __call__(self):
        return self.t


class RandomSource:
    def __init__(self, sid, rng, interval=10.0):
        self.id = sid
        self.interval = interval
        self.timeout = 2.0
        self.rng = rng
        self.fail = False
        self.services = {}
        self.jobs = {}
        self.usage = {}
        self.alerts = {}

    def mutate(self):
        r = self.rng
        for _ in range(r.randrange(0, 6)):
            op = r.random()
            i = r.randrange(0, 40)
            if op < 0.45:
                self.services[f"s{i}"] = {"id": f"s{i}", "name": f"service-{i}", "group": r.choice(["hosts", "services"]),
                                          "state": r.choice(["up", "down", "degraded", "unknown"]),
                                          "detail": r.choice(["", "slow", "HTTP 500", "disk 91%"]), "cpu": r.randrange(0, 100)}
            elif op < 0.6:
                self.services.pop(f"s{i}", None)
            elif op < 0.75:
                self.jobs[f"j{i}"] = {"id": f"j{i}", "name": f"job-{i}", "lastStatus": r.choice(["ok", "failed", "running"]),
                                      "lastRun": 1_791_000_000 + r.randrange(0, 10_000)}
            elif op < 0.8:
                self.jobs.pop(f"j{i}", None)
            elif op < 0.9:
                self.usage[f"u{i % 5}"] = {"id": f"u{i % 5}", "provider": f"provider-{i % 3}", "window": "week",
                                           "remainingPct": r.randrange(0, 101)}
            else:
                self.alerts[f"a{i}"] = {"id": f"a{i}", "ts": 1_791_000_000 + i, "title": f"alert {i}",
                                        "severity": r.choice(["info", "warn", "critical"]),
                                        "state": r.choice(["open", "resolved"])}
        self.fail = r.random() < 0.15

    def collect(self):
        if self.fail:
            raise RuntimeError("source offline")
        return json.loads(json.dumps({"services": list(self.services.values()), "jobs": list(self.jobs.values()),
                                      "usage": list(self.usage.values()), "alerts": list(self.alerts.values())}))


def canonical(model):
    return {PLURAL[k]: sorted((json.dumps(e, sort_keys=True) for e in model[PLURAL[k]])) for k in ENTITY_KINDS}


class ConvergenceTest(unittest.TestCase):
    def run_steps(self, hub, clock, sources, steps, rng, step_s=10.0):
        early = OpsMirror()
        prev = None
        late = None
        for step in range(steps):
            for s in sources:
                if hasattr(s, "mutate"):
                    s.mutate()
            asyncio.run(hub.tick())
            model = hub.model()
            if prev is None:
                snap = hub.snapshot(model)
                self.assertEqual(validate(snap, strict=True), [])
                early.apply(snap)
            else:
                for msg in OpsHub.changes(prev, model):
                    self.assertEqual(validate(msg, strict=True), [], msg)
                    self.assertTrue(early.apply(json.loads(json.dumps(msg))))
                    if late is not None:
                        late.apply(json.loads(json.dumps(msg)))
            if step == steps // 2:  # a client that connects mid-stream gets a fresh snapshot
                late = OpsMirror()
                late.apply(hub.snapshot(model))
            self.assertEqual(canonical(early.as_model()), canonical(model), f"step {step}")
            if late is not None:
                self.assertEqual(canonical(late.as_model()), canonical(model), f"late client, step {step}")
            prev = model
            clock.t += step_s * rng.choice([1, 1, 1, 5, 20])  # sometimes long gaps: stale and back
        return early

    def test_random_sources(self):
        for seed in range(6):
            rng = random.Random(seed)
            clock = Clock()
            sources = [RandomSource("alpha", rng), RandomSource("beta", rng, interval=30.0)]
            hub = OpsHub(sources, clock=clock)
            self.run_steps(hub, clock, sources, 60, rng)
            hub.close()

    def test_mock_two_cycles_and_caps(self):
        rng = random.Random(42)
        clock = Clock()
        big = RandomSource("big", rng)
        big.services = {f"s{i}": {"id": f"s{i}", "name": f"service-{i}", "state": "up"} for i in range(MAX["service"] + 30)}
        sources = [MockOpsSource(interval=10, clock=clock), big]
        hub = OpsHub(sources, clock=clock)
        mirror = self.run_steps(hub, clock, sources, 2 * CYCLE + 3, rng)
        self.assertLessEqual(len(mirror.state["service"]), MAX["service"])
        hub.close()

    def test_mirror_ignores_what_it_does_not_know(self):
        m = OpsMirror()
        self.assertFalse(m.apply({"type": "agent.upsert", "agent": {"id": "x"}}))
        self.assertFalse(m.apply({"type": "ops.future.upsert", "future": {"id": "x"}}))
        self.assertTrue(m.apply({"type": "ops.remove", "kind": "service", "id": "never-seen"}))
        self.assertEqual(m.as_model(), {PLURAL[k]: [] for k in ENTITY_KINDS})
        self.assertFalse(m.ready)


if __name__ == "__main__":
    unittest.main()
