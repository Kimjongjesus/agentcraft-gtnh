"""Secret and personal-data stripping for everything the adapter sends.

Every free-text string that leaves the adapter (titles, comments, block reasons, summaries, log
lines, cron names) goes through :func:`clean`. The rules are deliberately blunt: the viewer is a
Minecraft world, so losing a bit of detail is fine and leaking a token is not.

1. Anything that references Eli's personal notes (``personal-*.md`` or a ``memory/personal`` path)
   is withheld entirely, not just the path: the surrounding text may quote the note. The check
   always runs on the whole source text; :func:`clean_excerpt` cuts a display line out only after
   the whole text passed, so splitting can never separate a quote from its personal-notes hint.
2. Credential shapes (API keys, bearer tokens, JWTs, private keys, ``password=...``,
   ``--password X`` / ``--api-key 'X'`` flags, ``Authorization: <scheme> X``, ``sshpass -p X``,
   "the password is X", URLs with user:pass@, long hex/base64 blobs) become ``[redacted]``.
3. Absolute paths are shortened to their last component (``.../file.py``) and paths to
   credential-looking files (``.env``, ``*.pem``, ``id_rsa``, ``auth.json`` ...) become ``[path]``.
4. E-mail addresses become ``[email]``. Control characters are dropped, whitespace is collapsed
   unless ``keep_newlines`` is set, and the result is truncated to ``limit`` characters.
"""

from __future__ import annotations

import re

WITHHELD = "[withheld: mentions personal notes]"
REDACTED = "[redacted]"

_PERSONAL = re.compile(r"personal-[\w.*-]*\.md|personal-\*|memory/personal|personal_notes", re.I)

# words that make a command-line flag sensitive (--password, --api-key, --gh-token, --client-secret ...)
_SENSITIVE_WORD = (
    r"(?:pass(?:word|wd|phrase)?|secret|token|api[_\-]?key|apikey|auth[_\-]?key|private[_\-]?key|"
    r"access[_\-]?key|session[_\-]?key|cookie|credentials?)"
)

_SECRET_PATTERNS: list[re.Pattern[str]] = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(-----END [A-Z ]*PRIVATE KEY-----|$)", re.S),
    # vendor token shapes
    re.compile(r"\bsk-(?:ant-|proj-|live-|test-)?[A-Za-z0-9_\-]{16,}"),
    re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"\bxox[abposr]-[A-Za-z0-9-]{10,}"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_\-]{30,}"),
    re.compile(r"\bglpat-[A-Za-z0-9_\-]{16,}"),
    re.compile(r"\bhf_[A-Za-z0-9]{20,}"),
    # Discord bot tokens: three dot-separated base64 parts
    re.compile(r"\b[MNO][A-Za-z0-9_\-]{23,27}\.[A-Za-z0-9_\-]{6}\.[A-Za-z0-9_\-]{27,40}\b"),
    # JWTs
    re.compile(r"\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"),
    # Authorization headers
    re.compile(r"\b(?:Bearer|Basic|Bot)\s+[A-Za-z0-9._~+/=\-]{12,}", re.I),
    # key=value / key: value secrets (keep the key name, drop the value)
    re.compile(
        r"(?P<k>\b[\w.\-]*(?:pass(?:word|wd|phrase)?|secret|token|api[_\-]?key|apikey|auth[_\-]?key|"
        r"private[_\-]?key|client[_\-]?secret|access[_\-]?key|session[_\-]?key|cookie|credential)s?"
        r"[\"']?\s*[:=]\s*)(?P<v>\"[^\"]*\"|'[^']*'|[^\s,;]+)",
        re.I,
    ),
    # Authorization header with any scheme ("Authorization: token abc", "authorization=Basic xyz")
    re.compile(
        r"(?P<k>\b(?:proxy-)?authori[sz]ation[\"']?\s*[:=]\s*)(?P<v>(?:[A-Za-z][\w\-]*\s+)?(?:\"[^\"]*\"|'[^']*'|[^\s,;\"']+))",
        re.I,
    ),
    # sensitive command-line flags with a space-separated value: --password X, --api-key 'X',
    # -token "X", --client-secret X ... (the '=' form is handled by the key=value rule above)
    re.compile(
        rf"(?P<k>(?<![\w\-])--?[\w\-]*{_SENSITIVE_WORD}[\w\-]*\s+)(?P<v>\"[^\"]*\"?|'[^']*'?|[^\s,;]+)",
        re.I,
    ),
    # sshpass -p X
    re.compile(r"(?P<k>\bsshpass\s+-p\s*)(?P<v>\"[^\"]*\"?|'[^']*'?|\S+)", re.I),
    # prose: "password X", "the passphrase is X", "password for root is X" when X looks like a
    # credential (has a digit, capital or symbol), so "password reset flow" stays readable
    re.compile(
        r"(?P<k>\b(?i:pass(?:word|wd|phrase)|secret)s?\s+(?:(?i:is|was|for\s+\S+\s+is)\s+)?)"
        r"(?P<v>\"[^\"]*\"?|'[^']*'?|(?=[^\s,;]*[0-9A-Z_!@#$%^&*+=?~])[^\s,;]{4,})"
    ),
    # credentials embedded in URLs
    re.compile(r"(?P<scheme>\b[a-z][a-z0-9+.\-]*://)[^/\s:@]+:[^/\s@]+@", re.I),
    # long opaque hex blobs (>= 32)
    re.compile(r"\b[0-9a-fA-F]{32,}\b"),
]
# base64-ish blobs >= 32 chars; only redacted when they mix upper, lower and digits, so long
# kebab-case slugs ("agentcraft-gtnh-port-card-1-...") survive
_BLOB = re.compile(r"(?<![\w/.])[A-Za-z0-9+/_\-]{32,}={0,2}(?![\w/])")


def _blob_repl(m: re.Match[str]) -> str:
    s = m.group(0)
    if any(c.isupper() for c in s) and any(c.islower() for c in s) and any(c.isdigit() for c in s):
        return REDACTED
    return s

_SENSITIVE_FILE = re.compile(
    r"(?:^|/)(?:\.env(?:\.[\w-]+)?|[\w.-]*\.(?:pem|key|p12|pfx|kdbx)|id_(?:rsa|ed25519|ecdsa)(?:\.pub)?|"
    r"auth\.json|credentials(?:\.\w+)?|[\w.-]*secret[\w.-]*|[\w.-]*token[\w.-]*|vault[\w.-]*|\.netrc|\.pgpass)$",
    re.I,
)
# absolute paths and ~/ paths (a path segment run starting with / or ~/)
_PATH = re.compile(r"(?<![\w:/.~])(?:~/|/)(?:[\w.@+\-]+/)*[\w.@+\-]*")
_EMAIL = re.compile(r"\b[\w.+\-]+@[\w\-]+(?:\.[\w\-]+)+\b")
_CTRL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_WS = re.compile(r"\s+")


def _shorten_path(m: re.Match[str]) -> str:
    p = m.group(0)
    if p in ("/", "~", "~/"):
        return p
    last = p.rstrip("/").rsplit("/", 1)[-1]
    if not last:
        return "[path]"
    if _SENSITIVE_FILE.search("/" + last):
        return "[path]"
    # a single-segment absolute path like "/tmp" or a command like "/answer" stays readable
    if p.count("/") <= 1 and not p.startswith("~"):
        return p
    return ".../" + last


def _sub_secret(pattern: re.Pattern[str], text: str) -> str:
    def repl(m: re.Match[str]) -> str:
        gd = m.groupdict()
        if "k" in gd and gd.get("k") is not None:
            return gd["k"] + REDACTED
        if "scheme" in gd and gd.get("scheme") is not None:
            return gd["scheme"] + REDACTED + "@"
        return REDACTED

    return pattern.sub(repl, text)


def mentions_personal(text: str) -> bool:
    return bool(_PERSONAL.search(text or ""))


def clean(text: object, limit: int = 200, keep_newlines: bool = False) -> str:
    """Return a safe, bounded version of ``text`` (see module docstring)."""
    if text is None:
        return ""
    s = str(text)
    if mentions_personal(s):
        return WITHHELD
    s = _CTRL.sub("", s)
    for pat in _SECRET_PATTERNS:
        s = _sub_secret(pat, s)
    s = _BLOB.sub(_blob_repl, s)
    s = _PATH.sub(_shorten_path, s)
    s = _EMAIL.sub("[email]", s)
    if keep_newlines:
        s = "\n".join(_WS.sub(" ", line).strip() for line in s.splitlines())
        s = re.sub(r"\n{3,}", "\n\n", s).strip()
    else:
        s = _WS.sub(" ", s).strip()
    if limit and len(s) > limit:
        s = s[: max(1, limit - 1)].rstrip() + "\u2026"
    return s


def clean_excerpt(text: object, limit: int = 200, strip_prefix: re.Pattern[str] | None = None) -> str:
    """First non-empty line of ``text``, made safe from the WHOLE source first.

    Personal-note detection and secret redaction run over the full text before a line is cut out,
    so a hint further down ("Source: personal-....md", the closing half of a multi-line key block)
    can never be lost by excerpting. ``strip_prefix`` (e.g. ``PROGRESS:``) is removed afterwards.
    """
    full = clean(text, 0, keep_newlines=True)
    if full == WITHHELD:
        return WITHHELD
    line = next((ln for ln in full.splitlines() if ln.strip()), "")
    if strip_prefix is not None:
        line = strip_prefix.sub("", line)
    return clean(line, limit)


def clean_id(text: object, limit: int = 64) -> str:
    """Identifier-safe: lowercase [a-z0-9._-] only."""
    s = re.sub(r"[^a-z0-9._\-]+", "-", str(text or "").lower()).strip("-")
    return s[:limit] or "unknown"
