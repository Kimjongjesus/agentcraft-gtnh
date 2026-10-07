#!/usr/bin/env python3
"""Console command files for the throwaway card-2 test arena on the GTNH DEV COPY only.

A glass platform in the sky above the dev copy's spawn (spawn chunks stay loaded), with the same
desks / library / user / lounge layout as dev/qa-arena.txt shifted by (+40, +157, +270). It is
NOT the HQ (the player builds that by hand) and never goes near the real game server's world.

    python3 dev/gtnh-sky-arena.py check  > check.txt   # testforblock air over the whole volume
    python3 dev/gtnh-sky-arena.py build  > arena.txt   # platform, blocks, bindings, anchors
    python3 dev/gtnh-sky-arena.py clear  > clear.txt   # puts every arena block back to air

Feed a file on the test PC with: grep -v '^#' FILE > "$GTNH_DEV_SERVER"/console.fifo
"""
import sys

X0, X1 = -26, 4      # platform x range (inclusive)
Z0, Z1 = 30, 51      # platform z range (inclusive)
FLOOR = 160          # glass floor; agents stand at y=161
FEET = FLOOR + 1

BLOCKS = [  # (x, y, z, block, meta) on top of the platform
    (-16, FEET, 33, "agentcraftgtnh:monitor", 3),
    (-11, FEET, 33, "agentcraftgtnh:monitor", 3),
    (-6, FEET, 33, "agentcraftgtnh:monitor", 3),
    (-14, FEET, 33, "agentcraftgtnh:status_lamp", 0),
    (-9, FEET, 33, "agentcraftgtnh:status_lamp", 0),
    (-4, FEET, 33, "agentcraftgtnh:status_lamp", 0),
    (2, FEET, 39, "bookshelf", 0),
    (2, FEET, 40, "bookshelf", 0),
    (2, FEET, 41, "bookshelf", 0),
    (2, FEET + 1, 40, "bookshelf", 0),
    (-22, FEET, 44, "agentcraftgtnh:fleet_beacon", 0),
    (-20, FEET, 42, "standing_sign", 0),
]
BINDS = [
    ("builder-a", -16, FEET, 33),
    ("builder-b", -11, FEET, 33),
    ("reviewer-a", -6, FEET, 33),
    ("builder-a", -14, FEET, 33),
    ("builder-b", -9, FEET, 33),
    ("fleet", -4, FEET, 33),
    ("overflow", -20, FEET, 42),
]
# terminal is deliberately left out first (missing-anchor fallback)
ANCHORS = [
    ("desk_builder-a", -15.5, 35.5, "north"),
    ("desk", -10.5, 35.5, "north"),
    ("desk_2", -5.5, 35.5, "north"),
    ("library", 0.5, 38.5, "east"),
    ("library_2", 0.5, 41.5, "east"),
    ("user", -22.5, 37.5, "east"),
    ("user_2", -22.5, 40, "east"),
    ("lounge", -16.5, 47.5, "north"),
    ("lounge_2", -14, 47.5, "north"),
    ("lounge_3", -11.5, 47.5, "north"),
    ("lounge_4", -9, 47.5, "north"),
]


def platform():
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            yield x, z


def main(mode):
    if mode == "check":
        print("# every position the arena can touch must be air before it is built")
        for x, z in platform():
            for y in range(FLOOR, FEET + 3):
                print(f"testforblock {x} {y} {z} air")
    elif mode == "build":
        print("# glass floor (mobs cannot spawn on glass)")
        for x, z in platform():
            print(f"setblock {x} {FLOOR} {z} glass")
        for x, y, z, b, meta in BLOCKS:
            print(f"setblock {x} {y} {z} {b} {meta}")
        for who, x, y, z in BINDS:
            print(f"agentcraft bind {who} {x} {y} {z}")
        for name, x, z, facing in ANCHORS:
            print(f"agentcraft anchor set {name} {x} {FEET} {z} {facing}")
    elif mode == "clear":
        for x, y, z, _b, _m in BLOCKS:
            print(f"setblock {x} {y} {z} air")
        for x, z in platform():
            print(f"setblock {x} {FLOOR} {z} air")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "")
