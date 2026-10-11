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

## Security review hardening (card 7 review, F4 / F5 / F8 / F11)

- **F4, queued frames after a lock or disarm.** `Controller.Uplink.invalidate()` is called on the game lock and on
  every armed -> disarmed transition (and whenever a Confirm prompt is open): `ControlLink` marks the connection
  dead under its send gate, empties the outbox and **aborts** the socket (high-risk review r1, R4: `WebSocketClient.abort()`
  closes at once with SO_LINGER 0, sends no close frame and takes no lock, so a writer blocked on a full socket
  fails at once and the server thread never waits for it). The writer re-checks the gate after every dequeue, so
  a frame still queued, or dequeued but not past the gate, is never written; a frame already past the gate can
  leave only until the abort, i.e. before `invalidate()` returns; after it returns no byte leaves on that
  connection. A protocol violation seen by the reader also aborts instead of closing gracefully. The link
  reconnects by itself and announces a held lock first thing (`action.lock`). The control service's
  connection-close cleanup voids every token of the old connection. Checks: `ControllerCheck.uplinkInvalidation`
  (queued request, queued confirm, closing link, security disarm; staying disarmed does not bounce the link) and
  `LinkCheck` (the real `ControlLink` and `WebSocketClient` over loopback with the signed handshake: lock, disarm
  and outbox overflow return within 500 ms while the writer is blocked mid-write on a peer that never reads; a
  frame held on latches between dequeue and send start is never written after a lock; `abort()` frees a stuck
  writer and reader and sends no close frame). Residual: the reader's reply to a server close frame is still a
  graceful close, which can wait for a stuck writer; it runs on the link thread, never the server thread, and the
  next lock, disarm or outbox overflow aborts it.
- **F5, lock file.** `WriteLock.load` reads attributes with `NOFOLLOW_LINKS`; only `NoSuchFileException` means
  unlocked. Access denied, any other I/O error, a directory or other non-regular file, and a symlink (dangling or
  not) latch locked. A lock already held in memory still survives a vanished file. Checks: `HardeningCheck.lockFile`.
- **F8, token lifecycle.** A new policy revision closes every local Confirm prompt; a replaced prompt and a
  departing player's prompt are cancelled upstream; `action.cancel` no longer needs request-bucket credit;
  voided prompts resolve as `cancelled` at once (see the open points below); the security disarm invalidates the
  uplink. Checks: `ControllerCheck.tokenLifecycle`.
- **F11, transport limit.** `WebSocketClient(maxMessage, strict)` is an opt-in constructor: the per-message limit
  (all fragments together) is checked from the frame header before any payload is allocated or read; binary
  frames, a new data frame inside an unfinished message, oversized or fragmented control frames and invalid UTF-8
  close the connection. The write link uses `new WebSocketClient(16384, true)`; the read client keeps its defaults
  (16 MiB, lenient). The option names nothing about the write wire, so the core-jar scan stays clean. Checks:
  `HardeningCheck.transport` (loopback server, including a 1 GiB declared length that is refused without waiting
  for it).
- **Title row.** On the task wall and the decision screen the left title text (scope + the add-on's status) is
  ellipsized to the width the right-hand stats / pill leave free, so `writes locked: ...` can no longer be drawn
  over `open N / M done all time`.

## Core hooks (the only changes in `gtnh-mod`)

`dev.agentcraft.gtnh.api.Extensions` holds two nullable provider fields, `server` and `client`; the core
does nothing extra while they are null. Call sites: `/agentcraft write ...` delegate, a right-click on an
agent NPC (`EntityHermesAgent.interact`), the edit-tool lock query (`EditService.denied`, anchor and bind
recording), and a footer strip + status line on the decision screen and the task-wall card detail. The
decision screen keeps saying "read-only: answer outside the game" until a footer delegate is installed.

## Client screens (card 7, part A)

Package `dev.agentcraft.gtnh.write.client`. Everything sends only through `WriteClient`; the server and the
control service decide again, so a modified client gains nothing. Without this jar the core screens keep
saying "read-only" (the core only asks the hooks this jar installs).

| screen | where it comes from | what it does |
| --- | --- | --- |
| **Footer strip** (`WriteFooter`) | under the detail pane of the core's decision screen and task wall | status pill `ARMED` / `DISARMED` / `LOCKED`, a `DRY RUN` badge, the sentence `writes armed` / `writes disarmed: <reason>` / `writes locked: <reason>`, the one-click **Lock writes** button (no confirmation) and **Write actions** |
| task wall footer | selected card | **Dispatch...** (pick a builder profile, then the Confirm screen), **Edit**, **Comment**, **New card**; a disabled button says why in its tooltip |
| decision footer | selected decision | a plain question: its offered choices as buttons (sent at once); an open question: a text box + Send; a PERMISSION halt: only **Deny** (+ an optional note) and "approve outside the game"; hand-off and unrecognised decisions: read-only with that sentence. The kind is guessed with `DecisionKind` (a mirror of the control service's `classify.py`; display only, the service classifies again) |
| **Confirm** (`GuiDispatchConfirm`) | the control service's `action.prompt` (sent to the requesting player only) | what will run (card id + title, board, builder profile, model, first body lines), a ring + "Expires in N s" countdown (60 s), **Confirm** / **Cancel**, a DRY RUN badge; closes itself on expiry, lock, disarm, control-link loss or when the server drops the prompt; closing it any other way cancels the token; after Confirm it shows the result (applied / refused / unknown, DRY RUN, audit id, what the mock recorded) |
| Dispatch picker (`GuiDispatchPick`) | **Dispatch...** | the policy's builder profiles as buttons; sends `card.dispatch` only |
| Card forms (`GuiCardForm`) | **New card** / **Edit** / **Comment** | simple forms (board, title, details, priority); only changed fields are sent on edit |
| **Write actions** (`GuiWriteActions`) | footer strip | **Restart** buttons for the policy's services, **Run** buttons for its jobs, the last results |
| Chat window (`GuiChatWindow`) | right-clicking an agent NPC (owner only; `ClientSide.openChatListener`) | a conversation with one agent (`agent.chat`, stable conversation id); says so when chat is off in the policy |
| `/ask <agent> <question>` (`mc/AskCommand`) | server command registered by this jar | one `agent.ask`; the answer is printed into the Minecraft chat. Same queue, gate, owner check, limits and audit as a screen request |

One hook was added to the core for the decision box: `Extensions.ClientHooks.footerKey` (called first by the
decision screen's and task wall's `keyTyped`; `true` = a text box in the strip consumed the key, Esc included).

**State** `ClientWriteState` (static): `armed`, `locked`, `hermesLocked`, `dryRun`, `overridden`, `linkUp`,
`reason`, `lockInfo`, `revision`, `policy` (`capabilities` name -> `{tier, confirm, enabled}`, plus `boards`,
`profiles`, `services`, `jobs`, `agents`; `policy.usable(cap)`), `pendingPrompt()` (token, requestId,
`summary` {card, title, board, profile, model, body}, `msLeft()`), `lastResults()` / `lastResult()` /
`resultFor(requestId)` (status `applied|refused|queued|unknown|prompted|cancelled`, `error`, `result`, `audit`,
`dryRun`), `chatLines()` / `chatLines(agentId)`, `canWrite()`, `statusLine()` (`writes armed` /
`writes disarmed: <reason>` / `writes locked: <info>`, plus `DRY RUN`), `takeOpenChat()`, and `version`.

**Calls** `WriteClient` (each only sends a request; returns false and records a local refusal when writes are
disarmed/locked):

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

`ClientSide.delegate` / `ClientSide.openChatListener` stay public so another GUI could replace the footer and
the chat window.

### Dev automation (inert unless a property is set)

`-Dagentcraft.dev.writeAuto=1` on the dev client turns on `WriteDevAuto`: the server console's `say devwrite ...`
presses the same buttons a player presses (`click NAME` calls the screen's own `mouseClicked` at the button's
centre, `type TEXT`, `key enter|esc|tab|back`, `chat AGENT`, `actions`, `cmd /ask ...`, `dump`, and `raw CAP k=v...`,
which sends a request the way a modified client would, without the local armed/lock check). Only lines that come
from the console's `say` are obeyed. Together with the core's `agentcraft.dev.connect` / `shotOnChat` (`devgui`,
`devshot`).

## Network (`acwrite`)

Client -> server: `Req{cap, argsJson}`, `Confirm{token}`, `Cancel{token}`, `Lock{reason}`, `Sync{}`.
Server -> client: `State{json}`, `Prompt{requestId, token, msLeft, summaryJson}`, `PromptClosed{token, why}`,
`Result{...}`, `Chat{...}`, `OpenChat{agentId}`. Strings are length-checked on read. The core's channel is
untouched.

## Tests

```
cd gtnh-write && dev/tests/run.sh     # pure Java (no Minecraft), JDK 8+: ProtoCheck, GateCheck, ControllerCheck, LockAuditCheck, ClientCheck, HardeningCheck (795 checks)
cd gtnh-mod   && dev/tests/run.sh     # core checks + the core-jar scan when build/libs has a jar
cd gtnh-mod   && dev/tests/core-jar-scan.sh   # fails if any core class contains "action.", "acwrite" or "hermes_control"
python3 gtnh-write/dev/tests/make_vector.py  # the HMAC test vector hard-coded in ProtoCheck
```

## QA on the test PC (`dev/qa/`)

Throwaway data only, on loopback, small heaps (`_JAVA_OPTIONS` caps the RFG run JVMs; the build's own `-Xmx6G`
would otherwise win), the control service always in `--dry-run` with mock executors and the example board
fixture, a fresh key (mode 600) and a QA-only policy whose `actors` is the run's owner. Never a real world,
server, Hermes or the real `hermes` CLI. Scripts use `$QA_DIR`, `$JAVA_HOME`, `$GRADLE_USER_HOME`; nothing here
names a host.

| file | what |
| --- | --- |
| `run-write-qa.sh` | `prep online\|loopback`, `control start\|stop\|status\|unlock-hermes`, `adapter start\|stop` (the READ adapter on `qa_setup.py fixture` data), `server start\|stop\|cmd\|run\|feed`, `client start\|stop` (private headless mutter), `stop-all`. The server refuses to start without the arena marker `prep` writes |
| `qa_setup.py` | key file, QA policy, fixture Hermes home (cards `t-demo-1..8`, decisions `d-main-101..104`), board fixture, the offline UUID of a name |
| `b1-online-gate.sh` | online-mode: `verify` all PASS, whitelist off/on, op/deop, whitelist add/remove, wrong key, other actor |
| `b1-client-refusal.sh` | the offline dev client is refused by the online-mode server |
| `b2-dry-run-screens.sh` + `b2-scenes-a.txt`, `b2-scenes-b.txt` | loopback dry-run override: verify (OVERRIDDEN, never PASS), then the dev client walks dispatch -> Confirm -> applied (dry run), the lock while a Confirm screen is open, a raw request refused and audited, unlock, decision screens, forms, write actions, chat, `/ask`, with a screenshot per scene |

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
- Run end to end on the test PC (card 7 QA, see "QA" below): the Minecraft-bound classes, the live link, the gate
  in online mode, the loopback dry-run override and the client screens were exercised with a plain Forge dev
  server + dev client and the control service in `--dry-run`. Not exercised: a real (non-dry-run) executor, a
  real Mojang-authenticated login (the dev client is offline), the NPC right-click itself (the chat window was
  opened with the dev automation), and any GTNH modpack.
- The lists' own key listing (`UserList.func_152685_a`) returns player NAMES, not UUIDs; `McFacts` first read
  them as UUIDs and the gate never passed on a real server (found by the card 7 QA). It now reads each entry's
  profile (by reflection on `func_152688_e`) and fails closed (a marker that never equals a UUID) if that breaks.
- Fixed (security review F8): a Confirm request that the write lock or a disarm voids is resolved at once as
  `cancelled` (audit detail `cancelled: locked` / `cancelled: disarmed: <reason>` / `cancelled: policy revision
  changed`), not left to report `unknown` after 90 s. A link that drops still reports `unknown` (the game cannot
  know what the control service did).
- Open: the control service refuses a version 3 policy actor and has no dev allowance, so the loopback dry-run
  override (offline dev player = version 3 owner) cannot talk to the unmodified control service. The QA used a
  throwaway COPY of `hermes_control` with that one check skipped (`QA_ALLOW_V3_ACTOR`), nothing in the repo.
