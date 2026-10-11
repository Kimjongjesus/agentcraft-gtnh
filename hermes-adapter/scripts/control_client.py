#!/usr/bin/env python3
"""Small signed test client for the control service (QA and interop; the game side is gtnh-write/).

    # terminal 1 (dry run, placeholder data only)
    python3 -m hermes_control serve --policy P --key-file K --state-dir D --dry-run --board-fixture hermes_control/fixtures/board.example.json
    # terminal 2
    python3 scripts/control_client.py --key-file K handshake
    python3 scripts/control_client.py --key-file K request card.dispatch '{"card":"t-demo-1","board":"main","profile":"builder-a"}' --confirm
    python3 scripts/control_client.py --key-file K request decision.answer '{"card":"t-demo-2","decision":"d-main-101","choice":"Option A"}'
    python3 scripts/control_client.py --key-file K lock --reason "panic"

Every frame (sent and received) is printed with its decoded payload. The key comes from a file, never
from the command line. The client is a test tool: it does NOT enforce the receiver rules the real
game side must (it prints what arrives, after checking the signature).
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from hermes_adapter.wsclient import WSClient, WSError  # noqa: E402

DEFAULT_ACTOR = "00000000-0000-4000-8000-000000000001"


def load_key(path: str) -> bytes:
    st = os.stat(path)
    if st.st_mode & 0o077:
        raise SystemExit(f"{path}: key file must not be readable or writable by group/others (chmod 600)")
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            return bytes.fromhex(line)
    raise SystemExit("no key line in the key file")


class ControlClient:
    def __init__(self, host: str, port: int, key: bytes, actor_uuid: str = DEFAULT_ACTOR, actor_name: str = "TestPlayer", quiet: bool = False,
                 timeout: float = 5.0, host_header: str | None = None, extra_headers: dict[str, str] | None = None) -> None:
        self.key = key
        self.ws = WSClient(host, port, host_header=host_header, extra_headers=extra_headers, timeout=timeout)
        self.actor = {"uuid": actor_uuid, "name": actor_name}
        self.quiet = quiet
        self.session = ""
        self.n = 0

    # ---- low level ----------------------------------------------------------------------------
    def sign(self, payload: str) -> str:
        return hmac.new(self.key, payload.encode("utf-8"), hashlib.sha256).hexdigest()

    def build(self, type_: str, fields: dict[str, Any], *, ts: int | None = None, nonce: str | None = None, session: str | None = None,
              dir_: str = "g2c", id_: str | None = None) -> tuple[str, dict[str, Any]]:
        self.n += 1
        payload = {"type": type_, "session": session if session is not None else self.session, "dir": dir_,
                   "id": id_ or f"g{int(time.time() * 1000) % 10**9}-{self.n}", "nonce": nonce or secrets.token_hex(16),
                   "ts": ts if ts is not None else int(time.time() * 1000), **fields}
        return json.dumps(payload, separators=(",", ":")), payload

    def send_raw(self, text: str) -> None:
        self.ws.send_frame(0x1, text.encode("utf-8"))

    def send(self, type_: str, fields: dict[str, Any], **kw: Any) -> dict[str, Any]:
        body, payload = self.build(type_, fields, **kw)
        self.send_raw(json.dumps({"v": 1, "type": type_, "payload": body, "sig": self.sign(body)}, separators=(",", ":")))
        if not self.quiet:
            print(f"-> {type_} {json.dumps(payload)}")
        return payload

    def recv(self, timeout: float = 5.0) -> tuple[str, dict[str, Any]]:
        frame = self.ws.recv(timeout)
        if set(frame) != {"v", "type", "payload", "sig"}:
            raise SystemExit(f"bad frame shape: {sorted(frame)}")
        if not hmac.compare_digest(self.sign(frame["payload"]), frame["sig"]):
            raise SystemExit("received a frame with a bad signature")
        payload = json.loads(frame["payload"])
        if not self.quiet:
            print(f"<- {frame['type']} {json.dumps(payload)}")
        return frame["type"], payload

    def recv_until(self, pred, timeout: float = 5.0) -> list[tuple[str, dict[str, Any]]]:
        seen = []
        deadline = time.time() + timeout
        while time.time() < deadline:
            t, p = self.recv(max(0.1, deadline - time.time()))
            seen.append((t, p))
            if pred(t, p):
                return seen
        raise SystemExit("timed out")

    # ---- protocol -----------------------------------------------------------------------------
    def handshake(self) -> list[tuple[str, dict[str, Any]]]:
        t, ch = self.recv()
        if t != "action.challenge":
            raise SystemExit(f"first frame was {t}, not action.challenge")
        self.session = ch["session"]
        self.send("hello", {"challenge": ch["challenge"], "features": ["action"]})
        got = [self.recv() for _ in range(3)]
        return [(t, ch), *got]

    def request(self, capability: str, args: dict[str, Any], tier: int | None = None, **kw: Any) -> dict[str, Any]:
        tiers = {"card.dispatch": 2, "service.restart": 2, "cron.run": 2}
        return self.send("action.request", {"actor": self.actor, "capability": capability,
                                            "tier": tiers.get(capability, 1) if tier is None else tier, "args": args}, **kw)

    def close(self) -> None:
        self.ws.close()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=7879)
    ap.add_argument("--key-file", required=True)
    ap.add_argument("--actor", default=DEFAULT_ACTOR)
    ap.add_argument("--name", default="TestPlayer")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("handshake", help="connect, handshake, print the policy and state, exit")
    r = sub.add_parser("request", help="send one action.request and print frames until its action.result")
    r.add_argument("capability")
    r.add_argument("args", help="JSON object")
    r.add_argument("--confirm", action="store_true", help="answer an action.prompt with action.confirm")
    r.add_argument("--cancel", action="store_true", help="answer an action.prompt with action.cancel")
    lk = sub.add_parser("lock", help="send action.lock")
    lk.add_argument("--reason", default="locked from the test client")
    args = ap.parse_args(argv)

    c = ControlClient(args.host, args.port, load_key(args.key_file), args.actor, args.name)
    try:
        c.handshake()
        if args.cmd == "handshake":
            return 0
        if args.cmd == "lock":
            c.send("action.lock", {"actor": c.actor, "reason": args.reason})
            c.recv_until(lambda t, p: t == "action.state" and p["locked"], 5.0)
            return 0
        sent = c.request(args.capability, json.loads(args.args))
        rid = sent["id"]
        seen = c.recv_until(lambda t, p: (t == "action.result" and p["re"] == rid) or t == "error", 5.0)
        last_t, last = seen[-1]
        if last_t == "action.result" and last["status"] == "prompted" and (args.confirm or args.cancel):
            token = next(p["token"] for t, p in seen if t == "action.prompt")
            c.send("action.cancel" if args.cancel else "action.confirm", {"actor": c.actor, "token": token})
            seen = c.recv_until(lambda t, p: t in ("action.result", "error") and p.get("re") in (rid, None, ""), 10.0)
        else:
            while last_t == "action.result" and last["status"] == "queued":
                seen = c.recv_until(lambda t, p: t == "action.result" and p["re"] == rid and p["status"] != "queued", 130.0)
                last_t, last = seen[-1]
        return 0
    except (WSError, OSError) as e:
        print(f"connection ended: {e}", file=sys.stderr)
        return 1
    finally:
        c.close()


if __name__ == "__main__":
    sys.exit(main())
