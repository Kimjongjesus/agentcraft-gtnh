"""The Hermes-side write lock: a file. Exists or unreadable = locked.

Only :func:`set_lock` is reachable from the network (an ``action.lock`` from the game, when the
policy says ``lockSetsHermesLock``). Clearing it is :func:`clear_lock`, used by the terminal command
``python3 -m hermes_control unlock`` and by nothing else; the service never imports it.
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class LockState:
    locked: bool
    reason: str = ""
    by: str = ""
    since: int = 0


UNLOCKED = LockState(False)


class LockFile:
    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)

    def state(self) -> LockState:
        try:
            os.lstat(self.path)
        except FileNotFoundError:
            return UNLOCKED
        except OSError:
            return LockState(True, "lock file unreadable (treated as locked)", "system", 0)
        try:
            fd = os.open(self.path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            try:
                raw = os.read(fd, 4096)
            finally:
                os.close(fd)
            d = json.loads(raw.decode("utf-8"))
            if not isinstance(d, dict):
                raise ValueError
            since = d.get("since")
            return LockState(True, str(d.get("reason", ""))[:4096], str(d.get("by", ""))[:4096], since if type(since) is int and since >= 0 else 0)
        except (OSError, ValueError, UnicodeDecodeError):
            return LockState(True, "lock file unreadable (treated as locked)", "system", 0)

    def is_locked(self) -> bool:
        return self.state().locked

    def set(self, reason: str, by: str, now_ms: int | None = None) -> bool:
        """Create the lock (0600, exclusive). Returns False when it already exists (left untouched)."""
        body = json.dumps({"reason": reason[:200], "by": by[:64], "since": now_ms if now_ms is not None else int(time.time() * 1000)}).encode()
        try:
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        except FileExistsError:
            return False
        try:
            os.write(fd, body)
            os.fsync(fd)
        finally:
            os.close(fd)
        return True


def set_lock(lock: LockFile, reason: str, by: str, now_ms: int | None = None) -> bool:
    return lock.set(reason, by, now_ms)


def clear_lock(path: Path | str) -> bool:
    """Terminal only. Returns True when a lock file was removed."""
    try:
        os.unlink(path)
        return True
    except FileNotFoundError:
        return False
