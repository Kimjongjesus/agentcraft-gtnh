"""World journal: a local history of the factory for agents (SQLite, outside any repository).

What it keeps, written by the adapter's world hub after every capture:

* **events**: the ``world.event`` changes (what appeared, started, stopped, broke, recovered; power
  and AE2 transitions; coverage gaps; game server restarts), with their location data;
* **samples**: one row of numbers per capture (machine counts by state, AE and EU power, CPUs):
  the power and activity trends;
* **snapshots**: the full normalised model (journal-only fields included) every
  ``snapshot_every_s`` seconds and at every new telemetry session, zlib-compressed.

Retention is enforced on open and periodically: age limits per table, row caps, a total size cap
(oldest snapshots go first). The journal may contain player names and coordinates, so the file is
created ``0600`` in a ``0700`` directory and is refused inside the adapter's own git checkout.
Nothing here talks to the game; "read-only" means read-only towards the world.

Query side: :meth:`WorldJournal.query_events`, :meth:`latest_snapshot`, :meth:`trend`,
:meth:`stats`, and the CLI ``python3 -m hermes_adapter.journal query --since 2h`` (``--json`` for
agents, ``--public`` to drop names and coordinates before pasting anywhere).
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
import threading
import time
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

SCHEMA_VERSION = 1
ENV_PATH = "AGENTCRAFT_WORLD_JOURNAL"
SEVERITY_RANK = {"info": 0, "warn": 1, "critical": 2}
MAX_QUERY_LIMIT = 5000
MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
_DAY_MS = 86_400_000


class JournalError(Exception):
    """Unusable journal location or file. Message is safe to log."""


@dataclass
class Retention:
    max_age_days: float = 30.0
    snapshot_max_age_days: float = 7.0
    max_snapshots: int = 1000
    max_events: int = 200_000
    max_samples: int = 200_000
    max_bytes: int = 256 * 1024 * 1024
    snapshot_every_s: float = 600.0
    prune_every_s: float = 3600.0


def default_path() -> Path:
    env = os.environ.get(ENV_PATH)
    if env:
        return Path(env).expanduser()
    state = os.environ.get("XDG_STATE_HOME") or str(Path.home() / ".local" / "state")
    return Path(state) / "agentcraft-gtnh" / "world-journal.sqlite3"


def _repo_root() -> Path | None:
    """The git checkout this adapter runs from, if any."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / ".git").exists():
            return parent
    return None


def check_location(path: Path, allow_in_repo: bool = False) -> Path:
    resolved = path.expanduser().resolve()
    root = _repo_root()
    if root is not None and not allow_in_repo and (resolved == root or root in resolved.parents):
        raise JournalError("refusing to keep the world journal inside the adapter's git checkout")
    return resolved


_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, session TEXT NOT NULL, kind TEXT NOT NULL,
  severity TEXT NOT NULL, subject TEXT NOT NULL, text TEXT NOT NULL, data TEXT);
CREATE INDEX IF NOT EXISTS events_ts ON events (ts);
CREATE INDEX IF NOT EXISTS events_subject ON events (subject, ts);
CREATE INDEX IF NOT EXISTS events_kind ON events (kind, ts);
CREATE TABLE IF NOT EXISTS samples (
  ts INTEGER NOT NULL, session TEXT NOT NULL, machines INTEGER, running INTEGER, idle INTEGER,
  problem INTEGER, maintenance INTEGER, unformed INTEGER, unknown INTEGER,
  ae_stored REAL, ae_max REAL, ae_usage REAL, ae_injection REAL, ae_powered INTEGER,
  eu_consuming INTEGER, eu_generating INTEGER, eu_stored INTEGER, eu_capacity INTEGER,
  cpus INTEGER, busy_cpus INTEGER);
CREATE INDEX IF NOT EXISTS samples_ts ON samples (ts);
CREATE TABLE IF NOT EXISTS snapshots (
  id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, session TEXT NOT NULL, sequence INTEGER NOT NULL,
  reason TEXT NOT NULL, body BLOB NOT NULL);
CREATE INDEX IF NOT EXISTS snapshots_ts ON snapshots (ts);
"""

_SAMPLE_COLS = ("ts", "session", "machines", "running", "idle", "problem", "maintenance", "unformed", "unknown",
                "ae_stored", "ae_max", "ae_usage", "ae_injection", "ae_powered", "eu_consuming", "eu_generating",
                "eu_stored", "eu_capacity", "cpus", "busy_cpus")


def _like_prefix(prefix: str) -> str:
    return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


class WorldJournal:
    def __init__(self, path: Path | str | None = None, retention: Retention | None = None,
                 readonly: bool = False, allow_in_repo: bool = False) -> None:
        self.path = check_location(Path(path) if path else default_path(), allow_in_repo)
        self.retention = retention or Retention()
        self.readonly = readonly
        self._lock = threading.Lock()
        self._last_snapshot_ts = 0
        self._last_snapshot_session = ""
        self._last_prune = 0.0
        if readonly:
            if not self.path.is_file():
                raise JournalError("no world journal at that path yet")
            uri = "file:" + self.path.as_posix() + "?mode=ro"
            self._db = sqlite3.connect(uri, uri=True, check_same_thread=False)
            try:
                self._db.execute("PRAGMA query_only=1")
                version = self._db.execute("PRAGMA user_version").fetchone()[0]
            except sqlite3.DatabaseError:
                self._db.close()
                raise JournalError("that file is not a world journal (unreadable SQLite file)") from None
            if version != SCHEMA_VERSION:
                self._db.close()
                raise JournalError(f"world journal schema {version} is not the one this adapter reads ({SCHEMA_VERSION})")
            return
        self._prepare_file()
        self._check_existing()
        self._db = sqlite3.connect(str(self.path), check_same_thread=False)
        try:
            self._init_schema()
        except sqlite3.DatabaseError:
            self._db.close()
            raise JournalError("that file is not a world journal (unreadable SQLite file)") from None
        # only now is it known to be our journal: tighten a file somebody created with loose bits
        if os.name == "posix" and self.path.stat().st_mode & 0o077:
            os.chmod(self.path, 0o600)
        self.prune()

    _OWN_NAMES = ("meta", "events", "samples", "snapshots", "events_ts", "events_subject", "events_kind",
                  "samples_ts", "snapshots_ts")

    def _check_existing(self) -> None:
        """Look at an existing non-empty file READ-ONLY first: never open somebody else's database
        (a kanban.db passed by mistake, say) for writing, not even to add a table."""
        if not self.path.is_file() or self.path.stat().st_size == 0:
            return
        try:
            db = sqlite3.connect("file:" + self.path.as_posix() + "?mode=ro", uri=True)
        except sqlite3.Error:
            raise JournalError("that file is not a world journal (unreadable SQLite file)") from None
        try:
            version = db.execute("PRAGMA user_version").fetchone()[0]
            names = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")]
        except sqlite3.DatabaseError:
            raise JournalError("that file is not a world journal (unreadable SQLite file)") from None
        finally:
            db.close()
        if any(n not in self._OWN_NAMES for n in names):
            raise JournalError("that file is another SQLite database, not a world journal; refusing to write to it")
        if version not in (0, SCHEMA_VERSION):
            raise JournalError(f"world journal schema {version} is newer than this adapter ({SCHEMA_VERSION})")

    def _init_schema(self) -> None:
        with self._lock:
            version = self._db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0, SCHEMA_VERSION):
                self._db.close()
                raise JournalError(f"world journal schema {version} is newer than this adapter ({SCHEMA_VERSION})")
            if version == 0:
                self._db.execute("PRAGMA auto_vacuum=INCREMENTAL")
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.executescript(_SCHEMA)
            self._db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
            self._db.commit()
            row = self._db.execute("SELECT ts, session FROM snapshots ORDER BY ts DESC, id DESC LIMIT 1").fetchone()
            if row:
                self._last_snapshot_ts, self._last_snapshot_session = row[0], row[1]

    def _prepare_file(self) -> None:
        parent = self.path.parent
        parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if os.name == "posix" and not self.path.exists():
            # create it private before SQLite does (SQLite would use the umask)
            fd = os.open(self.path, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
            os.close(fd)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ---- writing ----------------------------------------------------------------------------
    def record(self, model: Mapping[str, Any] | None, events: Iterable[Mapping[str, Any]], ts_ms: int,
               session: str = "", force_snapshot: bool = False) -> None:
        """Append one capture's events and sample; snapshot when due. ``model`` may be None for
        events that have no capture (source lost / back)."""
        if self.readonly:
            raise JournalError("journal opened read-only")
        session = str((model or {}).get("session") or session or "unknown")[:64]
        rows = [(int(e.get("ts") or ts_ms), session, str(e.get("kind", ""))[:64], str(e.get("severity", "info"))[:16],
                 str(e.get("subject", ""))[:160], str(e.get("text", ""))[:400],
                 json.dumps(e["data"], separators=(",", ":"), sort_keys=True)[:4000] if e.get("data") else None)
                for e in events]
        with self._lock:
            if rows:
                self._db.executemany("INSERT INTO events (ts, session, kind, severity, subject, text, data) "
                                     "VALUES (?, ?, ?, ?, ?, ?, ?)", rows)
            if model is not None:
                self._db.execute(f"INSERT INTO samples ({', '.join(_SAMPLE_COLS)}) VALUES "
                                 f"({', '.join('?' * len(_SAMPLE_COLS))})", self._sample_row(model, ts_ms, session))
                due = ts_ms - self._last_snapshot_ts >= self.retention.snapshot_every_s * 1000
                if force_snapshot or due or session != self._last_snapshot_session:
                    body = zlib.compress(json.dumps(model, separators=(",", ":"), sort_keys=True).encode("utf-8"), 6)
                    if len(body) <= MAX_SNAPSHOT_BYTES:
                        reason = "session" if session != self._last_snapshot_session else ("forced" if force_snapshot else "periodic")
                        self._db.execute("INSERT INTO snapshots (ts, session, sequence, reason, body) VALUES (?, ?, ?, ?, ?)",
                                         (ts_ms, session, int(model.get("sequence") or 0), reason, body))
                        self._last_snapshot_ts, self._last_snapshot_session = ts_ms, session
            self._db.commit()
        if time.monotonic() - self._last_prune >= self.retention.prune_every_s:
            self.prune()

    @staticmethod
    def _sample_row(model: Mapping[str, Any], ts_ms: int, session: str) -> tuple[Any, ...]:
        s = (model.get("base") or {}).get("summary") or {}
        ae = (model.get("power") or {}).get("ae") or {}
        eu = (model.get("power") or {}).get("eu") or {}
        a2 = model.get("ae2") or {}
        powered = ae.get("powered")
        return (ts_ms, session, s.get("machines"), s.get("running"), s.get("idle"), s.get("problem"),
                s.get("maintenance"), s.get("unformed"), s.get("unknown"), ae.get("stored"), ae.get("max"),
                ae.get("avgUsage"), ae.get("avgInjection"), None if powered is None else int(bool(powered)),
                eu.get("consumingPerTick"), eu.get("generatingPerTick"), eu.get("stored"), eu.get("capacity"),
                a2.get("cpus"), a2.get("busyCpus"))

    def prune(self, now_ms: int | None = None) -> dict[str, int]:
        """Apply the retention limits now. Returns rows deleted per table."""
        if self.readonly:
            return {}
        r = self.retention
        now_ms = int(time.time() * 1000) if now_ms is None else now_ms
        out = {"events": 0, "samples": 0, "snapshots": 0}
        with self._lock:
            cur = self._db.cursor()
            out["events"] += cur.execute("DELETE FROM events WHERE ts < ?", (now_ms - int(r.max_age_days * _DAY_MS),)).rowcount
            out["samples"] += cur.execute("DELETE FROM samples WHERE ts < ?", (now_ms - int(r.max_age_days * _DAY_MS),)).rowcount
            out["snapshots"] += cur.execute("DELETE FROM snapshots WHERE ts < ?",
                                            (now_ms - int(r.snapshot_max_age_days * _DAY_MS),)).rowcount
            for table, cap, order in (("events", r.max_events, "id"), ("samples", r.max_samples, "rowid"),
                                      ("snapshots", r.max_snapshots, "id")):
                out[table] += cur.execute(f"DELETE FROM {table} WHERE {order} IN (SELECT {order} FROM {table} "
                                          f"ORDER BY ts DESC, {order} DESC LIMIT -1 OFFSET ?)", (max(0, cap),)).rowcount
            # size cap: drop the oldest half of the snapshots, then the oldest events/samples, until it fits
            for _ in range(8):
                if self._size_locked() <= r.max_bytes:
                    break
                n = cur.execute("SELECT COUNT(*) FROM snapshots").fetchone()[0]
                if n > 1:
                    out["snapshots"] += cur.execute("DELETE FROM snapshots WHERE id IN (SELECT id FROM snapshots "
                                                    "ORDER BY ts, id LIMIT ?)", ((n + 1) // 2,)).rowcount
                else:
                    for table, order in (("events", "id"), ("samples", "rowid")):
                        m = cur.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                        out[table] += cur.execute(f"DELETE FROM {table} WHERE {order} IN (SELECT {order} FROM {table} "
                                                  f"ORDER BY ts, {order} LIMIT ?)", ((m + 1) // 2,)).rowcount
                self._db.commit()
                self._db.execute("PRAGMA incremental_vacuum").fetchall()
            self._db.commit()
            if any(out.values()):
                self._db.execute("PRAGMA incremental_vacuum").fetchall()
        self._last_prune = time.monotonic()
        return out

    def _size_locked(self) -> int:
        pages = self._db.execute("PRAGMA page_count").fetchone()[0]
        free = self._db.execute("PRAGMA freelist_count").fetchone()[0]
        size = self._db.execute("PRAGMA page_size").fetchone()[0]
        return (pages - free) * size

    # ---- reading ----------------------------------------------------------------------------
    def query_events(self, since_ms: int | None = None, until_ms: int | None = None, kinds: Iterable[str] = (),
                     subject: str | None = None, min_severity: str | None = None, limit: int = 200,
                     newest_first: bool = False) -> list[dict[str, Any]]:
        """Events in a time window. A kind ending in ``.`` is a prefix (``machine.``)."""
        sql = ["SELECT id, ts, session, kind, severity, subject, text, data FROM events WHERE 1=1"]
        args: list[Any] = []
        if since_ms is not None:
            sql.append("AND ts >= ?")
            args.append(int(since_ms))
        if until_ms is not None:
            sql.append("AND ts <= ?")
            args.append(int(until_ms))
        kinds = [k for k in kinds if k]
        if kinds:
            sql.append("AND (" + " OR ".join("kind LIKE ? ESCAPE '\\'" if k.endswith(".") else "kind = ?" for k in kinds) + ")")
            args.extend(_like_prefix(k) if k.endswith(".") else k for k in kinds)
        if subject:
            sql.append("AND subject = ?")
            args.append(subject)
        if min_severity:
            allowed = [s for s, r in SEVERITY_RANK.items() if r >= SEVERITY_RANK.get(min_severity, 0)]
            sql.append(f"AND severity IN ({', '.join('?' * len(allowed))})")
            args.extend(allowed)
        sql.append("ORDER BY ts DESC, id DESC" if newest_first else "ORDER BY ts, id")
        sql.append("LIMIT ?")
        args.append(max(1, min(MAX_QUERY_LIMIT, int(limit))))
        with self._lock:
            rows = self._db.execute(" ".join(sql), args).fetchall()
        out = []
        for rid, ts, session, kind, sev, subj, txt, data in rows:
            e: dict[str, Any] = {"id": rid, "ts": ts, "session": session, "kind": kind, "severity": sev,
                                 "subject": subj, "text": txt}
            if data:
                try:
                    e["data"] = json.loads(data)
                except ValueError:
                    pass
            out.append(e)
        return out

    def latest_snapshot(self, at_ms: int | None = None) -> dict[str, Any] | None:
        """The newest snapshot at or before ``at_ms`` (default: now)."""
        sql = "SELECT ts, session, sequence, reason, body FROM snapshots"
        args: tuple[Any, ...] = ()
        if at_ms is not None:
            sql += " WHERE ts <= ?"
            args = (int(at_ms),)
        with self._lock:
            row = self._db.execute(sql + " ORDER BY ts DESC, id DESC LIMIT 1", args).fetchone()
        if row is None:
            return None
        ts, session, seq, reason, body = row
        return {"ts": ts, "session": session, "sequence": seq, "reason": reason,
                "model": json.loads(zlib.decompress(body).decode("utf-8"))}

    def trend(self, since_ms: int, until_ms: int | None = None, max_points: int = 200) -> list[dict[str, Any]]:
        """Samples in a window, averaged into at most ``max_points`` time buckets."""
        until_ms = int(time.time() * 1000) if until_ms is None else int(until_ms)
        points = max(1, min(2000, int(max_points)))
        bucket = max(1, (until_ms - int(since_ms)) // points + 1)
        num = [c for c in _SAMPLE_COLS if c not in ("ts", "session")]
        sql = (f"SELECT MIN(ts), COUNT(*), {', '.join(f'AVG({c})' for c in num)} FROM samples "
               "WHERE ts >= ? AND ts <= ? GROUP BY (ts - ?) / ? ORDER BY MIN(ts)")
        with self._lock:
            rows = self._db.execute(sql, (int(since_ms), until_ms, int(since_ms), bucket)).fetchall()
        out = []
        for row in rows:
            point: dict[str, Any] = {"ts": row[0], "samples": row[1]}
            for col, val in zip(num, row[2:]):
                if val is not None:
                    point[col] = round(val, 2)
            out.append(point)
        return out

    def stats(self) -> dict[str, Any]:
        with self._lock:
            out: dict[str, Any] = {}
            for table in ("events", "samples", "snapshots"):
                n, lo, hi = self._db.execute(f"SELECT COUNT(*), MIN(ts), MAX(ts) FROM {table}").fetchone()
                out[table] = {"rows": n, "oldest": lo, "newest": hi}
            out["sessions"] = self._db.execute("SELECT COUNT(DISTINCT session) FROM samples").fetchone()[0]
            out["bytes"] = self._size_locked()
        out["schema"] = SCHEMA_VERSION
        return out


# ---------------------------------------------------------------------------------------------
# public view: no player names, no coordinates (for pasting outside the local machine)

# GregTech problem texts name positions ("ME channel inactive at 131,73,59"), so free text is
# scrubbed too, not just the structured fields
_POS = re.compile(r"-?\d+\s*,\s*-?\d+\s*,\s*-?\d+")


def scrub_positions(text: str) -> str:
    return _POS.sub("[pos]", text)


def _hide_id(mid: str) -> str:
    import hashlib

    if "@" not in mid:
        return mid
    head = mid.split("@", 1)[0]
    return f"{head}#{hashlib.sha1(mid.encode()).hexdigest()[:6]}"


def public_event(e: Mapping[str, Any]) -> dict[str, Any]:
    out = {k: v for k, v in e.items() if k not in ("data", "session")}
    out["subject"] = _hide_id(str(e.get("subject", "")))
    out["text"] = scrub_positions(str(e.get("text", "")))
    data = e.get("data") or {}
    keep = {k: (scrub_positions(v) if isinstance(v, str) else v) for k, v in data.items() if k not in ("x", "y", "z", "dim")}
    if keep:
        out["data"] = keep
    return out


def public_model(model: Mapping[str, Any]) -> dict[str, Any]:
    out = {k: v for k, v in model.items() if k != "private"}
    base = dict(out.get("base") or {})
    if base:
        scope = dict(base.get("scope") or {})
        scope.pop("center", None)
        base["scope"] = scope
        base["headline"] = scrub_positions(str(base.get("headline", "")))
        base["id"] = scrub_positions(str(base.get("id", "")))
        out["base"] = base
    ms = []
    for m in out.get("machines") or []:
        pm = {k: v for k, v in m.items() if k not in ("x", "y", "z", "dim", "parts")}
        pm["id"] = _hide_id(m["id"])
        if "problem" in pm:
            pm["problem"] = scrub_positions(pm["problem"])
        ms.append(pm)
    out["machines"] = ms
    return out


# ---------------------------------------------------------------------------------------------
# CLI: python3 -m hermes_adapter.journal [--db PATH] {query,snapshot,trend,stats,prune}

_UNITS = {"s": 1_000, "m": 60_000, "h": 3_600_000, "d": _DAY_MS, "w": 7 * _DAY_MS}


def parse_time(value: str, now_ms: int | None = None) -> int:
    """``30m`` / ``2h`` / ``7d`` ago, ``now``, ISO-8601 (``2026-10-06T07:00:00Z``), epoch s or ms."""
    from datetime import datetime, timezone

    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    v = value.strip().lower()
    if v == "now":
        return now_ms
    if len(v) > 1 and v[-1] in _UNITS and v[:-1].replace(".", "", 1).isdigit():
        return now_ms - int(float(v[:-1]) * _UNITS[v[-1]])
    if v.isdigit():
        n = int(v)
        return n if n >= 10 ** 11 else n * 1000
    try:
        dt = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        raise ValueError(f"cannot read time {value[:40]!r} (try 2h, 7d, now or 2026-10-06T07:00:00Z)") from None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def _fmt_ts(ms: int | None) -> str:
    from datetime import datetime, timezone

    if not ms:
        return "-"
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def main(argv: list[str] | None = None) -> int:
    import argparse
    import sys

    p = argparse.ArgumentParser(prog="python3 -m hermes_adapter.journal",
                                description="Read the world journal (factory history) written by the adapter.")
    p.add_argument("--db", type=Path, default=None, help=f"journal file (default ${ENV_PATH} or ~/.local/state/agentcraft-gtnh/)")
    sub = p.add_subparsers(dest="cmd", required=True)
    q = sub.add_parser("query", help="change events in a time window")
    q.add_argument("--since", default="24h")
    q.add_argument("--until", default=None)
    q.add_argument("--kind", action="append", default=[], help="event kind; a trailing '.' matches a prefix (machine.)")
    q.add_argument("--subject", default=None, help="machine id, or base / power / ae2 / source")
    q.add_argument("--severity", choices=tuple(SEVERITY_RANK), default=None, help="minimum severity")
    q.add_argument("--limit", type=int, default=200)
    q.add_argument("--newest-first", action="store_true")
    s = sub.add_parser("snapshot", help="the newest full snapshot (at or before --at)")
    s.add_argument("--at", default=None)
    t = sub.add_parser("trend", help="power and activity numbers over time")
    t.add_argument("--since", default="24h")
    t.add_argument("--until", default=None)
    t.add_argument("--points", type=int, default=48)
    sub.add_parser("stats", help="row counts, time range and size")
    sub.add_parser("prune", help="apply the retention limits now (opens the journal for writing)")
    for sp in (q, s, t):
        sp.add_argument("--json", action="store_true", help="machine-readable output for agents")
        sp.add_argument("--public", action="store_true", help="drop player names and coordinates")
    args = p.parse_args(argv)

    try:
        if args.cmd == "prune":
            j = WorldJournal(args.db)
            print(json.dumps(j.prune()))
            j.close()
            return 0
        j = WorldJournal(args.db, readonly=True)
    except (JournalError, sqlite3.Error) as e:
        print(f"journal: {e}", file=sys.stderr)
        return 2
    try:
        if args.cmd == "stats":
            print(json.dumps(j.stats(), indent=2))
        elif args.cmd == "query":
            events = j.query_events(parse_time(args.since), parse_time(args.until) if args.until else None,
                                    args.kind, args.subject, args.severity, args.limit, args.newest_first)
            if args.public:
                events = [public_event(e) for e in events]
            if args.json:
                print(json.dumps(events, indent=1, ensure_ascii=False))
            else:
                for e in events:
                    print(f"{_fmt_ts(e['ts'])}  {e['severity']:<8} {e['kind']:<20} {e['text']}")
                print(f"({len(events)} event{'s' if len(events) != 1 else ''})")
        elif args.cmd == "snapshot":
            snap = j.latest_snapshot(parse_time(args.at) if args.at else None)
            if snap is None:
                print("no snapshot in the journal yet", file=sys.stderr)
                return 1
            if args.public:
                snap = {**snap, "model": public_model(snap["model"])}
                snap.pop("session", None)
            if args.json:
                print(json.dumps(snap, indent=1, ensure_ascii=False))
            else:
                base = snap["model"].get("base") or {}
                print(f"{_fmt_ts(snap['ts'])}  {base.get('headline', '')}")
                for m in snap["model"].get("machines", []):
                    where = "" if args.public else f"  @ {m.get('x')},{m.get('y')},{m.get('z')}"
                    print(f"  {m['state']:<12} {m['name']}{where}")
        elif args.cmd == "trend":
            pts = j.trend(parse_time(args.since), parse_time(args.until) if args.until else None, args.points)
            if args.json:
                print(json.dumps(pts, indent=1))
            else:
                print("time                  samples running idle problem  AE stored        EU/t used")
                for pt in pts:
                    print(f"{_fmt_ts(pt['ts'])}  {pt['samples']:>7} {pt.get('running', 0):>7.0f} {pt.get('idle', 0):>4.0f} "
                          f"{pt.get('problem', 0) + pt.get('maintenance', 0):>7.0f}  {pt.get('ae_stored', 0):>14,.0f}  "
                          f"{pt.get('eu_consuming', 0):>10,.0f}")
    except ValueError as e:
        print(f"journal: {e}", file=sys.stderr)
        return 2
    finally:
        j.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
