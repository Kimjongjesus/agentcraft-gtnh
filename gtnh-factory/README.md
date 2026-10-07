# AI Factory (`gtnh-factory/`)

AI Factory is the GTNH companion mod for the read-only base copilot. It
observes a **named, bounded base region**, publishes immutable telemetry
captures, and serves them over a token-gated loopback HTTP endpoint. It has no
world mutator: no craft, no item movement, no block placement, no server
commands.

## In AgentCraft

This directory is the factory-telemetry module of AgentCraft GTNH. It ships as
its own jar (mod id `aifactory`, package `com.robertsnest.aifactory`) next to
the office mod in `../gtnh-mod/` (mod id `agentcraftgtnh`); neither depends on
the other, and either can be left out of a pack.

```
GTNH server (Forge 1.7.10)                           adapter host
+-------------------------------+                    +------------------------------+
| agentcraftgtnh  (office, NPCs)| <-- WebSocket ---- | hermes-adapter (port 7878)   |
| aifactory (this module)       |                    |   sources/factory.py polls   |
|   HTTP 127.0.0.1:25580 -------|-- GET + token --> |   -> world.* (opt-in clients)|
|   /health /telemetry/*        |   (loopback or     |   -> world journal (SQLite,  |
+-------------------------------+    a local tunnel) |      outside the repo)       |
                                                     +------------------------------+
```

- The adapter turns captures into the `world.*` protocol extension
  ([`docs/world-protocol.md`](../docs/world-protocol.md)) and a local world
  journal; see [`hermes-adapter/README.md`](../hermes-adapter/README.md),
  "Factory telemetry". Any adapter that can do an authenticated HTTP GET can
  use the same endpoints.
- Origin and the exact source commit: [`PROVENANCE.md`](PROVENANCE.md).
- Compatibility: GTNH 2.9.x only (Forge 1.7.10). Older or newer packs are not
  tested.
- Acting in the world (AE2 crafts, builds) is **not** part of this module and
  never will be: it comes later as a separate write module with its own design
  and security review per tier. `action.*` is reserved in the protocol and not
  implemented anywhere.

## Target

- Minecraft Forge: 1.7.10 / Forge 10.13.4.1614
- GTNH target: 2.9.0-beta-3 (GregTech 5.09.54.133, AE2 rv3-beta-1050-GTNH)
- Runtime/build toolchain: Java 25; compiled classes are Java 8 format (Jabel)
- GregTech and AE2 are optional at runtime; missing mods make their sections
  `UNAVAILABLE`, never empty-and-healthy.

## What it does (protocol `ai-factory/v2`)

- **Incremental capture.** A `CaptureEngine` advances a bounded slice per
  server tick (default 8 ms / 4096 attempts) through machines -> ME stock ->
  block palette/light survey -> surroundings, then publishes one immutable
  `TelemetryCapture`. Captures are paced (default >= 30 s). HTTP handlers only
  read the latest published capture; **no HTTP request ever schedules work on
  the game thread.**
- **Explicit scope.** The base is `base.dimensionId` + centre + radius/height
  from the config. Nothing is captured until `base.configured=true`. An
  optional `playerRelativeScopeFor=<name>` allows a clearly labelled
  `PLAYER_RELATIVE` box around one named player. There is no "first online
  player" fallback.
- **Coverage everywhere.** Every section carries `coverage` with
  `status` (OK / PARTIAL / UNAVAILABLE / ERROR), attempted/succeeded/error/
  skipped-unloaded counts, `distinctSeen` vs `returned`, budget and truncation
  flags. Unknown is reported as unknown (`needsMaintenance: null`, `euStored:
  null`), not as zero or healthy.
- **Identity.** Machines are `dim<id>:<gt-meta-name>@x,y,z`. Stock IDs are
  `registry@damage` plus a short NBT-tag hash when the stack has a tag. Block
  variants are `registry#meta`.
- **Session identity.** Each server start gets a fresh `session.id`; the
  Oracle splits history on it so a restart is never read as time going
  backwards.
- **Server-only compatible.** A `@NetworkCheckHandler` accepts any client, so
  a matching GTNH client without this mod can join.

### Routes (all `GET`, `Authorization: Bearer <token>`)

| Path | Body |
| --- | --- |
| `/health` | liveness + last-tick age + last capture time; never touches game state |
| `/telemetry/capture` | full v2 capture: machines, stock, design, surroundings, coverage |
| `/telemetry/snapshot` | legacy flat shape (stock + machines) with derived `truncated` plus coverage |
| `/telemetry/multiblocks` | machines section only |
| `/telemetry/design` | palette / artificial-light survey only |

Admission is bounded: two workers, an 8-deep queue, 503 on overload, one
telemetry serialisation at a time, `/health` never waits on that permit.
Responses over 4 MiB are refused.

## Configuration (`config/aifactory.cfg`)

```
http { enabled=true, bindHost=127.0.0.1, port=25580, tokenFile=<config>/aifactory-token.txt }
base { configured=false, label, dimensionId, centerX/Y/Z, radius=64, height=32,
       playerRelativeScopeFor="", meAccessPointConfigured=false, meAccessX/Y/Z }
capture { intervalSeconds>=30, tickBudgetMillis<=20, tickBudgetAttempts, maxMachines,
          maxTileAttempts, stockCap, stockMaxAttempts, maxCaptureTicks }
design_survey { enabled, stride, maxAttempts, paletteCap }
safety { SUBMIT_CRAFT=false, ... }   # no route performs any action in this build
```

The token file's first non-comment line (16+ chars) is the secret. No token
file, no listener. Keep it outside the world folder and `chmod 600`.

### Token, step by step

1. Generate a random secret on the server host, never in chat or a shared
   document, for example `head -c 32 /dev/urandom | base64 > aifactory-token.txt`
   inside the server's `config/` directory, then `chmod 600` it.
2. Leave `http.bindHost=127.0.0.1` unless the adapter runs on another host. If it
   does, prefer an SSH tunnel to the loopback port over binding a LAN address:
   the endpoint is plain HTTP, so a LAN bind sends the bearer token in clear
   text on that network.
3. Give the adapter the same secret through a file it can read
   (`--factory-token-file`, or `AGENTCRAFT_FACTORY_TOKEN_FILE`) and the URL
   (`--factory-url http://127.0.0.1:25580`). The adapter never accepts the token
   as a command-line value, never logs it and never follows redirects, so the
   header cannot be replayed to another host.
4. Rotate by replacing the file and restarting the server (the mod reads it at
   start-up); the old token stops working immediately.

All routes, `/health` included, answer `401` with a byte-identical body for a
missing, malformed or wrong token. The comparison runs over the full length of
the presented value whatever its prefix (`TokenAuthenticator.matches`, tested by
`comparisonCostDoesNotDependOnHowMuchOfTheSecretMatches`).

## Safety posture

What this module is: **read-only telemetry**. What it checks, and where:

| property | how it is enforced | how it is verified |
| --- | --- | --- |
| no world writes, no AE2 or GT mutation | no such code: no action gateway, ledger or dispatcher in the jar | `ReadOnlySurfaceTest` (constant pools of every compiled class) plus a class list and bytecode scan of the built jar (see "Verifying a build") |
| no chunk loading | `blockExists` before every block or tile read; no `ForgeChunkManager`, `loadChunk` or `provideChunk` reference | `ReadOnlySurfaceTest`; `BaseDesignScannerTest`, `CaptureEngineTest` |
| bounded game-thread cost | `CaptureEngine` work budget per tick (8 ms / 4096 attempts by default), checked before every work unit | `WorkBudgetTest`, `CaptureEngineTest`, `./gradlew captureBudgetProbe` |
| bounded HTTP | 2 workers, 8-deep queue, `503` on overload, one telemetry serialisation at a time, 4 MiB response cap | `TelemetryHttpServerTest` |
| loopback by default | `DEFAULT_BIND = 127.0.0.1`; a wildcard needs an explicit config value | `defaultBindIsLoopbackAndNotWildcard` |
| constant-time token check | XOR over the whole presented value, length folded in after the loop | `TokenAuthenticatorTest` |

- The action library (gateway, idempotency ledger, server-thread dispatcher)
  of an earlier prototype is **not in this jar**, so its review findings are
  excluded mechanically, not by config. `safety.*` flags remain for forward
  compatibility and only produce a log line. `FactoryOperation` and
  `CapabilityPolicy` are that policy vocabulary; nothing executes them.
- Item names, machine names, sign text and player names are untrusted data
  and are escaped, never interpreted.
- Loaded chunks only: `blockExists` is checked before every read; nothing
  forces a chunk load, generates terrain or reads save files.
- The optional client part (`CLIENT.md`) adds one **client-side** chat command,
  `/oracle`, registered with Forge's client command handler; it talks to an
  Oracle service over HTTP and never to the server or the world. It stays off
  unless configured on the client.

## Local build

From the repository root (Java 25 runs Gradle; RetroFuturaGradle fetches the
Java 8 toolchain for the Minecraft side; compiled classes are Java 8, Jabel):

```bash
cd gtnh-factory
export JAVA_HOME=/path/to/jdk-25 GRADLE_USER_HOME=/path/to/a/private/gradle-home
PATH="$JAVA_HOME/bin:$PATH" ./gradlew --no-daemon clean spotlessCheck test build
# build/libs/aifactory-<version>.jar  (set VERSION=... when this is not a git checkout)
```

The official GTNH starter supplies the Gradle wrapper. Do not replace its build
script casually; use supported properties and dependency files so GTNH
build-script updates remain portable.

Unit tests cover the capture engine, scanner, budgets, collectors, JSON
(independent parser round trips) and the HTTP surface over real sockets with
fixed sources. They are **not** a Forge server launch: a boot next to the
office mod on a dev server is part of the release check.

### Verifying a build

A reviewer can re-check the "read-only" claim on any jar without trusting this
README:

```bash
jar tf build/libs/aifactory-<v>.jar | grep '\.class$'          # the whole class list
javap -c -p -classpath build/libs/aifactory-<v>-dev.jar <every class> \
  | grep -oE 'Method net/minecraft/world/[A-Za-z/]+\.[A-Za-z_0-9]+' | sort | uniq -c
```

The second command lists every world method the mod can call. For this module
it is reads only: `blockExists`, `getBlock`, `getBlockMetadata`,
`getSavedLightValue`, `getTileEntity`, `getBiomeGenForCoords`, world time and
weather.
