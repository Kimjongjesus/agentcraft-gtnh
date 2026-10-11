# AgentCraft GTNH port: roadmap

Where the GT New Horizons port (the `gtnh-mod/` Forge 1.7.10 mod and the `hermes-adapter/`) is going.
This is direction, not a promise: every step below gets its own design, its own tests and, for
anything that writes, its own security review. Public release is decided when it is done.

Compatibility target: **GT New Horizons 2.9.x only** (Forge 1.7.10, Java 8 through lwjgl3ify Java
17+). Older or newer packs are not tested.

## Done so far

| card | what |
|---|---|
| 1-2 | Hermes adapter (read-only, privacy-filtered) and the bridge mod: agents as NPCs walking to their stations, desk monitors, status lamps, fleet beacon. |
| 3 | Task wall, goal atrium and agent library blocks with a bounded board sync (read-only). |
| 4 | Readable UI: a bundled TrueType font (Nunito, plus JetBrains Mono for logs and ids) drawn from a texture atlas, a small widget toolkit, a panel registry and an office layout file; wall, atrium, library and monitors rebuilt on it with distance-based level of detail; waiting agents fan out and nameplates no longer pile up; one source of truth for done counts with the window labelled ("last 3 days" vs "all time"). |
| 5 | Ops feeds as panels (fleet health, scheduled jobs, provider usage, alerts; the `ops.*` protocol and adapter source plugins) and a toast plus sound when a decision needs the player. Read-only. |
| 6 | In-game office edit tool (op only): edit mode with an overlay, a Panel Inspector (rebind from a list, resize with a live preview, label, theme, duplicate, delete), a panel palette fed by the panel registry, an anchor editor, and a per-world layout with undo/redo, named snapshots with a diff, bundled presets and export/import with a dry run. It only ever places, moves or removes this mod's own panel blocks and anchors (never other blocks or machines); every change is audited, rate-limited and capped, and `/agentcraft edit lock` stops all of it server-wide. Still read-only towards Hermes. |
| G1 | Factory telemetry: the read-only `aifactory` mod imported as `gtnh-factory/`, the opt-in `world.*` protocol extension, the adapter's factory source and a local world journal (see the GTNH agent track below). |

## Next

| card | what |
|---|---|
| 7 | Write path (built, awaiting review; **nothing enabled**): a separate control service (`hermes-adapter/hermes_control/`, [CONTROL.md](../hermes-adapter/CONTROL.md)) with an allowlist policy, signed `action.*` frames ([action-protocol.md](action-protocol.md)), a replay and idempotency ledger, single-use confirm tokens, an audit log and a write lock; and a separate write-module jar (`gtnh-write/`) with the online-mode arming gate, its own channel, a game-side audit and lock, and the screens (dispatch Confirm, lock button, decision answers, card forms, chat window, `/ask`, restart and run). Leaving the jar out keeps the office read-only; the shipped policy is empty. Design and the answers to its open questions: [`write-path.md`](write-path.md). |
| 8 | Read-only web dashboard on the local network. |
| 9 | Install on a real world: fresh backup first, explicit owner approval, adapter running as a service. |

## Longer term

### Protocol: `world.*` beside `action.*`

The protocol (`docs/protocol.md`) today flows one way: adapter to game. Two namespaces are planned:

- `world.*`: game-to-adapter **telemetry** (machines, multiblocks, power networks, AE2 storage,
  base layout). Read-only facts about the world that agents can read.
- `action.*`: **requests** from the game, each tagged with a capability and a tier, checked
  against a policy on the adapter side before anything happens. None are implemented; card 7 is
  the first, and it only covers board and service actions, not the world.

### The mod stays modular

- **core/UI**: what exists now (agents, panels, toolkit, layout). Read-only.
- **factory telemetry** (read-only): an existing separate Forge mod, `ai-factory`, gets folded in
  as a module that produces `world.*` telemetry.
- **write module** (later, separate): the only part that can send `action.*` requests. Leaving it
  out of a pack leaves the office strictly read-only.

New panel types register in the panel registry and are named in the layout file, so a module adds
panels without changing the core renderers.

### The GTNH agent ("the Oracle")

A GTNH-specialist agent appears in the office as an NPC with its own desk, with an optional
companion mode that follows the player. It learns from a world journal, notes, the build history
and a pack knowledge base.

Acting in the world follows a tier plan. Each tier gets its own design and security review, runs
only while the player is logged in, and is tested on a copy of the world first. The first tiers
are advice only; anything that changes the world comes later and only tier by tier.

### Any adapter

Hermes is the first adapter. The protocol and the mod must work with any adapter that speaks the
protocol: nothing in the mod depends on Hermes beyond the messages in `docs/protocol.md`.

## GTNH agent track: the factory joins the office (card G1)

Compatibility target: GT New Horizons 2.9.x only (Forge 1.7.10).

The office (`gtnh-mod/`) shows the agents; the factory module (`gtnh-factory/`) lets them see the
base. Both ship from this repository as separate jars, and the adapter joins them:

```
GTNH server (Forge 1.7.10)
  agentcraftgtnh  (office: NPCs, desks, panels)  <---- WebSocket: protocol v1 (+ opt-in world.*)
  aifactory       (read-only telemetry)          ----> HTTP GET /health, /telemetry/* (token, loopback)
                                                            |
adapter (Hermes is the first; any adapter that speaks the protocol works)
  sources/factory.py   poll, bound, privacy-filter    -> world.* to clients that ask for it
  world journal        events + samples + snapshots   -> query CLI / API for agents
                                                            |
agents
  the GTNH agent ("Oracle", later): reads the journal and the live world.* view through a scoped,
  read-only toolset; appears in the office as an NPC with a desk (companion mode later)
```

| order | what | state |
| --- | --- | --- |
| 1 | factory telemetry imported as `gtnh-factory/` (read-only, token-gated loopback HTTP, budgeted capture); `world.*` protocol extension; adapter factory source; world journal with retention and a query CLI | card G1 |
| 2 | the GTNH agent as a Hermes profile with scoped read tools over the journal and the live world view; desk in the office, chat through the same window as the other agents; advice: recipe chains, bottlenecks from the journal trends, proactive alerts as decision toasts | planned |
| 3 | companion NPC that walks the base (toggleable) | planned |
| 4 | vision on request | planned |
| 5 | acting in the world, tier by tier (below) | planned |

Card numbers after G1 are not decided; the order follows the plan.

Learning, in order of arrival: the automatic world journal (G1), "remember this" notes, build
history linked to cards and commits, a pack knowledge base (GT recipes and machines).

### Acting stays out until each tier is reviewed

`action.*` is reserved in the protocol and refused by the adapter. Acting (AE2 crafts, builds)
comes as a **separate write module**, never inside the telemetry jar, one tier at a time:

| tier | scope |
| --- | --- |
| 0 | read-only telemetry (G1) |
| 1 | suggest only: plans a player carries out by hand |
| 2 | single actions the player confirms in-game, each with an undo record |
| 3+ | bounded autonomy, only while the player is online, with an agreed loss budget |

Every tier gets its own design and security review and is tested on a copy of a world first.
Leaving the write module out of a pack leaves everything read-only.

### Integration surface for other projects

- **Other adapters / agent frameworks:** speak `docs/protocol.md`; add `world.*`
  (`docs/world-protocol.md`) to receive factory telemetry. The adapter side is Python standard
  library only.
- **Other GTNH servers:** drop in the `aifactory` jar, configure a base region and a token; the
  telemetry routes are documented in `gtnh-factory/README.md`.
- **Other data sources:** a world source plugin (`create_sources(config)` returning objects with
  `id`, `interval`, `collect()`) can replace the factory source; the normalisation, bounds,
  privacy filter and journal stay the same.
