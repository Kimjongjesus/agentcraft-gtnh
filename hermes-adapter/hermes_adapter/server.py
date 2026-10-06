"""AgentCraft "Foreman"-side server backed by read-only Hermes state.

hello -> snapshot -> incremental upserts, exactly like foreman/src/server.ts, but every client
intent that would change something is refused with ``ack {ok:false}``: this adapter never writes
to Hermes (no kanban writes, no dispatches, no answers).
"""

from __future__ import annotations

import asyncio
import ipaddress
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Callable

from .mapping import Mapper
from .sources import HermesData
from .wsserver import UpgradeRequest, WSClosed, WSConnection, serve

log = logging.getLogger("hermes_adapter")

PROTOCOL_VERSION = 1
MUTATING = {"goal.submit", "user.message", "decision.answer", "task.action", "agent.action", "repo.add"}
READ_ONLY_ERROR = "read-only Hermes view: {type} is disabled (change things in Hermes, not in-game)"
LOOPBACK_HOSTS = ("127.0.0.1", "localhost", "[::1]", "::1")


@dataclass
class AccessPolicy:
    allowed_hosts: set[str] = field(default_factory=lambda: set(LOOPBACK_HOSTS))
    allowed_peers: list[ipaddress._BaseNetwork] = field(
        default_factory=lambda: [ipaddress.ip_network("127.0.0.0/8"), ipaddress.ip_network("::1/128")]
    )

    @staticmethod
    def host_only(host_header: str) -> str:
        h = host_header.strip().lower()
        if h.startswith("["):
            return h[: h.find("]") + 1] if "]" in h else h
        return h.rsplit(":", 1)[0] if h.count(":") == 1 else h

    def peer_ok(self, peer: str) -> bool:
        try:
            ip = ipaddress.ip_address(peer)
        except ValueError:
            return False
        if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        return any(ip in net for net in self.allowed_peers)

    def refuse_reason(self, req: UpgradeRequest) -> str | None:
        # any Origin header (also "null") means a browser page; the mod and CLI tools send none
        if "origin" in req.headers:
            return f"browser origin {req.headers['origin'] or '(empty)'}"
        host = self.host_only(req.headers.get("host", ""))
        if host not in self.allowed_hosts:
            return f"Host header {host or '(none)'} not in allowlist"
        if not self.peer_ok(req.peer):
            return f"peer {req.peer} not in allowlist"
        return None


def diff_entities(prev: list[dict[str, Any]], cur: list[dict[str, Any]], key: str = "id") -> list[dict[str, Any]]:
    """Entities in ``cur`` that are new or changed vs ``prev`` (by id)."""
    before = {e[key]: e for e in prev}
    return [e for e in cur if before.get(e[key]) != e]


class AdapterServer:
    def __init__(
        self,
        read: Callable[[], HermesData],
        mapper: Mapper,
        host: str = "127.0.0.1",
        port: int = 7878,
        policy: AccessPolicy | None = None,
        poll_interval: float = 3.0,
        max_clients: int = 8,
        ping_interval: float = 15.0,
    ) -> None:
        self.read = read
        self.mapper = mapper
        self.host = host
        self.port = port
        self.policy = policy or AccessPolicy()
        self.poll_interval = poll_interval
        self.max_clients = max_clients
        self.ping_interval = ping_interval
        self.model: dict[str, Any] | None = None
        self.clients: set[WSConnection] = set()
        self.hello: set[WSConnection] = set()
        self._server: asyncio.base_events.Server | None = None
        self._tasks: list[asyncio.Task[Any]] = []
        self.polls = 0
        self.last_error: str | None = None

    # ---- model ---------------------------------------------------------------------------
    async def refresh(self) -> list[dict[str, Any]]:
        """Re-read Hermes and return the incremental messages (also applied to self.model)."""
        loop = asyncio.get_running_loop()
        data = await loop.run_in_executor(None, self.read)
        new = self.mapper.build(data)
        self.polls += 1
        msgs = self.changes(self.model, new) if self.model is not None else []
        self.model = new
        return msgs

    @staticmethod
    def changes(old: dict[str, Any], new: dict[str, Any]) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        if old["foreman"] != new["foreman"]:
            out.append({"type": "foreman.status", "status": new["foreman"]})
        for a in diff_entities(old["agents"], new["agents"]):
            out.append({"type": "agent.upsert", "agent": a})
        # tasks that fell out of the window/board are kept for history as cancelled
        new_ids = {t["id"] for t in new["tasks"]}
        for t in old["tasks"]:
            if t["id"] not in new_ids and t["status"] != "cancelled":
                gone = dict(t, status="cancelled")
                new["tasks"].append(gone)
        for t in diff_entities(old["tasks"], new["tasks"]):
            out.append({"type": "task.upsert", "task": t})
        for d in diff_entities(old["decisions"], new["decisions"]):
            out.append({"type": "decision.upsert", "decision": d})
        for g in diff_entities(old["goals"], new["goals"]):
            out.append({"type": "goal.upsert", "goal": g})
        # library entries that dropped out (decision answered, note aged out of the window) are
        # replaced by a tombstone: same id, empty body, "removed": true (an extra field; upstream
        # receivers ignore it and show an empty note). Kept in the model for one poll, like the
        # cancelled tasks above, so it is sent exactly once and never appears in a snapshot.
        new_mem = {e["id"] for e in new["memory"]}
        for e in old["memory"]:
            if e["id"] not in new_mem and not e.get("removed"):
                new["memory"].append({"id": e["id"], "scope": e["scope"], "title": e["title"], "body": "",
                                      "updated": e["updated"], "removed": True})
        for e in diff_entities(old["memory"], new["memory"]):
            out.append({"type": "memory.upsert", "entry": e})
        seen_feed = {(f["ts"], f["text"]) for f in old["feed"]}
        last_ts = max((f["ts"] for f in old["feed"]), default=0)
        for f in new["feed"]:
            if (f["ts"], f["text"]) not in seen_feed and f["ts"] >= last_ts:
                out.append({"type": "feed.add", "item": f})
        old_logs = {l["agentId"]: {(e["ts"], e["text"]) for e in l["entries"]} for l in old["logs"]}
        old_last = {l["agentId"]: max((e["ts"] for e in l["entries"]), default=0) for l in old["logs"]}
        for l in new["logs"]:
            seen = old_logs.get(l["agentId"], set())
            fresh = [e for e in l["entries"] if (e["ts"], e["text"]) not in seen and e["ts"] >= old_last.get(l["agentId"], 0)]
            if fresh:
                out.append({"type": "agent.log", "agentId": l["agentId"], "entries": fresh})
        return out

    def snapshot(self) -> dict[str, Any]:
        assert self.model is not None
        m = self.model
        snap = {"type": "snapshot", **{k: m[k] for k in ("foreman", "agents", "tasks", "decisions", "repos", "memory", "goals", "feed", "logs")}}
        snap["memory"] = [e for e in m["memory"] if not e.get("removed")]
        if m["goals"]:
            # protocol: the current goal; with one goal per board that is the most recently active board
            snap["goal"] = max(m["goals"], key=lambda g: (g["updatedAt"], g["id"]))
        return snap

    # ---- wire ----------------------------------------------------------------------------
    @staticmethod
    def encode(msg: dict[str, Any]) -> str:
        return json.dumps({"v": PROTOCOL_VERSION, **msg}, separators=(",", ":"), ensure_ascii=False)

    async def send(self, conn: WSConnection, msg: dict[str, Any]) -> None:
        try:
            await conn.send_text(self.encode(msg))
        except (WSClosed, ConnectionError, RuntimeError):
            self._drop(conn)

    async def broadcast(self, msgs: list[dict[str, Any]]) -> None:
        for msg in msgs:
            for conn in list(self.hello):
                await self.send(conn, msg)

    def _drop(self, conn: WSConnection) -> None:
        self.clients.discard(conn)
        self.hello.discard(conn)

    async def handle_message(self, conn: WSConnection, raw: str) -> None:
        if raw == "\x00binary":
            await self.send(conn, {"type": "error", "message": "binary frames are not supported"})
            return
        try:
            msg = json.loads(raw)
            if not isinstance(msg, dict) or not isinstance(msg.get("type"), str):
                raise ValueError("not an object with a type")
        except ValueError as e:
            await self.send(conn, {"type": "error", "message": f"bad message: {e}"})
            return
        mtype = msg["type"]
        mid = msg.get("id") if isinstance(msg.get("id"), str) else None
        if mtype == "hello":
            self.hello.add(conn)
            log.info("hello from %s (%s %s)", conn.request.peer, str(msg.get("client", "client"))[:20], str(msg.get("modVersion", "?"))[:40])
            await self.send(conn, self.snapshot())
            if mid:
                await self.send(conn, {"type": "ack", "re": mid, "ok": True})
            return
        if conn not in self.hello:  # lenient like upstream: first message counts as hello
            self.hello.add(conn)
        if mtype in MUTATING:
            err = READ_ONLY_ERROR.format(type=mtype)
            log.info("refused %s from %s (read-only)", mtype, conn.request.peer)
            if mid:
                await self.send(conn, {"type": "ack", "re": mid, "ok": False, "error": err})
            else:
                await self.send(conn, {"type": "error", "message": err})
            return
        if mtype == "diff.request":
            req_id = str(msg.get("requestId", ""))[:64]
            await self.send(conn, {
                "type": "diff", "requestId": req_id, "repoId": str(msg.get("repoId", ""))[:64],
                "worktree": str(msg.get("worktree", ""))[:64], "files": [],
                "stats": {"files": 0, "additions": 0, "deletions": 0}, "truncated": False,
                "error": "diffs are not available in the read-only Hermes view",
            })
            if mid:
                await self.send(conn, {"type": "ack", "re": mid, "ok": False, "error": "diffs not available"})
            return
        err = f"unknown message type {mtype[:40]}"
        await self.send(conn, {"type": "error", "message": err, **({"re": mid} if mid else {})})
        if mid:
            await self.send(conn, {"type": "ack", "re": mid, "ok": False, "error": err})

    async def on_connection(self, conn: WSConnection) -> None:
        if len(self.clients) >= self.max_clients:
            await conn.close(1013, "too many clients")
            return
        self.clients.add(conn)
        log.info("client connected from %s", conn.request.peer)
        try:
            while True:
                raw = await conn.recv()
                await self.handle_message(conn, raw)
        except (WSClosed, asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            self._drop(conn)
            log.info("client %s disconnected", conn.request.peer)

    def _on_reject(self, req: UpgradeRequest | None, why: str) -> None:
        log.warning("rejected WebSocket: %s", why)

    # ---- lifecycle -----------------------------------------------------------------------
    async def poll_loop(self) -> None:
        while True:
            await asyncio.sleep(self.poll_interval)
            try:
                msgs = await self.refresh()
                self.last_error = None
            except Exception as e:  # keep serving the last good model
                if str(e) != self.last_error:
                    log.error("refresh failed: %s", e)
                self.last_error = str(e)
                continue
            if msgs:
                log.debug("broadcast %d message(s)", len(msgs))
                await self.broadcast(msgs)

    async def ping_loop(self) -> None:
        while True:
            await asyncio.sleep(self.ping_interval)
            for conn in list(self.clients):
                try:
                    await conn.ping()
                except (WSClosed, ConnectionError, RuntimeError):
                    self._drop(conn)

    async def start(self) -> int:
        await self.refresh()
        self._server = await serve(self.host, self.port, self.on_connection, self.policy.refuse_reason, self._on_reject, max_message=64 * 1024)
        sock = self._server.sockets[0].getsockname() if self._server.sockets else (self.host, self.port)
        self.port = sock[1]
        self._tasks = [asyncio.create_task(self.poll_loop()), asyncio.create_task(self.ping_loop())]
        log.info("listening on ws://%s:%d (hosts=%s peers=%s)", self.host, self.port, sorted(self.policy.allowed_hosts), [str(p) for p in self.policy.allowed_peers])
        return self.port

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        for conn in list(self.clients):
            await conn.close(1001, "adapter shutting down")
        if self._server:
            self._server.close()
            await self._server.wait_closed()


def now_ms() -> int:
    return int(time.time() * 1000)
