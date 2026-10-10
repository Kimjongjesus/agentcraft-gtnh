"""CLI: python3 -m hermes_adapter [options]

Defaults are loopback-only. To let a test game server on the LAN connect, bind the LAN address
and allow that one peer explicitly, e.g.:

    python3 -m hermes_adapter --bind 192.0.2.10 --allow-peer 192.0.2.20

Ops feeds (docs/ops-protocol.md) are off unless a source is given:

    python3 -m hermes_adapter --ops-mock                      # generic demo data
    python3 -m hermes_adapter --ops-plugin path/to/plugin     # a .py file, package dir, module[:factory] or ep:<name>
    python3 -m hermes_adapter --ops-mock --ops-once           # print one ops.snapshot as JSON and exit

Factory telemetry (world.*, docs/world-protocol.md) is off unless a world source is given:

    python3 -m hermes_adapter --factory-token-file ~/.config/agentcraft/aifactory-token.txt
    python3 -m hermes_adapter --world-plugin path/to/plugin   # same plugin forms as --ops-plugin
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
from typing import Any

from . import redact
from .dashboard import DashboardError, DashboardServer, validate_bind as validate_dashboard_bind
from .journal import JournalError, Retention, WorldJournal
from .mapping import CAST_FILE_NAME, Mapper, load_cast
from .ops import OpsHub, load_plugin
from .ops_mock import MockOpsSource
from .server import LOOPBACK_HOSTS, AccessPolicy, AdapterServer
from .sources import HermesSource, env_hermes_home
from .sources import factory
from .sources.plugin import load_plugin as load_world_plugin
from .world_hub import WorldHub

log = logging.getLogger("hermes_adapter")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="hermes_adapter", description="Read-only AgentCraft protocol server for Hermes.")
    p.add_argument("--bind", default="127.0.0.1", help="interface address to listen on (default 127.0.0.1; wildcards refused)")
    p.add_argument("--port", type=int, default=7878)
    d = p.add_argument_group("optional read-only HTTP dashboard")
    d.add_argument("--dashboard", action="store_true", help="enable the separate GET-only dashboard listener")
    d.add_argument("--dashboard-bind", default="127.0.0.1", help="numeric specific dashboard address (default 127.0.0.1)")
    d.add_argument("--dashboard-port", type=int, default=8787, help="dashboard HTTP port (default 8787)")
    d.add_argument("--dashboard-allow-lan", action="store_true", help="explicitly allow a non-loopback dashboard bind; no authentication or TLS")
    p.add_argument("--allow-host", action="append", default=[], help="extra accepted Host header value (repeatable)")
    p.add_argument("--allow-peer", action="append", default=[], help="extra client IP or CIDR allowed to connect (repeatable)")
    p.add_argument("--hermes-home", type=Path, default=None, help="global Hermes home (default: Hermes' standard home directory)")
    p.add_argument("--boards", default="", help="comma-separated board slugs (default: all except *scratch*)")
    p.add_argument("--source", choices=("sqlite", "cli"), default="sqlite", help="kanban source (default: read-only sqlite)")
    p.add_argument("--hermes-bin", default="hermes")
    p.add_argument("--poll", type=float, default=3.0, help="seconds between Hermes reads")
    p.add_argument("--cast", type=Path, default=None, help="JSON {profile: {name, role, title, color}} overrides")
    p.add_argument("--once", action="store_true", help="print one snapshot as JSON and exit")
    g = p.add_argument_group("ops feeds (docs/ops-protocol.md)")
    g.add_argument("--ops-plugin", action="append", default=[], metavar="SPEC",
                   help="load ops sources from a .py file, a package directory, module[:factory] or ep:<entry point> (repeatable)")
    g.add_argument("--ops-config", type=Path, default=None, metavar="FILE",
                   help="JSON object handed to every plugin's create_sources(config); never sent to clients")
    g.add_argument("--ops-mock", action="store_true", help="add the generic mock ops source (demo data, RFC 5737 addresses)")
    g.add_argument("--ops-mock-interval", type=float, default=10.0, help="seconds between mock collects (default 10)")
    g.add_argument("--ops-tick", type=float, default=1.0, help="seconds between ops schedule checks (default 1)")
    g.add_argument("--ops-once", action="store_true", help="collect every ops source once, print the ops.snapshot as JSON and exit")
    p.add_argument("--allow-ip-text", action="store_true",
                   help="do NOT redact IPv4/IPv6 addresses in text sent to clients (redacted by default)")
    p.add_argument("--log-level", default="INFO")
    w = p.add_argument_group("factory telemetry (world.*, docs/world-protocol.md)")
    w.add_argument("--factory-url", default=None, help="aifactory telemetry base URL, e.g. http://127.0.0.1:25580 "
                   "(enables the factory source; default from $AGENTCRAFT_FACTORY_URL when --factory-token-file is set)")
    w.add_argument("--factory-token-file", type=Path, default=None,
                   help="file holding the telemetry token (chmod 600). There is deliberately no flag for the token itself")
    w.add_argument("--factory-interval", type=float, default=30.0, help="seconds between telemetry polls (min 10)")
    w.add_argument("--factory-allow-remote", action="store_true",
                   help="allow a non-loopback telemetry URL (the token then crosses the network; prefer an SSH tunnel)")
    w.add_argument("--world-plugin", default=None, help="custom world source plugin (module[:factory] or .py path)")
    w.add_argument("--journal", type=Path, default=None, help="world journal file (default ~/.local/state/agentcraft-gtnh/)")
    w.add_argument("--no-journal", action="store_true", help="do not keep a world journal")
    w.add_argument("--journal-days", type=float, default=30.0, help="keep journal events and samples this many days")
    w.add_argument("--world-once", action="store_true", help="poll the world source once, print world.snapshot, exit")
    return p.parse_args(argv)


def build_world(args: argparse.Namespace) -> WorldHub | None:
    """The world hub for the configured source, or None when no world source was asked for."""
    if not (args.factory_url or args.factory_token_file or args.world_plugin):
        return None
    try:
        if args.world_plugin:
            try:
                sources = load_world_plugin(args.world_plugin, {"interval": args.factory_interval})
            except (ValueError, ImportError) as e:
                # the shared loader's messages can quote a plugin's repr: filter them like --ops-plugin
                raise ValueError(redact.clean(str(e), 300)) from None
        else:
            sources = factory.create_sources({
                "url": args.factory_url, "token_file": args.factory_token_file, "interval": args.factory_interval,
                "allow_remote": args.factory_allow_remote,
            })
    except (factory.ConfigError, ValueError, ImportError) as e:
        raise SystemExit(f"world source: {e}") from None
    if len(sources) != 1:
        raise SystemExit("world source: exactly one source per adapter is supported")
    journal = None
    if not args.no_journal and not args.world_once:
        try:
            journal = WorldJournal(args.journal, Retention(max_age_days=max(1.0, args.journal_days)))
        except (JournalError, OSError) as e:
            raise SystemExit(f"world journal: {e}") from None
    return WorldHub(sources[0], journal)


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


def load_ops_config(path: Path | None) -> dict[str, Any]:
    if path is None:
        return {}
    try:
        data = json.loads(path.expanduser().read_text())
    except (OSError, ValueError) as e:
        # the message names the file only: a parse error could quote a line of a private config
        raise SystemExit(f"--ops-config {path.name}: cannot read a JSON object ({type(e).__name__})") from None
    if not isinstance(data, dict):
        raise SystemExit(f"--ops-config {path.name}: expected a JSON object")
    return data


def build_ops(args: argparse.Namespace) -> OpsHub | None:
    """The OpsHub for the configured sources, or None when no ops source is configured."""
    sources: list[Any] = []
    if args.ops_mock:
        sources.append(MockOpsSource(interval=args.ops_mock_interval))
    if args.ops_plugin:
        config = load_ops_config(args.ops_config)
        for spec in args.ops_plugin:
            try:
                loaded = load_plugin(spec, config)
            except Exception as e:  # noqa: BLE001 - report which plugin failed, then stop
                raise SystemExit(f"--ops-plugin {spec}: {type(e).__name__}: {redact.clean(str(e), 300)}") from None
            log.info("ops plugin %s: %d source(s)", spec, len(loaded))
            sources.extend(loaded)
    elif args.ops_config is not None:
        raise SystemExit("--ops-config needs at least one --ops-plugin")
    if not sources:
        return None
    try:
        return OpsHub(sources)
    except ValueError as e:
        raise SystemExit(f"ops sources: {e}") from None


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.dashboard:
        try:
            validate_dashboard_bind(args.dashboard_bind, args.dashboard_port, args.dashboard_allow_lan)
        except DashboardError as e:
            raise SystemExit(str(e)) from None
    logging.basicConfig(level=args.log_level.upper(), format="%(asctime)s %(levelname)s %(name)s: %(message)s", stream=sys.stderr)
    # privacy first: the switch must be set before any source text is cleaned
    redact.set_redact_ips(not args.allow_ip_text)
    if args.allow_ip_text:
        log.warning("--allow-ip-text: IP addresses in agent/ops text are sent to clients unredacted")
    ops = build_ops(args)
    if args.ops_once:
        if ops is None:
            raise SystemExit("--ops-once needs --ops-mock or --ops-plugin")

        async def once() -> dict[str, Any]:
            await ops.collect_all()
            return ops.snapshot()

        try:
            snap = asyncio.run(once())
        finally:
            ops.close()
        print(json.dumps({"v": 1, **snap}, indent=2, ensure_ascii=False))
        return 0
    try:
        world = build_world(args)
    except BaseException:
        if ops is not None:
            ops.close()
        raise
    if args.world_once:
        if ops is not None:
            ops.close()
        if world is None:
            raise SystemExit("--world-once needs --factory-url / --factory-token-file or --world-plugin")
        world.poll()
        snap = world.snapshot_message()
        world.close()
        print(json.dumps({"v": 1, **snap}, indent=2, ensure_ascii=False))
        return 0 if snap["source"]["state"] in ("ok", "stale", "starting") else 1
    hermes_home = args.hermes_home or env_hermes_home()
    cast = load_cast(args.cast) if args.cast else None
    if cast is None and (hermes_home / CAST_FILE_NAME).is_file():
        # the installation's private names/colours, kept next to Hermes' own config, not in this repo
        cast = load_cast(hermes_home / CAST_FILE_NAME)
        log.info("cast: %d profile(s) from %s", len(cast), CAST_FILE_NAME)
    source = HermesSource(
        hermes_home=hermes_home,
        boards=[b.strip() for b in args.boards.split(",") if b.strip()] or None,
        kanban_source=args.source,
        hermes_bin=args.hermes_bin,
    )
    mapper = Mapper(cast)
    if args.once:
        server = AdapterServer(source.read, mapper)
        server.model = mapper.build(source.read())
        print(json.dumps({"v": 1, **server.snapshot()}, indent=2, ensure_ascii=False))
        if ops is not None:
            ops.close()
        if world is not None:
            world.close()
        return 0
    policy = build_policy(args)
    server = AdapterServer(source.read, mapper, host=args.bind, port=args.port, policy=policy, poll_interval=args.poll,
                           ops=ops, ops_tick=args.ops_tick, world=world)
    dashboard = (DashboardServer(server, args.dashboard_bind, args.dashboard_port, args.dashboard_allow_lan)
                 if args.dashboard else None)

    async def run() -> None:
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        registered = []
        try:
            await server.start()
            if dashboard is not None:
                await dashboard.start()
            for sig in (signal.SIGINT, signal.SIGTERM):
                loop.add_signal_handler(sig, stop.set)
                registered.append(sig)
            await stop.wait()
        except DashboardError as e:
            raise SystemExit(str(e)) from None
        finally:
            for sig in registered:
                loop.remove_signal_handler(sig)
            try:
                if dashboard is not None:
                    await dashboard.stop()
            finally:
                await server.stop()

    asyncio.run(run())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
