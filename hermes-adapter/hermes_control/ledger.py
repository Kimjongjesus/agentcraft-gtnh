"""The ledger: SQLite (0600, outside the repository) behind every durable promise of the contract.

* nonces with expiry (never evicted early; a full cache refuses new frames),
* the service start time and the last-seen wall clock (backwards jump -> refuse everything),
* idempotency claims ``(actor, id, digest, state, result)`` kept 24 hours; a claim left ``pending``
  by a crash becomes ``unknown`` at start-up and is never re-run,
* confirm tokens (stored as SHA-256 hashes; consumed in the same transaction that claims the dispatch),
* rate-limit events.
"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Iterator

from . import safety

NONCE_WINDOW_MS = 60_000
MAX_NONCES = 4096
CLAIM_KEEP_MS = 24 * 3600 * 1000
TOKEN_TTL_MS = 60_000
MAX_OPEN_TOKENS = 4

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS nonces (nonce TEXT PRIMARY KEY, expires_ms INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS nonces_exp ON nonces (expires_ms);
CREATE TABLE IF NOT EXISTS claims (
  actor TEXT NOT NULL, id TEXT NOT NULL, digest TEXT NOT NULL, capability TEXT NOT NULL,
  state TEXT NOT NULL, status TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
  result TEXT NOT NULL DEFAULT '{}', audit TEXT NOT NULL DEFAULT '',
  created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY (actor, id));
CREATE INDEX IF NOT EXISTS claims_created ON claims (created_ms);
CREATE TABLE IF NOT EXISTS tokens (
  hash TEXT PRIMARY KEY, session TEXT NOT NULL, actor TEXT NOT NULL, req_id TEXT NOT NULL, digest TEXT NOT NULL,
  card_rev TEXT NOT NULL, policy_rev TEXT NOT NULL, expires_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL, ts_ms INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS events_key ON events (key, ts_ms);
"""

# claim states
PENDING, PROMPTED, APPLIED, REFUSED, UNKNOWN = "pending", "prompted", "applied", "refused", "unknown"


class LedgerError(Exception):
    pass


def token_hash(token: str) -> str:
    return hashlib.sha256(b"agentcraft-token:" + token.encode("ascii", "replace")).hexdigest()


class Ledger:
    def __init__(self, path: Path | str, clock: Callable[[], int], allow_in_repo: bool = False) -> None:
        self.path = safety.refuse_in_repo(Path(path), "control ledger", allow_in_repo)
        self.clock = clock
        self._lock = threading.RLock()
        parent = self.path.parent
        try:
            safety.ensure_private_dir(parent, "state directory")
            flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
            os.close(os.open(self.path, flags, 0o600))
            os.chmod(self.path, 0o600)
            safety.check_file(self.path, "ledger file", private=True)
        except (OSError, safety.UnsafePath) as e:
            raise LedgerError(f"ledger unusable: {e}") from None
        self.db = sqlite3.connect(str(self.path), isolation_level=None, check_same_thread=False, timeout=5)
        self.db.execute("PRAGMA synchronous = FULL")
        self.db.executescript(_SCHEMA)
        now = self.clock()
        self.start_ms = now
        with self._tx() as c:
            c.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('start_ms', ?)", (str(now),))
            c.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('last_seen_ms', ?)", (str(now),))
            # a claim left pending (crash between doing the work and recording it) is never re-run
            c.execute("UPDATE claims SET state = ?, status = 'unknown', error = 'service restarted while the request was in flight', updated_ms = ? WHERE state = ?", (UNKNOWN, now, PENDING))
            # a claim that was only prompted loses its token below; the request is over
            c.execute("UPDATE claims SET state = ?, status = 'cancelled', error = 'service restarted before confirm', updated_ms = ? WHERE state = ?", (REFUSED, now, PROMPTED))
            c.execute("DELETE FROM tokens")
            c.execute("DELETE FROM claims WHERE created_ms < ?", (now - CLAIM_KEEP_MS,))
            c.execute("DELETE FROM events WHERE ts_ms < ?", (now - 2 * 3600 * 1000,))
            c.execute("DELETE FROM nonces WHERE expires_ms <= ?", (now,))

    # ---- plumbing --------------------------------------------------------------------------
    @contextmanager
    def _tx(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield self.db
            except BaseException:
                self.db.execute("ROLLBACK")
                raise
            else:
                self.db.execute("COMMIT")

    def close(self) -> None:
        with self._lock:
            try:
                self.db.close()
            except sqlite3.Error:
                pass

    def meta(self, key: str) -> str | None:
        with self._lock:
            row = self.db.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None

    # ---- clock ------------------------------------------------------------------------------
    def last_seen(self) -> int:
        return int(self.meta("last_seen_ms") or 0)

    def touch(self, now: int) -> None:
        with self._tx() as c:
            c.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('last_seen_ms', ?)", (str(max(now, self.last_seen())),))

    # ---- nonces -----------------------------------------------------------------------------
    def take_nonce(self, nonce: str, ts: int, now: int) -> str:
        """'ok' | 'replay' | 'full'. Entries live until ts + 60 s and are never removed earlier."""
        with self._tx() as c:
            c.execute("DELETE FROM nonces WHERE expires_ms <= ?", (now,))
            if c.execute("SELECT 1 FROM nonces WHERE nonce = ?", (nonce,)).fetchone():
                return "replay"
            n = c.execute("SELECT COUNT(*) FROM nonces").fetchone()[0]
            if n >= MAX_NONCES:
                return "full"
            c.execute("INSERT INTO nonces (nonce, expires_ms) VALUES (?, ?)", (nonce, max(ts, now) + NONCE_WINDOW_MS))
            return "ok"

    def nonce_count(self) -> int:
        with self._lock:
            return int(self.db.execute("SELECT COUNT(*) FROM nonces").fetchone()[0])

    # ---- rate limits ------------------------------------------------------------------------
    @staticmethod
    def _count(c: sqlite3.Connection, key: str, since: int) -> int:
        return int(c.execute("SELECT COUNT(*) FROM events WHERE key = ? AND ts_ms > ?", (key, since)).fetchone()[0])

    def limit_check(self, c: sqlite3.Connection, cap: str, target: str | None, limits: dict[str, int], now: int) -> str | None:
        """Why the request is over a limit, or None."""
        if "perMinute" in limits and self._count(c, f"cap:{cap}", now - 60_000) >= limits["perMinute"]:
            return f"{cap}: limit {limits['perMinute']} per minute reached"
        if "perHour" in limits and self._count(c, f"cap:{cap}", now - 3_600_000) >= limits["perHour"]:
            return f"{cap}: limit {limits['perHour']} per hour reached"
        if target is not None and "perTargetCount" in limits:
            win = limits.get("perTargetSeconds", 600) * 1000
            if self._count(c, f"tgt:{cap}:{target}", now - win) >= limits["perTargetCount"]:
                return f"{cap}: limit {limits['perTargetCount']} per target per {limits.get('perTargetSeconds', 600)} s reached"
        return None

    @staticmethod
    def limit_record(c: sqlite3.Connection, cap: str, target: str | None, now: int) -> None:
        c.execute("INSERT INTO events (key, ts_ms) VALUES (?, ?)", (f"cap:{cap}", now))
        if target is not None:
            c.execute("INSERT INTO events (key, ts_ms) VALUES (?, ?)", (f"tgt:{cap}:{target}", now))

    def limit_peek(self, cap: str, target: str | None, limits: dict[str, int], now: int) -> str | None:
        with self._lock:
            return self.limit_check(self.db, cap, target, limits, now)

    # ---- claims -----------------------------------------------------------------------------
    @staticmethod
    def _row(r: sqlite3.Row | tuple | None) -> dict[str, Any] | None:
        if r is None:
            return None
        keys = ("actor", "id", "digest", "capability", "state", "status", "error", "result", "audit", "created_ms", "updated_ms")
        d = dict(zip(keys, r))
        d["result"] = json.loads(d["result"] or "{}")
        return d

    _COLS = "actor, id, digest, capability, state, status, error, result, audit, created_ms, updated_ms"

    def get_claim(self, actor: str, rid: str) -> dict[str, Any] | None:
        with self._lock:
            return self._row(self.db.execute(f"SELECT {self._COLS} FROM claims WHERE actor = ? AND id = ?", (actor, rid)).fetchone())

    def claim(self, actor: str, rid: str, digest: str, cap: str, now: int, *, state: str = PENDING,
              target: str | None = None, limits: dict[str, int] | None = None, record_event: bool = True,
              limit_cap: str | None = None) -> tuple[str, dict[str, Any] | None]:
        """Atomically claim ``(actor, id)`` with ``digest``.

        Returns ('new', None), ('same', stored claim) when the same id and digest were claimed
        before, ('conflict', stored) for the same id with another digest, or ('limit', reason).
        The rate-limit check and the event record share the transaction with the claim.
        """
        with self._tx() as c:
            row = self._row(c.execute(f"SELECT {self._COLS} FROM claims WHERE actor = ? AND id = ?", (actor, rid)).fetchone())
            if row is not None:
                return ("same" if row["digest"] == digest else "conflict"), row
            lcap = limit_cap or cap
            if limits is not None:
                why = self.limit_check(c, lcap, target, limits, now)
                if why:
                    return "limit", {"error": why}
            c.execute("INSERT INTO claims (actor, id, digest, capability, state, created_ms, updated_ms) VALUES (?,?,?,?,?,?,?)",
                      (actor, rid, digest, cap, state, now, now))
            if record_event and limits is not None:
                self.limit_record(c, lcap, target, now)
            return "new", None

    def finish(self, actor: str, rid: str, state: str, status: str, error: str, result: dict[str, str], audit: str, now: int) -> None:
        with self._tx() as c:
            c.execute("UPDATE claims SET state = ?, status = ?, error = ?, result = ?, audit = ?, updated_ms = ? WHERE actor = ? AND id = ?",
                      (state, status, error[:200], json.dumps(result), audit[:64], now, actor, rid))

    # ---- confirm tokens ---------------------------------------------------------------------
    def open_tokens(self, actor: str) -> int:
        with self._lock:
            return int(self.db.execute("SELECT COUNT(*) FROM tokens WHERE actor = ?", (actor,)).fetchone()[0])

    def put_token(self, token: str, session: str, actor: str, req_id: str, digest: str, card_rev: str, policy_rev: str, expires_ms: int) -> None:
        with self._tx() as c:
            c.execute("INSERT INTO tokens (hash, session, actor, req_id, digest, card_rev, policy_rev, expires_ms) VALUES (?,?,?,?,?,?,?,?)",
                      (token_hash(token), session, actor, req_id, digest, card_rev, policy_rev, expires_ms))

    def peek_token(self, token: str) -> dict[str, Any] | None:
        with self._lock:
            r = self.db.execute("SELECT session, actor, req_id, digest, card_rev, policy_rev, expires_ms FROM tokens WHERE hash = ?", (token_hash(token),)).fetchone()
        if r is None:
            return None
        return dict(zip(("session", "actor", "req_id", "digest", "card_rev", "policy_rev", "expires_ms"), r))

    def drop_token(self, token: str) -> bool:
        with self._tx() as c:
            return c.execute("DELETE FROM tokens WHERE hash = ?", (token_hash(token),)).rowcount > 0

    def drop_tokens(self, session: str | None = None) -> int:
        """Void the tokens of one session (connection closed) or all of them (lock, reload)."""
        with self._tx() as c:
            if session is None:
                return c.execute("DELETE FROM tokens").rowcount
            return c.execute("DELETE FROM tokens WHERE session = ?", (session,)).rowcount

    def consume_and_claim_dispatch(self, token: str, actor: str, req_id: str, target: str, limits: dict[str, int], now: int,
                                   validate: Callable[[dict[str, Any]], str | None]) -> tuple[str, str]:
        """One transaction: re-check limits, consume the token, move the request's claim prompted -> pending.

        ``validate(token row)`` returns a refusal reason or None; it runs inside the transaction. On
        any refusal nothing is consumed except an expired / mismatching token which is dropped.
        Returns (outcome, detail) with outcome 'ok' | 'refused'.
        """
        with self._tx() as c:
            r = c.execute("SELECT session, actor, req_id, digest, card_rev, policy_rev, expires_ms FROM tokens WHERE hash = ?", (token_hash(token),)).fetchone()
            if r is None:
                return "refused", "unknown or used token"
            row = dict(zip(("session", "actor", "req_id", "digest", "card_rev", "policy_rev", "expires_ms"), r))
            c.execute("DELETE FROM tokens WHERE hash = ?", (token_hash(token),))  # single use, whatever happens next
            if row["expires_ms"] <= now:
                return "refused", "token expired"
            why = validate(row)
            if why:
                return "refused", why
            claim = self._row(c.execute(f"SELECT {self._COLS} FROM claims WHERE actor = ? AND id = ?", (row["actor"], row["req_id"])).fetchone())
            if claim is None or claim["state"] != PROMPTED or claim["digest"] != row["digest"]:
                return "refused", "request is not waiting for a confirm"
            why = self.limit_check(c, "card.dispatch", target, limits, now)
            if why:
                c.execute("UPDATE claims SET state = ?, status = 'refused', error = ?, updated_ms = ? WHERE actor = ? AND id = ?",
                          (REFUSED, why[:200], now, row["actor"], row["req_id"]))
                return "refused", why
            c.execute("UPDATE claims SET state = ?, updated_ms = ? WHERE actor = ? AND id = ?", (PENDING, now, row["actor"], row["req_id"]))
            self.limit_record(c, "card.dispatch", target, now)
            return "ok", row["req_id"]

    def set_claim_state(self, actor: str, rid: str, state: str, status: str, error: str, now: int) -> None:
        self.finish(actor, rid, state, status, error, {}, "", now)

    def prune(self, now: int) -> None:
        """Hourly housekeeping: claims older than 24 h, rate-limit events older than 2 h, expired nonces."""
        with self._tx() as c:
            c.execute("DELETE FROM claims WHERE created_ms < ? AND state NOT IN (?, ?)", (now - CLAIM_KEEP_MS, PENDING, PROMPTED))
            c.execute("DELETE FROM events WHERE ts_ms < ?", (now - 2 * 3600 * 1000,))
            c.execute("DELETE FROM nonces WHERE expires_ms <= ?", (now,))
