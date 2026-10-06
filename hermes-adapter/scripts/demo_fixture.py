"""Scripted demo: a synthetic Hermes home whose 'claude-builder' agent walks through states.

Writes ONLY into the directory given as the first argument (a throwaway fixture built from
tests/fixture.py); it never touches the real Hermes. Run the adapter against it with
``--hermes-home <dir>/.hermes`` to watch NPC state changes in-game.

    python3 scripts/demo_fixture.py /tmp/acdemo [step-seconds]
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE / "tests"))

from fixture import Fixture  # noqa: E402


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def main() -> int:
    root = Path(sys.argv[1])
    step = float(sys.argv[2]) if len(sys.argv) > 2 else 20.0
    if (root / ".hermes").exists():
        print(f"refusing: {root}/.hermes already exists (use a fresh scratch dir)", file=sys.stderr)
        return 2
    root.mkdir(parents=True, exist_ok=True)
    now = int(time.time())
    f = Fixture(root, now)
    f.task("t_demo", "Demo card: AgentCraft GTNH viewer", "running", "claude-builder", priority=50)
    rid = f.run("t_demo", "claude-builder", started=now - 5, hb=now)
    f.event("t_demo", "claimed", {"run_id": rid}, run_id=rid, at=now - 5)
    f.comment("t_demo", "claude-builder", "PROGRESS: wiring the websocket bridge", at=now)
    log(f"fixture ready at {root}/.hermes: claude-builder editing")
    time.sleep(step)

    t = int(time.time())
    f.comment("t_demo", "claude-builder", "PROGRESS: spawning the NPC in GTNH", at=t)
    log("claude-builder activity -> spawning the NPC in GTNH")
    time.sleep(step)

    t = int(time.time())
    f.sql("UPDATE task_runs SET status='blocked', ended_at=?, outcome='blocked' WHERE id=?", (t, rid))
    f.sql("UPDATE tasks SET status='blocked', block_kind='needs_input' WHERE id='t_demo'")
    f.event("t_demo", "blocked", {"reason": "DEMO READY d1: NPC shows live Hermes state || CHOICES: Send to review | Revise", "kind": "needs_input"}, run_id=rid, at=t)
    log("claude-builder -> waiting_user (DEMO READY)")
    time.sleep(step)

    t = int(time.time())
    f.sql("UPDATE tasks SET status='done', completed_at=? WHERE id='t_demo'", (t,))
    f.event("t_demo", "unblocked", None, at=t)
    rid2 = f.run("t_demo", "claude-builder", status="done", started=t - 2, ended=t, outcome="completed", summary="viewer foundation done")
    f.event("t_demo", "completed", {"summary": "viewer foundation done"}, run_id=rid2, at=t)
    log("claude-builder -> done")
    time.sleep(step)
    log("demo finished")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
