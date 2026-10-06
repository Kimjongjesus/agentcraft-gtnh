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


def taskwall_front(x, y):
    # walnut frame, cork board, five rows of little paper cards in column colours
    if edge(x, y):
        return (59, 42, 32, 255)
    cols = [(156, 148, 136), (47, 163, 160), (201, 162, 39), (143, 169, 139), (217, 119, 87)]
    if 2 <= y <= 13 and x in (2, 3, 5, 6, 8, 9, 11, 12):
        c = cols[(x - 2) // 3 % 5] if y in (2, 3) else (233, 225, 211)
        if y in (5, 8, 11) or y in (2, 3):
            return (*c, 255) if y in (2, 3) else (200, 190, 175, 255)
        return (233, 225, 211, 255)
    return (120, 92, 66, 255) if (x * 5 + y * 3) % 7 else (104, 80, 58, 255)


def atrium_front(x, y):
    if edge(x, y):
        return (59, 42, 32, 255)
    d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
    if 3.6 <= d <= 5.8:
        import math
        a = (math.degrees(math.atan2(x - 7.5, -(y - 7.5))) + 360) % 360
        return (143, 169, 139, 255) if a < 250 else (58, 54, 50, 255)
    return (26, 25, 23, 255)


def library_front(x, y):
    # bookshelf: two shelves of book spines in the palette colours
    if edge(x, y) or y in (7, 8):
        return (77, 55, 39, 255)
    spines = [(217, 119, 87), (47, 163, 160), (201, 162, 39), (143, 169, 139), (91, 141, 239), (180, 85, 58)]
    c = spines[(x + (y // 8) * 3) % len(spines)]
    shade = 0 if x % 2 else 18
    if y in (1, 9):
        return (40, 30, 22, 255)
    return (max(0, c[0] - shade), max(0, c[1] - shade), max(0, c[2] - shade), 255)


def library_side(x, y):
    if edge(x, y):
        return (77, 55, 39, 255)
    v = 99 + ((x * 3 + y * 7) % 4) * 4
    return (v, v - 28, v - 48, 255)


def library_top(x, y):
    v = 122 + ((x + y * 5) % 6) * 3
    return (v, v - 32, v - 56, 255)


def edit_tool(x, y):
    """Card 6 item: a teal T-square with a clay handle, diagonal, on transparency."""
    # handle: lower-left to the middle
    d = x + y
    if 14 <= d <= 16 and 1 <= x <= 8 and abs(x - (15 - y)) <= 1:
        return (217, 119, 87, 255) if (x + y) % 2 else (190, 98, 70, 255)
    # head: a bar across the upper right, perpendicular to the handle
    if 8 <= x <= 14 and 1 <= y <= 7 and abs((x - 11) - (y - 4)) <= 1:
        return (47, 163, 160, 255) if (x * y) % 3 else (36, 130, 128, 255)
    if 7 <= x <= 10 and 5 <= y <= 9 and abs((x - 8) + (y - 7)) <= 0:
        return (244, 239, 230, 255)
    return (0, 0, 0, 0)


def main(out: Path):
    out.mkdir(parents=True, exist_ok=True)
    for name, fn in [("monitor_front", monitor_front), ("monitor_side", monitor_side), ("lamp", lamp),
                     ("beacon_side", beacon_side), ("beacon_top", beacon_top), ("taskwall_front", taskwall_front),
                     ("atrium_front", atrium_front), ("library_front", library_front), ("library_side", library_side),
                     ("library_top", library_top)]:
        png(out / f"{name}.png", grid(fn))
        print("wrote", out / f"{name}.png")
    items = out.parent / "items"
    items.mkdir(parents=True, exist_ok=True)
    png(items / "edit_tool.png", grid(edit_tool))
    print("wrote", items / "edit_tool.png")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
