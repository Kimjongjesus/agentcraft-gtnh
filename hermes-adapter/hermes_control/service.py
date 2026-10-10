"""The control service: handshake, receiver checks in contract order, admission, executors, results.

One asyncio process. Every frame goes through the same pipeline (docs/action-protocol.md 2.2):

    size -> outer shape -> signature -> payload shape / type / session / dir -> ts window and
    "not before this process started" -> nonce (ledger; never evicted early; full = refuse) -> clock

and only then reaches a handler. Handlers check actor, capability, tier, enabled flag, arguments,
board data, lock, limits and idempotency; the admission record is on disk (fsync) before an
executor is called; the lock is checked again right before the executor call.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import hmac
import logging
import secrets
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from hermes_adapter import redact
from hermes_adapter.server import AccessPolicy
from hermes_adapter.wsserver import UpgradeRequest, WSClosed, WSConnection, serve

from . import PROTOCOL_VERSION, classify, executors, frames, ledger as ledger_mod, policy as pol
from . import board as board_mod
from .audit import Audit, AuditError
from .lock import LockFile, set_lock

log = logging.getLogger("hermes_control")

MAX_CONNECTIONS = 2
HELLO_TIMEOUT_S = 10.0
SEND_TIMEOUT_S = 10.0
FRAMES_PER_10S = 120
BAD_FRAMES_BEFORE_CLOSE = 8
PROMPT_LIMIT = {"perHour": 12}
CHAT_FRAME = 1800
CHAT_REPLY_CAP = 6000
STATE_POLL_S = 1.0
PING_S = 15.0  # the game side's socket read timeout is 60 s


class Refuse(Exception):
    """A request that is refused with a short, generic-enough message (sent as action.result)."""


class Locked(Exception):
    pass


class FrameRefused(Exception):
    def __init__(self, reason: str, public: str, authenticated: bool, close: bool = False) -> None:
        super().__init__(reason)
        self.reason, self.public, self.authenticated, self.close = reason, public, authenticated, close


@dataclass(eq=False)
class Conn:
    ws: WSConnection
    session: str
    challenge: str
    state: str = "hello"  # hello -> ready
    seq: int = 0
    bad: int = 0
    stamps: list[float] = field(default_factory=list)
    tasks: set[asyncio.Task[Any]] = field(default_factory=set)
    closed: bool = False

    @property
    def peer(self) -> str:
        return self.ws.request.peer


@dataclass
class Pending:
    """A dispatch waiting for its confirm (memory side of the ledger token row)."""

    conn: Conn
    actor_uuid: str
    actor_name: str
    req_id: str
    args: dict[str, Any]
    prep: dict[str, Any]
    digest: str
    card_rev: str
    policy_rev: str
    expires_ms: int


def validate_bind(host: str, insecure_lan_bind: bool) -> None:
    """Loopback by default; anything else needs --insecure-lan-bind; wildcards are always refused."""
    import ipaddress

    h = (host or "").strip()
    if h in ("", "*", "0.0.0.0", "::", "[::]", "0", "0:0:0:0:0:0:0:0") or h.lower() in ("any", "all"):
        raise ValueError("wildcard binds are refused")
    if h.lower() == "localhost":
        return
    try:
        ip = ipaddress.ip_address(h.strip("[]"))
    except ValueError:
        raise ValueError("bind address must be an IP address literal (or localhost)") from None
    if ip.is_unspecified:
        raise ValueError("wildcard binds are refused")
    if not ip.is_loopback and not insecure_lan_bind:
        raise ValueError("non-loopback bind needs --insecure-lan-bind (plaintext; prefer an SSH or VPN tunnel)")


class ControlService:
    def __init__(
        self,
        *,
        key: bytes,
        policy: pol.Policy,
        policy_path: Path | None,
        ledger: ledger_mod.Ledger,
        audit: Audit,
        lock: LockFile,
        reader: board_mod.BoardReader,
        executor_map: dict[str, executors.Executor],
        clock: Callable[[], int] | None = None,
        dry_run: bool = False,
        hermes_lock_by: str = "game",
        access: AccessPolicy | None = None,
        host: str = "127.0.0.1",
        port: int = 7879,
        max_connections: int = MAX_CONNECTIONS,
    ) -> None:
        self.key = key
        self.policy = policy
        self.policy_path = policy_path
        self.ledger = ledger
        self.audit = audit
        self.lock = lock
        self.reader = reader
        self.executors = executor_map
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.dry_run = dry_run
        self.hermes_lock_by = hermes_lock_by
        self.access = access or AccessPolicy()
        self.host, self.port = host, port
        self.max_connections = max_connections
        self.reload_n = 0
        self.revision = self._revision()
        self.conns: set[Conn] = set()
        self.pending: dict[str, Pending] = {}
        self.frozen: str | None = None
        self._last_seen = self.clock()
        self._last_touch = 0.0
        self._last_lock_state = self.lock.state()
        self._server: asyncio.base_events.Server | None = None
        self._tasks: list[asyncio.Task[Any]] = []
        self._pool = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="control-exec")
        self._out_seq = 0
        self._boot = secrets.token_hex(3)  # outgoing ids are unique per process start: c<boot>.<n>
        self.bad_frame_limit = BAD_FRAMES_BEFORE_CLOSE

    # ---- small helpers ------------------------------------------------------------------------
    def _revision(self) -> str:
        return f"{self.policy.file_hash[:12] or 'inline'}.{self.reload_n}"

    def now(self) -> int:
        return self.clock()

    def armed(self) -> bool:
        return self.frozen is None and not self.lock.is_locked()

    def state_body(self) -> dict[str, Any]:
        st = self.lock.state()
        return {"armed": self.frozen is None and not st.locked, "locked": st.locked or self.frozen is not None,
                "lockReason": (st.reason if st.locked else (self.frozen or ""))[:200], "lockedBy": (st.by if st.locked else "system")[:64],
                "since": st.since if st.locked else 0}

    # ---- sending ------------------------------------------------------------------------------
    async def send(self, conn: Conn, type_: str, fields: dict[str, Any]) -> bool:
        if conn.closed:
            return False
        self._out_seq += 1
        conn.seq += 1
        body = {"type": type_, "session": conn.session, "dir": "c2g", "id": f"c{self._boot}.{self._out_seq}", "nonce": secrets.token_hex(16), "ts": self.now(), **fields}
        try:
            frames.check_payload(body, type_)
        except frames.FrameError as e:  # a bug on our side must never put a malformed frame on the wire
            log.error("refusing to send malformed %s: %s", type_, e.reason)
            return False
        try:
            await asyncio.wait_for(conn.ws.send_text(frames.encode_frame(self.key, type_, body)), SEND_TIMEOUT_S)
            return True
        except (asyncio.TimeoutError, WSClosed, ConnectionError, RuntimeError):
            await self._close(conn, 1011, "send failed")
            return False

    async def send_error(self, conn: Conn, re_id: str, text: str) -> None:
        await self.send(conn, "error", {"re": re_id[:64], "error": text[:200]})

    async def result(self, conn: Conn, re_id: str, status: str, error: str = "", result: dict[str, str] | None = None, audit: str = "") -> None:
        await self.send(conn, "action.result", {
            "re": re_id, "status": status, "error": redact.clean(error, 200), "result": executors._result(result or {}),
            "audit": audit[:64], "dryRun": self.dry_run,
        })

    async def broadcast_state(self) -> None:
        for c in list(self.conns):
            if c.state == "ready":
                await self.send(c, "action.state", self.state_body())

    async def broadcast_policy(self) -> None:
        for c in list(self.conns):
            if c.state == "ready":
                await self.send(c, "action.policy", self.policy.wire(self.revision, self.dry_run))
                await self.send(c, "action.state", self.state_body())

    async def _close(self, conn: Conn, code: int = 1008, reason: str = "closing") -> None:
        if conn.closed:
            return
        conn.closed = True
        self.drop_tokens(conn)
        try:
            await conn.ws.close(code, reason)
        except Exception:  # noqa: BLE001
            pass

    # ---- tokens -------------------------------------------------------------------------------
    def drop_tokens(self, conn: Conn | None = None) -> None:
        """Void confirm tokens: one connection's (closed) or all (cancel/lock/reload)."""
        try:
            self.ledger.drop_tokens(conn.session if conn else None)
        except Exception:  # noqa: BLE001 - never let cleanup raise
            log.error("could not drop tokens in the ledger")
        for k in [k for k, p in self.pending.items() if conn is None or p.conn is conn]:
            p = self.pending.pop(k)
            try:
                self.ledger.set_claim_state(p.actor_uuid, p.req_id, ledger_mod.REFUSED, "cancelled", "confirm voided", self.now())
            except Exception:  # noqa: BLE001
                pass

    # ---- clock --------------------------------------------------------------------------------
    def check_clock(self) -> bool:
        """False (and frozen for good) when the wall clock moved backwards by more than the window."""
        if self.frozen:
            return False
        now = self.now()
        if now < self._last_seen - frames.SKEW_MS:
            self.frozen = "clock moved backwards; restart the service"
            self.audit.try_write("clock-jump", now=now, last_seen=self._last_seen)
            self.drop_tokens(None)
            return False
        self._last_seen = max(self._last_seen, now)
        t = time.monotonic()
        if t - self._last_touch >= 1.0:
            self._last_touch = t
            try:
                self.ledger.touch(self._last_seen)
            except Exception:  # noqa: BLE001
                pass
        return True

    # ---- receiver pipeline --------------------------------------------------------------------
    def receive(self, conn: Conn, raw: str) -> tuple[str, dict[str, Any]]:
        """Contract section 2.2, steps 2..7. Returns (type, payload) or raises FrameRefused."""
        if len(raw.encode("utf-8")) > frames.MAX_MESSAGE:
            raise FrameRefused("message too big", "too big", False, close=True)
        try:
            outer = frames.parse_outer(raw)
        except frames.FrameError as e:
            raise FrameRefused(f"outer: {e.reason}", "bad frame", False) from None
        if not frames.verify(self.key, outer["payload"], outer["sig"]):
            raise FrameRefused("bad signature", "bad signature", False)
        try:
            obj = frames.strict_loads(outer["payload"])
            if not isinstance(obj, dict):
                raise frames.FrameError("payload not an object")
            t = outer["type"]
            frames.check_payload(obj, t)
        except frames.FrameError as e:
            raise FrameRefused(f"payload: {e.reason}", "bad frame", True) from None
        if frames.TYPES[t][0] != "g2c":
            raise FrameRefused("wrong direction for type", "bad frame", True)
        if not hmac.compare_digest(obj["session"], conn.session):
            raise FrameRefused("wrong session", "bad session", True)
        now = self.now()
        if abs(now - obj["ts"]) > frames.SKEW_MS:
            raise FrameRefused("ts outside the window", "stale", True)
        if obj["ts"] < self.ledger.start_ms:
            raise FrameRefused("ts before service start", "stale", True)
        verdict = self.ledger.take_nonce(obj["nonce"], obj["ts"], now)
        if verdict == "replay":
            raise FrameRefused("nonce replay", "replay", True)
        if verdict == "full":
            raise FrameRefused("nonce cache full", "busy", True)
        return t, obj

    # ---- connection ---------------------------------------------------------------------------
    async def on_connection(self, ws: WSConnection) -> None:
        if len(self.conns) >= self.max_connections:
            self.audit.unauthenticated("too many connections", ws.request.peer)
            await ws.close(1013, "too many connections")
            return
        conn = Conn(ws, secrets.token_hex(16), secrets.token_hex(16))
        self.conns.add(conn)
        try:
            if not await self.send(conn, "action.challenge", {"challenge": conn.challenge}):
                return
            while not conn.closed:
                timeout = HELLO_TIMEOUT_S if conn.state == "hello" else None
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout)
                except asyncio.TimeoutError:
                    self.audit.try_write("handshake-timeout", peer=conn.peer)
                    await self._close(conn, 1008, "no hello")
                    return
                await self.handle(conn, raw)
        except (WSClosed, asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            conn.closed = True
            self.conns.discard(conn)
            self.drop_tokens(conn)

    async def handle(self, conn: Conn, raw: str) -> None:
        t0 = time.monotonic()
        conn.stamps = [s for s in conn.stamps if t0 - s < 10.0] + [t0]
        if len(conn.stamps) > FRAMES_PER_10S:
            self.audit.unauthenticated("frame flood", conn.peer)
            await self._close(conn, 1008, "too many frames")
            return
        if raw == "\x00binary":
            self.audit.unauthenticated("binary frame", conn.peer)
            await self._close(conn, 1003, "text only")
            return
        if not self.check_clock():
            self.audit.unauthenticated("clock frozen", conn.peer)
            if conn.state == "hello":
                await self._close(conn, 1008, "restart required")
            else:
                await self.send_error(conn, "", "service clock error: restart required")
            return
        try:
            t, p = self.receive(conn, raw)
        except FrameRefused as e:
            if e.authenticated:
                self.audit.try_write("refused-frame", reason=e.reason, peer=conn.peer)
            else:
                self.audit.unauthenticated(e.reason, conn.peer)
            conn.bad += 1
            if conn.state == "hello" or e.close or conn.bad >= self.bad_frame_limit:
                await self._close(conn, 1008, e.public)
            else:
                await self.send_error(conn, "", e.public)
            return
        try:
            if conn.state == "hello":
                await self.on_hello(conn, t, p)
            elif t == "hello":
                self.audit.try_write("second-hello", peer=conn.peer)
                await self._close(conn, 1008, "second hello")
            elif t == "action.request":
                await self.on_request(conn, p)
            elif t == "action.confirm":
                await self.on_confirm(conn, p)
            elif t == "action.cancel":
                await self.on_cancel(conn, p)
            elif t == "action.lock":
                await self.on_lock(conn, p)
            else:
                await self.send_error(conn, p["id"], "unexpected message")
        except (WSClosed, ConnectionError):
            raise
        except Exception as e:  # noqa: BLE001 - one bad frame must never take the service down
            log.error("handler crashed: %s", type(e).__name__)
            self.audit.try_write("handler-error", error=type(e).__name__)
            await self.send_error(conn, p.get("id", ""), "internal error")

    # ---- handshake ----------------------------------------------------------------------------
    async def on_hello(self, conn: Conn, t: str, p: dict[str, Any]) -> None:
        if t != "hello" or not hmac.compare_digest(p["challenge"], conn.challenge) or "action" not in p["features"]:
            self.audit.try_write("handshake-refused", peer=conn.peer, type=t)
            await self._close(conn, 1008, "handshake refused")
            return
        conn.state = "ready"
        self.audit.try_write("hello", peer=conn.peer, revision=self.revision, dryRun=self.dry_run)
        await self.send(conn, "ack", {"result": {"features": ["action"]}})
        await self.send(conn, "action.policy", self.policy.wire(self.revision, self.dry_run))
        await self.send(conn, "action.state", self.state_body())

    # ---- requests -----------------------------------------------------------------------------
    def _stored_reply(self, row: dict[str, Any]) -> tuple[str, str, dict[str, str], str]:
        s = row["state"]
        if s == ledger_mod.PENDING:
            return "queued", "request in progress", {}, row.get("audit", "")
        if s == ledger_mod.PROMPTED:
            return "prompted", "", {}, row.get("audit", "")
        return row["status"] or "unknown", row["error"], row["result"], row["audit"]

    async def on_request(self, conn: Conn, p: dict[str, Any]) -> None:
        rid = p["id"]
        actor = p["actor"]
        auuid, aname = actor["uuid"], actor["name"]
        cap = p["capability"]
        pl = self.policy

        async def refuse(msg: str, **kw: Any) -> None:
            aid = self.audit.try_write("request", req=rid, actor=auuid, name=aname, capability=cap, tier=p["tier"], decision="refused", reason=msg, **kw)
            await self.result(conn, rid, "refused", msg, None, aid)

        if cap not in frames.ARGS:
            return await refuse("unknown capability")
        tier, confirm, _ = frames.ARGS[cap]
        if p["tier"] != tier:
            return await refuse("tier mismatch")
        if auuid not in pl.actors:
            return await refuse("actor not allowed")
        cp = pl.cap(cap)
        if not cp.enabled:
            return await refuse("capability disabled")
        try:
            args = frames.check_args(cap, p["args"])
        except frames.FrameError as e:
            return await refuse(f"bad arguments ({e.reason})")
        dig = frames.digest(cap, tier, args)
        stored = self.ledger.get_claim(auuid, rid)
        if stored is not None:
            if stored["digest"] != dig:
                return await refuse("request id reused with a different request")
            status, err, res, aid = self._stored_reply(stored)
            self.audit.try_write("request", req=rid, actor=auuid, capability=cap, decision="replay", stored=status)
            return await self.result(conn, rid, status, err, res, aid)
        if not self.check_clock():
            return await refuse("service clock error: restart required")
        try:
            prep, target = self.prepare(cap, cp, args)
        except Refuse as e:
            return await refuse(str(e))
        if self.lock.is_locked():
            return await refuse("write lock is set")
        now = self.now()
        if confirm:
            why = self.ledger.limit_peek(cap, target, cp.limits, now)
            if why:
                return await refuse(why)
            if self.ledger.open_tokens(auuid) >= ledger_mod.MAX_OPEN_TOKENS:
                return await refuse("too many open confirms")
            outcome, row = self.ledger.claim(auuid, rid, dig, cap, now, state=ledger_mod.PROMPTED, limits=PROMPT_LIMIT, limit_cap="card.dispatch.prompt")
        else:
            outcome, row = self.ledger.claim(auuid, rid, dig, cap, now, state=ledger_mod.PENDING, target=target, limits=cp.limits)
        if outcome == "limit":
            return await refuse(str((row or {}).get("error", "rate limit")))
        if outcome == "conflict":
            return await refuse("request id reused with a different request")
        if outcome == "same":
            assert row is not None
            status, err, res, aid = self._stored_reply(row)
            return await self.result(conn, rid, status, err, res, aid)
        # claimed. No audit, no action:
        try:
            aid = self.audit.write("request", req=rid, actor=auuid, name=aname, capability=cap, tier=tier, decision="prompted" if confirm else "admitted",
                                   args=self.audit_args(cap, args), digest=dig[:16], revision=self.revision, dryRun=self.dry_run)
        except AuditError:
            self.ledger.finish(auuid, rid, ledger_mod.REFUSED, "refused", "audit log unavailable", {}, "", self.now())
            return await self.result(conn, rid, "refused", "audit log unavailable")
        req = executors.ExecRequest(cap, args, auuid, aname, rid, pl, prep)
        if confirm:
            await self.prompt(conn, p, req, dig, aid)
            return
        task = asyncio.create_task(self.run_executor(conn, req, dig, aid))
        conn.tasks.add(task)
        task.add_done_callback(conn.tasks.discard)

    @staticmethod
    def audit_args(cap: str, args: dict[str, Any]) -> dict[str, Any]:
        out = dict(args)
        for k in ("text", "comment", "body"):
            if k in out and isinstance(out[k], str):
                out[k] = out[k][:300]
        return out

    def prepare(self, cap: str, cp: pol.CapPolicy, args: dict[str, Any]) -> tuple[dict[str, Any], str | None]:
        """Allowlists + board data. Returns (prep for the executor, rate-limit target). Raises Refuse."""
        pl = self.policy
        if cap == "decision.answer":
            card = board_mod.locate(self.reader, cp.boards, args["card"])
            if card is None:
                raise Refuse("card not found on an allowed board")
            dec = self.reader.decision(card["board"], card["id"], args["decision"])
            if dec is None:
                raise Refuse("that decision is not open")
            cls = classify.classify(dec["reason"], dec.get("blockKind"))
            ans = classify.resolve_answer(cls, args.get("choice"), args.get("text"), cp.handoff_answerable)
            if not ans.ok:
                raise Refuse(ans.error)
            # defence in depth: a permission halt can only ever be answered with Deny (policy cannot change this)
            if cls.kind == classify.PERMISSION and classify.norm(ans.choice) != "deny":
                raise Refuse("permission halts can only be denied from the game")
            return {"board": card["board"], "card": card["id"], "choice": ans.choice, "note": ans.note, "kind": cls.kind}, None
        if cap == "card.create":
            if args["board"] not in cp.boards:
                raise Refuse("board not allowed")
            if args["title"].lstrip().startswith("-"):
                raise Refuse("title must not start with '-'")
            return {}, None
        if cap == "card.edit":
            card = board_mod.locate(self.reader, cp.boards, args["card"])
            if card is None:
                raise Refuse("card not found on an allowed board")
            if "comment" not in args and board_mod.is_running(card):
                raise Refuse("card is running: only a comment is allowed")
            return {"board": card["board"], "card": card["id"]}, None
        if cap in ("agent.chat", "agent.ask"):
            if args["agent"] not in cp.agents:
                raise Refuse("agent not allowed")
            if not cp.toolsets:
                raise Refuse("chat has no enforced toolset in the policy")
            return {}, None
        if cap == "card.dispatch":
            if args["board"] not in cp.boards:
                raise Refuse("board not allowed")
            if args["profile"] not in cp.profiles:
                raise Refuse("profile not allowed")
            card = board_mod.locate(self.reader, cp.boards, args["card"], board=args["board"])
            if card is None:
                raise Refuse("card not found on that board")
            if not board_mod.dispatchable(card):
                raise Refuse("card is not dispatchable (needs status todo/ready, not running, not blocked)")
            return {"board": card["board"], "card": card["id"], "card_rev": card["revision"], "card_view": card}, f"{card['board']}:{card['id']}"
        if cap == "service.restart":
            if args["service"] not in pl.services:
                raise Refuse("service not allowed")
            return {}, args["service"]
        if cap == "cron.run":
            if args["job"] not in pl.jobs:
                raise Refuse("job not allowed")
            return {}, args["job"]
        raise Refuse("unknown capability")

    # ---- dispatch: prompt / confirm / cancel --------------------------------------------------
    async def prompt(self, conn: Conn, p: dict[str, Any], req: executors.ExecRequest, dig: str, aid: str) -> None:
        token = secrets.token_hex(16)
        now = self.now()
        expires = now + ledger_mod.TOKEN_TTL_MS
        card_rev = req.prep["card_rev"]
        self.ledger.put_token(token, conn.session, req.actor_uuid, req.req_id, dig, card_rev, self.revision, expires)
        self.pending[token] = Pending(conn, req.actor_uuid, req.actor_name, req.req_id, req.args, req.prep, dig, card_rev, self.revision, expires)
        summ = board_mod.summary(req.prep["card_view"], req.args["profile"])
        await self.send(conn, "action.prompt", {"re": req.req_id, "token": token, "expiresAt": expires, "summary": summ})
        await self.result(conn, req.req_id, "prompted", "", {}, aid)
        loop = asyncio.get_running_loop()
        loop.call_later(ledger_mod.TOKEN_TTL_MS / 1000 + 1, self._expire_token, token)

    def _expire_token(self, token: str) -> None:
        p = self.pending.pop(token, None)
        if p is None:
            return
        try:
            self.ledger.drop_token(token)
            self.ledger.set_claim_state(p.actor_uuid, p.req_id, ledger_mod.REFUSED, "refused", "confirm expired", self.now())
        except Exception:  # noqa: BLE001
            pass

    async def on_confirm(self, conn: Conn, p: dict[str, Any]) -> None:
        cid, token = p["id"], p["token"]
        auuid, aname = p["actor"]["uuid"], p["actor"]["name"]
        pend = self.pending.get(token)

        async def refuse(msg: str, re_id: str | None = None) -> None:
            aid = self.audit.try_write("confirm", req=re_id or cid, actor=auuid, decision="refused", reason=msg)
            await self.result(conn, re_id or cid, "refused", msg, None, aid)

        if pend is None:
            self.ledger.drop_token(token)
            return await refuse("unknown, expired or used token")
        rid = pend.req_id
        target = f"{pend.prep['board']}:{pend.prep['card']}"
        cp = self.policy.cap("card.dispatch")

        def validate(row: dict[str, Any]) -> str | None:
            if row["session"] != conn.session or pend.conn is not conn:
                return "token belongs to another connection"
            if row["actor"] != auuid:
                return "token belongs to another actor"
            if auuid not in self.policy.actors:
                return "actor not allowed"
            if not cp.enabled:
                return "capability disabled"
            if row["policy_rev"] != self.revision:
                return "policy changed since the prompt"
            if not self.check_clock():
                return "service clock error: restart required"
            if self.lock.is_locked():
                return "write lock is set"
            a = pend.args
            if a["board"] not in cp.boards or a["profile"] not in cp.profiles:
                return "board or profile no longer allowed"
            if frames.digest("card.dispatch", 2, a) != row["digest"]:
                return "request changed"
            card = self.reader.card(pend.prep["board"], pend.prep["card"])
            if card is None or card["revision"] != row["card_rev"]:
                return "card changed since the prompt"
            if not board_mod.dispatchable(card):
                return "card is no longer dispatchable"
            return None

        outcome, detail = self.ledger.consume_and_claim_dispatch(token, auuid, rid, target, cp.limits, self.now(), validate)
        self.pending.pop(token, None)
        if outcome != "ok":
            self.ledger.set_claim_state(pend.actor_uuid, rid, ledger_mod.REFUSED, "refused", detail, self.now())
            return await refuse(detail, rid)
        try:
            aid = self.audit.write("confirm", req=rid, actor=auuid, name=aname, capability="card.dispatch", decision="admitted",
                                   args=pend.args, revision=self.revision, dryRun=self.dry_run)
        except AuditError:
            self.ledger.finish(auuid, rid, ledger_mod.REFUSED, "refused", "audit log unavailable", {}, "", self.now())
            return await self.result(conn, rid, "refused", "audit log unavailable")
        req = executors.ExecRequest("card.dispatch", pend.args, pend.actor_uuid, pend.actor_name, rid, self.policy, pend.prep)
        task = asyncio.create_task(self.run_executor(conn, req, pend.digest, aid))
        conn.tasks.add(task)
        task.add_done_callback(conn.tasks.discard)

    async def on_cancel(self, conn: Conn, p: dict[str, Any]) -> None:
        cid, token, auuid = p["id"], p["token"], p["actor"]["uuid"]
        pend = self.pending.get(token)
        if pend is None or pend.conn is not conn or pend.actor_uuid != auuid:
            aid = self.audit.try_write("cancel", req=cid, actor=auuid, decision="refused", reason="unknown token")
            return await self.result(conn, cid, "refused", "unknown, expired or used token", None, aid)
        self.pending.pop(token, None)
        self.ledger.drop_token(token)
        self.ledger.set_claim_state(pend.actor_uuid, pend.req_id, ledger_mod.REFUSED, "cancelled", "cancelled by the player", self.now())
        aid = self.audit.try_write("cancel", req=pend.req_id, actor=auuid, decision="cancelled")
        await self.result(conn, pend.req_id, "cancelled", "", None, aid)

    async def on_lock(self, conn: Conn, p: dict[str, Any]) -> None:
        """The game-side lock was set. Fail-safe direction: accepted from any signed, well-formed frame."""
        actor, reason = p["actor"], p["reason"]
        self.drop_tokens(None)
        was = self.lock.is_locked()
        made = False
        if self.policy.lock_sets_hermes_lock:
            by = f"game:{actor['name']}/{actor['uuid'][:8]}"
            made = set_lock(self.lock, reason or "locked from the game", by, self.now())
        self.audit.try_write("game-lock", req=p["id"], actor=actor["uuid"], name=actor["name"], reason=reason,
                             actorKnown=actor["uuid"] in self.policy.actors, hermesLockSet=made, alreadyLocked=was,
                             policySetsLock=self.policy.lock_sets_hermes_lock)
        await self.poll_state()

    # ---- executing ----------------------------------------------------------------------------
    def _guarded(self, ex: executors.Executor, req: executors.ExecRequest) -> executors.ExecResult:
        """Runs in a worker thread, immediately before the executor: the lock is checked once more."""
        if self.lock.is_locked():
            raise Locked()
        if self.frozen:
            raise Locked()
        return ex.execute(req)

    async def run_executor(self, conn: Conn, req: executors.ExecRequest, dig: str, aid: str) -> None:
        loop = asyncio.get_running_loop()
        state, status, error, result, reply = ledger_mod.APPLIED, "applied", "", {}, None
        ex = self.executors.get(req.capability)
        try:
            if ex is None:
                raise executors.ExecRefused("no executor")
            res = await loop.run_in_executor(self._pool, self._guarded, ex, req)
            result, reply = res.result, res.reply
            status = res.status
        except Locked:
            state, status, error = ledger_mod.REFUSED, "refused", "write lock is set"
        except executors.ExecRefused as e:
            state, status, error = ledger_mod.REFUSED, "refused", str(e)
        except executors.ExecUnknown as e:
            state, status, error = ledger_mod.UNKNOWN, "unknown", str(e)
        except Exception as e:  # noqa: BLE001
            log.error("executor crashed: %s", type(e).__name__)
            state, status, error = ledger_mod.UNKNOWN, "unknown", "executor failed; check outside the game"
        error = redact.clean(error, 200)
        frames_n = 0
        if reply is not None and status == "applied":
            text = redact.clean(reply, CHAT_REPLY_CAP, keep_newlines=True) or "(no reply)"  # the reply is filtered as a whole, once
            chunks = chunk_paragraphs(text, CHAT_FRAME)
            frames_n = len(chunks)
            result = {**result, "frames": str(frames_n)}
            for i, ch in enumerate(chunks):
                conv = str(req.args.get("conversation", req.req_id))[:64]
                await self.send(conn, "action.chat", {"re": req.req_id, "conversation": conv, "agentId": str(req.args.get("agent", ""))[:64],
                                                      "text": ch, "final": i == len(chunks) - 1})
        try:
            self.ledger.finish(req.actor_uuid, req.req_id, state, status, error, result, aid, self.now())
        except Exception:  # noqa: BLE001
            log.error("could not record the outcome in the ledger")
        self.audit.try_write("outcome", req=req.req_id, actor=req.actor_uuid, capability=req.capability, status=status, error=error,
                             result=result, reply=(reply or "")[:300] if reply is not None else None, dryRun=self.dry_run, ref=aid)
        await self.result(conn, req.req_id, status, error, result, aid)

    # ---- background ---------------------------------------------------------------------------
    async def poll_state(self) -> None:
        """Push action.state when the lock (or the clock) changed; a new lock voids confirm tokens."""
        cur = (self.lock.state(), self.frozen)
        prev = (self._last_lock_state, getattr(self, "_last_frozen", None))
        if cur != prev:
            self._last_lock_state, self._last_frozen = cur
            if cur[0].locked or cur[1]:
                self.drop_tokens(None)
            await self.broadcast_state()

    async def ping_all(self) -> None:
        """WebSocket pings keep the game side's 60 s socket read timeout from firing on a quiet link."""
        for c in list(self.conns):
            try:
                await asyncio.wait_for(c.ws.ping(), SEND_TIMEOUT_S)
            except (asyncio.TimeoutError, ConnectionError, RuntimeError, WSClosed):
                await self._close(c, 1011, "ping failed")

    async def tick_loop(self) -> None:
        last_prune = time.monotonic()
        last_ping = time.monotonic()
        while True:
            await asyncio.sleep(STATE_POLL_S)
            try:
                self.check_clock()
                await self.poll_state()
                if time.monotonic() - last_ping >= PING_S:
                    last_ping = time.monotonic()
                    await self.ping_all()
                if time.monotonic() - last_prune > 3600:
                    last_prune = time.monotonic()
                    self.ledger.prune(self.now())
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                log.error("tick failed: %s", type(e).__name__)

    def reload_policy(self) -> bool:
        """SIGHUP: re-read the policy. Valid -> swap, bump the revision, void tokens. Invalid -> keep the old one."""
        if self.policy_path is None:
            return False
        try:
            new = pol.load(self.policy_path)
        except pol.PolicyError as e:
            self.audit.try_write("policy-reload-failed", reason=str(e))
            log.error("policy reload refused: %s", e)
            return False
        self.policy = new
        self.reload_n += 1
        self.revision = self._revision()
        self.drop_tokens(None)
        self.audit.try_write("policy-reload", revision=self.revision)
        return True

    async def reload_and_broadcast(self) -> None:
        if self.reload_policy():
            await self.broadcast_policy()

    # ---- lifecycle ----------------------------------------------------------------------------
    def check_upgrade(self, req: UpgradeRequest) -> str | None:
        return self.access.refuse_reason(req)

    def _on_reject(self, req: UpgradeRequest | None, why: str) -> None:
        self.audit.unauthenticated(f"upgrade refused: {why}", req.peer if req else "")

    async def start(self) -> int:
        self._server = await serve(self.host, self.port, self.on_connection, self.check_upgrade, self._on_reject, max_message=frames.MAX_MESSAGE)
        sock = self._server.sockets[0].getsockname() if self._server.sockets else (self.host, self.port)
        self.port = sock[1]
        self._tasks = [asyncio.create_task(self.tick_loop())]
        self.audit.try_write("start", host=self.host, port=self.port, revision=self.revision, dryRun=self.dry_run, protocol=PROTOCOL_VERSION)
        return self.port

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        for c in list(self.conns):
            await self._close(c, 1001, "shutting down")
        if self._server:
            self._server.close()
            await self._server.wait_closed()
        self._pool.shutdown(wait=False, cancel_futures=True)
        self.audit.try_write("stop")


def chunk_paragraphs(text: str, size: int) -> list[str]:
    """Complete paragraphs per frame (at most ``size`` characters); an oversized paragraph is cut at spaces."""
    parts: list[str] = []
    for para in text.split("\n\n"):
        para = para.strip()
        while len(para) > size:
            cut = para.rfind(" ", 0, size)
            cut = cut if cut > size // 2 else size
            parts.append(para[:cut].strip())
            para = para[cut:].strip()
        if para:
            parts.append(para)
    out: list[str] = []
    cur = ""
    for part in parts:
        if cur and len(cur) + 2 + len(part) > size:
            out.append(cur)
            cur = part
        else:
            cur = f"{cur}\n\n{part}" if cur else part
    if cur:
        out.append(cur)
    return out[:8] or ["(no reply)"]
