"""Optional, bounded, read-only HTTP view of the adapter's existing mapped state.

No source collection, world access, arbitrary file routes, request logging, or writes.
Assets are loaded once at startup; fonts remain in the mod's existing resource tree.
This is a plaintext listener, not an authentication boundary: LAN exposure is opt-in.
"""
from __future__ import annotations

import asyncio
import ipaddress
import json
import math
import re
import socket
import time
from pathlib import Path
from typing import Any

from . import redact

MAX_HEADERS = 8192
MAX_HEADER_COUNT = 64
MAX_RESPONSE = 2 * 1024 * 1024
MAX_CLIENTS = 16
REQUEST_TIMEOUT = 3.0
OUTPUT_TIMEOUT = 3.0
STATIC_ROOT = Path(__file__).resolve().parent / "dashboard_static"
FONT_ROOT = (Path(__file__).resolve().parents[2] / "gtnh-mod" / "src" / "main" /
             "resources" / "assets" / "agentcraftgtnh" / "fonts")
ASSETS = {
    "/": ("index.html", "text/html; charset=utf-8", False),
    "/dashboard.css": ("dashboard.css", "text/css; charset=utf-8", False),
    "/dashboard.js": ("dashboard.js", "text/javascript; charset=utf-8", False),
    "/fonts/Nunito-Regular.ttf": ("Nunito-Regular.ttf", "font/ttf", True),
    "/fonts/Nunito-Bold.ttf": ("Nunito-Bold.ttf", "font/ttf", True),
    "/fonts/JetBrainsMono-Regular.ttf": ("JetBrainsMono-Regular.ttf", "font/ttf", True),
}
SNAPSHOT_FIELDS = frozenset(("type", "foreman", "agents", "tasks", "decisions", "repos",
                             "memory", "goals", "goal", "feed", "logs"))
OPS_FIELDS = frozenset(("type", "services", "jobs", "usage", "alerts", "sources", "limits", "ts"))
CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; "
       "connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; "
       "frame-ancestors 'none'; form-action 'none'")
_REASONS = {200: "OK", 400: "Bad Request", 403: "Forbidden", 404: "Not Found",
            405: "Method Not Allowed", 408: "Request Timeout", 431: "Request Header Fields Too Large",
            503: "Service Unavailable"}
_HEADER_NAME = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+\Z")


class DashboardError(ValueError):
    """Public, constant diagnostics only; never include request or exception text."""


def validate_bind(host: str, port: int, allow_lan: bool = False) -> str:
    """Numeric, specific unicast address only. Port zero is available for local tests."""
    try:
        if not isinstance(host, str) or "%" in host:
            raise ValueError
        address = ipaddress.ip_address(host)
    except ValueError:
        raise DashboardError("dashboard bind must be a numeric, specific IP address") from None
    if address.is_unspecified or address.is_multicast or str(address) == "255.255.255.255":
        raise DashboardError("dashboard wildcard or multicast binds are refused")
    # IPv4-mapped IPv6 is not an alternate route around the LAN acknowledgement.
    effective = address.ipv4_mapped if isinstance(address, ipaddress.IPv6Address) else None
    if not (effective or address).is_loopback and not allow_lan:
        raise DashboardError("non-loopback dashboard bind requires --dashboard-allow-lan")
    if type(port) is not int or not 0 <= port <= 65535:
        raise DashboardError("dashboard port must be between 0 and 65535")
    return str(address)


def _safe_value(value: Any, budget: list[int], depth: int = 0) -> Any:
    """Defence-in-depth over already mapped state, including identifiers and dict keys.

    Unknown Python objects are never stringified. Oversized or pathological state fails
    closed, rather than slicing before whole-source personal-note/secret filtering.
    """
    budget[0] -= 1
    if budget[0] < 0 or depth > 16:
        raise DashboardError("dashboard snapshot exceeds limits")
    if isinstance(value, str):
        if len(value) > 65536:
            raise DashboardError("dashboard snapshot exceeds limits")
        budget[1] -= len(value.encode("utf-8"))
        if budget[1] < 0:
            raise DashboardError("dashboard snapshot exceeds limits")
        return redact.clean(value, 0, keep_newlines=True)
    if value is None or type(value) in (bool, int):
        if type(value) is int and value.bit_length() > 64:
            raise DashboardError("dashboard snapshot exceeds limits")
        return value
    if type(value) is float:
        return value if math.isfinite(value) else None
    if isinstance(value, dict):
        if len(value) > 4096:
            raise DashboardError("dashboard snapshot exceeds limits")
        out = {}
        for key, item in value.items():
            if not isinstance(key, str):
                continue
            safe_key = _safe_value(key, budget, depth + 1)
            # Changed keys are not protocol fields. Drop instead of making collisions.
            if safe_key != key:
                continue
            out[safe_key] = _safe_value(item, budget, depth + 1)
        return out
    if isinstance(value, (list, tuple)):
        if len(value) > 4096:
            raise DashboardError("dashboard snapshot exceeds limits")
        return [_safe_value(item, budget, depth + 1) for item in value]
    return None


class DashboardServer:
    def __init__(self, adapter: Any, host: str = "127.0.0.1", port: int = 8787,
                 allow_lan: bool = False, *, max_clients: int = MAX_CLIENTS,
                 request_timeout: float = REQUEST_TIMEOUT, output_timeout: float = OUTPUT_TIMEOUT,
                 max_response: int = MAX_RESPONSE) -> None:
        self.host = validate_bind(host, port, allow_lan)
        self.port = port
        self.adapter = adapter
        if not 1 <= max_clients <= MAX_CLIENTS:
            raise DashboardError("invalid dashboard client limit")
        if not all(math.isfinite(t) and t > 0 for t in (request_timeout, output_timeout)):
            raise DashboardError("invalid dashboard deadline")
        if not 64 <= max_response <= MAX_RESPONSE:
            raise DashboardError("invalid dashboard response limit")
        self.max_clients = max_clients
        self.request_timeout = request_timeout
        self.output_timeout = output_timeout
        self.max_response = max_response
        self._server: asyncio.Server | None = None
        self._observer: asyncio.Task[Any] | None = None
        self._clients: dict[asyncio.Task[Any], asyncio.StreamWriter] = {}
        self._assets: dict[str, tuple[bytes, str]] = {}
        self.authorities: frozenset[str] = frozenset()
        self._polls = 0
        self._updated_at = 0
        self._observed_at = 0.0

    def _observe_polls(self) -> None:
        polls = self.adapter.polls
        if type(polls) is int and polls > self._polls:
            # AdapterServer increments only on successful mapped reads. Do not touch
            # refresh/read or replace any WebSocket lifecycle methods to observe it.
            self._polls = polls
            self._updated_at = int(time.time() * 1000)
            self._observed_at = time.monotonic()

    async def _observe(self) -> None:
        while True:
            self._observe_polls()
            await asyncio.sleep(0.05)

    def snapshot_bytes(self) -> bytes:
        self._observe_polls()
        snapshot = self.adapter.snapshot()
        ops = self.adapter.ops_snapshot()
        if not isinstance(snapshot, dict) or not isinstance(ops, dict):
            raise DashboardError("dashboard snapshot unavailable")
        # Only the two known mapped protocols; future world/raw extensions stay private.
        payload = {
            "snapshot": {k: v for k, v in snapshot.items() if k in SNAPSHOT_FIELDS},
            "ops": {k: v for k, v in ops.items() if k in OPS_FIELDS},
            "meta": {
                "generatedAt": int(time.time() * 1000), "updatedAt": self._updated_at,
                "polls": self._polls,
                "stale": bool(not self._polls or self.adapter.last_error is not None or
                              getattr(self.adapter, "ops_error", None) is not None or
                              time.monotonic() - self._observed_at >
                              max(1.0, float(self.adapter.poll_interval) * 3)),
                "readOnly": True,
            },
        }
        data = json.dumps(_safe_value(payload, [100000, self.max_response]),
                          ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        if len(data) > self.max_response:
            raise DashboardError("dashboard snapshot exceeds limits")
        return data

    def _load_assets(self) -> None:
        self._assets.clear()
        for route, (name, content_type, font) in ASSETS.items():
            root = FONT_ROOT if font else STATIC_ROOT
            path = root / name
            try:
                # Fixed paths only, and no symlink escape out of the resource directory.
                if path.is_symlink() or path.resolve().parent != root.resolve():
                    continue
                with path.open("rb") as handle:
                    data = handle.read(self.max_response + 1)
                if len(data) <= self.max_response:
                    self._assets[route] = (data, content_type)
            except OSError:
                # Missing package resources are unavailable, never an arbitrary fallback.
                continue

    async def start(self) -> int:
        if self._server is not None:
            raise DashboardError("dashboard already started")
        self._load_assets()
        family = socket.AF_INET6 if ipaddress.ip_address(self.host).version == 6 else socket.AF_INET
        try:
            self._server = await asyncio.start_server(
                self._accept, self.host, self.port, family=family, limit=MAX_HEADERS,
                backlog=self.max_clients, start_serving=False)
            self.port = self._server.sockets[0].getsockname()[1]
            host = f"[{self.host}]" if family == socket.AF_INET6 else self.host
            authorities = {f"{host}:{self.port}"}
            if self.host in ("127.0.0.1", "::1"):
                authorities.add(f"localhost:{self.port}")
            if self.port == 80:
                authorities.update(a.removesuffix(":80") for a in tuple(authorities))
            self.authorities = frozenset(authorities)
            self._observe_polls()
            self._observer = asyncio.create_task(self._observe())
            await self._server.start_serving()
        except BaseException as error:
            await self.stop()
            if not isinstance(error, Exception):
                raise
            raise DashboardError("dashboard listener could not start") from None
        return self.port

    def _accept(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        # Synchronous admission: rejected sockets never become unbounded waiting tasks.
        if self._server is None or len(self._clients) >= self.max_clients:
            writer.transport.abort()
            return
        task = asyncio.create_task(self._handle(reader, writer))
        self._clients[task] = writer
        task.add_done_callback(lambda done: self._clients.pop(done, None))

    async def _request(self, reader: asyncio.StreamReader) -> tuple[int, str | None, bool]:
        try:
            raw = await reader.readuntil(b"\r\n\r\n")
        except asyncio.LimitOverrunError:
            return 431, None, False
        except asyncio.IncompleteReadError:
            return 400, None, False
        if len(raw) > MAX_HEADERS:
            return 431, None, False
        try:
            lines = raw[:-4].decode("ascii").split("\r\n")
            method, target, version = lines[0].split(" ")
        except (ValueError, UnicodeDecodeError):
            return 400, None, False
        if version != "HTTP/1.1":
            return 400, None, method == "HEAD"
        # No fallback handlers for mutating, preflight, HEAD, or extension methods.
        if method != "GET":
            return 405, None, method == "HEAD"
        if len(lines) - 1 > MAX_HEADER_COUNT:
            return 431, None, False
        headers = {}
        for line in lines[1:]:
            name, sep, value = line.partition(":")
            name = name.lower()
            if not sep or not _HEADER_NAME.fullmatch(name) or name in headers:
                return 400, None, False
            if any(ord(c) < 32 and c != "\t" or ord(c) == 127 for c in value):
                return 400, None, False
            headers[name] = value.strip(" \t")
        host = headers.get("host", "")
        if host not in self.authorities:
            return 403, None, False
        origin = headers.get("origin")
        if origin is not None and origin != "http://" + host:
            return 403, None, False
        if headers.get("sec-fetch-site") in ("cross-site", "same-site"):
            return 403, None, False
        # No body consumption, request pipelining, chunking or upgrades. One response, close.
        if ("transfer-encoding" in headers or "upgrade" in headers or
                headers.get("content-length", "0") != "0"):
            return 400, None, False
        if target not in ASSETS and target != "/api/snapshot":
            return 404, None, False
        return 200, target, False

    async def _send(self, writer: asyncio.StreamWriter, status: int, body: bytes,
                    content_type: str, head: bool = False) -> None:
        headers = [f"HTTP/1.1 {status} {_REASONS[status]}",
                   f"Content-Length: {len(body)}", f"Content-Type: {content_type}",
                   "Connection: close", "Cache-Control: no-store",
                   "X-Content-Type-Options: nosniff", "X-Frame-Options: DENY",
                   "Referrer-Policy: no-referrer", "Cross-Origin-Resource-Policy: same-origin",
                   "Content-Security-Policy: " + CSP]
        if status == 405:
            headers.append("Allow: GET")
        writer.write(("\r\n".join(headers) + "\r\n\r\n").encode("ascii"))
        if not head:
            writer.write(body)
        await asyncio.wait_for(writer.drain(), self.output_timeout)

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            try:
                status, target, head = await asyncio.wait_for(self._request(reader), self.request_timeout)
            except asyncio.TimeoutError:
                status, target, head = 408, None, False
            content_type = "text/plain; charset=utf-8"
            body = (_REASONS[status] + "\n").encode("ascii")
            if status == 200:
                if target == "/api/snapshot":
                    try:
                        body = self.snapshot_bytes()
                        content_type = "application/json; charset=utf-8"
                    except Exception:
                        status, body = 503, b"Service Unavailable\n"
                elif target in self._assets:
                    body, content_type = self._assets[target]
                else:
                    status, body = 503, b"Service Unavailable\n"
            await self._send(writer, status, body, content_type, head)
        except Exception:
            # No request/path/body or internal diagnostics reach logs or the response.
            pass
        finally:
            writer.close()
            try:
                await asyncio.wait_for(writer.wait_closed(), self.output_timeout)
            except (Exception, asyncio.CancelledError):
                writer.transport.abort()

    async def stop(self) -> None:
        server, self._server = self._server, None
        if server is not None:
            server.close()
        tasks = list(self._clients)
        if self._observer is not None:
            tasks.append(self._observer)
            self._observer = None
        for task in tasks:
            task.cancel()
        for writer in tuple(self._clients.values()):
            writer.transport.abort()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        if server is not None:
            await server.wait_closed()
        self._clients.clear()
        self._assets.clear()
