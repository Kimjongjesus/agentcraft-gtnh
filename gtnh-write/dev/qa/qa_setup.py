#!/usr/bin/env python3
"""QA helpers for card 7 (throwaway data only; placeholders; nothing here touches a real Hermes or a real world).

    qa_setup.py key      <file>                         # 32 random bytes as hex, mode 600
    qa_setup.py policy   <repo> <out.json> <owner-uuid> [--chat]   # QA-only control policy: actors = [owner]
    qa_setup.py fixture  <repo> <dir>                   # a throwaway Hermes home the READ adapter can serve
    qa_setup.py board    <repo> <out.json>              # the control service's example board + one more dispatchable card
    qa_setup.py offline-uuid <name>                     # the UUID an offline-mode server gives <name> (version 3)

The fixture matches hermes_control/fixtures/board.example.json: cards t-demo-1..8 on board "main" and the four
open decisions with ids d-main-101..104 (the read adapter builds decision ids as d-<board>-<event id>), so the
decision screen shows the same decisions the control service's fixture board knows.
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import sys
import time
import uuid
from pathlib import Path


def cmd_key(path: str) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(secrets.token_hex(32) + "\n")
    os.chmod(p, 0o600)
    print(f"key file {p} (mode 600)")


def cmd_policy(repo: str, out: str, owner: str, chat: bool) -> None:
    src = Path(repo) / "hermes-adapter" / "hermes_control" / "policy.example.json"
    pol = json.loads(src.read_text())
    pol["actors"] = [owner]
    caps = pol["capabilities"]
    for name in ("decision.answer", "card.create", "card.edit", "card.dispatch", "service.restart", "cron.run"):
        caps[name]["enabled"] = True
    if chat:
        caps["agent.chat"]["enabled"] = True
        caps["agent.ask"]["enabled"] = True
    p = Path(out)
    p.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(pol, f, indent=2)
        f.write("\n")
    os.chmod(p, 0o600)
    print(f"policy {p}: actors={pol['actors']} enabled={[k for k, v in caps.items() if v.get('enabled')]}")


def cmd_fixture(repo: str, d: str) -> None:
    sys.path.insert(0, str(Path(repo) / "hermes-adapter" / "tests"))
    from fixture import BoardFixture  # noqa: E402

    root = Path(d)
    home = root / "hermes-home"
    if home.exists():
        raise SystemExit(f"refusing: {home} already exists (use a fresh scratch dir)")
    now = int(time.time())
    profiles = ["builder-a", "builder-b", "reviewer-a", "helper-a"]
    colours = ["#2E78C6", "#D97757", "#9B6FD6", "#1ABC9C"]
    for p in profiles:
        pd = home / "profiles" / p
        pd.mkdir(parents=True)
        (pd / "state.db").write_bytes(b"")  # "seen recently" (content never read)
        os.utime(pd / "state.db", (now, now))
    (home / "cron").mkdir(parents=True)
    (home / "cron" / "jobs.json").write_text('{"jobs": []}')
    (home / "agentcraft-cast.json").write_text(json.dumps({p: {"color": c} for p, c in zip(profiles, colours)}))
    b = BoardFixture(home / "kanban" / "boards" / "main" / "kanban.db", now)
    b.task("t-demo-1", "Add the placeholder widget", "todo", None, body="Placeholder card body. Dispatchable: status todo, no assignee, no run.", priority=10)
    waits = [
        ("t-demo-2", "Pick a placeholder option", 101, "QUESTION q1: Which placeholder option should the widget use? || CHOICES: Option A | Option B | Option C"),
        ("t-demo-3", "Name the placeholder module", 102, "QUESTION q2: What should the placeholder module be called?"),
        ("t-demo-4", "Run the placeholder command", 103, "PERMISSION p1: run the placeholder command on host-a || CHOICES: Approve | Deny"),
        ("t-demo-5", "Placeholder feature demo", 104, "DEMO READY h1: the placeholder feature is ready for review || CHOICES: Send to review | Revise"),
    ]
    for i, (tid, title, ev, reason) in enumerate(waits):
        b.task(tid, title, "blocked", "builder-a" if i % 2 == 0 else "builder-b", body="Waiting on the player.", block_kind="needs_input")
        b.sql(
            "INSERT INTO task_events (id, task_id, run_id, kind, payload, created_at) VALUES (?,?,?,?,?,?)",
            (ev, tid, None, "blocked", json.dumps({"reason": reason, "kind": "needs_input"}), now - 300 + i * 20),
        )
    b.task("t-demo-6", "Placeholder build in progress", "running", "builder-a", body="A running card: edits are refused, comments are fine.", priority=50)
    rid = b.run("t-demo-6", "builder-a", started=now - 600, hb=now)
    b.event("t-demo-6", "claimed", {"run_id": rid}, run_id=rid, at=now - 600)
    b.comment("t-demo-6", "builder-a", "PROGRESS: wiring the placeholder", at=now - 30)
    b.task("t-demo-7", "Placeholder finished work", "done", "builder-b", body="Done.")
    b.task("t-demo-8", "Add the second placeholder widget", "todo", None, body="A second dispatchable card (the control service allows one dispatch per card per 10 minutes).", priority=5)
    b.run("t-demo-7", "builder-b", status="done", started=now - 7200, ended=now - 3600, outcome="completed", summary="done")
    print(f"fixture {home}: 8 cards, open decisions d-main-101..104")


def cmd_board(repo: str, out: str) -> None:
    """The control service's example board fixture plus a second dispatchable card (QA copy, outside the repo)."""
    src = Path(repo) / "hermes-adapter" / "hermes_control" / "fixtures" / "board.example.json"
    board = json.loads(src.read_text())
    board["boards"]["main"]["cards"].append({"id": "t-demo-8", "title": "Add the second placeholder widget", "body": "A second dispatchable card.", "status": "todo", "priority": 5, "model": "model-a"})
    p = Path(out)
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(board, f, indent=2)
        f.write("\n")
    print(f"board fixture {p}: {len(board['boards']['main']['cards'])} cards")


def cmd_offline_uuid(name: str) -> None:
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    print(uuid.UUID(bytes=bytes(h)))


def main(argv: list[str]) -> int:
    if len(argv) >= 3 and argv[1] == "key":
        cmd_key(argv[2])
    elif len(argv) >= 5 and argv[1] == "policy":
        cmd_policy(argv[2], argv[3], argv[4], "--chat" in argv[5:])
    elif len(argv) == 4 and argv[1] == "fixture":
        cmd_fixture(argv[2], argv[3])
    elif len(argv) == 4 and argv[1] == "board":
        cmd_board(argv[2], argv[3])
    elif len(argv) == 3 and argv[1] == "offline-uuid":
        cmd_offline_uuid(argv[2])
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
