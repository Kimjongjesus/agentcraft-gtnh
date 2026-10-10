"""Board reads for admission checks and confirm prompts (read-only, always).

Two sources with the same interface:

* :class:`SqliteBoardReader` opens each board's ``kanban.db`` with ``mode=ro`` and
  ``PRAGMA query_only`` (the same helper the read adapter uses);
* :class:`FixtureBoardReader` serves a generic JSON file of cards and decisions
  (``--board-fixture``, allowed only together with ``--dry-run``).

Card ids follow the read adapter's task ids (the plain Hermes id, or ``<board>:<id>`` when two
boards share one). Decision ids follow the adapter too (``d-<board>-<event id>``); the bare event id
is accepted as well.
"""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from pathlib import Path
from typing import Any, Protocol

from hermes_adapter.redact import clean
from hermes_adapter.sources import _ro_connect

from . import frames

RUNNING_STATUSES = ("running", "claimed", "in_progress", "doing")
DISPATCHABLE = ("todo", "ready")


class BoardError(Exception):
    pass


class BoardReader(Protocol):
    def card(self, board: str, card_id: str) -> dict[str, Any] | None: ...
    def decision(self, board: str, card_id: str, decision_id: str) -> dict[str, Any] | None: ...


def revision(card: dict[str, Any]) -> str:
    """A fingerprint of everything a confirm must not outlive."""
    key = json.dumps([card.get("title"), card.get("body"), card.get("status"), card.get("assignee"), card.get("priority"),
                      card.get("run"), card.get("block")], sort_keys=True, default=str)
    return hashlib.sha256(key.encode("utf-8", "replace")).hexdigest()[:24]


def split_card_id(card_id: str, boards: tuple[str, ...]) -> tuple[str | None, str]:
    """'<board>:<id>' -> (board, id) when the prefix is an allowed board, else (None, card_id)."""
    if ":" in card_id:
        b, _, rest = card_id.partition(":")
        if b in boards and rest:
            return b, rest
    return None, card_id


def is_running(card: dict[str, Any]) -> bool:
    return str(card.get("status") or "") in RUNNING_STATUSES or bool(card.get("run"))


def dispatchable(card: dict[str, Any]) -> bool:
    return str(card.get("status") or "") in DISPATCHABLE and not card.get("run") and not card.get("block")


def decision_ids(board: str, event_id: Any) -> tuple[str, str]:
    return (f"d-{re.sub(r'[^a-z0-9._-]+', '-', board.lower()).strip('-')}-{event_id}", str(event_id))


class SqliteBoardReader:
    def __init__(self, hermes_home: Path, boards: tuple[str, ...] | list[str] = ()) -> None:
        self.home = Path(hermes_home).expanduser()
        self.boards = tuple(boards)

    def _path(self, board: str) -> Path:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}", board):
            raise BoardError("bad board name")
        return self.home / "kanban" / "boards" / board / "kanban.db"

    def _conn(self, board: str) -> sqlite3.Connection:
        p = self._path(board)
        if not p.exists():
            raise BoardError("board not found")
        return _ro_connect(p)

    def card(self, board: str, card_id: str) -> dict[str, Any] | None:
        try:
            conn = self._conn(board)
        except (BoardError, sqlite3.Error):
            return None
        try:
            r = conn.execute(
                "SELECT id, title, body, assignee, status, priority, block_kind, current_run_id FROM tasks WHERE id = ? AND status != 'archived'",
                (card_id,),
            ).fetchone()
            if r is None:
                return None
            block = None
            if r["status"] == "blocked":
                ev = conn.execute("SELECT id FROM task_events WHERE task_id = ? AND kind = 'blocked' ORDER BY id DESC LIMIT 1", (card_id,)).fetchone()
                block = ev["id"] if ev else None
            c = {"id": r["id"], "board": board, "title": r["title"] or "", "body": r["body"] or "", "status": r["status"] or "",
                 "assignee": r["assignee"] or "", "priority": int(r["priority"] or 0), "run": r["current_run_id"], "block": block,
                 "model": "profile default"}
        except sqlite3.Error:
            return None
        finally:
            conn.close()
        c["revision"] = revision(c)
        return c

    def decision(self, board: str, card_id: str, decision_id: str) -> dict[str, Any] | None:
        """The OPEN decision of a card (its latest 'blocked' event while the card is blocked)."""
        try:
            conn = self._conn(board)
        except (BoardError, sqlite3.Error):
            return None
        try:
            t = conn.execute("SELECT status FROM tasks WHERE id = ?", (card_id,)).fetchone()
            if t is None or t["status"] != "blocked":
                return None
            ev = conn.execute("SELECT id, payload FROM task_events WHERE task_id = ? AND kind = 'blocked' ORDER BY id DESC LIMIT 1", (card_id,)).fetchone()
            if ev is None or decision_id not in decision_ids(board, ev["id"]):
                return None
            try:
                payload = json.loads(ev["payload"]) if ev["payload"] else {}
            except (TypeError, ValueError):
                payload = {}
            if not isinstance(payload, dict):
                payload = {}
            kind = payload.get("kind")
            return {"id": decision_ids(board, ev["id"])[0], "event": ev["id"], "board": board, "card": card_id,
                    "reason": str(payload.get("reason") or ""), "blockKind": kind if isinstance(kind, str) else None}
        except sqlite3.Error:
            return None
        finally:
            conn.close()


class FixtureBoardReader:
    """``--board-fixture FILE``:

    {"schema": 1, "boards": {"main": {"cards": [{"id", "title", "body", "status", "assignee", "priority", "model"}],
                                       "decisions": [{"id", "card", "reason", "blockKind"}]}}}
    """

    def __init__(self, path: Path | str) -> None:
        raw = Path(path).read_bytes()
        if len(raw) > 512 * 1024:
            raise BoardError("fixture too big")
        try:
            data = frames.strict_loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, frames.FrameError):
            raise BoardError("fixture is not valid JSON") from None
        if not isinstance(data, dict) or data.get("schema") != 1 or not isinstance(data.get("boards"), dict):
            raise BoardError("fixture: need {schema: 1, boards: {...}}")
        self.boards: dict[str, dict[str, Any]] = {}
        for bname, b in data["boards"].items():
            if not isinstance(b, dict) or not isinstance(b.get("cards", []), list) or not isinstance(b.get("decisions", []), list):
                raise BoardError(f"fixture: bad board {bname[:30]!r}")
            cards: dict[str, dict[str, Any]] = {}
            for c in b.get("cards", []):
                if not isinstance(c, dict) or not isinstance(c.get("id"), str) or not isinstance(c.get("title"), str):
                    raise BoardError("fixture: bad card")
                card = {"id": c["id"], "board": bname, "title": c["title"], "body": str(c.get("body") or ""),
                        "status": str(c.get("status") or "todo"), "assignee": str(c.get("assignee") or ""),
                        "priority": int(c.get("priority") or 0), "run": c.get("run"), "block": None,
                        "model": str(c.get("model") or "profile default")}
                cards[c["id"]] = card
            decisions: dict[str, dict[str, Any]] = {}
            for d in b.get("decisions", []):
                if not isinstance(d, dict) or not isinstance(d.get("id"), str) or d.get("card") not in cards:
                    raise BoardError("fixture: bad decision")
                decisions[d["card"]] = {"id": d["id"], "event": d["id"], "board": bname, "card": d["card"], "reason": str(d.get("reason") or ""),
                                        "blockKind": d.get("blockKind")}
                cards[d["card"]]["status"] = "blocked"
                cards[d["card"]]["block"] = d["id"]
            for c in cards.values():
                c["revision"] = revision(c)
            self.boards[bname] = {"cards": cards, "decisions": decisions}

    def card(self, board: str, card_id: str) -> dict[str, Any] | None:
        b = self.boards.get(board)
        c = b["cards"].get(card_id) if b else None
        return dict(c) if c else None

    def decision(self, board: str, card_id: str, decision_id: str) -> dict[str, Any] | None:
        b = self.boards.get(board)
        d = b["decisions"].get(card_id) if b else None
        if d is None or decision_id not in (d["id"], d["id"].rsplit("-", 1)[-1]):
            return None
        return dict(d)


def locate(reader: BoardReader, boards: tuple[str, ...], card_id: str, board: str | None = None) -> dict[str, Any] | None:
    """Find a card on the allowed boards (exactly one match, else None)."""
    pre, plain = split_card_id(card_id, boards)
    candidates = [board] if board else ([pre] if pre else list(boards))
    found = []
    for b in candidates:
        if b not in boards:
            continue
        c = reader.card(b, plain)
        if c:
            found.append(c)
    return found[0] if len(found) == 1 else None


def summary(card: dict[str, Any], profile: str) -> dict[str, str]:
    """The ``action.prompt`` summary, built from board data (filtered), never from the request."""
    return {
        "card": clean(card.get("id"), 64), "title": clean(card.get("title"), 200), "board": clean(card.get("board"), 64),
        "profile": clean(profile, 64), "model": clean(card.get("model") or "profile default", 100),
        "body": clean(card.get("body"), 600, keep_newlines=True),
    }
