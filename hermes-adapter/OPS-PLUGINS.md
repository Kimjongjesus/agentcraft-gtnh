# Writing an ops source plugin

The adapter's `ops.*` feed ([../docs/ops-protocol.md](../docs/ops-protocol.md)) is filled by
*ops sources*. The adapter itself knows no monitoring system: anything that can say "this host is
up", "that job failed" or "this quota is 40 % used" can be a source. Sources that know a real
installation (hostnames, addresses, file locations) belong in a **private** plugin outside this
repository; this repository only ships the interface and a generic mock
(`hermes_adapter/ops_mock.py`).

## Interface

A plugin is a Python module (or package) with a factory:

```python
def create_sources(config: dict) -> list:   # or a single source, or None
    return [MySource(config)]
```

A source is any object with these attributes (subclassing `hermes_adapter.ops.OpsSource` is
optional; plugins do not need to import the adapter at all):

| attribute | required | meaning |
| --- | --- | --- |
| `id` | yes | short stable id (`fleet`, `cron`); lowercased to `[a-z0-9._-]`, <= 24 chars; becomes the prefix of every entity id |
| `name` | no | display name for the source status line |
| `interval` | no | seconds between collects (default 60, clamped to 1 .. 86400) |
| `timeout` | no | longest a collect may take (default `min(interval, 30)`, clamped to 0.5 .. 300) |
| `collect()` | yes | returns the current state (below); called in a worker thread |
| `close()` | no | called once when the adapter stops |

`collect()` returns a dict with any of these keys, each a list of dicts that use the protocol
field names from the spec (ids are local to the source; the adapter adds the `<source>/` prefix):

```python
{
    "services": [{"id": "host-a", "name": "host-a", "group": "hosts", "state": "up",
                  "since": 1791000000, "detail": "load 0.2", "cpu": 20, "mem": 41.5, "disk": 55}],
    "jobs":     [{"id": "backup", "name": "backup", "schedule": "daily 03:00", "lastStatus": "ok",
                  "lastRun": "2026-10-06T03:00:00+00:00", "nextRun": 1791090000, "durationMs": 61000}],
    "usage":    [{"provider": "provider-a", "window": "week", "remainingPct": 58, "resetsAt": 1791600000}],
    "alerts":   [{"id": "disk-host-b", "ts": 1791000000, "severity": "warn", "source": "metrics",
                  "title": "disk 91% on host-b", "state": "open"}],
    "warning":  "cache file is 3 h old",   # optional: source state becomes "warn" with this detail
}
```

A missing key means "this source has none of those"; an empty list means the same. Return the
**complete current state** every time: the adapter diffs consecutive results and sends removals
for entities that disappeared.

What the adapter does with it, so a plugin does not have to:

* **Validation.** Only the protocol fields survive; anything else is dropped. Enums accept common
  synonyms (`healthy`/`online` -> `up`, `success` -> `ok`, `warning` -> `warn`, `closed` ->
  `resolved` ...); anything unrecognised becomes the safe default (`unknown` state, `warn`
  severity, `open` alert). Percentages are clamped to 0..100. Timestamps may be epoch seconds,
  epoch ms or ISO-8601 strings. Entries without their required fields (a service without a name,
  an alert without `ts`/`title`/`id`) are dropped and counted in `OpsSource.rejected`.
* **Privacy.** Every string, ids included, goes through the privacy filter: credentials, personal
  notes, paths, e-mail and (by default) IP addresses are redacted. Still, **never put a secret in
  the returned data on purpose**: the filter is a safety net, not a license.
* **Bounds.** Lengths and counts are enforced; when a cap is hit the important entries are kept
  (`down` services, `failed` jobs, open/critical alerts).
* **Failure handling.** An exception or a timeout marks the source `error` (its last good data
  stays visible); after `max(120 s, 3 x interval)` without a good collect it turns `stale` and its
  services show `unknown`. The exception text is filtered before it is logged or sent.
* **Scheduling.** Each source runs in a worker thread at its own interval, never concurrently
  with itself (a collect that overran its timeout is not started again until it returns).

## Rules for plugins

* **Read-only.** `collect()` must not write, restart, delete or send anything. Open databases in
  read-only mode (`sqlite3.connect("file:...?mode=ro", uri=True)`), read files, call read-only
  HTTP endpoints. No new listening sockets. One SQLite detail to know: for a WAL-mode database
  whose writer has closed (no `-shm` file on disk), even a `mode=ro` reader makes SQLite create
  the `-shm` index and an empty `-wal` next to the database (content untouched; the next writer
  removes them). So such a source cannot read it with that directory read-only; when the database
  is only part of the source's data, catch the `sqlite3.OperationalError` and return the rest plus
  a `warning` (see the next rules).
* **Credentials stay at runtime.** If a source needs a token, read it at runtime from the existing
  secret file or environment, never from `config` passed on the command line, never in code, and
  never include it in returned text or exceptions.
* **Be quick and bounded.** Read only recent history (e.g. the last 24 h of alerts), cap what you
  read from files, and set `timeout` below `interval`.
* **Fail loudly, not partially.** If the data cannot be read, raise (the source shows `error`
  with the reason). If part of it is old or missing, return what you have plus a `warning`.

## Loading

```
python3 -m hermes_adapter --ops-plugin ~/private/ops_plugin            # package directory with __init__.py
python3 -m hermes_adapter --ops-plugin ~/private/ops_plugin.py         # single file
python3 -m hermes_adapter --ops-plugin mypkg.ops                       # importable module (create_sources)
python3 -m hermes_adapter --ops-plugin mypkg.ops:make_sources          # explicit factory
python3 -m hermes_adapter --ops-plugin ep:homelab                      # entry point, group agentcraft_gtnh.ops_sources
python3 -m hermes_adapter --ops-plugin ~/private/ops_plugin --ops-config ~/private/ops.json
```

`--ops-plugin` can be repeated; at most 16 sources in total, and source ids must be unique.
`--ops-config` is a JSON object passed to every factory as `config` (paths, intervals, which
collectors to enable); it is never sent to clients. A module-level `SOURCES = [...]` list works
instead of a factory. A plugin that fails to load stops the adapter with the plugin spec and the
filtered error, rather than starting with a silently missing feed.

Check a plugin without starting the server:

```
python3 -m hermes_adapter --ops-plugin ~/private/ops_plugin --ops-once     # one ops.snapshot as JSON
```

## Testing a plugin

Feed `collect()` output through the same normalization the server uses:

```python
from hermes_adapter.ops import normalize_batch
batch = normalize_batch(MySource(config).collect(), "mysource")
assert batch.rejected == 0
```

and keep the plugin's own tests on synthetic data (no real hostnames or addresses in fixtures
that might be shared).
