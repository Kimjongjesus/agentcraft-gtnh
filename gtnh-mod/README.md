# gtnh-mod: AgentCraft for GT New Horizons (Forge 1.7.10)

A GTNH-native reimplementation of the idea of [blendi-remade/agentcraft](https://github.com/blendi-remade/agentcraft)
(MIT): Hermes agents from ai-ops appear in the world as NPCs with a live status nameplate. It is not
a port of the upstream Fabric code (nothing compiles across 1.7.10 and 26.x); it speaks the same
WebSocket protocol (`../docs/protocol.md`) to `../hermes-adapter/`.

Scope so far (read-only: nothing in the game can change Hermes):

- card 1: viewer foundation (one NPC, live state, adapter link).
- card 2 (this branch): every agent as an NPC, an HQ that **Eli builds himself** and wires up with
  anchors, agents walking between stations, desk monitors with log tails, a "waiting on Eli" marker,
  status lamps and a fleet beacon. The mod never generates, places or breaks blocks in the world.
- card 3 (later): task wall, library and atrium GUIs.

## How it works

```
hermes-adapter (ai-ops, Python)  --ws://...:7878-->  server: ForemanBridge thread (hello, then read only)
                                                        -> inbox -> AgentWorldSync (server tick)
                                                        -> StationAssigner + hq-anchors.json -> NPC targets
                                                        -> EntityHermesAgent (registerModEntity, tracked)
                                                        -> SimpleNetworkWrapper "agentcraftgtnh"
                                                     client: ClientAgentCache -> RenderHermesAgent,
                                                             RenderAgentCraftTile (monitor/lamp/beacon)
```

- `bridge/WebSocketClient`, `bridge/ForemanBridge`: minimal RFC 6455 client (ws:// only, no Origin
  header, Java 8 API), daemon thread, reconnect with backoff 1 s .. 30 s, re-sends `hello`; the
  snapshot rebuilds all state. The mod never sends anything except `hello`.
- `server/AgentWorldSync`: applies `snapshot`, `agent.upsert`, `agent.log` and decision messages on
  the server thread. One NPC per agent (most urgent first) up to `maxAgents` (default 12); the rest
  are named on the overflow sign. The NPC of an agent that leaves the snapshot (or drops over the
  cap) is removed. Agents, the fleet summary and monitor tails go to clients **on change** (plus a
  5 s agent refresh; no per-tick packets). While the link is down every plate and monitor says
  "Hermes adapter offline".
- `hq/HqAnchors`, `hq/StationAssigner`: the anchors file and the station -> spot rules (below).
- `entity/EntityHermesAgent`: invulnerable, not pushable, never despawns on its own, **never saved
  with the chunk** (the bridge recreates it, so a restart cannot duplicate it; a periodic stray-NPC
  sweep is a second guard), drops and picks up nothing, triggers no pressure plates, no step sounds.
  Walks with vanilla `PathNavigate` over the real blocks. Outfit = dyed leather in the agent colour;
  the lead (front door) wears a gold helmet, the scheduler iron, reviewers chainmail.
- `client/RenderHermesAgent`: two-line plate `● Name` (agent colour) and `state · activity`
  (<= 48 chars, upstream status colour: working teal, thinking amber, waiting clay, error red, done
  sage, idle grey), plus a big clay `!` above anyone waiting on Eli (display only; nothing in the
  game answers a decision).
- `client/RenderAgentCraftTile`: monitor text, lamp glow and the beacon beam, drawn only within
  `monitorRenderDistance` of the player.
- `client/AnchorOverlayRenderer`: the `/agentcraft anchor show` overlay (anchor markers + names).
- `client/DevShots` + `CommonProxy` dev hooks: inert unless `-Dagentcraft.dev.*` system properties
  are set (QA screenshots only).

## Wiring up the HQ (Eli builds it; the mod only reads it)

### 1. Anchors: where each station is

Stations are upstream's enum: `desk library terminal testbench mergestation meeting lounge user`
(`user` is where agents stand while they wait for Eli). Anchor names:

| name | meaning |
|---|---|
| `desk` | slot 1 of the shared desk station |
| `desk_2` .. `desk_N` | more slots (up to 32 per station) |
| `desk_claude-builder` | a personal spot: that agent always uses it for that station |
| `lounge`, `lounge_2`, ... | where off-shift agents idle |
| `overflow_sign` | a vanilla sign Eli placed; the mod writes "+N more agents" and names on it |
| `cam_*` | free viewpoints for `anchor tp` (QA cameras, keep yaw and pitch); agents ignore them |

Stand on the spot, face the way the agent should face, and run (op level 2):

```
/agentcraft anchor set desk_claude-builder        # block centre, facing rounded to N/E/S/W
/agentcraft anchor set lounge_2 south             # explicit facing
/agentcraft anchor set terminal -44.5 4 -224.5 north   # exact coordinates (also from the console)
/agentcraft anchor list [prefix]                  # what is set, dimension, file
/agentcraft anchor tp <name> [player]             # go and look at a spot
/agentcraft anchor missing                        # stations with no anchor + agents not on a slot
/agentcraft anchor show [seconds]                 # client overlay of every anchor (0 hides)
/agentcraft anchor remove <name>
/agentcraft anchor reload                         # after editing the file by hand
```

The file is `config/agentcraftgtnh/hq-anchors.json` (config `hq.anchorsFile`), rewritten on every
`set`/`remove` and safe to edit by hand:

```json
{
  "version": 1,
  "dimension": 0,
  "anchors": {
    "lounge":              {"x": 100.5, "y": 64, "z": 200.5, "facing": "south"},
    "desk_claude-builder": {"x": 104.5, "y": 64, "z": 196.5, "facing": "north"},
    "overflow_sign":       {"x": 99, "y": 65, "z": 201}
  }
}
```

`y` is the block the agent stands **in** (feet), one above the floor. `facing` is
north/south/east/west, or give `yaw` in degrees. All anchors share one dimension (the HQ is one
place).

### 2. Which spot an agent gets (`StationAssigner`; `dev/tests/StationAssignerCheck.java`)

The adapter always sends a valid station (`../hermes-adapter/MAPPING.md`: `desk` when working and
nothing more specific applies, `lounge` when idle, `user` while waiting on Eli). Per agent, first
match wins:

1. its personal spot for that station (`desk_<agent>`),
2. the slot it already had there (agents do not shuffle when others come and go),
3. the first free slot of the station.

Missing anchors: a station with **no anchor at all** is logged once (`no anchor for station
'testbench': ...`) and its agents take a free lounge slot. Agents left without a slot hover in a
small ring next to the station's first slot, else the lounge's, else the nearest configured anchor.
NPCs never roam: they only walk to their target, and an NPC with no path, no progress for
`teleportAfterSeconds`, a fall below its spot, or a target more than 48 blocks away is put on the
spot. With no anchors at all the NPCs stand in a row next to the world spawn (card-1 behaviour).

### 3. Monitors, status lamps, fleet beacon

Three ordinary blocks in the Decorations creative tab (or `/agentcraft give monitor|lamp|beacon
[count]`): **Agent Monitor**, **Agent Status Lamp**, **Fleet Beacon**. Eli places and breaks them like
any block (they drop themselves); they never tick and only hold a binding.

| block | bind to | shows |
|---|---|---|
| Agent Monitor (faces you when placed) | an agent id | name, state, activity and the agent's last `monitorLines` log lines (`agent.log` from the adapter, privacy-filtered there) |
| Agent Status Lamp | an agent id, or `fleet` | that agent's status colour, or the fleet colour |
| Fleet Beacon | nothing needed | a beam in the fleet colour (put it on the roof) |

Fleet colour, most urgent first: someone waiting on Eli (clay) > an error (red) > anyone working or
thinking (teal) > all idle (grey). With the adapter offline the beam and lamps go dark.

Look at the block (within 8 blocks) and bind it:

```
/agentcraft bind claude-builder          # monitor or lamp at the crosshair
/agentcraft bind claude-builder 3 2      # monitor: 3x2-block screen (w 1..8, h 1..6)
/agentcraft bind fleet                   # lamp: fleet colour
/agentcraft bind overflow                # looking at a vanilla sign: it becomes the overflow sign
/agentcraft bind clear
/agentcraft bind <agent|fleet|overflow|clear> <x> <y> <z> [w h]   # console form
```

A monitor belongs at the agent's desk, but the binding is by agent id, so it can hang anywhere. Text
is drawn client-side only within `monitorRenderDistance` blocks and changes only when a new log line
or state arrives.

### Other commands

`/agentcraft status` (link, counters, anchors, every NPC with position, target and tracked flag),
`/agentcraft agents` (every agent with station, spot and waiting marker), `/agentcraft cap <0..64>`
(NPC cap until the next restart).

## Config (`config/agentcraftgtnh.cfg`)

- `bridge`: `adapterUrl` (default `ws://127.0.0.1:7878`), `enabled`, `verboseLog`.
- `display`: `displayAgents` (empty = everyone, most urgent first), `maxAgents` (12), and the
  no-anchors fallback row: `spacing`, `spawnAtWorldSpawn`, `spawnDimension`, `spawnX/Y/Z`.
- `hq`: `anchorsFile`, `monitorLines` (9), `monitorRenderDistance` (24), `teleportAfterSeconds`
  (12), `walkSpeed` (0.3).

The dev-copy settings are in `dev/agentcraftgtnh.dev.cfg`.

## Build

```
export GRADLE_USER_HOME=<gradle home> JAVA_HOME=<JDK for Gradle, e.g. 25>   # RFG fetches its Java 8 toolchain
./gradlew --no-daemon assemble        # build/libs/agentcraftgtnh-<version>.jar
```

Card 1 built on gaming-spare (`~/gtnh-dev/jdk25`); card 2 also builds on ai-ops with a JDK and Gradle
home in the builder's scratch directory (nothing installed system-wide). Set `VERSION=` when the
build directory is not a git checkout. Template:
[GTNewHorizons/ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) (RetroFuturaGradle,
Forge 10.13.4.1614, Jabel; template licence in `LICENSE-template`).

## Dev and QA (never the real world)

- RFG dev server on ai-ops (plain Forge + this mod, FLAT world, loopback port 25571):
  `dev/run-hq-qa.sh start <adapter ws url> [display]`, then `cmd`, `run`, `feed`, `stop`. With a
  display a dev client joins and takes a screenshot on `say devshot NAME`.
- `dev/qa-arena.txt`: the throwaway test arena (a few `setblock`s, bindings, anchors, cameras) at
  about x -64..-38, y 4, z -237..-212, just south of the FLAT spawn (-56, 4, -246). It is **not**
  the HQ.
- `dev/qa-scenes.sh <fixture dir>`: drives `../hermes-adapter/scripts/demo_hq.py` phase by phase
  (progress note, demo-ready, unblock, crash, all idle) and screenshots each state.
- GTNH dev copy (gaming-spare `~/gtnh-dev/server`, GTNH 2.9.0-beta-3): `dev/prepare-dev-copy.sh`
  (once; offline mode, port 25570, loopback bind), `dev/run-dev-server.sh` / `dev/stop-dev-server.sh`
  (heap 1..5 GB, console FIFO, clean `stop`, never kill -9), `dev/run-dev-client-shot.sh`.
  `dev/gtnh-sky-arena.py check|build|clear` writes console command files for the throwaway test
  arena there: a glass platform at y=160 (x -26..4, z 30..51) above the copy's spawn, same layout as
  `qa-arena.txt`; `check` (testforblock air over the whole volume) runs before `build`.

The real Pterodactyl world is untouched; installing there needs a fresh backup and Eli's approval.

## Evidence

Run logs and screenshots from the dev copy are kept out of the public repo because they contain real
board content. Verification summary: the mod boots on a GTNH 2.9.0-beta-3 dev copy with all agents
spawning once, walking between stations, following board changes, falling back to the lounge when an
anchor is missing, and restarting without duplicates; screenshots came from a plain Forge client, so
rendering inside the full GTNH client is unverified.
