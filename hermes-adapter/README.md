# hermes-adapter

Read-only bridge that shows Hermes (ai-ops) agents, kanban cards and cron jobs to AgentCraft
clients: the GTNH mod in `../gtnh-mod/`, upstream's `foreman/scripts/fake-mod.ts`, or anything
else that speaks `../docs/protocol.md`. Python 3.11 standard library only (no pip installs).

    python3 -m hermes_adapter --once | less          # print one snapshot and exit
    python3 -m hermes_adapter                        # serve ws://127.0.0.1:7878 (loopback only)
    python3 -m hermes_adapter --bind 192.168.0.161 --allow-peer 192.168.0.222   # gaming-spare test server

Options: `--boards homelab,ai-ops`, `--source sqlite|cli`, `--poll 3`, `--cast cast.json`
(`{"claude-builder": {"name": "Opus", "color": "#2E78C6"}}`), `--allow-host`, `--port`.

It never writes to Hermes: every client intent (goals, messages, decision answers, task/agent
actions, repo.add) is refused with `ack {ok:false}`. Mapping, read-only guarantees and secret
stripping: [MAPPING.md](MAPPING.md).

## Tests

    PYTHONPATH=. python3 -m unittest discover -s tests      # mapping, redaction, server, access policy
    scripts/run-upstream-checks.sh                           # upstream zod schema + fake-mod.ts (needs `npm ci` in ../foreman)

## Deployment

`systemd/hermes-agentcraft-adapter.service` is a `systemd --user` unit. It is **not** installed
or enabled; that is Eli's decision.
