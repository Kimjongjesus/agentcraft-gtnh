#!/usr/bin/env python3
"""Watch the ops.* feed of a running adapter (read-only test client; sends only hello).

    python3 scripts/ops_watch.py                         # ws://127.0.0.1:7878, until Ctrl-C
    python3 scripts/ops_watch.py --port 17878 --seconds 120 --jsonl /tmp/ops.jsonl
    python3 scripts/ops_watch.py --plain                 # control: a client WITHOUT the feature

Prints one compact line per message (types, ids, states) and checks every ops.* message against
the spec (hermes_adapter/ops_schema.py, strict); --jsonl also records every message.
Exit code 1 if a message violates the spec or a --plain client ever receives an ops.* message.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hermes_adapter.ops_schema import validate  # noqa: E402
from hermes_adapter.wsclient import WSClient  # noqa: E402


def describe(m: dict) -> str:
    t = m.get("type", "?")
    if t == "ops.snapshot":
        counts = {k: len(m.get(k, [])) for k in ("services", "jobs", "usage", "alerts", "sources")}
        return f"ops.snapshot {counts}"
    if t == "ops.remove":
        return f"ops.remove {m.get('kind')} {m.get('id')}"
    if t.startswith("ops.") and t.endswith(".upsert"):
        kind = t[4:-7]
        e = m.get(kind, {})
        state = e.get("state") or e.get("lastStatus") or (f"{e.get('remainingPct')}%" if kind == "usage" else "")
        return f"{t} {e.get('id')} {state} {e.get('detail', '')}".rstrip()
    if t == "snapshot":
        return f"snapshot agents={len(m.get('agents', []))} tasks={len(m.get('tasks', []))}"
    if t == "ack":
        return f"ack ok={m.get('ok')} result={m.get('result')}"
    return t


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=7878)
    ap.add_argument("--seconds", type=float, default=0, help="stop after this long (0 = until Ctrl-C)")
    ap.add_argument("--jsonl", type=Path, default=None, help="also append every message to this file")
    ap.add_argument("--plain", action="store_true", help="do not ask for the ops feature (control client)")
    args = ap.parse_args(argv)
    c = WSClient(args.host, args.port)
    hello = {"type": "hello", "id": "w1", "modVersion": "ops-watch", "protocol": 1, "client": "cli"}
    if not args.plain:
        hello["features"] = ["ops"]
    c.send(hello)
    end = time.time() + args.seconds if args.seconds else None
    out = args.jsonl.open("a") if args.jsonl else None
    leaked = 0
    invalid = 0
    try:
        while end is None or time.time() < end:
            try:
                m = c.recv(timeout=max(0.2, (end - time.time()) if end else 30))
            except OSError:
                continue
            if args.plain and str(m.get("type", "")).startswith("ops."):
                leaked += 1
            print(time.strftime("%H:%M:%S"), describe(m), flush=True)
            if str(m.get("type", "")).startswith("ops."):
                for problem in validate(m, strict=True):
                    invalid += 1
                    print(f"  INVALID {problem}", flush=True)
            if out:
                out.write(json.dumps(m, ensure_ascii=False) + "\n")
                out.flush()
    except KeyboardInterrupt:
        pass
    finally:
        c.close()
        if out:
            out.close()
    if args.plain:
        print(f"plain client received {leaked} ops.* message(s)")
    print(f"spec violations: {invalid}")
    return 1 if leaked or invalid else 0


if __name__ == "__main__":
    raise SystemExit(main())
