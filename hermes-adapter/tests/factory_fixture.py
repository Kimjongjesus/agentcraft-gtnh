"""Mock ``aifactory`` telemetry server (protocol ai-factory/v2) for the world.* tests.

A real HTTP server on 127.0.0.1 (ephemeral port) that serves ``/health`` and
``/telemetry/capture`` like the Forge mod, behind the same bearer-token rule, plus failure modes
(401, 503, redirects, oversized and broken bodies, wrong protocol, slow answers). Every name in
it is made up; nothing comes from a real world.
"""

from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

# assembled at runtime so the source holds no token-shaped literal; not a secret
TOKEN = "mock" + "-telemetry-" + "token-0123456789"
T0 = 1_790_000_000_000


def machine(i: int, state: str = "running", *, x: int | None = None, name: str | None = None,
            eu: int = -480, parts: int = 3, problem: str | None = None, **extra: Any) -> dict[str, Any]:
    """One raw multiblock as the mod renders it."""
    x = 100 + i * 4 if x is None else x
    m: dict[str, Any] = {
        "id": f"dim0:multimachine.test{i}@{x},64,-20", "typeKey": f"multimachine.test{i}",
        "name": name or f"Test Machine {i}", "dimensionId": 0, "x": x, "y": 64, "z": -20,
        "formed": state != "unformed", "active": state == "running",
        "needsMaintenance": state == "maintenance", "euPerTick": eu if state == "running" else 0,
        "progressTicks": 40 if state == "running" else 0, "maxProgressTicks": 200 if state == "running" else 0,
        "euStored": 10_000, "euCapacity": 64_000, "efficiencyPercent": 100.0,
        "problem": problem if problem is not None else ("maintenance" if state == "maintenance" else None),
        "maintenanceIssues": ["wrench"] if state == "maintenance" else [],
        "parts": [{"type": "INPUT_BUS", "name": f"Input Bus {p}", "x": x + p, "y": 63, "z": -20, "tier": 3,
                   "meChannelActive": None} for p in range(parts)],
    }
    if state == "unknown":
        m["active"] = None
        m["formed"] = None
        m["needsMaintenance"] = None
    m.update(extra)
    return m


def coverage(status: str = "OK", **kw: Any) -> dict[str, Any]:
    c = {"status": status, "reason": None, "attempted": 10, "succeeded": 10, "errors": 0, "skippedUnloaded": 0,
         "returned": 10, "distinctSeen": 10, "truncated": False, "budgetExhausted": False, "elapsedMillis": 3,
         "complete": status == "OK"}
    c.update(kw)
    return c


def capture(machines: list[dict[str, Any]] | None = None, *, session: str = "sess-a", seq: int = 1,
            captured_at: int | None = None, ae_stored: float = 800_000.0, ae_max: float = 1_000_000.0,
            powered: bool = True, cpus: int = 4, busy: int = 1, players: list[str] | None = None,
            machine_cov: str = "OK", items: list[dict[str, Any]] | None = None,
            session_started: int = T0 - 3_600_000) -> dict[str, Any]:
    ms = machines if machines is not None else [machine(1), machine(2, "idle"), machine(3, "maintenance")]
    its = items if items is not None else [
        {"id": "minecraft:iron_ingot@0", "name": "Iron Ingot", "quantity": 5000, "craftable": False},
        {"id": "gregtech:gt.metaitem.01@11305", "name": "Steel Ingot", "quantity": 1200, "craftable": True},
        {"id": "minecraft:stone@0", "name": "Stone", "quantity": 90000, "craftable": False},
    ]
    return {
        "protocol": "ai-factory/v2", "worldRevision": seq, "capturedAtMillis": captured_at or T0 + seq * 30_000,
        "captureStartedAtMillis": (captured_at or T0 + seq * 30_000) - 400, "captureTicks": 9,
        "captureSequence": seq, "worldName": "Test World", "baseId": "test-base",
        "session": {"id": session, "startedAtMillis": session_started, "modVersion": "1.0.0-test"},
        "scope": {"kind": "CONFIGURED", "label": "Test Base", "identity": "test-base", "dimensionId": 0,
                  "dimension": "Overworld", "centerX": 100, "centerY": 64, "centerZ": -20, "radius": 64,
                  "height": 32, "minY": 48, "maxY": 80, "anchor": None},
        "warnings": [],
        "multiblocks": ms, "machineCoverage": coverage(machine_cov, returned=len(ms), distinctSeen=len(ms)),
        "stock": {"items": its, "power": {"stored": ae_stored, "max": ae_max, "avgUsage": 120.5, "avgInjection": 300.0,
                                          "powered": powered, "unit": "AE"},
                  "crafting": {"cpus": cpus, "busyCpus": busy}, "coverage": coverage(returned=len(its), distinctSeen=len(its))},
        "design": {"stride": 4, "phaseX": 0, "phaseY": 0, "phaseZ": 0, "samplingScheme": "stride lattice",
                   "positionsAttempted": 1000, "blocksSampled": 1000, "solidBlocks": 420, "paletteReturned": 2,
                   "paletteDistinctSeen": 2, "density": 0.42, "dominantShare": 0.6,
                   "palette": [{"id": "minecraft:stonebrick#0", "name": "Stone Bricks", "count": 252},
                               {"id": "minecraft:glass#0", "name": "Glass", "count": 168}],
                   "artificialLight": {"openPositions": 580, "lit": 500, "unlit": 80, "unlitFraction": 0.1379,
                                       "minLight": 0, "maxLight": 15, "blockLightSpawnFloor": 8, "meaning": "x"},
                   "coverage": coverage()},
        "surroundings": {"worldTime": 6000, "totalWorldTime": 99999, "raining": False, "thundering": False,
                         "biomeAtCenter": "Plains", "playersOnline": 1, "hostileCount": 0,
                         "playersInScope": players if players is not None else ["PlayerOne"], "entityCounts": {},
                         "coverage": coverage()},
    }


class MockTelemetry:
    """Threaded HTTP server; ``mode`` switches the failure behaviour of telemetry routes."""

    def __init__(self, token: str = TOKEN) -> None:
        self.token = token
        self.capture: dict[str, Any] | None = None
        self.mode = "ok"
        self.redirect_to = ""
        self.requests: list[tuple[str, str | None]] = []
        self.health_extra: dict[str, Any] = {}
        mock = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a: Any) -> None:
                pass

            def do_POST(self) -> None:
                mock.requests.append((self.path, self.headers.get("Authorization")))
                self._send(405, {"protocol": "ai-factory/v2", "error": "method not allowed"})

            def do_GET(self) -> None:
                mock.requests.append((self.path, self.headers.get("Authorization")))
                mode = mock.mode
                if mode == "redirect":
                    self.send_response(302)
                    self.send_header("Location", mock.redirect_to)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if self.headers.get("Authorization") != f"Bearer {mock.token}" or mode == "401":
                    self._send(401, {"protocol": "ai-factory/v2", "error": "unauthorized"})
                    return
                if mode == "slow":
                    time.sleep(3)
                if self.path == "/health":
                    self._send(200, mock.health())
                elif self.path == "/telemetry/capture":
                    if mode == "busy":
                        self._send(503, {"protocol": "ai-factory/v2", "error": "server busy"})
                    elif mock.capture is None:
                        self._send(503, {"protocol": "ai-factory/v2", "error": "no capture yet"})
                    elif mode == "huge":
                        self._raw(200, b'{"pad":"' + b"x" * (5 * 1024 * 1024) + b'"}')
                    elif mode == "chunked":
                        self._chunked(b"x" * (5 * 1024 * 1024))
                    elif mode == "drip":
                        self._drip(b'{"protocol": "ai-factory/v2"}' + b" " * 200)
                    elif mode == "badjson":
                        self._raw(200, b'{"protocol": "ai-factory/v2", ')
                    elif mode == "deep":
                        self._raw(200, b"[" * 100_000 + b"]" * 100_000)
                    else:
                        self._send(200, mock.capture)
                else:
                    self._send(404, {"protocol": "ai-factory/v2", "error": "not found"})

            def _send(self, status: int, body: Any) -> None:
                self._raw(status, json.dumps(body).encode())

            def _chunked(self, data: bytes) -> None:
                """No Content-Length: the size cap must hold while streaming."""
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                try:
                    for i in range(0, len(data), 65536):
                        part = data[i:i + 65536]
                        self.wfile.write(f"{len(part):x}\r\n".encode() + part + b"\r\n")
                    self.wfile.write(b"0\r\n\r\n")
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def _drip(self, data: bytes) -> None:
                """Valid headers, then one byte every 0.2 s: only a total deadline stops this."""
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    for b in data:
                        self.wfile.write(bytes([b]))
                        self.wfile.flush()
                        time.sleep(0.2)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def _raw(self, status: int, data: bytes) -> None:
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def health(self) -> dict[str, Any]:
        c = self.capture
        h = {"protocol": "v1-old" if self.mode == "v1" else "ai-factory/v2", "status": "ok",
             "nowMillis": (c or {}).get("capturedAtMillis", T0) + 1000, "lastTickMillis": T0,
             "lastTickAgeMillis": 50, "lastCaptureMillis": c["capturedAtMillis"] if c else None,
             "captureSequence": c["captureSequence"] if c else 0, "httpRejected": 0, "gregTech": True, "ae2": True,
             "enabledOperations": ["TELEMETRY_SNAPSHOT"]}
        h.update(self.health_extra)
        return h

    def telemetry_requests(self) -> int:
        return sum(1 for p, _ in self.requests if p.startswith("/telemetry/"))

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
