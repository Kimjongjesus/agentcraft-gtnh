"""``ops.*``: AI-ops feeds (fleet health, scheduled jobs, provider usage, alerts) for AgentCraft.

The adapter does not know any real monitoring system. It runs *ops sources*: small plugins that
return plain dicts (see :class:`OpsSource` and ``../OPS-PLUGINS.md``). Everything a source returns
passes through this module before a client sees it:

* only whitelisted fields of the four entity kinds survive (unknown fields are dropped, never
  forwarded), enums are checked, numbers are validated and clamped, timestamps become epoch ms;
* EVERY string, including ids, goes through :func:`redact.clean` (personal-note withholding,
  credential and IP redaction) before it is cut to its display limit;
* ids are namespaced per source (``<source>/<id>``), so two plugins can never overwrite each
  other's entities;
* every list is bounded (per kind, after merging all sources; the important entries are kept);
* a source that fails keeps its last good data, flagged on its ``ops.source`` status; once it is
  stale its services turn ``unknown`` instead of showing a state nobody has checked.

Collection runs in a small dedicated thread pool with a timeout per source, so a hung collector
never blocks the Hermes poll or the WebSocket server. Wire format: ``../../docs/ops-protocol.md``.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import hashlib
import importlib
import importlib.util
import logging
import math
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping

from .redact import clean, clean_id

log = logging.getLogger("hermes_adapter.ops")

KINDS = ("service", "job", "usage", "alert")
PLURAL = {"service": "services", "job": "jobs", "usage": "usage", "alert": "alerts", "source": "sources"}
SERVICE_STATES = ("up", "degraded", "down", "unknown")
JOB_STATUSES = ("ok", "failed", "running", "unknown")
SEVERITIES = ("info", "warn", "critical")
ALERT_STATES = ("open", "resolved")
SOURCE_STATES = ("starting", "ok", "warn", "error", "stale")

# protocol bounds (docs/ops-protocol.md "Bounds"); clients may rely on them
MAX = {"service": 256, "job": 128, "usage": 32, "alert": 100, "source": 16}
TEXT = {
    "name": 40,
    "group": 24,
    "detail": 80,
    "schedule": 48,
    "provider": 32,
    "window": 32,
    "title": 100,
    "alert_detail": 200,
    "alert_source": 24,
    "source_name": 40,
    "source_detail": 120,
}
SOURCE_ID_MAX = 24
LOCAL_ID_MAX = 64
SCAN_FACTOR = 4  # a source's list is read up to SCAN_FACTOR x the kind's cap; the rest is rejected unread
FEATURE = "ops"
ENTRY_POINT_GROUP = "agentcraft_gtnh.ops_sources"

# lenient input aliases: plugins may use common synonyms; anything else maps to the safe default
_SERVICE_ALIASES = {
    "ok": "up", "healthy": "up", "online": "up", "running": "up", "pass": "up",
    "warn": "degraded", "warning": "degraded", "pending": "degraded", "partial": "degraded",
    "offline": "down", "failed": "down", "fail": "down", "error": "down", "critical": "down", "dead": "down",
}
_JOB_ALIASES = {
    "success": "ok", "succeeded": "ok", "completed": "ok", "complete": "ok", "pass": "ok",
    "error": "failed", "fail": "failed", "failure": "failed", "crashed": "failed", "timeout": "failed",
    "claimed": "running", "started": "running", "in_progress": "running",
}
_SEVERITY_ALIASES = {
    "low": "info", "min": "info", "notice": "info", "ok": "info",
    "warning": "warn", "default": "warn", "medium": "warn", "high": "warn", "error": "warn",
    "urgent": "critical", "crit": "critical", "fatal": "critical", "emergency": "critical", "page": "critical",
}
_ALERT_STATE_ALIASES = {"closed": "resolved", "ok": "resolved", "recovered": "resolved", "firing": "open", "active": "open"}

_MIN_MS = 946684800000  # 2000-01-01
_MAX_MS = 32503680000000  # 3000-01-01
_MAX_DURATION_MS = 30 * 86400 * 1000


# ---------------------------------------------------------------------------------------------
# value helpers (pure)

def to_ms(value: Any) -> int | None:
    """Epoch ms from epoch seconds / ms (int or float) or an ISO-8601 string; None if invalid.
    Numbers below 1e11 are taken as seconds. Naive ISO strings are local time."""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        v = float(value)
        if not math.isfinite(v):
            return None
        out = int(v if abs(v) >= 1e11 else v * 1000)
    elif isinstance(value, str):
        s = value.strip()
        if not s:
            return None
        if s.isdigit():
            return to_ms(int(s))
        try:
            out = int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000)
        except (ValueError, OverflowError, OSError):
            return None
    else:
        return None
    return out if _MIN_MS <= out <= _MAX_MS else None


def pct(value: Any) -> float | None:
    """A percentage in [0, 100] rounded to 0.1, or None for anything that is not a finite number."""
    if value is None or isinstance(value, bool):
        return None
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(v):
        return None
    return round(min(100.0, max(0.0, v)), 1)


def duration_ms(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(v) or v < 0:
        return None
    return int(min(v, _MAX_DURATION_MS))


def _enum(value: Any, allowed: tuple[str, ...], aliases: Mapping[str, str], default: str) -> str:
    v = str(value or "").strip().lower()
    if v in allowed:
        return v
    return aliases.get(v, default)


def _text(value: Any, limit: int) -> str:
    """Whole-source privacy filter, then the display cut (redact.clean does both in that order)."""
    return clean(value, limit) if value is not None else ""


class _Ids:
    """Per-source id allocator: privacy-filtered, identifier-safe, unique within a kind."""

    def __init__(self, source_id: str) -> None:
        self.source_id = source_id
        self.used: dict[str, set[str]] = {}

    def make(self, kind: str, raw: Any) -> str | None:
        raw_s = "" if raw is None else str(raw)
        if not raw_s.strip():
            return None
        local = clean_id(clean(raw_s, 0), LOCAL_ID_MAX)
        used = self.used.setdefault(kind, set())
        if local in used:  # collision after filtering: number it deterministically (never hash raw text)
            n = 2
            while f"{local[:LOCAL_ID_MAX - 4]}-{n}" in used:
                n += 1
            local = f"{local[:LOCAL_ID_MAX - 4]}-{n}"
        used.add(local)
        return f"{self.source_id}/{local}"


# ---------------------------------------------------------------------------------------------
# entity normalization (pure). Each returns the protocol entity or None (rejected).

def normalize_service(raw: Mapping[str, Any], source_id: str, ids: _Ids) -> dict[str, Any] | None:
    name = _text(raw.get("name") or raw.get("id"), TEXT["name"])
    eid = ids.make("service", raw.get("id") or raw.get("name")) if name else None
    if not name or not eid:
        return None
    out: dict[str, Any] = {
        "id": eid,
        "sourceId": source_id,
        "name": name,
        "group": _text(raw.get("group"), TEXT["group"]) or "other",
        "state": _enum(raw.get("state"), SERVICE_STATES, _SERVICE_ALIASES, "unknown"),
    }
    since = to_ms(raw.get("since"))
    if since is not None:
        out["since"] = since
    detail = _text(raw.get("detail"), TEXT["detail"])
    if detail:
        out["detail"] = detail
    for k in ("cpu", "mem", "disk"):
        v = pct(raw.get(k))
        if v is not None:
            out[k] = v
    return out


def normalize_job(raw: Mapping[str, Any], source_id: str, ids: _Ids) -> dict[str, Any] | None:
    name = _text(raw.get("name") or raw.get("id"), TEXT["name"])
    eid = ids.make("job", raw.get("id") or raw.get("name")) if name else None
    if not name or not eid:
        return None
    out: dict[str, Any] = {
        "id": eid,
        "sourceId": source_id,
        "name": name,
        "lastStatus": _enum(raw.get("lastStatus"), JOB_STATUSES, _JOB_ALIASES, "unknown"),
        "enabled": raw.get("enabled") is not False,
    }
    sched = _text(raw.get("schedule"), TEXT["schedule"])
    if sched:
        out["schedule"] = sched
    for k in ("lastRun", "nextRun"):
        v = to_ms(raw.get(k))
        if v is not None:
            out[k] = v
    d = duration_ms(raw.get("durationMs"))
    if d is not None:
        out["durationMs"] = d
    detail = _text(raw.get("detail"), TEXT["detail"])
    if detail:
        out["detail"] = detail
    return out


def normalize_usage(raw: Mapping[str, Any], source_id: str, ids: _Ids) -> dict[str, Any] | None:
    provider = _text(raw.get("provider"), TEXT["provider"])
    window = _text(raw.get("window"), TEXT["window"])
    if not provider or not window:
        return None
    eid = ids.make("usage", raw.get("id") or f"{raw.get('provider')}-{raw.get('window')}")
    if not eid:
        return None
    out: dict[str, Any] = {"id": eid, "sourceId": source_id, "provider": provider, "window": window}
    rem = pct(raw.get("remainingPct"))
    if rem is not None:
        out["remainingPct"] = rem
    resets = to_ms(raw.get("resetsAt"))
    if resets is not None:
        out["resetsAt"] = resets
    detail = _text(raw.get("detail"), TEXT["detail"])
    if detail:
        out["detail"] = detail
    return out


def normalize_alert(raw: Mapping[str, Any], source_id: str, ids: _Ids) -> dict[str, Any] | None:
    title = _text(raw.get("title"), TEXT["title"])
    ts = to_ms(raw.get("ts"))
    eid = ids.make("alert", raw.get("id")) if title and ts is not None else None
    if not title or ts is None or not eid:
        return None
    out: dict[str, Any] = {
        "id": eid,
        "sourceId": source_id,
        "ts": ts,
        "severity": _enum(raw.get("severity"), SEVERITIES, _SEVERITY_ALIASES, "warn"),
        "source": _text(raw.get("source"), TEXT["alert_source"]) or source_id,
        "title": title,
        "state": _enum(raw.get("state"), ALERT_STATES, _ALERT_STATE_ALIASES, "open"),
    }
    detail = _text(raw.get("detail"), TEXT["alert_detail"])
    if detail:
        out["detail"] = detail
    if out["state"] == "resolved":
        r = to_ms(raw.get("resolvedAt"))
        if r is not None:
            out["resolvedAt"] = r
    return out


NORMALIZE: dict[str, Callable[[Mapping[str, Any], str, _Ids], dict[str, Any] | None]] = {
    "service": normalize_service,
    "job": normalize_job,
    "usage": normalize_usage,
    "alert": normalize_alert,
}


@dataclass
class Batch:
    entities: dict[str, list[dict[str, Any]]]
    rejected: int = 0
    warning: str = ""


def normalize_batch(raw: Any, source_id: str) -> Batch:
    """A source's collect() result -> protocol entities. Raises ValueError if it is not a mapping
    of lists. A kind key that is absent means "this source has none of those"."""
    if not isinstance(raw, Mapping):
        raise ValueError(f"collect() returned {type(raw).__name__}, expected a dict")
    ids = _Ids(source_id)
    out: dict[str, list[dict[str, Any]]] = {}
    rejected = 0
    for kind in KINDS:
        items = raw.get(PLURAL[kind])
        if items is None:
            out[kind] = []
            continue
        if not isinstance(items, (list, tuple)):
            raise ValueError(f"'{PLURAL[kind]}' must be a list")
        scan = MAX[kind] * SCAN_FACTOR  # never spend unbounded CPU on a runaway source
        if len(items) > scan:
            rejected += len(items) - scan
            items = items[:scan]
        keep: list[dict[str, Any]] = []
        for item in items:
            ent = NORMALIZE[kind](item, source_id, ids) if isinstance(item, Mapping) else None
            if ent is None:
                rejected += 1
            elif len(keep) < MAX[kind] * 2:  # generous per-source cap; the merged cap is applied later
                keep.append(ent)
            else:
                rejected += 1
        out[kind] = keep
    warning = _text(raw.get("warning"), TEXT["source_detail"]) if raw.get("warning") else ""
    return Batch(out, rejected, warning)


# ---------------------------------------------------------------------------------------------
# bounded merge: which entities survive a cap (most important first) and presentation order

_SERVICE_RANK = {"down": 0, "degraded": 1, "unknown": 2, "up": 3}
_JOB_RANK = {"failed": 0, "running": 1, "unknown": 2, "ok": 3}
_SEV_RANK = {"critical": 0, "warn": 1, "info": 2}


def _cap(kind: str, items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if kind == "service":
        keep = sorted(items, key=lambda e: (_SERVICE_RANK[e["state"]], e["group"], e["name"], e["id"]))[: MAX[kind]]
        return sorted(keep, key=lambda e: (e["group"], e["name"].lower(), e["id"]))
    if kind == "job":
        keep = sorted(items, key=lambda e: (_JOB_RANK[e["lastStatus"]], not e["enabled"], e["name"], e["id"]))[: MAX[kind]]
        return sorted(keep, key=lambda e: (e["name"].lower(), e["id"]))
    if kind == "usage":
        keep = sorted(items, key=lambda e: (e.get("remainingPct", 101.0), e["id"]))[: MAX[kind]]
        return sorted(keep, key=lambda e: (e["provider"].lower(), e["window"].lower(), e["id"]))
    # alerts: open first, then severity, then newest
    keep = sorted(items, key=lambda e: (e["state"] != "open", _SEV_RANK[e["severity"]], -e["ts"], e["id"]))[: MAX[kind]]
    return sorted(keep, key=lambda e: (-e["ts"], e["id"]))


# ---------------------------------------------------------------------------------------------
# sources

class OpsSource:
    """Interface of an ops source (subclassing is optional: any object with these attributes works).

    * ``id`` (str): short stable id, e.g. ``"fleet"``; becomes the id prefix of its entities.
    * ``name`` (str, optional): display name for the source status line.
    * ``interval`` (seconds, optional, default 60): time between collects.
    * ``timeout`` (seconds, optional, default min(interval, 30)): longest a collect may take.
    * ``collect()`` -> dict with any of ``services``, ``jobs``, ``usage``, ``alerts`` (lists of
      dicts using the protocol field names; ids are local to the source) and an optional
      ``warning`` string. Called in a worker thread; must not write anything anywhere.
    * ``close()`` (optional): called once when the adapter stops.
    """

    id = "source"
    name = ""
    interval = 60.0
    timeout = 30.0

    def collect(self) -> Mapping[str, Any]:  # pragma: no cover - interface
        raise NotImplementedError

    def close(self) -> None:
        return None


@dataclass
class _Slot:
    source: Any
    id: str
    name: str
    interval: float
    timeout: float
    kinds: tuple[str, ...] = ()
    state: str = "starting"
    detail: str = ""
    warning: str = ""
    last_ok: float | None = None
    last_attempt: float | None = None
    created: float = 0.0
    next_due: float = 0.0
    busy: bool = False
    rejected: int = 0
    data: dict[str, list[dict[str, Any]]] = field(default_factory=lambda: {k: [] for k in KINDS})


def _num(value: Any, default: float, lo: float, hi: float) -> float:
    try:
        v = float(value)
    except (TypeError, ValueError):
        return default
    if not math.isfinite(v):
        return default
    return min(hi, max(lo, v))


class OpsHub:
    """Schedules the sources, keeps their last good data and builds the bounded ``ops`` model."""

    def __init__(
        self,
        sources: Iterable[Any],
        clock: Callable[[], float] = time.time,
        stale_factor: float = 3.0,
        min_stale: float = 120.0,
    ) -> None:
        self.clock = clock
        self.stale_factor = stale_factor
        self.min_stale = min_stale
        self.slots: list[_Slot] = []
        now = clock()
        seen: set[str] = set()
        for src in sources:
            sid = clean_id(getattr(src, "id", "") or "", SOURCE_ID_MAX)
            if sid == "unknown" or not callable(getattr(src, "collect", None)):
                raise ValueError(f"ops source {src!r} needs a non-empty 'id' and a collect() method")
            if sid in seen:
                raise ValueError(f"two ops sources share the id {sid!r}")
            if len(self.slots) >= MAX["source"]:
                raise ValueError(f"at most {MAX['source']} ops sources")
            seen.add(sid)
            interval = _num(getattr(src, "interval", 60.0), 60.0, 1.0, 86400.0)
            timeout = _num(getattr(src, "timeout", None), min(interval, 30.0), 0.5, 300.0)
            self.slots.append(_Slot(
                source=src, id=sid, name=_text(getattr(src, "name", "") or sid, TEXT["source_name"]) or sid,
                interval=interval, timeout=timeout, created=now))
        self._pool = concurrent.futures.ThreadPoolExecutor(
            max_workers=max(1, len(self.slots)), thread_name_prefix="ops-collect")
        self._tasks: set[asyncio.Task[None]] = set()

    # ---- collection -----------------------------------------------------------------------
    async def tick(self, wait: bool = True) -> None:
        """Collect every source that is due (concurrently), then refresh staleness.

        ``wait=False`` (the server) starts the due collects as background tasks and returns at
        once, so one slow source never delays the others: their results land in the model as each
        collect finishes and the caller's next tick diffs them out."""
        now = self.clock()
        due = [s for s in self.slots if not s.busy and now >= s.next_due]
        if due and wait:
            await asyncio.gather(*(self._run(s, now) for s in due))
        elif due:
            for s in due:
                s.busy = True  # claimed now, so a tick before the task starts cannot start it again
                task = asyncio.create_task(self._run(s, now))
                self._tasks.add(task)  # strong reference until done (asyncio only keeps weak ones)
                task.add_done_callback(self._tasks.discard)
        self._mark_stale(self.clock())

    async def collect_all(self) -> None:
        """Collect every source once regardless of schedule (``--once-ops``, tests)."""
        now = self.clock()
        await asyncio.gather(*(self._run(s, now) for s in self.slots if not s.busy))
        self._mark_stale(self.clock())

    async def _run(self, slot: _Slot, now: float) -> None:
        slot.busy = True
        slot.last_attempt = now
        slot.next_due = now + slot.interval
        loop = asyncio.get_running_loop()
        # collect AND normalize in the worker thread: a big batch never blocks the event loop
        fut = loop.run_in_executor(self._pool, _collect_normalized, slot.source, slot.id)
        try:
            batch, kinds = await asyncio.wait_for(asyncio.shield(fut), slot.timeout)
        except asyncio.TimeoutError:
            # the worker thread cannot be killed; the slot stays busy (never run twice) until it ends
            self._fail(slot, f"collect did not finish within {slot.timeout:g}s")
            fut.add_done_callback(lambda f: _release_late(slot, f))
            return
        except _BadResult as e:
            self._fail(slot, str(e))
            slot.busy = False
            return
        except Exception as e:  # noqa: BLE001 - a plugin may raise anything
            self._fail(slot, f"{type(e).__name__}: {e}")
            slot.busy = False
            return
        slot.busy = False
        slot.data = batch.entities
        slot.rejected = batch.rejected
        slot.warning = batch.warning
        slot.kinds = kinds
        slot.last_ok = self.clock()
        slot.state = "warn" if batch.warning else "ok"
        slot.detail = batch.warning
        if batch.rejected:
            log.info("ops source %s: %d malformed entr%s dropped", slot.id, batch.rejected, "y" if batch.rejected == 1 else "ies")

    def _fail(self, slot: _Slot, why: str) -> None:
        msg = _text(why, TEXT["source_detail"]) or "collect failed"
        if msg != slot.detail:
            log.warning("ops source %s failed: %s", slot.id, msg)  # filtered text only, never raw errors
        slot.state = "error"
        slot.detail = msg

    def stale_after(self, slot: _Slot) -> float:
        return max(self.min_stale, self.stale_factor * slot.interval)

    def _mark_stale(self, now: float) -> None:
        for s in self.slots:
            ref = s.last_ok if s.last_ok is not None else s.created
            if now - ref > self.stale_after(s) and s.state in ("error", "ok", "warn", "starting"):
                if s.last_ok is not None:
                    s.state = "stale"
                elif s.state == "starting":
                    s.state, s.detail = "error", "no data yet"

    def close(self) -> None:
        for task in list(self._tasks):
            task.cancel()
        for s in self.slots:
            closer = getattr(s.source, "close", None)
            if callable(closer):
                try:
                    closer()
                except Exception as e:  # noqa: BLE001
                    log.warning("ops source %s close failed: %s", s.id, _text(f"{type(e).__name__}: {e}", 120))
        self._pool.shutdown(wait=False, cancel_futures=True)

    # ---- model ----------------------------------------------------------------------------
    def model(self) -> dict[str, list[dict[str, Any]]]:
        merged: dict[str, list[dict[str, Any]]] = {k: [] for k in KINDS}
        for s in self.slots:
            for kind in KINDS:
                items = s.data.get(kind, [])
                if kind == "service" and s.state == "stale":
                    note = _text(f"no fresh data from {s.name}", TEXT["detail"])
                    items = [_stale_service(e, note, s.last_ok) for e in items]
                merged[kind].extend(items)
        out = {PLURAL[k]: _cap(k, merged[k]) for k in KINDS}
        out["sources"] = [self._status(s) for s in self.slots]
        return out

    def _status(self, s: _Slot) -> dict[str, Any]:
        st: dict[str, Any] = {
            "id": s.id,
            "name": s.name,
            "state": s.state,
            "interval": int(s.interval),
            "kinds": [k for k in KINDS if k in s.kinds],
            "counts": {PLURAL[k]: len(s.data.get(k, [])) for k in KINDS},
        }
        if s.last_ok is not None:
            st["lastOk"] = int(s.last_ok // 60) * 60_000  # minute resolution: <= 1 upsert/min/source
        if s.detail:
            st["detail"] = s.detail
        if s.rejected:
            st["rejected"] = s.rejected
        return st

    def snapshot(self, model: dict[str, Any] | None = None) -> dict[str, Any]:
        m = model if model is not None else self.model()
        return {
            "type": "ops.snapshot",
            **{PLURAL[k]: m[PLURAL[k]] for k in KINDS},
            "sources": m["sources"],
            "limits": {PLURAL[k]: n for k, n in MAX.items()},
            "ts": int(self.clock() * 1000),
        }

    @staticmethod
    def changes(old: dict[str, Any] | None, new: dict[str, Any]) -> list[dict[str, Any]]:
        """Incremental ``ops.*`` messages turning model ``old`` into ``new`` (none if ``old`` is None)."""
        if old is None:
            return []
        out: list[dict[str, Any]] = []
        for kind in ("source",) + KINDS:
            key = PLURAL[kind]
            before = {e["id"]: e for e in old.get(key, [])}
            after = {e["id"]: e for e in new.get(key, [])}
            for eid in before:
                if eid not in after:
                    out.append({"type": "ops.remove", "kind": kind, "id": eid})
            for eid, e in after.items():
                if before.get(eid) != e:
                    out.append({"type": f"ops.{kind}.upsert", kind: e})
        return out


class _BadResult(ValueError):
    """collect() returned something that is not a valid ops batch (reported without a type name)."""


def _collect_normalized(source: Any, source_id: str) -> tuple[Batch, tuple[str, ...]]:
    """Runs in the worker thread: the source's collect() plus normalization of its result."""
    raw = source.collect()
    try:
        batch = normalize_batch(raw, source_id)
    except ValueError as e:
        raise _BadResult(str(e)) from None
    return batch, tuple(k for k in KINDS if isinstance(raw.get(PLURAL[k]), (list, tuple)))


def _release_late(slot: _Slot, fut: "asyncio.Future[Any]") -> None:
    """A timed-out collect finally ended: free the slot. Its late result is discarded on purpose
    (the next scheduled collect is fresher), and its exception is consumed so asyncio stays quiet."""
    slot.busy = False
    if not fut.cancelled():
        fut.exception()


def _stale_service(e: dict[str, Any], note: str, last_ok: float | None) -> dict[str, Any]:
    out = {k: v for k, v in e.items() if k not in ("since", "detail")}
    out["state"] = "unknown"
    out["detail"] = note
    if last_ok is not None:
        out["since"] = int(last_ok * 1000)
    return out


# ---------------------------------------------------------------------------------------------
# plugin loading
#
# The same loader serves the adapter's other plugin kind, the world sources of card G1
# (``sources/plugin.py``): ``kind`` only changes the wording of errors and the private module
# name, ``group`` the entry-point group. The defaults are the ops ones.

def _load_module_from_path(path: Path, kind: str = "ops") -> Any:
    path = path.expanduser().resolve()
    tag = hashlib.sha1(str(path).encode()).hexdigest()[:10]
    name = f"_agentcraft_{kind}_plugin_{tag}"
    if path.is_dir():
        init = path / "__init__.py"
        if not init.is_file():
            raise ValueError(f"{kind} plugin directory {path.name} has no __init__.py")
        spec = importlib.util.spec_from_file_location(name, init, submodule_search_locations=[str(path)])
    elif path.is_file() and path.suffix == ".py":
        spec = importlib.util.spec_from_file_location(name, path)
    else:
        raise ValueError(f"{kind} plugin {path.name}: not a .py file or a package directory")
    if spec is None or spec.loader is None:
        raise ValueError(f"cannot load {kind} plugin {path.name}")
    if name in sys.modules:
        return sys.modules[name]
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module  # relative imports inside a package plugin need this
    try:
        spec.loader.exec_module(module)
    except BaseException:
        sys.modules.pop(name, None)
        raise
    return module


def _entry_point(name: str, kind: str = "ops", group: str = ENTRY_POINT_GROUP) -> Any:
    from importlib.metadata import entry_points

    for ep in entry_points(group=group):
        if ep.name == name:
            return ep.load()
    raise ValueError(f"no installed {kind} plugin entry point {name!r} in group {group}")


def resolve_factory(spec: str, *, kind: str = "ops", group: str = ENTRY_POINT_GROUP) -> Callable[[dict[str, Any]], Any]:
    """``spec`` -> plugin factory. Accepted forms:

    * a path to a ``.py`` file or a package directory (``__init__.py``) defining ``create_sources``;
    * ``package.module`` or ``package.module:factory`` (importable from ``sys.path``);
    * ``ep:<name>``: an installed entry point in ``group`` (ops: ``agentcraft_gtnh.ops_sources``).
    """
    spec = spec.strip()
    if not spec:
        raise ValueError(f"empty {kind} plugin spec")
    if spec.startswith("ep:"):
        obj = _entry_point(spec[3:], kind, group)
    else:
        p = Path(spec).expanduser()
        if p.exists() or "/" in spec or spec.endswith(".py"):
            obj = _load_module_from_path(p, kind)
        else:
            mod_name, _, attr = spec.partition(":")
            obj = importlib.import_module(mod_name)
            if attr:
                obj = getattr(obj, attr)
    if hasattr(obj, "create_sources"):
        obj = obj.create_sources
    elif hasattr(obj, "SOURCES") and not callable(obj):
        fixed = obj.SOURCES
        obj = lambda _config: fixed  # noqa: E731
    if not callable(obj):
        raise ValueError(f"{kind} plugin {spec!r} has no create_sources(config) factory")
    return obj


def load_plugin(spec: str, config: Mapping[str, Any] | None = None, *, kind: str = "ops",
                group: str = ENTRY_POINT_GROUP) -> list[Any]:
    """Load one plugin and return its sources (validated for the minimal interface)."""
    factory = resolve_factory(spec, kind=kind, group=group)
    result = factory(dict(config or {}))
    if result is None:
        return []
    if isinstance(result, Mapping) or not isinstance(result, Iterable):
        result = [result]
    sources = list(result)
    for src in sources:
        if not callable(getattr(src, "collect", None)) or not str(getattr(src, "id", "") or "").strip():
            raise ValueError(f"{kind} plugin {spec!r} returned {src!r}, which lacks an id or collect()")
    return sources
