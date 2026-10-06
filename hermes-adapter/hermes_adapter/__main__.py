"""CLI: python3 -m hermes_adapter [options]

Defaults are loopback-only. To let the gaming-spare test server connect, bind the LAN address
and allow that one peer explicitly, e.g.:

    python3 -m hermes_adapter --bind 192.0.2.10 --allow-peer 192.0.2.20
"""

from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
import logging
import signal
import sys
from pathlib import Path

from .mapping import Mapper
from .server import LOOPBACK_HOSTS, AccessPolicy, AdapterServer
from .sources import HermesSource, env_hermes_home


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="hermes_adapter", description="Read-only AgentCraft protocol server for Hermes.")
    p.add_argument("--bind", default="127.0.0.1", help="interface address to listen on (default 127.0.0.1; wildcards refused)")
    p.add_argument("--port", type=int, default=7878)
    p.add_argument("--allow-host", action="append", default=[], help="extra accepted Host header value (repeatable)")
    p.add_argument("--allow-peer", action="append", default=[], help="extra client IP or CIDR allowed to connect (repeatable)")
    p.add_argument("--hermes-home", type=Path, default=None, help="global Hermes home (default ~/.hermes)")
    p.add_argument("--boards", default="", help="comma-separated board slugs (default: all except *scratch*)")
    p.add_argument("--source", choices=("sqlite", "cli"), default="sqlite", help="kanban source (default: read-only sqlite)")
    p.add_argument("--hermes-bin", default="hermes")
    p.add_argument("--poll", type=float, default=3.0, help="seconds between Hermes reads")
    p.add_argument("--cast", type=Path, default=None, help="JSON {profile: {name, role, title, color}} overrides")
    p.add_argument("--once", action="store_true", help="print one snapshot as JSON and exit")
    p.add_argument("--log-level", default="INFO")
    return p.parse_args(argv)


def build_policy(args: argparse.Namespace) -> AccessPolicy:
    bind = ipaddress.ip_address(args.bind)
    if bind.is_unspecified:
        raise SystemExit("refusing to bind a wildcard address; pass the one LAN address that is needed")
    policy = AccessPolicy()
    hosts: set[str] = set(LOOPBACK_HOSTS)
    if not bind.is_loopback:
        hosts.add(str(bind) if bind.version == 4 else f"[{bind}]")
    hosts.update(h.strip().lower() for h in args.allow_host if h.strip())
    policy.allowed_hosts = hosts
    for peer in args.allow_peer:
        policy.allowed_peers.append(ipaddress.ip_network(peer.strip(), strict=False))
    return policy


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=args.log_level.upper(), format="%(asctime)s %(levelname)s %(name)s: %(message)s", stream=sys.stderr)
    cast = json.loads(args.cast.read_text()) if args.cast else None
    source = HermesSource(
        hermes_home=args.hermes_home or env_hermes_home(),
        boards=[b.strip() for b in args.boards.split(",") if b.strip()] or None,
        kanban_source=args.source,
        hermes_bin=args.hermes_bin,
    )
    mapper = Mapper(cast)
    if args.once:
        server = AdapterServer(source.read, mapper)
        server.model = mapper.build(source.read())
        print(json.dumps({"v": 1, **server.snapshot()}, indent=2, ensure_ascii=False))
        return 0
    policy = build_policy(args)
    server = AdapterServer(source.read, mapper, host=args.bind, port=args.port, policy=policy, poll_interval=args.poll)

    async def run() -> None:
        await server.start()
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop.set)
        await stop.wait()
        await server.stop()

    asyncio.run(run())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
