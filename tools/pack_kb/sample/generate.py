"""Rebuild the original synthetic store; never reads a real pack or archive.

Run from the repository root: python3 -m tools.pack_kb.sample.generate
"""
from __future__ import annotations

import copy
import json
from pathlib import Path

from tools.pack_kb.core import validate_store
from tools.pack_kb.extract import extract_pack

HERE = Path(__file__).resolve().parent
VERSION = "synthetic"


def generate(source_dir: Path = HERE) -> dict:
    """Extract recipe provenance, then attach explicit synthetic source metadata."""
    source = source_dir / "recipes.json"
    data = extract_pack(source_dir, VERSION, [source])
    metadata = json.loads(source.read_text(encoding="utf-8"))["synthetic_metadata"]
    sid = next(row["id"] for row in data["sources"] if row["path"] == "recipes.json")
    for kind in ("tiers", "machines", "facts"):
        for index, record in enumerate(metadata[kind]):
            record = copy.deepcopy(record)
            record["provenance"] = [{"source": sid, "locator": f"/synthetic_metadata/{kind}/{index}"}]
            data[kind].append(record)
    data["coverage"]["synthetic"] = True
    data["coverage"]["description"] = "Original fictional examples, not GTNH pack recipes, machine registrations, or voltage values."
    data["coverage"]["counts"] = {kind: len(data[kind]) for kind in ("sources", "tiers", "machines", "recipes", "facts")}
    return validate_store(data)


def main() -> None:
    data = generate()
    # This is a fixture-authoring script, not the extractor's guarded output API.
    # Only its own sibling synthetic store is replaced; pack inputs stay untouched.
    (HERE / "store.json").write_text(json.dumps(data, indent=2, ensure_ascii=True, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({"pack_version": data["pack_version"], "counts": data["coverage"]["counts"]}, sort_keys=True))


if __name__ == "__main__":
    main()
