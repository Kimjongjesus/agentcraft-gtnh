# gtnh-mod: AgentCraft for GT New Horizons (Forge 1.7.10)

A GTNH-native reimplementation of the idea of [blendi-remade/agentcraft](https://github.com/blendi-remade/agentcraft)
(MIT): Hermes agents from the agent host appear in the world as NPCs with a live status nameplate. It is not
a port of the upstream Fabric code (nothing compiles across 1.7.10 and 26.x); it speaks the same
WebSocket protocol (`../docs/protocol.md`) to `../hermes-adapter/`.

Scope so far (read-only: nothing in the game can change Hermes):

- card 1: viewer foundation (one NPC, live state, adapter link).
- card 2 (this branch): every agent as an NPC, an HQ that **the player builds by hand** and wires up with
  anchors, agents walking between stations, desk monitors with log tails, a "waiting on the player" marker,
  status lamps and a fleet beacon. The mod never generates, places or breaks blocks in the world.
- card 3: a task wall, a library and goal atrium panels (read-only screens), bound to boards like
  the monitors are bound to agents.

## How it works

```
hermes-adapter (agent host, Python)  --ws://...:7878-->  server: ForemanBridge thread (hello, then read only)
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
  sage, idle grey), plus a big clay `!` above anyone waiting on the player (display only; nothing in the
  game answers a decision).
- `client/RenderAgentCraftTile`: monitor text, lamp glow and the beacon beam, drawn only within
  `monitorRenderDistance` of the player.
- `client/AnchorOverlayRenderer`: the `/agentcraft anchor show` overlay (anchor markers + names).
- `client/DevShots` + `CommonProxy` dev hooks: inert unless `-Dagentcraft.dev.*` system properties
  are set (QA screenshots only).

## Wiring up the HQ (the player builds it; the mod only reads it)

### 1. Anchors: where each station is

Stations are upstream's enum: `desk library terminal testbench mergestation meeting lounge user`
(`user` is where agents stand while they wait for the player). Anchor names:

| name | meaning |
|---|---|
| `desk` | slot 1 of the shared desk station |
| `desk_2` .. `desk_N` | more slots (up to 32 per station) |
| `desk_builder-a` | a personal spot: that agent always uses it for that station |
| `lounge`, `lounge_2`, ... | where off-shift agents idle |
| `overflow_sign` | a vanilla sign the player placed; the mod writes "+N more agents" and names on it |
| `cam_*` | free viewpoints for `anchor tp` (QA cameras, keep yaw and pitch); agents ignore them |

Stand on the spot, face the way the agent should face, and run (op level 2):

```
/agentcraft anchor set desk_builder-a        # block centre, facing rounded to N/E/S/W
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
    "desk_builder-a": {"x": 104.5, "y": 64, "z": 196.5, "facing": "north"},
    "overflow_sign":       {"x": 99, "y": 65, "z": 201}
  }
}
```

`y` is the block the agent stands **in** (feet), one above the floor. `facing` is
north/south/east/west, or give `yaw` in degrees. All anchors share one dimension (the HQ is one
place).

### 2. Which spot an agent gets (`StationAssigner`; `dev/tests/StationAssignerCheck.java`)

The adapter always sends a valid station (`../hermes-adapter/MAPPING.md`: `desk` when working and
nothing more specific applies, `lounge` when idle, `user` while waiting on the player). Per agent, first
match wins:

1. its personal spot for that station (`desk_<agent>`),
2. the slot it already had there (agents do not shuffle when others come and go),
3. the first free slot of the station.

Missing anchors: a station with **no anchor at all** is logged once (`no anchor for station
'testbench': ...`) and its agents take a free lounge slot. Agents left without a slot (for example
twelve agents all waiting at a `user` station with two slots) **fan out** (card 4) over distinct
free standable cells within `FAN_RADIUS` (6) blocks of the station's first slot, else the lounge's,
else the nearest configured anchor: the two-block lattice first (so neighbours' nameplates stay
apart), cells in front of the anchor before the ones behind it, never on another anchor or another
agent's cell, the same cell again on the next tick. Only when no free cell is left does an agent
share the anchor spot; the client then collapses those plates into one with a "+N here" pill
(section 5). Checked by `StationAssignerCheck` (12 agents waiting, walled-in nook, determinism).
NPCs never roam: they only walk to their target, and an NPC with no path, no progress for
`teleportAfterSeconds`, a fall below its spot, or a target more than 48 blocks away is put on the
spot. With no anchors at all the NPCs stand in a row next to the world spawn (card-1 behaviour).

### 3. Monitors, status lamps, fleet beacon

Three ordinary blocks in the Decorations creative tab (or `/agentcraft give monitor|lamp|beacon
[count]`): **Agent Monitor**, **Agent Status Lamp**, **Fleet Beacon**. The player places and breaks them like
any block (they drop themselves); they never tick and only hold a binding.

| block | bind to | shows |
|---|---|---|
| Agent Monitor (faces you when placed) | an agent id | name, state, activity and the agent's last `monitorLines` log lines (`agent.log` from the adapter, privacy-filtered there) |
| Agent Status Lamp | an agent id, or `fleet` | that agent's status colour, or the fleet colour |
| Fleet Beacon | nothing needed | a beam in the fleet colour (put it on the roof) |

Fleet colour, most urgent first: someone waiting on the player (clay) > an error (red) > anyone working or
thinking (teal) > all idle (grey). With the adapter offline the beam and lamps go dark.

Look at the block (within 8 blocks) and bind it:

```
/agentcraft bind builder-a               # monitor or lamp at the crosshair
/agentcraft bind builder-a 3 2           # monitor: 3x2-block screen (w 1..8, h 1..6)
/agentcraft bind fleet                   # lamp: fleet colour
/agentcraft bind overflow                # looking at a vanilla sign: it becomes the overflow sign
/agentcraft bind clear
/agentcraft bind <agent|fleet|overflow|clear> <x> <y> <z> [w h]   # console form
```

A monitor belongs at the agent's desk, but the binding is by agent id, so it can hang anywhere. Text
is drawn client-side only within `monitorRenderDistance` blocks and changes only when a new log line
or state arrives.

### 4. Task wall, library, goal atrium (card 3, read-only)

Three more blocks in the Decorations tab (or `/agentcraft give taskwall|library|atrium [count]`):

| block | bind to | shows |
|---|---|---|
| **Task Wall** (faces you) | a board slug, or `all` (default) | the Kanban as five columns `todo / doing / review / done / blocked` (cancelled hidden; the adapter maps Hermes `triage`/`ready` to todo and `running` to doing). Each card: the full title wrapped by pixel width (up to three lines, only the last one ellipsized), assignee in its agent colour, a priority pill. The Done column is labelled with its window ("last 3 days"). A column with more cards than fit flips pages every `wallPageSeconds`. Level of detail by distance: see section 5. |
| **Goal Atrium** (faces you) | a board slug, or `all` | a progress ring (done / total of that board, labelled "all time"), counts of todo, doing, review and blocked, "N done in the last 3 days" (the same number as the wall's Done column) and "N decisions need you" (count only). |
| **Agent Library** | a board slug, or `all` | a book block; the screen lists the library notes (below). |

Right-click any of them for its screen. The **task wall screen** lists every card with column filter
buttons; clicking a card shows its detail (description, dependencies, assignee, status, board). The
**library screen** has a search box (matches title and text) and a scrolling text view. Nothing on
these screens changes Hermes: no console, no answering decisions, no task actions (P3).

Bind exactly like a monitor: look at the block (or use the console form) and

```
/agentcraft bind all 5 3        # task wall: 5x3-block screen (w 1..8, h 1..6), every board
/agentcraft bind main 3 3       # atrium for one board, 3x3 panel
/agentcraft bind all            # library: every board's notes
/agentcraft bind clear          # back to the default (all boards)
/agentcraft bind main <x> <y> <z> [w h]   # console form
/agentcraft board               # what the server holds: tasks, goals, notes, blob sizes, per-board summary
```

Build them as you like: the screen is drawn on the block's front face, centred left-right on the
block and growing upward from its bottom edge (`w x h` blocks), so put the Task Wall block in the
middle of the bottom row of a wall of any blocks; an
atrium panel on a pillar or floor wall, the library on a lectern. Bindings are saved with the block,
so a server restart keeps them (verified: wall still bound after a restart with no re-bind).

The **Fleet Beacon** keeps its card-2 beam and now also shows the all-boards summary as a floating
label above it (within `wallRenderDistance`) and in chat on right-click
("All boards: 3 todo, 2 doing, 1 review, 2 blocked, 4/12 done (33%), 2 decisions need you").

Library notes come only from text the adapter already filters for the monitors and plates (agents'
`PLAN:` / `HANDOFF:` / review-verdict comments, the 12 newest done cards' summaries, a per-board
overview, open decision questions). Each is privacy-filtered as a whole before it is cut. Hermes
memory files, `personal-*.md`, cron prompts, profile configs and human comments are never sources;
see `../hermes-adapter/MAPPING.md` ("Library").

Data path: the adapter's `task.upsert`, `goal.upsert` and `memory.upsert` messages go into
`server/BoardSync`, which keeps at most 256 tasks (cancelled, then the oldest done cards dropped
first), 16 goals and 64 notes, and sends two binary blobs (board, library) over the existing
`agentcraftgtnh` channel in 30 kB parts, at most once per `boardSyncSeconds` (2) and only when the
bytes changed; a blob is capped at 512 kB. Players who log in get the current blobs. The client
(`state/ClientHq`) only reads them; the screens keep scroll and filter state locally.

### 5. UI toolkit, panels and the office layout (card 4)

Every screen and in-world panel is drawn with one toolkit (`ui/`), in the bundled fonts instead of
the Minecraft font:

- **Fonts** (`assets/agentcraftgtnh/fonts/`, SIL Open Font License 1.1, licence texts next to the
  files): Nunito Regular, Bold and ExtraBold for everything, JetBrains Mono for log lines and ids.
  `UiFont` rasterizes the .ttf once with `java.awt` (only `Font`, `BufferedImage`, `Graphics2D`:
  the headless-safe part of AWT, no windows or `Toolkit`) at 64 px per em into a mipmapped alpha
  atlas and draws glyph quads at any size, so text is crisp up close and does not shimmer far away.
  If AWT cannot load a font, the same API falls back to the vanilla font, so a screen never breaks.
  Java support: verified on the plain Forge client (Java 8). GTNH 2.9.x runs on Java 17+ through
  lwjgl3ify; those runtimes ship `java.desktop` (the module with `Font`, `BufferedImage`,
  `Graphics2D`), and only AWT windowing/`Toolkit` clashes with LWJGL3's GLFW (notably on macOS),
  which `UiFont` never touches. The GTNH client render itself is not verified yet (card 4).
- **Theme** (`Theme`, pure Java): a dark walnut theme with cream text and paper cards (default) and
  a light one. Body text is at least 4.5:1 contrast, headlines 3:1 (WCAG AA); agent and status
  colours from the adapter go through `Theme.readable` until they meet 4.5:1 on their background.
- **Text layout** (`TextLayout`, pure Java): wraps and ellipsizes by pixel width, never by a
  character count. The full title is always in the detail view.
- **Primitives and widgets** (`Ui`, `Widgets`): segmented panels, cards, status pills, progress
  ring, buttons, tabs, scroll lists, a search field, tooltips. GUI sizes follow the GUI scale.
- **Nameplates** (`client/PlateLayout`, `ui/PlateDeclutter`): agents on the same spot collapse into
  one plate with a "+N here" pill; the others are laid out in screen space so they never cover one
  another: the plate under the crosshair and the nearest keep their spot, farther ones shrink (name
  and state, then name only) or rise a tier, and only as a last resort become their bare `!` or
  status dot. Look at an agent to read its full plate. Checked by `dev/tests/PlateLayoutCheck`.

**Panels.** An in-world screen is a *panel type* (`ui/panel/PanelRenderer`) registered by id in
`PanelRegistry`, reading a *data source* registered there too:

| panel id | source (binding) | block | detail by distance |
|---|---|---|---|
| `kanban` | `board` (slug or `all`) | Task Wall | near: cards with three-line titles, assignee, priority; mid: two-line titles, larger counts; far: column names and big counts |
| `goal` | `board` | Goal Atrium | near: ring "all time", open counts, "N done in the last 3 days", decisions; far: big percentage and decisions |
| `agent-monitor` | `agent` (agent id) | Monitor | near: header strip, activity, log tail in mono; mid: name, state, activity big; far: name and state |

The level of detail comes from the on-screen size of one block (pixels per block for the current
window height, FOV and distance) against `lod.nearPx` / `lod.midPx`, with a 10 % band so it does not
flicker at a threshold. Each panel is drawn at `pxPerBlock` canvas units per block.

**Layout file.** `config/agentcraftgtnh/office-layout.json` (client; written with the defaults on
first start, re-read a few seconds after a hand edit) says which panel each block kind shows, its
theme and resolution, the detail thresholds and optional per-block overrides:

```json
{
  "version": 1,
  "theme": "dark",
  "lod": {"nearPx": 110, "midPx": 85},
  "blocks": {
    "task_wall":   {"panel": "kanban",        "pxPerBlock": 128},
    "goal_atrium": {"panel": "goal",          "pxPerBlock": 128},
    "monitor":     {"panel": "agent-monitor", "pxPerBlock": 128}
  },
  "instances": {"0:-72,4,-221": {"panel": "goal", "theme": "light"}}
}
```

Extension points (used by later cards; nothing else has to change):

1. a new panel type: implement `PanelRenderer` (id, source, `render(PanelContext)`), call
   `PanelRegistry.register` in `ClientProxy`, name it in the layout file;
2. a new data source: `PanelRegistry.registerSource("id", binding -> data)`;
3. the in-game edit tool (card 6, below) keeps a server-side `hq-layout.json`; its display options
   (theme, detail thresholds) override this client file, which stays the local default. Adding a
   panel type or a block kind needs no editor change: the palette lists every registered kind.

**One count, two windows.** The adapter sends every done card ever as the goal's `counts.done`
(the ring, "all time") and the done cards inside its task window as `doneRecent` with `windowDays`
(the wall's Done column, "last 3 days"). Wall, atrium, beacon label and task wall screen all read
these from `client/BoardView`, so they cannot disagree; the screen's Done filter explains the two
numbers. Older adapters without `doneRecent` fall back to the list count.

### 6. The office edit tool (card 6)

The office stays the player's build: the mod never generates or breaks world blocks on its own. Card 6
adds a tool that makes *its own* parts of the office (panel blocks, their bindings, sizes and
labels, the station anchors and the display options) friendly to change and reversible.

**Getting started.** An op runs `/agentcraft give edittool` (not in any creative tab), then
sneak + right-clicks with it to enter edit mode (the item glows; the server checks the op level).
In edit mode:

- looking at a panel shows an overlay: what it is, what it is bound to, its size, facing and
  position, with corner handles on the panel's whole face; looking at an anchor marker shows the
  station, slot and facing. All anchors show as markers with name plates (green = station spot,
  cyan = camera, yellow = block anchor).
- right-click a panel: the **Panel Inspector** (rebind from a searchable list of agents, boards
  and sources, no typed ids; resize w x h with a live outline in the world; label; per-panel theme;
  duplicate next to it; delete with a second click to confirm);
- right-click an anchor marker: the **Anchor Editor** with that anchor selected;
- right-click anywhere else: the **Office editor** with three tabs:
  - **Panel palette**: every registered panel kind (task wall, goal atrium, library, desk monitor,
    status lamp, fleet beacon, overflow sign and anything later cards register) with a preview
    and "give me this block". The list comes from the registry: a new kind needs no editor change.
  - **Anchor editor**: every station from `hq-anchors.json` grouped with slot counts, missing
    stations (agents of a station without an anchor still fall back to the lounge), and for the
    selected anchor: move to my crosshair / my feet, rotate, add a slot, teleport, remove.
  - **Layouts & undo**: undo/redo with the step names, named snapshots (save, diff against the
    current layout, restore), presets and imports with a dry run (what would change, conflicts,
    bounding box) before "apply", export, display options, and the lock.

Every action is a request to the server, which re-checks permission, lock, limits and the world
before anything happens; the client never changes the world itself.

**Commands** (all still work without the tool; `/agentcraft anchor ...` and `/agentcraft bind ...`
behave as before and are now recorded, undoable and blocked while locked):

| command | what |
|---|---|
| `/agentcraft edit status` | lock, panels / max, undo and redo depth, snapshots, protected areas, files |
| `/agentcraft edit undo` / `redo` / `history` | step back / forward (64 kept, the last 20 survive a restart) |
| `/agentcraft edit snapshot save\|diff\|restore\|list <name>` | named layouts; diff lists what restore would change |
| `/agentcraft edit preset [<name> add\|replace]` | list presets, or dry-run one at your feet facing your way |
| `/agentcraft edit export <name> [radius] [keep]` | write `exports/<name>.json` relative to where you stand |
| `/agentcraft edit imports` / `import <name> [add\|replace]` | list importable files / dry-run one |
| `/agentcraft edit apply <token>` | apply your own dry run (5 minutes; refused if the area changed since, so you get what the dry run showed) |
| `/agentcraft edit scan [radius]` | adopt panels placed before card 6 (or with `/setblock`) into the layout |
| `/agentcraft edit lock [reason]` / `unlock` | the panic switch (below) |
| `/agentcraft edit audit [n]` / `reload` | last audit lines / re-read the layout files |

**Files** (next to `hq-anchors.json`, all human-readable JSON, written to a temp file and moved over
the target so a crash never leaves half a file; names are `[a-z0-9_-]`, max 32):
`hq-layout.json` (the per-world layout: panels, display options, the last 20 undo/redo steps,
`"schema": 2`), `edit-lock.json` (while locked), `layouts/` (snapshots), `exports/`, `imports/`,
`presets/` (your own; `open-office`, `noc-wall` and `focus-pods` are bundled). A card 4
`office-layout.json` (`"version": 1`) is migrated (theme and detail thresholds kept); a layout
written by a newer schema makes editing read-only instead of being overwritten.

**Sharing layouts.** An export stores positions relative to the origin you stand on and your
facing; agent ids, board slugs and labels become placeholders unless you add `keep`. An import or
preset is placed at your feet, turned to your facing, and always goes through a dry run that shows
the bounding box, every change and every conflict. `add` only fills empty cells (an existing panel
is kept; a station that already has an anchor gets a new numbered slot). `replace` first removes
every AgentCraft panel of the layout and every non-camera anchor, then places the new one; the dry
run lists all of it, and undo reverts the whole apply in one step.

**Safety rules** (enforced in the pure-Java engine and again in the Forge world port):

- the tool only places this mod's panel blocks, and only on air or over another AgentCraft panel;
  it only removes or changes AgentCraft panels, and only moves this mod's anchors. Any other block,
  tile entity or GregTech machine in the way is listed as a conflict and skipped, never replaced;
- nothing happens in an unloaded chunk, outside the box a dry run confirmed, or within a protected
  radius (`protectedAreas`, empty by default);
- only ops at `opLevel` (and, if set, only `allowedPlayers`) may edit; the server console always may;
- limits: `editsPerSecond` per player (bursts of twice that), `maxPanels` per layout,
  `maxLayoutKB` per layout / snapshot / import file, `maxImportSpan` per import or preset;
- `/agentcraft edit lock` (any op allowed to run `/agentcraft`, even one not on the editors list)
  stops the tool, every edit command, anchor and bind changes and every layout write server-wide
  until an editor runs `unlock`. The lock survives a restart;
- every placement, removal and change goes to `agentcraft-edit-audit.log` in the server directory:
  UTC time, who, what, where, before and after (block id/meta or value). Append-only, rolls to `.1`.
- nothing here talks to Hermes: the adapter stays read-only, and no network listener changes.

**Tests.** `dev/tests/EditCheck.java` (run by `dev/tests/run.sh`, no Minecraft needed) drives the
same engine with a fake world: operation log and undo/redo (including the 64-step cap and the 20
persisted steps), snapshots, diff and restore, presets and import conflicts (stone, a tile entity,
a foreign block in a panel cell), the confirmed box, schema migration and refusal of newer schemas,
rate limits, panel and size caps, the lock, protected radii, the audit record, and a randomized run
of thousands of edits checking that nothing but AgentCraft panels is ever placed or removed.

**Verified where.** The screens and edits were exercised on the plain Forge 1.7.10 dev client and
server with generic fixtures (`dev/qa-arena-card6.txt`; the `devact` QA hook in `DevShots` runs
editor actions as the dev player and exists only in a dev client started with
`-Dagentcraft.dev.shotOnChat`). Not verified yet: the full GTNH client and a GTNH server with
GregTech machines next to the panels (the engine's foreign-block rules are what protect them).

### 7. Ops panels and the decision toast (card 5b, read-only)

Besides the agents, the adapter can forward what goes on around them: host and service health,
scheduled jobs, provider usage and alerts (the `ops.*` messages, `../docs/ops-protocol.md`; the
sources are adapter plugins, `../hermes-adapter/OPS-PLUGINS.md`). The mod only reads them.

**Server side** (`server/OpsSync`). The bridge's hello opts in with `features: ["ops"]` (config
`ops.opsFeeds`); `ops.*` messages go into `ops/OpsModel`, which follows the adapter's reference
receiver: a snapshot replaces everything, an upsert replaces one entity, a remove drops one, unknown
`ops.*` types are ignored, and every list is capped (256 services, 128 jobs, 32 usage windows,
100 alerts, 16 sources; a snapshot's `limits` can lower these, never raise them; strings are
re-capped and numbers clamped). The clients get one binary blob, re-encoded at most every
`opsSyncSeconds` and only while something changed, and sent only when the bytes differ from the last
one; a player who logs in gets the current blob. Open decisions travel in a second small blob
(checked every second, change-only): the details of the newest 32 plus the ids of every open
decision and a "complete" flag, so a decision pushed out of the 32 shown is still known to be open
(see the toast rules below). Nothing flows back to the adapter.
`dev/tests/OpsCheck.java` replays a recorded adapter wire trace (`dev/tests/make_ops_trace.py`, the
generic mock source) and checks that the mod's model equals the adapter's after every step.

**Four panel kinds** (blocks `fleet_board`, `cron_board`, `usage_panel`, `alert_feed`), placed and
bound like the card 3 and 4 panels: `/agentcraft bind <filter|all> x y z [w h]`, or the edit tool.
The binding is a filter: `all` (default), or a group, source id or provider name
(`/agentcraft ops` lists what the current data offers).

| panel | far away (headline) | up close |
|---|---|---|
| Fleet board | "1 down" / "2 degraded" / "source error" / "2 unknown" / "all up", counts | a tile per host or service, lamp colour up / degraded / down / unknown, CPU, RAM and disk bars when reported |
| Cron board | "1 failed" / "source error" / "1 unknown" / "all ok" | each job with last run (and its result) and next run; failed jobs highlighted red, paused ones grey |
| Usage panel | the lowest "% left" and which provider | a bar per provider window with the % left and when it resets |
| Alert feed | "2 open" / "alerts unverified" / "no alert source" / "no open alerts" | the alerts, open first then newest, severity coloured (critical, warn, info), resolved ones grey; a long feed pages by itself every `wallPageSeconds` (no input) |

Each panel grows its type to fill the screen when the list is short and pages when it is long.

**Only confirmed good data is green** (`ops/OpsHealth`, checked by `OpsCheck`). An entry is green
only when its own state is good and its source is healthy. Unknown hosts, jobs that never ran or
report unknown, and usage without a remaining % are grey, and they keep the headline grey: "all up"
and "all ok" need every entry confirmed. When a source is in `error` or `stale` the adapter keeps
its last good data on the board (the protocol says so), but the panels treat it as "last known":
its good entries and bars turn grey ("up · last known" up close), bad ones stay red or amber (old
data may still warn, it never reassures), and every panel that source feeds names the problem:
the far-away headline turns amber ("source error" / "data stale", unless something is already
down, failed or critical) with the source on the second line, and the source line sits at the
bottom of the panel at mid and close range. The alert feed follows the same rule ("alerts
unverified" while a source that may carry alerts is failing; "no alert source" in grey when no
source reports alerts at all). A source that never reported which kinds it carries counts for
every panel.

**The edit tool needs no change for them.** `ops/OpsPanels` registers the four kinds in the same
`PanelTypes` registry as every other kind, so the palette lists them, the inspector rebinds and
resizes them (its binding list comes from a registered choices hook that lists the ops filters, not
from editor code), and presets, export and import carry them. `OpsCheck` proves it: it places,
inspects, rebinds, resizes, exports and removes each of the four through the card 6 engine.

**Fleet beacon, fleet lamps and atrium = worst of (agents, ops).** The ops state is reduced to one
value: `error` (a service down, an open critical alert or an enabled job whose last run failed),
`warn` (a degraded service, an open warning, an ops source in error or stale), `ok`, or `none` (no ops
data). That is combined with the agents' fleet state in `OpsData.combineFleet`, most urgent first:
someone waiting on the player > error (an agent or ops) > warn (ops only, amber) > working >
thinking > idle. Offline (adapter link down) stays offline, because then nothing is known. The goal
atrium shows a small "ops ok / ops: warning / ops: problem" pill so the colour is explained.

**Decision toast.** When a NEW decision or approval needs the player, the client shows a HUD card
(title, agent, card id, age) and plays a sound. Click it or press **N** (rebindable under
Controls, "AgentCraft") to open the read-only decision screen (question, options and context;
answering is a later card). Only decisions toast: alerts and ops events never do. Rules
(`ops/ToastPolicy`, checked by `OpsCheck`):

- dedupe by decision id; ids are remembered in `config/agentcraftgtnh/toast-seen.txt` (30 days, at
  most 2000), so nothing replays after a reconnect or a client restart, and a decision already
  older than `maxAgeMinutes` when the client first sees it is not toasted. If more decisions are
  open than the client can remember (only possible while the server's id list is cut), the oldest
  are not just forgotten but kept in a small fixed-size "seen" filter (saved in the same file), so
  a decision that never closed never toasts twice; the filter can only err towards silence, and the
  next complete id list clears it;
- a decision that closes and re-opens toasts again only after `cooldownMinutes`. "Closes" means the
  server's complete list of open ids no longer has it: a decision that only dropped out of the 32
  shown (because newer ones arrived) is still open and never toasts again when it comes back. If
  the server ever has to cut even the id list, it says so, and the client then treats a missing id
  as "unknown", never as closed;
- at most `perMinute` toasts a minute; the rest fold into "+N more" on the next one;
- `/agentcraft toast mute|unmute|status|test|open` (each player for themself; the server console
  adds a player name). Mute is kept in `config/agentcraftgtnh/toast-muted.txt`; decisions that
  arrive while muted never replay.
- Sound: the vanilla `note.pling` by default (no asset needed), `volume` and `pitch` configurable;
  `sound` takes any sound event name, e.g. one from a resource pack.

**Verified where.** Plain Forge 1.7.10 dev client and server on the agent host with the adapter's
generic `--ops-mock` source and the `demo_hq.py` fixture (`dev/qa-arena-card5b.txt`,
`dev/qa-scenes-card5b.sh`). Not verified yet: the full GTNH client, and real ops sources (only the
mock and a recorded trace of it). The sound was not heard: the headless dev client has no audio
device (the sound engine runs in silent mode there), so only the call to play it was exercised.

### Other commands

`/agentcraft status` (link, counters, anchors, every NPC with position, target and tracked flag),
`/agentcraft agents` (every agent with station, spot and waiting marker), `/agentcraft board`
(card-3 data), `/agentcraft cap <0..64>` (NPC cap until the next restart).

## Config (`config/agentcraftgtnh.cfg`)

- `bridge`: `adapterUrl` (default `ws://127.0.0.1:7878`), `enabled`, `verboseLog`.
- `display`: `displayAgents` (empty = everyone, most urgent first), `maxAgents` (12), and the
  no-anchors fallback row: `spacing`, `spawnAtWorldSpawn`, `spawnDimension`, `spawnX/Y/Z`.
- `hq`: `anchorsFile`, `monitorLines` (9), `monitorRenderDistance` (24), `teleportAfterSeconds`
  (12), `walkSpeed` (0.3).
- `interface` (card 3): `boardSyncSeconds` (2), `wallRenderDistance` (32), `wallPageSeconds` (8).
- `ops` (card 5b, server): `opsFeeds` (true), `opsSyncSeconds` (2).
- `toast` (card 5b, client): `enabled` (true), `volume` (0.8), `sound` (`note.pling`), `pitch`
  (1.2), `seconds` (9), `maxAgeMinutes` (30), `cooldownMinutes` (10), `perMinute` (3).

The dev-copy settings are in `dev/agentcraftgtnh.dev.cfg`.

## Build

```
export GRADLE_USER_HOME=<gradle home> JAVA_HOME=<JDK for Gradle, e.g. 25>   # RFG fetches its Java 8 toolchain
./gradlew --no-daemon assemble        # build/libs/agentcraftgtnh-<version>.jar
```

Card 1 built on the test PC (a portable JDK 25); card 2 also builds on the agent host with a JDK and Gradle
home in the builder's scratch directory (nothing installed system-wide). Set `VERSION=` when the
build directory is not a git checkout. Template:
[GTNewHorizons/ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) (RetroFuturaGradle,
Forge 10.13.4.1614, Jabel; template licence in `LICENSE-template`).

## Dev and QA (never the real world)

- RFG dev server on the agent host or any Linux box (plain Forge + this mod, FLAT world, loopback port 25571):
  `dev/run-hq-qa.sh start <adapter ws url> [display]`, then `cmd`, `run`, `feed`, `stop`. With a
  display a dev client joins and takes a screenshot on `say devshot NAME`. The GTNH buildscript
  gives the dev client `-Xmx6G`; on a memory-capped machine or session, cap the run tasks' heap
  (e.g. `maxHeapSize = '1536m'` for `runClient` from a Gradle init script in your Gradle home), or
  the client grows until the kernel kills the session.
- `dev/qa-arena.txt`: the throwaway test arena (a few `setblock`s, bindings, anchors, cameras) at
  about x -64..-38, y 4, z -237..-212, just south of the FLAT spawn (-56, 4, -246). It is **not**
  the HQ.
- `dev/qa-scenes.sh <fixture dir>`: drives `../hermes-adapter/scripts/demo_hq.py` phase by phase
  (progress note, demo-ready, unblock, crash, all idle) and screenshots each state.
- `dev/qa-arena-card5b.txt` and `dev/qa-scenes-card5b.sh <fixture dir>` (card 5b): the four ops
  panels, an atrium and a fleet beacon north of the FLAT spawn; the scenes shoot them at three
  distances, the beacon after every decision is answered (worst of agents and ops), the edit tool
  on the ops kinds, and a new decision's toast and decision screen. Run the adapter with
  `--ops-mock` (generic mock data only).
- GTNH dev copy (on the test PC, `$AGENTCRAFT_DEV_ROOT/server` or `$GTNH_DEV_SERVER`, GTNH 2.9.0-beta-3): `dev/prepare-dev-copy.sh`
  (once; offline mode, port 25570, loopback bind), `dev/run-dev-server.sh` / `dev/stop-dev-server.sh`
  (heap 1..5 GB, console FIFO, clean `stop`, never kill -9), `dev/run-dev-client-shot.sh`.
  `dev/gtnh-sky-arena.py check|build|clear` writes console command files for the throwaway test
  arena there: a glass platform at y=160 (x -26..4, z 30..51) above the copy's spawn, same layout as
  `qa-arena.txt`; `check` (testforblock air over the whole volume) runs before `build`.
- Environment for the dev scripts (nothing about your machines is hard-coded):

  | variable | default | meaning |
  | --- | --- | --- |
  | `AGENTCRAFT_DEV_ROOT` | `$HOME/agentcraft-dev` | holds the dev copy (`server/`) and a portable JDK (`jdk25/`); its name must end in `-dev` |
  | `GTNH_DEV_SERVER` | `$AGENTCRAFT_DEV_ROOT/server` | the dev-copy server directory (must sit under a `*-dev` directory) |
  | `GTNH_DEV_JAVA` | `$AGENTCRAFT_DEV_ROOT/jdk25/bin/java` | Java for the dev copy |
  | `GTNH_DEV_BIND`, `GTNH_DEV_XMX`, `GTNH_DEV_XMS`, `GTNH_DEV_STOP_WAIT` | `127.0.0.1`, `5G`, `1G`, `180` | bind address, heap, stop timeout |

  The scripts run on the machine that holds the dev copy (the "test PC"); none of them opens an SSH
  connection, so there is no host name to configure. Reach that machine however you like.

The real world on the game server is untouched; installing there needs a fresh backup and the owner's approval.

## Evidence

Run logs and screenshots from the dev copy are kept out of the public repo because they contain real
board content. Verification summary: the mod boots on a GTNH 2.9.0-beta-3 dev copy with all agents
spawning once, walking between stations, following board changes, falling back to the lounge when an
anchor is missing, and restarting without duplicates; screenshots came from a plain Forge client, so
rendering inside the full GTNH client is unverified.
