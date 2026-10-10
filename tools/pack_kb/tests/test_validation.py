"""Strict schema, hostile JSON, and pack-relative provenance validation."""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from tools.pack_kb import core
from tools.pack_kb.tests.helpers import SAMPLE, scratch, store


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.data = store()

    def test_valid_store_returned_unchanged(self):
        before = copy.deepcopy(self.data)
        self.assertIs(core.validate_store(self.data), self.data)
        self.assertEqual(self.data, before)
        self.assertEqual(self.data["pack_version"], "synthetic")

    def test_missing_extra_and_wrong_top_level_fields(self):
        for field in self.data:
            with self.subTest(missing=field):
                bad = copy.deepcopy(self.data)
                del bad[field]
                with self.assertRaises(core.ValidationError):
                    core.validate_store(bad)
        for field, value in (("schema_version", True), ("schema_version", 2), ("pack_version", " "), ("coverage", []), ("recipes", {}), ("unknown", 1)):
            with self.subTest(field=field, value=value):
                bad = copy.deepcopy(self.data)
                bad[field] = value
                with self.assertRaises(core.ValidationError):
                    core.validate_store(bad)

    def test_duplicate_ids_in_every_kind_and_record_shapes(self):
        for kind in ("sources", "tiers", "machines", "recipes", "facts"):
            for operation in ("duplicate", "unknown", "missing", "nonobject", "blankid"):
                with self.subTest(kind=kind, operation=operation):
                    bad = copy.deepcopy(self.data)
                    if operation == "duplicate":
                        bad[kind].append(copy.deepcopy(bad[kind][0]))
                    elif operation == "unknown":
                        bad[kind][0]["unexpected"] = 1
                    elif operation == "missing":
                        del bad[kind][0]["id"]
                    elif operation == "nonobject":
                        bad[kind][0] = []
                    else:
                        bad[kind][0]["id"] = ""
                    with self.assertRaises(core.ValidationError):
                        core.validate_store(bad)

    def test_source_paths_reject_host_urls_traversal_and_ambiguous_archive(self):
        paths = ("/absolute/file", "~/file", "C:\\file", "https://example.invalid/file", "a\\b", "../file", "a/../b", "a/./b", "a//b", "a/", "a.jar!/../x", "a.jar!//x", "a.zip!/x", "a.jar!/x!/y", "a!b", "a\nb")
        for value in paths:
            with self.subTest(path=value):
                bad = copy.deepcopy(self.data)
                bad["sources"][0]["path"] = value
                with self.assertRaises(core.ValidationError):
                    core.validate_store(bad)
        for value in ("recipes.json", "config/Synthetic.cfg", "mods/synthetic.jar!/sample/Test.class"):
            with self.subTest(valid=value):
                bad = copy.deepcopy(self.data)
                bad["sources"][0]["path"] = value
                core.validate_store(bad)
        bad = copy.deepcopy(self.data)
        bad["sources"].append({**bad["sources"][0], "id": "sample:source2"})
        with self.assertRaisesRegex(core.ValidationError, "duplicate source path"):
            core.validate_store(bad)

    def test_sha256_and_provenance_validation(self):
        for value in ("", "a" * 63, "g" * 64, 123, None):
            with self.subTest(digest=value):
                bad = copy.deepcopy(self.data)
                bad["sources"][0]["sha256"] = value
                with self.assertRaises(core.ValidationError):
                    core.validate_store(bad)
        for value in ([], [{"source": "sample:missing", "locator": "line:1"}], [{"source": self.data["sources"][0]["id"], "locator": ""}], self.data["recipes"][0]["provenance"] * 2):
            with self.subTest(provenance=value):
                bad = copy.deepcopy(self.data)
                bad["recipes"][0]["provenance"] = value
                with self.assertRaises(core.ValidationError):
                    core.validate_store(bad)

    def test_tier_machine_recipe_and_fact_scalars(self):
        changes = (("tiers", "index", True), ("tiers", "voltage", -1), ("tiers", "recipe_eut", 1.0), ("machines", "tier", "sample:missing"), ("machines", "basis", "guessed"), ("recipes", "enabled", 1), ("recipes", "duration_ticks", -1), ("recipes", "eut", True), ("recipes", "basis", "unknown"), ("recipes", "outputs", []), ("facts", "value", []))
        for kind, field, value in changes:
            with self.subTest(kind=kind, field=field, value=value):
                bad = copy.deepcopy(self.data)
                bad[kind][0][field] = value
                with self.assertRaises(core.ValidationError):
                    core.validate_store(bad)
        self.data["tiers"][1]["index"] = self.data["tiers"][0]["index"]
        with self.assertRaisesRegex(core.ValidationError, "duplicate tier index"):
            core.validate_store(self.data)

    def test_stack_validation_and_identity(self):
        for field, value in (("amount", 0), ("amount", -1), ("amount", True), ("amount", 1.5), ("unit", "L"), ("item", ""), ("consumed", 0), ("chance", 0), ("chance", 1.1), ("chance", True), ("chance", float("nan")), ("chance", float("inf")), ("extra", 1)):
            with self.subTest(field=field, value=value):
                bad = copy.deepcopy(self.data)
                bad["recipes"][0]["inputs"][0][field] = value
                with self.assertRaises(core.ValidationError):
                    core.validate_store(bad)
        original = self.data["recipes"][0]["inputs"][0]
        self.data["recipes"][0]["inputs"].append({**original, "consumed": True, "chance": 1.0})
        with self.assertRaisesRegex(core.ValidationError, "duplicate stack identity"):
            core.validate_store(self.data)

    def test_json_coverage_bounds_nonfinite_nonjson_and_cycles(self):
        for value in (float("nan"), float("inf"), object(), {1: "badkey"}, (1, 2)):
            with self.subTest(value=type(value).__name__):
                bad = copy.deepcopy(self.data)
                bad["coverage"]["bad"] = value
                with self.assertRaises(core.ValidationError):
                    core.validate_store(bad)
        cyclic = []
        cyclic.append(cyclic)
        self.data["coverage"]["bad"] = cyclic
        with self.assertRaisesRegex(core.ValidationError, "cyclic"):
            core.validate_store(self.data)
        deep = {}
        for _ in range(core.MAX_JSON_DEPTH + 1):
            deep = {"next": deep}
        self.data["coverage"] = deep
        with self.assertRaisesRegex(core.ValidationError, "nesting limit"):
            core.validate_store(self.data)
        with patch.object(core, "MAX_JSON_NODES", 4), self.assertRaisesRegex(core.ValidationError, "node limit"):
            core.validate_store(store())

    def test_load_rejects_malformed_duplicate_nonfinite_utf8_and_depth(self):
        cases = (b"{", b'{"a":1,"a":2}', b'{"schema_version":1,"coverage":{"nested":{"a":1,"a":2}}}', b'{"a":NaN}', b'{"a":Infinity}', b'{"a":1e999}', b"\xff", b"[]", b"[" * 1100 + b"0" + b"]" * 1100)
        with scratch() as directory:
            path = Path(directory) / "bad.json"
            for content in cases:
                with self.subTest(content=content[:50]):
                    path.write_bytes(content)
                    with self.assertRaises(core.ValidationError):
                        core.load_store(path)

    def test_load_caps_and_io_errors_and_does_not_open_sources(self):
        path = SAMPLE / "store.json"
        self.assertEqual(core.load_store(path, max_bytes=path.stat().st_size), self.data)
        with self.assertRaisesRegex(core.ValidationError, "load limit"):
            core.load_store(path, max_bytes=path.stat().st_size - 1)
        for value in (0, True, core.MAX_STORE_BYTES + 1):
            with self.assertRaises(core.ValidationError):
                core.load_store(path, max_bytes=value)
        with scratch() as directory:
            missing = Path(directory) / "missing.json"
            with self.assertRaises(FileNotFoundError):
                core.load_store(missing)
            detached = copy.deepcopy(self.data)
            detached["sources"][0]["path"] = "not-present/fictional-source.json"
            target = Path(directory) / "detached.json"
            target.write_text(json.dumps(detached), encoding="utf-8")
            self.assertEqual(core.load_store(target), detached)


if __name__ == "__main__":
    unittest.main()
