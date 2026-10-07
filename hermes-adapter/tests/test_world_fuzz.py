"""Seeded fuzzing of the world.* normalisation: hostile telemetry never crashes the adapter,
never produces NaN/inf, never exceeds a bound and never leaks a planted secret or player name."""

import copy
import json
import random
import unittest

from hermes_adapter import world

from factory_fixture import capture, machine

SECRET = "gh" + "p_" + "ZYXWVUTSRQPONMLKJIHGFEDCBAzyxwvu9876"
PLAYER = "PlantedPlayerName"


def junk(rng, depth=0):
    """A random JSON-ish value (including the awkward ones json.loads can produce)."""
    choices = ["str", "int", "float", "bool", "none", "big", "neg", "unicode", "secret"]
    if depth < 3:
        choices += ["list", "dict"]
    kind = rng.choice(choices)
    if kind == "str":
        return "".join(rng.choice("abc XYZ,.:@#/\\\n\t\x00\x7f<>{}\"'") for _ in range(rng.randint(0, 300)))
    if kind == "int":
        return rng.randint(-1000, 1000)
    if kind == "float":
        return rng.choice([0.5, -1e300, 1e300, float("nan"), float("inf"), -float("inf"), 3.25])
    if kind == "bool":
        return rng.random() < 0.5
    if kind == "none":
        return None
    if kind == "big":
        return 10 ** rng.randint(18, 40)
    if kind == "neg":
        return -(10 ** rng.randint(10, 30))
    if kind == "unicode":
        return "".join(chr(rng.randint(0x80, 0x2FFFF)) for _ in range(rng.randint(1, 80)))
    if kind == "secret":
        return f"token {SECRET} for {PLAYER}"
    if kind == "list":
        return [junk(rng, depth + 1) for _ in range(rng.randint(0, 6))]
    return {rng.choice(["id", "name", "x", "status", "items", "q"]): junk(rng, depth + 1) for _ in range(rng.randint(0, 5))}


def mutate(rng, obj, rate=0.25):
    """Replace, delete or keep each field of a valid capture at random (recursively)."""
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            r = rng.random()
            if r < rate / 3:
                continue
            out[k] = junk(rng) if r < rate else mutate(rng, v, rate)
        return out
    if isinstance(obj, list):
        return [junk(rng) if rng.random() < rate else mutate(rng, v, rate) for v in obj]
    return obj


def strings(obj, path="$"):
    if isinstance(obj, str):
        yield path, obj
    elif isinstance(obj, dict):
        for k, v in obj.items():
            yield path + "." + str(k), str(k)
            yield from strings(v, path + "." + str(k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from strings(v, f"{path}[{i}]")


class FuzzTest(unittest.TestCase):
    def check(self, model):
        pub = world.public(model)
        text = json.dumps(pub, allow_nan=False)  # raises on NaN / inf
        self.assertNotIn(SECRET, json.dumps(model, allow_nan=False))
        if PLAYER in model["private"]["playersInScope"]:
            # names the capture reports are kept out of every public string (ids excepted)
            self.assertNotIn(PLAYER.lower(), json.dumps({k: v for k, v in pub.items()}).lower())
        for path, s in strings(pub):
            self.assertLessEqual(len(s), world.TEXT["headline"], path)
        self.assertLessEqual(len(pub["machines"]), world.LIMITS["machines"])
        for m in pub["machines"]:
            self.assertLessEqual(len(m["id"]), world.TEXT["id"])
            self.assertLessEqual(len(m["parts"]), world.LIMITS["parts"])
            self.assertIn(m["state"], world.MACHINE_STATES)
        self.assertLessEqual(len(pub["ae2"]["top"]), world.LIMITS["top"])
        self.assertLessEqual(len(pub["design"].get("palette", [])), world.LIMITS["palette"])

    def test_mutated_captures(self):
        rng = random.Random(20261006)
        base = capture([machine(i, s) for i, s in enumerate(["running", "idle", "maintenance", "unformed", "unknown"])],
                       players=[PLAYER])
        prev = world.normalize(base)
        for i in range(400):
            raw = mutate(rng, copy.deepcopy(base), rate=rng.choice([0.05, 0.2, 0.5]))
            raw["captureSequence"] = i + 2
            with self.subTest(i=i):
                model = world.normalize(raw)
                self.check(model)
                events = world.change_events(prev, model, {})
                json.dumps(events, allow_nan=False)
                self.assertLessEqual(len(events), world.LIMITS["eventsPerCapture"])
                world.diff_messages(world.public(prev), world.public(model))
                prev = model

    def test_entirely_random_documents(self):
        rng = random.Random(7)
        for i in range(300):
            doc = junk(rng)
            raw = doc if isinstance(doc, dict) else {"multiblocks": doc}
            with self.subTest(i=i):
                self.check(world.normalize(raw))

    def test_ids_from_random_unicode(self):
        rng = random.Random(99)
        seen = {}
        for _ in range(2000):
            raw = junk(rng)
            w = world.wid(raw)
            self.assertLessEqual(len(w), world.TEXT["id"])
            self.assertRegex(w, r"^[A-Za-z0-9_.:@,#+\-~]*$")
            if isinstance(raw, str) and w:
                seen.setdefault(w, set()).add(raw)
        # truncation and hashing keep distinct long inputs distinct
        longs = {k: v for k, v in seen.items() if "~" in k}
        self.assertTrue(all(len(v) == 1 for v in longs.values()))


if __name__ == "__main__":
    unittest.main()
