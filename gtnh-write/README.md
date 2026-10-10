# gtnh-write: the AgentCraft GTNH write module (card 7)

A **separate Forge 1.7.10 jar** (`agentcraftgtnhwrite-<version>.jar`, mod id `agentcraftgtnhwrite`,
`required-after:agentcraftgtnh`) that adds an opt-in, fenced write path from the game to a Hermes
**control service**. Leave the jar out and everything stays read-only: the core jar has no packet, no
`action.` message name and no control client (a build-time scan proves it, see "Tests").

Design and reasons: [`../docs/write-path.md`](../docs/write-path.md). Wire contract (normative):
[`../docs/action-protocol.md`](../docs/action-protocol.md). This module is the *game side* ("g2c" sender,
"c2g" verifier).

## What it does

- **Gate** (write-path 7.1): arms only on a dedicated, online-mode server whose whitelist is exactly
  `write.owner`, with no op but the owner, a version 4 owner UUID, a completed signed handshake with a
  control service whose policy actors are exactly `[owner]`, no lock on either side, a writable audit log.
  Runs at start, every 30 s and on every request/confirm. Any failure **disarms**: everything is refused,
  open confirm tokens and prompts are dropped, the change is audited, `/agentcraft write status` says why,
  the client state shows `writes disarmed: <reason>`. It re-arms by itself when all checks pass again.
- **Control link**: one daemon thread, `ws://` to `write.controlUrl`, reconnect with backoff 1..30 s. Signed
  frames exactly per the contract; handshake `action.challenge` -> signed `hello` -> `ack` -> `action.policy`
  -> `action.state`. Fail closed: link down = every request fails at once, nothing is queued, an unanswered
  request becomes `unknown` after 90 s and is never retried.
- **Receiver rules** (`proto/Receiver`): size, outer shape, constant-time HMAC check, strict payload parse
  (duplicate/unknown keys and wrong types refused), session, direction, `ts` within 60 s and not before the
  game process started, nonce cache never evicted early (cap 4096), refuse-everything latch if the clock
  jumps back by more than 60 s.
- **Requests**: the player's client sends only a capability name and JSON arguments. The server fills
  `id`, `nonce`, `ts`, `session`, `dir` and the **actor** (the authenticated `EntityPlayerMP` game profile).
  Order: per-player token bucket (1 per 2 s, burst 3) -> gate -> actor == owner -> argument validation ->
  link -> policy (enabled, name offered) -> presence (tier 2) -> per-capability limits -> audit line -> send.
- **Confirm** (`card.dispatch`): the control service's `action.prompt` opens a 60 s confirm for the
  requesting player only; confirm must come from the same player, re-runs the gate, is single use, and is
  void after cancel, lock, disarm, connection close, expiry or the player leaving.
- **Write lock**: `/agentcraft write lock [reason]` (any op) or the client lock packet (op only); persisted as
  `write-lock.json` (0600); an unreadable file counts as locked; unlock only by the owner (in game) or the
  console. On lock: prompts and tokens dropped at once and from then on the *only* frame sent is `action.lock`.
  `lockAlsoLocksEdit=true` (default) also locks the card 6 edit tool through a core hook.
- **Audit**: `agentcraft-write-audit.log` in the server directory, card 6 line format, 0600, rolls at 8 MiB
  to `.1`, one line per request, refusal (with why), prompt, confirm, cancel, result, arm, disarm, lock,
  unlock, verify. Tokens and keys are never written. If it cannot be written the module disarms and sends
  nothing (no audit, no action).

## Build

Gradle project like `gtnh-mod` (RetroFuturaGradle, `--no-daemon`). The write module compiles against the
**core's deobfuscated dev jar**; it does not shade it in. Build the core first:

```
cd gtnh-mod   && ./gradlew --no-daemon assemble      # -> build/libs/agentcraftgtnh-<ver>.jar and -dev.jar
cd ../gtnh-write && ./gradlew --no-daemon assemble   # -> build/libs/agentcraftgtnhwrite-<ver>.jar
```

`dependencies.gradle` picks `../gtnh-mod/build/libs/agentcraftgtnh-*-dev.jar` (or `-PcoreDevJar=<path>`).
Install both reobfuscated jars in `mods/` (server and every client; Forge matches the `acwrite` channel).

## Configure (server) - `config/agentcraftgtnhwrite.cfg`, category `write`

| key | default | meaning |
| --- | --- | --- |
| `owner` | empty | the ONE writer, a lowercase canonical version 4 UUID (not a name). Empty = disarmed. |
| `controlUrl` | `ws://127.0.0.1:7879/` | the control service (not the read adapter's port). Loopback; use a tunnel for another host. |
| `keyFile` | empty | HMAC key file: one line of 64+ hex chars, `#` comments and blank lines allowed. Refused if group/other can read or write it (POSIX). There is no other way to give the key. |
| `presenceRadius` | 8 | tier 2 (dispatch, restart, run) needs the player within this many blocks of an HQ anchor in the HQ dimension; no anchors = refused; 0 = off. |
| `lockAlsoLocksEdit` | true | the write lock also locks the card 6 edit tool. |
| `auditLog` / `lockFile` | `agentcraft-write-audit.log` / `write-lock.json` | server-directory relative. |

Server prerequisites: `online-mode=true`, `white-list=true`, `whitelist.json` = the owner only, `ops.json`
empty or the owner only (the owner must be op to run `/agentcraft` commands in game).

## Commands (`/agentcraft write ...`, op level 2, also from the console)

- `status` - armed/disarmed and why, link, policy revision, locks, open prompts.
- `verify` - runs the gate and prints every 7.1 check as PASS / FAIL / OVERRIDDEN (and INFO); each check is
  written to the audit log.
- `lock [reason]` - any op. `unlock` - the owner in game, or the console. `audit [n]` - last lines (1..30).

Without this jar `/agentcraft write` answers "no write add-on is installed".

## Dev override (open question 6)

`-Dagentcraft.write.devOverride=loopback-dry-run` is honoured **only if all of these hold**: dedicated server,
`server-ip` in `server.properties` exactly `127.0.0.1`, `controlUrl` host loopback (`127.0.0.1` / `[::1]`),
the control service's `action.policy` has `dryRun:true`, and no result without `dryRun` was ever seen. Then
the online-mode and version 4 checks show `OVERRIDDEN (loopback dry run)` (never PASS), the owner may be the
offline dev player's UUID, and **every other check still applies**. Otherwise the property is ignored and
`status`/`verify` say why. A result that does not say `dryRun:true` under the override disarms the module and
kills the override until restart, so nothing real can run under it.

## Core hooks (the only changes in `gtnh-mod`)

`dev.agentcraft.gtnh.api.Extensions` holds two nullable provider fields, `server` and `client`; the core
does nothing extra while they are null. Call sites: `/agentcraft write ...` delegate, a right-click on an
agent NPC (`EntityHermesAgent.interact`), the edit-tool lock query (`EditService.denied`, anchor and bind
recording), and a footer strip + status line on the decision screen and the task-wall card detail. The
decision screen keeps saying "read-only: answer outside the game" until a footer delegate is installed.

## Client side API (for the GUI helper)

Package `dev.agentcraft.gtnh.write.client`; no Minecraft types; safe to call from the render thread.

**State** `ClientWriteState` (static): `armed`, `locked`, `hermesLocked`, `dryRun`, `overridden`, `linkUp`,
`reason`, `lockInfo`, `revision`, `policy` (`capabilities` name -> `{tier, confirm, enabled}`, plus `boards`,
`profiles`, `services`, `jobs`, `agents`; `policy.usable(cap)`), `pendingPrompt()` (token, requestId,
`summary` {card, title, board, profile, model, body}, `msLeft()`), `lastResults()` / `lastResult()` (status
`applied|refused|queued|unknown|prompted|cancelled`, `error`, `result`, `audit`, `dryRun`), `chatLines()` /
`chatLines(agentId)`, `canWrite()`, `statusLine()` (`writes armed` / `writes disarmed: <reason>` /
`writes LOCKED: <info>`), `takeOpenChat()`, and `version` (an `AtomicInteger`; redraw when it moves).

**Calls** `WriteClient` (each only sends a request; the server decides everything again; returns false and
records a local refusal when writes are disarmed/locked):

```java
WriteClient.requestDispatch(card, board, profile);      // then watch ClientWriteState.pendingPrompt()
WriteClient.confirm(token);   WriteClient.cancel(token);
WriteClient.lock(reason);     WriteClient.sync();
WriteClient.answerDecision(card, decision, choice, text);   // choice or text may be null, not both
WriteClient.createCard(board, title, body, priority);        // body, priority nullable
WriteClient.editCard(card, title, body, priority);           // nulls = unchanged
WriteClient.comment(card, text);
WriteClient.chat(agent, conversation, text);  WriteClient.ask(agent, text);
WriteClient.restartService(name);             WriteClient.runJob(name);
```

**GUI plug-in points** `ClientSide` (client proxy): set `ClientSide.delegate` (an `Extensions.ClientHooks`)
to draw/click the footer strips on screens `"decisions"` and `"taskwall"` (subject = the selected
`DecisionData.Decision` / `HqData.Task`, or null), and `ClientSide.openChatListener` (called on the client
thread with the agent id after the owner right-clicks an agent NPC).

## Network (`acwrite`)

Client -> server: `Req{cap, argsJson}`, `Confirm{token}`, `Cancel{token}`, `Lock{reason}`, `Sync{}`.
Server -> client: `State{json}`, `Prompt{requestId, token, msLeft, summaryJson}`, `PromptClosed{token, why}`,
`Result{...}`, `Chat{...}`, `OpenChat{agentId}`. Strings are length-checked on read. The core's channel is
untouched.

## Tests

```
cd gtnh-write && dev/tests/run.sh     # pure Java (no Minecraft), JDK 8+: ProtoCheck, GateCheck, ControllerCheck, LockAuditCheck
cd gtnh-mod   && dev/tests/run.sh     # core checks + the core-jar scan when build/libs has a jar
cd gtnh-mod   && dev/tests/core-jar-scan.sh   # fails if any core class contains "action.", "acwrite" or "hermes_control"
python3 gtnh-write/dev/tests/make_vector.py  # the HMAC test vector hard-coded in ProtoCheck
```

## Contract readings picked (the stricter one) and open points

- `action.state.armed=false` from the control service disarms (gate check `hermes-armed`).
- A policy naming a capability outside the table, or changing a tier, is refused as a whole; policy actor
  UUIDs must be lowercase canonical; `limits` values must be scalars (the contract does not type them).
- `action.prompt.summary` needs all six keys, each a string up to 600 chars.
- A result after `action.confirm` may name the original request id or the confirm frame id; both are mapped.
- `action.lock` carries the configured owner UUID as `actor.uuid` (the control service refuses any other
  actor) and the real sender in `actor.name` and the reason (`by <name>: ...`); the console shows as `console`.
- `ts` must not be before the JVM start time: with a control host clock more than a few seconds behind this
  one, the first handshakes right after a server start may be refused until the clocks agree.
- The WebSocket read timeout of the reused core client is 60 s, so the control service must send traffic or a
  WebSocket ping at least that often (the read adapter pings every 15 s).
- Not exercised end to end: the Minecraft-bound classes (`mc/`, `client/`) and the live link were compiled and
  built but not run; no server or client was started for this card. The wire, gate, controller, audit and lock
  logic is covered by the pure checks.
