# Card 7: the write path (design; built on branch, nothing enabled)

Status: **built, awaiting review; nothing is enabled.** The owner approved this design with the
proposed defaults ("Card 7 with proposed defaults"). The implementation follows it: the wire
contract is pinned in [action-protocol.md](action-protocol.md), the control service is
`hermes-adapter/hermes_control/` ([CONTROL.md](../hermes-adapter/CONTROL.md)) and the write module
is the separate `gtnh-write/` jar ([README](../gtnh-write/README.md)). The shipped policy is empty
(every capability off, no actor), and the example policy uses placeholders only. Section 10 below
records which option was taken for each open question. The rest of this document is the design as
approved; the text below this paragraph still describes the state before card 7 was built.

An independent security review of the
first draft has been folded in (signed handshake, nonce and idempotency rules, confirm-token
atomicity, lock semantics, audit failure, chat toolset, decision classification). No code in this
repository acts on Hermes from the game yet. Today the adapter refuses every client intent and every
`action.*` message (`hermes-adapter/hermes_adapter/server.py`, `MUTATING` / `MUTATING_PREFIXES`),
and the mod has exactly one client-to-server packet, the card 6 edit-tool request, which never
leaves the game server. This document says what card 7 will add, what it will never add, and what
has to be true before the first line of it is written.

All names, hosts and addresses below are made up (`host-a`, `service-1`, RFC 5737 `192.0.2.x`).

## 1. What the owner decided

| decision | consequence in this design |
| --- | --- |
| Writes go through a **control service with an allowlist**: answer decisions, create / edit / dispatch cards, chat with agents (click an agent for a chat window, and `/ask`), restart a named service, rerun a scheduled job. | Section 3 lists every action. Anything not in that list has no message, no code path and no policy entry. |
| A Confirm in the game counts as **named approval only for that allowlist**. Hypervisor, storage pools, reverse proxy and single sign-on stay outside the game (chat bridge or terminal). | Those capabilities do not exist in the protocol (section 4.2), so no policy file can switch them on. |
| A **Confirm screen only on builder dispatch.** | `card.dispatch` is the only two-step action (section 5). |
| An **audit log** of every game-originated action and a **one-click write lock** (server-wide, like `/agentcraft edit lock`). | Two audit logs and two locks, one on each side (section 6). |
| The server runs **`online-mode=true` with a whitelist (only the owner) before any write path exists**. | The write module refuses to arm until a verification step passes, and re-checks it on every request (section 7). |
| Protocol: an **`action.*` namespace** with capability and tier tags, checked by a policy on the adapter side; reserved and refused until then. | Section 4. |
| The mod stays modular: **the write module is a separate jar.** | Section 2. Leaving the jar out leaves the office read-only. |

## 2. Shape

```
player's client (Forge 1.7.10)
  agentcraftgtnh          core: office, panels, decision screen (read-only)
  agentcraftgtnh-write    write module: buttons, chat window, /ask, Confirm screen, lock button
        |  own packet channel "acwrite", over the encrypted online-mode connection
        v
dedicated GTNH server
  agentcraftgtnh          read bridge -> adapter (unchanged, read-only, ws://, hello only)
  agentcraftgtnh-write    gate (identity, arming, lock, rate) + game-side audit log
        |  separate connection: action.* frames, each one signed (HMAC) with a nonce and a time
        v
Hermes host
  hermes-adapter          read-only, unchanged; still refuses action.* on its own port
  control service         NEW, separate process and port: policy, ledger, Hermes-side audit + lock,
                          executors: board, chat, service, cron (plugins; Hermes is the first)
```

Why three pieces instead of teaching the adapter to write:

- The adapter opens the Hermes board read-only (SQLite `mode=ro` plus `PRAGMA query_only`) and is
  tested for it. Keeping writes in another process keeps that guarantee true and testable.
- The control service can run as a different user with exactly the permissions its executors need,
  and can be stopped on its own without losing the office view.
- The game client never talks to the Hermes host. Only the game server does, after it has checked
  who sent the request. A modified client can at most send packets to the game server, where every
  rule below is enforced again.

### 2.1 The write module jar

- New Gradle project `gtnh-write/` producing `agentcraftgtnh-write-<version>.jar`, Forge mod id
  `agentcraftgtnhwrite`, depending on the core jar for the UI toolkit and the decision model.
- It registers its **own** network channel. The core keeps its single client-to-server packet (the
  edit tool) and gains no new one.
- The core gets small extension hooks that do nothing without the module: "extra buttons on the
  decision screen", "what clicking an agent does", "extra buttons on an ops tile". Without the
  module the decision screen keeps saying "read-only: answer outside the game".
- A build-time check (like the factory jar's `ReadOnlySurfaceTest`) scans the **core** jar's
  constant pools and fails if `action.` message names, the `acwrite` channel or a control-service
  client ever appear there.
- The module does nothing in single player or on a LAN-opened world: it arms only on a dedicated
  server (section 7).

### 2.2 The control service

- Standard-library Python like the adapter, its own package and its own service unit. Binds
  `127.0.0.1` by default; a LAN bind needs an explicit flag, refuses wildcards, and keeps the
  adapter's peer and Host allowlist and its "no `Origin` header" rule.
- Reads three owner-maintained files that are never in a repository: the HMAC key (a file, mode
  0600, no command-line flag for the key itself), the policy (`policy.json`, section 4.3), and the
  lock file (section 6.2).
- Executors are plugins, like the ops sources: `board` (decisions and cards), `chat`, `service`,
  `cron`. Each receives already-validated, typed arguments and returns a small result dict. An
  executor runs programs only from an argument list fixed in the policy file, never through a shell,
  and never with text that came from the game in the program position.
- Every reply text that goes back to the game passes the adapter's privacy filter (secrets,
  personal notes, addresses) before it leaves the host, exactly like the read path. The filter is
  pattern-based, so it is a second line of defence, not the boundary: the first is what the
  game-chat toolset may read at all (section 3). Chat replies are filtered as a whole (or in
  complete paragraphs), never chunk by chunk, so a secret cannot slip through split across two
  frames.

## 3. The allowlist

Every action has a capability name, a tier (section 4.2) and fixed arguments. There is nothing
else.

| capability | what it does in Hermes | tier | confirm screen | default limits |
| --- | --- | --- | --- | --- |
| `decision.answer` | answers an **open** decision: one of its offered options, or free text when the decision takes text. Uses the same path as the owner's existing answer tool, so the card is commented and released exactly as if answered outside the game. | 1 | no | 20 / hour |
| `card.create` | creates a card on an allowlisted board, unassigned or in triage, tagged as made in the game. Never starts work by itself. | 1 | no | 20 / hour |
| `card.edit` | title, body, priority of a card that is not running; or a comment on any card. | 1 | no | 60 / hour |
| `agent.chat` | sends a message to an allowlisted agent in a chat session and shows the reply in the chat window (in complete paragraphs, after filtering). | 1 | no | 10 / minute, 2000 characters |
| `agent.ask` | `/ask <agent> <question>`: one question, one answer printed in the chat. Same rules as `agent.chat`, no session kept. | 1 | no | 10 / minute |
| `card.dispatch` | assigns a card to an allowlisted builder profile and lets the dispatcher start it. | 2 | **yes** | 6 / hour, 1 per card per 10 minutes |
| `service.restart` | restarts one **named** service from the policy (the game sends only the name). | 2 | no (the button press is the approval) | per service: 1 per 10 minutes; 6 / hour overall |
| `cron.run` | asks the scheduler to run one **named** job from the policy on its next tick. | 2 | no (the button press is the approval) | per job: 1 per 5 minutes; 12 / hour overall |

Rules that hold for all of them:

- **Answering a `PERMISSION` decision is limited.** A permission halt can ask to run anything on any
  machine, which is exactly what must stay out of the game. Proposed default: the game may answer
  `Deny` (and add a note), never `Approve`; the decision screen shows "approve outside the game".
  Open question 1 asks the owner to confirm.
- **Which decisions the game may answer is decided on the Hermes side, from board data, not from
  text the game sends.** Today a decision counts as a permission because its reason starts with
  `PERMISSION`. The control service classifies every open decision itself (block kind plus that
  prefix) and only lets the game answer the ones it classifies as plain questions; anything it
  cannot classify is shown read-only. The game can only pick one of the offered options, or send
  free text where the decision takes text.
- **A game answer, card or comment is never an approval for anything else.** Builders read cards,
  comments and answers, so text written in the game reaches agents. Every write is tagged as made
  in the game, and the agents' own rules treat approvals written inside such text as no approval:
  anything a builder needs permission for is still asked as a permission halt, which the game
  cannot approve. Dispatching a card approves that card's work, not every command the builder may
  later ask for.
- Chat cannot approve, unblock or dispatch anything. A game chat runs in its own session with a
  tool allowlist that Hermes **enforces** (proposed: read-only tools, no shell, no delegation to
  agents with more tools, no credentials beyond what those tools need; open question 3). It never
  resumes a session that has broader tools. If the enforcement cannot be shown in a test, chat
  stays disabled. A one-shot command that bypasses approvals is never used for game chat.
- Service and job names, boards, builder profiles and chat agents all come from the policy file. The
  game shows only what the policy offers (it is sent in `action.policy`, section 4.4), and the
  control service checks the name again on every request.
- Card bodies and chat text are data. They are stored and shown, never executed, and they cannot
  change policy, lock state or the allowlist.

## 4. Protocol: `action.*`

`action.*` is an opt-in extension of [protocol v1](protocol.md), like `ops.*` and `world.*`. It is
carried on the **control endpoint**, not on the read endpoint: the read adapter keeps answering any
`action.` message with a refusal, and that stays tested. An adapter for another agent framework may
serve both on one port if it wants; the rules below do not change.

### 4.1 Opting in and framing

The game server's write module connects to the control endpoint. The handshake is signed too, so
nothing is trusted before both sides have proved they hold the key:

1. On connect the control service sends `action.challenge {session, challenge}` (128 random bits
   each), signed.
2. The write module answers with a signed `hello` with `"features": ["action"]` whose payload
   repeats `session` and `challenge`.
3. The control service answers with a signed `ack {result: {features: ["action"]}}`, then
   `action.policy` (4.4) and `action.state` (6.2).

Anything else first, an unsigned `hello`, a wrong challenge or a second `hello` closes the
connection. None of the read adapter's leniency carries over (it treats any first message as a
hello and does not check versions): here `v` must be `1`, and every payload must name the `session`
and its `dir` (`c2g` or `g2c`); a frame with the wrong session or direction is dropped. A
connection that did not ask for the feature gets nothing.

Every frame, in both directions, has this shape:

```json
{ "v": 1, "type": "action.request", "payload": "<the message as a JSON string>", "sig": "<hex>" }
```

- `sig` = HMAC-SHA256 over the exact UTF-8 bytes of `payload`, with the shared key. The receiver
  checks the signature first (constant-time compare), then parses `payload`. Signing the string as
  sent avoids any canonical-JSON rules between Java and Python.
- `payload` carries `type` again (must match the outer `type`), `session`, `dir`, `id` (request id,
  unique, at most 64 characters), `nonce` (128 random bits, 32 hex characters), `ts` (milliseconds
  since the epoch, an integer) and the body. A payload with duplicate keys, unknown fields or values
  of the wrong type is refused.
- A frame with a bad or missing signature, an outer and inner `type` that differ, a malformed
  `nonce` or `ts`, a `ts` more than 60 s away from the receiver's clock, or a `nonce` already seen
  is dropped, answered with a generic `error` and audited. The signature is checked before the
  nonce takes any space in the cache.
- Accepted nonces are kept until their `ts` is out of the window and are **never evicted early**:
  when the cache is full, new frames are refused (and audited) until entries expire. The cache is
  stored in the ledger, so a restart does not forget it; a frame whose `ts` is older than the last
  start of the service is refused as well. If the clock jumps backwards by more than the window,
  the service refuses everything until an operator restarts it.
- Frames are at most 16 KiB; a larger one closes the connection.

### 4.2 Capabilities and tiers

Each request names its `capability` (section 3) and its `tier`. The control service looks both up
in its own table; a request whose tier does not match the table is refused, so a client cannot
"downgrade" an action to skip its confirmation.

| tier | meaning | from the game |
| --- | --- | --- |
| 0 | read: everything the office already shows | yes (not an action) |
| 1 | records text in Hermes or talks to an agent; carries no approval | yes, if the policy enables it |
| 2 | the game Confirm (or button press) **is** the owner's named approval, only for `card.dispatch`, `service.restart`, `cron.run` | yes, if the policy enables it |
| 3 | everything else that needs named approval: infrastructure, storage, proxies, sign-on, credentials, deploys, merges and pushes, approving permission halts | **never**: no capability name exists |

The world tiers in [world-protocol.md](world-protocol.md) section 7 (AE2 crafts, builds) are a
different axis and are not part of card 7. When they arrive they will use their own capability
names under `action.world.*`, their own module and their own review.

### 4.3 The policy (owner-maintained, on the Hermes host)

```json
{
  "schema": 1,
  "actors": ["00000000-0000-4000-8000-000000000001"],
  "capabilities": {
    "decision.answer": { "enabled": true, "permissionApprove": false },
    "card.create":     { "enabled": true, "boards": ["main"] },
    "card.edit":       { "enabled": true, "boards": ["main"] },
    "card.dispatch":   { "enabled": false, "profiles": ["builder-a"] },
    "agent.chat":      { "enabled": true, "agents": ["helper-a"], "toolset": "read-only" },
    "agent.ask":       { "enabled": true, "agents": ["helper-a"], "toolset": "read-only" },
    "service.restart": { "enabled": false },
    "cron.run":        { "enabled": false }
  },
  "services": { "service-1": { "argv": ["systemctl", "--user", "restart", "service-1.service"], "timeoutSeconds": 60 } },
  "jobs": ["job-a1"]
}
```

- `actors` are Minecraft UUIDs (online-mode, version 4), never names. The game server sends the
  UUID of the player whose authenticated connection sent the packet; the control service refuses an
  actor not in this list even if the game server's own allowlist is wider.
- Capabilities are off unless enabled. An unknown capability name in the file stops the service
  from starting (a typo must not silently enable or disable something).
- The file is read at start and on `SIGHUP`. Nothing in the protocol can change it.

### 4.4 Messages

| type | direction | fields (inside `payload`) |
| --- | --- | --- |
| `action.challenge` | control -> game | `session`, `challenge` (first frame on a connection, 4.1). |
| `action.policy` | control -> game | `actors` (the UUIDs the control service accepts), and what the game may offer: capabilities with tier, confirm flag and limits; service names; job names; boards; builder profiles; chat agents. Display data only; the control service re-checks every request. |
| `action.state` | control -> game | `armed`, `locked`, `lockReason`, `lockedBy`, `since` for the Hermes-side lock (6.2). |
| `action.request` | game -> control | `id`, `nonce`, `ts`, `actor {uuid, name}`, `capability`, `tier`, `args` (typed per capability, every string capped). |
| `action.prompt` | control -> game | for `card.dispatch` only: `re`, `token`, `expiresAt` (60 s), `summary` (card id and title, profile, model, board, first lines of the body). The summary comes from Hermes, not from the request. |
| `action.confirm` | game -> control | `id`, `nonce`, `ts`, `actor`, `token`. |
| `action.cancel` | game -> control | `token`. |
| `action.lock` | game -> control | `reason`, `actor`: tells the control service the game-side lock was set (audit only, 6.2). |
| `action.result` | control -> game | `re`, `status` (`applied`, `refused`, `queued`, `unknown`), `error` (short, filtered), `result` (small dict, filtered), `audit` (the audit line id). |
| `action.chat` | control -> game | `conversation`, `agentId`, `text` (filtered, capped), `final`. |

Semantics:

- **Idempotency:** request ids are scoped to the actor. Before doing any work the control service
  atomically claims `(actor, id)` together with a digest of the request in the ledger (SQLite, kept
  24 hours). The same id with the same digest returns the stored state and never starts the work
  again; the same id with a different digest is refused. A claim moves `pending` -> `applied` /
  `refused` / `unknown`. If the service dies between doing the work and recording it, the claim
  stays `pending` and becomes `unknown` at the next start: it is **never re-run automatically**.
  The guarantee lasts as long as the ledger keeps the id (24 hours); executors also de-duplicate
  where Hermes allows it (for example an idempotency key when creating a card).
- **`unknown`, not `failed`:** if an executor times out or a claim is left `pending`, the result is
  `unknown` and the panel says "check outside the game". The game never retries by itself.
- **Fail closed, no queue:** if the connection is down, requests fail at once in the game. Nothing
  is stored and sent later.
- **Confirm tokens** are 128 random bits, single-use, valid 60 s, and bound to the actor, the full
  dispatch request (card, profile, board), the card's revision and the policy revision at prompt
  time. They are dropped when the player cancels, when the game or Hermes side locks, when the
  write module disarms and when the connection closes.

## 5. The Confirm screen (builder dispatch only)

1. The player presses "Dispatch" on a card and picks a builder from the policy's list.
2. The game server checks the gate (section 7), lock and rate, then sends `action.request`.
3. The control service checks policy and limits, reads the card from Hermes, and returns
   `action.prompt` with a token and a summary built from Hermes data.
4. The write module shows the Confirm screen to **that player only**: what will run, where and with
   which model, with "Confirm" and "Cancel". It closes itself after 60 s.
5. "Confirm" sends `action.confirm` with the token. The game server checks that the confirming
   player is the requesting player and runs its gate again (arming, lock, rate). The control
   service then, in one ledger transaction, consumes the token and claims the dispatch, after
   checking again **everything** it checked in step 3 (actor, policy and its revision, limits,
   Hermes-side lock) and that the card's revision is still the one in the prompt. If anything
   differs it refuses and the player starts again.

The Confirm screen protects against misclicks and stale data. It does **not** protect against a
modified client of an allowed player, which can send the confirm packet without showing anything.
That case is bounded by the allowlist, the rate limits, the audit and the lock (section 8).

## 6. Audit and the write lock

### 6.1 Audit

- **Game side:** `agentcraft-write-audit.log` in the server directory, the same line format as the
  card 6 edit audit (UTC time, who as `name/uuid8`, action, target, before and after, detail;
  control characters flattened, fields capped, append-only, rolls to `.1`). One line for every
  request, refusal (and why), prompt, confirm, cancel and result.
- **Hermes side (authoritative):** an append-only JSON-lines file next to the policy, one record
  per received frame and per executor outcome, with the request id, actor UUID, capability, tier,
  arguments (filtered, capped), decision (refused / prompted / applied / unknown) and duration.
  Chat text is stored filtered and capped at 300 characters.
- The two logs share the request id, so a line in one can be found in the other.
- **No audit, no action.** Unlike the card 6 edit audit (which records a write error and carries
  on), the write path treats the audit as a safeguard: the control service writes and flushes the
  admission record (with the ledger claim) **before** any executor runs, and refuses the request if
  it cannot. The game side disarms when its audit file cannot be written. Keys and confirm tokens
  are never written to either log; refusals of unauthenticated frames are logged at most once a
  second with a counter, so a flood cannot fill the disk. Both files are owner-only (0600) and keep
  the newest 8 MiB plus one rolled file.
- `/agentcraft write audit [n]` shows the last game-side lines; a later panel may show them on the
  wall.

### 6.2 The write lock

- **Game side:** `/agentcraft write lock [reason]` and a lock button in the write module's screens
  (one click, no confirmation). Any op may lock; only the owner or the console may unlock.
  Server-wide, persisted as `write-lock.json`, read at start, and an unreadable lock file counts as
  locked (fail closed), exactly like the card 6 edit lock. The lock takes effect on the server
  thread the moment it is set: every request not yet sent is refused, every open Confirm screen is
  closed and its token dropped, and from then on the module sends the control service nothing but
  the `action.lock` notice.
- **Hermes side:** a lock file the control service checks at admission and again immediately before
  every execution, set and cleared only from the Hermes host (terminal or the owner's chat bridge).
  While it is set every request is refused and audited, whatever the game sends, and outstanding
  confirm tokens are void.
- **What a lock does not do:** it stops new actions; it does not undo or stop work that already
  started. A builder already dispatched keeps running, a job already handed to the scheduler still
  runs, and a restart already issued completes. Stopping those is done outside the game, as today.
- Locking in the game sends a best-effort `action.lock` notice so the Hermes-side audit shows it;
  it does not set the Hermes-side lock (open question 5 asks whether it should). Whether the game
  lock should also lock the edit tool is part of the same question.

## 7. Online mode and whitelist before anything else

In offline mode a player's UUID is computed from the name they type, so anyone can claim the
owner's name. The whitelist is only meaningful with online mode. And in 1.7.10 **ops bypass the
whitelist**, so the ops list matters too.

### 7.1 Arming checks (run by the write module)

| check | how |
| --- | --- |
| dedicated server | `MinecraftServer.isDedicatedServer()`; never on an integrated or LAN-opened world |
| online mode on | `MinecraftServer.isServerInOnlineMode()` |
| whitelist enforced | the dedicated player list's whitelist flag (`isWhiteListEnabled()` exists only on the server side; called only after the dedicated check) |
| whitelist equals the owner | the whitelist holds exactly the one UUID in `write.owner`, and nothing else |
| ops are only the owner | ops skip the whitelist, so any op other than the owner fails the check (an empty ops list passes) |
| the owner UUID is well formed | a version 4 UUID; a version 3 UUID is an offline-mode name hash. This is a sanity check against a mistyped config, not proof of an account: the proof is online mode itself |
| identity per request | the actor is taken from the authenticated connection that sent the packet (`EntityPlayerMP` game profile) and must equal `write.owner` |
| control service agrees | its `action.policy` lists exactly the owner's UUID as actor, and the signed handshake round trip works |
| not locked | game-side and Hermes-side lock both clear |

`write.owner` is one UUID in the write module's config (not a name, unlike the card 6 editor list).
The owner decided that only they write; allowing a second writer later would be a config and policy
change with its own review. The checks run at server start, every 30 seconds, and again on every
request, because `/whitelist off`, `/op` or `/whitelist add` can change them at runtime. Any failure
**disarms**: the module refuses everything, drops open confirm tokens, says why in
`/agentcraft write status`, shows "writes disarmed: <reason>" in its screens and audits the change.
It re-arms on its own once every check passes again.

### 7.2 Verification step (before card 7 code is enabled on any world)

1. In `server.properties`: `online-mode=true`, `white-list=true`. `whitelist.json` holds only the
   owner; `ops.json` holds only the owner (or is empty).
2. Start the server with the write module and run `/agentcraft write verify` from the console. It
   prints every check from 7.1 as PASS / FAIL and writes the result to the audit log. All must pass.
3. Negative tests, each one must be refused at login:
   - an offline-mode client using the owner's name: "Failed to verify username";
   - a second, real account that is not whitelisted: "You are not white-listed on this server!".
4. Live test: with the owner logged in, run `/whitelist off` from the console. Within one request
   (and at most 30 s) `/agentcraft write status` says disarmed, a write attempt is refused and
   audited, and an open Confirm screen can no longer be confirmed; `/whitelist on` re-arms it. Same
   with `/whitelist add <someone else>`, `/op <someone else>` and `/deop`.
5. Wrong places and wrong partners: the module must refuse to arm in single player and on a world
   opened to LAN; with a control service whose policy names a different actor; with a malformed or
   version 3 `write.owner`; and with a wrong key (handshake fails).
6. Save the console output and the audit lines as the evidence for the review. The write path is
   enabled in the policy only after the owner has seen it.

Online mode authenticates every login against the account service and encrypts the connection
between the game client and the server, so other machines on the network cannot read the packets.
It is not a substitute for the checks above (the encryption has no integrity tag of its own): the
server still re-validates every request.

## 8. Threat model

### 8.1 What we protect

The Hermes board (cards, decisions and their answers), the owner's approval authority, agents'
time and tools, the availability of the named services and jobs, secrets on the Hermes host, and
the world itself (which card 7 never touches).

### 8.2 Threats and answers

| threat | answer |
| --- | --- |
| **Someone joins as the owner** (offline-mode name spoof) | online mode is required to arm (7.1); identity is the UUID of the authenticated connection, never a field in a packet |
| **Another player on the server** | whitelist of the owner only, no op but the owner, owner UUID checked in the game *and* in the control service; packets from anyone else are refused and audited |
| **Modified client of an allowed player** (a bad mod in the pack, a stolen session) | it can send any packet and skip any screen, including the Confirm. Bounded by: the allowlist (nothing outside section 3 exists), server-side re-checks of every field, rate limits on both sides, the Hermes-side lock and caps that the game cannot change, and both audit logs. It cannot approve permission halts, reach infrastructure or run arbitrary commands. |
| **Spoofed game server** (any process on the local network or the Hermes host talking to the control port) | peer and Host allowlist, no `Origin`, and the HMAC key: without the key no frame is accepted |
| **Replay** of a captured control frame | signed nonce plus timestamp: older than 60 s, older than the service's last start, or seen before is dropped; nonces are never evicted while still valid; request ids are idempotent per actor, so a replayed id returns the stored state and runs nothing |
| **Replay of a Confirm** | tokens are random, single-use, bound to actor, request, card revision and policy revision, valid 60 s, consumed in the same transaction that claims the dispatch, and void after cancel, lock or disarm |
| **Flooding** | game side: per-player token bucket (1 request per 2 s, burst 3) and the per-capability limits; Hermes side: the same limits again, independently, plus at most 2 connections and 16 KiB frames; unauthenticated frames are refused before they use nonce space or audit space |
| **Text that tries to act** (prompt injection in a chat message, a card body, a decision answer) | text is data: it is never put into an argument list or a shell and cannot change policy or lock. Text written in the game is tagged as such, never counts as an approval for agents, and which decisions the game may answer is decided from board data (section 3). The chat agent runs with the enforced game-chat toolset only |
| **Data leaking back into the game** | first, the game-chat toolset limits what an agent can read; second, every reply passes the privacy filter as a whole before it leaves the Hermes host, as on the read path |
| **Control service down or slow** | fail closed, nothing queued, a timeout is `unknown` and never retried by the game |
| **Key theft** | the key lives in two 0600 files, never on a command line, never in a log; rotating it is replacing both files and restarting |

### 8.3 What the mod can never do

These are properties of the code, not of configuration. Changing them needs a new card, its own
review and the owner's approval.

- Run a shell command, or any program whose argument list did not come from the policy file.
- Send a free-form command, host name or path: only the capability names in section 3 with typed
  arguments.
- Touch the hypervisor, storage pools, reverse proxy, single sign-on, credentials, deployments,
  merges or pushes: no capability exists for them.
- Approve a permission halt (unless the owner answers open question 1 otherwise).
- Edit the policy, the allowlist, the key or the Hermes-side lock.
- Write to the world: that is the separate world-action track with its own tiers.
- Act without the write module: the core jar stays read-only towards Hermes (its one
  client-to-server packet is the card 6 edit tool, which never leaves the game server), and the
  read adapter keeps refusing `action.*`.
- Act on a world in single player, on a LAN-opened world, or on a server that fails 7.1.

## 9. What card 7 would deliver (after approval)

1. The write module jar with the gate, game-side audit and lock, `/agentcraft write status | verify
   | lock | unlock | audit`, and the screens: decision answers, card create / edit, the dispatch
   Confirm screen, chat window and `/ask`, restart and run buttons on allowlisted tiles.
2. The control service with policy, ledger, Hermes-side audit and lock, and the four executors.
3. Tests: pure-Java checks for the gate (every 7.1 failure disarms and drops confirm tokens), the
   token rules, the lock and the rate limits; Python tests for the signed handshake (unsigned or
   repeated hello, wrong challenge, session and direction), signature, malformed and out-of-window
   timestamps, nonce reuse and a full nonce cache, a restart in the middle of a replay window,
   idempotency (same id with the same and with a different digest, a claim left `pending` by a
   crash), tier mismatch, unknown capability, policy typos, refusal when the audit cannot be
   written, decision classification (a permission can never be approved), the lock at admission
   and before execution, the chat toolset boundary, and a fuzz run of random frames; the core-jar
   scan; the read adapter still refusing `action.*`.
4. QA on a test copy only, with the verification step of 7.2 as evidence, and nothing enabled on
   the real world until the owner says so.

## 10. Open questions for the owner

1. **Permission halts from the game:** `Deny` only (proposed), or also `Approve` when the halt
   matches an allowlisted action, or not answerable at all?
2. **Restart and job buttons:** a single press (your decision, as written) or a press-and-hold to
   avoid accidents? Are the default limits in section 3 right?
3. **Chat toolset:** read-only tools without a shell, enforced by Hermes (proposed), or no tools at
   all? (The agent's normal tools with approvals blocked is not offered: it is not an enforced
   boundary.)
4. **Link between game server and Hermes host:** HMAC-signed frames give integrity but not privacy.
   Add TLS with a pinned certificate, run it through an SSH or VPN tunnel, or accept plaintext on
   the home network?
5. **One lock or two:** should the write lock also lock the card 6 edit tool (one panic button), and
   should a game lock also set the Hermes-side lock (then unlocking needs the terminal)?
6. **Testing:** a server in offline mode cannot arm the write module, so card 7 QA needs an
   authenticated (online-mode) test server whitelisted for your account. Is that acceptable, or do
   you want a dev-only override that works only on a loopback-bound server with the control service
   in a dry-run mode that executes nothing?
7. **First allowlist:** which services, which jobs, which builder profiles and which chat agents
   start enabled? (Proposed: none of the tier 2 ones until the verification step has passed.)
8. **Presence:** should tier 2 actions also require being in the office (near the panel), as the
   world-action design asks for its confirmations?
9. **Where the control service runs:** same host as the adapter under its own user (proposed), and
   who besides you may edit the policy file?
10. **Hand-off decisions:** may a "demo ready" style decision (one that sends work on to review) be
    answered from the game, or should those stay outside the game like permission halts?

### 10.1 What card 7 implements for each question

The owner's decision was "Card 7 with proposed defaults". Where the document proposed an option it
is used; where it did not, the most restrictive option was chosen and can be changed in one place.

| # | taken | where it lives |
| --- | --- | --- |
| 1 | proposed: `Deny` only (with an optional note); `Approve` is always refused | `decision.answer.permissionApprove` must be `false`; `true` stops the service |
| 2 | as written: a single press, limits as in section 3 | the restart / run buttons; limits in `docs/action-protocol.md` 4.1 |
| 3 | proposed: read-only toolset enforced by Hermes; because that enforcement cannot be proven inside this repository, chat **ships disabled** | `agent.chat` / `agent.ask` `enabled: false`; a denied toolset name stops the service |
| 4 | chosen (restrictive): plaintext never leaves the host; the control service binds loopback, use an SSH or VPN tunnel | `--insecure-lan-bind` is the only way to bind elsewhere |
| 5 | chosen (restrictive): one panic button. The game write lock also locks the edit tool, and also sets the Hermes-side lock (unlocking needs the terminal) | `lockAlsoLocksEdit=true` in the module config; `lockSetsHermesLock: true` in the policy |
| 6 | as instructed: the arming gate is QA'd on an online-mode test server; screenshots use a dev override that works only on a loopback-bound server against a control service in dry-run mode (mock executors, nothing runs) | JVM property `agentcraft.write.devOverride=loopback-dry-run`; `--dry-run` (plus `--dev-offline-actors` for the offline dev player) |
| 7 | proposed: no tier 2 capability enabled; the shipped policy is empty | `hermes_control/policy.empty.json`; placeholders only in `policy.example.json` |
| 8 | chosen (restrictive): tier 2 needs the player within 8 blocks of an office anchor | `presenceRadius` in the module config (0 turns it off) |
| 9 | proposed: same host as the adapter, own user; chosen (restrictive): only the service's own user may write the policy, key and lock files | start refused when any of them is owned by someone else or group/other-writable |
| 10 | chosen (restrictive): hand-off decisions (demo ready, review, revise) stay read-only in the game | `decision.answer.handoffAnswerable: false` |
