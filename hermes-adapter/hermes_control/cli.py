"""CLI: ``python3 -m hermes_control serve | lock | unlock | status | check-policy``."""

from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
import logging
import os
import signal
import sys
import time
from pathlib import Path
from typing import Any

from hermes_adapter.server import AccessPolicy
from hermes_adapter.sources import env_hermes_home

from . import board as board_mod
from . import executors, frames, policy as pol, safety
from .audit import Audit, AuditError
from .ledger import Ledger, LedgerError
from .lock import LockFile, clear_lock
from .service import ControlService, is_loopback_bind, validate_bind

log = logging.getLogger("hermes_control")

DEFAULT_PORT = 7879
LOCK_NAME = "hermes.lock"
LEDGER_NAME = "ledger.sqlite3"
DRY_RUN_LEDGER_NAME = "ledger-dry-run.sqlite3"  # never shared with the live ledger: a replay always reports the mode it ran in
AUDIT_NAME = "hermes-control-audit.jsonl"


class StartupRefused(Exception):
    """The service must not start. The message says why (never contains key material)."""


def default_state_dir() -> Path:
    env = os.environ.get("AGENTCRAFT_CONTROL_STATE")
    if env:
        return Path(env).expanduser()
    state = os.environ.get("XDG_STATE_HOME") or str(Path.home() / ".local" / "state")
    return Path(state) / "agentcraft-gtnh" / "control"


def lock_path(args: argparse.Namespace) -> Path:
    if getattr(args, "lock_file", None):
        return Path(args.lock_file).expanduser()
    return Path(args.state_dir or default_state_dir()).expanduser() / LOCK_NAME


def build(args: argparse.Namespace, allow_in_repo: bool = False, clock: Any = None) -> ControlService:
    """Everything `serve` checks before it listens; raises StartupRefused."""
    try:
        validate_bind(args.bind, args.insecure_lan_bind)
    except ValueError as e:
        raise StartupRefused(str(e)) from None
    if args.board_fixture and not args.dry_run:
        raise StartupRefused("--board-fixture is allowed only together with --dry-run")
    dev_offline = bool(getattr(args, "dev_offline_actors", False))
    if dev_offline:
        # QA only: offline (version 3) actor UUIDs for a loopback dry run against a dev game server. Never in a real setup.
        if not args.dry_run:
            raise StartupRefused("--dev-offline-actors is allowed only together with --dry-run")
        if not is_loopback_bind(args.bind):
            raise StartupRefused("--dev-offline-actors is allowed only on a loopback bind")
    policy_path = Path(args.policy).expanduser()
    try:
        policy = pol.load(policy_path, allow_offline=dev_offline)
    except pol.PolicyError as e:
        raise StartupRefused(f"policy: {e}") from None
    try:
        key = frames.load_key(args.key_file)
    except frames.FrameError as e:
        raise StartupRefused(f"key: {e.reason}") from None
    state_dir = Path(args.state_dir or default_state_dir()).expanduser()
    lockp = lock_path(args)
    audit_path = Path(args.audit_file).expanduser() if args.audit_file else policy_path.resolve().parent / AUDIT_NAME
    try:
        safety.refuse_in_repo(state_dir, "control state directory", allow_in_repo)
        safety.refuse_in_repo(lockp, "lock file", allow_in_repo)
        safety.refuse_in_repo(audit_path, "audit log (pass --audit-file outside the checkout)", allow_in_repo)
        safety.ensure_private_dir(state_dir, "state directory")
        safety.check_dir(lockp.resolve().parent, "lock directory")
        safety.check_dir(audit_path.resolve().parent, "audit directory")
        if os.path.lexists(lockp):
            safety.check_file(lockp, "lock file")
    except safety.UnsafePath as e:
        raise StartupRefused(str(e)) from None
    try:
        ledger = Ledger(state_dir / (DRY_RUN_LEDGER_NAME if args.dry_run else LEDGER_NAME), clock or (lambda: int(time.time() * 1000)), allow_in_repo=allow_in_repo)
        audit = Audit(audit_path)
    except (LedgerError, AuditError) as e:
        raise StartupRefused(str(e)) from None
    if args.board_fixture:
        try:
            reader: board_mod.BoardReader = board_mod.FixtureBoardReader(args.board_fixture)
        except (OSError, board_mod.BoardError) as e:
            raise StartupRefused(f"board fixture: {e}") from None
    else:
        reader = board_mod.SqliteBoardReader(Path(args.hermes_home).expanduser() if args.hermes_home else env_hermes_home())
    access = AccessPolicy()
    for h in args.allow_host:
        access.allowed_hosts.add(h.strip().lower())
    for p in args.allow_peer:
        try:
            access.allowed_peers.append(ipaddress.ip_network(p, strict=False))
        except ValueError:
            raise StartupRefused(f"--allow-peer {p!r} is not an address or network") from None
    return ControlService(
        key=key, policy=policy, policy_path=policy_path, ledger=ledger, audit=audit, lock=LockFile(lockp), reader=reader,
        executor_map=executors.build(args.dry_run), clock=clock, dry_run=args.dry_run, access=access, host=args.bind, port=args.port,
        dev_offline_actors=dev_offline,
    )


async def serve_forever(svc: ControlService) -> None:
    await svc.start()
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    loop.add_signal_handler(signal.SIGHUP, lambda: asyncio.ensure_future(svc.reload_and_broadcast()))
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    caps = [n for n, c in svc.policy.caps.items() if c.enabled]
    print(f"hermes_control listening on ws://{svc.host}:{svc.port}/ dryRun={svc.dry_run} policy={svc.revision} enabled={caps or 'none'}"
          + (" DEV-OFFLINE-ACTORS (QA only)" if svc.dev_offline_actors else ""), file=sys.stderr, flush=True)
    await stop.wait()
    await svc.stop()


def add_common(p: argparse.ArgumentParser) -> None:
    p.add_argument("--state-dir", default=None, help="ledger and default lock location (default ~/.local/state/agentcraft-gtnh/control)")
    p.add_argument("--lock-file", default=None, help="Hermes-side lock file (default <state-dir>/hermes.lock)")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(prog="hermes_control", description="AgentCraft write path: Hermes-side control service (card 7).")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve", help="run the control service")
    s.add_argument("--policy", required=True, help="policy.json (owner-maintained; re-read on SIGHUP)")
    s.add_argument("--key-file", required=True, help="HMAC key file: one line of >= 64 hex characters, mode 0600")
    add_common(s)
    s.add_argument("--audit-file", default=None, help="audit log (default: next to the policy file)")
    s.add_argument("--bind", default="127.0.0.1", help="loopback by default; another address needs --insecure-lan-bind; wildcards are refused")
    s.add_argument("--port", type=int, default=DEFAULT_PORT)
    s.add_argument("--insecure-lan-bind", action="store_true", help="allow a non-loopback bind (plaintext on the LAN; prefer an SSH or VPN tunnel)")
    s.add_argument("--allow-peer", action="append", default=[], help="extra accepted peer address or network (repeatable)")
    s.add_argument("--allow-host", action="append", default=[], help="extra accepted Host header value (repeatable)")
    s.add_argument("--dry-run", action="store_true", help="replace every executor with a mock that records and executes nothing")
    s.add_argument("--board-fixture", default=None, help="JSON of cards and decisions used instead of the Hermes board (needs --dry-run)")
    s.add_argument("--dev-offline-actors", action="store_true",
                   help="QA ONLY: let the policy name offline (version 3) actor UUIDs. Refused unless --dry-run is given and the bind is loopback; audited at start")
    s.add_argument("--hermes-home", default=None, help="Hermes home for board reads (default: the global Hermes home)")
    s.add_argument("-v", "--verbose", action="store_true")
    k = sub.add_parser("lock", help="set the Hermes-side write lock")
    add_common(k)
    k.add_argument("--reason", required=True)
    k.add_argument("--by", default=None)
    u = sub.add_parser("unlock", help="clear the Hermes-side write lock (terminal only; nothing on the wire can do this)")
    add_common(u)
    u.add_argument("--yes", action="store_true", help="do not ask (needed when stdin is not a terminal)")
    st = sub.add_parser("status", help="show lock state (and policy summary with --policy)")
    add_common(st)
    st.add_argument("--policy", default=None)
    c = sub.add_parser("check-policy", help="validate a policy file and print what it enables")
    c.add_argument("--policy", required=True)
    c.add_argument("--no-perm-check", action="store_true", help="skip the owner / mode checks (content only)")
    c.add_argument("--json", action="store_true")
    return ap.parse_args(argv)


def summarize(p: pol.Policy) -> dict[str, Any]:
    return {
        "actors": len(p.actors), "lockSetsHermesLock": p.lock_sets_hermes_lock,
        "enabled": [n for n, c in p.caps.items() if c.enabled], "disabled": [n for n, c in p.caps.items() if not c.enabled],
        "services": sorted(p.services), "jobs": list(p.jobs), "hash": p.file_hash[:12],
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.cmd == "check-policy":
        try:
            p = pol.load(args.policy, check_perms=not args.no_perm_check)
        except pol.PolicyError as e:
            print(f"policy refused: {e}", file=sys.stderr)
            return 1
        info = summarize(p)
        if args.json:
            print(json.dumps(info, indent=2))
        else:
            print(f"policy ok: {info['actors']} actor(s); enabled: {', '.join(info['enabled']) or 'none'}; services: {', '.join(info['services']) or 'none'}; "
                  f"jobs: {', '.join(info['jobs']) or 'none'}; lockSetsHermesLock={info['lockSetsHermesLock']}")
        return 0
    if args.cmd == "lock":
        lp = lock_path(args)
        try:
            safety.check_dir(lp.resolve().parent, "lock directory")
        except safety.UnsafePath as e:
            print(f"refused: {e}", file=sys.stderr)
            return 1
        made = LockFile(lp).set(args.reason, args.by or f"terminal:{os.environ.get('USER', 'owner')}"[:64])
        print("locked" if made else "already locked (left unchanged)")
        return 0
    if args.cmd == "unlock":
        lp = lock_path(args)
        if not args.yes:
            if not sys.stdin.isatty():
                print("refused: unlock needs a terminal (or --yes)", file=sys.stderr)
                return 1
            if input(f"clear the Hermes-side lock {lp.name}? [y/N] ").strip().lower() != "y":
                print("left locked")
                return 1
        print("unlocked" if clear_lock(lp) else "was not locked")
        return 0
    if args.cmd == "status":
        st = LockFile(lock_path(args)).state()
        out: dict[str, Any] = {"locked": st.locked, "lockReason": st.reason, "lockedBy": st.by, "since": st.since,
                               "lockFile": str(lock_path(args)), "stateDir": str(Path(args.state_dir or default_state_dir()).expanduser())}
        if args.policy:
            try:
                out["policy"] = summarize(pol.load(args.policy))
            except pol.PolicyError as e:
                out["policy"] = f"refused: {e}"
        print(json.dumps(out, indent=2))
        return 0
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    try:
        svc = build(args)
    except StartupRefused as e:
        print(f"hermes_control: refusing to start: {e}", file=sys.stderr)
        return 2
    try:
        asyncio.run(serve_forever(svc))
    except OSError as e:
        print(f"hermes_control: cannot listen: {e.strerror or e}", file=sys.stderr)
        return 2
    return 0
