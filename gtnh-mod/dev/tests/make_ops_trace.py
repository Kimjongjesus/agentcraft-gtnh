#!/usr/bin/env python3
"""Card 5b: record a real ops.* wire trace from the adapter for the mod's plain-Java OpsCheck.

Drives hermes_adapter.ops.OpsHub with the generic mock source and seeded random sources (the
same churn as the adapter's convergence test: entities added/changed/removed, sources failing,
going stale and recovering, a cap overflow), and writes one JSON object per line:

    {"msg": <ops.* message exactly as a client receives it>}
    {"expect": {"services": [ids...], ...}, "states": {service id: state}}   # the adapter's model

OpsCheck replays the messages into the mod's OpsModel and checks it against every "expect" line.
Generic data only (mock names, RFC 5737 addresses). Usage: make_ops_trace.py OUT.jsonl [seed]
"""

from __future__ import annotations

import asyncio
import json
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ADAPTER = HERE.parent.parent.parent / "hermes-adapter"
sys.path.insert(0, str(ADAPTER))
sys.path.insert(0, str(ADAPTER / "tests"))

from hermes_adapter.ops import MAX, PLURAL, OpsHub  # noqa: E402
from hermes_adapter.ops_mirror import ENTITY_KINDS  # noqa: E402
from hermes_adapter.ops_mock import CYCLE, MockOpsSource  # noqa: E402
from test_ops_convergence import Clock, RandomSource  # noqa: E402


def main() -> int:
    out = Path(sys.argv[1])
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 7
    rng = random.Random(seed)
    clock = Clock()
    big = RandomSource("big", rng)
    big.services = {f"s{i}": {"id": f"s{i}", "name": f"service-{i}", "state": "up"} for i in range(MAX["service"] + 30)}
    sources = [MockOpsSource(interval=10, clock=clock), RandomSource("alpha", rng), RandomSource("beta", rng, interval=30.0), big]
    hub = OpsHub(sources, clock=clock)
    prev = None
    lines = 0
    with out.open("w", encoding="utf-8") as fh:
        for step in range(2 * CYCLE + 40):
            for s in sources:
                if hasattr(s, "mutate"):
                    s.mutate()
            asyncio.run(hub.tick())
            model = hub.model()
            msgs = [hub.snapshot(model)] if prev is None or step == CYCLE else OpsHub.changes(prev, model)
            for m in msgs:
                fh.write(json.dumps({"msg": {"v": 1, **m}}) + "\n")
                lines += 1
            expect = {PLURAL[k]: sorted(e["id"] for e in model[PLURAL[k]]) for k in ENTITY_KINDS}
            states = {e["id"]: e["state"] for e in model["services"]}
            fh.write(json.dumps({"expect": expect, "states": states}) + "\n")
            prev = model
            clock.t += 10.0 * rng.choice([1, 1, 1, 5, 20])
    hub.close()
    print(f"wrote {lines} ops messages to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
