# Read-only pack knowledge base

Python 3.11+, standard library only. Run from the repository root; no install,
Java runtime, Minecraft launch, network service, or Hermes access is required.
All code and examples in this directory are independent of the Oracle and write
lanes. This is a bounded advisory index, **not the complete live recipe registry**.

## Quick start with original synthetic data

```sh
python3 -m tools.pack_kb --store tools/pack_kb/sample/store.json info
python3 -m tools.pack_kb --store tools/pack_kb/sample/store.json recipes sample:plate
python3 -m tools.pack_kb --store tools/pack_kb/sample/store.json machines sample:T0
python3 -m tools.pack_kb --store tools/pack_kb/sample/store.json needs sample:die
python3 -m tools.pack_kb --store tools/pack_kb/sample/store.json requirements sample:plate
python3 -m tools.pack_kb --store tools/pack_kb/sample/store.json chain sample:widget --amount 5
python3 -m tools.pack_kb --store tools/pack_kb/sample/store.json chain sample:widget --amount 5 --inventory '{"sample:ore":10,"mB:sample:water":250}'
python3 -m unittest discover -s tools/pack_kb/tests -v
```

`needs X` answers **which recipes need X**, including nonconsumed tools.
`requirements X` answers what the recipes producing X need. IDs and tiers are
case-sensitive exact matches, not fuzzy display-name searches. Fluid queries
must include `--unit mB`. Unknown IDs return empty lists. `machines unclassified`
returns records without a decoded tier; these are not necessarily tierless.
Queries include disabled records so absence and explicitly disabled recipes are
not confused. Chains use enabled records only.

The sample is fictional and clearly marked `pack_version=synthetic`; its tier
numbers are not GT values. Rebuild it with `python3 -m tools.pack_kb.sample.generate`.
The tests enforce a combined 64 KiB ceiling on shipped sample JSON. Test archives
and bytecode are generated from original synthetic definitions, not copied jars.

## Read-only extraction and private storage

Place a **copy** of the authorized test pack inputs under
`tools/pack_kb/private/pack/`, keeping their pack-relative layout:

- `mods/gregtech-<version>.jar` (exactly one);
- `config/GregTech/MachineStats.cfg`;
- optionally an explicitly supplied normalized recipe dump.

Do not point the extractor at a production world. It reads only the named config,
one GregTech jar's selected members, and explicit dump arguments; it never walks
world directories, loads classes, evaluates Java, or writes in the input tree.
Full data, input jars, and evidence belong in `tools/pack_kb/private/`, which is
ignored by this directory's `.gitignore`. Do not force-add them to git.

```sh
python3 -m tools.pack_kb.extract --pack-root tools/pack_kb/private/pack --pack-version 'GTNH 2.9 beta 3' --output tools/pack_kb/private/store.json
python3 -m tools.pack_kb --store tools/pack_kb/private/store.json recipes gt:ItemList:Large_Fluid_Cell_Steel
python3 -m tools.pack_kb --store tools/pack_kb/private/store.json machines LV
python3 -m tools.pack_kb --store tools/pack_kb/private/store.json needs ore:plate:Steel
python3 -m tools.pack_kb --store tools/pack_kb/private/store.json chain gt:ItemList:Large_Fluid_Cell_Steel
```

`--pack-version` is an operator-supplied label, not automatic pack attestation.
Hash provenance identifies the actual bytes read, including each jar member.
No absolute source paths are stored. An output must be outside the input tree,
in an existing directory; it is written atomically with mode 0600. Input symlinks,
output symlinks/hardlinks, unsafe archives and ambiguous multiple jars are refused.
Extraction currently uses POSIX file-descriptor guards and is tested on Linux.

### What can be extracted without running the game

- V/VN and VP/VA initializers from `GTValues.class`, including the supported
  BigInteger stream transform. `recipe_eut` uses decoded VP (otherwise VA),
  overridden by a matching decoded `TierEU.RECIPE_<tier>` assignment. These are
  nominal tier facts, not an overclock simulation or machine amperage limits.
- Machine names, families and numeric tier suffixes from the GregTech English
  language member. These are **language metadata**, not a metatile registry:
  templates and multiblocks may have no classified tier, and recipe-map names
  are not automatically mapped to machine names.
- Scalar machine settings from `MachineStats.cfg`, preserving section and line.
- A conservative subset of explicit recipe-builder chains in branch-free
  prefixes of base GregTech recipe-loader `run()` methods. Unsupported calls,
  metadata requirements, loops, branches, NBT, helpers and other mods are not
  approximated. Multi-release Java variants are not selected.

The inspected test jar yielded useful static registrations, but no ready runtime
recipe dump was found in the checked test-pack locations. `basis=static-bytecode`
and `coverage.runtime_registry_complete=false` are intentional. A static
`enabled=true` means the registration expression was decoded, not that the
runtime pack exposes it. Load order, recipe-map transformations and mod presence
can change the actual result. The candidate count covers only inspected prefixes;
it is **not a whole-pack coverage denominator**.

Static IDs are symbolic (`ore:<prefix>:<material>`, `gt:ItemList:<field>`, and
`gt:Materials:<material>:<getter>`). Registry aliases are not collapsed. Symbolic
leaves, including integrated-circuit prerequisites, remain explicitly unresolved;
a real static-store chain can show raw demands and bottlenecks without proving
that the chain is executable. Do not silently convert these to canonical IDs.

### Optional normalized runtime dump

Native NEI exports vary and are **not parsed generically**. The accepted input
contract is [FORMAT.md](FORMAT.md): a versioned JSON object with `recipes`, each
using `basis=runtime-dump`. Supply its full path (relative paths are relative to
the working directory), within the copied input root:

```sh
python3 -m tools.pack_kb.extract --pack-root tools/pack_kb/private/pack --pack-version 'GTNH 2.9 beta 3' --dump tools/pack_kb/private/pack/recipes.json --output tools/pack_kb/private/store.json
```

The label is the producer's claim, not a cryptographic runtime attestation.
Even an empty accepted dump sets `runtime_dump=true`; inspect its recipe count.
Static and dump records remain separate alternatives with distinct bases, and
must have unique IDs. Use a dump-only input root (without a jar) when static
alternatives are unwanted. This release does not generate a live dump: doing so
would require a separately approved test-game action/exporter and normalization.
Malformed dumps, duplicate identities, inconsistent source data, and exceeded
bounds fail explicitly rather than quietly publishing a misleading store.

## Chain semantics and bounds

- Whole-batch ceiling division, exact integer item counts and mB quantities.
- Separate Cartesian alternatives, branch-local cycles, probabilistic outputs
  never counted as guaranteed production, nonconsumed tools not multiplied by
  batches. Reusable raw tools merge by maximum; manufacturing a tool in separate
  branches may overestimate effort. There is no global scheduling optimizer.
- Raw inputs are **leaves in this store**, not necessarily naturally occurring
  resources. Byproducts, surplus reuse, inventory of intermediates, tier gating,
  machine availability and overclocks are not credited or checked.
- Without inventory, bottlenecks are largest raw demand **separately for each
  unit**. With inventory, all ties at maximum `max(0, required-available)/required`
  are reported. This is an input-deficit heuristic, not production throughput.
- `--max-depth` defaults to 32 (maximum 128); `--max-chains` defaults to 32
  (maximum 256); 20,000 expansion/combination work units and 4,096 steps per
  chain bound computation. These are structural bounds, not a wall-clock SLA.
- A store is capped at 16 MiB, 250,000 JSON nodes and depth 64. User-supplied
  quantities are bounded to signed 64-bit nonnegative integers (positive stacks
  and target counts). Derived counts use exact Python integers.
- Selected jar members are capped at 8 MiB each / 64 MiB total; the jar at
  512 MiB. Dumps are read with a 32 MiB ceiling, but the stricter resulting-store
  bounds also apply. **A full GTNH runtime dump may exceed these v1 limits**;
  it must not be silently truncated. Larger-store indexing is not implemented.

An unresolved/truncated plan exits 0 because the query succeeded; inspect
`status`, `truncated`, `unresolved` and `warnings`. Invalid data/I/O exits 2.

## Oracle integration point (proposed, no Oracle files changed)

The read-only tool can import `tools.pack_kb` and call `load_store`,
`recipes_by_output`, `machines_by_tier`, `needs`, `requirements`, or `resolve`.
Pin the store path in trusted host configuration, not in a model-supplied argument.
Alternatively launch the query module with a fixed executable/module/store and
an allowlisted subcommand using an argument list (`shell=False`). Never expose
`extract`, `classfile`, or sample generation to the Oracle; those are offline
operator utilities. Bound response sizes and apply an outer timeout in the host.

For a response, include schema/pack versions, coverage, recipe basis, source
records referenced by entry provenance, and unresolved/truncation warnings.
Resolver steps reference recipe IDs; join them to the store's recipes and sources
for citations. Only treat canonical runtime-dump data as a candidate for concrete
in-game advice, and verify against the current pack. The tool grants no world,
service, board, shell, or Hermes write capability.

## Licensing and publication

Only extractor/query code, documentation, and original synthetic examples belong
in the public repository. The full local index, copied jar/config and disassembled
content are not redistributed. This is not a license grant for pack assets or mod
data: obtain any necessary rights before publishing derived records. Hashes and
relative provenance are for private reproducibility, not permission to redistribute.
