"""Frames: signing, verification and strict parsing (docs/action-protocol.md sections 2 and 4).

Every function here is pure or reads one file. The receiver pipeline (order of checks, nonce cache,
clock) lives in service.py and ledger.py.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
from pathlib import Path
from typing import Any, Callable

from . import safety

MAX_MESSAGE = 16384
SKEW_MS = 60_000
MAX_INT = 2**53
HEX32 = re.compile(r"^[0-9a-f]{32}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
NAME_RE = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")  # also "does not start with -" is checked where it matters


class FrameError(Exception):
    """A frame or field that must be refused. ``public`` is the generic text sent back."""

    def __init__(self, reason: str, public: str = "bad frame") -> None:
        super().__init__(reason)
        self.reason = reason
        self.public = public


# ---- key ------------------------------------------------------------------------------------


def load_key(path: Path | str) -> bytes:
    """First non-comment line: >= 64 hex characters (>= 32 bytes). Group/other access refuses."""
    p = Path(path)
    try:
        safety.check_file(p, "key file", private=True)
    except safety.UnsafePath as e:
        raise FrameError(str(e)) from None
    try:
        text = p.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as e:
        raise FrameError(f"key file unreadable ({type(e).__name__})") from None
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if len(line) < 64 or len(line) % 2 or not re.fullmatch(r"[0-9a-fA-F]+", line):
            raise FrameError("key file: need one line of >= 64 hex characters")
        key = bytes.fromhex(line)
        if len(set(key)) < 4:
            raise FrameError("key file: key looks degenerate")
        return key
    raise FrameError("key file: no key line")


# ---- signing --------------------------------------------------------------------------------


def sign(key: bytes, payload: str) -> str:
    return hmac.new(key, payload.encode("utf-8"), hashlib.sha256).hexdigest()


def verify(key: bytes, payload: str, sig: str) -> bool:
    return hmac.compare_digest(sign(key, payload), sig)


def encode_frame(key: bytes, type_: str, payload: dict[str, Any]) -> str:
    body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    return json.dumps({"v": 1, "type": type_, "payload": body, "sig": sign(key, body)}, separators=(",", ":"), ensure_ascii=False)


# ---- strict JSON ----------------------------------------------------------------------------


def _no_dups(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for k, v in pairs:
        if k in out:
            raise ValueError("duplicate key")
        out[k] = v
    return out


def _bad_const(_: str) -> Any:
    raise ValueError("non-finite number")


def strict_loads(text: str) -> Any:
    try:
        return json.loads(text, object_pairs_hook=_no_dups, parse_constant=_bad_const)
    except (ValueError, RecursionError) as e:
        raise FrameError(f"json: {type(e).__name__}") from None


def parse_outer(raw: str) -> dict[str, Any]:
    """Section 2: exactly ``v``, ``type``, ``payload``, ``sig`` with the right types."""
    obj = strict_loads(raw)
    if not isinstance(obj, dict) or set(obj) != {"v", "type", "payload", "sig"}:
        raise FrameError("outer: wrong keys")
    if type(obj["v"]) is not int or obj["v"] != 1:
        raise FrameError("outer: v")
    if type(obj["type"]) is not str or not 1 <= len(obj["type"]) <= 32 or not re.fullmatch(r"[A-Za-z0-9._-]+", obj["type"]):
        raise FrameError("outer: type")
    if type(obj["payload"]) is not str:
        raise FrameError("outer: payload")
    if type(obj["sig"]) is not str or not HEX64.match(obj["sig"]):
        raise FrameError("outer: sig")
    try:
        obj["payload"].encode("utf-8")
    except UnicodeEncodeError:
        raise FrameError("outer: payload encoding") from None
    return obj


# ---- field validators -----------------------------------------------------------------------


Validator = Callable[[Any], Any]


def _bad(what: str) -> FrameError:
    return FrameError(f"field {what}")


def S(lo: int = 0, hi: int = 200, *, multiline: bool = False, pattern: re.Pattern[str] | None = None,
      choices: tuple[str, ...] | None = None) -> Validator:
    """String of lo..hi characters (counted in code points); no control characters (except \\n \\t
    when ``multiline``); no lone surrogates."""

    def v(x: Any) -> Any:
        if type(x) is not str or not lo <= len(x) <= hi:
            raise _bad("string")
        try:
            x.encode("utf-8")
        except UnicodeEncodeError:
            raise _bad("string encoding") from None
        for ch in x:
            o = ord(ch)
            if o < 0x20 or 0x7F <= o <= 0x9F:
                if multiline and ch in "\n\t":
                    continue
                raise _bad("control character")
        if pattern is not None and not pattern.match(x):
            raise _bad("string pattern")
        if choices is not None and x not in choices:
            raise _bad("string choice")
        return x

    return v


def I(lo: int = 0, hi: int = MAX_INT) -> Validator:  # noqa: E743 - short on purpose
    def v(x: Any) -> Any:
        if type(x) is not int or not lo <= x <= hi:  # bool is not int here; floats never are
            raise _bad("integer")
        return x

    return v


def B() -> Validator:
    def v(x: Any) -> Any:
        if type(x) is not bool:
            raise _bad("boolean")
        return x

    return v


def L(item: Validator, lo: int = 0, hi: int = 64) -> Validator:
    def v(x: Any) -> Any:
        if type(x) is not list or not lo <= len(x) <= hi:
            raise _bad("array")
        for e in x:
            item(e)
        return x

    return v


def O(spec: dict[str, tuple[Validator, bool]]) -> Validator:
    """Object with exactly the listed keys; (validator, required)."""

    def v(x: Any) -> Any:
        return check_object(x, spec)

    return v


def check_object(x: Any, spec: dict[str, tuple[Validator, bool]]) -> dict[str, Any]:
    if type(x) is not dict:
        raise _bad("object")
    for k in x:
        if k not in spec:
            raise FrameError(f"unknown key {k[:24]!r}")
    for k, (fn, required) in spec.items():
        if k not in x:
            if required:
                raise FrameError(f"missing key {k}")
            continue
        fn(x[k])
    return x


def D(max_keys: int = 16, max_value: int = 600) -> Validator:
    """Free-form small dict of string values (results)."""

    def v(x: Any) -> Any:
        if type(x) is not dict or len(x) > max_keys:
            raise _bad("dict")
        for k, val in x.items():
            S(1, 64)(k)
            S(0, max_value, multiline=True)(val)
        return x

    return v


UUID = S(36, 36, pattern=UUID_RE)
HEX = S(32, 32, pattern=HEX32)
CARDID = S(1, 64, pattern=ID_RE)
Req = True
Opt = False

ACTOR = O({"uuid": (UUID, Req), "name": (S(1, 16), Req)})

COMMON: dict[str, tuple[Validator, bool]] = {
    "type": (S(1, 32), Req),
    "session": (HEX, Req),
    "dir": (S(3, 3, choices=("g2c", "c2g")), Req),
    "id": (S(1, 64, pattern=ID_RE), Req),
    "nonce": (HEX, Req),
    "ts": (I(0, MAX_INT), Req),
}

# per-type fields in addition to COMMON, both directions (so the game-side stub / tests can reuse them)
_STR_LIST = L(S(1, 64), 0, 64)
_FEATURES = L(S(1, 32), 0, 8)
_LIMITS = O({
    "perMinute": (I(1, 100000), Opt), "perHour": (I(1, 100000), Opt),
    "perTargetCount": (I(1, 100000), Opt), "perTargetSeconds": (I(1, 86400), Opt),
})
def _args_obj(x: Any) -> Any:
    if type(x) is not dict:
        raise _bad("args")
    return x


def _caps(x: Any) -> Any:
    if type(x) is not dict or len(x) > 16:
        raise _bad("capabilities")
    for name, c in x.items():
        S(1, 32)(name)
        check_object(c, {"tier": (I(0, 3), Req), "confirm": (B(), Req), "enabled": (B(), Req), "limits": (_LIMITS, Req)})
    return x


TYPES: dict[str, tuple[str, dict[str, tuple[Validator, bool]]]] = {
    # type: (direction, fields)
    "action.challenge": ("c2g", {"challenge": (HEX, Req)}),
    "hello": ("g2c", {"challenge": (HEX, Req), "features": (_FEATURES, Req)}),
    "ack": ("c2g", {"result": (O({"features": (_FEATURES, Req)}), Req)}),
    "action.policy": ("c2g", {
        "revision": (S(1, 64), Req), "dryRun": (B(), Req), "actors": (L(UUID, 0, 64), Req),
        "capabilities": (_caps, Req), "services": (_STR_LIST, Req), "jobs": (_STR_LIST, Req),
        "boards": (_STR_LIST, Req), "profiles": (_STR_LIST, Req), "agents": (_STR_LIST, Req),
    }),
    "action.state": ("c2g", {
        "armed": (B(), Req), "locked": (B(), Req), "lockReason": (S(0, 200), Req),
        "lockedBy": (S(0, 64), Req), "since": (I(0, MAX_INT), Req),
    }),
    "action.request": ("g2c", {
        "actor": (ACTOR, Req), "capability": (S(1, 64), Req), "tier": (I(0, 100), Req),
        "args": (_args_obj, Req),
    }),
    "action.prompt": ("c2g", {
        "re": (S(1, 64, pattern=ID_RE), Req), "token": (HEX, Req), "expiresAt": (I(), Req),
        "summary": (O({
            "card": (S(0, 64), Req), "title": (S(0, 200), Req), "board": (S(0, 64), Req),
            "profile": (S(0, 64), Req), "model": (S(0, 100), Req), "body": (S(0, 600, multiline=True), Req),
        }), Req),
    }),
    "action.confirm": ("g2c", {"actor": (ACTOR, Req), "token": (HEX, Req)}),
    "action.cancel": ("g2c", {"actor": (ACTOR, Req), "token": (HEX, Req)}),
    "action.lock": ("g2c", {"actor": (ACTOR, Req), "reason": (S(0, 200), Req)}),
    "action.result": ("c2g", {
        "re": (S(1, 64, pattern=ID_RE), Req),
        "status": (S(1, 16, choices=("applied", "refused", "queued", "unknown", "prompted", "cancelled")), Req),
        "error": (S(0, 200), Req), "result": (D(16, 600), Req), "audit": (S(0, 64), Req), "dryRun": (B(), Req),
    }),
    "action.chat": ("c2g", {
        "re": (S(1, 64, pattern=ID_RE), Req), "conversation": (S(0, 64), Req), "agentId": (S(0, 64), Req),
        "text": (S(0, 2000, multiline=True), Req), "final": (B(), Req),
    }),
    "error": ("c2g", {"re": (S(0, 64), Req), "error": (S(0, 200), Req)}),
}


def check_payload(obj: Any, expect_type: str, expect_dir: str | None = None) -> dict[str, Any]:
    """Shape of a parsed payload for ``expect_type``: common fields + the type's fields, nothing else."""
    if expect_type not in TYPES:
        raise FrameError("unknown message type", "unknown type")
    direction, fields = TYPES[expect_type]
    spec = {**COMMON, **fields}
    check_object(obj, spec)
    if obj["type"] != expect_type:
        raise FrameError("inner/outer type mismatch")
    if obj["dir"] != direction:
        raise FrameError("direction")
    if expect_dir is not None and obj["dir"] != expect_dir:
        raise FrameError("direction")
    return obj


# ---- per-capability arguments ---------------------------------------------------------------


def _cap_spec(**kw: tuple[Validator, bool]) -> dict[str, tuple[Validator, bool]]:
    return kw


_TEXT2000 = S(1, 2000, multiline=True)
ARGS: dict[str, tuple[int, bool, dict[str, tuple[Validator, bool]]]] = {
    # capability: (tier, confirm, args spec)
    "decision.answer": (1, False, _cap_spec(card=(S(1, 64, pattern=ID_RE), Req), decision=(S(1, 16, pattern=ID_RE), Req),
                                            choice=(S(1, 120), Opt), text=(_TEXT2000, Opt))),
    "card.create": (1, False, _cap_spec(board=(S(1, 64, pattern=ID_RE), Req), title=(S(1, 120), Req),
                                        body=(S(0, 4000, multiline=True), Opt), priority=(I(0, 100), Opt))),
    "card.edit": (1, False, _cap_spec(card=(S(1, 64, pattern=ID_RE), Req), comment=(S(1, 2000, multiline=True), Opt),
                                      title=(S(1, 120), Opt), body=(S(0, 4000, multiline=True), Opt), priority=(I(0, 100), Opt))),
    "agent.chat": (1, False, _cap_spec(agent=(S(1, 64, pattern=ID_RE), Req), conversation=(S(1, 64, pattern=ID_RE), Req), text=(_TEXT2000, Req))),
    "agent.ask": (1, False, _cap_spec(agent=(S(1, 64, pattern=ID_RE), Req), text=(_TEXT2000, Req))),
    "card.dispatch": (2, True, _cap_spec(card=(S(1, 64, pattern=ID_RE), Req), board=(S(1, 64, pattern=ID_RE), Req),
                                         profile=(S(1, 64, pattern=ID_RE), Req))),
    "service.restart": (2, False, _cap_spec(service=(S(1, 64, pattern=ID_RE), Req))),
    "cron.run": (2, False, _cap_spec(job=(S(1, 64, pattern=ID_RE), Req))),
}
CAPABILITIES = tuple(ARGS)
# the board ``card`` ids in args use the adapter's task-id alphabet (letters, digits, . _ : -)


def check_args(capability: str, args: dict[str, Any]) -> dict[str, Any]:
    """Strict, typed, capped ``args`` for a capability (the cross-field rules included)."""
    tier, _confirm, spec = ARGS[capability]
    check_object(args, spec)
    if capability == "decision.answer" and "choice" not in args and "text" not in args:
        raise FrameError("decision.answer needs choice or text")
    if capability == "card.edit":
        if "comment" in args:
            if set(args) - {"card", "comment"}:
                raise FrameError("card.edit: comment goes alone")
        elif not ({"title", "body", "priority"} & set(args)):
            raise FrameError("card.edit: nothing to change")
    return args


def digest(capability: str, tier: int, args: dict[str, Any]) -> str:
    """SHA-256 of the canonical request (section 4.2)."""
    canon = json.dumps({"capability": capability, "tier": tier, "args": args}, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canon.encode("utf-8")).hexdigest()
