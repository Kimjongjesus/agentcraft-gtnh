# `action.*` wire protocol (card 7, normative)

This is the exact wire contract between the write module (`gtnh-write/`, the game server side) and
the control service (`hermes-adapter/hermes_control/`). The design and its reasons are in
[write-path.md](write-path.md); where the two differ on a wire detail, this file wins. Both
implementations are tested against it. All names and addresses are placeholders.

## 1. Transport

- WebSocket (RFC 6455), text messages only, on the **control endpoint**, default
  `ws://127.0.0.1:7879/`. Never on the read adapter's port: the read adapter keeps refusing every
  `action.` message.
- The control service binds loopback by default. Any other bind address needs
  `--insecure-lan-bind` (default chosen for open question 4: plaintext never leaves the host; use an
  SSH or VPN tunnel and connect to loopback on both ends). Wildcard binds (`0.0.0.0`, `::`) are
  always refused.
- Upgrade requests with an `Origin` header are refused; `Host` must be loopback or in
  `--allow-host`; the peer must be loopback or in `--allow-peer`.
- At most **2** concurrent connections; a third is closed at once.
- A message longer than **16384 bytes** (UTF-8) closes the connection.

## 2. Frame

Every message in both directions is exactly this JSON object (no other keys):

```json
{ "v": 1, "type": "action.request", "payload": "<the message as a JSON string>", "sig": "<64 lowercase hex>" }
```

- `v` is the integer `1`. `type` is a string. `payload` is a string. `sig` is 64 lowercase hex
  characters.
- `sig` = HMAC-SHA256(key, UTF-8 bytes of `payload`), lowercase hex. The receiver checks the
  signature **first**, with a constant-time compare, before parsing `payload`.
- The key file holds one line of 64 or more hex characters (at least 32 key bytes, the hex decoded
  to bytes); blank lines and lines starting with `#` are ignored. A key file readable or writable by
  group or others is refused. There is no command-line flag or config value for the key itself.

### 2.1 Payload

`payload` parses to a JSON object. Duplicate keys, unknown keys, missing required keys and values
of the wrong JSON type are refused. Every payload has these **common fields**:

| field | type | rule |
| --- | --- | --- |
| `type` | string | equals the outer `type` |
| `session` | string | 32 lowercase hex; the session from `action.challenge` |
| `dir` | string | `g2c` (game to control) or `c2g` (control to game); must match the direction it travelled |
| `id` | string | 1..64 chars of `[A-Za-z0-9._:-]`; unique per sender and actor |
| `nonce` | string | 32 lowercase hex (128 random bits) |
| `ts` | integer | milliseconds since the epoch |

Strings are additionally capped per field (below); a longer string is refused, not cut.
Integers must be JSON integers (no fractions, no exponents, no booleans).

### 2.2 Receiver checks, in order

1. Message size <= 16384 bytes, else close.
2. Outer object shape (section 2), else `error` + audit.
3. Signature, else `error` + audit (unauthenticated refusals are audited at most once a second
   with a counter; they never take nonce space).
4. Payload shape, `type` equality, `session`, `dir`.
5. `ts` within **60 000 ms** of the receiver clock, and not before the moment the receiver process
   started (so a restart cannot reopen a replay window).
6. `nonce` not seen before. Accepted nonces are kept (in the ledger on the control side, in memory
   on the game side) until `ts + 60 s` has passed and are never evicted early; when the cache holds
   **4096** live nonces new frames are refused until entries expire.
7. If the receiver's clock moves backwards by more than 60 s, it refuses everything until it is
   restarted.

A frame that fails 2..7 is dropped, answered with an `error` (when a session exists) and audited.

## 3. Handshake

1. control -> game, first frame: `action.challenge` with `challenge` (32 hex). `session` is the new
   session id; `dir` is `c2g`.
2. game -> control: `hello` with `challenge` (the same value) and `features: ["action"]`.
3. control -> game: `ack` with `result: {"features": ["action"]}`, then `action.policy`, then
   `action.state`.

Anything else first, an unsigned or badly signed `hello`, a wrong `challenge`, a wrong `session`, a
`hello` without `"action"` in `features`, or a second `hello` closes the connection (audited). The
game side closes when the first frame is not a valid `action.challenge`, or when `ack`,
`action.policy` and `action.state` do not follow. Until `ack` the control service accepts nothing
but the `hello`.

## 4. Messages

Fields below are in addition to the common fields.

| type | dir | fields |
| --- | --- | --- |
| `action.challenge` | c2g | `challenge` |
| `hello` | g2c | `challenge`, `features` (array of strings, <= 8, each <= 32) |
| `ack` | c2g | `result` `{features: [...]}` |
| `action.policy` | c2g | `revision` (string <= 64), `dryRun` (bool), `actors` (array of UUID strings), `capabilities` (object: name -> `{tier, confirm, enabled, limits}`), `services`, `jobs`, `boards`, `profiles`, `agents` (arrays of strings <= 64 chars, <= 64 entries) |
| `action.state` | c2g | `armed` (bool), `locked` (bool), `lockReason` (string <= 200), `lockedBy` (string <= 64), `since` (integer ms, 0 when not locked) |
| `action.request` | g2c | `actor`, `capability` (string), `tier` (integer), `args` (object, per capability below) |
| `action.prompt` | c2g | `re` (the request id), `token` (32 hex), `expiresAt` (integer ms, prompt time + 60 s), `summary` (object: `card`, `title`, `board`, `profile`, `model`, `body`; strings, body <= 600) |
| `action.confirm` | g2c | `actor`, `token` |
| `action.cancel` | g2c | `actor`, `token` |
| `action.lock` | g2c | `actor`, `reason` (string <= 200) |
| `action.result` | c2g | `re` (request id), `status` (`applied`, `refused`, `queued`, `unknown`, `prompted`, `cancelled`), `error` (string <= 200, `""` when none), `result` (object, <= 16 keys, string values <= 600), `audit` (string <= 64, the Hermes-side audit record id), `dryRun` (bool) |
| `action.chat` | c2g | `re`, `conversation` (string <= 64), `agentId` (string <= 64), `text` (string <= 2000), `final` (bool) |
| `error` | c2g | `re` (string, `""` when unknown), `error` (string <= 200, generic) |

`actor` is `{ "uuid": "<lowercase canonical UUID>", "name": "<string <= 16>" }`. The game server fills
it from the authenticated connection that sent the packet, never from client data. The control
service refuses an actor that is not in the policy's `actors`.

### 4.1 Capabilities, tiers and arguments

| capability | tier | confirm | `args` (all keys listed; `?` = optional) |
| --- | --- | --- | --- |
| `decision.answer` | 1 | no | `card` (<= 64), `decision` (<= 16), `choice?` (<= 120), `text?` (<= 2000); at least one of `choice` / `text` |
| `card.create` | 1 | no | `board` (<= 64), `title` (1..120), `body?` (<= 4000), `priority?` (integer 0..100) |
| `card.edit` | 1 | no | `card` (<= 64), then either `comment` (1..2000) alone, or one or more of `title?` (1..120), `body?` (<= 4000), `priority?` (0..100) |
| `agent.chat` | 1 | no | `agent` (<= 64), `conversation` (<= 64), `text` (1..2000) |
| `agent.ask` | 1 | no | `agent` (<= 64), `text` (1..2000) |
| `card.dispatch` | 2 | **yes** | `card` (<= 64), `board` (<= 64), `profile` (<= 64) |
| `service.restart` | 2 | no | `service` (<= 64) |
| `cron.run` | 2 | no | `job` (<= 64) |

A request whose `tier` differs from this table is refused (no downgrade). A capability name not in
this table is refused. There is no capability for anything else and no way to add one from the
policy.

Default limits (both sides enforce them independently):

| capability | limits |
| --- | --- |
| `decision.answer` | 20 per hour |
| `card.create` | 20 per hour |
| `card.edit` | 60 per hour |
| `agent.chat` | 10 per minute |
| `agent.ask` | 10 per minute |
| `card.dispatch` | 6 per hour; 1 per card per 10 minutes |
| `service.restart` | 1 per service per 10 minutes; 6 per hour overall |
| `cron.run` | 1 per job per 5 minutes; 12 per hour overall |

The game side also keeps a per-player token bucket: 1 request per 2 s, burst 3.

### 4.2 Semantics

- **Idempotency.** `(actor uuid, id)` is claimed atomically with a SHA-256 digest of the canonical
  request (`capability`, `tier`, `args` as sorted-key JSON) before any work. Same id and digest:
  the stored result is returned (status as stored), nothing runs again. Same id, other digest:
  refused. Claims go `pending` -> `applied` / `refused` / `unknown`; a claim still `pending` at
  start-up becomes `unknown` and is never re-run. Claims are kept 24 hours.
- **Decision classification** happens on the control side from board data: `question` (the game
  may pick an offered choice, or send text when the decision offers no choices), `permission`
  (the game may only send `choice: "Deny"`, optionally with `text` as a note; `Approve` is refused
  whatever the policy says), `handoff` (review / demo-ready style decisions: read-only by default,
  open question 10), `unknown` (read-only).
- **Confirm tokens** (`card.dispatch` only): 128 random bits, single use, valid 60 s, bound to the
  actor, the request digest (card, board, profile), the card revision and the policy revision at
  prompt time. `action.confirm` re-checks everything checked at request time and consumes the token
  and claims the dispatch in one ledger transaction. Tokens are void after cancel, any lock, a
  policy reload and when the connection closes.
- **Locks.** The control service checks its lock at admission and again immediately before every
  executor call. With `lockSetsHermesLock: true` in the policy (default chosen for open question 5)
  an `action.lock` from the game also **sets** the Hermes-side lock; nothing from the game can clear
  it.
- **No audit, no action.** The admission record is written and flushed before any executor runs;
  if it cannot be written the request is refused.
- **Fail closed.** Nothing is queued; while the link is down every request fails at once in the game.
  An executor timeout or a claim left pending is `unknown`, and the game never retries by itself.
- **Dry run.** A control service started with `--dry-run` replaces every executor with a mock that
  records the call and executes nothing, and says `dryRun: true` in `action.policy` and in every
  `action.result`.
