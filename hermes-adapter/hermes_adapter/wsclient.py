"""Tiny blocking WebSocket client (stdlib only) for tests and the probe tool.

Sends no Origin header, like the mod. Not used by the server itself.
"""

from __future__ import annotations

import base64
import json
import os
import socket
import struct
from typing import Any


class WSError(Exception):
    pass


class WSClient:
    def __init__(self, host: str, port: int, host_header: str | None = None, extra_headers: dict[str, str] | None = None, timeout: float = 5.0) -> None:
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        hdrs = {
            "Host": host_header or f"{host}:{port}",
            "Upgrade": "websocket",
            "Connection": "Upgrade",
            "Sec-WebSocket-Key": key,
            "Sec-WebSocket-Version": "13",
        }
        hdrs.update(extra_headers or {})
        req = "GET / HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in hdrs.items()) + "\r\n"
        self.sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            resp += chunk
        head, _, rest = resp.partition(b"\r\n\r\n")
        self.status_line = head.split(b"\r\n", 1)[0].decode(errors="replace")
        self.buf = rest
        if " 101 " not in self.status_line:
            self.sock.close()
            raise WSError(self.status_line)

    def _read(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise WSError("connection closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send_frame(self, opcode: int, payload: bytes) -> None:
        mask = os.urandom(4)
        n = len(payload)
        head = bytearray([0x80 | opcode])
        if n < 126:
            head.append(0x80 | n)
        elif n < 1 << 16:
            head.append(0x80 | 126)
            head += struct.pack("!H", n)
        else:
            head.append(0x80 | 127)
            head += struct.pack("!Q", n)
        body = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(head) + mask + body)

    def send(self, obj: dict[str, Any]) -> None:
        self.send_frame(0x1, json.dumps({"v": 1, **obj}).encode())

    def recv(self, timeout: float | None = None) -> dict[str, Any]:
        if timeout is not None:
            self.sock.settimeout(timeout)
        while True:
            b1, b2 = self._read(2)
            opcode = b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                (n,) = struct.unpack("!H", self._read(2))
            elif n == 127:
                (n,) = struct.unpack("!Q", self._read(8))
            data = self._read(n)
            if opcode == 0x9:
                self.send_frame(0xA, data)
                continue
            if opcode == 0x8:
                raise WSError("closed by server")
            if opcode == 0x1:
                return json.loads(data.decode())

    def close(self) -> None:
        try:
            self.send_frame(0x8, struct.pack("!H", 1000))
        except OSError:
            pass
        self.sock.close()
