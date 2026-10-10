# Pack knowledge base handoff

## Built

All changes are isolated to `tools/pack_kb/`:

- Standard-library Python read-only jar/config extractor and bounded JVM
  class-file decoder; no Java execution or game startup.
- Schema-v1 compact JSON store with per-entry relative source paths, SHA-256
  hashes and line/JSON-pointer/bytecode provenance.
- Exact output-recipe, tier-machine and reverse input queries; direct prerequisite
  queries; bounded recipe-chain alternatives, raw-input quantities, reusable
  catalysts and input-deficit bottleneck heuristics.
- Original synthetic data (17 recipes), reproducible sample generation, tests,
  format documentation and a proposed read-only Oracle import/CLI contract.
- Private input/output/evidence directory ignored by git. No Oracle, adapter,
  protocol, write-module, world, service or remote configuration changes.

## Parent verification

Commands run from the repository root:

```sh
python3 -m tools.pack_kb.sample.generate
python3 -m unittest discover -s tools/pack_kb/tests -v
python3 -m tools.pack_kb.extract --pack-root tools/pack_kb/private/pack --pack-version 'GTNH 2.9 beta 3' --output tools/pack_kb/private/store.json
python3 -m tools.pack_kb.private.verify_parent
git diff --check
git check-ignore tools/pack_kb/private/store.json tools/pack_kb/private/pack/mods/gregtech-5.09.54.133.jar tools/pack_kb/private/tests.txt
```

Observed: **63 tests passed**. Real copied-jar extraction: **203 static recipes,
846 machine-language records, 16 tiers, 27 machine-config facts, 66 sources**.
The compact private store is **488,955 bytes**. Every source/member hash matched;
re-extraction was deterministic; local input hashes were unchanged. Parent CLI
checks exercised output recipes, LV machines, reverse needs, requirements and a
chain on real extracted data. The synthetic widget chain resolves into four
alternatives. A real static chain returns quantities and a bottleneck while
correctly reporting unresolved symbolic prerequisites. Parent also inspected the
recipe's actual bytecode registration and TierEU voltage assignment.

Private evidence (not committed): `private/tests.txt`, `private/evidence.txt`,
`private/query-*.json`, `private/assembler-bytecode.txt`, `private/store.json`.
The parent verification script itself is private because it targets the local
copied inputs. The public unittest suite is independently runnable without them.

## Risks and explicit nonclaims

- No ready runtime recipe dump was found in the checked test-copy locations.
  This is a **partial static index**, not all GTNH recipes or the live registry.
  Static enabled flags do not prove runtime availability; unmodelled branches,
  metadata, other mods, recipe-map transformations and multi-release variants
  can change results.
- Machine records come from language metadata, not runtime registrations. Some
  templates/multiblocks have unclassified tiers. No automatic map-to-machine
  registry or overclock/throughput model is present.
- Symbolic ore/GT identities are not canonicalized. Their leaf prerequisites,
  including circuits, remain unresolved. A query result is not an action plan
  authorized for execution.
- The normalized dump import contract is tested, but native NEI formats and a
  real runtime export are not. A complete runtime dump may exceed v1's 16 MiB /
  250,000-node bounds; no silent truncation or large-store support is claimed.
- Linux/Python local verification only. Live Oracle integration, Minecraft
  runtime validation, deployment and protected-world actions were not tested
  or performed. No push or merge is authorized by this handoff.
- An independent builder-side inspection informed fixes; it is not the formal
  workflow review. Formal review remains gated on the owner's decision.
- The requested project-specific skill was unavailable through the skill loader
  and searched skill locations. Roadmap/protocol and the golden workflow were
  read instead; no missing instructions were invented.
- Both required timing-marker invocations were attempted. The helper rejected
  the terminal context as delegated; no guard was bypassed. Early subagent
  ad-hoc checks preceded the attempted marker and are not the final evidence.

## Decisions needed

1. Accept this deliberately partial, source-backed static index as the first
   demo candidate, or require a complete runtime registry before acceptance?
2. If full runtime coverage is required, approve a separate test-copy export
   approach and larger-store design before any game/exporter/config action.
3. Send the committed candidate to independent formal review through the intake.
   Oracle wiring and all deployment remain outside this lane.
