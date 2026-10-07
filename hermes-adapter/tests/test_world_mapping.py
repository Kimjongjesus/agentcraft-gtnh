"""world.* mapping: snapshot normalisation, incremental diffs, change events, bounds, privacy."""

import copy
import json
import unittest

from hermes_adapter import world
from hermes_adapter.redact import REDACTED, WITHHELD

from factory_fixture import T0, capture, machine


class SnapshotMappingTest(unittest.TestCase):
    def setUp(self):
        self.model = world.normalize(capture())

    def test_base_scope_coverage_summary_and_headline(self):
        base = self.model["base"]
        self.assertEqual(base["id"], "test-base")
        self.assertEqual(base["label"], "Test Base")
        self.assertEqual(base["scope"]["kind"], "configured")
        self.assertEqual(base["scope"]["center"], {"x": 100, "y": 64, "z": -20})
        self.assertEqual(set(base["coverage"]), {"machines", "stock", "design", "surroundings"})
        self.assertEqual(base["coverage"]["machines"]["status"], "ok")
        s = base["summary"]
        self.assertEqual((s["machines"], s["running"], s["idle"], s["maintenance"]), (3, 1, 1, 1))
        self.assertEqual(s["playersInScope"], 1)
        self.assertIn("3 machines: 1 running, 1 idle, 1 needing maintenance.", base["headline"])
        self.assertIn("AE2 powered, 1 of 4 crafting CPUs busy.", base["headline"])
        self.assertNotIn("Not fully seen", base["headline"])

    def test_machine_is_typed_located_and_has_a_state(self):
        m = next(m for m in self.model["machines"] if m["id"].startswith("dim0:multimachine.test1@"))
        self.assertEqual(m["kind"], "multiblock")
        self.assertEqual(m["type"], "multimachine.test1")
        self.assertEqual((m["dim"], m["x"], m["y"], m["z"]), (0, 104, 64, -20))
        self.assertEqual(m["state"], "running")
        self.assertEqual(m["progress"], {"ticks": 40, "max": 200})
        self.assertEqual(m["euPerTick"], -480)
        self.assertEqual(len(m["parts"]), 3)
        self.assertNotIn("meChannelActive", m["parts"][0], "null tri-state must be omitted, not false")
        self.assertNotIn("problem", m)

    def test_power_ae2_design(self):
        p = self.model["power"]
        self.assertEqual(p["ae"]["fillPct"], 80.0)
        self.assertEqual(p["eu"]["consumingPerTick"], 480)
        self.assertEqual(p["eu"]["machinesReporting"], 3)
        a = self.model["ae2"]
        self.assertTrue(a["available"])
        self.assertEqual((a["cpus"], a["busyCpus"], a["jobs"], a["craftableTypes"]), (4, 1, 1, 1))
        self.assertEqual(a["top"][0]["name"], "Stone")
        d = self.model["design"]
        self.assertEqual(d["palette"][0], {"id": "minecraft:stonebrick#0", "name": "Stone Bricks", "count": 252, "share": 0.252})
        self.assertEqual(d["light"]["unlit"], 80)

    def test_unknown_tristate_is_omitted_and_state_unknown(self):
        m = world.machine(machine(9, "unknown"))
        self.assertEqual(m["state"], "unknown")
        for key in ("active", "formed", "needsMaintenance"):
            self.assertNotIn(key, m)

    def test_unreadable_sections_are_unavailable_not_empty_and_healthy(self):
        raw = capture()
        del raw["stock"]
        raw["design"] = {"coverage": {"status": "UNAVAILABLE", "reason": "disabled"}}
        m = world.normalize(raw)
        self.assertFalse(m["ae2"]["available"])
        self.assertEqual(m["ae2"]["coverage"]["status"], "unavailable")
        self.assertNotIn("ae", m["power"], "no AE numbers when AE2 could not be read")
        self.assertFalse(m["design"]["available"])
        self.assertIn("Not fully seen: design, stock.", m["base"]["headline"])

    def test_garbage_values_do_not_crash_or_leak_through(self):
        raw = capture([{"id": "dim0:x@1,2,3", "x": "12", "y": float("nan"), "active": "yes", "euPerTick": True,
                        "parts": "nope", "maintenanceIssues": [{"a": 1}, None, 5]}, "not a dict", None, {"no": "id"}])
        raw["stock"]["items"] = [None, {"id": None}, {"id": "a:b@0", "quantity": "lots"}]
        m = world.normalize(raw)
        self.assertEqual(len(m["machines"]), 1)
        mm = m["machines"][0]
        self.assertEqual((mm["x"], mm["y"]), (0, 0))
        self.assertNotIn("active", mm)
        self.assertNotIn("euPerTick", mm)
        self.assertEqual(mm["parts"], [])
        self.assertEqual(m["ae2"]["top"], [{"id": "a:b@0", "name": "a:b@0", "quantity": 0, "craftable": False}])
        json.dumps(m)  # always serialisable


class PrivacyTest(unittest.TestCase):
    def test_player_names_and_anchor_only_in_private_part(self):
        raw = capture(players=["PlayerOne", "PlayerTwo"])
        raw["scope"]["kind"] = "PLAYER_RELATIVE"
        raw["scope"]["anchor"] = "PlayerOne"
        m = world.normalize(raw)
        self.assertEqual(m["private"]["playersInScope"], ["PlayerOne", "PlayerTwo"])
        self.assertEqual(m["private"]["anchor"], "PlayerOne")
        wire = json.dumps(world.public(m))
        self.assertNotIn("PlayerOne", wire)
        self.assertNotIn("PlayerTwo", wire)
        self.assertEqual(m["base"]["summary"]["playersInScope"], 2)
        self.assertEqual(m["base"]["scope"]["kind"], "player_relative")

    def test_untrusted_strings_are_filtered_over_the_whole_text_before_the_cut(self):
        tok = "gh" + "p_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef1234"
        long_name = "A" * 70 + " " + tok
        raw = capture([machine(1, name=long_name), machine(2, name="see personal-notes.md for it"),
                       machine(3, problem="password=" + "hunter2Secret!")])
        m = {x["id"]: x for x in world.normalize(raw)["machines"]}
        names = [x["name"] for x in m.values()]
        self.assertTrue(all(tok[:12] not in n for n in names))
        self.assertIn(WITHHELD, names)
        prob = next(x for x in m.values() if x["id"].startswith("dim0:multimachine.test3@"))["problem"]
        self.assertIn(REDACTED, prob)
        self.assertNotIn("hunter2", prob)
        self.assertTrue(all(len(n) <= world.TEXT["name"] for n in names))

    def test_ids_are_strict_and_hashed_when_the_filter_objects(self):
        self.assertEqual(world.wid("dim0:multimachine.ebf@1,2,-3"), "dim0:multimachine.ebf@1,2,-3")
        self.assertEqual(world.wid("bad id\n<script>"), "bad_id__script_")
        hexy = "a" * 40
        self.assertTrue(world.wid(hexy).startswith("id-"))
        long = "x." * 200
        w = world.wid(long)
        self.assertLessEqual(len(w), world.TEXT["id"])
        self.assertNotEqual(w, world.wid(long + "y"), "truncated ids stay unique")


class BoundsTest(unittest.TestCase):
    def test_machine_cap_keeps_problems_first(self):
        ms = [machine(i, "running") for i in range(600)] + [machine(1000 + i, "maintenance") for i in range(5)]
        out = world.machines(ms)
        self.assertEqual(len(out), world.LIMITS["machines"])
        self.assertEqual(sum(1 for m in out if m["state"] == "maintenance"), 5)
        self.assertEqual([m["id"] for m in out], sorted(m["id"] for m in out))

    def test_parts_issues_items_palette_caps(self):
        raw = capture([machine(1, parts=100, maintenanceIssues=[f"issue {i}" for i in range(30)])],
                      items=[{"id": f"m:item{i}@0", "name": f"Item {i}", "quantity": i, "craftable": i % 2 == 0} for i in range(500)])
        raw["design"]["palette"] = [{"id": f"m:block{i}#0", "name": "B", "count": i} for i in range(300)]
        m = world.normalize(raw)
        mm = m["machines"][0]
        self.assertEqual(len(mm["parts"]), world.LIMITS["parts"])
        self.assertEqual(mm["partsTotal"], 100)
        self.assertEqual(len(mm["maintenance"]), world.LIMITS["maintenance"])
        self.assertEqual(len(m["ae2"]["top"]), world.LIMITS["top"])
        self.assertEqual(m["ae2"]["itemTypes"], 500)
        self.assertEqual(m["ae2"]["craftableTypes"], 250)
        self.assertEqual(len(m["design"]["palette"]), world.LIMITS["palette"])

    def test_events_per_capture_are_capped_with_a_summary(self):
        old = world.normalize(capture([machine(i, "running") for i in range(200)]))
        new = world.normalize(capture([machine(i, "idle") for i in range(200)], seq=2))
        ev = world.change_events(old, new)
        self.assertEqual(len(ev), world.LIMITS["eventsPerCapture"])
        self.assertEqual(ev[-1]["kind"], "events.dropped")
        self.assertIn("137 more changes", ev[-1]["text"])

    def test_snapshot_encodes_under_the_websocket_frame_budget(self):
        ms = [machine(i, "running", parts=40, name="N" * 80) for i in range(600)]
        m = world.public(world.normalize(capture(ms)))
        self.assertLess(len(json.dumps(m)), 2 * 1024 * 1024)

    def test_normalising_a_capture_at_the_caps_is_fast(self):
        import time

        raw = capture([machine(i, ["running", "idle", "problem"][i % 3], parts=40, problem="hatch at 1,2,3" if i % 3 == 2 else None)
                       for i in range(600)],
                      items=[{"id": f"m:item{i}@0", "name": f"Item {i}", "quantity": i, "craftable": i % 2 == 0} for i in range(3000)])
        t0 = time.perf_counter()
        a = world.normalize(raw)
        b = world.normalize(raw)
        world.change_events(a, b)
        world.diff_messages(world.public(a), world.public(b))
        self.assertLess(time.perf_counter() - t0, 5.0, "two captures at the caps must normalise well inside a poll interval")


class IncrementalTest(unittest.TestCase):
    def test_diff_messages_upsert_remove_and_sections(self):
        old = world.normalize(capture())
        raw = capture([machine(1, "idle"), machine(2, "idle"), machine(4)], seq=2, busy=4, ae_stored=100_000.0)
        new = world.normalize(raw)
        msgs = world.diff_messages(world.public(old), world.public(new))
        types = [m["type"] for m in msgs]
        self.assertEqual(types[0], "world.base.upsert")
        ups = {m["machine"]["id"].split("@")[0] for m in msgs if m["type"] == "world.machine.upsert"}
        self.assertEqual(ups, {"dim0:multimachine.test1", "dim0:multimachine.test4"}, "unchanged machine 2 is not re-sent")
        self.assertEqual([m["id"].split("@")[0] for m in msgs if m["type"] == "world.machine.remove"], ["dim0:multimachine.test3"])
        self.assertIn("world.power.upsert", types)
        self.assertIn("world.ae2.upsert", types)
        self.assertNotIn("world.design.upsert", types)
        for m in msgs:
            self.assertNotIn("PlayerOne", json.dumps(m))

    def test_identical_capture_produces_nothing(self):
        a = world.public(world.normalize(capture()))
        self.assertEqual(world.diff_messages(a, copy.deepcopy(a)), [])

    def test_change_events_cover_the_transitions(self):
        old = world.normalize(capture([machine(1), machine(2, "idle"), machine(3, "maintenance"), machine(5, "problem", problem="no power"),
                                       machine(6, "unformed"), machine(7)]))
        new = world.normalize(capture([machine(1, "idle"), machine(2), machine(3, "idle"), machine(5, "problem", problem="output full"),
                                       machine(6, "idle"), machine(8)], seq=2, powered=False, busy=4, ae_stored=40_000.0))
        flags = {}
        kinds = {(e["kind"], e["subject"].split("@")[0].replace("dim0:multimachine.", "")) for e in world.change_events(old, new, flags)}
        for expected in [("machine.stopped", "test1"), ("machine.started", "test2"), ("machine.recovered", "test3"),
                         ("machine.problem", "test5"), ("machine.formed", "test6"), ("machine.gone", "test7"),
                         ("machine.appeared", "test8"), ("ae2.offline", "ae2"), ("ae2.cpus.full", "ae2"), ("power.low", "power")]:
            self.assertIn(expected, kinds)
        self.assertTrue(flags["powerLow"])
        back = world.normalize(capture([machine(1)], seq=3, ae_stored=500_000.0, busy=1))
        kinds2 = {e["kind"] for e in world.change_events(new, back, flags)}
        self.assertIn("power.recovered", kinds2)
        self.assertIn("ae2.online", kinds2)
        self.assertIn("ae2.cpus.free", kinds2)
        self.assertFalse(flags["powerLow"])

    def test_power_low_has_hysteresis(self):
        flags = {}
        seq = [800_000, 150_000, 250_000, 180_000, 310_000]
        models = [world.normalize(capture(seq=i + 1, ae_stored=float(v))) for i, v in enumerate(seq)]
        kinds = [[e["kind"] for e in world.change_events(a, b, flags) if e["kind"].startswith("power.")]
                 for a, b in zip(models, models[1:])]
        self.assertEqual(kinds, [["power.low"], [], [], ["power.recovered"]])

    def test_coverage_degraded_and_recovered(self):
        a = world.normalize(capture())
        b = world.normalize(capture(seq=2, machine_cov="PARTIAL"))
        ev = world.change_events(a, b)
        self.assertEqual([e["kind"] for e in ev if e["kind"].startswith("coverage")], ["coverage.degraded"])
        self.assertEqual([e["kind"] for e in world.change_events(b, a) if e["kind"].startswith("coverage")], ["coverage.recovered"])

    def test_gone_while_chunks_are_unloaded_is_not_an_alarm(self):
        a = world.normalize(capture([machine(1), machine(2)]))
        raw = capture([machine(1)], seq=2)
        raw["machineCoverage"]["skippedUnloaded"] = 12
        ev = [e for e in world.change_events(a, world.normalize(raw)) if e["kind"] == "machine.gone"]
        self.assertEqual(len(ev), 1)
        self.assertEqual(ev[0]["severity"], "info")
        self.assertIn("chunk may be unloaded", ev[0]["text"])
        self.assertEqual(ev[0]["data"]["unloadedSkipped"], 12)
        sev = [e["severity"] for e in world.change_events(a, world.normalize(capture([machine(1)], seq=2)))
               if e["kind"] == "machine.gone"]
        self.assertEqual(sev, ["warn"], "a running machine vanishing from a fully loaded base is a warning")

    def test_unformed_and_maintenance_have_severity(self):
        a = world.normalize(capture([machine(1), machine(2)]))
        b = world.normalize(capture([machine(1, "unformed"), machine(2, "maintenance")], seq=2))
        sev = {e["kind"]: e["severity"] for e in world.change_events(a, b)}
        self.assertEqual(sev["machine.unformed"], "critical")
        self.assertEqual(sev["machine.maintenance"], "warn")

    def test_start_up_burst_becomes_one_settled_summary(self):
        # first capture 20 s after the server start: GT reports everything unformed
        boot = T0
        caps = [
            capture([machine(i, "unformed") for i in range(50)], seq=1, captured_at=boot + 20_000, session_started=boot),
            capture([machine(i, "idle") for i in range(50)], seq=2, captured_at=boot + 50_000, session_started=boot),
            capture([machine(i, "idle") for i in range(50)], seq=3, captured_at=boot + 130_000, session_started=boot),
            capture([machine(i, "idle") for i in range(49)] + [machine(49)], seq=4, captured_at=boot + 160_000, session_started=boot),
        ]
        models = [world.normalize(c) for c in caps]
        self.assertTrue(world.settling(models[0]))
        self.assertFalse(world.settling(models[2]))
        steps = [world.change_events(a, b) for a, b in zip(models, models[1:])]
        self.assertEqual(steps[0], [], "no 'formed' burst while settling")
        self.assertEqual([e["kind"] for e in steps[1]], ["session.settled"])
        self.assertIn("50 machines: 50 idle", steps[1][0]["text"])
        self.assertEqual([e["kind"] for e in steps[2]], ["machine.started"], "normal events after the window")

    def test_ae_events_still_flow_while_settling(self):
        a = world.normalize(capture(seq=1, captured_at=T0 + 10_000, session_started=T0))
        b = world.normalize(capture(seq=2, captured_at=T0 + 40_000, session_started=T0, powered=False))
        self.assertEqual([e["kind"] for e in world.change_events(a, b)], ["ae2.offline"])


if __name__ == "__main__":
    unittest.main()
