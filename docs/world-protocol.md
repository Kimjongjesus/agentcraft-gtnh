# AgentCraft protocol extension: `world.*` (factory telemetry)

Status: extension to [protocol v1](protocol.md), implemented by `hermes-adapter` (card G1) on top
of the read-only telemetry module in [`gtnh-factory/`](../gtnh-factory/README.md). No in-game panel
shows it yet; this document is the contract for whoever builds one (and for any other adapter).

`world.*` carries what an agent needs to *know* a GregTech base: the base and how much of it was
actually seen, its multiblocks (typed, located, with a state), power, the AE2 network and a coarse
design survey, plus **change events** derived from consecutive captures. It is strictly one-way
and read-only: the adapter sends, the client displays or reasons. Nothing a client sends can
change the world, the telemetry mod or the adapter.

Everything here is generic. Examples use made-up names and coordinates. The telemetry mod is the
first producer; any server-side mod that can produce the same facts can feed an adapter.

## 1. Where the data comes from

```
game (Forge 1.7.10)                 adapter                          clients / agents
aifactory telemetry  --HTTP GET-->  sources/factory.py  --world.*-->  WebSocket clients that opted in
  /health                           (poll, token, bounds)            world journal (SQLite, local)
  /telemetry/capture                world.py (normalise, diff)       journal query CLI / API
```

The adapter polls `GET /health` every `interval` seconds (default 30, minimum 10) and fetches
`GET /telemetry/capture` only when the health answer shows a new capture (`captureSequence` or
`lastCaptureMillis` changed). One capture is one consistent view: every `world.*` entity of one
poll comes from the same capture. The telemetry mod paces captures itself (default at least 30 s
apart, a bounded slice of work per server tick), so polling faster gains nothing.

| `world.*` entity | telemetry source (protocol `ai-factory/v2`) |
| --- | --- |
| `WorldSource` | `/health` (`status`, `lastCaptureMillis`, `captureSequence`, `gregTech`, `ae2`) and the poll outcome (HTTP status, time-outs) |
| `WorldBase` | `/telemetry/capture` envelope: `baseId`, `worldName`, `worldRevision`, `capturedAtMillis`, `session`, `scope`; every section's `coverage`; `surroundings` (counts only) |
| `WorldMachine` | `/telemetry/capture` `multiblocks[]` (same objects as `/telemetry/multiblocks`) |
| `WorldPower` | `/telemetry/capture` `stock.power` (AE) and the `euPerTick` / `euStored` / `euCapacity` of the multiblocks (EU) |
| `WorldAe2` | `/telemetry/capture` `stock` (`items`, `crafting`, `coverage`); `/telemetry/snapshot` has the same items in the legacy shape |
| `WorldDesign` | `/telemetry/capture` `design` (same object as `/telemetry/design`) |
| `WorldEvent` | derived by the adapter from two consecutive captures **of the same telemetry session** (`session.id`); a new session starts a new baseline instead of a burst of false changes |

All telemetry routes need `Authorization: Bearer <token>`; see the module README for how the token
is created and handed to the adapter.

## 2. Opting in

Same mechanism as the other extensions: the client lists `"world"` in `hello.features`.

```json
{ "v": 1, "type": "hello", "id": "h1", "modVersion": "0.4.0", "protocol": 1, "client": "gtnh-mod", "features": ["world"] }
```

The adapter answers, in this order: `snapshot` (protocol v1), then `world.snapshot`, then the `ack`
(with `result.features` when the hello carried `features`). Connections that did not ask receive
no `world.*` message at all, so upstream clients and validators are unaffected. Every hello
re-decides the opt-in. An adapter with the feature but no telemetry source configured still sends
a `world.snapshot` (no `base`, empty lists, `source.state = "off"`), so a client can tell "no
telemetry configured" from "old adapter".

## 3. Messages (adapter -> client)

Protocol v1 envelope (`{"v": 1, "type": ...}`). Timestamps are integer epoch milliseconds.
Optional fields are **omitted, never `null`**: an absent `active` means "unknown", which is not the
same as `false`. **Receivers ignore unknown fields and unknown `world.*` message types.**

| type | payload | when |
| --- | --- | --- |
| `world.snapshot` | `world` (int, extension version, `1`), `ts`, `source`, optional `base`, `machines`, optional `power`, optional `ae2`, optional `design`, `events` (most recent last), `limits` | after the hello; again whenever the telemetry session changes (game server restarted). Replaces everything the client knew. |
| `world.source.upsert` | `source` | the source state or freshness changed |
| `world.base.upsert` | `base` | anything in the base changed |
| `world.machine.upsert` | `machine` | a machine appeared or any of its fields changed (replace the whole entity by `id`) |
| `world.machine.remove` | `id`, `reason` (`"gone"`) | a machine is no longer in the capture (unloaded, removed, out of scope) |
| `world.power.upsert` | `power` | power numbers changed |
| `world.ae2.upsert` | `ae2` | AE2 numbers changed |
| `world.design.upsert` | `design` | the design survey changed |
| `world.event` | `event` | one change event, appended (like `feed.add`) |

Rate: at most one burst per new capture (default every 30 s or slower), plus source-state changes.

## 4. Entities

### WorldSource

| field | type | notes |
| --- | --- | --- |
| `id` | string | source id, `"factory"` for the built-in one |
| `name` | string | display name |
| `state` | enum | `off` (not configured), `starting` (no capture yet), `ok`, `stale` (polls work but the newest capture is older than 3 poll intervals, at least 3 minutes: the capture engine is stalled or the server lags), `error` (the last poll failed) |
| `detail` | string | short reason for `starting` / `stale` / `error` (`"no capture yet"`, `"unauthorized"`, `"unreachable"`, `"timeout"`, `"busy"`, `"redirect refused"`, `"response too large"`, ...) |
| `lastOk`, `lastAttempt` | ms | last successful poll / last attempt. Changes of these two alone do not trigger an upsert |
| `capturedAt` | ms | `capturedAtMillis` of the newest capture seen |
| `sequence` | int | `captureSequence` |
| `session` | string | telemetry session id (changes when the game server restarts) |
| `telemetry` | string | telemetry protocol string, e.g. `ai-factory/v2` |
| `modVersion` | string | telemetry mod version |
| `mods` | object | `{gregTech: bool, ae2: bool}`: which readers the mod has |

### WorldBase

| field | type | notes |
| --- | --- | --- |
| `id`, `label` | string | base id and label from the mod config |
| `world` | string | world name |
| `revision` | int | `worldRevision` |
| `capturedAt` | ms | |
| `scope` | object | `kind` (`configured`, `player_relative`, `none`), `dimensionId`, `dimension`, `center {x,y,z}`, `radius`, `height`, `minY`, `maxY`. The anchor player of a `player_relative` scope is **not** sent. |
| `coverage` | object | `machines`, `stock`, `design`, `surroundings`, each a [Coverage](#coverage) |
| `summary` | object | `machines`, `running`, `idle`, `problem`, `maintenance`, `unformed`, `unknown` (counts by state), `playersOnline`, `playersInScope` (a **count**, never names), `hostiles`, `raining`, `thundering`, `worldTime`, `biome` |
| `headline` | string | one readable line ("the vibe"), e.g. `"12 machines: 9 running, 2 idle, 1 needs maintenance. AE2 powered, 1 of 4 crafting CPUs busy."` Coverage gaps are always named in it. |

### Coverage

`status` (`ok`, `partial`, `unavailable`, `error`), `complete`, `truncated`, `budgetExhausted`
(booleans), `attempted`, `succeeded`, `errors`, `skippedUnloaded`, `returned`, `distinctSeen`
(ints), optional `reason`. A section whose coverage is not `ok` is **not** a healthy empty
section: an agent must say "I could not see X", not "there is no X".

### WorldMachine

| field | type | notes |
| --- | --- | --- |
| `id` | string | `dim<id>:<type>@x,y,z`, stable across captures and restarts |
| `kind` | enum | `multiblock` (the only kind the telemetry produces today; single-block `machine` is reserved). There is no separate `world.multiblock` message: a multiblock is a machine of kind `multiblock` with `parts`. |
| `type` | string | machine type key (e.g. `multimachine.blastfurnace`) |
| `name` | string | display name |
| `dim`, `x`, `y`, `z` | int | controller position |
| `state` | enum | first match of: `unformed` (formed is false), `maintenance`, `problem`, `running` (active), `idle` (not active), `unknown` |
| `formed`, `active`, `needsMaintenance` | bool | omitted when the reader could not tell |
| `problem` | string | the most actionable fault; omitted when none |
| `maintenance` | string[] | open maintenance issues |
| `euPerTick` | int | negative while consuming, positive while generating |
| `progress` | object | `{ticks, max}`; omitted when there is no recipe in progress |
| `euStored`, `euCapacity` | int | omitted when unknown |
| `efficiencyPct` | number | |
| `parts` | object[] | `{type, name, x, y, z, tier?, meChannelActive?}`; `meChannelActive` omitted means "not ME-backed", not "channel down" |
| `partsTotal` | int | parts before the cap |

### WorldPower

| field | type | notes |
| --- | --- | --- |
| `ae` | object | `stored`, `max`, `fillPct`, `avgUsage`, `avgInjection`, `powered`, `unit` (`"AE"`); omitted when AE2 is not readable |
| `eu` | object | `consumingPerTick`, `generatingPerTick` (sums over running machines), `stored`, `capacity` (sums over machines that report them), `machinesReporting` |
| `trend` | object | `windowMs`, `samples`, `aeStoredDelta`, `aeStoredPerMin`, `euStoredDelta`: over the last captures of the current session; omitted until there are two |

### WorldAe2

| field | type | notes |
| --- | --- | --- |
| `available` | bool | false when the mod could not read an ME network (see `coverage`) |
| `powered` | bool | |
| `cpus`, `busyCpus` | int | crafting CPUs |
| `jobs` | int | running crafting jobs. The telemetry reports busy CPUs, not individual jobs, so this equals `busyCpus` |
| `itemTypes`, `itemTypesSeen` | int | item types returned / seen before the mod's cap |
| `craftableTypes` | int | item types the network has a pattern for (the pattern summary) |
| `totalItems` | int | sum of returned quantities |
| `top` | object[] | `{id, name, quantity, craftable}`, largest quantities first |
| `coverage` | Coverage | |

### WorldDesign

`available`, `stride`, `blocksSampled`, `solidBlocks`, `density`, `dominantShare`, `paletteSeen`,
`palette` (`{id, name, count, share}`, most used first), `light` (`open`, `lit`, `unlit`,
`unlitFraction`, `minLight`, `maxLight`; saved block light only, not a mob-spawn measurement),
`coverage`. The survey is a stride lattice: a coarse sample, not a census.

### WorldEvent

| field | type | notes |
| --- | --- | --- |
| `id` | string | unique per adapter run |
| `ts` | ms | capture time of the change |
| `kind` | string | see below; receivers must accept kinds they do not know |
| `severity` | enum | `info`, `warn`, `critical` |
| `subject` | string | a machine id, or `base`, `power`, `ae2`, `source` |
| `text` | string | one readable line |
| `data` | object | optional small details: location (`dim`, `x`, `y`, `z`, `type`), `from` / `to` states, `problem`, `fillPct`, counts |

Kinds: `machine.appeared`, `machine.gone`, `machine.started`, `machine.stopped`, `machine.problem`,
`machine.recovered`, `machine.maintenance`, `machine.unformed`, `machine.formed`, `power.low`,
`power.recovered`, `ae2.offline`, `ae2.online`, `ae2.cpus.full`, `ae2.cpus.free`,
`coverage.degraded`, `coverage.recovered`, `session.started`, `session.settled`, `source.lost`,
`source.ok`, `events.dropped`.

**Start-up window.** Right after a game server starts, GregTech multiblocks report `formed: false`
until their first structure check runs; on a GTNH 2.9 copy the first capture (about 20 s after the
boot) showed nearly every multiblock unformed and the next one showed them formed. Machine and
coverage transitions out of a capture taken less than 120 s after the telemetry session started
are therefore not reported one by one: the first capture after the window yields one
`session.settled` event with the counts by state. Power and AE2 events are not held back.

`power.low` fires below 20 % AE storage (`critical` below 5 %) and `power.recovered` only at 30 %
or more, so a value hovering around the threshold does not flap.

`machine.gone` means "not in this capture". The telemetry only reads loaded chunks, so when the
capture skipped unloaded positions (`coverage.machines.skippedUnloaded > 0`) the event is `info`
and says the chunk may be unloaded; a running machine vanishing from a fully loaded base is `warn`.

## 5. Bounds

Every list is capped after normalisation; caps are also sent in `world.snapshot.limits`.

| what | cap |
| --- | --- |
| machines | 512 (problems and maintenance first, then by id) |
| parts per machine / maintenance issues per machine | 32 / 8 |
| AE2 `top` / design `palette` | 16 / 24 |
| events in `world.snapshot` / per capture | 50 / 64 (the rest collapse into one `info` event saying how many were dropped) |
| strings | names 64, problem 120, event text 160, headline 200, ids 128 characters |
| telemetry response read by the adapter | 4 MiB + 64 KiB (the mod itself refuses bodies over 4 MiB) |

At the caps a `world.snapshot` is about 1.4 MiB of JSON (512 machines with 32 parts each; a
300-machine base is about 0.4 MiB), so a client must accept WebSocket frames of that size;
upserts and events are small. The adapter normalises a capture at the caps in about 0.3 s.

## 6. Privacy

- Every string the telemetry returns (item, block and machine names, problems, warnings, labels)
  is untrusted. The adapter runs it through the same whole-text filter as everything else
  (personal-note withholding, credential and address redaction) **before** cutting it to length.
  Ids are reduced to a strict character set.
- `world.*` frames never carry player names: only `playersOnline` and `playersInScope` counts.
  The scope anchor (a player name) is dropped, and any player name the capture itself reports is
  replaced by `[player]` wherever it turns up in other text (a renamed machine, a problem string).
- The adapter's own logs carry states, counts and HTTP status codes only: no player names,
  coordinates, item names, URLs with credentials or tokens.
- The world journal (adapter side, local SQLite outside any repository, mode 0600) may keep player
  names and coordinates, because answering "what happened at my base" needs them. It is a private
  file for local agents and is never published.

## 7. Read-only, and `action.*`

`world.*` is telemetry only. `action.*` is **reserved** for requests that would change the world
(AE2 crafts, builds). Nothing implements it: the telemetry jar has no action code, and the adapter
answers any client message whose type starts with `action.` with `ack {ok: false}` like every other
mutating message.

When acting arrives it follows a tier plan, each tier its own module with its own design and
security review, tested on a copy of a world first:

| tier | scope |
| --- | --- |
| 0 | read-only telemetry: this document |
| 1 | suggest only: plans and recipe chains a player carries out by hand |
| 2 | single actions confirmed by the player in-game, each with an undo record |
| 3+ | bounded autonomy, only while the player is online, with a loss budget agreed in advance |

## 8. Versioning

`world.snapshot.world` is `1`. Additive changes (new optional fields, new event kinds, new message
types) keep the number; receivers ignore what they do not know. A breaking change would use a new
feature name, so an old client never receives a shape it misreads.
