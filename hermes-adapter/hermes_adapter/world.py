"""``world.*``: factory telemetry for AgentCraft (wire format: ``../../docs/world-protocol.md``).

A *world source* (``sources/factory.py`` is the built-in one) returns the raw telemetry of the game
(``{"health": {...}, "capture": {...}}`` in the ``ai-factory/v2`` shape). Nothing it returns
reaches a client or the journal before this module has been over it:

* only whitelisted fields survive; numbers are validated (finite, clamped), booleans are
  tri-state (an unknown value is omitted, never turned into ``false``);
* EVERY string goes through :func:`redact.clean` over the whole text before it is cut to its
  display limit; ids are reduced to a strict character set (and hashed if the filter objects);
* every list is bounded (the important entries are kept);
* player names and the scope anchor are split off into a ``private`` part that only the local
  journal sees. ``world.*`` frames carry counts, never names.

:class:`WorldHub` runs one source, keeps the current model, derives change events between two
captures of the same telemetry session and produces the ``world.*`` messages.
"""

from __future__ import annotations

import hashlib
import math
import re
from typing import Any, Mapping

from .redact import clean

FEATURE = "world"
VERSION = 1
TELEMETRY_PROTOCOL = "ai-factory/v2"

MACHINE_STATES = ("unformed", "maintenance", "problem", "running", "idle", "unknown")
SOURCE_STATES = ("off", "starting", "ok", "stale", "error")
COVERAGE_STATES = ("ok", "partial", "unavailable", "error")
SEVERITIES = ("info", "warn", "critical")
SCOPE_KINDS = {"CONFIGURED": "configured", "PLAYER_RELATIVE": "player_relative", "NONE": "none"}
# the wire vocabulary; docs/world-protocol.md lists exactly these (tests/test_world_protocol_doc.py)
MESSAGE_TYPES = ("world.snapshot", "world.source.upsert", "world.base.upsert", "world.machine.upsert",
                 "world.machine.remove", "world.power.upsert", "world.ae2.upsert", "world.design.upsert",
                 "world.event")
EVENT_KINDS = ("machine.appeared", "machine.gone", "machine.started", "machine.stopped", "machine.problem",
               "machine.recovered", "machine.maintenance", "machine.unformed", "machine.formed", "power.low",
               "power.recovered", "ae2.offline", "ae2.online", "ae2.cpus.full", "ae2.cpus.free",
               "coverage.degraded", "coverage.recovered", "session.started", "session.settled", "source.lost",
               "source.ok", "events.dropped")

LIMITS = {
    "machines": 512, "parts": 32, "maintenance": 8, "top": 16, "palette": 24,
    "events": 50, "eventsPerCapture": 64, "trendSamples": 20, "players": 16, "warnings": 8,
}
TEXT = {
    "id": 128, "type": 96, "name": 64, "label": 48, "problem": 120, "issue": 48, "event": 160,
    "headline": 200, "detail": 120, "short": 32, "player": 32,
}

_INT_MAX = 2 ** 62
_ID_CHARS = re.compile(r"[^A-Za-z0-9_.:@,#+\-]")


# ---------------------------------------------------------------------------------------------
# scalar helpers (every value from the telemetry is untrusted)

def to_int(value: Any, lo: int = -_INT_MAX, hi: int = _INT_MAX) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        v = value
    elif isinstance(value, float) and math.isfinite(value):
        v = int(value)
    else:
        return None
    return max(lo, min(hi, v))


def to_num(value: Any, digits: int = 3) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    v = float(value)
    if not math.isfinite(v):
        return None
    return round(max(-1e15, min(1e15, v)), digits)


def to_bool(value: Any) -> bool | None:
    return value if isinstance(value, bool) else None


def text(value: Any, limit: int) -> str:
    """Whole-text privacy filter first, then the display cut (see redact.clean)."""
    if value is None or isinstance(value, (dict, list)):
        return ""
    return clean(value, limit)


def wid(value: Any, limit: int = TEXT["id"]) -> str:
    """Identifier: strict charset; hashed when the privacy filter would change it; bounded."""
    raw = "" if value is None or isinstance(value, (dict, list)) else str(value)
    s = _ID_CHARS.sub("_", raw.strip())
    if not s:
        return ""
    digest = hashlib.sha1(raw.encode("utf-8", "replace")).hexdigest()[:12]
    if clean(s, 0) != s:
        return "id-" + digest
    if len(s) > limit:
        return s[: limit - 13] + "~" + digest
    return s


def _put(out: dict[str, Any], key: str, value: Any) -> None:
    """Optional fields are omitted, never null."""
    if value is not None and value != "":
        out[key] = value


def _get(raw: Any, *path: str) -> Any:
    cur = raw
    for key in path:
        if not isinstance(cur, Mapping):
            return None
        cur = cur.get(key)
    return cur


def _list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


# ---------------------------------------------------------------------------------------------
# normalisation: raw capture -> model

def coverage(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, Mapping):
        return {"status": "unavailable", "complete": False, "truncated": False, "budgetExhausted": False,
                "attempted": 0, "succeeded": 0, "errors": 0, "skippedUnloaded": 0, "returned": 0,
                "distinctSeen": 0, "reason": "missing"}
    status = str(raw.get("status") or "").lower()
    out: dict[str, Any] = {"status": status if status in COVERAGE_STATES else "error"}
    for key in ("complete", "truncated", "budgetExhausted"):
        out[key] = raw.get(key) is True
    for key in ("attempted", "succeeded", "errors", "skippedUnloaded", "returned", "distinctSeen"):
        out[key] = to_int(raw.get(key), 0) or 0
    _put(out, "reason", text(raw.get("reason"), TEXT["short"]))
    return out


def machine_state(m: Mapping[str, Any]) -> str:
    if m.get("formed") is False:
        return "unformed"
    if m.get("needsMaintenance") is True or m.get("maintenance"):
        return "maintenance"
    if m.get("problem"):
        return "problem"
    if m.get("active") is True:
        return "running"
    if m.get("active") is False:
        return "idle"
    return "unknown"


_STATE_RANK = {s: i for i, s in enumerate(MACHINE_STATES)}


def machine(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, Mapping):
        return None
    mid = wid(raw.get("id"))
    if not mid:
        return None
    out: dict[str, Any] = {"id": mid, "kind": "multiblock"}
    _put(out, "type", wid(raw.get("typeKey"), TEXT["type"]))
    out["name"] = text(raw.get("name"), TEXT["name"]) or out.get("type", "machine")
    for key, src in (("dim", "dimensionId"), ("x", "x"), ("y", "y"), ("z", "z")):
        out[key] = to_int(raw.get(src), -(2 ** 31), 2 ** 31) or 0
    for key in ("formed", "active", "needsMaintenance"):
        _put(out, key, to_bool(raw.get(key)))
    _put(out, "problem", text(raw.get("problem"), TEXT["problem"]))
    issues = [text(i, TEXT["issue"]) for i in _list(raw.get("maintenanceIssues"))[: LIMITS["maintenance"]]]
    if any(issues):
        out["maintenance"] = [i for i in issues if i]
    _put(out, "euPerTick", to_int(raw.get("euPerTick")))
    ticks, top = to_int(raw.get("progressTicks"), 0), to_int(raw.get("maxProgressTicks"), 0)
    if top:
        out["progress"] = {"ticks": min(ticks or 0, top), "max": top}
    _put(out, "euStored", to_int(raw.get("euStored"), 0))
    _put(out, "euCapacity", to_int(raw.get("euCapacity"), 0))
    _put(out, "efficiencyPct", to_num(raw.get("efficiencyPercent"), 1))
    parts_raw = _list(raw.get("parts"))
    parts = []
    for p in parts_raw[: LIMITS["parts"]]:
        if not isinstance(p, Mapping):
            continue
        part: dict[str, Any] = {"type": wid(p.get("type"), TEXT["short"]) or "part"}
        _put(part, "name", text(p.get("name"), TEXT["label"]))
        for key in ("x", "y", "z"):
            part[key] = to_int(p.get(key), -(2 ** 31), 2 ** 31) or 0
        _put(part, "tier", to_int(p.get("tier"), -1, 64))
        _put(part, "meChannelActive", to_bool(p.get("meChannelActive")))
        parts.append(part)
    out["parts"] = parts
    out["partsTotal"] = len(parts_raw)
    out["state"] = machine_state(out)
    return out


def machines(raw_list: Any) -> list[dict[str, Any]]:
    seen: dict[str, dict[str, Any]] = {}
    for raw in _list(raw_list):
        m = machine(raw)
        if m is not None and m["id"] not in seen:
            seen[m["id"]] = m
    keep = sorted(seen.values(), key=lambda m: (_STATE_RANK[m["state"]] > 2, m["id"]))[: LIMITS["machines"]]
    return sorted(keep, key=lambda m: m["id"])


def power(stock: Any, ms: list[dict[str, Any]], ae_readable: bool) -> dict[str, Any]:
    out: dict[str, Any] = {}
    p = _get(stock, "power")
    if ae_readable and isinstance(p, Mapping) and to_num(p.get("max")) is not None:
        stored, top = to_num(p.get("stored"), 1) or 0.0, to_num(p.get("max"), 1) or 0.0
        ae: dict[str, Any] = {"stored": stored, "max": top, "unit": "AE"}
        if top > 0:
            ae["fillPct"] = round(max(0.0, min(100.0, stored * 100.0 / top)), 1)
        _put(ae, "avgUsage", to_num(p.get("avgUsage"), 2))
        _put(ae, "avgInjection", to_num(p.get("avgInjection"), 2))
        _put(ae, "powered", to_bool(p.get("powered")))
        out["ae"] = ae
    consuming = sum(-m["euPerTick"] for m in ms if m.get("active") is True and m.get("euPerTick", 0) < 0)
    generating = sum(m["euPerTick"] for m in ms if m.get("active") is True and m.get("euPerTick", 0) > 0)
    reporting = [m for m in ms if "euCapacity" in m]
    out["eu"] = {
        "consumingPerTick": consuming, "generatingPerTick": generating,
        "stored": sum(m.get("euStored", 0) for m in reporting),
        "capacity": sum(m["euCapacity"] for m in reporting),
        "machinesReporting": len(reporting),
    }
    return out


def ae2(stock: Any) -> dict[str, Any]:
    cov = coverage(_get(stock, "coverage"))
    items = []
    for raw in _list(_get(stock, "items")):
        if not isinstance(raw, Mapping):
            continue
        iid = wid(raw.get("id"))
        if iid:
            items.append({"id": iid, "name": text(raw.get("name"), TEXT["name"]) or iid,
                          "quantity": to_int(raw.get("quantity"), 0) or 0, "craftable": raw.get("craftable") is True})
    cpus = to_int(_get(stock, "crafting", "cpus"), 0) or 0
    busy = min(cpus, to_int(_get(stock, "crafting", "busyCpus"), 0) or 0)
    out: dict[str, Any] = {
        "available": cov["status"] in ("ok", "partial"),
        "cpus": cpus, "busyCpus": busy, "jobs": busy,
        "itemTypes": len(items), "itemTypesSeen": max(len(items), cov["distinctSeen"]),
        "craftableTypes": sum(1 for i in items if i["craftable"]),
        "totalItems": sum(i["quantity"] for i in items),
        "top": sorted(items, key=lambda i: (-i["quantity"], i["id"]))[: LIMITS["top"]],
        "coverage": cov,
    }
    _put(out, "powered", to_bool(_get(stock, "power", "powered")))
    return out


def design(raw: Any) -> dict[str, Any]:
    cov = coverage(_get(raw, "coverage"))
    out: dict[str, Any] = {"available": isinstance(raw, Mapping) and to_int(raw.get("stride")) is not None, "coverage": cov}
    if not out["available"]:
        return out
    for key in ("stride", "blocksSampled", "solidBlocks"):
        out[key] = to_int(raw.get(key), 0) or 0
    out["paletteSeen"] = to_int(raw.get("paletteDistinctSeen"), 0) or 0
    for key in ("density", "dominantShare"):
        _put(out, key, to_num(raw.get(key), 4))
    sampled = max(1, out["blocksSampled"])
    palette = []
    for b in _list(raw.get("palette"))[: LIMITS["palette"] * 4]:
        if isinstance(b, Mapping) and wid(b.get("id")):
            count = to_int(b.get("count"), 0) or 0
            palette.append({"id": wid(b.get("id")), "name": text(b.get("name"), TEXT["name"]) or wid(b.get("id")),
                            "count": count, "share": round(count / sampled, 4)})
    out["palette"] = sorted(palette, key=lambda b: (-b["count"], b["id"]))[: LIMITS["palette"]]
    light = _get(raw, "artificialLight")
    if isinstance(light, Mapping):
        out["light"] = {k: v for k, v in (
            ("open", to_int(light.get("openPositions"), 0)), ("lit", to_int(light.get("lit"), 0)),
            ("unlit", to_int(light.get("unlit"), 0)), ("unlitFraction", to_num(light.get("unlitFraction"), 4)),
            ("minLight", to_int(light.get("minLight"), 0, 15)), ("maxLight", to_int(light.get("maxLight"), 0, 15)),
        ) if v is not None}
    return out


def _scope(raw: Any) -> dict[str, Any]:
    s = raw if isinstance(raw, Mapping) else {}
    out: dict[str, Any] = {"kind": SCOPE_KINDS.get(str(s.get("kind") or "").upper(), "none")}
    out["dimensionId"] = to_int(s.get("dimensionId"), -(2 ** 31), 2 ** 31) or 0
    _put(out, "dimension", text(s.get("dimension"), TEXT["label"]))
    out["center"] = {k: to_int(s.get("center" + k.upper()), -(2 ** 31), 2 ** 31) or 0 for k in ("x", "y", "z")}
    for key in ("radius", "height", "minY", "maxY"):
        out[key] = to_int(s.get(key), -(2 ** 31), 2 ** 31) or 0
    return out


def _count_states(ms: list[dict[str, Any]]) -> dict[str, int]:
    counts = {s: 0 for s in MACHINE_STATES}
    for m in ms:
        counts[m["state"]] += 1
    return counts


def headline(model: Mapping[str, Any]) -> str:
    """One readable line about the base (the 'vibe'); coverage gaps are always named."""
    base = model.get("base") or {}
    s = base.get("summary") or {}
    n = s.get("machines", 0)
    parts = []
    if n:
        bits = [f"{s.get(k, 0)} {label}" for k, label in (
            ("running", "running"), ("idle", "idle"), ("problem", "with a problem"),
            ("maintenance", "needing maintenance"), ("unformed", "unformed"), ("unknown", "unknown"),
        ) if s.get(k)]
        parts.append(f"{n} machine{'s' if n != 1 else ''}: " + ", ".join(bits) + ".")
    else:
        parts.append("No machines in view.")
    a = model.get("ae2") or {}
    if a.get("available"):
        pw = "powered" if a.get("powered") is not False else "NOT powered"
        parts.append(f"AE2 {pw}, {a.get('busyCpus', 0)} of {a.get('cpus', 0)} crafting CPUs busy.")
    gaps = [name for name, c in (base.get("coverage") or {}).items() if c.get("status") != "ok"]
    if gaps:
        parts.append("Not fully seen: " + ", ".join(sorted(gaps)) + ".")
    return clean(" ".join(parts), TEXT["headline"])


def normalize(capture: Mapping[str, Any]) -> dict[str, Any]:
    """Raw ``/telemetry/capture`` -> world model (``private`` is journal-only, never on the wire)."""
    ms = machines(capture.get("multiblocks"))
    stock = capture.get("stock") if isinstance(capture.get("stock"), Mapping) else {}
    a = ae2(stock)
    d = design(capture.get("design"))
    sur = capture.get("surroundings") if isinstance(capture.get("surroundings"), Mapping) else {}
    covs = {
        "machines": coverage(capture.get("machineCoverage")), "stock": a["coverage"],
        "design": d["coverage"], "surroundings": coverage(sur.get("coverage")),
    }
    counts = _count_states(ms)
    players = [text(p, TEXT["player"]) for p in _list(sur.get("playersInScope"))]
    summary: dict[str, Any] = {"machines": len(ms), **counts,
                               "playersOnline": to_int(sur.get("playersOnline"), 0) or 0,
                               "playersInScope": len(players), "hostiles": to_int(sur.get("hostileCount"), 0) or 0}
    for key in ("raining", "thundering"):
        _put(summary, key, to_bool(sur.get(key)))
    _put(summary, "worldTime", to_int(sur.get("worldTime"), 0))
    _put(summary, "biome", text(sur.get("biomeAtCenter"), TEXT["label"]))
    session = _get(capture, "session") or {}
    base: dict[str, Any] = {
        "id": wid(capture.get("baseId")) or "base",
        "label": text(_get(capture, "scope", "label"), TEXT["label"]) or "base",
        "revision": to_int(capture.get("worldRevision"), 0) or 0,
        "capturedAt": to_int(capture.get("capturedAtMillis"), 0) or 0,
        "scope": _scope(capture.get("scope")), "coverage": covs, "summary": summary,
    }
    _put(base, "world", text(capture.get("worldName"), TEXT["label"]))
    model: dict[str, Any] = {
        "session": wid(session.get("id") if isinstance(session, Mapping) else None, 64) or "unknown",
        "sessionStartedAt": to_int(session.get("startedAtMillis") if isinstance(session, Mapping) else None, 0) or 0,
        "sequence": to_int(capture.get("captureSequence"), 0) or 0,
        "base": base, "machines": ms, "power": power(stock, ms, a["available"]), "ae2": a, "design": d,
        "private": {
            "playersInScope": [p for p in players if p][: LIMITS["players"]],
            "anchor": text(_get(capture, "scope", "anchor"), TEXT["player"]),
            "warnings": [text(w, TEXT["problem"]) for w in _list(capture.get("warnings"))[: LIMITS["warnings"]]],
        },
    }
    base["headline"] = headline(model)
    names = [n for n in model["private"]["playersInScope"] + [model["private"]["anchor"]] if len(n) >= 3]
    if names:
        # a player name the capture itself reports can also turn up in free text (a renamed
        # machine, a sign): keep it out of everything but the journal-only part
        pattern = re.compile("|".join(re.escape(n) for n in sorted(set(names), key=len, reverse=True)), re.I)
        for key in ("base", "machines", "power", "ae2", "design"):
            model[key] = _scrub_names(model[key], pattern)
    return model


def _scrub_names(obj: Any, pattern: re.Pattern[str], key: str = "") -> Any:
    if isinstance(obj, str):
        return obj if key in ("id", "type") else pattern.sub("[player]", obj)
    if isinstance(obj, dict):
        return {k: _scrub_names(v, pattern, k) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_scrub_names(v, pattern, key) for v in obj]
    return obj


def public(model: Mapping[str, Any]) -> dict[str, Any]:
    """The model without its journal-only part (what a WebSocket client may see)."""
    return {k: v for k, v in model.items() if k != "private"}


# ---------------------------------------------------------------------------------------------
# change events between two captures of the same session

POWER_LOW_PCT = 20.0
POWER_CRITICAL_PCT = 5.0
POWER_RECOVERED_PCT = 30.0
# Right after a game server starts, GregTech multiblocks report "unformed" until their first
# structure check runs (seen on a GTNH 2.9 copy: the first capture ~20 s after boot had almost
# every multiblock unformed, the next one had them formed). Machine transitions out of a capture
# taken inside this window are therefore start-up noise, not news.
SETTLE_MS = 120_000
_SEV_RANK = {"critical": 0, "warn": 1, "info": 2}


def settling(model: Mapping[str, Any]) -> bool:
    """True when ``model`` was captured inside the start-up window of its telemetry session."""
    started = model.get("sessionStartedAt") or 0
    captured = (model.get("base") or {}).get("capturedAt") or 0
    return bool(started) and captured - started < SETTLE_MS


def settled_summary(model: Mapping[str, Any]) -> dict[str, Any]:
    """The one event that replaces the start-up burst: the state of the base once it settled."""
    s = (model.get("base") or {}).get("summary") or {}
    bits = [f"{s.get(k, 0)} {label}" for k, label in (
        ("running", "running"), ("idle", "idle"), ("problem", "with a problem"),
        ("maintenance", "needing maintenance"), ("unformed", "unformed")) if s.get(k)]
    text = f"base settled after the server start: {s.get('machines', 0)} machines" + (": " + ", ".join(bits) if bits else "")
    return _ev("session.settled", "info", "base", text, {k: s.get(k, 0) for k in ("machines", *MACHINE_STATES)})


def _ev(kind: str, severity: str, subject: str, msg: str, data: Mapping[str, Any] | None = None) -> dict[str, Any]:
    out: dict[str, Any] = {"kind": kind, "severity": severity, "subject": subject, "text": clean(msg, TEXT["event"])}
    if data:
        out["data"] = dict(data)
    return out


def _machine_events(old: Mapping[str, Any], new: Mapping[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    before = {m["id"]: m for m in old.get("machines", [])}
    after = {m["id"]: m for m in new.get("machines", [])}
    for mid, m in after.items():
        loc = {"type": m.get("type", ""), "x": m["x"], "y": m["y"], "z": m["z"], "dim": m["dim"]}
        prev = before.get(mid)
        if prev is None:
            out.append(_ev("machine.appeared", "info", mid, f"{m['name']} appeared ({m['state']})", {**loc, "state": m["state"]}))
            continue
        a, b = prev["state"], m["state"]
        data = {**loc, "from": a, "to": b}
        if a == b:
            if b == "problem" and prev.get("problem") != m.get("problem"):
                out.append(_ev("machine.problem", "warn", mid, f"{m['name']}: {m.get('problem', 'problem')}", {**data, "problem": m.get("problem", "")}))
            continue
        if b == "unformed":
            out.append(_ev("machine.unformed", "critical", mid, f"{m['name']} is no longer formed", data))
        elif b == "maintenance":
            issues = ", ".join(m.get("maintenance", [])) or "maintenance needed"
            out.append(_ev("machine.maintenance", "warn", mid, f"{m['name']} needs maintenance: {issues}", data))
        elif b == "problem":
            out.append(_ev("machine.problem", "warn", mid, f"{m['name']}: {m.get('problem', 'problem')}", {**data, "problem": m.get("problem", "")}))
        elif a == "unformed":
            out.append(_ev("machine.formed", "info", mid, f"{m['name']} is formed again ({b})", data))
        elif a in ("problem", "maintenance"):
            out.append(_ev("machine.recovered", "info", mid, f"{m['name']} recovered ({b})", data))
        elif b == "running":
            out.append(_ev("machine.started", "info", mid, f"{m['name']} started running", data))
        elif a == "running":
            out.append(_ev("machine.stopped", "info", mid, f"{m['name']} stopped ({b})", data))
    unloaded = (((new.get("base") or {}).get("coverage") or {}).get("machines") or {}).get("skippedUnloaded", 0)
    for mid, m in before.items():
        if mid not in after:
            loc = {"type": m.get("type", ""), "x": m["x"], "y": m["y"], "z": m["z"], "dim": m["dim"], "from": m["state"]}
            if unloaded:
                # the capture skipped positions in unloaded chunks: most likely nobody is near it,
                # not that it was removed; say so instead of raising an alarm
                out.append(_ev("machine.gone", "info", mid, f"{m['name']} is no longer seen (was {m['state']}; "
                               f"its chunk may be unloaded)", {**loc, "unloadedSkipped": unloaded}))
            else:
                sev = "warn" if m["state"] == "running" else "info"
                out.append(_ev("machine.gone", sev, mid, f"{m['name']} is no longer seen (was {m['state']})", loc))
    return out


def _coverage_events(old: Mapping[str, Any], new: Mapping[str, Any]) -> list[dict[str, Any]]:
    out = []
    oc = (old.get("base") or {}).get("coverage") or {}
    nc = (new.get("base") or {}).get("coverage") or {}
    for name in sorted(nc):
        a, b = (oc.get(name) or {}).get("status", "ok"), nc[name]["status"]
        if a == "ok" and b != "ok":
            why = nc[name].get("reason") or ("budget exhausted" if nc[name].get("budgetExhausted") else b)
            out.append(_ev("coverage.degraded", "warn", "base", f"{name} only {b} ({why})", {"section": name, "status": b}))
        elif a != "ok" and b == "ok":
            out.append(_ev("coverage.recovered", "info", "base", f"{name} fully seen again", {"section": name}))
    return out


def _ae_events(old: Mapping[str, Any], new: Mapping[str, Any], flags: dict[str, Any]) -> list[dict[str, Any]]:
    out = []
    oa, na = old.get("ae2") or {}, new.get("ae2") or {}
    if oa.get("powered") is True and na.get("powered") is False:
        out.append(_ev("ae2.offline", "critical", "ae2", "AE2 network lost power"))
    elif oa.get("powered") is False and na.get("powered") is True:
        out.append(_ev("ae2.online", "info", "ae2", "AE2 network powered again"))
    cpus, busy, obusy = na.get("cpus", 0), na.get("busyCpus", 0), oa.get("busyCpus", 0)
    if cpus and busy >= cpus and obusy < oa.get("cpus", 0):
        out.append(_ev("ae2.cpus.full", "info", "ae2", f"all {cpus} crafting CPUs are busy", {"cpus": cpus}))
    elif cpus and busy < cpus and oa.get("cpus") and obusy >= oa["cpus"]:
        out.append(_ev("ae2.cpus.free", "info", "ae2", f"{cpus - busy} of {cpus} crafting CPUs free", {"cpus": cpus, "busy": busy}))
    fill = ((new.get("power") or {}).get("ae") or {}).get("fillPct")
    if fill is not None:
        if not flags.get("powerLow") and fill < POWER_LOW_PCT:
            flags["powerLow"] = True
            sev = "critical" if fill < POWER_CRITICAL_PCT else "warn"
            out.append(_ev("power.low", sev, "power", f"AE2 energy storage at {fill:.1f}%", {"fillPct": fill}))
        elif flags.get("powerLow") and fill >= POWER_RECOVERED_PCT:
            flags["powerLow"] = False
            out.append(_ev("power.recovered", "info", "power", f"AE2 energy storage back to {fill:.1f}%", {"fillPct": fill}))
    return out


def change_events(old: Mapping[str, Any], new: Mapping[str, Any], flags: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    """Events between two models of the SAME telemetry session (bounded per capture).

    While ``old`` is inside the start-up window (:func:`settling`) machine and coverage
    transitions are suppressed; the first capture after the window yields one ``session.settled``
    summary instead of hundreds of "formed" events."""
    flags = flags if flags is not None else {}
    if settling(old):
        events = _ae_events(old, new, flags)
        if not settling(new):
            events.insert(0, settled_summary(new))
        return events
    events = _machine_events(old, new) + _coverage_events(old, new) + _ae_events(old, new, flags)
    cap = LIMITS["eventsPerCapture"]
    if len(events) > cap:
        keep = sorted(range(len(events)), key=lambda i: (_SEV_RANK[events[i]["severity"]], i))[: cap - 1]
        dropped = len(events) - len(keep)
        events = [events[i] for i in sorted(keep)]
        events.append(_ev("events.dropped", "info", "base", f"{dropped} more changes in this capture not listed", {"dropped": dropped}))
    return events


def diff_messages(old: Mapping[str, Any], new: Mapping[str, Any]) -> list[dict[str, Any]]:
    """``world.*`` upserts/removes that turn the public ``old`` model into ``new``."""
    out: list[dict[str, Any]] = []
    if old.get("base") != new.get("base") and new.get("base") is not None:
        out.append({"type": "world.base.upsert", "base": new["base"]})
    before = {m["id"]: m for m in old.get("machines", [])}
    after_ids = set()
    for m in new.get("machines", []):
        after_ids.add(m["id"])
        if before.get(m["id"]) != m:
            out.append({"type": "world.machine.upsert", "machine": m})
    for mid in sorted(set(before) - after_ids):
        out.append({"type": "world.machine.remove", "id": mid, "reason": "gone"})
    for key in ("power", "ae2", "design"):
        if old.get(key) != new.get(key) and new.get(key) is not None:
            out.append({"type": f"world.{key}.upsert", key: new[key]})
    return out
