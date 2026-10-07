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
   URLs that carry credentials lose them too: ``user:pass@`` and long ``token@`` user parts,
   credential query parameters (``?token=``, ``&key=``, ``&sig=``, ``X-Amz-Signature=`` ...),
   Discord/Slack webhook paths and opaque mixed-case path segments.
3. Absolute paths are shortened to their last component (``.../file.py``) and paths to
   credential-looking files (``.env``, ``*.pem``, ``id_rsa``, ``auth.json`` ...) become ``[path]``.
4. IPv4 and IPv6 addresses become ``[ip]`` (ports and ``/prefix`` lengths are kept). On by default;
   :func:`set_redact_ips` (adapter flag ``--allow-ip-text``) turns only this rule off.
5. E-mail addresses become ``[email]``. Control characters are dropped, whitespace is collapsed
   unless ``keep_newlines`` is set, and the result is truncated to ``limit`` characters.
"""

from __future__ import annotations

import ipaddress
import re
from contextlib import contextmanager
from typing import Iterator

WITHHELD = "[withheld: mentions personal notes]"
REDACTED = "[redacted]"
IP = "[ip]"

# Process-wide privacy setting: redact IP addresses in every string sent to a client. The adapter
# sets it once at startup (--allow-ip-text); tests use the ip_policy() context manager.
_REDACT_IPS = True


def set_redact_ips(on: bool) -> None:
    """Turn IP-address redaction on (default) or off for every later :func:`clean` call."""
    global _REDACT_IPS
    _REDACT_IPS = bool(on)


def redact_ips_enabled() -> bool:
    return _REDACT_IPS


@contextmanager
def ip_policy(on: bool) -> Iterator[None]:
    """Temporarily set IP redaction (tests)."""
    before = _REDACT_IPS
    set_redact_ips(on)
    try:
        yield
    finally:
        set_redact_ips(before)

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
    # credentials embedded in URLs: user:pass@ and a long bare token used as the user part
    re.compile(r"(?P<scheme>\b[a-z][a-z0-9+.\-]*://)[^/\s:@]+:[^/\s@]+@", re.I),
    re.compile(r"(?P<scheme>\b[a-z][a-z0-9+.\-]*://)[^/\s:@]{16,}@", re.I),
    # credential query parameters (?token=, &key=, &sig=, X-Amz-Signature=, ?code=, ...)
    re.compile(
        r"(?P<k>[?&;](?:[\w.\-]*(?:token|secret|signature|passw(?:or)?d|credential|api[_\-]?key|access[_\-]?key)"
        r"[\w.\-]*|code|sig|sid|key|auth|jwt|ticket|session)=)(?P<v>[^&#\s]+)",
        re.I,
    ),
    # webhook URLs are bearer capabilities: keep the host, drop the id/token path
    re.compile(r"(?P<k>\bdiscord(?:app)?\.com/api(?:/v\d+)?/webhooks/)(?P<v>[^\s?#]+)", re.I),
    re.compile(r"(?P<k>\bhooks\.slack\.com/(?:services|workflows|triggers)/)(?P<v>[^\s?#]+)", re.I),
    # long opaque hex blobs (>= 32)
    re.compile(r"\b[0-9a-fA-F]{32,}\b"),
]
# URLs, for the opaque-path-segment rule (tokens in a path such as /bot123:AbC.../ or /t/<key>)
_URL = re.compile(r"\b[a-z][a-z0-9+.\-]*://[^\s<>\"'`]+", re.I)
_URL_SEGMENT = re.compile(r"[A-Za-z0-9_\-:]{24,}")


def _url_repl(m: re.Match[str]) -> str:
    def seg(s: re.Match[str]) -> str:
        v = s.group(0)
        if any(c.isupper() for c in v) and any(c.islower() for c in v) and any(c.isdigit() for c in v):
            return REDACTED
        return v

    url = m.group(0)
    head, sep, rest = url.partition("://")
    host, slash, path = rest.partition("/")
    return head + sep + host + slash + _URL_SEGMENT.sub(seg, path) if slash else url


# IP addresses. Candidates are validated with the ipaddress module, so version strings with leading
# zeros or octets > 255 (5.09.54.133, 10.13.4.1614), times (12:30:45) and MAC addresses survive.
_IPV4 = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?!\w|\.\d)")
_IPV6_BRACKET = re.compile(r"\[([0-9A-Fa-f:.]+)(?:%[\w.\-]+)?\]")
_IPV6_BARE = re.compile(
    r"(?<![\w:.])((?:[0-9A-Fa-f]{0,4}:){2,7}(?:[0-9A-Fa-f]{1,4}|\d{1,3}(?:\.\d{1,3}){3})?)(?:%[\w.\-]+)?(?![\w:.])"
)


def _ipv6_repl(m: re.Match[str]) -> str:
    cand = m.group(1)
    # "::" or "a::" in prose (C++ scopes, Rust paths) is not an address; real ones carry a digit
    if not any(c.isdigit() for c in cand):
        return m.group(0)
    try:
        ipaddress.IPv6Address(cand)
    except ValueError:
        return m.group(0)
    return IP


def _ipv4_repl(m: re.Match[str]) -> str:
    try:
        ipaddress.IPv4Address(m.group(0))
    except ValueError:
        return m.group(0)
    return IP


def redact_ips(text: str) -> str:
    """Replace every valid IPv4/IPv6 address in ``text`` with ``[ip]`` (ports and /len kept)."""
    text = _IPV6_BRACKET.sub(_ipv6_repl, text)
    text = _IPV6_BARE.sub(_ipv6_repl, text)
    return _IPV4.sub(_ipv4_repl, text)
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
        # bounded like any other result (a 24-char field must not get the 35-char marker)
        return WITHHELD if not limit or len(WITHHELD) <= limit else WITHHELD[: max(1, limit - 1)].rstrip() + "\u2026"
    s = _CTRL.sub("", s)
    for pat in _SECRET_PATTERNS:
        s = _sub_secret(pat, s)
    s = _URL.sub(_url_repl, s)
    s = _BLOB.sub(_blob_repl, s)
    s = _PATH.sub(_shorten_path, s)
    if _REDACT_IPS:
        s = redact_ips(s)
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
