# hermes-adapter

Read-only bridge that shows Hermes agents, kanban cards and cron jobs to AgentCraft
clients: the GTNH mod in `../gtnh-mod/`, upstream's `foreman/scripts/fake-mod.ts`, or anything
else that speaks `../docs/protocol.md`. Python 3.11 standard library only (no pip installs).

    python3 -m hermes_adapter --once | less          # print one snapshot and exit
    python3 -m hermes_adapter                        # serve ws://127.0.0.1:7878 (loopback only)
    python3 -m hermes_adapter --bind 192.0.2.10 --allow-peer 192.0.2.20   # a test game server on the LAN

Options: `--boards main,ops`, `--source sqlite|cli`, `--poll 3`, `--cast cast.json`
(`{"builder-a": {"name": "Ada", "color": "#2E78C6"}}`), `--allow-host`, `--port`.

Agent names: profiles show as their title-cased name (`builder-a` -> "Builder A"); only Hermes'
built-ins `default` ("Lead") and `cron` ("Scheduler") have fixed names. Your own names, roles,
titles and colours belong in a private cast file: `--cast FILE`, or `agentcraft-cast.json` in the
Hermes home, which is picked up automatically when `--cast` is not given. Keep it out of any repo.

It never writes to Hermes: every client intent (goals, messages, decision answers, task/agent
actions, repo.add) is refused with `ack {ok:false}`. Mapping, read-only guarantees and secret
stripping: [MAPPING.md](MAPPING.md).

## HTTP dashboard

The optional dashboard is a separate, read-only HTTP listener for the adapter's mapped Hermes and ops snapshots. It is disabled unless `--dashboard` is given. Its default address is `127.0.0.1:8787`; it is loopback-only by default.

    python3 -m hermes_adapter --dashboard
    python3 -m hermes_adapter --dashboard --dashboard-bind 192.0.2.10 --dashboard-allow-lan

For LAN access, choose the specific interface IP (not a wildcard) and explicitly add `--dashboard-allow-lan`. The dashboard listener is independent of the WebSocket `--bind`, `--allow-host`, and `--allow-peer` options. It has no login or TLS by design and is intended only for a trusted network, never direct Internet exposure. It serves GET requests only; HEAD and other methods are refused. Routes are fixed, the `Host` header must exactly match the listener authority, and there are no write, arbitrary-file, or world-telemetry routes.

The dashboard is display-only, not a complete privacy anonymizer: task, goal, and other titles remain visible. The adapter's text redaction applies here too, including the default IPv4/IPv6 redaction; `--allow-ip-text` disables that IP-text redaction across clients and therefore also affects the dashboard. Do not expose sensitive board data to an untrusted network.

`GET /api/snapshot` returns a JSON envelope with `snapshot`, `ops`, and `meta` (including generated/updated timestamps, poll count, stale status, and `readOnly: true`). It contains only the adapter's existing mapped protocol fields. Strings are filtered before output; pathological values, excessive depth/size, and oversized responses fail closed. The API and static assets send `Cache-Control: no-store`. The bounded server limits concurrent clients, request/header sizes, request/output deadlines, and response size; browser scripts fetch same-origin with no-store.

Task display is bounded: the adapter supplies at most 200 task cards and the UI shows up to eight newest cards per column. Recent done cards use the supplied three-day task window, while all-time completion is shown separately; these totals are intentionally different. Cancelled cards are hidden from the wall and excluded from board totals. Display caps and source freshness are labelled rather than implying complete coverage.

For reproducible synthetic data, use a fresh scratch directory and explicitly point the adapter at its fixture home (the normal default is the real Hermes home):

    demo_dir=$(mktemp -d)
    python3 scripts/demo_hq.py "$demo_dir" init
    python3 -m hermes_adapter --hermes-home "$demo_dir/hermes-home" --ops-mock --dashboard
    # In another shell, add older cards to demonstrate the distinct totals:
    python3 scripts/demo_hq.py "$demo_dir" history

The demo uses generic fixture content; use only such synthetic data for screenshots. `--ops-mock` alone does not replace the default live Hermes home—pass the fixture explicitly with `--hermes-home`. Dashboard fonts are served from the mod's existing font resources, which must remain available alongside the adapter.

## Ops feeds (fleet health, jobs, provider usage, alerts)

Clients that send `hello.features: ["ops"]` also get the `ops.*` extension
([../docs/ops-protocol.md](../docs/ops-protocol.md)). It is filled by ops source plugins
([OPS-PLUGINS.md](OPS-PLUGINS.md)); without one, opted-in clients get an empty `ops.snapshot`.

    python3 -m hermes_adapter --ops-mock                               # generic demo source (RFC 5737 data)
    python3 -m hermes_adapter --ops-plugin ~/private/ops_plugin        # your own sources
    python3 -m hermes_adapter --ops-plugin ~/private/ops_plugin --ops-once   # one ops.snapshot, then exit
    python3 scripts/ops_watch.py --port 7878                           # watch the live ops.* stream (test client)
    python3 scripts/ops_watch.py --port 7878 --plain                   # control client: must see no ops.* message

Privacy: every string sent to a client (base protocol, ops and world) loses credentials, personal-note
references and, by default, IPv4/IPv6 addresses (`[ip]`). `--allow-ip-text` keeps addresses.

## Factory telemetry (`world.*`) and the world journal

Optional. With a telemetry source configured the adapter also polls the read-only GTNH factory
mod (`../gtnh-factory/`), streams `world.*` to clients that ask for it in `hello.features`
([`../docs/world-protocol.md`](../docs/world-protocol.md)) and keeps a local **world journal**
that agents can query.

    python3 -m hermes_adapter --factory-url http://127.0.0.1:25580 \
        --factory-token-file ~/.config/agentcraft/aifactory-token.txt     # chmod 600
    python3 -m hermes_adapter --factory-token-file ... --world-once       # one read-only probe, prints world.snapshot

| option | default | notes |
| --- | --- | --- |
| `--factory-url` | `$AGENTCRAFT_FACTORY_URL`, else `http://127.0.0.1:25580` | loopback only unless `--factory-allow-remote` (plain HTTP: the token would cross the network; prefer an SSH tunnel) |
| `--factory-token-file` | `$AGENTCRAFT_FACTORY_TOKEN_FILE` (or the token in `$AGENTCRAFT_FACTORY_TOKEN`) | first non-comment line; refused if group/other can read it. There is no flag for the token itself: argv is visible to every user |
| `--factory-interval` | 30 s (min 10) | the mod captures every 30 s or slower; the capture is fetched only when `/health` shows a new one |
| `--journal` / `--no-journal` | `$AGENTCRAFT_WORLD_JOURNAL`, else `~/.local/state/agentcraft-gtnh/world-journal.sqlite3` | refused inside this git checkout; created `0600` |
| `--journal-days` | 30 | events and samples; snapshots are kept 7 days, at most 1000, and the file is capped at 256 MiB |
| `--world-plugin` | | a custom world source instead of the factory: the same plugin forms and loader as `--ops-plugin` ([OPS-PLUGINS.md](OPS-PLUGINS.md); entry-point group `agentcraft_gtnh.world_sources`), but `collect()` returns world telemetry, not ops entities |

What the factory source does and does not do: it sends `GET /health` and `GET /telemetry/capture`
with a bearer token, nothing else. It follows no redirects, uses no proxy, caps responses at
4 MiB + 64 KiB while reading (also without a `Content-Length`), gives every request a total
deadline (a server that drips bytes cannot hold the poll), and reports failures as short fixed
reasons (`unauthorized`, `unreachable`, `timeout`, `busy`, ...), never response text. After
`unauthorized` it retries only every 5 minutes. Every string from the game passes the same
whole-text privacy filter as Hermes text before it is cut; player names never leave the journal;
logs carry states and counts only. The journal refuses to open an existing file that is not a
world journal (it checks read-only first, so a database passed by mistake is never written).

### Journal queries (for agents and people)

    python3 -m hermes_adapter.journal query --since 2h                    # what changed
    python3 -m hermes_adapter.journal query --since 7d --kind machine.stopped --kind machine.problem --json
    python3 -m hermes_adapter.journal query --since 1d --severity warn --public   # safe to paste: no coordinates
    python3 -m hermes_adapter.journal snapshot [--at 2026-10-06T07:00:00Z] [--json] [--public]
    python3 -m hermes_adapter.journal trend --since 24h --points 48       # power and activity over time
    python3 -m hermes_adapter.journal stats

Times: `30m`, `2h`, `7d`, `now`, ISO-8601 or epoch. Query commands open the file read-only
(`mode=ro`, `query_only`), so they are safe to run while the adapter writes. A kind ending in `.`
is a prefix (`machine.`). The same queries are methods of `hermes_adapter.journal.WorldJournal`
(`query_events`, `latest_snapshot`, `trend`, `stats`) for an agent that imports it.

What is in it: every `world.event` (machine appeared / gone / started / stopped / problem /
recovered / maintenance / unformed / formed, power low / recovered, AE2 offline / online, crafting
CPUs full / free, coverage degraded / recovered, game server restarts, telemetry lost / back), one
row of numbers per capture (machine counts by state, AE and EU power, CPUs) and a full snapshot
every 10 minutes and at every game server start. It may contain player names and coordinates:
it is a private file on the adapter host and never belongs in a repository or a public channel.
Size: a compressed snapshot of a 300-machine base is about 20 KiB (about 3 MiB a day of snapshots),
plus one small row per capture and per event; the 256 MiB cap is a backstop, not the usual size.

## Write path (control service, card 7)

The adapter above never writes. The write path is a separate package, `hermes_control/`, with its
own process, port (`ws://127.0.0.1:7879`), key, policy, ledger, audit log and lock. It ships
**disabled** (`hermes_control/policy.empty.json`: no actors, every capability off) and nothing in this
repository starts it. Run, policy schema, lock, dry run and threat notes: [CONTROL.md](CONTROL.md);
wire contract: [../docs/action-protocol.md](../docs/action-protocol.md).

    python3 -m hermes_control check-policy --policy hermes_control/policy.example.json
    python3 scripts/control_client.py --help          # signed test client for QA

## Tests

    PYTHONPATH=. python3 -m unittest discover -s tests      # mapping, redaction, server, access policy
    node --test tests/test_dashboard_js.js                 # optional frontend regressions; Node 18+, no npm packages
    scripts/run-upstream-checks.sh                           # upstream zod schema + fake-mod.ts (needs `npm ci` in ../foreman)

## Deployment

`systemd/hermes-agentcraft-adapter.service` is a `systemd --user` unit. It is **not** installed
or enabled; that is the owner's decision.
