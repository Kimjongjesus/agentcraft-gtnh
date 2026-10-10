"""Subprocess smoke tests of every public query/extractor/inspection command."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

from tools.pack_kb import core
from tools.pack_kb.tests.helpers import REPO, SAMPLE, make_pack, scratch


class CLITests(unittest.TestCase):
    def run_cli(self, module, *args):
        return subprocess.run([sys.executable, "-m", module, *map(str, args)], cwd=REPO,
                              env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
                              capture_output=True, text=True, timeout=30, check=False)

    def query(self, *args):
        result = self.run_cli("tools.pack_kb", "--store", SAMPLE / "store.json", *args)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        return json.loads(result.stdout)

    def test_every_query_command(self):
        self.assertEqual([r["id"] for r in self.query("recipes", "sample:plate")], ["sample:plate_press", "sample:plate_cast"])
        self.assertEqual([m["id"] for m in self.query("machines", "sample:T0")], ["sample:press"])
        self.assertEqual([r["id"] for r in self.query("needs", "sample:die")], ["sample:plate_press", "sample:plate_cast", "sample:wire_draw"])
        self.assertEqual(self.query("requirements", "sample:plate")[0]["guaranteed_output_per_batch"], 2)
        chain = self.query("chain", "sample:widget", "--amount", "5", "--max-depth", "8", "--max-chains", "4", "--inventory", '{"sample:ore":7}')
        self.assertEqual(chain["status"], "resolved")
        self.assertEqual(len(chain["chains"]), 4)
        self.assertEqual(chain["chains"][0]["bottleneck"]["method"], "largest_inventory_deficit_ratio")
        info = self.query("info")
        self.assertEqual(info["pack_version"], "synthetic")
        self.assertEqual(info["counts"]["recipes"], 17)
        self.assertTrue(info["advisory"])
        self.assertFalse(info["coverage"]["runtime_registry_complete"])
        self.assertEqual(self.query("recipes", "sample:water", "--unit", "mB")[0]["id"], "sample:water_condense")
        self.assertEqual(self.query("needs", "sample:water", "--unit", "mB")[0]["id"], "sample:plate_cast")
        self.assertEqual(self.query("requirements", "sample:water", "--unit", "mB")[0]["guaranteed_output_per_batch"], 1000)
        self.assertEqual(self.query("chain", "sample:water", "--unit", "mB", "--amount", "1001")["chains"][0]["steps"][0]["batches"], 2)

    def test_unresolved_and_truncated_are_successful_machine_readable_queries(self):
        self.assertEqual(self.query("chain", "sample:cycle_a")["status"], "unresolved")
        self.assertEqual(self.query("chain", "sample:gem")["status"], "partial")
        self.assertEqual(self.query("chain", "sample:widget", "--max-chains", "1")["status"], "truncated")

    def test_operational_and_inventory_errors_are_json_exit_two(self):
        for args in (("chain", "sample:plate", "--amount", "0"), ("chain", "sample:plate", "--max-depth", "129"), ("chain", "sample:plate", "--inventory", "[1]"), ("chain", "sample:plate", "--inventory", '{"sample:x":1,"sample:x":2}'), ("chain", "sample:plate", "--inventory", '{"sample:x":NaN}'), ("chain", "sample:plate", "--inventory", "{")):
            with self.subTest(args=args):
                result = self.run_cli("tools.pack_kb", "--store", SAMPLE / "store.json", *args)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")
                self.assertEqual(json.loads(result.stderr)["error_type"], "ValidationError")
        with scratch() as directory:
            bad = Path(directory) / "bad.json"
            bad.write_text("{", encoding="utf-8")
            result = self.run_cli("tools.pack_kb", "--store", bad, "info")
            self.assertEqual(result.returncode, 2)
            self.assertEqual(json.loads(result.stderr)["error_type"], "ValidationError")

    def test_argparse_errors_and_help(self):
        for args in ((), ("unknown",), ("recipes",), ("chain", "sample:x", "--unit", "L")):
            result = self.run_cli("tools.pack_kb", "--store", SAMPLE / "store.json", *args)
            self.assertEqual(result.returncode, 2)
            self.assertIn("usage:", result.stderr)
        result = self.run_cli("tools.pack_kb", "--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("requirements", result.stdout)
        self.assertIn("needs", result.stdout)

    def test_extractor_and_class_inspection_commands(self):
        with scratch() as directory:
            base = Path(directory)
            root = base / "pack"
            make_pack(root)
            dump = root / "recipes.json"
            dump.write_bytes((SAMPLE / "recipes.json").read_bytes())
            output = base / "store.json"
            args = ("--pack-root", root, "--pack-version", "synthetic", "--output", output, "--dump", dump)
            result = self.run_cli("tools.pack_kb.extract", *args)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stderr, "")
            self.assertEqual(json.loads(result.stdout)["counts"]["recipes"], 18)
            self.assertEqual(len(core.load_store(output)["recipes"]), 18)
            bad = self.run_cli("tools.pack_kb.extract", "--pack-root", root, "--pack-version", "synthetic", "--output", root / "forbidden.json")
            self.assertEqual(bad.returncode, 2)
            self.assertIn("outside the input pack tree", bad.stderr)
            self.assertFalse((root / "forbidden.json").exists())
            jar = root / "mods/gregtech-synthetic.jar"
            listing = self.run_cli("tools.pack_kb.classfile", jar)
            self.assertEqual(listing.returncode, 0, listing.stderr)
            self.assertIn("GTValues.class", listing.stdout)
            self.assertIn("TierEU.class", listing.stdout)
            inspect = self.run_cli("tools.pack_kb.classfile", jar, "gregtech/loaders/postload/recipes/SyntheticRecipes.class", "--limit", "2")
            self.assertEqual(inspect.returncode, 0, inspect.stderr)
            self.assertIn("METHOD run ()V", inspect.stdout)
            self.assertIn("stdBuilder", inspect.stdout)


if __name__ == "__main__":
    unittest.main()
