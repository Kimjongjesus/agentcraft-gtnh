"""Hermes-side audit log: append-only JSON lines, owner-only, fsynced before any executor runs.

``write_admission`` raises :class:`AuditError` when the record cannot be made durable; the caller
then refuses the request (no audit, no action). Keys, signatures, confirm tokens and raw payloads
are never written: callers pass small dicts, and :func:`scrub` drops anything that looks like one.
"""

from __future__ import annotations

import errno
import json
import os
import re
import secrets
import threading
import time
from pathlib import Path
from typing import Any, Callable

from hermes_adapter import redact

ROLL_BYTES = 8 * 1024 * 1024
CHAT_CAP = 300
FIELD_CAP = 300
_SECRET_KEYS = {"token", "key", "sig", "payload", "challenge", "secret", "password"}
_HEX64 = re.compile(r"\b[0-9a-fA-F]{32,}\b")


class AuditError(Exception):
    pass


def scrub(value: Any, cap: int = FIELD_CAP, depth: int = 0) -> Any:
    """Filter + cap everything that goes into a record."""
    if isinstance(value, str):
        return _HEX64.sub("[hex]", redact.clean(value, cap))
    if isinstance(value, bool) or value is None or isinstance(value, (int, float)):
        return value
    if isinstance(value, dict) and depth < 4:
        return {str(k)[:40]: ("[withheld]" if str(k).lower() in _SECRET_KEYS else scrub(v, cap, depth + 1)) for k, v in list(value.items())[:32]}
    if isinstance(value, (list, tuple)) and depth < 4:
        return [scrub(v, cap, depth + 1) for v in list(value)[:32]]
    return str(type(value).__name__)


class Audit:
    def __init__(self, path: Path | str, clock: Callable[[], int] | None = None, roll_bytes: int = ROLL_BYTES) -> None:
        self.path = Path(path)
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.roll_bytes = roll_bytes
        self._lock = threading.Lock()
        self._seq = 0
        self._boot = secrets.token_hex(4)
        self._fd: int | None = None
        self._need_newline = False
        self._unauth_last = 0.0
        self._unauth_suppressed = 0
        self._open()  # fail at start-up when the audit file cannot be written

    # ---- file -------------------------------------------------------------------------------
    def _open(self) -> None:
        flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
        try:
            fd = os.open(self.path, flags, 0o600)
            os.fchmod(fd, 0o600)
        except OSError as e:
            raise AuditError(f"audit file unusable ({e.strerror or type(e).__name__})") from None
        if self._fd is not None:
            try:
                os.close(self._fd)
            except OSError:
                pass
        self._fd = fd

    def _roll_if_needed(self) -> None:
        try:
            if self._fd is not None and os.fstat(self._fd).st_size >= self.roll_bytes:
                os.replace(self.path, str(self.path) + ".1")
                self._open()
        except OSError as e:
            raise AuditError(f"audit roll failed ({e.strerror or type(e).__name__})") from None

    def _append(self, record: dict[str, Any]) -> str:
        with self._lock:
            self._seq += 1
            rid = f"{self._boot}-{self._seq:06d}"
            record = {"id": rid, "ts": self.clock(), **record}
            line = (json.dumps(record, separators=(",", ":"), ensure_ascii=True) + "\n").encode("ascii")
            try:
                if self._fd is None:
                    self._open()
                self._roll_if_needed()
                assert self._fd is not None
                if self._need_newline:  # a previous record was cut short and could not be removed: start a fresh line
                    line = b"\n" + line
                self._write_record(self._fd, line)
                os.fsync(self._fd)  # only after the WHOLE record is written
                self._need_newline = False
            except (OSError, AssertionError) as e:
                raise AuditError(f"audit write failed ({getattr(e, 'strerror', None) or type(e).__name__})") from None
            return rid

    def _write_record(self, fd: int, data: bytes) -> None:
        """Write ALL of ``data``: loop over short writes and EINTR; no progress is an error (no audit, no action)."""
        before = None
        try:
            before = os.fstat(fd).st_size
        except OSError:
            pass
        done = 0
        try:
            while done < len(data):
                try:
                    n = os.write(fd, data[done:])
                except InterruptedError:
                    continue
                if not isinstance(n, int) or n <= 0:
                    raise OSError(errno.EIO, "audit write made no progress")
                done += n
        except OSError:
            if done:  # a partial record is on disk: take it back out, or make sure the next record starts on a new line
                try:
                    if before is None:
                        raise OSError
                    os.ftruncate(fd, before)
                except OSError:
                    self._need_newline = True
            raise

    # ---- api --------------------------------------------------------------------------------
    def write(self, event: str, **fields: Any) -> str:
        """Durable record; raises AuditError when it cannot be written."""
        rec: dict[str, Any] = {"event": event}
        for k, v in fields.items():
            if k == "req":  # request ids are already restricted to [A-Za-z0-9._:-]{1,64}; kept verbatim to correlate with the game log
                rec[k] = str(v)[:64] if re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", str(v)) else scrub(v)
                continue
            rec[k] = scrub(v, CHAT_CAP if k in ("text", "reply") else FIELD_CAP)
        return self._append(rec)

    def try_write(self, event: str, **fields: Any) -> str:
        """Best effort (outcomes, refusals): returns '' if the record could not be written."""
        try:
            return self.write(event, **fields)
        except AuditError:
            return ""

    def unauthenticated(self, reason: str, peer: str = "") -> None:
        """Refusals of frames that failed the signature: at most one record a second, with a counter."""
        now = time.monotonic()
        with self._lock:
            if now - self._unauth_last < 1.0:
                self._unauth_suppressed += 1
                return
            suppressed, self._unauth_suppressed = self._unauth_suppressed, 0
            self._unauth_last = now
        self.try_write("unauthenticated", reason=reason, peer=peer, suppressed=suppressed)

    def close(self) -> None:
        with self._lock:
            if self._fd is not None:
                try:
                    os.close(self._fd)
                except OSError:
                    pass
                self._fd = None
