# Control service (`hermes_control`, card 7)

The Hermes-side half of the AgentCraft write path. It receives HMAC-signed `action.*` frames from
the game server's write module (`gtnh-write/`), checks them against an owner-maintained policy and
runs a short allowlist of actions on Hermes. Python 3.11 standard library only; it reuses the read
adapter's WebSocket server and privacy filter. Wire contract (normative):
[../docs/action-protocol.md](../docs/action-protocol.md). Design and threat model:
[../docs/write-path.md](../docs/write-path.md). All names below are placeholders.

**It ships off.** `hermes_control/policy.empty.json` has no actors and every capability disabled;
`policy.example.json` has placeholders only, with `card.dispatch`, `service.restart`, `cron.run`,
`agent.chat` and `agent.ask` disabled. Nothing in this repository installs or starts the service.

## Run

    cd hermes-adapter
    python3 -m hermes_control check-policy --policy hermes_control/policy.example.json
    python3 -m hermes_control serve --policy P --key-file K --state-dir D \
        [--lock-file L] [--audit-file A] [--bind 127.0.0.1] [--port 7879] \
        [--dry-run] [--board-fixture F] [--dev-offline-actors] [--allow-peer NET] [--allow-host H] [--insecure-lan-bind]
    python3 -m hermes_control lock --reason "why"        # set the Hermes-side lock
    python3 -m hermes_control unlock                     # clear it (terminal only, asks; --yes without a tty)
    python3 -m hermes_control status [--policy P]        # lock state, policy summary
    kill -HUP <pid>                                      # re-read the policy

| option | default | notes |
| --- | --- | --- |
| `--policy` | required | JSON, see below. Re-read on `SIGHUP` |
| `--key-file` | required | one line of >= 64 hex characters (>= 32 bytes), `#` comments allowed. There is no flag for the key itself |
| `--state-dir` | `$AGENTCRAFT_CONTROL_STATE`, else `~/.local/state/agentcraft-gtnh/control` | holds `ledger.sqlite3` (0600) and, by default, `hermes.lock` |
| `--lock-file` | `<state-dir>/hermes.lock` | exists or unreadable = locked |
| `--audit-file` | `hermes-control-audit.jsonl` next to the policy | append-only JSON lines, 0600, rolls at 8 MiB keeping one `.1` |
| `--bind` / `--port` | `127.0.0.1` / `7879` | loopback only. Another address needs `--insecure-lan-bind` (plaintext on the LAN; use an SSH or VPN tunnel instead). Wildcards (`0.0.0.0`, `::`) are always refused |
| `--allow-peer`, `--allow-host` | loopback | extra accepted peers / `Host` headers, same rules as the read adapter. A request with an `Origin` header is always refused |
| `--dry-run` | off | every executor is replaced by a mock that records the call (and the argv it would have used) and executes nothing; `dryRun: true` goes into `action.policy` and every `action.result` |
| `--board-fixture` | none | a JSON file of cards and decisions used instead of the Hermes board (QA). Accepted **only** together with `--dry-run`. Example: `hermes_control/fixtures/board.example.json` |
| `--hermes-home` | the global Hermes home | where board reads happen (`kanban.db` opened `mode=ro` + `PRAGMA query_only`, like the read adapter) |
| `--dev-offline-actors` | off | **QA only.** Lets `policy.actors` name offline (version 3) UUIDs, which an offline-mode dev game server hands out. Refused unless `--dry-run` is also given **and** the bind is loopback (`127.0.0.0/8`, `::1`, `localhost`); the start-up line says `DEV-OFFLINE-ACTORS`, the `start` audit record carries `devOfflineActors: true`, and `action.policy` keeps `dryRun: true`. Without the flag a version 3 actor keeps the policy from loading (also on `SIGHUP`). Only version 3 with a valid variant is added; nothing else about actor checking changes. Never use it with a real board |

The service **refuses to start** (exit status 2, reason on stderr) when: the policy has an unknown
key or capability, `permissionApprove` is `true`, or a chat toolset has a denied name; the policy
file, key file or state / lock / audit directory is not owned by the running user or is group/other
writable; the key file is group/other readable (or is a symlink, too short, not hex); the state
directory, lock file or audit log would live inside the git checkout; the audit log or ledger
cannot be opened; `--board-fixture` is given without `--dry-run`; `--dev-offline-actors` is given
without `--dry-run` or on a non-loopback bind; the bind address is a wildcard or a
non-loopback address without `--insecure-lan-bind`. On `SIGHUP` an invalid policy is ignored and the
old one stays (the failure is audited).

QA with the signed test client (dry run, placeholder data):

    python3 -m hermes_control serve --policy P --key-file K --state-dir D --dry-run \
        --board-fixture hermes_control/fixtures/board.example.json
    python3 scripts/control_client.py --key-file K request card.dispatch \
        '{"card":"t-demo-1","board":"main","profile":"builder-a"}' --confirm
    python3 scripts/control_client.py --key-file K request decision.answer \
        '{"card":"t-demo-2","decision":"d-main-101","choice":"Option A"}'
    python3 scripts/control_client.py --key-file K lock --reason "panic"

## Policy

`policy.json`, owner-maintained, never editable over the wire. Every key is checked; an unknown key
anywhere stops the service. Capabilities are off unless `"enabled": true`.

```json
{
  "schema": 1,
  "actors": ["00000000-0000-4000-8000-000000000001"],
  "lockSetsHermesLock": true,
  "hermesProgram": ["hermes"],
  "capabilities": {
    "decision.answer": { "enabled": true, "boards": ["main"], "permissionApprove": false, "handoffAnswerable": false },
    "card.create":     { "enabled": true, "boards": ["main"] },
    "card.edit":       { "enabled": true, "boards": ["main"] },
    "card.dispatch":   { "enabled": false, "boards": ["main"], "profiles": ["builder-a"] },
    "agent.chat":      { "enabled": false, "agents": ["helper-a"], "toolsets": ["read-only"], "timeoutSeconds": 120 },
    "agent.ask":       { "enabled": false, "agents": ["helper-a"], "toolsets": ["read-only"], "timeoutSeconds": 120 },
    "service.restart": { "enabled": false },
    "cron.run":        { "enabled": false }
  },
  "services": { "service-1": { "argv": ["systemctl", "--user", "restart", "service-1.service"], "timeoutSeconds": 60 } },
  "jobs": ["job-a1"]
}
```

| key | meaning |
| --- | --- |
| `actors` | Minecraft account UUIDs (lowercase, canonical, **version 4**: a version 3 UUID is an offline-mode name hash and is refused). A request from anyone else is refused whatever the game says |
| `lockSetsHermesLock` | default `true` (open question 5): an `action.lock` from the game **creates** the Hermes-side lock file. Nothing from the game can clear it |
| `hermesProgram` | argv prefix for every Hermes command, default `["hermes"]` (e.g. an absolute path). The only program the board, cron and chat executors ever run |
| `decision.answer.permissionApprove` | must be `false` (open question 1). `true` makes the service refuse to start ("not supported"): permission halts can only be **denied** from the game |
| `decision.answer.handoffAnswerable` | default `false` (open question 10): demo-ready / review / REVISE style decisions are read-only from the game |
| `boards`, `profiles`, `agents` | allowlists of names (`[A-Za-z0-9][A-Za-z0-9._:-]*`, <= 64, never a leading `-`, because names end up in argv). A capability that is enabled with an empty allowlist is a start-up error |
| `agent.*.toolsets` | the toolset names handed to `hermes chat --toolsets`. A name that is, or contains, `terminal`, `shell`, `bash`, `exec`, `code`, `delegat`, `subagent`, `process`, `computer`, `browser`, `cron`, `kanban`, `yolo`, `sudo`, `ssh`, `mcp`, `patch`, `write`, `edit`, `docker`, `git`, `all`, ... stops the service from starting (denylist in `policy.py`) |
| `services.<name>.argv` | the **only** command `service.restart` runs for that name; run without a shell. `timeoutSeconds` 1..600 |
| `jobs` | names `cron.run` may pass to `hermes cron run` |
| `capabilities.<name>.limits` | may only **tighten** the defaults (`perMinute`, `perHour`, `perTargetCount`, `perTargetSeconds`); a looser value stops the service from starting |

Default limits (also enforced by the game side): `decision.answer` 20/h, `card.create` 20/h,
`card.edit` 60/h, `agent.chat` and `agent.ask` 10/min, `card.dispatch` 6/h and 1 per card per 10 min,
`service.restart` 6/h and 1 per service per 10 min, `cron.run` 12/h and 1 per job per 5 min.
(`card.dispatch` prompts are additionally capped at 12/h and 4 open confirms per actor.)

Reload with `SIGHUP`: the policy is re-read, the revision (`<hash>.<n>`) is bumped, every outstanding
confirm token is void, and connected clients get a fresh `action.policy` and `action.state`. A reload
also wins against work that was admitted but has not started its executor yet (next section).

## What each capability does on Hermes

All Hermes commands are fixed argv lists built in code, run without a shell. Game text is passed
through `--body-file -`, stdin, or `--option=value` forms; a bare positional that could be parsed as
an option (a title starting with `-`) is refused. Every text written to Hermes starts with
`[from game: <name>/<uuid8>]`, and `card.create` uses `--created-by=agentcraft-game --triage` plus an
idempotency key `agentcraft-game:<actor uuid>:<request id>`.

| capability | command |
| --- | --- |
| `decision.answer` | `hermes kanban --board B unblock --reason=<tagged answer> CARD` (the same "comment, then unblock" an owner answer makes) |
| `card.create` | `hermes kanban --board B create --triage --created-by=agentcraft-game --idempotency-key=K [--priority=N] --body-file - --json TITLE` (body on stdin) |
| `card.edit` | `... comment --author=agentcraft-game CARD <tagged text>`, or `... edit [--title=T] [--body=B] [--priority=N] CARD` (refused while the card is running) |
| `card.dispatch` | after the Confirm: `hermes kanban --board B assign CARD PROFILE`; the dispatcher starts it |
| `agent.chat`, `agent.ask` | `hermes --profile AGENT chat --oneshot --quiet --query-file - --toolsets <policy toolsets> --source tool --max-turns 8 --run-budget N` with the text on stdin. One-shot only: never `--yolo`, never `--resume`/`--continue`, never another session; the argv is checked again in code before it runs |
| `service.restart` | the policy's `services.<name>.argv` |
| `cron.run` | `hermes cron run JOB` |

A command that did not run or exited non-zero is `refused`; one that timed out is `unknown` (the work
may have happened), is never re-run, and the game says "check outside the game".

### Chat ships disabled (open question 3)

Enforcement of a read-only toolset inside Hermes cannot be proven from this repository. Game chat
therefore stays `enabled: false` until the owner has verified, on a test copy, that the toolset named
in the policy really has no shell, terminal, code execution, file writes or delegation. The code side
of the boundary is tested: the argv never contains `--yolo`, `--resume`, `--continue`,
`--accept-hooks`, `--worktree`, the program position is always the policy's, the toolset is exactly
the policy's, and a policy toolset with a denied name keeps the service from starting. The reply is
filtered as a whole with the adapter's privacy filter (secrets, personal notes, addresses), capped at
6000 characters, then sent as `action.chat` frames of complete paragraphs (<= 1800 characters each,
at most 8), so a secret cannot slip through split over two frames.

## Decisions: what the game may answer

Classified on the Hermes side from board data (the block event's reason and kind), never from the
request. Anything not recognised is read-only.

| kind | recognised by | the game may |
| --- | --- | --- |
| `question` | `QUESTION` / `NEEDS INPUT` prefix and no permission or hand-off signal | pick one **offered** choice (exact text), or send text when the decision offers no choices |
| `permission` | `PERMISSION` prefix, the word permission / approve / grant / allow in the first line, or an `Approve`-like offered choice (after Unicode normalisation, zero-width characters removed) | answer exactly `Deny` (it must be offered), optionally with a note. `Approve`, `approve`, ` Approve `, look-alikes and anything else are refused, whatever the policy says |
| `handoff` | `DEMO READY` / `REVISE` / `HELD` prefix, or send-to-review / hand-off wording | nothing, unless `handoffAnswerable: true` (then like a question) |
| `unknown` | everything else, a block kind other than `needs_input`, withheld text | nothing |

The service records the **offered** option text, not the game's spelling.

## Execution-time re-checks (the last gate before an executor)

Admission and confirm validate against the board and the policy, but an admitted request can wait in
the four-thread worker pool, and the board is not locked by the control ledger. So the worker thread
repeats the checks **immediately before the executor call**, after the lock check, and refuses
(`status: refused`) on any mismatch:

- **Board.** The board is read again. `card.dispatch`: the card still exists, its revision equals the
  one in the confirmed prompt (title, body, status, assignee, priority, run and block are all part of
  it), its assignee is unchanged, and it is still dispatchable and not running. `decision.answer`: the
  decision is still open and is the **same** decision (same id and event), is classified exactly as at
  admission (kind, question, offered options) and the requested choice resolves to the same recorded
  answer; a question that turned into a permission is refused, and Approve on a permission is never
  sent. `card.edit`: the card still exists and is not running (a comment is always allowed).
- **Policy.** Work is judged by the policy in force now: it is refused when the policy was reloaded
  since admission (any reload, even to identical content, bumps the revision: the player simply sends
  the request again), and also when the actor, the capability or the board / profile / agent / service /
  job it names is not allowed any more. The policy swap of a reload and this authorise-and-start step
  are serialised by one lock, so a reload either completes before the check (the work is refused) or
  after the work started (a started command cannot be revoked).
- **Lock.** Checked before and after the two steps above, including the in-memory latch (next section).

**Residual window.** The `hermes` CLI has no conditional mutation (no "assign only if revision is X",
no "unblock only decision N"), so the board is re-read and then the CLI is called; a change landing in
those few milliseconds (the gap between this read and the CLI process reading the board) is not
caught. If that is not acceptable, leave `card.dispatch` and `decision.answer` disabled.

## Lock

`hermes.lock` (JSON: reason, by, since): if the file exists, or exists and cannot be read, the
service is locked. It is checked at admission and again, in the worker thread, immediately before
every executor call; a lock also voids outstanding confirm tokens and is pushed to clients in
`action.state` within a second. An `action.lock` from the game sets the file (default policy) and
voids tokens; it is accepted from any validly signed frame, because locking is the fail-safe
direction. **The lock is latched in memory before the file is written**: if the write fails (disk
full, permissions), admission and the worker guard still refuse everything, the failure is audited
(`game-lock-persist-failed`), the write is retried on the one-second tick, and the latch stays until
the process restarts (or, once the file has been written, until the terminal `unlock` removes it).
Nothing on the wire clears the lock: the service does not even import the function that
does. `python3 -m hermes_control unlock` is the only way, and it asks. A lock stops new actions only;
a builder already dispatched, a job already handed to the scheduler and a restart already issued keep
going. `lockReason` and `lockedBy` in `action.state` go through the adapter's privacy filter as whole
strings (secrets, addresses, e-mail, personal notes) before the 200 / 64 character caps.

## Audit

`hermes-control-audit.jsonl`, one record per received authenticated frame and per executor outcome:
`id` (returned to the game as `audit`), `ts`, `event`, `req` (the request id, shared with the
game-side log), actor UUID, capability, decision, arguments (filtered, strings capped at 300 characters;
chat text capped at 300), `dryRun`. The admission record is written in full (short writes and `EINTR`
are looped over; no progress is an error), then fsynced, **before** any executor runs; if that fails
the request is refused ("no audit, no action") and a half-written record is cut back off the file
(or, if that is impossible, the next record starts on a new line). Unauthenticated frames
are logged at most once a second with a counter. Keys, signatures, session ids, challenges and confirm
tokens are never written (tokens are also stored only as SHA-256 hashes in the ledger).

## Receiver pipeline (what the tests attack)

1. message <= 16384 bytes (else the connection closes), 2. outer shape, 3. signature (constant time,
before the payload is parsed), 4. strict payload parse (duplicate keys, unknown keys, wrong types,
bool-as-int, control characters, lone surrogates), type equality, session, direction,
5. `ts` within 60 s and not before this process started, 6. nonce unseen in the ledger (kept, never
evicted early, until `ts + 60 s` **inclusive**: it is deleted only when strictly older, the same
millisecond the timestamp window still accepts; 4096 live nonces at most, then new frames are refused),
7. a wall clock that moves
backwards by more than 60 s freezes the service until restart. Then actor in policy, capability
known, tier equal to the table (no downgrade), capability enabled, typed arguments, allowlists, board
data (classification, card state), lock, idempotency claim plus rate limits (one ledger transaction),
audit, executor.

## Deviations and readings of the contract

None of these changes a byte of the contract; each is where it is silent, and the stricter reading
was taken.

- **Confirm / cancel results.** A valid confirm or cancel is answered with an `action.result` whose
  `re` is the **original request id** (`applied` / `refused` / `unknown` / `cancelled`); a confirm or
  cancel that names no live token is answered with `re` = the confirm / cancel frame's own id.
- **Prompt.** `card.dispatch` sends `action.prompt`, then `action.result` with status `prompted`
  (same `re`). A replay of a prompted request returns `prompted` without a new token; a replay of a
  request still running returns `queued`.
- **`ack`** carries only `result` (as in the message table), no `re`.
- **`action.lock`** gets no direct reply; its effect is visible in `action.state`.
- **Unauthenticated peers** get a signed generic `error`; eight bad frames on one connection, 120
  frames in 10 s, a binary frame, or a missing `hello` within 10 s close it.
- **`armed`** in `action.state` is false while locked or after a clock jump; `lockedBy` is `system`
  for the latter.
- **Policy `limits`** appear in `action.policy` as `{perMinute?, perHour?, perTargetCount?, perTargetSeconds?}`.
- **Actors** must be version 4 UUIDs in the policy (the contract only says UUID). The one exception is
  the QA-only `--dev-offline-actors` dry-run allowance above.
- **Handshake.** After a valid `hello` a connection is *handshaking*: `ack`, `action.policy` and
  `action.state` are sent as one uninterrupted sequence (serialised against the state / policy
  broadcasters, which skip it), and only then does it become ready for requests. A lock or reload
  that lands during the handshake is therefore delivered after, never in between.
- **Queued work.** A request that was admitted can be answered `refused` at execution time with
  "policy changed since the request was admitted" or one of the board mismatch messages (see
  "Execution-time re-checks"); the contract's `refused` status already covers it.
- **`action.state` text** (`lockReason`, `lockedBy`) is filtered by the privacy filter, so a secret
  or e-mail address in a lock reason arrives as a placeholder.
- **Executor failure** (non-zero exit, missing program) is `refused`; only a timeout is `unknown`.
- **Permission look-alikes** (an `Approve`-like offered choice on a decision that is not labelled
  `PERMISSION`) are classified as permission, so a halt dressed as a question cannot be approved.

## Threat notes

- The link is signed, not encrypted: bind loopback and tunnel (SSH / VPN) if the game server is on
  another host. A LAN bind is possible only with `--insecure-lan-bind`.
- Anyone holding the key can speak for the game server; the policy's `actors` list, the allowlists,
  the limits, the lock and the audit are what bound a modified client or a stolen key. Rotate by
  replacing both key files and restarting.
- Game text is data: it never reaches a program position, a shell, the policy or the lock, and it is
  tagged `[from game: ...]` wherever it lands in Hermes. It still reaches agents that read the card; the
  agents' own rules must treat approvals written in such text as no approval.
- The `hermes` CLI's own behaviour (what `unblock` does with a permission halt's answer, how
  `--toolsets` is enforced) is outside this repository; the QA step in `docs/write-path.md` section 7.2
  and a test copy of the board come first.

## Tests

    cd hermes-adapter && PYTHONPATH=. python3 -m unittest discover -s tests

`tests/test_control_*.py` use temp directories, a fake `hermes` script that records its argv and the
signed test client; the real `hermes` is never called. `tests/test_control_security_fixes.py` holds the
regression tests of the independent security review (execution-time re-checks, queued work vs reload,
lock latch, complete audit writes, lock-state privacy, nonce boundary, handshake ordering, and the
`--dev-offline-actors` gate).
