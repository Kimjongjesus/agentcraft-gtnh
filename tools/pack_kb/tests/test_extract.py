"""Extractor integration using only original generated synthetic pack inputs."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import unittest
from unittest.mock import patch
import warnings
import zipfile

from tools.pack_kb import core, extract
from tools.pack_kb.sample.generate import generate
from tools.pack_kb.tests.helpers import SAMPLE, make_pack, scratch, store


def snapshot(root):
    return {p.relative_to(root).as_posix(): (p.read_bytes(), p.stat().st_mtime_ns, p.stat().st_mode) for p in root.rglob("*") if p.is_file()}


class ExtractionTests(unittest.TestCase):
    def setUp(self):
        self.temp = scratch()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "pack"
        self.root.mkdir()

    def dump(self, data=None, content=None):
        path = self.root / "recipes.json"
        path.write_bytes(content if content is not None else json.dumps(data).encode("utf-8"))
        return path

    def test_sample_store_is_exactly_reproducible_with_real_source_hashes(self):
        data = generate()
        self.assertEqual(data, store())
        source = data["sources"][0]
        self.assertEqual(source["sha256"], hashlib.sha256((SAMPLE / "recipes.json").read_bytes()).hexdigest())
        self.assertEqual(source["path"], "recipes.json")
        source_doc = json.loads((SAMPLE / "recipes.json").read_text(encoding="utf-8"))
        for kind in ("tiers", "machines", "recipes", "facts"):
            for row in data[kind]:
                self.assertTrue(row["id"].startswith("sample:"))
                for p in row["provenance"]:
                    self.assertEqual(p["source"], source["id"])
                    pointed = source_doc
                    for component in p["locator"].split("/")[1:]:
                        pointed = pointed[int(component)] if isinstance(pointed, list) else pointed[component]
                    self.assertEqual({k: v for k, v in row.items() if k != "provenance"}, pointed)
        self.assertFalse(data["coverage"]["runtime_registry_complete"])
        self.assertTrue(data["coverage"]["synthetic"])
        # All shipped JSON fixture bytes together remain far below the budget.
        self.assertLessEqual(sum(p.stat().st_size for p in SAMPLE.glob("*.json")), 64 * 1024)

    def test_explicit_normalized_dump_extracts_without_mutating_inputs(self):
        path = self.dump(content=(SAMPLE / "recipes.json").read_bytes())
        before = snapshot(self.root)
        data = extract.extract_pack(self.root, "synthetic", [path])
        self.assertEqual(snapshot(self.root), before)
        self.assertTrue(data["coverage"]["runtime_dump"])
        self.assertFalse(data["coverage"]["runtime_registry_complete"])
        self.assertEqual(len(data["recipes"]), 17)
        self.assertEqual(data["recipes"][0]["provenance"][0]["locator"], "/recipes/0")
        self.assertEqual(data["sources"][0]["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        # A dump is never inferred from a filename without an explicit argument.
        omitted = extract.extract_pack(self.root, "synthetic")
        self.assertEqual(omitted["recipes"], [])
        self.assertFalse(omitted["coverage"]["runtime_dump"])

    def test_generated_class_archive_config_language_voltage_and_recipe(self):
        payloads = make_pack(self.root)
        before = snapshot(self.root)
        data = extract.extract_pack(self.root, "synthetic")
        self.assertEqual(snapshot(self.root), before)
        self.assertEqual([(t["id"], t["voltage"], t["recipe_eut"]) for t in data["tiers"]], [("sample:T0", 11, 7), ("sample:T1", 43, 29)])
        self.assertEqual(data["coverage"]["voltage_arrays"], ["V", "VN", "VP"])
        self.assertEqual([(m["family"], m["tier"], m["basis"]) for m in data["machines"]], [("synthetic", "sample:T0", "language-metadata"), ("synthetic.manual", None, "language-metadata")])
        self.assertEqual({f["id"]: f["value"] for f in data["facts"]}, {"config:synthetic.enabled": True, "config:synthetic.parallel": 3, "config:synthetic.ratio": .75, "config:synthetic.label": "Fictional"})
        self.assertEqual(len(data["recipes"]), 1)
        row = data["recipes"][0]
        self.assertEqual((row["basis"], row["duration_ticks"], row["eut"], row["machine"]), ("static-bytecode", 37, 29, "syntheticRecipes"))
        self.assertEqual(row["inputs"], [{"item": "gt:ItemList:SyntheticInput", "amount": 2, "unit": "item"}, {"item": "gt:Materials:SyntheticLiquid:getFluid", "amount": 125, "unit": "mB"}, {"item": "gt:integrated_circuit:4", "amount": 1, "unit": "item", "consumed": False}])
        self.assertEqual(row["outputs"], [{"item": "gt:ItemList:SyntheticOutput", "amount": 3, "unit": "item", "chance": .25}])
        self.assertEqual(data["coverage"]["static_builder_candidates"], 1)
        self.assertEqual(data["coverage"]["static_skips"], {})
        sources = {s["id"]: s for s in data["sources"]}
        self.assertEqual(len(sources), len(payloads))
        for source in sources.values():
            self.assertEqual(source["sha256"], hashlib.sha256(payloads[source["path"]]).hexdigest())
        prov_paths = {sources[p["source"]]["path"] for p in row["provenance"]}
        self.assertTrue(any("TierEU.class" in path for path in prov_paths))
        self.assertTrue(any("GTValues.class" in path for path in prov_paths))
        self.assertTrue(any("SyntheticRecipes.class" in path for path in prov_paths))
        self.assertTrue(all("bytecode:" in p["locator"] for p in row["provenance"]))

    def test_unsupported_branch_handler_or_call_skipped_not_guessed(self):
        for options in ({"prefix": b"\xa7\x00\x03"}, {"exceptions": 1}, {"unsupported": True}):
            with self.subTest(options=options):
                make_pack(self.root, **options)
                data = extract.extract_pack(self.root, "synthetic")
                self.assertEqual(data["recipes"], [])
                self.assertTrue(data["coverage"]["static_skips"])
                self.assertFalse(data["coverage"]["runtime_registry_complete"])

    def test_dump_rejects_malformed_duplicate_fields_and_invalid_recipes(self):
        for content in (b"{", b"\xff", b'{"schema_version":1,"schema_version":1,"recipes":[]}', b'{"schema_version":1,"recipes":[],"bad":NaN}', b'{"schema_version":true,"recipes":[]}', b'{"schema_version":2,"recipes":[]}', b'{"schema_version":1,"recipes":{}}'):
            with self.subTest(content=content):
                path = self.dump(content=content)
                with self.assertRaises(extract.ExtractionError):
                    extract.extract_pack(self.root, "synthetic", [path])
        original = json.loads((SAMPLE / "recipes.json").read_text(encoding="utf-8"))["recipes"][0]
        for field, value in (("basis", "static-bytecode"), ("enabled", 1), ("inputs", []), ("duration_ticks", True), ("eut", -1), ("unknown", 1)):
            with self.subTest(field=field):
                bad = copy.deepcopy(original)
                bad[field] = value
                path = self.dump({"schema_version": 1, "recipes": [bad]})
                with self.assertRaises(extract.ExtractionError):
                    extract.extract_pack(self.root, "synthetic", [path])
        path = self.dump({"schema_version": 1, "recipes": [original, original]})
        with self.assertRaisesRegex(extract.ExtractionError, "duplicate IDs"):
            extract.extract_pack(self.root, "synthetic", [path])

    def test_dump_replaces_untrusted_provenance_and_rejects_duplicate_stacks(self):
        original = json.loads((SAMPLE / "recipes.json").read_text(encoding="utf-8"))["recipes"][0]
        original["provenance"] = [{"source": "untrusted", "locator": "fake"}]
        path = self.dump({"schema_version": 1, "recipes": [original]})
        data = extract.extract_pack(self.root, "synthetic", [path])
        self.assertEqual(data["recipes"][0]["provenance"][0]["locator"], "/recipes/0")
        self.assertNotEqual(data["recipes"][0]["provenance"][0]["source"], "untrusted")
        original["inputs"].append(copy.deepcopy(original["inputs"][0]))
        path = self.dump({"schema_version": 1, "recipes": [original]})
        with self.assertRaisesRegex(extract.ExtractionError, "duplicate stack"):
            extract.extract_pack(self.root, "synthetic", [path])

    def test_dump_outside_root_symlinks_and_multiple_jars_rejected(self):
        outside = self.base / "outside.json"
        outside.write_bytes((SAMPLE / "recipes.json").read_bytes())
        with self.assertRaisesRegex(extract.ExtractionError, "inside pack root"):
            extract.extract_pack(self.root, "synthetic", [outside])
        linked = self.root / "linked.json"
        linked.symlink_to(outside)
        with self.assertRaisesRegex(extract.ExtractionError, "symlink"):
            extract.extract_pack(self.root, "synthetic", [linked])
        alias = self.base / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(extract.ExtractionError, "symlink"):
            extract.extract_pack(alias, "synthetic")
        make_pack(self.root)
        shutil.copyfile(self.root / "mods/gregtech-synthetic.jar", self.root / "mods/gregtech-other.jar")
        with self.assertRaisesRegex(extract.ExtractionError, "multiple gregtech"):
            extract.extract_pack(self.root, "synthetic")

    def test_archive_rejects_traversal_duplicates_symlink_and_size_bounds(self):
        (self.root / "mods").mkdir()
        path = self.root / "mods/gregtech-synthetic.jar"
        for names in (("../escape",), ("/absolute",), ("bad\\name",), ("same", "same")):
            with self.subTest(names=names):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    with zipfile.ZipFile(path, "w") as jar:
                        for name in names:
                            jar.writestr(name, b"synthetic")
                with self.assertRaisesRegex(extract.ExtractionError, "unsafe or duplicate"):
                    extract.extract_pack(self.root, "synthetic")
        entry = zipfile.ZipInfo("symlink")
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr(entry, b"target")
        with self.assertRaises(extract.ExtractionError):
            extract.extract_pack(self.root, "synthetic")
        make_pack(self.root)
        with patch.object(extract, "MAX_MEMBER", 1), self.assertRaisesRegex(extract.ExtractionError, "size or compression"):
            extract.extract_pack(self.root, "synthetic")
        with patch.object(extract, "MAX_TOTAL", 1), self.assertRaisesRegex(extract.ExtractionError, "total selected"):
            extract.extract_pack(self.root, "synthetic")
        path.write_bytes(b"not a zip archive")
        with self.assertRaisesRegex(extract.ExtractionError, "invalid archive"):
            extract.extract_pack(self.root, "synthetic")

    def test_bad_config_and_duplicate_language_fail_explicitly(self):
        make_pack(self.root)
        config = self.root / "config/GregTech/MachineStats.cfg"
        for payload in (b"}\n", b"synthetic {\n", b"S:list <\n"):
            with self.subTest(payload=payload):
                config.write_bytes(payload)
                with self.assertRaises(extract.ExtractionError):
                    extract.extract_pack(self.root, "synthetic")
        engine = extract.Extractor(self.root, "synthetic")
        sid = engine.source("sample.lang", b"synthetic")
        with self.assertRaisesRegex(extract.ExtractionError, "duplicate machine"):
            engine.language(b"gt.blockmachines.synthetic.name=A\ngt.blockmachines.synthetic.name=B\n", sid)

    def test_write_guards_atomic_roundtrip_no_input_mutation_or_temp_leftovers(self):
        path = self.dump(content=(SAMPLE / "recipes.json").read_bytes())
        data = extract.extract_pack(self.root, "synthetic", [path])
        before_data = copy.deepcopy(data)
        before_inputs = snapshot(self.root)
        output = self.base / "store.json"
        extract.write_store(data, output, self.root)
        self.assertEqual(core.load_store(output), data)
        self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
        extract.write_store(data, output, self.root)
        self.assertEqual(core.load_store(output), data)
        self.assertEqual(snapshot(self.root), before_inputs)
        self.assertEqual(data, before_data)
        self.assertEqual(list(self.base.glob(".pack-kb-*")), [])
        for target in (self.root / "new.json", path, self.root):
            with self.subTest(target=target.name), self.assertRaises(extract.ExtractionError):
                extract.write_store(data, target, self.root)
        link = self.base / "link.json"
        link.symlink_to(output)
        with self.assertRaisesRegex(extract.ExtractionError, "symlink"):
            extract.write_store(data, link, self.root)
        hardlink = self.base / "hard.json"
        os.link(output, hardlink)
        with self.assertRaisesRegex(extract.ExtractionError, "hardlinked"):
            extract.write_store(data, hardlink, self.root)
        with patch.object(extract, "MAX_STORE_BYTES", 1), self.assertRaisesRegex(extract.ExtractionError, "output exceeds"):
            extract.write_store(data, self.base / "too-large.json", self.root)
        self.assertFalse((self.base / "too-large.json").exists())
        self.assertEqual(snapshot(self.root), before_inputs)

    def test_atomic_write_failure_cleans_temp_and_preserves_old_target(self):
        output = self.base / "old.json"
        output.write_bytes(b"original target")
        with patch.object(extract.os, "replace", side_effect=OSError("synthetic failure")):
            with self.assertRaises(OSError):
                extract.write_store(store(), output, self.root)
        self.assertEqual(output.read_bytes(), b"original target")
        self.assertEqual(list(self.base.glob(".pack-kb-*")), [])


if __name__ == "__main__":
    unittest.main()
