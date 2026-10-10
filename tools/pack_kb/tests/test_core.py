"""Contract tests for exact queries and bounded, advisory recipe resolution."""
import copy
import itertools
import unittest
from unittest.mock import patch

from tools.pack_kb import core
from tools.pack_kb.tests.helpers import custom, recipe, stack, store


def ids(rows):
    return [row["id"] for row in rows]


def raw(chain):
    return {(row["item"], row["unit"], row["consumed"]): row["amount"] for row in chain["raw_inputs"]}


class QueryTests(unittest.TestCase):
    def setUp(self):
        self.data = store()

    def test_reverse_needs_returns_whole_input_recipes_including_catalysts_disabled(self):
        rows = core.needs(self.data, "sample:die")
        self.assertEqual(ids(rows), ["sample:plate_press", "sample:plate_cast", "sample:wire_draw"])
        self.assertEqual(rows, [self.data["recipes"][i] for i in (0, 1, 2)])
        self.assertIn("sample:blocked", ids(core.needs(self.data, "sample:ore")))
        self.assertEqual(core.needs(self.data, "sample:widget"), [])
        self.assertEqual(core.needs(self.data, "sample:missing"), [])

    def test_reverse_needs_exact_unit_and_no_duplicate_recipe(self):
        self.assertEqual(core.needs(self.data, "sample:water"), [])
        self.assertEqual(ids(core.needs(self.data, "sample:water", "mB")), ["sample:plate_cast", "sample:widget_assemble"])
        self.data["recipes"][0]["inputs"].append(stack("sample:ore", consumed=False))
        self.assertEqual(ids(core.needs(self.data, "sample:ore")).count("sample:plate_press"), 1)

    def test_output_lookup_includes_disabled_chance_and_exact_unit(self):
        self.assertEqual(ids(core.recipes_by_output(self.data, "sample:plate")), ["sample:plate_press", "sample:plate_cast"])
        self.assertEqual(ids(core.recipes_by_output(self.data, "sample:water")), ["sample:water_token"])
        self.assertEqual(ids(core.recipes_by_output(self.data, "sample:water", "mB")), ["sample:water_condense"])
        self.assertFalse(core.recipes_by_output(self.data, "sample:blocked")[0]["enabled"])
        self.assertEqual(len(core.recipes_by_output(self.data, "sample:mixed")), 1)
        self.assertEqual(ids(core.recipes_by_output(self.data, "sample:gem")), ["sample:lottery", "sample:gem_cut"])
        self.assertEqual(core.recipes_by_output(self.data, "sample:missing"), [])

    def test_machines_exact_tier_and_unclassified(self):
        self.assertEqual(ids(core.machines_by_tier(self.data, "sample:T0")), ["sample:press"])
        self.assertEqual(ids(core.machines_by_tier(self.data, "sample:T1")), ["sample:assembler"])
        self.assertEqual(ids(core.machines_by_tier(self.data, None)), ["sample:bench"])
        self.assertEqual(core.machines_by_tier(self.data, "T0"), [])

    def test_requirements_partition_inputs_and_guaranteed_outputs(self):
        row = core.requirements(self.data, "sample:plate")[0]
        self.assertEqual(row["recipe_id"], "sample:plate_press")
        self.assertEqual(row["guaranteed_output_per_batch"], 2)
        self.assertEqual(row["inputs"], self.data["recipes"][0]["inputs"])
        self.assertEqual(row["consumed_inputs"], [stack("sample:ore", 3)])
        self.assertEqual(row["catalysts"], [stack("sample:die", 2, consumed=False)])
        self.assertEqual(core.requirements(self.data, "sample:scrap")[0]["guaranteed_output_per_batch"], 0)
        self.assertEqual(core.requirements(self.data, "sample:mixed")[0]["guaranteed_output_per_batch"], 2)
        self.assertFalse(core.requirements(self.data, "sample:blocked")[0]["enabled"])
        self.assertEqual(core.requirements(self.data, "sample:missing"), [])

    def test_every_query_is_detached_and_leaves_store_unchanged(self):
        before = copy.deepcopy(self.data)
        queries = (lambda: core.needs(self.data, "sample:die"),
                   lambda: core.recipes_by_output(self.data, "sample:plate"),
                   lambda: core.machines_by_tier(self.data, "sample:T0"),
                   lambda: core.requirements(self.data, "sample:plate"),
                   lambda: core.resolve(self.data, "sample:widget"))
        for query in queries:
            with self.subTest(query=query):
                result = query()
                if isinstance(result, list):
                    result[0]["provenance"][0]["locator"] = "mutated"
                    result.clear()
                else:
                    result["chains"][0]["steps"][0]["recipe_id"] = "mutated"
                    result["chains"].clear()
                self.assertEqual(self.data, before)

    def test_query_rejects_invalid_identity_or_invalid_store(self):
        for query in (core.needs, core.recipes_by_output, core.requirements):
            for item, unit in (("", "item"), (None, "item"), ("sample:x\n", "item"), ("sample:x", "liters")):
                with self.subTest(query=query.__name__, item=item, unit=unit), self.assertRaises(core.ValidationError):
                    query(self.data, item, unit)
        with self.assertRaises(core.ValidationError):
            core.machines_by_tier(self.data, True)
        self.data["schema_version"] = 2
        for query in (core.needs, core.recipes_by_output, core.requirements, core.resolve):
            with self.assertRaises(core.ValidationError):
                query(self.data, "sample:ore")


class ResolutionTests(unittest.TestCase):
    def setUp(self):
        self.data = store()

    def plan(self, item, **kw):
        return core.resolve(self.data, "sample:" + item, **kw)

    def test_integer_rounding_surplus_and_large_exact_amount(self):
        for amount in (1, 2, 3, 5, 2**60 + 1):
            with self.subTest(amount=amount):
                result = self.plan("plate", amount=amount)
                for chain, per_batch, demand in zip(result["chains"], (2, 3), (3, 4)):
                    step = chain["steps"][0]
                    expected = (amount + per_batch - 1) // per_batch
                    self.assertEqual(step["batches"], expected)
                    self.assertEqual(step["produced_amount"], expected * per_batch)
                    self.assertEqual(step["surplus"], expected * per_batch - amount)
                    name = "sample:ore" if per_batch == 2 else "sample:powder"
                    self.assertEqual(raw(chain)[(name, "item", True)], expected * demand)

    def test_cartesian_alternatives_are_distinct_and_deterministic(self):
        result = self.plan("widget", amount=5)
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(len(result["chains"]), 4)
        actual = [(chain["steps"][1]["recipe_id"], next(step["recipe_id"] for step in chain["steps"] if step["item"] == "sample:wire")) for chain in result["chains"]]
        expected = list(itertools.product(("sample:plate_press", "sample:plate_cast"), ("sample:wire_draw", "sample:wire_spin")))
        self.assertEqual(actual, expected)
        self.assertEqual(result, self.plan("widget", amount=5))
        self.assertEqual(result["chains"][0]["steps"][0]["batches"], 3)
        self.assertEqual(raw(result["chains"][0])[("sample:ore", "item", True)], 15)
        self.assertEqual(raw(result["chains"][0])[("sample:die", "item", False)], 3)

    def test_identical_raw_demands_keep_distinct_recipe_choices(self):
        data = custom([recipe("sample:first", [stack("sample:raw")], [stack("sample:out")]),
                       recipe("sample:second", [stack("sample:raw")], [stack("sample:out")])])
        result = core.resolve(data, "sample:out")
        self.assertEqual(len(result["chains"]), 2)
        self.assertEqual(result["chains"][0]["raw_inputs"], result["chains"][1]["raw_inputs"])

    def test_cycles_branch_local_and_unit_sensitive(self):
        result = self.plan("cycle_a")
        self.assertEqual(result["status"], "unresolved")
        problem = result["chains"][0]["unresolved"][0]
        self.assertEqual(problem["reason"], "cycle")
        self.assertEqual(problem["cycle"], [{"item": "sample:cycle_a", "unit": "item"}, {"item": "sample:cycle_b", "unit": "item"}, {"item": "sample:cycle_a", "unit": "item"}])
        self.data["recipes"].append(recipe("sample:escape", [stack("sample:raw")], [stack("sample:cycle_a")]))
        self.assertEqual(self.plan("cycle_a")["status"], "partial")
        data = custom([recipe("sample:convert", [stack("sample:same", 5, "mB")], [stack("sample:same")])])
        self.assertEqual(core.resolve(data, "sample:same")["status"], "resolved")

    def test_catalysts_scale_once_and_merge_max_without_byproduct_credit(self):
        chain = self.plan("widget", amount=20)["chains"][0]
        self.assertEqual(raw(chain)[("sample:die", "item", False)], 3)
        self.assertEqual(chain["catalysts"], [stack("sample:die", 3, consumed=False)])
        self.assertNotIn(("sample:scrap", "item", True), raw(chain))
        tool = self.plan("tool_product", amount=9)["chains"][0]
        self.assertEqual(raw(tool)[("sample:stone", "item", True)], 3)
        self.assertEqual(raw(tool)[("sample:ore", "item", True)], 18)
        self.assertFalse(tool["steps"][1]["consumed"])
        self.assertEqual(tool["steps"][1]["batches"], 1)
        warnings = {row["reason"] for row in tool["warnings"]}
        self.assertEqual(warnings, {"unknown_duration_ticks", "unknown_eut"})

    def test_consumed_and_reusable_same_leaf_are_separate_demands(self):
        data = custom([recipe("sample:r", [stack("sample:raw", 2), stack("sample:raw", 3, consumed=False)], [stack("sample:out")])])
        chain = core.resolve(data, "sample:out", amount=2, inventory={"sample:raw": 6})["chains"][0]
        self.assertEqual(raw(chain), {("sample:raw", "item", True): 4, ("sample:raw", "item", False): 3})
        self.assertEqual(chain["bottleneck"]["deficits"][0]["required"], 7)

    def test_stochastic_alternative_not_credited_and_guaranteed_sum_only(self):
        result = self.plan("gem")
        self.assertEqual(result["status"], "partial")
        self.assertEqual([row["status"] for row in result["chains"]], ["unresolved", "resolved"])
        chance = result["chains"][0]
        self.assertEqual(chance["steps"], [])
        self.assertEqual(chance["raw_inputs"], [])
        self.assertEqual(chance["unresolved"][0]["reason"], "probabilistic_output")
        mixed = self.plan("mixed", amount=5)["chains"][0]
        self.assertEqual(mixed["steps"][0]["batches"], 3)
        self.assertEqual(mixed["steps"][0]["guaranteed_output_per_batch"], 2)
        self.assertIn("stochastic_outputs_not_credited", {w["reason"] for w in mixed["warnings"]})
        uncertain = self.plan("uncertain")["chains"][0]
        self.assertEqual(uncertain["unresolved"][0]["reason"], "unsupported_prerequisite")
        self.assertEqual(uncertain["raw_inputs"], [])

    def test_disabled_only_symbolic_and_plain_leaves(self):
        blocked = self.plan("blocked")
        self.assertEqual(blocked["status"], "unresolved")
        self.assertEqual(blocked["chains"][0]["unresolved"][0]["reason"], "disabled_recipes")
        symbolic = self.plan("symbolic")["chains"][0]
        self.assertEqual(symbolic["unresolved"][0]["reason"], "unsupported_prerequisite")
        self.assertEqual(raw(symbolic)[("ore:synthetic:Imaginary", "item", True)], 2)
        leaf = self.plan("unknown", amount=6, max_depth=0)
        self.assertEqual(leaf["status"], "resolved")
        self.assertEqual(leaf["chains"][0]["steps"], [])
        self.assertEqual(raw(leaf["chains"][0])[("sample:unknown", "item", True)], 6)

    def test_fluid_identity_and_separate_unit_bottlenecks(self):
        self.assertEqual(self.plan("water", amount=1001, unit="mB")["chains"][0]["steps"][0]["batches"], 2)
        self.assertEqual(self.plan("water")["chains"][0]["steps"][0]["recipe_id"], "sample:water_token")
        data = custom([recipe("sample:mix", [stack("sample:x", 4), stack("sample:x", 500, "mB")], [stack("sample:out")])])
        chain = core.resolve(data, "sample:out")["chains"][0]
        self.assertEqual(chain["bottleneck"]["method"], "largest_raw_demand_per_unit")
        self.assertEqual(chain["bottleneck"]["by_unit"], {"item": {"item": "sample:x", "unit": "item", "amount": 4}, "mB": {"item": "sample:x", "unit": "mB", "amount": 500}})

    def test_inventory_exact_ratios_ties_and_scoring_only(self):
        data = custom([recipe("sample:r", [stack("sample:x", 4), stack("sample:y", 8), stack("sample:x", 100, "mB")], [stack("sample:out")])])
        inventory = {"sample:x": 2, "sample:y": 4, "mB:sample:x": 75, "sample:unused": 999}
        before = copy.deepcopy(inventory)
        plain = core.resolve(data, "sample:out")
        scored = core.resolve(data, "sample:out", inventory=inventory)
        self.assertEqual(inventory, before)
        self.assertEqual(scored["chains"][0]["steps"], plain["chains"][0]["steps"])
        self.assertEqual(scored["chains"][0]["raw_inputs"], plain["chains"][0]["raw_inputs"])
        bn = scored["chains"][0]["bottleneck"]
        self.assertEqual(bn["method"], "largest_inventory_deficit_ratio")
        self.assertEqual([row["item"] for row in bn["bottlenecks"]], ["sample:x", "sample:y"])
        for row in bn["bottlenecks"]:
            self.assertEqual(row["deficit_ratio_exact"], {"numerator": 1, "denominator": 2})
        full = core.resolve(data, "sample:out", inventory={"sample:x": 9, "sample:y": 9, "mB:sample:x": 999})
        self.assertEqual(full["chains"][0]["bottleneck"]["bottlenecks"], [])

    def test_depth_and_chain_caps_report_global_truncation(self):
        for depth in (0, 1):
            result = self.plan("widget", max_depth=depth)
            self.assertEqual(result["status"], "truncated")
            self.assertIn("max_depth", result["truncation_reasons"])
            self.assertTrue(any(p["reason"] == "max_depth" for c in result["chains"] for p in c["unresolved"]))
        result = self.plan("widget", max_chains=2)
        self.assertEqual(len(result["chains"]), 2)
        self.assertEqual(result["status"], "truncated")
        self.assertEqual(result["truncation_reasons"], ["max_chains"])
        self.assertTrue(all(c["status"] == "resolved" for c in result["chains"]))
        self.assertEqual(self.plan("widget", max_chains=4)["truncated"], False)

    def test_work_limit_is_observable_and_never_satisfies_remaining_inputs(self):
        # Lower internal bounds for a fast deterministic boundary test; production
        # values are asserted independently so a weakened implementation fails.
        self.assertEqual(core.MAX_RESOLUTION_WORK, 20000)
        with patch.object(core, "MAX_RESOLUTION_WORK", 4):
            result = self.plan("widget")
        self.assertEqual(result["status"], "truncated")
        self.assertIn("work_limit", result["truncation_reasons"])
        self.assertEqual(result["limits"]["work_used"], 4)
        self.assertTrue(all(c["unresolved"] for c in result["chains"]))
        self.assertTrue(any("remaining prerequisites" in p.get("detail", "") for c in result["chains"] for p in c["unresolved"]))

    def test_step_limit_is_observable_and_bounded(self):
        self.assertEqual(core.MAX_CHAIN_STEPS, 4096)
        with patch.object(core, "MAX_CHAIN_STEPS", 1):
            result = self.plan("widget")
        self.assertEqual(result["status"], "truncated")
        self.assertIn("step_limit", result["truncation_reasons"])
        self.assertTrue(all(len(c["steps"]) <= 1 for c in result["chains"]))
        self.assertTrue(any(p["reason"] == "step_limit" for c in result["chains"] for p in c["unresolved"]))

    def test_byproducts_and_shared_manufactured_catalysts_are_not_credited(self):
        data = custom([
            recipe("sample:root", [stack("sample:a"), stack("sample:b")], [stack("sample:out")]),
            recipe("sample:a_recipe", [stack("sample:tool", consumed=False)], [stack("sample:a"), stack("sample:b", 99)]),
            recipe("sample:b_recipe", [stack("sample:tool", consumed=False)], [stack("sample:b")]),
            recipe("sample:tool_recipe", [stack("sample:stone", 3)], [stack("sample:tool")]),
        ])
        result = core.resolve(data, "sample:out")
        explicit = next(c for c in result["chains"] if any(s["recipe_id"] == "sample:b_recipe" for s in c["steps"]))
        self.assertEqual(raw(explicit)[("sample:stone", "item", True)], 6)
        self.assertEqual(sum(s["recipe_id"] == "sample:tool_recipe" for s in explicit["steps"]), 2)
        self.assertEqual(explicit["catalysts"], [stack("sample:tool", consumed=False)])
        self.assertTrue(any(s["item"] == "sample:b" for s in explicit["steps"]))

    def test_production_depth_and_chain_limits(self):
        self.assertEqual((core.MAX_DEPTH, core.MAX_CHAINS), (128, 256))
        rows = [recipe(f"sample:r{i}", [stack(f"sample:d{i + 1}")], [stack(f"sample:d{i}")]) for i in range(129)]
        result = core.resolve(custom(rows), "sample:d0", max_depth=128)
        self.assertEqual(result["status"], "truncated")
        self.assertEqual(result["truncation_reasons"], ["max_depth"])
        self.assertEqual(len(result["chains"][0]["steps"]), 128)
        choices = [recipe(f"sample:choice{i}", [stack("sample:raw")], [stack("sample:out")]) for i in range(257)]
        result = core.resolve(custom(choices), "sample:out", max_chains=256)
        self.assertEqual(len(result["chains"]), 256)
        self.assertEqual(result["truncation_reasons"], ["max_chains"])

    def test_production_work_limit_on_generated_cartesian_store(self):
        inputs = [stack(f"sample:part{i}") for i in range(100)]
        rows = [recipe("sample:wide", inputs, [stack("sample:out")])]
        rows += [recipe(f"sample:part{i}_choice{choice}", [stack("sample:raw")], [stack(f"sample:part{i}")]) for i in range(100) for choice in range(2)]
        result = core.resolve(custom(rows), "sample:out", max_chains=256)
        self.assertEqual(result["status"], "truncated")
        self.assertIn("work_limit", result["truncation_reasons"])
        self.assertEqual(result["limits"]["work_used"], 20000)
        self.assertLessEqual(len(result["chains"]), 256)
        self.assertTrue(all(c["status"] == "unresolved" for c in result["chains"]))

    def test_production_step_limit_on_generated_wide_store(self):
        inputs = [stack(f"sample:part{i}") for i in range(4096)]
        rows = [recipe("sample:wide", inputs, [stack("sample:out")])]
        # Core permits no-input recipes. They isolate the step cap without
        # hitting the independent work cap first (one-output fictional sources).
        rows += [recipe(f"sample:part{i}_recipe", [], [stack(f"sample:part{i}")]) for i in range(4096)]
        result = core.resolve(custom(rows), "sample:out")
        self.assertEqual(result["status"], "truncated")
        self.assertEqual(result["truncation_reasons"], ["step_limit"])
        self.assertEqual(len(result["chains"][0]["steps"]), 4096)
        self.assertEqual(result["chains"][0]["unresolved"][0]["reason"], "step_limit")
        self.assertLessEqual(result["limits"]["work_used"], 20000)

    def test_static_warnings_do_not_claim_runtime_verification(self):
        data = custom([recipe("sample:r", [stack("sample:raw")], [stack("sample:out")], basis="static-bytecode", eut=None)])
        chain = core.resolve(data, "sample:out")["chains"][0]
        self.assertEqual(chain["status"], "resolved")
        self.assertEqual({w["reason"] for w in chain["warnings"]}, {"not_runtime_verified", "unknown_eut"})

    def test_resolve_rejects_invalid_parameters_and_inventory(self):
        for kwargs in ({"amount": 0}, {"amount": True}, {"amount": 1.5}, {"max_depth": -1}, {"max_depth": 129}, {"max_depth": True}, {"max_chains": 0}, {"max_chains": 257}, {"max_chains": False}, {"inventory": []}, {"inventory": {"sample:x": -1}}, {"inventory": {"sample:x": True}}, {"inventory": {"sample:x": 1.5}}, {"inventory": {"mB:": 2}}, {"inventory": {"": 1}}):
            with self.subTest(kwargs=kwargs), self.assertRaises(core.ValidationError):
                self.plan("plate", **kwargs)
        with self.assertRaises(core.ValidationError):
            core.resolve(self.data, "mB:ambiguous", inventory={})
        # Reserved-prefix item IDs can be queried, but not scored ambiguously.
        self.assertEqual(core.resolve(self.data, "mB:ambiguous")["status"], "resolved")


if __name__ == "__main__":
    unittest.main()
