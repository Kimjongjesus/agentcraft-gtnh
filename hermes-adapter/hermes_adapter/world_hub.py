"""World hub: runs one world source, keeps the model, derives events, feeds clients and the journal.

``poll()`` is blocking (HTTP) and runs in a worker thread; ``snapshot_message()`` and
``source_status()`` may be called from the event loop at the same time, so all state changes
happen under one lock (never held during the HTTP request itself).

Source states (``docs/world-protocol.md``): ``off`` (no source), ``starting`` (no capture yet),
``ok``, ``stale`` (polls work but the newest capture is old: the capture engine is stalled or the
server is lagging), ``error`` (the last poll failed; ``detail`` says why). After ``unauthorized``
the hub backs off to one attempt every 5 minutes instead of retrying a bad token every interval.
"""

from __future__ import annotations

import logging
import threading
import time
import uuid
from collections import deque
from typing import Any, Callable, Mapping

from . import world
from .sources.factory import TelemetryError

log = logging.getLogger("hermes_adapter.world")

UNAUTHORIZED_BACKOFF_S = 300.0
STALE_MIN_S = 180.0


def _stable(status: Mapping[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in status.items() if k not in ("lastOk", "lastAttempt")}


class WorldHub:
    def __init__(self, source: Any | None = None, journal: Any | None = None,
                 clock: Callable[[], float] = time.time) -> None:
        self.source = source
        self.journal = journal
        self.clock = clock
        self._lock = threading.Lock()
        self.model: dict[str, Any] | None = None  # full model, journal-only part included
        self.events: deque[dict[str, Any]] = deque(maxlen=world.LIMITS["events"])
        self.flags: dict[str, Any] = {}
        self._trend: deque[tuple[int, float | None, int | None]] = deque(maxlen=world.LIMITS["trendSamples"])
        self.state = "off" if source is None else "starting"
        self.detail = "" if source is not None else "no telemetry source configured"
        self.last_ok: float | None = None
        self.last_attempt: float | None = None
        self.health: dict[str, Any] = {}
        self.next_due = 0.0
        self.polls = 0
        self.journal_error: str | None = None
        self._ever_ok = False
        self._run = uuid.uuid4().hex[:6]
        self._seq = 0

    @property
    def interval(self) -> float:
        return float(getattr(self.source, "interval", 30.0) or 30.0)

    def due(self, now: float | None = None) -> bool:
        return self.source is not None and (self.clock() if now is None else now) >= self.next_due

    # ---- wire views ------------------------------------------------------------------------
    def source_status(self) -> dict[str, Any]:
        with self._lock:
            return self._status_locked()

    def _status_locked(self) -> dict[str, Any]:
        out: dict[str, Any] = {"id": str(getattr(self.source, "id", "factory")) if self.source else "factory",
                               "name": str(getattr(self.source, "name", "") or "factory telemetry"), "state": self.state}
        if self.detail:
            out["detail"] = world.clean(self.detail, world.TEXT["detail"])
        if self.last_ok:
            out["lastOk"] = int(self.last_ok * 1000)
        if self.last_attempt:
            out["lastAttempt"] = int(self.last_attempt * 1000)
        h = self.health
        for key in ("capturedAt", "sequence", "session", "telemetry", "modVersion", "mods"):
            if h.get(key) not in (None, ""):
                out[key] = h[key]
        return out

    def snapshot_message(self) -> dict[str, Any]:
        with self._lock:
            msg: dict[str, Any] = {"type": "world.snapshot", "world": world.VERSION, "ts": int(self.clock() * 1000),
                                   "source": self._status_locked(), "machines": []}
            if self.model is not None:
                pub = world.public(self.model)
                msg["base"] = pub["base"]
                msg["machines"] = pub["machines"]
                for key in ("power", "ae2", "design"):
                    msg[key] = pub[key]
            msg["events"] = list(self.events)
            msg["limits"] = dict(world.LIMITS)
            return msg

    # ---- polling ----------------------------------------------------------------------------
    def poll(self) -> list[dict[str, Any]]:
        """One collect + ingest. Returns the ``world.*`` messages for opted-in clients.

        ``world.source.upsert`` is sent when the state, detail or capture identity changes; the
        poll timestamps alone (``lastOk``, ``lastAttempt``) do not trigger one."""
        if self.source is None:
            return []
        before = _stable(self.source_status())
        now = self.clock()
        msgs: list[dict[str, Any]] = []
        try:
            raw = self.source.collect()
        except TelemetryError as e:
            msgs.extend(self._fail(e.detail, now, backoff=e.detail == "unauthorized"))
        except Exception as e:  # a plugin bug must not stop the adapter; never log its message (may hold data)
            log.warning("world source %s failed: %s", getattr(self.source, "id", "?"), type(e).__name__)
            msgs.extend(self._fail("collector failed", now))
        else:
            msgs.extend(self._ingest(raw if isinstance(raw, Mapping) else {}, now))
        after = self.source_status()
        if _stable(after) != before:
            msgs.insert(0, {"type": "world.source.upsert", "source": after})
        return msgs

    def _event(self, e: dict[str, Any], ts_ms: int) -> dict[str, Any]:
        self._seq += 1
        return {"id": f"{self._run}-{self._seq}", "ts": ts_ms, **e}

    def _fail(self, detail: str, now: float, backoff: bool = False) -> list[dict[str, Any]]:
        with self._lock:
            self.polls += 1
            self.last_attempt = now
            was = self.state
            if was != "error" or self.detail != detail:
                log.warning("factory telemetry: %s", detail)
            self.state, self.detail = "error", detail
            self.next_due = now + (max(UNAUTHORIZED_BACKOFF_S, self.interval) if backoff else self.interval)
            events = []
            if was in ("ok", "stale"):
                events.append(self._event(world._ev("source.lost", "warn", "source", f"factory telemetry lost: {detail}"),
                                          int(now * 1000)))
            self.events.extend(events)
            session = (self.model or {}).get("session", "")
        self._journal(None, events, int(now * 1000), session)
        return [{"type": "world.event", "event": e} for e in events]

    def _ingest(self, raw: Mapping[str, Any], now: float) -> list[dict[str, Any]]:
        health = raw.get("health") if isinstance(raw.get("health"), Mapping) else {}
        capture = raw.get("capture") if isinstance(raw.get("capture"), Mapping) else None
        new = world.normalize(capture) if capture is not None else None
        msgs: list[dict[str, Any]] = []
        events: list[dict[str, Any]] = []
        snapshot_needed = False
        with self._lock:
            self.polls += 1
            self.last_attempt = self.last_ok = now
            self.next_due = now + self.interval
            was = self.state
            self._read_health(health, capture)
            if new is not None:
                ts = new["base"]["capturedAt"] or int(now * 1000)
                old = self.model
                if old is None or old["session"] != new["session"]:
                    if old is not None:
                        events.append(world._ev("session.started", "info", "base",
                                                "game server restarted: new telemetry session, history continues"))
                    self._trend.clear()
                    fill = ((new.get("power") or {}).get("ae") or {}).get("fillPct")
                    self.flags = {"powerLow": fill is not None and fill < world.POWER_LOW_PCT}
                    self._add_trend(new, ts)
                    self.model = new
                    snapshot_needed = True
                elif new["sequence"] != old["sequence"] or new["base"]["capturedAt"] != old["base"]["capturedAt"]:
                    self._add_trend(new, ts)
                    events.extend(world.change_events(old, new, self.flags))
                    msgs.extend(world.diff_messages(world.public(old), world.public(new)))
                    self.model = new
                else:
                    new = None  # same capture again: nothing to record
            state, detail = self._freshness(health)
            if was == "error" and self._ever_ok and state in ("ok", "stale"):
                events.insert(0, world._ev("source.ok", "info", "source", "factory telemetry reachable again"))
            if state in ("ok", "stale"):
                self._ever_ok = True
            if state != was or detail != self.detail:
                log.info("factory telemetry: %s%s", state, f" ({detail})" if detail else "")
            self.state, self.detail = state, detail
            ts_ms = (new or {}).get("base", {}).get("capturedAt") or int(now * 1000)
            events = [self._event(e, ts_ms) for e in events]
            self.events.extend(events)
            session = (self.model or {}).get("session", "")
            model_for_journal = new
        if snapshot_needed:
            msgs = [self.snapshot_message()]  # it already lists this poll's events
        else:
            msgs.extend({"type": "world.event", "event": e} for e in events)
        self._journal(model_for_journal, events, ts_ms, session, force=snapshot_needed)
        return msgs

    def _read_health(self, health: Mapping[str, Any], capture: Mapping[str, Any] | None) -> None:
        h = self.health
        h["telemetry"] = world.text(health.get("protocol"), world.TEXT["short"])
        h["sequence"] = world.to_int(health.get("captureSequence"), 0)
        h["capturedAt"] = world.to_int(health.get("lastCaptureMillis"), 0)
        h["now"] = world.to_int(health.get("nowMillis"), 0)
        h["mods"] = {"gregTech": health.get("gregTech") is True, "ae2": health.get("ae2") is True}
        if capture is not None:
            sess = capture.get("session") if isinstance(capture.get("session"), Mapping) else {}
            h["session"] = world.wid(sess.get("id"), 64)
            h["modVersion"] = world.text(sess.get("modVersion"), world.TEXT["short"])

    def _freshness(self, health: Mapping[str, Any]) -> tuple[str, str]:
        last, now = self.health.get("capturedAt"), self.health.get("now")
        if not last:
            return "starting", "no capture yet"
        limit = max(STALE_MIN_S, 3 * self.interval) * 1000
        if now and now - last > limit:
            return "stale", f"newest capture is {int((now - last) / 60000)} min old"
        return "ok", ""

    def _add_trend(self, model: dict[str, Any], ts: int) -> None:
        p = model.get("power") or {}
        ae = (p.get("ae") or {}).get("stored")
        eu = (p.get("eu") or {}).get("stored") if (p.get("eu") or {}).get("machinesReporting") else None
        self._trend.append((ts, ae, eu))
        if len(self._trend) < 2:
            return
        (t0, a0, e0), (t1, a1, e1) = self._trend[0], self._trend[-1]
        if t1 <= t0:
            return
        trend: dict[str, Any] = {"windowMs": t1 - t0, "samples": len(self._trend)}
        if a0 is not None and a1 is not None:
            trend["aeStoredDelta"] = round(a1 - a0, 1)
            trend["aeStoredPerMin"] = round((a1 - a0) * 60000 / (t1 - t0), 1)
        if e0 is not None and e1 is not None:
            trend["euStoredDelta"] = e1 - e0
        p["trend"] = trend

    def _journal(self, model: dict[str, Any] | None, events: list[dict[str, Any]], ts_ms: int,
                 session: str, force: bool = False) -> None:
        if self.journal is None or (model is None and not events):
            return
        try:
            self.journal.record(model, events, ts_ms, session=session, force_snapshot=force)
            self.journal_error = None
        except Exception as e:  # the live feed must survive a full disk or a locked file
            if self.journal_error != type(e).__name__:
                log.error("world journal write failed: %s", type(e).__name__)
            self.journal_error = type(e).__name__

    def close(self) -> None:
        for obj in (self.source, self.journal):
            closer = getattr(obj, "close", None)
            if callable(closer):
                try:
                    closer()
                except Exception:
                    log.warning("close failed for %s", type(obj).__name__)
