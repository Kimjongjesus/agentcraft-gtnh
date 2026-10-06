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
cards are skipped; `done` cards are kept for 3 days. Never read: memory files, `personal-*.md`,
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

### Not mapped (always empty)

`repos`, `memory` (Hermes memory may hold personal notes; it is never exported), `goals`/`goal`.
`foreman` = `{backend: "claude", auth: "ok", message: "Hermes ai-ops (read-only) ...", adapter:
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
- e-mail addresses -> `[email]`; control characters dropped; lengths capped.

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

    python3 -m hermes_adapter --bind 192.168.0.161 --allow-peer 192.168.0.222

which accepts only that one peer IP, with Host `192.168.0.161[:port]` or loopback names.
