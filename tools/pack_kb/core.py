"""Validated, read-only pack knowledge and bounded advisory recipe planning.

All public queries validate their input and return detached JSON-compatible data.
``validate_store`` returns its input on success and raises ``ValidationError`` on
failure. Query identity is (item, unit); item queries default to the ``item`` unit.
Inventory keys are item IDs for items and ``'mB:' + item`` for fluids. Inventory
only scores deficits; it does not change recipe expansion or credit byproducts.
The ``mB:`` prefix is reserved for fluid inventory keys; item-unit IDs using
that prefix cannot be scored without ambiguity and are rejected when needed.

Resolution enumerates separate recipe choices, including Cartesian choices for
inputs. It is not a scheduler, machine availability check, or runtime verifier.
Raw leaves mean only that this store has no producing recipe. Symbolic ore:/gt:
leaves remain explicitly unresolved. Reusable leaf catalyst quantities merge by
maximum, not by batch count; manufactured prerequisites are expanded separately
in each branch, without cross-branch reuse or byproduct credit.
"""

from __future__ import annotations

import copy
import json
import math
import re
from fractions import Fraction
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
MAX_STORE_BYTES = 16 * 1024 * 1024
MAX_JSON_DEPTH = 64
MAX_JSON_NODES = 250_000
MAX_DEPTH = 128
MAX_CHAINS = 256
MAX_RESOLUTION_WORK = 20_000
MAX_CHAIN_STEPS = 4096
_BASES = frozenset({"runtime-dump", "static-bytecode", "language-metadata"})
_UNITS = frozenset({"item", "mB"})


class ValidationError(ValueError):
    """The store, query, or inventory violates the documented contract."""


StoreValidationError = ValidationError


def _fail(where: str, message: str) -> None:
    raise ValidationError(f"{where}: {message}")


def _string(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value.strip():
        _fail(where, "expected a nonempty string")
    if any(ord(c) < 32 for c in value):
        _fail(where, "control characters are not allowed in identifiers")
    return value


def _integer(value: Any, where: str, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        _fail(where, f"expected an integer >= {minimum} (not a boolean)")
    if value > 2**63 - 1:
        _fail(where, "integer exceeds signed 64-bit bound")
    return value


def _object(value: Any, where: str, required: set[str], optional: set[str] | None = None) -> dict:
    if type(value) is not dict:
        _fail(where, "expected an object")
    missing = required - value.keys()
    extra = value.keys() - required - (optional or set())
    if missing:
        _fail(where, f"missing fields: {', '.join(sorted(missing))}")
    if extra:
        _fail(where, f"unknown fields: {', '.join(sorted(map(str, extra)))}")
    return value


def _array(value: Any, where: str) -> list:
    if type(value) is not list:
        _fail(where, "expected an array")
    return value


def _json_value(value: Any, where: str) -> None:
    # Iterative inspection also bounds hostile in-memory inputs and rejects
    # cyclic containers, non-JSON values, and nonfinite numbers in coverage.
    pending = [(value, 0, False)]
    active: set[int] = set()
    count = 0
    while pending:
        current, depth, leaving = pending.pop()
        if leaving:
            active.remove(id(current))
            continue
        count += 1
        if count > MAX_JSON_NODES:
            _fail(where, f"JSON node limit exceeded ({MAX_JSON_NODES})")
        if depth > MAX_JSON_DEPTH:
            _fail(where, f"JSON nesting limit exceeded ({MAX_JSON_DEPTH})")
        if current is None or type(current) in (str, bool, int):
            continue
        if type(current) is float:
            if not math.isfinite(current):
                _fail(where, "nonfinite numbers are not JSON values")
            continue
        if type(current) not in (list, dict):
            _fail(where, f"not a JSON value: {type(current).__name__}")
        if id(current) in active:
            _fail(where, "cyclic JSON container")
        if count + len(pending) + len(current) > MAX_JSON_NODES:
            _fail(where, f"JSON node limit exceeded ({MAX_JSON_NODES})")
        active.add(id(current))
        pending.append((current, depth, True))
        if type(current) is dict:
            if any(type(key) is not str for key in current):
                _fail(where, "object keys must be strings")
            children = current.values()
        else:
            children = current
        pending.extend((child, depth + 1, False) for child in children)


def _source_path(value: Any, where: str) -> None:
    path = _string(value, where)
    if "\\" in path or ":" in path or path.startswith(("/", "~")):
        _fail(where, "expected a pack-relative POSIX path, not a host path or URL")
    pieces = path.split("!/")
    if len(pieces) > 2 or any("!" in piece for piece in pieces):
        _fail(where, "invalid archive-entry path")
    for piece in pieces:
        if not piece or any(part in ("", ".", "..") for part in piece.split("/")):
            _fail(where, "empty, absolute, or traversing path component")
    if len(pieces) == 2 and not pieces[0].lower().endswith(".jar"):
        _fail(where, "archive-entry paths must identify a jar")


def _provenance(value: Any, where: str, sources: set[str]) -> None:
    entries = _array(value, where)
    if not entries:
        _fail(where, "at least one provenance entry is required")
    seen = set()
    for index, entry in enumerate(entries):
        loc = f"{where}[{index}]"
        _object(entry, loc, {"source", "locator"})
        source = _string(entry["source"], loc + ".source")
        locator = _string(entry["locator"], loc + ".locator")
        if source not in sources:
            _fail(loc + ".source", f"unknown source {source!r}")
        pair = (source, locator)
        if pair in seen:
            _fail(loc, "duplicate provenance entry")
        seen.add(pair)


def _stacks(value: Any, where: str) -> None:
    seen = set()
    for index, stack in enumerate(_array(value, where)):
        loc = f"{where}[{index}]"
        _object(stack, loc, {"item", "amount", "unit"}, {"consumed", "chance"})
        item = _string(stack["item"], loc + ".item")
        _integer(stack["amount"], loc + ".amount", 1)
        unit = _string(stack["unit"], loc + ".unit")
        if unit not in _UNITS:
            _fail(loc + ".unit", "expected item or mB")
        consumed = stack.get("consumed", True)
        if type(consumed) is not bool:
            _fail(loc + ".consumed", "expected a boolean")
        chance = stack.get("chance", 1)
        if type(chance) not in (int, float) or not 0 < chance <= 1:
            _fail(loc + ".chance", "expected a finite number with 0 < chance <= 1")
        # NaN fails the comparison above, infinities fail its bounds.
        identity = (item, unit, consumed, chance)
        if identity in seen:
            _fail(loc, "duplicate stack identity; aggregate its amount explicitly")
        seen.add(identity)


def validate_store(data: Any) -> dict:
    """Strictly validate schema v1; return data unchanged or raise ValidationError.

    Unknown schema fields, duplicate IDs/source paths/tier indices/stacks and
    provenance pairs are rejected. Coverage is an arbitrary bounded JSON object;
    fact values are JSON scalars. Stack identity includes unit, consumed, chance.
    """
    _json_value(data, "store")
    _object(data, "store", {"schema_version", "pack_version", "coverage", "sources", "tiers", "machines", "recipes", "facts"})
    if type(data["schema_version"]) is not int or data["schema_version"] != SCHEMA_VERSION:
        _fail("schema_version", f"only schema version {SCHEMA_VERSION} is supported")
    _string(data["pack_version"], "pack_version")
    if type(data["coverage"]) is not dict:
        _fail("coverage", "expected an object")
    identifiers: dict[str, set[str]] = {}
    for kind in ("sources", "tiers", "machines", "recipes", "facts"):
        identifiers[kind] = set()
        for index, record in enumerate(_array(data[kind], kind)):
            loc = f"{kind}[{index}]"
            if type(record) is not dict:
                _fail(loc, "expected an object")
            identifier = _string(record.get("id"), loc + ".id")
            if identifier in identifiers[kind]:
                _fail(loc + ".id", f"duplicate ID {identifier!r}")
            identifiers[kind].add(identifier)
    paths = set()
    for index, record in enumerate(data["sources"]):
        loc = f"sources[{index}]"
        _object(record, loc, {"id", "path", "sha256"})
        _source_path(record["path"], loc + ".path")
        if record["path"] in paths:
            _fail(loc + ".path", "duplicate source path")
        paths.add(record["path"])
        if not isinstance(record["sha256"], str) or not re.fullmatch(r"[0-9a-fA-F]{64}", record["sha256"]):
            _fail(loc + ".sha256", "expected a 64-character SHA-256 hex digest")
    indices = set()
    fields = {
        "tiers": {"id", "index", "voltage", "recipe_eut", "provenance"},
        "machines": {"id", "name", "tier", "family", "basis", "provenance"},
        "recipes": {"id", "machine", "inputs", "outputs", "duration_ticks", "eut", "enabled", "basis", "provenance"},
        "facts": {"id", "category", "value", "provenance"},
    }
    for kind in ("tiers", "machines", "recipes", "facts"):
        for index, record in enumerate(data[kind]):
            loc = f"{kind}[{index}]"
            _object(record, loc, fields[kind])
            _provenance(record["provenance"], loc + ".provenance", identifiers["sources"])
            if kind == "tiers":
                for field in ("index", "voltage", "recipe_eut"):
                    _integer(record[field], loc + "." + field)
                if record["index"] in indices:
                    _fail(loc + ".index", "duplicate tier index")
                indices.add(record["index"])
            elif kind == "machines":
                for field in ("name", "family", "basis"):
                    _string(record[field], loc + "." + field)
                if record["basis"] not in _BASES:
                    _fail(loc + ".basis", "unknown basis")
                tier = record["tier"]
                if tier is not None:
                    _string(tier, loc + ".tier")
                    if tier not in identifiers["tiers"]:
                        _fail(loc + ".tier", f"unknown tier {tier!r}")
            elif kind == "recipes":
                _string(record["machine"], loc + ".machine")
                _string(record["basis"], loc + ".basis")
                if record["basis"] not in _BASES:
                    _fail(loc + ".basis", "unknown basis")
                if type(record["enabled"]) is not bool:
                    _fail(loc + ".enabled", "expected a boolean")
                for field in ("duration_ticks", "eut"):
                    if record[field] is not None:
                        _integer(record[field], loc + "." + field)
                for field in ("inputs", "outputs"):
                    _stacks(record[field], loc + "." + field)
                if not record["outputs"]:
                    _fail(loc + ".outputs", "a recipe needs at least one output")
            else:
                _string(record["category"], loc + ".category")
                if type(record["value"]) not in (str, int, float, bool, type(None)):
                    _fail(loc + ".value", "expected a JSON scalar")
    return data


def _unique_object(pairs: list[tuple[str, Any]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            _fail("JSON", f"duplicate object key {key!r}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    _fail("JSON", f"nonfinite numeric constant {value!r}")


def load_store(path: str | Path, *, max_bytes: int = MAX_STORE_BYTES) -> dict:
    """Load a UTF-8 JSON file with a hard byte cap and strict schema validation.

    A caller may lower, but not raise, the 16 MiB cap. No provenance source files
    are opened. Filesystem errors retain their OSError type.
    """
    _integer(max_bytes, "max_bytes", 1)
    if max_bytes > MAX_STORE_BYTES:
        _fail("max_bytes", f"cannot exceed {MAX_STORE_BYTES}")
    with Path(path).open("rb") as stream:
        content = stream.read(max_bytes + 1)
    if len(content) > max_bytes:
        _fail("store", f"file exceeds the {max_bytes}-byte load limit")
    try:
        data = json.loads(content.decode("utf-8"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise ValidationError(f"store: invalid UTF-8 JSON ({exc})") from exc
    except ValueError as exc:
        if isinstance(exc, ValidationError):
            raise
        raise ValidationError(f"store: invalid JSON number ({exc})") from exc
    return validate_store(data)


def _identity(item: Any, unit: Any) -> tuple[str, str]:
    _string(item, "item")
    _string(unit, "unit")
    if unit not in _UNITS:
        _fail("unit", "expected item or mB")
    return item, unit


def _matching(data: dict, item: str, unit: str) -> list[dict]:
    return [recipe for recipe in data["recipes"] if any((stack["item"], stack["unit"]) == (item, unit) for stack in recipe["outputs"])]


def recipes_by_output(data: dict, item: str, unit: str = "item") -> list[dict]:
    """Return matching complete recipes, including disabled/stochastic records."""
    validate_store(data)
    _identity(item, unit)
    return copy.deepcopy(_matching(data, item, unit))


def machines_by_tier(data: dict, tier: str | None) -> list[dict]:
    """Match an exact tier ID (or None for explicitly unclassified machines)."""
    validate_store(data)
    if tier is not None:
        _string(tier, "tier")
    return copy.deepcopy([machine for machine in data["machines"] if machine["tier"] == tier])


def needs(data: dict, item: str, unit: str = "item") -> list[dict]:
    """Reverse lookup: which recipes need X as an input (including catalysts)?"""
    validate_store(data)
    _identity(item, unit)
    return copy.deepcopy([recipe for recipe in data["recipes"]
                          if any((stack["item"], stack["unit"]) == (item, unit)
                                 for stack in recipe["inputs"])])


def requirements(data: dict, item: str, unit: str = "item") -> list[dict]:
    """Direct prerequisites, one record per matching recipe; not a merged demand.

    ``inputs`` retain all stacks, while ``consumed_inputs`` and ``catalysts``
    partition them. No stochastic output is called guaranteed production.
    """
    validate_store(data)
    _identity(item, unit)
    result = []
    for recipe in _matching(data, item, unit):
        outputs = [stack for stack in recipe["outputs"] if (stack["item"], stack["unit"]) == (item, unit)]
        result.append({
            "recipe_id": recipe["id"], "machine": recipe["machine"],
            "enabled": recipe["enabled"], "basis": recipe["basis"],
            "inputs": copy.deepcopy(recipe["inputs"]),
            "consumed_inputs": copy.deepcopy([stack for stack in recipe["inputs"] if stack.get("consumed", True)]),
            "catalysts": copy.deepcopy([stack for stack in recipe["inputs"] if not stack.get("consumed", True)]),
            "guaranteed_output_per_batch": sum(stack["amount"] for stack in outputs if stack.get("chance", 1) == 1),
            "outputs": copy.deepcopy(recipe["outputs"]),
            "duration_ticks": recipe["duration_ticks"], "eut": recipe["eut"],
            "provenance": copy.deepcopy(recipe["provenance"]),
        })
    return result


def _empty_plan() -> dict:
    return {"steps": [], "raw": {}, "reusable_raw": {}, "catalysts": {}, "unresolved": [], "warnings": []}


def _problem(item: str, amount: int, unit: str, reason: str, **extra: Any) -> dict:
    return {"item": item, "amount": amount, "unit": unit, "reason": reason, **extra}


def _combine(left: dict, right: dict) -> dict:
    result = _empty_plan()
    result["steps"] = left["steps"] + right["steps"]
    for field in ("raw", "reusable_raw", "catalysts"):
        result[field] = left[field].copy()
        for key, amount in right[field].items():
            if field == "raw":
                result[field][key] = result[field].get(key, 0) + amount
            else:
                result[field][key] = max(result[field].get(key, 0), amount)
    result["unresolved"] = left["unresolved"] + right["unresolved"]
    result["warnings"] = left["warnings"] + right["warnings"]
    return result


def _plan_key(plan: dict) -> tuple:
    # Different recipe choices remain distinct even with identical raw demands.
    return (
        tuple((step["recipe_id"], step["item"], step["unit"], step["amount"], step["batches"], step["depth"], step["consumed"]) for step in plan["steps"]),
        tuple(sorted(plan["raw"].items())), tuple(sorted(plan["reusable_raw"].items())),
        tuple(sorted(plan["catalysts"].items())),
        tuple(json.dumps(problem, sort_keys=True) for problem in plan["unresolved"]),
    )


def _stack_list(amounts: dict, consumed: bool) -> list[dict]:
    return [{"item": item, "unit": unit, "amount": amount, "consumed": consumed} for (item, unit), amount in sorted(amounts.items())]


def _inventory(inventory: Any) -> dict | None:
    if inventory is None:
        return None
    if type(inventory) is not dict:
        _fail("inventory", "expected an object mapping item IDs / mB: IDs to amounts")
    for key, amount in inventory.items():
        _string(key, "inventory key")
        if key.startswith("mB:"):
            _string(key[3:], "inventory fluid item")
        _integer(amount, f"inventory[{key!r}]")
    return inventory.copy()


def _bottleneck(plan: dict, inventory: dict | None) -> dict:
    required = plan["raw"].copy()
    for key, amount in plan["reusable_raw"].items():
        required[key] = required.get(key, 0) + amount
    if inventory is None:
        by_unit = {}
        for unit in ("item", "mB"):
            choices = [(key, amount) for key, amount in sorted(required.items()) if key[1] == unit]
            if choices:
                (item, _), amount = max(choices, key=lambda entry: entry[1])
                by_unit[unit] = {"item": item, "unit": unit, "amount": amount}
            else:
                by_unit[unit] = None
        return {"method": "largest_raw_demand_per_unit", "by_unit": by_unit}
    deficits = []
    for (item, unit), amount in sorted(required.items()):
        key = item if unit == "item" else "mB:" + item
        if unit == "item" and item.startswith("mB:"):
            _fail("inventory", "mB: is reserved for fluid keys; an item ID using that prefix is ambiguous")
        available = inventory.get(key, 0)
        deficit = max(0, amount - available)
        ratio = Fraction(deficit, amount)
        deficits.append((ratio, {"item": item, "unit": unit, "required": amount, "available": available,
                                 "deficit": deficit, "deficit_ratio": float(ratio),
                                 "deficit_ratio_exact": {"numerator": ratio.numerator, "denominator": ratio.denominator}}))
    largest = max((ratio for ratio, _ in deficits), default=Fraction(0))
    return {"method": "largest_inventory_deficit_ratio", "definition": "max(0, required - available) / required",
            "bottlenecks": [entry for ratio, entry in deficits if ratio == largest and ratio > 0],
            "deficits": [entry for _, entry in deficits]}


def resolve(data: dict, target: str, amount: int = 1, unit: str = "item", max_depth: int = 32,
            max_chains: int = 32, inventory: dict | None = None) -> dict:
    """Enumerate bounded, detached advisory chains for an exact target identity.

    Caps: depth <= 128, chains <= 256, 20,000 expansion/combination work units,
    4,096 steps per chain. Depth is zero-based; max_depth=0 prevents expansion
    of even a root recipe, but permits raw leaves. Every intermediate Cartesian
    result also respects max_chains. Truncation is reported globally, not hidden
    by a successfully resolved retained chain. Store order defines alternative
    order. Cycles are branch-local and unit-sensitive.

    Return keys: target, chains, status, truncated, truncation_reasons, limits,
    warnings. Each chain has steps, raw_inputs (including nonconsumed leaves),
    catalysts, unresolved, warnings, bottleneck, status. Stochastic-only recipes
    are separate unresolved alternatives and are never credited as production.
    Steps reference recipe IDs rather than copying large output/provenance
    arrays; use recipes_by_output to inspect the underlying source records.
    """
    validate_store(data)
    _identity(target, unit)
    _integer(amount, "amount", 1)
    _integer(max_depth, "max_depth")
    _integer(max_chains, "max_chains", 1)
    if max_depth > MAX_DEPTH:
        _fail("max_depth", f"cannot exceed {MAX_DEPTH}")
    if max_chains > MAX_CHAINS:
        _fail("max_chains", f"cannot exceed {MAX_CHAINS}")
    inventory = _inventory(inventory)
    index: dict[tuple[str, str], list[dict]] = {}
    for recipe in data["recipes"]:
        for identity in dict.fromkeys((stack["item"], stack["unit"]) for stack in recipe["outputs"]):
            index.setdefault(identity, []).append(recipe)
    work = 0
    truncation: set[str] = set()

    def spend() -> bool:
        nonlocal work
        if work >= MAX_RESOLUTION_WORK:
            truncation.add("work_limit")
            return False
        work += 1
        return True

    def append_distinct(plans: list[dict], seen: set, plan: dict) -> bool:
        signature = _plan_key(plan)
        if signature in seen:
            return True
        if len(plans) >= max_chains:
            truncation.add("max_chains")
            return False
        seen.add(signature)
        plans.append(plan)
        return True

    def stopped(item: str, count: int, kind: str, reason: str, consumed: bool, **extra: Any) -> dict:
        plan = _empty_plan()
        plan["unresolved"].append(_problem(item, count, kind, reason, **extra))
        if not consumed:
            plan["catalysts"][(item, kind)] = count
        return plan

    def expand(item: str, count: int, kind: str, depth: int, ancestors: tuple,
               consumed: bool = True) -> list[dict]:
        identity = (item, kind)
        if not spend():
            return [stopped(item, count, kind, "work_limit", consumed)]
        if identity in ancestors:
            cycle = [{"item": name, "unit": measure} for name, measure in ancestors[ancestors.index(identity):] + (identity,)]
            return [stopped(item, count, kind, "cycle", consumed, cycle=cycle)]
        candidates = index.get(identity, [])
        if not candidates:
            plan = _empty_plan()
            field = "raw" if consumed else "reusable_raw"
            plan[field][identity] = count
            if not consumed:
                plan["catalysts"][identity] = count
            if item.startswith(("ore:", "gt:")):
                plan["unresolved"].append(_problem(item, count, kind, "unsupported_prerequisite", detail="symbolic identity has no producing recipe in this store"))
            return [plan]
        enabled = [recipe for recipe in candidates if recipe["enabled"]]
        if not enabled:
            return [stopped(item, count, kind, "disabled_recipes", consumed, recipe_ids=[recipe["id"] for recipe in candidates])]
        if depth >= max_depth:
            truncation.add("max_depth")
            return [stopped(item, count, kind, "max_depth", consumed, depth=depth)]
        alternatives: list[dict] = []
        seen_alternatives: set = set()
        for recipe in enabled:
            if not spend():
                append_distinct(alternatives, seen_alternatives, stopped(item, count, kind, "work_limit", consumed))
                break
            matching = [stack for stack in recipe["outputs"] if (stack["item"], stack["unit"]) == identity]
            per_batch = sum(stack["amount"] for stack in matching if stack.get("chance", 1) == 1)
            if not per_batch:
                plan = stopped(item, count, kind, "probabilistic_output", consumed,
                               recipe_id=recipe["id"], probabilistic_output_count=len(matching),
                               detail="no guaranteed output; see the referenced recipe for chances")
                if not append_distinct(alternatives, seen_alternatives, plan):
                    break
                continue
            batches = (count + per_batch - 1) // per_batch
            base = _empty_plan()
            base["steps"].append({
                "recipe_id": recipe["id"], "item": item, "unit": kind, "amount": count,
                "consumed": consumed, "depth": depth, "batches": batches,
                "guaranteed_output_per_batch": per_batch, "produced_amount": batches * per_batch,
                "surplus": batches * per_batch - count, "machine": recipe["machine"],
                "duration_ticks": recipe["duration_ticks"], "eut": recipe["eut"],
                "basis": recipe["basis"],
            })
            if not consumed:
                base["catalysts"][identity] = count
            if recipe["basis"] != "runtime-dump":
                base["warnings"].append({"reason": "not_runtime_verified", "recipe_id": recipe["id"], "basis": recipe["basis"]})
            for field in ("duration_ticks", "eut"):
                if recipe[field] is None:
                    base["warnings"].append({"reason": "unknown_" + field, "recipe_id": recipe["id"]})
            if any(stack.get("chance", 1) < 1 for stack in recipe["outputs"]):
                base["warnings"].append({"reason": "stochastic_outputs_not_credited", "recipe_id": recipe["id"]})
            partial = [base]
            for stack in recipe["inputs"]:
                used = stack.get("consumed", True)
                needed = stack["amount"] * batches if used else stack["amount"]
                if stack.get("chance", 1) != 1:
                    children = [stopped(stack["item"], needed, stack["unit"], "unsupported_prerequisite", used,
                                        recipe_id=recipe["id"], detail="probabilistic input consumption is not modelled")]
                else:
                    children = expand(stack["item"], needed, stack["unit"], depth + 1, ancestors + (identity,), used)
                combined: list[dict] = []
                seen_combined: set = set()
                full = False
                for left in partial:
                    for right in children:
                        if not spend():
                            limited = _combine(left, stopped(item, count, kind, "work_limit", consumed))
                            append_distinct(combined, seen_combined, limited)
                            full = True
                            break
                        if len(left["steps"]) + len(right["steps"]) > MAX_CHAIN_STEPS:
                            truncation.add("step_limit")
                            plan = _combine(left, stopped(stack["item"], needed, stack["unit"], "step_limit", used))
                        else:
                            plan = _combine(left, right)
                        if not append_distinct(combined, seen_combined, plan):
                            full = True
                            break
                    if full:
                        break
                partial = combined
                if "work_limit" in truncation:
                    # No later inputs are silently interpreted as satisfied.
                    for plan in partial:
                        plan["unresolved"].append(_problem(item, count, kind, "work_limit", recipe_id=recipe["id"], detail="remaining prerequisites were not expanded"))
                    break
            full = False
            for plan in partial:
                if not append_distinct(alternatives, seen_alternatives, plan):
                    full = True
                    break
            if full or "work_limit" in truncation:
                break
        return alternatives or [stopped(item, count, kind, "work_limit", consumed)]

    plans = expand(target, amount, unit, 0, ())
    chains = []
    for plan in plans:
        # Keep warnings unique without erasing distinct unresolved branches.
        warnings = list({json.dumps(warning, sort_keys=True): warning for warning in plan["warnings"]}.values())
        chains.append({
            "status": "unresolved" if plan["unresolved"] else "resolved",
            "steps": copy.deepcopy(plan["steps"]),
            "raw_inputs": _stack_list(plan["raw"], True) + _stack_list(plan["reusable_raw"], False),
            "catalysts": _stack_list(plan["catalysts"], False),
            "unresolved": copy.deepcopy(plan["unresolved"]), "warnings": copy.deepcopy(warnings),
            "bottleneck": _bottleneck(plan, inventory),
        })
    if truncation:
        status = "truncated"
    elif all(chain["status"] == "resolved" for chain in chains):
        status = "resolved"
    elif any(chain["status"] == "resolved" for chain in chains):
        status = "partial"
    else:
        status = "unresolved"
    return {
        "target": {"item": target, "amount": amount, "unit": unit},
        "status": status, "truncated": bool(truncation),
        "truncation_reasons": sorted(truncation), "chains": chains,
        "limits": {"max_depth": max_depth, "max_chains": max_chains,
                   "max_work": MAX_RESOLUTION_WORK, "work_used": work,
                   "max_steps_per_chain": MAX_CHAIN_STEPS},
        "warnings": ["Advisory store expansion, not proof of the final runtime recipe set.",
                     "Raw inputs are store leaves, not proof of natural resource availability.",
                     "Inventory scores raw deficits only; byproducts and shared intermediate production are not credited.",
                     "Machine availability, tier gating, and power constraints are not checked.",
                     "Bottleneck is a planning heuristic, not a throughput estimate."],
    }
