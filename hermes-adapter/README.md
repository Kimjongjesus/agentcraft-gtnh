# hermes-adapter

Read-only bridge that shows Hermes (ai-ops) agents, kanban cards and cron jobs to AgentCraft
clients: the GTNH mod in `../gtnh-mod/`, upstream's `foreman/scripts/fake-mod.ts`, or anything
else that speaks `../docs/protocol.md`. Python 3.11 standard library only (no pip installs).

    python3 -m hermes_adapter --once | less          # print one snapshot and exit
    python3 -m hermes_adapter                        # serve ws://127.0.0.1:7878 (loopback only)
    python3 -m hermes_adapter --bind 192.0.2.10 --allow-peer 192.0.2.20   # gaming-spare test server

Options: `--boards homelab,ai-ops`, `--source sqlite|cli`, `--poll 3`, `--cast cast.json`
(`{"claude-builder": {"name": "Opus", "color": "#2E78C6"}}`), `--allow-host`, `--port`.

It never writes to Hermes: every client intent (goals, messages, decision answers, task/agent
actions, repo.add) is refused with `ack {ok:false}`. Mapping, read-only guarantees and secret
stripping: [MAPPING.md](MAPPING.md).

## Ops feeds (fleet health, jobs, provider usage, alerts)

Clients that send `hello.features: ["ops"]` also get the `ops.*` extension
([../docs/ops-protocol.md](../docs/ops-protocol.md)). It is filled by ops source plugins
([OPS-PLUGINS.md](OPS-PLUGINS.md)); without one, opted-in clients get an empty `ops.snapshot`.

    python3 -m hermes_adapter --ops-mock                               # generic demo source (RFC 5737 data)
    python3 -m hermes_adapter --ops-plugin ~/private/ops_plugin        # your own sources
    python3 -m hermes_adapter --ops-plugin ~/private/ops_plugin --ops-once   # one ops.snapshot, then exit
    python3 scripts/ops_watch.py --port 7878                           # watch the live ops.* stream (test client)
    python3 scripts/ops_watch.py --port 7878 --plain                   # control client: must see no ops.* message

Privacy: every string sent to a client (base protocol and ops) loses credentials, personal-note
references and, by default, IPv4/IPv6 addresses (`[ip]`). `--allow-ip-text` keeps addresses.

## Tests

    PYTHONPATH=. python3 -m unittest discover -s tests      # mapping, redaction, server, access policy
    scripts/run-upstream-checks.sh                           # upstream zod schema + fake-mod.ts (needs `npm ci` in ../foreman)

## Deployment

`systemd/hermes-agentcraft-adapter.service` is a `systemd --user` unit. It is **not** installed
or enabled; that is Eli's decision.
