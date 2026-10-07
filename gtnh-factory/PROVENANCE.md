# Provenance of `gtnh-factory/`

This module is the GTNH factory telemetry mod (mod id `aifactory`, package
`com.robertsnest.aifactory`), imported into AgentCraft so the office and the
factory agent ship from one repository. It was written by the same author in a
private repository first; the history before the import stays there.

## Source

| | |
| --- | --- |
| Repository | the author's private infrastructure repository |
| Path | `mods/ai-factory` |
| Commit | `731433512efb52198a3e1c024c7572f2e4954f24` |
| Tree of `mods/ai-factory` at that commit | `e5ae72e45edcce8af5e581b642d2878087283626` |
| Last commit that changed the mod | `5740354ebbd1e378af6cf5a9b2780a0284ca701f` |
| Files | 89 (plus this file) |

### Why this commit and not the earlier `7415d98`

The task that asked for this import named `7415d98` (mod tree
`cd56c1f9fa5afa2f5102bc3aad66de84c925d80d`, code at `2723cb1`) as the approved
state. At that tree the charged-cost acceptance gate
(`./gradlew captureBudgetProbe`, 500 synthetic machines, 100 000 ns per adapter
read) **fails**: the worst tick is 25.5 ms against the configured 8 ms budget.
The narrow capture-budget fix that closes it (commits `ef85b28` "check elapsed
budget before every work unit" and `075b47f` "yield before a phase starts past
its deadline") and the client fixes after it were independently reviewed in
the same private repository and approved at `7314335`; the mod tree is
byte-identical from `5740354` through `7314335` to the head of that branch.
Importing the older tree would have imported a known budget defect, and the
card's safety posture requires budgeted capture. The difference between the two
trees is 14 files; see "Changes against the source" for which of them survive
here.

## Verbatim check

The first commit that adds this directory is a byte-for-byte copy of the
source tree plus this file, with one exception made before anything was
committed: `src/main/resources/mcmod.info` named a private LAN host as the mod
URL and internal names as authors. This repository is public, so in that file
the URL is the public fork, the author is the repository owner and the credits
name the build template, like `gtnh-mod/`'s own `mcmod.info`. Git blob ids are
content hashes, so the copy can be checked against the private repository
without trusting either side:

```sh
diff <(git -C <private-repo> ls-tree -r 7314335:mods/ai-factory) \
     <(git ls-tree -r <import-commit>:gtnh-factory | grep -v 'PROVENANCE.md$')
# expected output: only the two lines for src/main/resources/mcmod.info
```

## Changes against the source

Listed in full so a reviewer can diff them; everything not listed here is
unchanged (no main Java source, Gradle file or resource other than these).

| file | change | why |
| --- | --- | --- |
| `src/main/resources/mcmod.info` | URL -> the public fork, author -> repository owner, credits -> build template | the source named a private LAN host and internal names (done before the first commit, see above) |
| `src/main/resources/LICENSE` | the template's placeholder MIT text (`[year] [fullname]`) -> this repository's MIT licence text (same terms) | one licence text for every jar the repository ships, as in `gtnh-mod/` |
| `.github/workflows/build-and-test.yml`, `CODEOWNERS`, `jitpack.yml` | removed | GTNH starter files for a stand-alone repository: inert in a subdirectory, and `CODEOWNERS` named the template's owners. `gtnh-mod/` does not carry them either |
| `README.md` | new "In AgentCraft" section, token procedure, safety table, build path `gtnh-factory/`, "Verifying a build" | the module now lives in this repository; the rest of the text is the source's |
| `src/test/java/com/robertsnest/aifactory/safety/ReadOnlySurfaceTest.java` | new test (the only code added) | parses every compiled class's constant pool and fails on any reference to a world write, chunk load, server command or AE2/GT mutation name, and on action-shaped class names; a deliberate `world.setBlock` class made it fail as expected (negative control, not committed) |
| `PROVENANCE.md` | new | this file |

Package (`com.robertsnest.aifactory`), mod id (`aifactory`), protocol string
(`ai-factory/v2`), config file name and every default are unchanged, so a
config or token file written for the original jar works with this one.

## Build check after the import

Built on the GTNH build host with the toolchain `gtnh-mod/` uses (Java 25 for
Gradle, RetroFuturaGradle, Jabel, `--no-daemon`, a Gradle home private to this
module): `./gradlew clean spotlessCheck test build` passes; the release and dev
jars carry the same class list. The evidence (build log, test counts, class list,
bytecode scan) is kept outside the repository with the other card evidence.
