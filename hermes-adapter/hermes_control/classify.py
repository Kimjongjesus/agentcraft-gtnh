"""Decision classification from board data (never from request text).

``question``   the game may pick an offered choice, or send text when the decision offers no choices;
``permission`` the game may only answer ``Deny`` (optionally with a note): Approve is refused whatever
               the policy says (open question 1);
``handoff``    review / demo-ready / send-to-review / REVISE style: read-only unless the policy sets
               ``decision.answer.handoffAnswerable`` (open question 10, default false);
``unknown``    read-only.

The classification is deliberately lopsided: any signal of "permission" wins over everything else,
and anything the code does not recognise is ``unknown``, never ``question``.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

from hermes_adapter.mapping import parse_decision_reason
from hermes_adapter.redact import WITHHELD

QUESTION, PERMISSION, HANDOFF, UNKNOWN = "question", "permission", "handoff", "unknown"

_PREFIX = re.compile(r"^\s*(QUESTION|PERMISSION|DEMO[ -]READY|REVISE|HELD|NEEDS[ -]INPUT)\b", re.I)
_HANDOFF_WORDS = re.compile(r"demo[ -]?ready|send[ -]to[ -]review|ready (?:for|to) review|request(?:ing)? review|hand[ -]?off|handing off|\brevise\b|\bheld\b", re.I)
_PERMISSION_WORDS = re.compile(r"permission|\bapprov|\bauthori[sz]|\bgrant\b|\ballow\b|\bsudo\b", re.I)
_APPROVE_LIKE = re.compile(r"approv|allow|grant|authori[sz]|permit")


def norm(text: str) -> str:
    """NFKC, no format / control characters, casefolded, single spaces. For comparison only."""
    t = unicodedata.normalize("NFKC", text or "")
    t = "".join(" " if unicodedata.category(c) in ("Zs", "Zl", "Zp") else c for c in t if unicodedata.category(c) not in ("Cf", "Cc") or c in " \t\n")
    return re.sub(r"\s+", " ", t).strip().casefold()


@dataclass(frozen=True)
class Classification:
    kind: str
    question: str = ""
    options: tuple[str, ...] = ()
    why: str = ""


def classify(reason: str, block_kind: str | None) -> Classification:
    """Classify an open decision from the block event's ``reason`` and ``kind`` (board data)."""
    if block_kind not in (None, "", "needs_input"):
        return Classification(UNKNOWN, why=f"block kind {str(block_kind)[:20]!r} is not a question")
    _, question, options = parse_decision_reason(reason or "")
    if question == WITHHELD:
        return Classification(UNKNOWN, why="decision text is withheld")
    nr = norm(reason or "")
    head = nr[:240]
    m = _PREFIX.match(unicodedata.normalize("NFKC", "".join(c for c in (reason or "") if unicodedata.category(c) not in ("Cf",))))
    prefix = m.group(1).upper().replace("-", " ") if m else ""
    opts = tuple(options)
    approve_option = any(_APPROVE_LIKE.search(norm(o)) for o in opts)
    if prefix == "PERMISSION" or _PERMISSION_WORDS.search(head) or approve_option:
        return Classification(PERMISSION, question, opts, "permission halt: the game may only deny")
    if prefix in ("DEMO READY", "REVISE", "HELD") or _HANDOFF_WORDS.search(head):
        return Classification(HANDOFF, question, opts, "hand-off decision: answer outside the game")
    if prefix in ("QUESTION", "NEEDS INPUT"):
        return Classification(QUESTION, question, opts)
    return Classification(UNKNOWN, question, opts, "unrecognised decision: read-only")


@dataclass
class Answer:
    ok: bool
    error: str = ""
    choice: str = ""  # the offered option to record (never the game's own spelling)
    note: str = ""
    kind: str = ""
    extra: dict[str, str] = field(default_factory=dict)


def resolve_answer(cls: Classification, choice: str | None, text: str | None, handoff_answerable: bool = False) -> Answer:
    """What may the game say to this decision? Pure function of board data + the request."""
    kind = cls.kind
    if kind == UNKNOWN:
        return Answer(False, "this decision is read-only in the game (unrecognised kind)", kind=kind)
    if kind == HANDOFF and not handoff_answerable:
        return Answer(False, "hand-off decisions are read-only in the game: answer outside the game", kind=kind)
    if kind == PERMISSION:
        if choice is None:
            return Answer(False, "permission halts can only be answered with Deny", kind=kind)
        if choice not in cls.options:
            return Answer(False, "that choice is not offered by this decision", kind=kind)
        n = norm(choice)
        if n != "deny":
            return Answer(False, "permission halts can only be denied from the game: approve outside the game", kind=kind)
        return Answer(True, choice=choice, note=text or "", kind=kind)
    # question (or an allowed hand-off)
    if cls.options:
        if choice is None:
            return Answer(False, "this decision needs one of its offered choices", kind=kind)
        if choice not in cls.options:
            return Answer(False, "that choice is not offered by this decision", kind=kind)
        return Answer(True, choice=choice, note=text or "", kind=kind)
    if choice is not None:
        return Answer(False, "this decision offers no choices: send text", kind=kind)
    if not text:
        return Answer(False, "this decision needs text", kind=kind)
    return Answer(True, note=text, kind=kind)
