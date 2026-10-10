"""Read-only JSON CLI: python3 -m tools.pack_kb --store PATH COMMAND."""

from __future__ import annotations

import argparse
import json
import sys
from typing import Sequence

from . import core


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Query advisory, provenance-backed pack knowledge; never a world action.")
    parser.add_argument("--store", required=True, metavar="PATH", help="UTF-8 schema-v1 store (at most 16 MiB)")
    commands = parser.add_subparsers(dest="command", required=True)
    for name, help_text in (("recipes", "Find output recipes, including disabled and stochastic records"),
                            ("needs", "Reverse lookup: which recipes need this input"),
                            ("requirements", "List direct prerequisites for an output")):
        command = commands.add_parser(name, help=help_text)
        command.add_argument("item", metavar="ITEM")
        command.add_argument("--unit", choices=("item", "mB"), default="item", help="Exact identity unit (default: item)")
    machines = commands.add_parser("machines", help="Find machines by exact tier ID")
    machines.add_argument("tier", metavar="TIER", help="Exact tier ID, or 'unclassified' for no decoded tier")
    chain = commands.add_parser("chain", help="Expand bounded recipe alternatives; no stochastic production guarantee")
    chain.add_argument("item", metavar="ITEM")
    chain.add_argument("--amount", type=int, default=1, metavar="N")
    chain.add_argument("--unit", choices=("item", "mB"), default="item")
    chain.add_argument("--max-depth", type=int, default=32, metavar="N", help="Depth cap (0..128, default: 32)")
    chain.add_argument("--max-chains", type=int, default=32, metavar="N", help="Alternative cap (1..256, default: 32)")
    chain.add_argument("--inventory", metavar="JSON", help='Inline JSON amounts: item IDs for items, "mB:" + item IDs for fluids; scoring only')
    commands.add_parser("info", help="Show store coverage, counts and planner limits")
    return parser


def _parse_inventory(text: str | None) -> dict | None:
    if text is None:
        return None
    if len(text.encode("utf-8")) > core.MAX_STORE_BYTES:
        raise core.ValidationError("inventory: inline JSON exceeds the byte limit")
    try:
        value = json.loads(text, object_pairs_hook=core._unique_object, parse_constant=core._reject_constant)
    except (ValueError, RecursionError) as exc:
        raise core.ValidationError(f"inventory: invalid JSON ({exc})") from exc
    core._json_value(value, "inventory")
    return core._inventory(value)


def main(argv: Sequence[str] | None = None) -> int:
    """Return 0 for successful queries, 2 for invalid input/I/O errors.

    An unresolved or truncated advisory plan is still a successful query; its
    machine-readable status is in the result. Operational errors are JSON on
    stderr. Argument-parser usage errors retain argparse's standard formatting.
    """
    args = _parser().parse_args(argv)
    try:
        store = core.load_store(args.store)
        if args.command == "recipes":
            result = core.recipes_by_output(store, args.item, args.unit)
        elif args.command == "machines":
            result = core.machines_by_tier(store, None if args.tier == "unclassified" else args.tier)
        elif args.command == "needs":
            result = core.needs(store, args.item, args.unit)
        elif args.command == "requirements":
            result = core.requirements(store, args.item, args.unit)
        elif args.command == "chain":
            result = core.resolve(store, args.item, args.amount, args.unit, args.max_depth,
                                  args.max_chains, _parse_inventory(args.inventory))
        else:
            result = {
                "schema_version": store["schema_version"], "pack_version": store["pack_version"],
                "coverage": store["coverage"], "sources": store["sources"],
                "counts": {kind: len(store[kind]) for kind in ("sources", "tiers", "machines", "recipes", "facts")},
                "limits": {"max_store_bytes": core.MAX_STORE_BYTES, "max_depth": core.MAX_DEPTH,
                           "max_chains": core.MAX_CHAINS, "max_work": core.MAX_RESOLUTION_WORK,
                           "max_steps_per_chain": core.MAX_CHAIN_STEPS},
                "inventory_keys": {"item": "item ID", "mB": "mB: + item ID"},
                "advisory": True,
                "warnings": ["Static registrations are not proof of the final enabled runtime recipe set.",
                             "Coverage describes only the supplied sources; completeness is not inferred."],
            }
        output = json.dumps(result, ensure_ascii=True, allow_nan=False, indent=2)
        sys.stdout.write(output + "\n")
        return 0
    except (OSError, ValueError, RecursionError) as exc:
        sys.stderr.write(json.dumps({"error": str(exc), "error_type": type(exc).__name__}, ensure_ascii=True, allow_nan=False) + "\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
