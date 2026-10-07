"""Factory telemetry source: polls the read-only ``aifactory`` HTTP endpoint (``gtnh-factory/``).

The source only ever sends ``GET /health`` and ``GET /telemetry/capture`` with a bearer token; the
telemetry mod has no route that changes anything, and this module has no code that would ask.

Token and URL come from configuration at runtime and are never committed:

* URL: ``config["url"]`` or ``$AGENTCRAFT_FACTORY_URL`` (default ``http://127.0.0.1:25580``).
  Loopback only unless ``allow_remote`` is set: the endpoint is plain HTTP, so a remote URL sends
  the token in clear text (prefer an SSH tunnel to the game host's loopback port).
* token: ``config["token_file"]`` or ``$AGENTCRAFT_FACTORY_TOKEN_FILE`` (first non-comment line,
  the same rule the mod uses; the file must not be readable by group or others), or
  ``$AGENTCRAFT_FACTORY_TOKEN``. Deliberately no command-line value: argv is visible to every
  user on the host.

Hardening: no redirects are followed (the Authorization header can never be replayed to another
host), no proxy is used, responses are capped before parsing, the token is checked for header
injection, and errors carry short fixed reasons (status codes), never response bodies.
"""

from __future__ import annotations

import http.client
import ipaddress
import json
import logging
import os
import stat
import time
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlsplit

from .plugin import SourcePlugin

log = logging.getLogger("hermes_adapter.world.factory")

DEFAULT_URL = "http://127.0.0.1:25580"
MIN_TOKEN_LENGTH = 16  # same floor as the mod's TokenAuthenticator
MAX_TOKEN_LENGTH = 512
MAX_RESPONSE_BYTES = 4 * 1024 * 1024 + 64 * 1024  # the mod refuses bodies over 4 MiB
MIN_INTERVAL = 10.0
ENV_URL = "AGENTCRAFT_FACTORY_URL"
ENV_TOKEN_FILE = "AGENTCRAFT_FACTORY_TOKEN_FILE"
ENV_TOKEN = "AGENTCRAFT_FACTORY_TOKEN"
SUPPORTED_PROTOCOLS = ("ai-factory/v2",)


class TelemetryError(Exception):
    """A poll failed. ``detail`` is a short fixed reason that is safe to show and log."""

    def __init__(self, detail: str, status: int | None = None) -> None:
        super().__init__(detail)
        self.detail = detail
        self.status = status


class ConfigError(ValueError):
    """The source cannot start (bad URL, missing or unsafe token). Message is safe to log."""


def read_token_file(path: Path) -> str:
    """First non-empty, non-comment line; refuses group/other-readable files and short secrets."""
    try:
        st = path.stat()
    except OSError:
        raise ConfigError("factory token file not found or not readable") from None
    if not stat.S_ISREG(st.st_mode):
        raise ConfigError("factory token file is not a regular file")
    if os.name == "posix" and st.st_mode & 0o077:
        raise ConfigError("factory token file must not be readable by group or others (chmod 600)")
    if st.st_size > 64 * 1024:
        raise ConfigError("factory token file is too large")
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        raise ConfigError("factory token file not found or not readable") from None
    for line in lines:
        s = line.strip()
        if s and not s.startswith("#"):
            return check_token(s)
    raise ConfigError("factory token file has no token line")


def check_token(token: str) -> str:
    t = token.strip()
    if len(t) < MIN_TOKEN_LENGTH:
        raise ConfigError(f"factory token is shorter than {MIN_TOKEN_LENGTH} characters")
    if len(t) > MAX_TOKEN_LENGTH:
        raise ConfigError("factory token is too long")
    if any(ord(c) < 0x21 or ord(c) > 0x7E for c in t):
        raise ConfigError("factory token must be printable ASCII without spaces")
    return t


def check_url(url: str, allow_remote: bool = False) -> tuple[str, str, int, str, bool]:
    """-> (scheme, host, port, base path, is_loopback). Refuses anything but a plain base URL."""
    try:
        parts = urlsplit(url.strip())
        port = parts.port
    except ValueError:
        raise ConfigError("factory URL is not a valid URL") from None
    if parts.scheme not in ("http", "https"):
        raise ConfigError("factory URL must be http:// or https://")
    if parts.username is not None or parts.password is not None or "@" in parts.netloc:
        raise ConfigError("factory URL must not contain credentials; use the token file")
    if parts.query or parts.fragment:
        raise ConfigError("factory URL must not have a query or fragment")
    host = parts.hostname or ""
    if not host:
        raise ConfigError("factory URL has no host")
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = host.lower() == "localhost"
    if not loopback and not allow_remote:
        raise ConfigError("factory URL is not loopback; pass allow_remote (the token would cross the network)")
    default_port = 443 if parts.scheme == "https" else 80
    return parts.scheme, host, port or default_port, parts.path.rstrip("/"), loopback


_SAFE_503 = {"no capture yet": "no capture yet", "server busy": "busy", "telemetry source unavailable": "telemetry unavailable"}


class FactorySource(SourcePlugin):
    """World source for the ``aifactory`` telemetry mod. ``collect()`` returns
    ``{"health": {...}, "capture": {...}?, "fetchedAt": ms}`` (``capture`` only when it is new)."""

    id = "factory"
    name = "GTNH factory telemetry"

    def __init__(self, url: str = DEFAULT_URL, token: str = "", interval: float = 30.0,
                 request_timeout: float = 10.0, allow_remote: bool = False) -> None:
        self.scheme, self.host, self.port, self.base_path, self.loopback = check_url(url, allow_remote)
        self._token = check_token(token)
        self.interval = max(MIN_INTERVAL, float(interval))
        self.request_timeout = max(1.0, min(30.0, float(request_timeout)))
        self.timeout = 2 * self.request_timeout + 5.0
        self._last_key: tuple[Any, Any] | None = None
        self.requests = 0
        if not self.loopback:
            log.warning("factory telemetry URL is not loopback: the bearer token crosses the network%s",
                        "" if self.scheme == "https" else " in clear text (plain http)")

    def __repr__(self) -> str:  # never show the token
        return f"FactorySource({self.scheme}, {'loopback' if self.loopback else 'remote'}, interval={self.interval:g})"

    # ---- HTTP -------------------------------------------------------------------------------
    def _get(self, path: str) -> dict[str, Any]:
        cls = http.client.HTTPSConnection if self.scheme == "https" else http.client.HTTPConnection
        conn = cls(self.host, self.port, timeout=self.request_timeout)
        self.requests += 1
        deadline = time.monotonic() + self.request_timeout
        try:
            conn.request("GET", self.base_path + path, headers={
                "Authorization": f"Bearer {self._token}", "Accept": "application/json",
                "User-Agent": "agentcraft-hermes-adapter", "Connection": "close",
            })
            resp = conn.getresponse()
            status = resp.status
            declared = resp.getheader("Content-Length") or ""
            if declared.isdigit() and int(declared) > MAX_RESPONSE_BYTES:
                raise TelemetryError("response too large", status)
            body = _read_bounded(resp, deadline)
        except TimeoutError:
            raise TelemetryError("timeout") from None
        except (OSError, http.client.HTTPException):
            raise TelemetryError("unreachable") from None
        finally:
            conn.close()
        if 300 <= status < 400:
            raise TelemetryError("redirect refused", status)
        if status == 401:
            raise TelemetryError("unauthorized", status)
        if status == 503:
            raise TelemetryError(_SAFE_503.get(_error_text(body), "busy"), status)
        if status != 200:
            raise TelemetryError(f"http {status}", status)
        try:
            data = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError, RecursionError):
            raise TelemetryError("bad json", status) from None
        if not isinstance(data, dict):
            raise TelemetryError("bad json", status)
        return data

    def collect(self) -> Mapping[str, Any]:
        health = self._get("/health")
        proto = str(health.get("protocol") or "")
        if proto not in SUPPORTED_PROTOCOLS:
            raise TelemetryError("unsupported telemetry protocol")
        out: dict[str, Any] = {"health": health, "fetchedAt": int(time.time() * 1000)}
        key = (health.get("captureSequence"), health.get("lastCaptureMillis"))
        if health.get("lastCaptureMillis") is None:
            return out
        if key != self._last_key:
            capture = self._get("/telemetry/capture")
            if str(capture.get("protocol") or "") not in SUPPORTED_PROTOCOLS:
                raise TelemetryError("unsupported telemetry protocol")
            out["capture"] = capture
            self._last_key = key
        return out

    def reset(self) -> None:
        """Forget the last capture key so the next collect fetches the capture again."""
        self._last_key = None


def _read_bounded(resp: http.client.HTTPResponse, deadline: float) -> bytes:
    """Read the body in pieces with a size cap and a total deadline, so neither an endless body
    nor a server that drips one byte at a time can hold the poll longer than ~2 request timeouts."""
    chunks: list[bytes] = []
    size = 0
    while True:
        if time.monotonic() > deadline:
            raise TelemetryError("timeout", resp.status)
        chunk = resp.read1(65536)
        if not chunk:
            return b"".join(chunks)
        size += len(chunk)
        if size > MAX_RESPONSE_BYTES:
            raise TelemetryError("response too large", resp.status)
        chunks.append(chunk)


def _error_text(body: bytes) -> str:
    if len(body) > 512:
        return ""
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError, RecursionError):
        return ""
    return str(data.get("error") or "") if isinstance(data, dict) else ""


def resolve_token(config: Mapping[str, Any]) -> str:
    """Token from config (``token`` / ``token_file``) or the environment, in that order."""
    if config.get("token"):
        return check_token(str(config["token"]))
    path = config.get("token_file") or os.environ.get(ENV_TOKEN_FILE)
    if path:
        return read_token_file(Path(str(path)).expanduser())
    env = os.environ.get(ENV_TOKEN)
    if env:
        return check_token(env)
    raise ConfigError(f"no factory token: set --factory-token-file or ${ENV_TOKEN_FILE}")


def create_sources(config: Mapping[str, Any]) -> list[FactorySource]:
    """Plugin factory (same shape as other source plugins)."""
    url = str(config.get("url") or os.environ.get(ENV_URL) or DEFAULT_URL)
    return [FactorySource(
        url=url, token=resolve_token(config),
        interval=float(config.get("interval", 30.0)),
        request_timeout=float(config.get("request_timeout", 10.0)),
        allow_remote=bool(config.get("allow_remote", False)),
    )]
