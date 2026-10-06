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
    python3 scripts/demo_hq.py <dir> wall-move     # card 3: cards change columns, new plan note
    python3 scripts/demo_hq.py <dir> all-waiting   # card 4: every on-shift agent waits on Eli at once
    python3 scripts/demo_hq.py <dir> history       # card 4: 40 done cards older than the task window
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
    b.task("t_game", "Game project: level polish", "running", "claude-builder-sonnet", priority=60)
    rid = b.run("t_game", "claude-builder-sonnet", started=now - 1200, hb=now)
    b.event("t_game", "claimed", {"run_id": rid}, run_id=rid, at=now - 1200)
    b.comment("t_game", "claude-builder-sonnet", "PROGRESS: tuning movement on level 3", at=now - 60)
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
    _card3_board(home, b, now)
    log(f"init: fixture at {home}")


def _card3_board(home: Path, b: BoardFixture, now: int) -> None:
    """Card 3: enough cards in every wall column, library notes and a second board (generic titles)."""
    b.comment("t_old", "claude-builder-mega", "HANDOFF: audit finished, nothing drifted.\nNext: re-run after the "
              "next host change.\n\nChecked: every guest config against the docs, backup jobs, open ports.", at=now - 3500)
    b.comment("t_review", "sol-reviewer", "PASS: retention math checks out; tiers keep 7 daily, 4 weekly, 6 monthly.",
              at=now - 40)
    # todo
    b.task("t_arena", "Game project: boss arena lighting", "todo", "claude-builder-mega", priority=40)
    b.task("t_guide", "Docs: HQ placement guide for the task wall", "ready", "astra-ultimate", priority=30,
           body="Write a short guide: where to put the task wall, library and atrium, and how to bind them.")
    b.task("t_saves", "Game project: save-file migration", "todo", None, priority=20,
           body="Migrate old save files to the new format. Needs the level polish card first.")
    b.link("t_game", "t_saves")
    b.task("t_copy", "Website: landing page copy", "triage", "luna-reviewer", priority=10)
    # review
    b.task("t_inv", "Game project: inventory UI pass", "review", "claude-builder-sonnet", priority=55,
           body="Tidy the inventory screen: bigger slots, readable counts, controller hints.")
    rid = b.run("t_inv", "claude-builder-sonnet", status="done", started=now - 5000, ended=now - 4000,
                outcome="review_requested", summary="Inventory slots 20% bigger, counts outlined, hints added.")
    b.event("t_inv", "review_requested", {"summary": "inventory pass ready"}, run_id=rid, at=now - 4000)
    b.comment("t_inv", "claude-builder-sonnet", "PLAN: 1) slot size 2) count outline 3) controller hints\n"
              "Keep the old layout behind a setting for one release.", at=now - 4900)
    # blocked (a question for Eli)
    b.task("t_music", "Game project: music licensing", "blocked", "astra-ultimate", block_kind="needs_input",
           priority=35)
    b.event("t_music", "blocked", {"reason": "QUESTION q1: use the free track pack or commission one? "
                                   "|| CHOICES: Free pack | Commission", "kind": "needs_input"}, at=now - 200)
    # done (summaries feed the library)
    for tid, title, prof, summ in (
        ("t_backup", "Backup job: weekly verify", "claude-builder", "Weekly restore test added; last run restored 3 "
         "sample files and compared checksums."),
        ("t_subs", "Media server: subtitle fix", "claude-builder-sonnet", "Subtitles default to English again; "
         "forced tracks kept."),
    ):
        b.task(tid, title, "done", prof)
        b.sql("UPDATE tasks SET completed_at=? WHERE id=?", (now - 1800, tid))
        b.run(tid, prof, status="done", started=now - 2400, ended=now - 1800, outcome="completed", summary=summ)
    # a second board: separate goal on the atrium
    o = BoardFixture(home / "kanban" / "boards" / "ai-ops" / "kanban.db", now)
    o.task("t_report", "Nightly report: tidy formatting", "running", "qwen-uncensored", priority=20)
    o.run("t_report", "qwen-uncensored", started=now - 300, hb=now)
    o.task("t_logs", "Cron: rotate logs", "done", "astra-ultimate")
    o.run("t_logs", "astra-ultimate", status="done", started=now - 3000, ended=now - 2000, outcome="completed",
          summary="Logs rotate weekly, 8 kept.")
    o.task("t_alerts", "Alerts: quiet hours", "todo", "claude-builder", priority=15)


def wall_move(root: Path) -> None:
    """Card 3: cards move between columns (wall/atrium update within seconds) and a new plan note."""
    b = board(root)
    t = int(time.time())
    rid = run_id(b, "t_game")
    b.sql("UPDATE task_runs SET status='done', ended_at=?, outcome='review_requested', summary=? WHERE id=?",
          (t, "Level 3 polish: ramps smoothed, two new checkpoints.", rid))
    b.sql("UPDATE tasks SET status='review' WHERE id='t_game'")
    b.event("t_game", "review_requested", {"summary": "level polish ready"}, run_id=rid, at=t)
    b.sql("UPDATE tasks SET status='running' WHERE id='t_guide'")
    rid = b.run("t_guide", "astra-ultimate", started=t, hb=t)
    b.event("t_guide", "claimed", {"run_id": rid}, run_id=rid, at=t)
    b.comment("t_guide", "astra-ultimate", "PLAN: one page per block, with a screenshot each.\n"
              "1) task wall 2) library 3) atrium 4) binding cheatsheet", at=t + 1)
    b.sql("UPDATE tasks SET status='done', completed_at=? WHERE id='t_inv'", (t,))
    log("wall-move: level polish -> review, placement guide -> doing, inventory pass -> done")


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
    for task in ("t_hq", "t_game", "t_review", "t_perm"):
        rid = run_id(b, task)
        outcome = "crashed" if task == "t_game" else "completed"
        b.sql("UPDATE task_runs SET status='done', ended_at=?, outcome=? WHERE id=?", (t - 400, outcome, rid))
        b.sql("UPDATE tasks SET status=? WHERE id=?", ("ready" if task == "t_game" else "done", task))
    b.event("t_game", "crashed", None, run_id=run_id(b, "t_game"), at=t - 400)
    log("crash: Sonnet's run crashed; everyone else finished")


def all_idle(root: Path) -> None:
    b = board(root)
    t = int(time.time())
    # move the crash out of the 30-minute error window and every finish out of the 5-minute done window
    b.sql("UPDATE task_runs SET ended_at=? WHERE ended_at IS NOT NULL", (t - 3 * 3600,))
    b.sql("UPDATE task_runs SET status='done', ended_at=?, outcome='completed' WHERE ended_at IS NULL", (t - 3 * 3600,))
    log("all-idle: no live runs, nothing waiting")


def all_waiting(root: Path) -> None:
    """Card 4: every on-shift agent waits on Eli at once (the stacked-at-one-spot bug scene)."""
    b = board(root)
    t = int(time.time())
    n = 0
    for i, prof in enumerate(PROFILES):
        if prof in OFF_SHIFT:
            continue
        tid_ = f"t_wait{i}"
        b.task(tid_, f"Decision fixture {i + 1}: pick an option", "blocked", prof, block_kind="needs_input", priority=50)
        b.event(tid_, "blocked", {"reason": f"QUESTION q{i + 1}: option A or option B? || CHOICES: A | B",
                                  "kind": "needs_input"}, at=t - i)
        n += 1
    log(f"all-waiting: {n} agents waiting on Eli")


def history(root: Path) -> None:
    """Card 4: old done cards (outside the 3-day task window), so 'done all time' > 'done, last 3 days'."""
    b = board(root)
    t = int(time.time())
    for i in range(40):
        tid_ = f"t_hist{i:02d}"
        b.task(tid_, f"Archived fixture card {i + 1}", "done", "claude-builder-mega")
        b.sql("UPDATE tasks SET completed_at=?, created_at=? WHERE id=?", (t - (5 + i) * 86400, t - (6 + i) * 86400, tid_))
    log("history: 40 done cards older than the task window")


PHASES = {"init": init, "progress": progress, "demo-ready": demo_ready, "unblock": unblock, "crash": crash,
          "all-idle": all_idle, "wall-move": wall_move, "all-waiting": all_waiting, "history": history}


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[2] not in PHASES:
        print(__doc__, file=sys.stderr)
        return 2
    PHASES[sys.argv[2]](Path(sys.argv[1]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
