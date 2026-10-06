"""Read-only access to Hermes state.

Two kanban sources:

* ``sqlite`` (default): opens each board's ``kanban.db`` with ``mode=ro`` and ``PRAGMA query_only``.
  This is the same database ``hermes kanban list/show`` reads, without spawning a Python process
  per poll, and it cannot write even by accident.
* ``cli``: shells out to ``hermes kanban --board <b> list --json`` (task rows only; no events or
  comments, so agent activity lines are coarser). Kept for environments where the DB is not
  readable directly.

Cron jobs come from ``~/.hermes/cron/jobs.json`` (only name/schedule/state fields are kept; prompts,
delivery targets and errors are never read into the model). Profiles are the directories under
``~/.hermes/profiles`` plus ``default``; for "sessions" only the modification time of each
profile's session store is used (never its content).
"""

from __future__ import annotations

import json
import os
import sqlite3
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# task_events kinds that are interesting for the feed / logs; heartbeats are counted separately
EVENT_KINDS = (
    "created",
    "claimed",
    "completed",
    "blocked",
    "unblocked",
    "review_requested",
    "changes_requested",
    "crashed",
    "timed_out",
    "gave_up",
    "spawn_failed",
    "reclaimed",
    "assigned",
    "archived",
    "block_loop_detected",
    "protocol_violation",
)


@dataclass
class BoardData:
    slug: str
    tasks: list[dict[str, Any]] = field(default_factory=list)
    links: list[tuple[str, str]] = field(default_factory=list)  # (parent, child)
    runs: list[dict[str, Any]] = field(default_factory=list)
    events: list[dict[str, Any]] = field(default_factory=list)
    comments: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class HermesData:
    now: float
    boards: list[BoardData]
    profiles: list[dict[str, Any]]
    cron: list[dict[str, Any]]


def _ro_connect(path: Path) -> sqlite3.Connection:
    uri = "file:" + str(path) + "?mode=ro"
    conn = sqlite3.connect(uri, uri=True, timeout=2.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA query_only = ON")
    return conn


class HermesSource:
    def __init__(
        self,
        hermes_home: Path,
        boards: list[str] | None = None,
        kanban_source: str = "sqlite",
        hermes_bin: str = "hermes",
        task_window_days: float = 3.0,
        event_limit: int = 1500,
    ) -> None:
        self.home = Path(hermes_home).expanduser()
        self.boards_filter = boards
        self.kanban_source = kanban_source
        self.hermes_bin = hermes_bin
        self.task_window_days = task_window_days
        self.event_limit = event_limit

    # ---- boards ---------------------------------------------------------------------------

    def board_slugs(self) -> list[str]:
        root = self.home / "kanban" / "boards"
        found: list[str] = []
        if root.is_dir():
            for d in sorted(root.iterdir()):
                if d.is_dir() and (d / "kanban.db").exists():
                    found.append(d.name)
        if self.boards_filter:
            return [b for b in self.boards_filter if b in found or self.kanban_source == "cli"]
        # default: everything except obvious scratch boards
        return [b for b in found if "scratch" not in b]

    def _read_board_sqlite(self, slug: str, now: float) -> BoardData:
        bd = BoardData(slug=slug)
        path = self.home / "kanban" / "boards" / slug / "kanban.db"
        since = int(now - self.task_window_days * 86400)
        conn = _ro_connect(path)
        try:
            bd.tasks = [
                dict(r)
                for r in conn.execute(
                    "SELECT id, title, body, assignee, status, priority, created_by, created_at, started_at, "
                    "completed_at, branch_name, result, block_kind, last_heartbeat_at, current_run_id "
                    "FROM tasks WHERE status != 'archived' AND (status != 'done' OR COALESCE(completed_at, created_at) >= ?)",
                    (since,),
                )
            ]
            ids = {t["id"] for t in bd.tasks}
            bd.links = [
                (r["parent_id"], r["child_id"])
                for r in conn.execute("SELECT parent_id, child_id FROM task_links")
                if r["child_id"] in ids
            ]
            bd.runs = [
                dict(r)
                for r in conn.execute(
                    "SELECT id, task_id, profile, status, started_at, ended_at, outcome, summary, last_heartbeat_at "
                    "FROM task_runs WHERE ended_at IS NULL OR ended_at >= ? ORDER BY id",
                    (since,),
                )
            ]
            q = "SELECT id, task_id, run_id, kind, payload, created_at FROM task_events WHERE kind IN ({}) ORDER BY id DESC LIMIT ?".format(
                ",".join("?" * len(EVENT_KINDS))
            )
            rows = conn.execute(q, (*EVENT_KINDS, self.event_limit)).fetchall()
            # heartbeats are ~70% of all events; only the ones that carry a note matter
            rows += conn.execute(
                "SELECT id, task_id, run_id, kind, payload, created_at FROM task_events "
                "WHERE kind = 'heartbeat' AND payload IS NOT NULL AND created_at >= ? ORDER BY id DESC LIMIT 200",
                (since,),
            ).fetchall()
            rows.sort(key=lambda r: r["id"], reverse=True)
            for r in reversed(rows):
                ev = dict(r)
                try:
                    ev["payload"] = json.loads(ev["payload"]) if ev["payload"] else None
                except (TypeError, ValueError):
                    ev["payload"] = None
                bd.events.append(ev)
            bd.comments = [
                dict(r)
                for r in conn.execute(
                    "SELECT id, task_id, author, body, created_at FROM task_comments WHERE created_at >= ? ORDER BY id",
                    (since,),
                )
            ]
        finally:
            conn.close()
        return bd

    def _read_board_cli(self, slug: str) -> BoardData:
        bd = BoardData(slug=slug)
        out = subprocess.run(
            [self.hermes_bin, "kanban", "--board", slug, "list", "--json"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        if out.returncode != 0:
            raise RuntimeError(f"hermes kanban list failed for {slug}: {out.stderr.strip()[:200]}")
        data = json.loads(out.stdout or "[]")
        rows = data.get("tasks", data) if isinstance(data, dict) else data
        for t in rows:
            if t.get("status") == "archived":
                continue
            bd.tasks.append(
                {
                    "id": t.get("id"),
                    "title": t.get("title", ""),
                    "body": t.get("body"),
                    "assignee": t.get("assignee"),
                    "status": t.get("status", "todo"),
                    "priority": t.get("priority") or 0,
                    "created_by": t.get("created_by"),
                    "created_at": t.get("created_at") or 0,
                    "started_at": t.get("started_at"),
                    "completed_at": t.get("completed_at"),
                    "branch_name": t.get("branch_name"),
                    "result": t.get("result"),
                    "block_kind": t.get("block_kind"),
                    "last_heartbeat_at": t.get("last_heartbeat_at"),
                    "current_run_id": t.get("current_run_id"),
                }
            )
            for p in t.get("parents") or []:
                bd.links.append((p, t.get("id")))
        return bd

    # ---- profiles / cron -------------------------------------------------------------------

    def read_profiles(self) -> list[dict[str, Any]]:
        out = [{"name": "default", "last_seen": self._last_seen(self.home)}]
        root = self.home / "profiles"
        if root.is_dir():
            for d in sorted(root.iterdir()):
                if d.is_dir() and not d.name.startswith("."):
                    out.append({"name": d.name, "last_seen": self._last_seen(d)})
        return out

    @staticmethod
    def _last_seen(profile_dir: Path) -> float | None:
        """Newest mtime of the profile's session store (state.db/-wal or sessions/). Content is never read."""
        best: float | None = None
        for name in ("state.db-wal", "state.db", "sessions"):
            p = profile_dir / name
            try:
                m = p.stat().st_mtime
            except OSError:
                continue
            best = m if best is None else max(best, m)
        return best

    def read_cron(self) -> list[dict[str, Any]]:
        path = self.home / "cron" / "jobs.json"
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            return []
        jobs = data.get("jobs", []) if isinstance(data, dict) else data
        keep = ("id", "name", "enabled", "state", "last_status", "last_run_at", "next_run_at", "failure_streak")
        out = []
        for j in jobs if isinstance(jobs, list) else []:
            if not isinstance(j, dict):
                continue
            row = {k: j.get(k) for k in keep}
            sched = j.get("schedule")
            row["schedule"] = sched.get("display") or sched.get("expr") if isinstance(sched, dict) else None
            out.append(row)
        return out

    # ---- all -------------------------------------------------------------------------------

    def read(self) -> HermesData:
        now = time.time()
        boards: list[BoardData] = []
        for slug in self.board_slugs():
            if self.kanban_source == "cli":
                boards.append(self._read_board_cli(slug))
            else:
                boards.append(self._read_board_sqlite(slug, now))
        return HermesData(now=now, boards=boards, profiles=self.read_profiles(), cron=self.read_cron())


def env_hermes_home() -> Path:
    """The *global* Hermes home (not a profile's HERMES_HOME)."""
    real = os.environ.get("HERMES_REAL_HOME")
    if real:
        return Path(real) / ".hermes"
    return Path.home() / ".hermes"
