"""Fixture builder: a throwaway Hermes home with one board, profiles and cron jobs."""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = """
CREATE TABLE tasks (id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT, assignee TEXT, status TEXT NOT NULL,
  priority INTEGER DEFAULT 0, created_by TEXT, created_at INTEGER NOT NULL, started_at INTEGER, completed_at INTEGER,
  branch_name TEXT, result TEXT, block_kind TEXT, last_heartbeat_at INTEGER, current_run_id INTEGER);
CREATE TABLE task_links (parent_id TEXT NOT NULL, child_id TEXT NOT NULL, PRIMARY KEY (parent_id, child_id));
CREATE TABLE task_comments (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, author TEXT NOT NULL,
  body TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE task_events (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, run_id INTEGER, kind TEXT NOT NULL,
  payload TEXT, created_at INTEGER NOT NULL);
CREATE TABLE task_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, profile TEXT, status TEXT NOT NULL,
  started_at INTEGER NOT NULL, ended_at INTEGER, outcome TEXT, summary TEXT, last_heartbeat_at INTEGER);
"""

FAKE_TOKEN = "sk-" + "ant-api03-" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"  # built at runtime: not a real key
FAKE_GH = "gh" + "p_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef1234"


class BoardFixture:
    """One board's kanban.db with insert helpers (rowids autoincrement per board, like Hermes)."""

    def __init__(self, db_path: Path, now: int) -> None:
        self.db_path = db_path
        self.now = now
        db_path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(db_path)
        conn.executescript(SCHEMA)
        conn.commit()
        conn.close()

    def sql(self, q: str, args: tuple = ()) -> int:
        conn = sqlite3.connect(self.db_path)
        cur = conn.execute(q, args)
        conn.commit()
        rid = cur.lastrowid or 0
        conn.close()
        return rid

    def task(self, tid: str, title: str, status: str, assignee: str | None = None, body: str | None = None, **kw) -> None:
        self.sql(
            "INSERT INTO tasks (id, title, body, assignee, status, priority, created_by, created_at, branch_name, block_kind, result) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (tid, title, body, assignee, status, kw.get("priority", 0), kw.get("created_by", "owner"), kw.get("created_at", self.now - 3600),
             kw.get("branch"), kw.get("block_kind"), kw.get("result")),
        )

    def run(self, tid: str, profile: str, status: str = "running", started: int | None = None, ended: int | None = None,
            outcome: str | None = None, summary: str | None = None, hb: int | None = None) -> int:
        return self.sql(
            "INSERT INTO task_runs (task_id, profile, status, started_at, ended_at, outcome, summary, last_heartbeat_at) VALUES (?,?,?,?,?,?,?,?)",
            (tid, profile, status, started or self.now - 600, ended, outcome, summary, hb if hb is not None else self.now - 30),
        )

    def event(self, tid: str, kind: str, payload: dict | None = None, run_id: int | None = None, at: int | None = None) -> int:
        return self.sql(
            "INSERT INTO task_events (task_id, run_id, kind, payload, created_at) VALUES (?,?,?,?,?)",
            (tid, run_id, kind, json.dumps(payload) if payload is not None else None, at or self.now - 60),
        )

    def comment(self, tid: str, author: str, body: str, at: int | None = None) -> int:
        return self.sql("INSERT INTO task_comments (task_id, author, body, created_at) VALUES (?,?,?,?)", (tid, author, body, at or self.now - 30))

    def link(self, parent: str, child: str) -> None:
        self.sql("INSERT INTO task_links (parent_id, child_id) VALUES (?,?)", (parent, child))


class Fixture(BoardFixture):
    """A throwaway Hermes home; the helpers inherited from BoardFixture write to board 'main'."""

    def __init__(self, root: Path, now: int) -> None:
        self.root = root
        self.home = root / "hermes-home"
        self.boards_dir = self.home / "kanban" / "boards"
        self.board = self.boards_dir / "main"
        super().__init__(self.board / "kanban.db", now)
        for p in ("builder-a", "reviewer-a", "builder-b", "helper-a", ".deleted"):
            (self.home / "profiles" / p).mkdir(parents=True)
        (self.home / "cron").mkdir(parents=True)
        (self.home / "cron" / "jobs.json").write_text(json.dumps({"jobs": [
            {"id": "a1", "name": "ops.daily-report", "enabled": True, "state": "scheduled", "last_status": "ok",
             # relative to `now`: the feed only shows cron runs of the last 3 days, so a fixed date
             # made the feed test fail a few days after it was written
             "last_run_at": datetime.fromtimestamp(now - 6 * 3600, timezone.utc).isoformat(),
             "next_run_at": "2099-01-01T06:30:00-04:00",
             "failure_streak": 0, "prompt": "SECRET PROMPT " + FAKE_TOKEN, "deliver": "discord:123456789012345678",
             "schedule": {"kind": "cron", "expr": "30 6 * * *", "display": "30 6 * * *"}},
        ]}))
        # a scratch board that must be ignored by default
        scratch = self.add_board("routing-scratch")
        scratch.sql("INSERT INTO tasks (id, title, status, created_at) VALUES ('t_scratch', 'scratch task', 'todo', ?)", (now,))

    def add_board(self, slug: str) -> BoardFixture:
        return BoardFixture(self.boards_dir / slug / "kanban.db", self.now)


def standard(root: Path, now: int) -> Fixture:
    """A board with one live builder, one waiting reviewer task, a dependency and planted secrets."""
    f = Fixture(root, now)
    f.task("t_build", "Build the thing", "running", "builder-a",
           body=f"Use token {FAKE_TOKEN} and read /home/user/.config/tool/auth.json", priority=80, branch="p1-gtnh-viewer")
    rid = f.run("t_build", "builder-a")
    f.event("t_build", "claimed", {"lock": "x", "run_id": rid}, run_id=rid, at=now - 590)
    f.comment("t_build", "builder-a", "PROGRESS: wiring the websocket bridge", at=now - 20)
    f.comment("t_build", "builder-a", f"debug: password={FAKE_GH} at https://user:hunter2@example.com/x", at=now - 25)
    f.task("t_parent", "Parent card", "done", "builder-b", created_at=now - 7200)
    f.link("t_parent", "t_build")
    f.task("t_wait", "Review the plan", "blocked", "reviewer-a", block_kind="needs_input")
    f.event("t_wait", "blocked", {"reason": "QUESTION q1: ship it now? || CHOICES: Yes | No | Later", "kind": "needs_input"}, at=now - 120)
    f.task("t_personal", "Summarise notes", "todo", "helper-a",
           body="Read ~/.claude/projects/-home-user/memory/personal-schedule.md and quote it")
    f.comment("t_personal", "helper-a", "From personal-health.md: private details here")
    return f
