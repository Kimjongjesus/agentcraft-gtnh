# Hermes -> AgentCraft mapping

The adapter (`python3 -m hermes_adapter`) plays the **Foreman** side of the AgentCraft protocol v1
(`../docs/protocol.md`) but its source of truth is Hermes on ai-ops, read strictly read-only.
Code: `hermes_adapter/mapping.py` (pure function, unit-tested in `tests/test_mapping.py`).

## Sources (all read-only)

| Hermes source | How it is read | Used for |
| --- | --- | --- |
| Kanban boards `~/.hermes/kanban/boards/<slug>/kanban.db` | SQLite `mode=ro` + `PRAGMA query_only` (default), or `hermes kanban --board <b> list --json` with `--source cli` | tasks, runs, events, comments, links |
| Profiles `~/.hermes/profiles/*` + `default` | directory names only | one Agent per profile |
| Sessions | **mtime only** of each profile's `state.db`/`sessions` (content never opened) | `active` (seen in the last 7 days) |
| Cron `~/.hermes/cron/jobs.json` | only `name, enabled, state, last_status, last_run_at, next_run_at, failure_streak, schedule` | the `cron` "Scheduler" agent + feed |

Boards default to every board except `*scratch*`; `--boards homelab,ai-ops` overrides. Archived
cards are skipped; `done` cards are kept for 3 days (the goal ring additionally counts every done
card on the board, a number only). Never read: memory files, `personal-*.md`,
`auth.json`, `.env`, cron prompts/delivery targets/errors, session transcripts, attachments.

Why SQLite by default instead of the CLI: it is the same database `hermes kanban` reads, it gives
events/comments the CLI `list` does not, a 3-second poll would otherwise spawn a Python process
every 3 s, and a `mode=ro` connection cannot write even by mistake (asserted in
`test_source_is_read_only`). The CLI path stays available as `--source cli`.

## Entities

### Agent (one per Hermes profile, plus `cron`)

| field | value |
| --- | --- |
| `id` | profile name, lowercased (`claude-builder`), `cron` for the scheduler |
| `name`, `role`, `title` | built-in cast (`default` = "Goon Goblin", lead; `claude-builder` = "Opus Builder"; ...), override with `--cast cast.json`; unknown profiles are title-cased workers |
| `color` | stable palette pick from a hash of the id; `accent` fixed |
| `skin` | the id (the GTNH mod falls back to the Steve skin) |
| `state` / `station` / `activity` | see table below |
| `taskId` | the card behind the current state |
| `paused` | always false (Hermes has no pause) |
| `active` | `default` always; others: any run or session activity in the last 7 days, else "off shift" |

State rules, first match wins:

| Hermes situation | `state` | `station` | `activity` (<= 48 chars) |
| --- | --- | --- | --- |
| live run (`task_runs.ended_at IS NULL`, status running) by this profile | `editing` (builders) / `reading` (profiles with "review" in the name); `thinking` if no heartbeat for 20 min | `desk` / `library` | latest `PROGRESS:`/`PLAN:` comment or heartbeat note by this profile in this run (same board), prefix stripped, first line only after the whole text passed `clean()`; else "working on <title>" |
| a card assigned to the profile is `blocked` with `needs_input` (or untyped) | `waiting_user` | `user` | "Question: / Permission: / Demo Ready: / Waiting: <title>" |
| last run ended crashed/timed_out/gave_up/spawn_failed < 30 min ago | `error` | `lounge` | "<outcome>: <title>" |
| last run completed / review_requested < 5 min ago | `done` | `lounge` | "finished <title>" |
| otherwise | `idle` | `lounge` | "idle - last: <title>" / "idle" / "off shift" |
| `cron` agent | `running` if a job's state is running, `error` if any enabled job is failing, else `idle` | `terminal` | "next <job> HH:MM" / "N failing: <job>" |

An agent that is waiting on Eli (row 2) is always `active: true`, even when the profile has had no
run for 7 days: waiting is not "off shift", and the in-world NPC must stand at the `user` station
with its "!" marker instead of being parked in the lounge.

### Stations (rule of last resort, `mapping.finalize_station`)

Applied to every agent after the table above, so `station` is always a protocol enum value
(`desk library terminal testbench mergestation meeting lounge user`); the GTNH mod walks the NPC to
the anchor of that station:

1. `active: false` (off shift) -> `lounge`.
2. a station the mapping did not set, or one outside the enum -> `desk` if the state is a working
   state (`thinking reading editing running testing`), else `lounge`.
3. otherwise the station from the table is kept.

Tests: `tests/test_card2_stations.py` (unit rule, serialized snapshot, incremental `agent.upsert`).

### Task (one per kanban card)

| field | value |
| --- | --- |
| `id`, `title`, `priority` | card id, cleaned title, priority |
| `status` | triage/todo/ready/scheduled -> `todo`, running -> `doing`, review -> `review`, done -> `done`, blocked -> `blocked`; a card that leaves the window is re-sent once as `cancelled` |
| `assignee` | assignee profile id (only if it is a known profile) |
| `deps` | parent card ids from `task_links` (only parents that are in the snapshot) |
| `description` | first 280 chars of the body, cleaned |
| `branch` | `branch_name` (workspace paths are never sent) |
| `blockedReason` | reason of the latest `blocked` event (cleaned) when blocked |
| `summary` | latest run summary or the card result (cleaned, 280 chars) |
| `ci` | always `unknown` |
| `createdBy` | profile id, else `user` |
| `createdAt` / `updatedAt` | ms; updated = newest of created/started/completed/event/comment |
| `board` | extra field (ignored by upstream receivers): board slug |

### Decision (read-only mirror of `needs_input` blocks)

One per `blocked` event with kind `needs_input`: `id = d-<board>-<event id>` (event ids are
per-board rowids, so the board is part of the id), `kind` = `permission` for
`PERMISSION ...` reasons else `question`, `question` = reason before `||`, `options` = the
`|| CHOICES: a | b` list, `status` = `open` while that block is the card's latest and the card is
still blocked, else `answered`. The whole reason is redacted and checked for personal-note
references BEFORE it is split into question and options; if it mentions personal notes the
question is withheld and no options are sent. The agent is the profile of the run that blocked
(joined on board + run id), else the card's assignee. Answering in-game is refused (see below):
Eli answers through the intake, as today.

### Multiple boards

Run, event and comment ids are SQLite rowids local to each board, and card ids are only unique
per board. Every row is tagged with its board and every join (event -> run -> profile, heartbeat
-> run, comment -> card, block -> card, dependency links) uses `(board, id)`. Card ids are sent
unchanged unless the same id exists on two boards; then both become `<board>:<card id>`.
`tests/test_review_r1.py::TwoBoardTest` builds two boards with identical rowids and checks that
decisions, agent activity, logs, feed and colliding card ids stay separate.

### LogEntry (`logs[]` / `agent.log`, <= 60 per agent)

`claimed` -> `tool`, `completed` / `review_requested` -> `result`, `blocked` and comments by the
profile -> `text`, crash/timeout/gave_up/spawn_failed -> `error`, heartbeat notes -> `text`.

These tails are what the GTNH mod's desk monitors display (`snapshot.logs`, then `agent.log`
appends). There is no second path: every entry is built from a whole comment / note / reason that
already went through `clean()`, so the monitor shows exactly the filtered text and nothing else.
`tests/test_card2_stations.py::MonitorLogTailTest` / `IncrementalStationTest` plant multi-line
canaries and flag-style secrets and check both the snapshot and the live `agent.log` messages.

### FeedItem (`feed[]` / `feed.add`, <= 200)

Card events ("Opus Builder picked up ...", "... is waiting: ...", "... finished ...", errors) and
cron runs in the last 3 days ("cron <name> ran: ok").

### Goal (one per board; card 3 atrium, `goals[]` / `goal.upsert`)

Hermes has no goal object; each kanban board is one goal: `id = board-<slug>`, `text = "Board
<slug>"`, extra fields `board`, `counts` (`todo doing review done blocked`), `total` and
`openDecisions` (a COUNT of open decisions on that board; the question text is never on a goal).
Open cards come from the task window; `done` counts every done card on the board (one
`SELECT COUNT(*)`, no titles), so the ring is the board's overall progress, not "done in the last 3
days". `progress = done / total`, cancelled/archived cards left out. `goal` in the snapshot is the
most recently active board's goal. Every Task carries `goalId`. `goal.upsert` is sent when any of these change.

Card 4 adds two count fields so every surface shows the same numbers: `doneRecent` (done cards
inside the task window, i.e. the wall's Done column) and `windowDays` (that window in days, e.g.
`3`; `0` when the source has no separate all-time count, so the list already holds every done
card). Both are plain numbers derived from the same rows as `counts`; no new text leaves the
adapter. The mod labels them "last 3 days" (wall, `doneRecent`) and "all time" (ring, `counts.done`).

### Library (`memory[]` / `memory.upsert`, <= 64 entries, bodies <= 1200 chars)

The upstream protocol calls it memory; here it is a read-only library built ONLY from text the
adapter already sends elsewhere, never from Hermes memory:

| entry | source (already sent as) | id |
| --- | --- | --- |
| plan / handoff / review verdict | newest `PLAN:` / `HANDOFF:` / `PASS` `REVISE` `VERIFICATION` comment per card and author, written by an agent profile (log tails) | `<agent>/<kind>-<card>` |
| done summary | result summary of the 12 newest done cards (`Task.summary`) | `shared/done-<card>` |
| board overview | per board: counts plus up to 8 card titles per open column (`Task.title`) | `shared/board-<slug>` |
| waiting on Eli | the question and choices of every open decision (`Decision.question`) | `<agent>/decision-<id>` |

Comments by anyone who is not an agent profile (Eli, the intake) are left out, and so are comments
without one of those prefixes. Every body is filtered as a whole source by `clean()` (flag-style
secrets, tokens, home paths, personal-note references -> whole text withheld) BEFORE it is cut to
1200 chars. An entry that drops out (decision answered, note aged out) is sent once as a tombstone
`memory.upsert {id, removed: true, body: ""}` and never appears in a snapshot.

Deliberately NOT sources (left out because they are not safe or not needed): Hermes memory files,
`personal-*.md`, cron prompts, profile configs and SOUL files, card bodies beyond the 280-char
`description`, comments by humans. `tests/test_card3_library.py` checks sources, bounds and
privacy on both the full snapshot and the incremental messages.

### Not mapped (always empty)

`repos`. `foreman` = `{backend: "claude", auth: "ok", message: "Hermes ai-ops (read-only) ...", adapter:
"hermes", readOnly: true}`; `backend` stays inside the upstream enum so the upstream schema and
Fabric mod accept it.

## Client intents

`hello` -> `snapshot` (+ `ack` if it had an id). `goal.submit`, `user.message`, `decision.answer`,
`task.action`, `agent.action`, `repo.add` -> `ack {ok:false, error:"read-only Hermes view: ..."}`
(or `error` without an id). `diff.request` -> an empty `diff` with `error`. Nothing is ever written
to Hermes.

## Secret stripping (`hermes_adapter/redact.py`)

Every outgoing string goes through `clean()`, always applied to the WHOLE source text (a full
comment, heartbeat note or block reason). Display fragments such as the one-line agent activity
are cut out afterwards with `clean_excerpt()`, so a personal-notes hint or the end of a key block
on a later line can never be separated from the line that is shown:

- text that references `personal-*.md` / `memory/personal` is replaced entirely by
  `[withheld: mentions personal notes]`;
- API keys (Anthropic/OpenAI/GitHub/Slack/AWS/Google/GitLab/HF), Discord bot tokens, JWTs,
  `Bearer`/`Basic` headers, `Authorization: <any scheme> <value>`, `password=`/`token:`/
  `api_key=`-style values, space-separated sensitive flags (`--password X`, `--api-key 'X'`,
  `--token "X"`, `--client-secret X`, `--with-token X`, ...), `sshpass -p X`, prose like
  "the password is X" when X looks like a credential, `user:pass@` in URLs, private-key blocks and
  long hex/base64 blobs -> `[redacted]` (the flag/key name is kept, the value is dropped);
- absolute and `~/` paths -> `.../<last component>`, credential-looking files (`.env`, `*.pem`,
  `id_rsa`, `auth.json`, `*secret*`, `*token*`, ...) -> `[path]`;
- credential-carrying URLs: long token user parts, credential query parameters (`?token=`,
  `&key=`, `&sig=`, `X-Amz-Signature=` ...), Discord/Slack webhook paths and opaque mixed-case
  path segments -> `[redacted]` (scheme and host kept);
- IPv4 and IPv6 addresses -> `[ip]` (ports and `/prefix` lengths kept; candidates are validated
  with `ipaddress`, so versions like `5.09.54.133`, times and MAC addresses survive). On by
  default; `--allow-ip-text` turns only this rule off;
- e-mail addresses -> `[email]`; control characters dropped; lengths capped.

The same filter runs on every string of the `ops.*` extension, ids included
([../docs/ops-protocol.md](../docs/ops-protocol.md) section 5); `tests/test_ops.py` and
`tests/test_ops_server.py` plant the same kind of canaries in every ops field and in plugin
error messages and assert on the normalized model and on the live WebSocket messages.

`tests/test_mapping.py::test_no_secrets_or_personal_notes_anywhere` plants tokens, credentials,
a personal-notes reference, a cron prompt and a delivery target in a fixture board and asserts none
of them appear anywhere in the serialized snapshot. `tests/test_review_r1.py` adds multi-line
canaries (comment, heartbeat, decision split both ways) and flag-style secrets in every task field,
again asserted on the serialized snapshot, and
`tests/test_server.py::test_incremental_upserts_never_carry_canaries` asserts the same on the live
`agent.upsert` / `task.upsert` / `agent.log` messages.

## Network exposure

Default bind `127.0.0.1:7878`, loopback peers and loopback Host headers only. Any `Origin` header
(including `null`) is refused with 401, as upstream does. A wildcard bind is refused. For the
gaming-spare test server:

    python3 -m hermes_adapter --bind 192.0.2.10 --allow-peer 192.0.2.20

which accepts only that one peer IP, with Host `192.0.2.10[:port]` or loopback names.
