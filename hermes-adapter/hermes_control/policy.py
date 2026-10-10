"""The policy file: owner-maintained, strictly validated, never changed by anything on the wire.

Unknown keys, unknown capability names, a ``permissionApprove: true`` and a chat toolset that
contains a denied name all make loading fail, which makes the service refuse to start (or, on
SIGHUP, keep the previous policy). Capabilities are off unless ``enabled`` is true.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import frames, safety

SCHEMA = 1
MAX_POLICY_BYTES = 64 * 1024
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")  # never a leading '-': names end up in argv
TOOLSET = re.compile(r"^[a-z0-9][a-z0-9_:.-]{0,63}$")

# Hermes chat toolsets that must never be reachable from the game (open question 3). A policy
# toolset whose name equals one of DENIED_EXACT or contains one of DENIED_PARTS is refused. This is
# a denylist on top of "name your read-only toolset in the policy"; enforcement inside Hermes
# cannot be proven here, which is why chat ships disabled.
DENIED_EXACT = frozenset({"all", "*", "default", "full", "everything", "hermes", "hermes-cli"})
DENIED_PARTS = (
    "terminal", "shell", "bash", "exec", "code", "delegat", "subagent", "process", "computer", "browser",
    "cron", "kanban", "yolo", "sudo", "ssh", "mcp", "patch", "write", "edit", "send_message", "messaging",
    "homeassistant", "docker", "git", "approve", "unsafe",
)

DEFAULT_LIMITS: dict[str, dict[str, int]] = {
    "decision.answer": {"perHour": 20},
    "card.create": {"perHour": 20},
    "card.edit": {"perHour": 60},
    "agent.chat": {"perMinute": 10},
    "agent.ask": {"perMinute": 10},
    "card.dispatch": {"perHour": 6, "perTargetCount": 1, "perTargetSeconds": 600},
    "service.restart": {"perHour": 6, "perTargetCount": 1, "perTargetSeconds": 600},
    "cron.run": {"perHour": 12, "perTargetCount": 1, "perTargetSeconds": 300},
}
# capability -> extra keys the policy may set (besides "enabled" and "limits")
CAP_KEYS: dict[str, tuple[str, ...]] = {
    "decision.answer": ("boards", "permissionApprove", "handoffAnswerable"),
    "card.create": ("boards",),
    "card.edit": ("boards",),
    "card.dispatch": ("boards", "profiles"),
    "agent.chat": ("agents", "toolsets", "timeoutSeconds"),
    "agent.ask": ("agents", "toolsets", "timeoutSeconds"),
    "service.restart": (),
    "cron.run": (),
}
TOP_KEYS = ("schema", "actors", "lockSetsHermesLock", "hermesProgram", "capabilities", "services", "jobs")
MAX_LIST = 64


class PolicyError(Exception):
    """The policy is not acceptable. The message names the problem, never a secret."""


def toolset_denied(name: str) -> bool:
    n = name.strip().lower()
    return n in DENIED_EXACT or any(part in n for part in DENIED_PARTS)


@dataclass(frozen=True)
class CapPolicy:
    name: str
    enabled: bool = False
    boards: tuple[str, ...] = ()
    profiles: tuple[str, ...] = ()
    agents: tuple[str, ...] = ()
    toolsets: tuple[str, ...] = ()
    limits: dict[str, int] = field(default_factory=dict)
    permission_approve: bool = False
    handoff_answerable: bool = False
    timeout_s: int = 120


@dataclass(frozen=True)
class ServicePolicy:
    name: str
    argv: tuple[str, ...]
    timeout_s: int = 60


@dataclass(frozen=True)
class Policy:
    file_hash: str
    actors: frozenset[str]
    actor_list: tuple[str, ...]
    lock_sets_hermes_lock: bool
    hermes_program: tuple[str, ...]
    caps: dict[str, CapPolicy]
    services: dict[str, ServicePolicy]
    jobs: tuple[str, ...]

    def cap(self, name: str) -> CapPolicy:
        return self.caps[name]

    def union(self, attr: str) -> list[str]:
        seen: list[str] = []
        for c in self.caps.values():
            if not c.enabled:
                continue
            for x in getattr(c, attr):
                if x not in seen:
                    seen.append(x)
        return seen[:MAX_LIST]

    def wire(self, revision: str, dry_run: bool) -> dict[str, Any]:
        """Body of ``action.policy`` (display data only; every request is re-checked)."""
        caps: dict[str, Any] = {}
        for name in frames.CAPABILITIES:
            tier, confirm, _ = frames.ARGS[name]
            c = self.caps[name]
            caps[name] = {"tier": tier, "confirm": confirm, "enabled": c.enabled, "limits": dict(c.limits)}
        return {
            "revision": revision,
            "dryRun": dry_run,
            "actors": list(self.actor_list),
            "capabilities": caps,
            "services": sorted(self.services)[:MAX_LIST] if self.caps["service.restart"].enabled else [],
            "jobs": list(self.jobs)[:MAX_LIST] if self.caps["cron.run"].enabled else [],
            "boards": self.union("boards"),
            "profiles": self.union("profiles"),
            "agents": self.union("agents"),
        }


def _names(v: Any, what: str, allow_empty: bool = True) -> tuple[str, ...]:
    if type(v) is not list or len(v) > MAX_LIST:
        raise PolicyError(f"{what}: need a list of at most {MAX_LIST} names")
    out: list[str] = []
    for x in v:
        if type(x) is not str or not NAME.match(x):
            raise PolicyError(f"{what}: bad name {str(x)[:30]!r} (letters, digits . _ : -, no leading '-', <= 64)")
        if x in out:
            raise PolicyError(f"{what}: duplicate {x!r}")
        out.append(x)
    if not out and not allow_empty:
        raise PolicyError(f"{what}: must not be empty")
    return tuple(out)


def _only(d: Any, allowed: tuple[str, ...], where: str) -> dict[str, Any]:
    if type(d) is not dict:
        raise PolicyError(f"{where}: must be an object")
    for k in d:
        if k not in allowed:
            raise PolicyError(f"{where}: unknown key {str(k)[:40]!r}")
    return d


def _bool(d: dict[str, Any], key: str, default: bool, where: str) -> bool:
    v = d.get(key, default)
    if type(v) is not bool:
        raise PolicyError(f"{where}.{key}: must be true or false")
    return v


def _limits(raw: Any, cap: str) -> dict[str, int]:
    defaults = DEFAULT_LIMITS[cap]
    out = dict(defaults)
    if raw is None:
        return out
    d = _only(raw, tuple(defaults), f"{cap}.limits")
    for k, v in d.items():
        if type(v) is not int or v < 1:
            raise PolicyError(f"{cap}.limits.{k}: must be a positive integer")
        if k == "perTargetSeconds":
            if v < defaults[k]:
                raise PolicyError(f"{cap}.limits.{k}: a policy may only tighten limits (>= {defaults[k]})")
        elif v > defaults[k]:
            raise PolicyError(f"{cap}.limits.{k}: a policy may only tighten limits (<= {defaults[k]})")
        out[k] = v
    return out


def parse(data: Any, file_hash: str = "") -> Policy:
    top = _only(data, TOP_KEYS, "policy")
    if top.get("schema") != SCHEMA or type(top.get("schema")) is not int:
        raise PolicyError(f"policy.schema: must be {SCHEMA}")
    actors_raw = top.get("actors")
    if type(actors_raw) is not list or len(actors_raw) > MAX_LIST:
        raise PolicyError("policy.actors: need a list of UUID strings")
    actors: list[str] = []
    for a in actors_raw:
        if type(a) is not str or not frames.UUID_RE.match(a):
            raise PolicyError("policy.actors: each actor must be a lowercase canonical UUID string")
        if a[14] != "4" or a[19] not in "89ab":
            raise PolicyError("policy.actors: actors must be version 4 UUIDs (online-mode accounts), never an offline name hash")
        if a in actors:
            raise PolicyError("policy.actors: duplicate actor")
        actors.append(a)
    lock_sets = _bool(top, "lockSetsHermesLock", True, "policy")
    prog_raw = top.get("hermesProgram", ["hermes"])
    if type(prog_raw) is not list or not 1 <= len(prog_raw) <= 8 or not all(type(x) is str and x and "\x00" not in x and len(x) <= 512 for x in prog_raw):
        raise PolicyError("policy.hermesProgram: need a non-empty list of strings")
    if prog_raw[0].startswith("-"):
        raise PolicyError("policy.hermesProgram: the program must not start with '-'")
    caps_raw = top.get("capabilities")
    if type(caps_raw) is not dict:
        raise PolicyError("policy.capabilities: must be an object")
    for name in caps_raw:
        if name not in frames.CAPABILITIES:
            raise PolicyError(f"policy.capabilities: unknown capability {str(name)[:40]!r}")
    services_raw = top.get("services", {})
    if type(services_raw) is not dict or len(services_raw) > MAX_LIST:
        raise PolicyError("policy.services: must be an object")
    services: dict[str, ServicePolicy] = {}
    for sname, sv in services_raw.items():
        if not NAME.match(sname):
            raise PolicyError(f"policy.services: bad service name {sname[:30]!r}")
        sd = _only(sv, ("argv", "timeoutSeconds"), f"services.{sname}")
        argv = sd.get("argv")
        if type(argv) is not list or not 1 <= len(argv) <= 32 or not all(type(x) is str and x and len(x) <= 512 and not any(ord(c) < 32 for c in x) for x in argv):
            raise PolicyError(f"services.{sname}.argv: need a non-empty list of strings")
        if argv[0].startswith("-"):
            raise PolicyError(f"services.{sname}.argv: the program must not start with '-'")
        t = sd.get("timeoutSeconds", 60)
        if type(t) is not int or not 1 <= t <= 600:
            raise PolicyError(f"services.{sname}.timeoutSeconds: 1..600")
        services[sname] = ServicePolicy(sname, tuple(argv), t)
    jobs = _names(top.get("jobs", []), "policy.jobs")

    caps: dict[str, CapPolicy] = {}
    for name in frames.CAPABILITIES:
        raw = caps_raw.get(name)
        if raw is None:
            caps[name] = CapPolicy(name=name, limits=dict(DEFAULT_LIMITS[name]))
            continue
        d = _only(raw, ("enabled", "limits") + CAP_KEYS[name], f"capabilities.{name}")
        if "enabled" not in d or type(d["enabled"]) is not bool:
            raise PolicyError(f"capabilities.{name}.enabled: required, true or false")
        boards = _names(d.get("boards", []), f"{name}.boards") if "boards" in CAP_KEYS[name] else ()
        profiles = _names(d.get("profiles", []), f"{name}.profiles") if "profiles" in CAP_KEYS[name] else ()
        agents = _names(d.get("agents", []), f"{name}.agents") if "agents" in CAP_KEYS[name] else ()
        toolsets: tuple[str, ...] = ()
        if "toolsets" in CAP_KEYS[name]:
            ts = d.get("toolsets", [])
            if type(ts) is not list or len(ts) > 8 or not all(type(x) is str and TOOLSET.match(x) for x in ts):
                raise PolicyError(f"{name}.toolsets: need a list of toolset names")
            for t in ts:
                if toolset_denied(t):
                    raise PolicyError(f"{name}.toolsets: {t!r} is a denied toolset (shell / terminal / code / delegation class)")
            toolsets = tuple(ts)
        timeout = d.get("timeoutSeconds", 120)
        if type(timeout) is not int or not 5 <= timeout <= 300:
            raise PolicyError(f"{name}.timeoutSeconds: 5..300")
        perm = d.get("permissionApprove", False)
        if type(perm) is not bool:
            raise PolicyError(f"{name}.permissionApprove: must be false")
        if perm:
            raise PolicyError("decision.answer.permissionApprove=true is not supported: permission halts can only be denied from the game")
        hand = _bool(d, "handoffAnswerable", False, name) if "handoffAnswerable" in CAP_KEYS[name] else False
        cp = CapPolicy(name=name, enabled=d["enabled"], boards=boards, profiles=profiles, agents=agents, toolsets=toolsets,
                       limits=_limits(d.get("limits"), name), permission_approve=False, handoff_answerable=hand, timeout_s=timeout)
        if cp.enabled:
            _require_enabled(cp, services, jobs)
        caps[name] = cp
    if caps["decision.answer"].enabled and not actors:
        raise PolicyError("a capability is enabled but policy.actors is empty")
    if any(c.enabled for c in caps.values()) and not actors:
        raise PolicyError("a capability is enabled but policy.actors is empty")
    return Policy(file_hash=file_hash, actors=frozenset(actors), actor_list=tuple(actors), lock_sets_hermes_lock=lock_sets,
                  hermes_program=tuple(prog_raw), caps=caps, services=services, jobs=jobs)


def _require_enabled(c: CapPolicy, services: dict[str, ServicePolicy], jobs: tuple[str, ...]) -> None:
    need = {
        "decision.answer": c.boards, "card.create": c.boards, "card.edit": c.boards,
        "card.dispatch": c.boards and c.profiles,
        "agent.chat": c.agents and c.toolsets, "agent.ask": c.agents and c.toolsets,
        "service.restart": tuple(services), "cron.run": jobs,
    }[c.name]
    if not need:
        raise PolicyError(f"capabilities.{c.name}: enabled but its allowlist is empty (boards / profiles / agents / toolsets / services / jobs)")


def load(path: Path | str, check_perms: bool = True) -> Policy:
    p = Path(path)
    if check_perms:
        try:
            safety.check_file(p, "policy file")
        except safety.UnsafePath as e:
            raise PolicyError(str(e)) from None
    try:
        raw = p.read_bytes()
    except OSError as e:
        raise PolicyError(f"policy file unreadable ({type(e).__name__})") from None
    if len(raw) > MAX_POLICY_BYTES:
        raise PolicyError("policy file too big")
    try:
        data = frames.strict_loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, frames.FrameError):
        raise PolicyError("policy file is not valid JSON (duplicate keys are refused too)") from None
    return parse(data, hashlib.sha256(raw).hexdigest())
