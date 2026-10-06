"""Pure mapping: raw Hermes rows (sources.HermesData) -> AgentCraft protocol v1 entities.

No I/O here, so it is unit-testable with hand-built fixtures. Every string that reaches an
entity goes through redact.clean(). See ../MAPPING.md for the human-readable version.
"""

from __future__ import annotations

import hashlib
import re
from datetime import datetime
from typing import Any

from .redact import WITHHELD, clean, clean_excerpt, clean_id
from .sources import BoardData, HermesData

ADAPTER_VERSION = "hermes-adapter 0.1.0"

# profile name -> (display name, role, title). Unknown profiles get a title-cased name.
CAST: dict[str, tuple[str, str, str]] = {
    "default": ("Goon Goblin", "lead", "Front door / orchestrator"),
    "claude-builder": ("Opus Builder", "worker", "Builder (Opus)"),
    "claude-builder-sonnet": ("Sonnet Builder", "worker", "Builder (Sonnet)"),
    "claude-builder-mega": ("Mega Builder", "worker", "Builder (large tasks)"),
    "sol-reviewer": ("Sol Reviewer", "worker", "Code reviewer"),
    "sol-reviewer-highrisk": ("Sol HR Reviewer", "worker", "High-risk reviewer"),
    "luna-reviewer": ("Luna Reviewer", "worker", "Reviewer"),
    "astra-ultimate": ("Astra", "worker", "Generalist"),
    "gtnh-oracle-bridge": ("GTNH Oracle", "worker", "GTNH knowledge bridge"),
    "qwen-uncensored": ("Qwen", "worker", "Local model"),
    "venues-research-agent": ("Venue Scout", "worker", "Research"),
    "cron": ("Scheduler", "worker", "Hermes cron jobs"),
}

PALETTE = [
    "#2E78C6", "#C6452E", "#3FA34D", "#B8860B", "#8E44AD", "#16A085",
    "#D35400", "#2C3E50", "#C0392B", "#7F8C8D", "#E67E22", "#1ABC9C",
]
# hand-picked colours for the known cast (readable on a dark nameplate); others use PALETTE
CAST_COLORS: dict[str, str] = {
    "default": "#3FA34D",
    "claude-builder": "#D97757",
    "claude-builder-sonnet": "#E8A33D",
    "claude-builder-mega": "#C6452E",
    "sol-reviewer": "#2E78C6",
    "sol-reviewer-highrisk": "#5B8DEF",
    "luna-reviewer": "#9B6FD6",
    "astra-ultimate": "#1ABC9C",
    "gtnh-oracle-bridge": "#C9A227",
    "qwen-uncensored": "#95A5A6",
    "venues-research-agent": "#E67E22",
    "cron": "#9C9488",
}
ACCENT = "#F4EFE6"

TASK_STATUS = {
    "triage": "todo",
    "todo": "todo",
    "ready": "todo",
    "scheduled": "todo",
    "running": "doing",
    "review": "review",
    "done": "done",
    "blocked": "blocked",
    "archived": "cancelled",
}

ERROR_OUTCOMES = {"crashed", "timed_out", "gave_up", "spawn_failed", "protocol_violation"}
# protocol enums (docs/protocol.md): where an agent stands, and which states count as "working"
STATIONS = ("desk", "library", "terminal", "testbench", "mergestation", "meeting", "lounge", "user")
WORKING_STATES = {"thinking", "reading", "editing", "running", "testing"}
ACTIVE_WINDOW_S = 7 * 86400
RECENT_DONE_S = 5 * 60
RECENT_ERROR_S = 30 * 60
STALE_HEARTBEAT_S = 20 * 60

_PREFIX = re.compile(r"^\s*(PLAN|PROGRESS|HANDOFF|STEER[^:]*|ANSWER[^:]*|NOTE)\s*:\s*", re.I)
# Library (MemoryEntry) notes: which agent comments count as a plan / handoff / review note.
_NOTE = re.compile(r"^\s*(PLAN|HANDOFF|PASS|REVISE|VERIFICATION)\b", re.I)
NOTE_KIND = {"PLAN": "plan", "HANDOFF": "handoff", "PASS": "review", "REVISE": "review", "VERIFICATION": "review"}
NOTE_LABEL = {"plan": "Plan", "handoff": "Handoff", "review": "Verdict"}
LIBRARY_NOTES = 36  # newest plan/handoff/review notes
LIBRARY_DONE = 12  # newest done cards with a summary
LIBRARY_MAX = 64  # every library entry together
LIBRARY_BODY = 1200  # characters per entry body (after whole-source filtering)
WALL_COLUMNS = ("todo", "doing", "review", "done", "blocked")
_DECISION = re.compile(r"^\s*(QUESTION|PERMISSION|DEMO READY|REVISE|HELD|NEEDS INPUT)\b\s*([A-Za-z]?\d+)?\s*[:\-\u2014]?\s*", re.I)


def ms(epoch_s: Any) -> int:
    try:
        return int(float(epoch_s) * 1000)
    except (TypeError, ValueError):
        return 0


def color_for(agent_id: str) -> str:
    h = int(hashlib.sha1(agent_id.encode()).hexdigest(), 16)
    return PALETTE[h % len(PALETTE)]


def display(profile: str) -> tuple[str, str, str]:
    if profile in CAST:
        return CAST[profile]
    words = re.split(r"[-_]+", profile)
    return (" ".join(w.capitalize() for w in words if w)[:24] or profile, "worker", "Hermes profile")


def is_reviewer(profile: str) -> bool:
    return "review" in profile


def finalize_station(agent: dict[str, Any]) -> dict[str, Any]:
    """Station rule of last resort (MAPPING.md "Stations"): an off-shift agent is in the lounge;
    a station outside the protocol enum (or none) becomes ``desk`` while working, else ``lounge``.
    The in-world NPCs walk to the anchor of this station, so it must always be a valid enum value."""
    if not agent.get("active", True):
        agent["station"] = "lounge"
    elif agent.get("station") not in STATIONS:
        agent["station"] = "desk" if agent.get("state") in WORKING_STATES else "lounge"
    return agent


def short_title(title: str, limit: int = 60) -> str:
    return clean(title, limit)


def parse_decision_reason(reason: str) -> tuple[str, str, list[str]]:
    """'PERMISSION p1: run x || CHOICES: Approve | Deny' -> ('permission', 'run x', ['Approve','Deny']).

    The whole reason is checked and redacted BEFORE it is split into question and options, so a
    personal-notes hint or a secret in one part cannot be separated from the other part.
    """
    raw = reason or ""
    kind = "permission" if re.match(r"^\s*PERMISSION\b", raw, re.I) else "question"
    text = clean(raw, 0, keep_newlines=True)
    if text == WITHHELD:
        return kind, WITHHELD, []
    options: list[str] = []
    if "||" in text:
        text, rest = text.split("||", 1)
        m = re.search(r"CHOICES\s*:\s*(.*)$", rest, re.I | re.S)
        if m:
            options = [clean(o, 40) for o in m.group(1).split("|") if o.strip()][:5]
    return kind, text.strip(), options


def goal_id(board: str) -> str:
    """Protocol goal id of a Kanban board (one goal per board, see MAPPING.md "Goals")."""
    return f"board-{clean_id(board)}"


def board_task_ids(boards: list[BoardData]) -> dict[tuple[str, str], str]:
    """(board slug, card id) -> protocol task id. Card ids are kept as-is unless the same id
    exists on two boards, in which case both become ``<board>:<id>`` so neither overwrites the other."""
    seen: dict[str, int] = {}
    for b in boards:
        for t in b.tasks:
            seen[t["id"]] = seen.get(t["id"], 0) + 1
    out: dict[tuple[str, str], str] = {}
    for b in boards:
        for t in b.tasks:
            out[(b.slug, t["id"])] = t["id"] if seen[t["id"]] == 1 else f"{clean_id(b.slug)}:{t['id']}"
    return out


class Mapper:
    def __init__(self, cast_overrides: dict[str, dict[str, str]] | None = None) -> None:
        self.cast_overrides = cast_overrides or {}

    # -------------------------------------------------------------------------------------
    def build(self, data: HermesData) -> dict[str, Any]:
        now = data.now
        profiles = {p["name"]: p for p in data.profiles}
        agent_ids = {name: clean_id(name) for name in profiles}

        # Run, event and comment ids are per-board SQLite rowids and card ids are only unique per
        # board, so every row is tagged with its board ("_b") and every join below uses
        # (board, id) keys. Rows are copied, never mutated.
        out_id = board_task_ids(data.boards)
        tasks_by_key: dict[tuple[str, str], dict[str, Any]] = {}
        runs: list[dict[str, Any]] = []
        events: list[dict[str, Any]] = []
        comments: list[dict[str, Any]] = []
        links: list[tuple[str, str, str]] = []
        for b in data.boards:
            for t in b.tasks:
                tasks_by_key[(b.slug, t["id"])] = {**t, "_b": b.slug}
            runs += [{**r, "_b": b.slug} for r in b.runs]
            events += [{**e, "_b": b.slug} for e in b.events]
            comments += [{**c, "_b": b.slug} for c in b.comments]
            links += [(b.slug, parent, child) for parent, child in b.links]
        run_by_key = {(r["_b"], r["id"]): r for r in runs}

        def tkey(row: dict[str, Any]) -> tuple[str, str]:
            """(board, card id) of a task row or of the card a run/event/comment belongs to."""
            return (row["_b"], row["task_id"] if "task_id" in row else row["id"])

        def agent_of_profile(p: Any) -> str | None:
            return agent_ids.get(p) if isinstance(p, str) else None

        def event_agent(ev: dict[str, Any]) -> str | None:
            run = run_by_key.get((ev["_b"], ev.get("run_id"))) if ev.get("run_id") is not None else None
            if run and agent_of_profile(run.get("profile")):
                return agent_of_profile(run["profile"])
            pl = ev.get("payload") or {}
            for k in ("claimer", "actor", "implementer", "assignee"):
                a = agent_of_profile(pl.get(k)) if isinstance(pl, dict) else None
                if a:
                    return a
            t = tasks_by_key.get(tkey(ev))
            return agent_of_profile(t.get("assignee")) if t else None

        # ---- tasks --------------------------------------------------------------------------
        last_update: dict[tuple[str, str], float] = {}
        for row in events + comments:
            k = tkey(row)
            last_update[k] = max(last_update.get(k, 0), row["created_at"] or 0)
        latest_block: dict[tuple[str, str], dict[str, Any]] = {}
        for ev in events:
            if ev["kind"] == "blocked":
                latest_block[tkey(ev)] = ev
        latest_summary: dict[tuple[str, str], str] = {}
        for r in runs:
            if r.get("summary"):
                latest_summary[tkey(r)] = r["summary"]

        deps: dict[tuple[str, str], list[str]] = {}
        for slug, parent, child in links:
            if (slug, parent) in tasks_by_key and (slug, child) in tasks_by_key:
                deps.setdefault((slug, child), []).append(out_id[(slug, parent)])

        tasks: list[dict[str, Any]] = []
        for key, t in tasks_by_key.items():
            created = t.get("created_at") or 0
            task: dict[str, Any] = {
                "id": out_id[key],
                "title": clean(t.get("title"), 120) or t["id"],
                "status": TASK_STATUS.get(t.get("status") or "", "todo"),
                "deps": sorted(deps.get(key, [])),
                "priority": int(t.get("priority") or 0),
                "ci": "unknown",
                "createdBy": agent_of_profile(t.get("created_by")) or "user",
                "createdAt": ms(created),
                "updatedAt": ms(max(created, last_update.get(key, 0), t.get("completed_at") or 0, t.get("started_at") or 0)),
                "board": t["_b"],
                "goalId": goal_id(t["_b"]),
            }
            if t.get("body"):
                task["description"] = clean(t["body"], 280)
            a = agent_of_profile(t.get("assignee"))
            if a:
                task["assignee"] = a
            if t.get("branch_name"):
                task["branch"] = clean(t["branch_name"], 80)
            if task["status"] == "blocked" and key in latest_block:
                reason = (latest_block[key].get("payload") or {}).get("reason")
                if reason:
                    task["blockedReason"] = clean(reason, 200)
            summ = latest_summary.get(key) or t.get("result")
            if summ:
                task["summary"] = clean(summ, 280)
            tasks.append(task)
        tasks.sort(key=lambda x: x["updatedAt"], reverse=True)
        all_tasks = list(tasks)  # goal counts use every card in the window, not just the 200 sent
        tasks = tasks[:200]

        # ---- decisions (needs_input blocks) -------------------------------------------------
        decisions: list[dict[str, Any]] = []
        for ev in events:
            if ev["kind"] != "blocked":
                continue
            pl = ev.get("payload") or {}
            if pl.get("kind") not in (None, "needs_input"):
                continue
            key = tkey(ev)
            t = tasks_by_key.get(key)
            if not t:
                continue
            reason = pl.get("reason") or ""
            kind, question, options = parse_decision_reason(reason)
            is_open = t.get("status") == "blocked" and latest_block.get(key) is ev
            agent = event_agent(ev) or agent_of_profile(t.get("assignee")) or "default"
            d = {
                "id": f"d-{clean_id(ev['_b'])}-{ev['id']}",
                "agentId": agent,
                "kind": kind,
                "question": clean(question, 240) or "needs input",
                "options": options,
                "context": clean(f"{t.get('title')} ({out_id[key]})", 160),
                "status": "open" if is_open else "answered",
                "taskId": out_id[key],
                "createdAt": ms(ev["created_at"]),
            }
            decisions.append(d)
        decisions.sort(key=lambda d: d["createdAt"])
        open_d = [d for d in decisions if d["status"] == "open"]
        done_d = [d for d in decisions if d["status"] != "open"][-20:]
        decisions = sorted(open_d + done_d, key=lambda d: d["createdAt"])

        # ---- goals (one per board) and the library ------------------------------------------
        goals = self._goals(data.boards, all_tasks, decisions)
        memory = self._library(
            tasks_by_key, out_id, agent_ids, comments, latest_summary, all_tasks, goals, decisions)

        # ---- agents -------------------------------------------------------------------------
        agents: list[dict[str, Any]] = []
        logs: dict[str, list[dict[str, Any]]] = {}
        for name, prof in profiles.items():
            aid = agent_ids[name]
            agents.append(self._agent(name, aid, prof, now, tasks_by_key, out_id, runs, events, comments, latest_block))
        agents.append(self._cron_agent(data.cron, now))
        for a in agents:
            finalize_station(a)

        # ---- logs ---------------------------------------------------------------------------
        def add_log(aid: str | None, ts: float, kind: str, text: str) -> None:
            if not aid or not text:
                return
            logs.setdefault(aid, []).append({"ts": ms(ts), "kind": kind, "text": text})

        for ev in events:
            key = tkey(ev)
            t = tasks_by_key.get(key)
            if not t:
                continue
            tid = out_id[key]
            title = short_title(t.get("title") or t["id"], 50)
            k = ev["kind"]
            pl = ev.get("payload") or {}
            aid = event_agent(ev)
            if k == "claimed":
                add_log(aid, ev["created_at"], "tool", f"claimed {tid} {title}")
            elif k == "completed":
                add_log(aid, ev["created_at"], "result", f"completed {tid}: {clean(pl.get('summary') or title, 200)}")
            elif k == "review_requested":
                add_log(aid, ev["created_at"], "result", f"review requested {tid}: {clean(pl.get('summary') or title, 200)}")
            elif k == "blocked":
                add_log(aid, ev["created_at"], "text", f"blocked {tid}: {clean(pl.get('reason'), 200)}")
            elif k in ERROR_OUTCOMES:
                add_log(aid, ev["created_at"], "error", f"{k.replace('_', ' ')} on {tid} {title}")
            elif k == "heartbeat" and isinstance(pl, dict) and pl.get("note"):
                add_log(aid, ev["created_at"], "text", clean(pl.get("note"), 200))
        for c in comments:
            aid = agent_of_profile(c.get("author"))
            key = tkey(c)
            if aid and key in tasks_by_key:
                add_log(aid, c["created_at"], "text", f"{out_id[key]}: {clean(c.get('body'), 300, keep_newlines=True)}")
        log_list = []
        for aid, entries in logs.items():
            entries.sort(key=lambda e: e["ts"])
            log_list.append({"agentId": aid, "entries": entries[-60:]})

        # ---- feed ---------------------------------------------------------------------------
        names = {a["id"]: a["name"] for a in agents}
        feed: list[dict[str, Any]] = []
        for ev in events:
            t = tasks_by_key.get(tkey(ev))
            if not t:
                continue
            item = self._feed_item(ev, t, event_agent(ev), names)
            if item:
                feed.append(item)
        for job in data.cron:
            ts = _iso_to_epoch(job.get("last_run_at"))
            if ts and now - ts < 3 * 86400:
                status = clean(job.get("last_status") or "?", 16)
                feed.append({
                    "ts": ms(ts),
                    "kind": "error" if status not in ("ok", "?") else "system",
                    "text": clean(f"cron {job.get('name')} ran: {status}", 120),
                    "agentId": "cron",
                })
        feed.sort(key=lambda f: f["ts"])
        feed = feed[-200:]

        status = {
            "version": ADAPTER_VERSION,
            "backend": "claude",
            "auth": "ok",
            "message": clean(f"Hermes ai-ops (read-only): {len(data.boards)} board(s), {len(profiles)} profiles", 120),
            "adapter": "hermes",
            "readOnly": True,
        }
        return {
            "foreman": status,
            "agents": agents,
            "tasks": tasks,
            "decisions": decisions,
            "repos": [],
            "memory": memory,
            "goals": goals,
            "feed": feed,
            "logs": log_list,
        }

    # -------------------------------------------------------------------------------------
    @staticmethod
    def _goals(boards: list[BoardData], tasks: list[dict[str, Any]], decisions: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """One Goal per board: progress = done / (open cards + done cards), cancelled left out.

        Open cards are all in the task window; done cards are counted over the WHOLE board
        (``BoardData.done_total``, a count only) so the ring shows the board's overall progress
        rather than "done in the last few days". Hermes has no goal object; a board is the closest
        thing to "the objective the team works on". Extra fields (ignored by upstream receivers):
        ``board``, ``counts`` per wall column, ``total`` and ``openDecisions`` (a count only: the
        question text is never on a goal).
        """
        board_of = {t["id"]: t["board"] for t in tasks}
        out: list[dict[str, Any]] = []
        for b in boards:
            mine = [t for t in tasks if t["board"] == b.slug and t["status"] != "cancelled"]
            counts = {c: 0 for c in WALL_COLUMNS}
            for t in mine:
                counts[t["status"]] = counts.get(t["status"], 0) + 1
            if b.done_total is not None:
                counts["done"] = max(counts["done"], b.done_total)
            total = sum(counts.values())
            done = counts["done"]
            n_open = sum(1 for d in decisions if d["status"] == "open" and board_of.get(d.get("taskId", "")) == b.slug)
            out.append({
                "id": goal_id(b.slug),
                "text": clean(f"Board {b.slug}", 80),
                "progress": round(done / total, 4) if total else 0.0,
                "status": "planning" if not total else "done" if done == total else "active",
                "createdAt": min((t["createdAt"] for t in mine), default=0),
                "updatedAt": max((t["updatedAt"] for t in mine), default=0),
                "board": b.slug,
                "counts": counts,
                "total": total,
                "openDecisions": n_open,
            })
        out.sort(key=lambda g: (g["createdAt"], g["id"]))  # protocol: oldest first
        return out

    @staticmethod
    def _library(
        tasks_by_key: dict[tuple[str, str], dict[str, Any]],
        out_id: dict[tuple[str, str], str],
        agent_ids: dict[str, str],
        comments: list[dict[str, Any]],
        latest_summary: dict[tuple[str, str], str],
        tasks: list[dict[str, Any]],
        goals: list[dict[str, Any]],
        decisions: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """Read-only Library (protocol ``memory``) built ONLY from text the adapter already sends in
        other places, now at a longer length (MAPPING.md "Library"):

        * plan / handoff / review-verdict comments written by agent profiles (logs carry the same
          comments today); comments by anyone else (Eli, the intake) are left out;
        * the result summaries of the newest done cards (Task.summary today);
        * one overview per board (card titles per column, Task.title today);
        * the question of every open decision (Decision.question today).

        Memory files, ``personal-*.md``, cron prompts and credentials are never read by the adapter
        at all (sources.py), so they cannot reach the library. Every body is filtered as a WHOLE
        source by redact.clean() before it is cut to LIBRARY_BODY characters.
        """
        entries: list[dict[str, Any]] = []
        # 1. newest plan / handoff / review note per (card, kind, author)
        latest: dict[tuple[str, str, str, str], dict[str, Any]] = {}
        for c in comments:
            aid = agent_ids.get(c.get("author")) if isinstance(c.get("author"), str) else None
            key = (c["_b"], c["task_id"])
            if not aid or key not in tasks_by_key:
                continue
            m = _NOTE.match(c.get("body") or "")
            if not m:
                continue
            kind = NOTE_KIND[m.group(1).upper()]
            k = (key[0], key[1], kind, aid)
            if k not in latest or (c["created_at"] or 0) >= (latest[k]["created_at"] or 0):
                latest[k] = c
        notes: list[dict[str, Any]] = []
        for (board, card, kind, aid), c in latest.items():
            tid = out_id[(board, card)]
            title = short_title(tasks_by_key[(board, card)].get("title") or card, 70)
            notes.append({
                "id": f"{aid}/{kind}-{clean_id(tid)}",
                "scope": aid,
                "title": clean(f"{NOTE_LABEL[kind]}: {title}", 100),
                "body": clean(c.get("body"), LIBRARY_BODY, keep_newlines=True),
                "updated": ms(c["created_at"]),
                "author": aid,
                "kind": kind,
                "taskId": tid,
                "board": board,
            })
        notes.sort(key=lambda e: e["updated"], reverse=True)
        entries += notes[:LIBRARY_NOTES]

        # 2. result summaries of the newest done cards
        done = [t for t in tasks if t["status"] == "done" and t.get("summary")]
        done.sort(key=lambda t: t["updatedAt"], reverse=True)
        key_of = {out_id[k]: k for k in out_id}
        for t in done[:LIBRARY_DONE]:
            k = key_of.get(t["id"])
            raw = (latest_summary.get(k) if k else None) or (tasks_by_key.get(k) or {}).get("result") or t["summary"]
            e: dict[str, Any] = {
                "id": f"shared/done-{clean_id(t['id'])}",
                "scope": "shared",
                "title": clean(f"Done: {t['title']}", 100),
                "body": clean(raw, LIBRARY_BODY, keep_newlines=True),
                "updated": t["updatedAt"],
                "kind": "summary",
                "taskId": t["id"],
                "board": t["board"],
            }
            if t.get("assignee"):
                e["author"] = t["assignee"]
            entries.append(e)

        # 3. one overview per board: card titles per column (already on every Task)
        for g in goals:
            mine = [t for t in tasks if t["board"] == g["board"]]
            lines = [f"{g['text']}: {g['counts']['done']} of {g['total']} cards done, "
                     f"{g['openDecisions']} decision(s) waiting on Eli."]
            for col in ("doing", "review", "blocked", "todo"):
                col_tasks = sorted((t for t in mine if t["status"] == col), key=lambda t: (-t["priority"], -t["updatedAt"]))
                if not col_tasks:
                    continue
                lines.append("")
                lines.append(f"{col.capitalize()} ({len(col_tasks)}):")
                lines += [f"- {short_title(t['title'], 70)}" for t in col_tasks[:8]]
                if len(col_tasks) > 8:
                    lines.append(f"- ... {len(col_tasks) - 8} more")
            entries.append({
                "id": f"shared/board-{clean_id(g['board'])}",
                "scope": "shared",
                "title": clean(f"Overview: {g['text']}", 100),
                "body": clean("\n".join(lines), LIBRARY_BODY, keep_newlines=True),
                "updated": g["updatedAt"],
                "kind": "overview",
                "board": g["board"],
            })

        # 4. open decisions: the (already filtered) question, read-only; nothing in-game answers
        board_of = {t["id"]: t["board"] for t in tasks}
        for d in decisions:
            if d["status"] != "open":
                continue
            body = d["question"] + ("\n\nChoices: " + " | ".join(d["options"]) if d["options"] else "")
            entries.append({
                "id": f"{d['agentId']}/decision-{clean_id(d['id'])}",
                "scope": d["agentId"],
                "title": clean(f"Waiting on Eli: {d.get('context') or d['id']}", 100),
                "body": clean(body, LIBRARY_BODY, keep_newlines=True),
                "updated": d["createdAt"],
                "author": d["agentId"],
                "kind": "decision",
                "taskId": d.get("taskId", ""),
                "board": board_of.get(d.get("taskId", ""), ""),
            })
        entries.sort(key=lambda e: e["updated"], reverse=True)
        return entries[:LIBRARY_MAX]

    # -------------------------------------------------------------------------------------
    def _agent(
        self,
        profile: str,
        aid: str,
        prof: dict[str, Any],
        now: float,
        tasks_by_key: dict[tuple[str, str], dict[str, Any]],
        out_id: dict[tuple[str, str], str],
        runs: list[dict[str, Any]],
        events: list[dict[str, Any]],
        comments: list[dict[str, Any]],
        latest_block: dict[tuple[str, str], dict[str, Any]],
    ) -> dict[str, Any]:
        """Agent entity for one profile. ``runs``/``events``/``comments`` rows carry their board in
        ``_b``; all task lookups use (board, card id)."""
        name, role, title = display(profile)
        ov = self.cast_overrides.get(profile, {})
        name, role, title = ov.get("name", name), ov.get("role", role), ov.get("title", title)
        agent: dict[str, Any] = {
            "id": aid,
            "name": clean(name, 24),
            "role": role if role in ("lead", "worker") else "worker",
            "title": clean(title, 40),
            "color": ov.get("color") or CAST_COLORS.get(profile) or color_for(aid),
            "accent": ACCENT,
            "skin": aid,
            "state": "idle",
            "activity": "idle",
            "station": "lounge",
            "paused": False,
            "active": True,
        }
        mine = [r for r in runs if r.get("profile") == profile]
        live = [r for r in mine if r.get("ended_at") is None and r.get("status") == "running"]
        last_seen = max([prof.get("last_seen") or 0] + [r.get("last_heartbeat_at") or r.get("ended_at") or r.get("started_at") or 0 for r in mine])
        agent["active"] = profile == "default" or (now - last_seen) < ACTIVE_WINDOW_S

        def task_of_run(r: dict[str, Any]) -> tuple[tuple[str, str], dict[str, Any] | None]:
            key = (r["_b"], r["task_id"])
            return key, tasks_by_key.get(key)

        def tid(key: tuple[str, str]) -> str:
            return out_id.get(key, key[1])

        if live:
            run = max(live, key=lambda r: r.get("started_at") or 0)
            key, t = task_of_run(run)
            ttl = short_title((t or {}).get("title") or run["task_id"], 40)
            agent["taskId"] = tid(key)
            agent["state"] = "reading" if is_reviewer(profile) else "editing"
            agent["station"] = "library" if is_reviewer(profile) else "desk"
            note = self._latest_note(profile, run, events, comments)
            if note:
                agent["activity"] = clean(note, 48)
            else:
                agent["activity"] = clean(("reviewing " if is_reviewer(profile) else "working on ") + ttl, 48)
            hb = run.get("last_heartbeat_at") or run.get("started_at") or 0
            if now - hb > STALE_HEARTBEAT_S:
                agent["state"] = "thinking"
            return agent

        # waiting on Eli: a needs_input block on a task this profile owns
        waiting = [
            key for key, t in tasks_by_key.items()
            if t.get("status") == "blocked" and t.get("assignee") == profile and t.get("block_kind") in (None, "needs_input")
        ]
        if waiting:
            key = max(waiting, key=lambda k: (latest_block.get(k) or {}).get("created_at") or 0)
            t = tasks_by_key[key]
            # waiting on Eli is never "off shift": the agent stands at the user station with a "!"
            agent.update(state="waiting_user", station="user", taskId=tid(key), active=True)
            reason = ((latest_block.get(key) or {}).get("payload") or {}).get("reason") or ""
            label = _DECISION.match(reason)
            head = label.group(1).title() if label else "Waiting"
            agent["activity"] = clean(f"{head}: {short_title(t.get('title') or t['id'], 40)}", 48)
            return agent

        if mine:
            last = max(mine, key=lambda r: r.get("ended_at") or r.get("started_at") or 0)
            ended = last.get("ended_at") or 0
            key, t = task_of_run(last)
            ttl = short_title((t or {}).get("title") or last["task_id"], 36)
            if last.get("outcome") in ERROR_OUTCOMES and now - ended < RECENT_ERROR_S:
                agent.update(state="error", station="lounge", activity=clean(f"{last['outcome']}: {ttl}", 48), taskId=tid(key))
                return agent
            if last.get("outcome") in ("completed", "review_requested") and now - ended < RECENT_DONE_S:
                agent.update(state="done", station="lounge", activity=clean(f"finished {ttl}", 48), taskId=tid(key))
                return agent
            agent["activity"] = clean(f"idle - last: {ttl}", 48)
        elif not agent["active"]:
            agent["activity"] = "off shift"
        return agent

    @staticmethod
    def _latest_note(profile: str, run: dict[str, Any], events: list[dict[str, Any]], comments: list[dict[str, Any]]) -> str | None:
        """Newest PROGRESS/PLAN comment or heartbeat note of ``run`` (same board only), reduced to
        one safe display line. The whole comment/note is filtered before the line is cut out."""
        started = run.get("started_at") or 0
        board = run["_b"]
        best: tuple[float, str] | None = None
        for c in comments:
            if c["_b"] == board and c["task_id"] == run["task_id"] and c.get("author") == profile and (c["created_at"] or 0) >= started:
                body = (c.get("body") or "").strip()
                if not body or body.upper().startswith("HANDOFF"):
                    continue
                if best is None or c["created_at"] >= best[0]:
                    best = (c["created_at"], clean_excerpt(body, 200, _PREFIX))
        for ev in events:
            if ev["kind"] == "heartbeat" and ev["_b"] == board and ev.get("run_id") == run["id"]:
                note = (ev.get("payload") or {}).get("note") if isinstance(ev.get("payload"), dict) else None
                if note and (best is None or ev["created_at"] >= best[0]):
                    best = (ev["created_at"], clean_excerpt(note, 200, _PREFIX))
        return best[1] if best else None

    def _cron_agent(self, jobs: list[dict[str, Any]], now: float) -> dict[str, Any]:
        name, role, title = display("cron")
        agent: dict[str, Any] = {
            "id": "cron",
            "name": name,
            "role": role,
            "title": title,
            "color": CAST_COLORS["cron"],
            "accent": ACCENT,
            "skin": "cron",
            "state": "idle",
            "activity": "no cron jobs",
            "station": "terminal",
            "paused": False,
            "active": bool(jobs),
        }
        enabled = [j for j in jobs if j.get("enabled")]
        running = [j for j in enabled if str(j.get("state") or "").lower() == "running"]
        failing = [j for j in enabled if (j.get("failure_streak") or 0) > 0 or (j.get("last_status") not in (None, "ok"))]
        upcoming: list[tuple[float, dict[str, Any]]] = []
        for j in enabled:
            ts_next = _iso_to_epoch(j.get("next_run_at"))
            if ts_next:
                upcoming.append((ts_next, j))
        upcoming.sort(key=lambda x: x[0])
        if running:
            agent.update(state="running", activity=clean(f"running {running[0].get('name')}", 48))
        elif failing:
            agent.update(state="error", activity=clean(f"{len(failing)} failing: {failing[0].get('name')}", 48))
        elif upcoming:
            ts, j = upcoming[0]
            agent["activity"] = clean(f"next {j.get('name')} {datetime.fromtimestamp(ts).strftime('%H:%M')}", 48)
        elif jobs:
            agent["activity"] = f"{len(enabled)} jobs enabled"
        return agent

    @staticmethod
    def _feed_item(ev: dict[str, Any], t: dict[str, Any], aid: str | None, names: dict[str, str]) -> dict[str, Any] | None:
        k = ev["kind"]
        pl = ev.get("payload") or {}
        who = names.get(aid or "", "Someone")
        title = short_title(t.get("title") or t["id"], 60)
        text: str | None = None
        kind = "task"
        if k == "created":
            text = f"New card: {title}"
        elif k == "claimed":
            text = f"{who} picked up {title}"
        elif k == "completed":
            text = f"{who} finished {title}"
        elif k == "review_requested":
            text = f"{who} asked for review: {title}"
        elif k == "changes_requested":
            text = f"Changes requested on {title}"
        elif k == "blocked":
            kind = "decision" if (isinstance(pl, dict) and pl.get("kind") in (None, "needs_input")) else "task"
            text = f"{who} is waiting: {title}"
        elif k == "unblocked":
            text = f"Unblocked: {title}"
        elif k in ERROR_OUTCOMES:
            kind = "error"
            text = f"{who}: {k.replace('_', ' ')} on {title}"
        elif k == "assigned":
            text = f"{title} assigned to {names.get(clean_id(pl.get('assignee') or ''), pl.get('assignee') or '?')}"
        if not text:
            return None
        item: dict[str, Any] = {"ts": ms(ev["created_at"]), "kind": kind, "text": clean(text, 140)}
        if aid:
            item["agentId"] = aid
        return item


def _iso_to_epoch(s: Any) -> float | None:
    if not s or not isinstance(s, str):
        return None
    try:
        return datetime.fromisoformat(s).timestamp()
    except ValueError:
        return None
