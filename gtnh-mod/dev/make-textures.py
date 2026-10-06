"""Generate the 16x16 block textures for gtnh-mod (pure stdlib PNG writer, deterministic)."""

import struct
import sys
import zlib
from pathlib import Path


def png(path: Path, px):
    h = len(px)
    w = len(px[0])
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *p) for p in row) for row in px)

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(data)


def grid(fn):
    return [[fn(x, y) for x in range(16)] for y in range(16)]


def edge(x, y):
    return x in (0, 15) or y in (0, 15)


def monitor_front(x, y):
    if edge(x, y):
        return (58, 60, 64, 255)
    if x in (1, 14) or y in (1, 14):
        return (34, 36, 40, 255)
    # dark screen with a faint scanline
    return (16, 20, 24, 255) if y % 3 else (20, 26, 30, 255)


def monitor_side(x, y):
    if edge(x, y):
        return (48, 50, 54, 255)
    v = 70 + ((x * 7 + y * 3) % 5) * 2
    return (v, v + 2, v + 6, 255)


def lamp(x, y):
    if edge(x, y):
        return (120, 104, 84, 255)
    if x in (1, 14) or y in (1, 14):
        return (90, 78, 62, 255)
    d = abs(x - 7.5) + abs(y - 7.5)
    v = int(235 - d * 6)
    return (v, v - 6, v - 18, 255)


def beacon_side(x, y):
    if edge(x, y):
        return (40, 44, 52, 255)
    if 5 <= x <= 10 and 3 <= y <= 12:
        return (200, 230, 235, 255) if (x + y) % 2 else (170, 210, 220, 255)
    return (74, 80, 92, 255)


def beacon_top(x, y):
    if edge(x, y):
        return (40, 44, 52, 255)
    d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
    if d < 4:
        return (230, 245, 250, 255)
    if d < 6:
        return (150, 200, 215, 255)
    return (74, 80, 92, 255)


def main(out: Path):
    out.mkdir(parents=True, exist_ok=True)
    for name, fn in [("monitor_front", monitor_front), ("monitor_side", monitor_side), ("lamp", lamp),
                     ("beacon_side", beacon_side), ("beacon_top", beacon_top)]:
        png(out / f"{name}.png", grid(fn))
        print("wrote", out / f"{name}.png")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
