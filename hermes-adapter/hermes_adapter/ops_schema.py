"""Validator for ``ops.*`` messages (docs/ops-protocol.md), for clients, tests and other adapters.

The protocol is meant to work with any adapter that speaks it, not only this one. ``validate()``
checks one decoded message against the spec and returns a list of problems (empty = valid):

    from hermes_adapter.ops_schema import validate
    problems = validate(json.loads(frame))

``strict=True`` also rejects fields the spec does not define (this adapter never sends any;
receivers in general must ignore unknown fields, so only producers should test with it).
Unknown ``ops.*`` message types are accepted: receivers ignore them by contract.
"""

from __future__ import annotations

import re
from typing import Any

from .ops import ALERT_STATES, JOB_STATUSES, KINDS, MAX, PLURAL, SERVICE_STATES, SEVERITIES, SOURCE_STATES, TEXT

_ID = re.compile(r"^[a-z0-9._-]{1,24}/[a-z0-9._-]{1,64}$")
_SOURCE_ID = re.compile(r"^[a-z0-9._-]{1,24}$")
_MIN_MS, _MAX_MS = 946684800000, 32503680000000

# field -> (type, required, extra check)
_STR, _INT, _NUM, _BOOL = "str", "int", "num", "bool"
SPEC: dict[str, dict[str, tuple[str, bool, Any]]] = {
    "service": {
        "id": (_STR, True, "id"), "sourceId": (_STR, True, "source"), "name": (_STR, True, TEXT["name"]),
        "group": (_STR, True, TEXT["group"]), "state": (_STR, True, SERVICE_STATES), "since": (_INT, False, "ts"),
        "detail": (_STR, False, TEXT["detail"]), "cpu": (_NUM, False, "pct"), "mem": (_NUM, False, "pct"),
        "disk": (_NUM, False, "pct"),
    },
    "job": {
        "id": (_STR, True, "id"), "sourceId": (_STR, True, "source"), "name": (_STR, True, TEXT["name"]),
        "lastStatus": (_STR, True, JOB_STATUSES), "enabled": (_BOOL, True, None),
        "schedule": (_STR, False, TEXT["schedule"]), "lastRun": (_INT, False, "ts"), "nextRun": (_INT, False, "ts"),
        "durationMs": (_INT, False, "duration"), "detail": (_STR, False, TEXT["detail"]),
    },
    "usage": {
        "id": (_STR, True, "id"), "sourceId": (_STR, True, "source"), "provider": (_STR, True, TEXT["provider"]),
        "window": (_STR, True, TEXT["window"]), "remainingPct": (_NUM, False, "pct"), "resetsAt": (_INT, False, "ts"),
        "detail": (_STR, False, TEXT["detail"]),
    },
    "alert": {
        "id": (_STR, True, "id"), "sourceId": (_STR, True, "source"), "ts": (_INT, True, "ts"),
        "severity": (_STR, True, SEVERITIES), "source": (_STR, True, TEXT["alert_source"]),
        "title": (_STR, True, TEXT["title"]), "state": (_STR, True, ALERT_STATES),
        "detail": (_STR, False, TEXT["alert_detail"]), "resolvedAt": (_INT, False, "ts"),
    },
    "source": {
        "id": (_STR, True, "sourceid"), "name": (_STR, True, TEXT["source_name"]), "state": (_STR, True, SOURCE_STATES),
        "interval": (_INT, True, "positive"), "kinds": ("kinds", True, None), "counts": ("counts", True, None),
        "lastOk": (_INT, False, "ts"), "detail": (_STR, False, TEXT["source_detail"]), "rejected": (_INT, False, "positive"),
    },
}
ENTITY_KINDS = KINDS + ("source",)
SNAPSHOT_KEYS = {"type", "v", "services", "jobs", "usage", "alerts", "sources", "limits", "ts"}


def _check_value(kind: str, field: str, value: Any, spec: tuple[str, bool, Any]) -> list[str]:
    typ, _req, extra = spec
    where = f"{kind}.{field}"
    if typ == _STR:
        if not isinstance(value, str):
            return [f"{where}: expected a string"]
        if isinstance(extra, int) and len(value) > extra:
            return [f"{where}: {len(value)} chars > {extra}"]
        if isinstance(extra, tuple) and value not in extra:
            return [f"{where}: {value!r} not one of {', '.join(extra)}"]
        if extra == "id" and not _ID.match(value):
            return [f"{where}: {value!r} is not <source>/<id> in [a-z0-9._-]"]
        if extra in ("source", "sourceid") and not _SOURCE_ID.match(value):
            return [f"{where}: {value!r} is not a source id"]
        if isinstance(extra, int) and (not value.strip() and field != "detail"):
            return [f"{where}: empty"]
        return []
    if typ == _BOOL:
        return [] if isinstance(value, bool) else [f"{where}: expected a boolean"]
    if typ in (_INT, _NUM):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or (typ == _INT and not isinstance(value, int)):
            return [f"{where}: expected {'an integer' if typ == _INT else 'a number'}"]
        if value != value or value in (float("inf"), float("-inf")):
            return [f"{where}: not finite"]
        if extra == "ts" and not _MIN_MS <= value <= _MAX_MS:
            return [f"{where}: {value} is not epoch milliseconds"]
        if extra == "pct" and not 0 <= value <= 100:
            return [f"{where}: {value} outside 0..100"]
        if extra in ("duration", "positive") and value < 0:
            return [f"{where}: negative"]
        return []
    if typ == "kinds":
        if not isinstance(value, list) or any(k not in KINDS for k in value):
            return [f"{where}: expected a list of {', '.join(KINDS)}"]
        return []
    if typ == "counts":
        if not isinstance(value, dict) or set(value) != {PLURAL[k] for k in KINDS} or \
                any(isinstance(v, bool) or not isinstance(v, int) or v < 0 for v in value.values()):
            return [f"{where}: expected {{services, jobs, usage, alerts}} counts"]
        return []
    return [f"{where}: unknown spec type {typ}"]


def validate_entity(kind: str, e: Any, strict: bool = False) -> list[str]:
    if kind not in SPEC:
        return [f"unknown entity kind {kind!r}"]
    if not isinstance(e, dict):
        return [f"{kind}: expected an object"]
    errs: list[str] = []
    for field, spec in SPEC[kind].items():
        if field not in e:
            if spec[1]:
                errs.append(f"{kind}.{field}: missing")
            continue
        if e[field] is None:
            errs.append(f"{kind}.{field}: null (optional fields are omitted, never null)")
            continue
        errs += _check_value(kind, field, e[field], spec)
    if kind in KINDS and isinstance(e.get("id"), str) and isinstance(e.get("sourceId"), str) \
            and not e["id"].startswith(e["sourceId"] + "/"):
        errs.append(f"{kind}.id: does not start with its sourceId")
    if kind == "alert" and "resolvedAt" in e and e.get("state") != "resolved":
        errs.append("alert.resolvedAt: only allowed when state is resolved")
    if strict:
        extra = sorted(set(e) - set(SPEC[kind]))
        if extra:
            errs.append(f"{kind}: fields not in the spec: {', '.join(extra)}")
    return errs


def validate(msg: Any, strict: bool = False) -> list[str]:
    """Problems with one ``ops.*`` message (an empty list means valid)."""
    if not isinstance(msg, dict) or not isinstance(msg.get("type"), str):
        return ["not an object with a string type"]
    t = msg["type"]
    if not t.startswith("ops."):
        return [f"{t}: not an ops.* message"]
    if "v" in msg and msg["v"] != 1:
        return [f"{t}: protocol version {msg['v']!r}, expected 1"]
    if t == "ops.snapshot":
        errs: list[str] = []
        limits = msg.get("limits")
        if not isinstance(limits, dict) or any(isinstance(v, bool) or not isinstance(v, int) or v < 0 for v in limits.values()):
            errs.append("ops.snapshot.limits: expected an object of counts")
            limits = {}
        if isinstance(msg.get("ts"), bool) or not isinstance(msg.get("ts"), int):
            errs.append("ops.snapshot.ts: expected epoch milliseconds")
        for kind in ENTITY_KINDS:
            key = PLURAL[kind]
            items = msg.get(key)
            if not isinstance(items, list):
                errs.append(f"ops.snapshot.{key}: missing or not a list")
                continue
            cap = limits.get(key, MAX[kind])
            if len(items) > cap:
                errs.append(f"ops.snapshot.{key}: {len(items)} entries > limit {cap}")
            ids = [e.get("id") for e in items if isinstance(e, dict)]
            if len(ids) != len(set(ids)):
                errs.append(f"ops.snapshot.{key}: duplicate ids")
            for i, e in enumerate(items):
                errs += [f"[{i}] {x}" for x in validate_entity(kind, e, strict)]
        if strict:
            extra = sorted(set(msg) - SNAPSHOT_KEYS - {"id"})
            if extra:
                errs.append(f"ops.snapshot: keys not in the spec: {', '.join(extra)}")
        return errs
    if t == "ops.remove":
        errs = []
        if msg.get("kind") not in ENTITY_KINDS:
            errs.append(f"ops.remove.kind: {msg.get('kind')!r} not one of {', '.join(ENTITY_KINDS)}")
        if not isinstance(msg.get("id"), str) or not msg["id"]:
            errs.append("ops.remove.id: expected a string")
        return errs
    m = re.fullmatch(r"ops\.([a-z]+)\.upsert", t)
    if m and m.group(1) in ENTITY_KINDS:
        kind = m.group(1)
        if kind not in msg:
            return [f"{t}: payload key {kind!r} missing"]
        return validate_entity(kind, msg[kind], strict)
    return []  # an ops.* type this validator does not know: receivers ignore it by contract
