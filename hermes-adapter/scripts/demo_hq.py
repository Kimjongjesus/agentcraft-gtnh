"""Scripted HQ demo for the GTNH mod (card 2): a synthetic Hermes home with every real profile
name, driven phase by phase, so screenshots can show each in-world state on demand.

Writes ONLY into the directory given (a throwaway fixture built with tests/fixture.py); it never
touches the real Hermes. Run the adapter against it with ``--hermes-home <dir>/.hermes``.

    python3 scripts/demo_hq.py <dir> init          # 12 agents: builders at desks, a reviewer in
                                                   # the library, one agent waiting on Eli, idlers
    python3 scripts/demo_hq.py <dir> progress      # Opus Builder posts new progress (monitor)
    python3 scripts/demo_hq.py <dir> demo-ready    # Opus Builder blocks: walks to "user", "!"
    python3 scripts/demo_hq.py <dir> unblock       # everyone waiting is answered -> fleet working
    python3 scripts/demo_hq.py <dir> crash         # Sonnet's run crashes -> error lamp/beacon
    python3 scripts/demo_hq.py <dir> all-idle      # every run ends cleanly -> fleet idle
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE / "tests"))

from fixture import BoardFixture  # noqa: E402

PROFILES = [
    "claude-builder", "claude-builder-sonnet", "claude-builder-mega", "sol-reviewer",
    "sol-reviewer-highrisk", "luna-reviewer", "astra-ultimate", "gtnh-oracle-bridge",
    "qwen-uncensored", "venues-research-agent",
]
OFF_SHIFT = {"venues-research-agent"}


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def board(root: Path) -> BoardFixture:
    db = root / ".hermes" / "kanban" / "boards" / "homelab" / "kanban.db"
    b = BoardFixture.__new__(BoardFixture)
    b.db_path, b.now = db, int(time.time())
    return b


def run_id(b: BoardFixture, task: str) -> int:
    import sqlite3
    conn = sqlite3.connect(b.db_path)
    row = conn.execute("SELECT id FROM task_runs WHERE task_id=? ORDER BY id DESC LIMIT 1", (task,)).fetchone()
    conn.close()
    return row[0] if row else 0


def init(root: Path) -> None:
    home = root / ".hermes"
    if home.exists():
        raise SystemExit(f"refusing: {home} already exists (use a fresh scratch dir)")
    now = int(time.time())
    for p in PROFILES:
        d = home / "profiles" / p
        d.mkdir(parents=True)
        if p not in OFF_SHIFT:
            (d / "state.db").write_bytes(b"")  # session mtime = "seen recently" (content never read)
            os.utime(d / "state.db", (now, now))
        else:
            os.utime(d, (now - 30 * 86400, now - 30 * 86400))
    (home / "cron").mkdir(parents=True)
    (home / "cron" / "jobs.json").write_text(
        '{"jobs": [{"id": "a1", "name": "nest.ops.daily", "enabled": true, "state": "scheduled", "last_status": "ok",'
        ' "next_run_at": "2099-01-01T06:30:00-04:00", "failure_streak": 0}]}'
    )
    b = BoardFixture(home / "kanban" / "boards" / "homelab" / "kanban.db", now)
    # Opus Builder at its desk
    b.task("t_hq", "AgentCraft GTNH port: card 2 (HQ in-world)", "running", "claude-builder", priority=80)
    rid = b.run("t_hq", "claude-builder", started=now - 900, hb=now)
    b.event("t_hq", "claimed", {"run_id": rid}, run_id=rid, at=now - 900)
    for i, line in enumerate([
        "PLAN: anchors file, station walker, monitor blocks",
        "PROGRESS: NPC walker uses vanilla pathfinding",
        "PROGRESS: monitors render the agent log tail",
    ]):
        b.comment("t_hq", "claude-builder", line, at=now - 600 + i * 120)
    b.event("t_hq", "heartbeat", {"note": "status lamps and fleet beacon wired"}, run_id=rid, at=now - 30)
    # Sonnet Builder at a shared desk
    b.task("t_samops", "Game project: level polish", "running", "claude-builder-sonnet", priority=60)
    rid = b.run("t_samops", "claude-builder-sonnet", started=now - 1200, hb=now)
    b.event("t_samops", "claimed", {"run_id": rid}, run_id=rid, at=now - 1200)
    b.comment("t_samops", "claude-builder-sonnet", "PROGRESS: tuning air-strafe on ramp 3", at=now - 60)
    # Sol Reviewer in the library
    b.task("t_review", "Review: nightly backup script", "running", "sol-reviewer", priority=50)
    rid = b.run("t_review", "sol-reviewer", started=now - 400, hb=now)
    b.event("t_review", "claimed", {"run_id": rid}, run_id=rid, at=now - 400)
    b.comment("t_review", "sol-reviewer", "PROGRESS: checking retention math", at=now - 50)
    # Sol HR Reviewer waiting on Eli (permission)
    b.task("t_perm", "Rotate the NPM certificate", "blocked", "sol-reviewer-highrisk", block_kind="needs_input")
    b.event("t_perm", "blocked", {"reason": "PERMISSION p1: reload nginx on the proxy || CHOICES: Approve | Deny",
                                  "kind": "needs_input"}, at=now - 300)
    # Mega Builder finished something an hour ago (idle - last: ...)
    b.task("t_old", "Fleet drift audit", "done", "claude-builder-mega")
    b.run("t_old", "claude-builder-mega", status="done", started=now - 7200, ended=now - 3600, outcome="completed",
          summary="audit clean")
    log(f"init: fixture at {home}")


def progress(root: Path) -> None:
    b = board(root)
    t = int(time.time())
    b.comment("t_hq", "claude-builder", "PROGRESS: live update from the demo script\nsecond line of the same note", at=t)
    b.event("t_hq", "heartbeat", {"note": "screenshots of every state next"}, run_id=run_id(b, "t_hq"), at=t + 1)
    log("progress: Opus Builder posted a new note")


def demo_ready(root: Path) -> None:
    b = board(root)
    t = int(time.time())
    rid = run_id(b, "t_hq")
    b.sql("UPDATE task_runs SET status='blocked', ended_at=?, outcome='blocked' WHERE id=?", (t, rid))
    b.sql("UPDATE tasks SET status='blocked', block_kind='needs_input' WHERE id='t_hq'")
    b.event("t_hq", "blocked", {"reason": "DEMO READY d1: HQ agents walk, monitors live || CHOICES: Send to review | Revise",
                                "kind": "needs_input"}, run_id=rid, at=t)
    log("demo-ready: Opus Builder waiting on Eli")


def unblock(root: Path) -> None:
    b = board(root)
    t = int(time.time())
    for task, prof in (("t_hq", "claude-builder"), ("t_perm", "sol-reviewer-highrisk")):
        b.sql("UPDATE tasks SET status='running', block_kind=NULL WHERE id=?", (task,))
        b.event(task, "unblocked", None, at=t)
        rid = b.run(task, prof, started=t, hb=t)
        b.event(task, "claimed", {"run_id": rid}, run_id=rid, at=t)
    b.comment("t_hq", "claude-builder", "PROGRESS: Eli said go; back at the desk", at=t + 1)
    log("unblock: both agents back to work")


def crash(root: Path) -> None:
    b = board(root)
    t = int(time.time())
    for task in ("t_hq", "t_samops", "t_review", "t_perm"):
        rid = run_id(b, task)
        outcome = "crashed" if task == "t_samops" else "completed"
        b.sql("UPDATE task_runs SET status='done', ended_at=?, outcome=? WHERE id=?", (t - 400, outcome, rid))
        b.sql("UPDATE tasks SET status=? WHERE id=?", ("ready" if task == "t_samops" else "done", task))
    b.event("t_samops", "crashed", None, run_id=run_id(b, "t_samops"), at=t - 400)
    log("crash: Sonnet's run crashed; everyone else finished")


def all_idle(root: Path) -> None:
    b = board(root)
    t = int(time.time())
    # move the crash out of the 30-minute error window and every finish out of the 5-minute done window
    b.sql("UPDATE task_runs SET ended_at=? WHERE ended_at IS NOT NULL", (t - 3 * 3600,))
    b.sql("UPDATE task_runs SET status='done', ended_at=?, outcome='completed' WHERE ended_at IS NULL", (t - 3 * 3600,))
    log("all-idle: no live runs, nothing waiting")


PHASES = {"init": init, "progress": progress, "demo-ready": demo_ready, "unblock": unblock, "crash": crash,
          "all-idle": all_idle}


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[2] not in PHASES:
        print(__doc__, file=sys.stderr)
        return 2
    PHASES[sys.argv[2]](Path(sys.argv[1]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
