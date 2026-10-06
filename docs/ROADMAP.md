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
| 6 | In-game office edit tool (op only): edit mode with an overlay, a Panel Inspector (rebind from a list, resize with a live preview, label, theme, duplicate, delete), a panel palette fed by the panel registry, an anchor editor, and a per-world layout with undo/redo, named snapshots with a diff, bundled presets and export/import with a dry run. It only ever places, moves or removes this mod's own panel blocks and anchors (never other blocks or machines); every change is audited, rate-limited and capped, and `/agentcraft edit lock` stops all of it server-wide. Still read-only towards Hermes. |

## Next

| card | what |
|---|---|
| 5 | Ops feeds as panels (fleet health, scheduled jobs, provider usage, alerts) and a toast plus sound when a decision needs the player. Read-only. |
| 7 | Write path through a small control service with an allowlist of actions (answer decisions, create, edit and dispatch cards, chat with agents, restart named services, rerun scheduled jobs). Confirmation on builder dispatch, an audit log and a one-click write lock; the server moves to online mode with a whitelist first. |
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
