"""Minimal RFC 6455 WebSocket server on asyncio streams (stdlib only).

Scope is exactly what the AgentCraft protocol needs: text frames (fragmented or not), ping/pong,
close; server frames are never masked, client frames must be. Upgrade requests are vetted by a
caller-supplied ``check(request) -> str | None`` before the handshake completes (the browser-Origin
/ Host / peer allowlist lives in server.py).
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import struct
from dataclasses import dataclass, field
from typing import Awaitable, Callable

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_HEADER_BYTES = 8192


class WSClosed(Exception):
    pass


@dataclass
class UpgradeRequest:
    method: str
    path: str
    headers: dict[str, str]  # lowercased names
    peer: str


@dataclass(eq=False)
class WSConnection:
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter
    request: UpgradeRequest
    max_message: int = 1 << 20
    closed: bool = False
    _lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def send_text(self, text: str) -> None:
        await self._send_frame(0x1, text.encode("utf-8"))

    async def ping(self, payload: bytes = b"") -> None:
        await self._send_frame(0x9, payload)

    async def close(self, code: int = 1000, reason: str = "") -> None:
        if self.closed:
            return
        try:
            await self._send_frame(0x8, struct.pack("!H", code) + reason.encode()[:120])
        except (ConnectionError, RuntimeError):
            pass
        self.closed = True
        try:
            self.writer.close()
        except RuntimeError:
            pass

    async def _send_frame(self, opcode: int, payload: bytes) -> None:
        if self.closed:
            raise WSClosed()
        n = len(payload)
        head = bytearray([0x80 | opcode])
        if n < 126:
            head.append(n)
        elif n < 1 << 16:
            head.append(126)
            head += struct.pack("!H", n)
        else:
            head.append(127)
            head += struct.pack("!Q", n)
        async with self._lock:
            self.writer.write(bytes(head) + payload)
            await self.writer.drain()

    async def recv(self) -> str:
        """Next complete text message. Handles ping/pong/close internally."""
        buf = bytearray()
        msg_opcode: int | None = None
        while True:
            b1, b2 = await self.reader.readexactly(2)
            fin = bool(b1 & 0x80)
            opcode = b1 & 0x0F
            masked = bool(b2 & 0x80)
            n = b2 & 0x7F
            if n == 126:
                (n,) = struct.unpack("!H", await self.reader.readexactly(2))
            elif n == 127:
                (n,) = struct.unpack("!Q", await self.reader.readexactly(8))
            if not masked:
                await self.close(1002, "client frames must be masked")
                raise WSClosed()
            if n > self.max_message or len(buf) + n > self.max_message:
                await self.close(1009, "message too big")
                raise WSClosed()
            mask = await self.reader.readexactly(4)
            data = bytearray(await self.reader.readexactly(n))
            for i in range(n):
                data[i] ^= mask[i % 4]
            if opcode == 0x8:
                await self.close()
                raise WSClosed()
            if opcode == 0x9:
                await self._send_frame(0xA, bytes(data[:125]))
                continue
            if opcode == 0xA:
                continue
            if opcode in (0x1, 0x2):
                msg_opcode = opcode
                buf = data
            elif opcode == 0x0:
                if msg_opcode is None:
                    await self.close(1002, "unexpected continuation")
                    raise WSClosed()
                buf += data
            else:
                await self.close(1002, "bad opcode")
                raise WSClosed()
            if fin:
                if msg_opcode == 0x2:
                    return "\x00binary"
                try:
                    return bytes(buf).decode("utf-8")
                except UnicodeDecodeError:
                    await self.close(1007, "invalid utf-8")
                    raise WSClosed()


Handler = Callable[[WSConnection], Awaitable[None]]
Checker = Callable[[UpgradeRequest], "str | None"]


async def _http_error(writer: asyncio.StreamWriter, status: str, body: str) -> None:
    data = body.encode()
    writer.write(
        f"HTTP/1.1 {status}\r\nContent-Type: text/plain\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n".encode()
        + data
    )
    try:
        await writer.drain()
    finally:
        writer.close()


async def _read_request(reader: asyncio.StreamReader, peer: str) -> UpgradeRequest | None:
    raw = await reader.readuntil(b"\r\n\r\n")
    if len(raw) > MAX_HEADER_BYTES:
        return None
    lines = raw.decode("latin-1").split("\r\n")
    parts = lines[0].split(" ")
    if len(parts) < 3:
        return None
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return UpgradeRequest(method=parts[0], path=parts[1], headers=headers, peer=peer)


def make_accept(key: str) -> str:
    return base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()


async def serve(
    host: str,
    port: int,
    handler: Handler,
    check: Checker,
    on_reject: Callable[[UpgradeRequest | None, str], None] | None = None,
    max_message: int = 1 << 20,
) -> asyncio.base_events.Server:
    async def on_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peername = writer.get_extra_info("peername")
        peer = peername[0] if isinstance(peername, tuple) else str(peername)
        try:
            req = await asyncio.wait_for(_read_request(reader, peer), timeout=10)
        except (asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError, ConnectionError):
            writer.close()
            return
        if req is None:
            await _http_error(writer, "400 Bad Request", "bad request")
            return
        why = check(req)
        if why:
            if on_reject:
                on_reject(req, why)
            await _http_error(writer, "401 Unauthorized", "refused")
            return
        key = req.headers.get("sec-websocket-key")
        if (
            req.method != "GET"
            or "websocket" not in req.headers.get("upgrade", "").lower()
            or "upgrade" not in req.headers.get("connection", "").lower()
            or req.headers.get("sec-websocket-version") != "13"
            or not key
        ):
            await _http_error(writer, "426 Upgrade Required", "websocket only")
            return
        writer.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Accept: {make_accept(key)}\r\n\r\n"
            ).encode()
        )
        await writer.drain()
        conn = WSConnection(reader, writer, req, max_message=max_message)
        try:
            await handler(conn)
        finally:
            if not conn.closed:
                await conn.close(1001, "bye")

    return await asyncio.start_server(on_client, host, port, limit=MAX_HEADER_BYTES * 2)
