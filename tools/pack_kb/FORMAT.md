# Pack knowledge store v1

Standard-library Python only. Data is advisory and read-only; never a world action.
The JSON document has `schema_version: 1`, `pack_version` (string), `coverage`
(object describing sources and limitations), and arrays `sources`, `tiers`,
`machines`, `recipes`, `facts`.

- sources: `{id, path, sha256}`. Paths are pack-relative, never host paths;
  jar entries use `mods/file.jar!/entry`. Hash is the bytes identified by path.
- Each tier/machine/recipe/fact has unique `id` within its kind and `provenance`:
  `[{source: source_id, locator: string}]` (line, JSON pointer, or bytecode offset).
- tiers: `{id, index, voltage, recipe_eut, provenance}`; index/voltages integers.
- machines: `{id, name, tier, family, basis, provenance}`. Tier is a tier id or
  null; basis states whether this is language metadata, registry, or dump data.
- recipes: `{id, machine, inputs, outputs, duration_ticks, eut, enabled,
  basis, provenance}`. Machine is a recipe-map/family string (not necessarily
  a machine id); duration/eut are nonnegative integers or null. enabled boolean.
- stacks: `{item, amount, unit, consumed?, chance?}`. item string (canonical ID
  or explicitly symbolic `ore:`/`gt:` ID); amount positive integer; unit `item`
  or `mB`. consumed defaults true; chance defaults 1 (0 < chance <= 1).
  Zero-size GT catalysts must be represented as amount 1, consumed false.
- facts: `{id, category, value, provenance}`. Value is a JSON scalar.

`basis` distinguishes `runtime-dump`, `static-bytecode`, and `language-metadata`.
Static registrations are not proof of the final enabled runtime recipe set.
Never infer runtime recipes from names or claim static extraction is complete.
Unsupported static bytecode expressions are skipped with coverage warnings, not
guessed. Malformed/ambiguous data (duplicate IDs, stacks or source paths, invalid
schema or archives) fails extraction; it is never quietly published as valid.

Query API (core.py): `load_store(path)`, `validate_store(data)`,
`recipes_by_output(data, item, unit='item')`, `machines_by_tier(data, tier)`,
`needs(data, item, unit='item')` (reverse input lookup),
`requirements(data, item, unit='item')` (output prerequisites),
`resolve(data, target, amount=1, unit='item',
max_depth=32, max_chains=32, inventory=None)`.
Query identity is exact item + unit. Alternative recipes form separate chains;
cycles, probabilistic outputs, unsupported prerequisites and bounds are explicit
unresolved results. Byproducts do not reduce other branches' demand. Quantities
round to whole batches. Catalysts are required but not consumed. Raw inputs mean
leaves in this store, not proof that a resource occurs naturally.
Bottleneck is the largest inventory deficit ratio when inventory is supplied,
otherwise largest raw demand separately per unit (never compare items with mB).
This is a planning heuristic, not a throughput estimate.

CLI: `python3 -m tools.pack_kb --store PATH recipes ITEM`, `machines TIER`,
`needs ITEM`, `requirements ITEM`, `chain ITEM --amount N`, `info`.
Item commands accept `--unit item|mB`; chains also accept `--max-depth`,
`--max-chains` and `--inventory JSON`. Inventory keys are item IDs or `mB:` +
fluid ID. `machines unclassified` selects null-tier metadata.
Symbolic ore:/gt: leaves remain unresolved (including circuits); raw-demand
results are still provided, but do not prove executability. See README for the
structural/file bounds and unsupported runtime export formats.
Extractor CLI: `python3 -m tools.pack_kb.extract --pack-root PATH
--pack-version VERSION --output PATH [--dump PATH ...]`.
Dump input uses `{schema_version: 1, recipes: [...]}` and the recipe fields
above, except provenance is generated from the dump file and JSON pointer.
Only explicitly supplied dumps are read. Full stores and input jars go in
`tools/pack_kb/private/` (gitignored); only small original synthetic fixtures ship.
