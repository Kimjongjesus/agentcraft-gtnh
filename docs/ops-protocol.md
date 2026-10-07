# AgentCraft protocol extension: `ops.*` (AI-ops feeds)

Status: extension to [protocol v1](protocol.md), implemented by `hermes-adapter` (card 5a). The
in-game panels that show it are card 5b; this document is the contract between the two.

`ops.*` carries the state of the machines and automation around the agents: **fleet health**
(hosts and services), **scheduled jobs**, **provider usage / rate-limit windows** and **alerts**.
It is strictly one-way and read-only: the adapter sends, the client displays. Nothing a client
sends can change an ops source.

Everything here is generic. The adapter knows no monitoring system; *ops sources* (plugins, see
[`hermes-adapter/OPS-PLUGINS.md`](../hermes-adapter/OPS-PLUGINS.md)) return plain dicts and the
adapter validates, bounds and privacy-filters them before any client sees a byte. All examples use
made-up names (`host-a`, `service-1`, `provider-a`) and RFC 5737 documentation addresses.

## 1. Opting in

The base protocol is unchanged. A client that wants ops data says so in `hello`:

```json
{ "v": 1, "type": "hello", "id": "h1", "modVersion": "0.3.0", "protocol": 1, "client": "gtnh-mod", "features": ["ops"] }
```

| field | type | notes |
| --- | --- | --- |
| `features` | string[] | protocol extensions the client understands. Unknown names are ignored; at most 16 are read. |

The adapter answers, in this order on the wire:

1. `snapshot` (exactly as in protocol v1),
2. `ops.snapshot` (only if `"ops"` was requested),
3. `ack {re, ok: true, result: {features: ["ops"]}}` if the hello had an `id`. The `result` field is
   present only when the hello carried `features`, so the ack of a plain upstream hello stays
   byte-identical to protocol v1.

After that the connection receives `ops.*` incremental messages until it disconnects or sends a
new `hello` without `"ops"` (every hello re-decides the opt-in). Connections that never asked
receive **no** `ops.*` message at all, so upstream clients and schema validators that do not know
these types keep working.

An adapter that understands `ops` but has no source configured still sends an `ops.snapshot`
with empty lists and `sources: []`. A client can therefore tell three cases apart:
no `ops.snapshot` at all (older adapter, or one without the feature), an empty one (feature on,
nothing configured) and a populated one.

## 2. Messages (adapter -> client)

All messages use the protocol v1 envelope (`{"v": 1, "type": ...}`). Timestamps are integer epoch
milliseconds. Optional fields are omitted, never `null`. **Receivers must ignore unknown fields
and unknown `ops.*` message types** (new kinds can be added without a version bump).

### `ops.snapshot`

Full ops state. Sent after `snapshot` to every client that opted in; replaces everything the
client knew about ops.

| field | type | required | notes |
| --- | --- | --- | --- |
| `services` | [OpsService](#opsservice)[] | yes | ordered by `group`, then `name` |
| `jobs` | [OpsJob](#opsjob)[] | yes | ordered by `name` |
| `usage` | [OpsUsage](#opsusage)[] | yes | ordered by `provider`, then `window` |
| `alerts` | [OpsAlert](#opsalert)[] | yes | newest first |
| `sources` | [OpsSource](#opssource)[] | yes | in configuration order |
| `limits` | object | yes | the count caps in force: `{services, jobs, usage, alerts, sources}` |
| `ts` | integer | yes | when the adapter built this snapshot |

### `ops.service.upsert`, `ops.job.upsert`, `ops.usage.upsert`, `ops.alert.upsert`, `ops.source.upsert`

One entity, new or changed. **Replaces the whole entity with that id** (fields missing from the
new version are gone, e.g. `detail` after a recovery).

```json
{ "v": 1, "type": "ops.service.upsert", "service": { "id": "mock/host-c", "sourceId": "mock", "name": "host-c", "group": "hosts", "state": "down", "since": 1791262000000, "detail": "no answer from [ip] for 2 checks" } }
```

The payload key is the kind: `service`, `job`, `usage`, `alert`, `source`.

### `ops.remove`

```json
{ "v": 1, "type": "ops.remove", "kind": "service", "id": "mock/host-c" }
```

The entity is gone (the source stopped reporting it, or it fell out of a cap). `kind` is one of
`service`, `job`, `usage`, `alert`, `source`. Removing an unknown id is a no-op.

### Order and rate

* Within one update the adapter sends source statuses first, then removals before upserts per
  kind, so a client learns that a source went stale before it sees the entities change.
* Unchanged entities are never re-sent. The adapter checks its sources every second (`--ops-tick`)
  but a source only runs at its own interval (default 60 s), and `OpsSource.lastOk` moves at most
  once a minute, so an idle feed is silent.
* After a reconnect the client gets a fresh `ops.snapshot`; it never needs to replay anything.

## 3. Entities

Ids are `<sourceId>/<localId>`: lowercase `[a-z0-9._-]`, at most 89 characters, unique per kind.
Two sources can never overwrite each other's entities. Treat ids as opaque.

### OpsService

A host, a service or anything else with a health state.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | |
| `sourceId` | string | yes | the [OpsSource](#opssource) that reported it |
| `name` | string | yes | <= 40 chars |
| `group` | string | yes | <= 24 chars, free text used for grouping (`hosts`, `services`, `desktops` ...); `other` if the source gave none |
| `state` | [ServiceState](#enums) | yes | |
| `since` | integer | no | when the current state began (if the source knows) |
| `detail` | string | no | <= 80 chars, one line: why it is in this state |
| `cpu`, `mem`, `disk` | number | no | percent used, 0..100, one decimal |

When a source goes **stale** (see [OpsSource](#opssource)) its services are re-sent with
`state: "unknown"`, `detail: "no fresh data from <source name>"` and `since` = the time of the
last good collect: a dashboard must never show "up" for something nobody has checked lately.

### OpsJob

A scheduled job (cron entry, timer, CI schedule ...).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id`, `sourceId` | string | yes | |
| `name` | string | yes | <= 40 chars |
| `lastStatus` | [JobStatus](#enums) | yes | `unknown` if it never ran |
| `enabled` | boolean | yes | `false` = paused/disabled |
| `schedule` | string | no | <= 48 chars, human text (`every 5m`, `daily 06:30`, `30 6 * * *`) |
| `lastRun` | integer | no | start of the last run |
| `nextRun` | integer | no | next planned run |
| `durationMs` | integer | no | duration of the last run, capped at 30 days |
| `detail` | string | no | <= 80 chars, e.g. the last error |

### OpsUsage

One provider quota window (a rate limit, a weekly allowance, prepaid credit).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id`, `sourceId` | string | yes | |
| `provider` | string | yes | <= 32 chars |
| `window` | string | yes | <= 32 chars, label of the window (`session`, `week`, `credit` ...) |
| `remainingPct` | number | no | 0..100, one decimal; absent = unknown |
| `resetsAt` | integer | no | when the window resets |
| `detail` | string | no | <= 80 chars (`$4.63 of $10.00`) |

### OpsAlert

| field | type | required | notes |
| --- | --- | --- | --- |
| `id`, `sourceId` | string | yes | |
| `ts` | integer | yes | when it was raised |
| `severity` | [Severity](#enums) | yes | |
| `source` | string | yes | <= 24 chars, what raised it (`probe`, `metrics`, `backup` ...); defaults to `sourceId` |
| `title` | string | yes | <= 100 chars |
| `state` | [AlertState](#enums) | yes | |
| `detail` | string | no | <= 200 chars |
| `resolvedAt` | integer | no | only when `state` is `resolved` |

### OpsSource

Status of one configured ops source, so a panel can say *why* something is missing or old.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | <= 24 chars, the id prefix of its entities |
| `name` | string | yes | <= 40 chars |
| `state` | [SourceState](#enums) | yes | |
| `interval` | integer | yes | seconds between collects |
| `kinds` | string[] | yes | kinds the last good collect reported (`service`, `job`, `usage`, `alert`) |
| `counts` | object | yes | `{services, jobs, usage, alerts}` this source reported, **before** the global caps |
| `lastOk` | integer | no | last good collect, minute resolution |
| `detail` | string | no | <= 120 chars: the source's warning or the (filtered) error |
| `rejected` | integer | no | malformed entries dropped from the last collect |

### Enums

* **ServiceState**: `up`, `degraded`, `down`, `unknown`
* **JobStatus**: `ok`, `failed`, `running`, `unknown`
* **Severity**: `info`, `warn`, `critical`
* **AlertState**: `open`, `resolved`
* **SourceState**: `starting` (no collect finished yet), `ok`, `warn` (data plus a warning, see
  `detail`), `error` (the last collect failed; the previous good data is still shown),
  `stale` (no good collect for `max(120 s, 3 x interval)`)

## 4. Bounds

Clients may rely on these; the adapter enforces them after merging all sources.

| kind | max count | which survive a cap |
| --- | --- | --- |
| services | 256 | `down`, then `degraded`, `unknown`, `up` |
| jobs | 128 | `failed`, then `running`, `unknown`, `ok`; enabled before disabled |
| usage | 32 | lowest `remainingPct` first |
| alerts | 100 | open before resolved, then by severity, then newest |
| sources | 16 | (configuration error beyond that) |

String limits are in the entity tables; longer text is cut with a trailing `…` **after** the
privacy filter has seen the whole original string. A full `ops.snapshot` with every cap reached
and every string at its limit measures about 185 KB of JSON (the mod's WebSocket client accepts
16 MB); typical snapshots are a few KB.

## 5. Privacy

Every string in every `ops.*` message, ids included, passes through the adapter's privacy filter
(`hermes_adapter/redact.py`) on the **whole** source string before it is cut to length:

* text that references personal notes is replaced entirely by `[withheld: mentions personal notes]`;
* credential shapes become `[redacted]`: API keys, bearer/basic tokens, JWTs, private keys,
  `password=...`, `--password X` style flags, `user:pass@` in URLs, credential query parameters
  (`?token=`, `&sig=` ...), webhook paths, long opaque blobs;
* absolute paths are shortened to their last component, credential-looking files become `[path]`;
* **IPv4 and IPv6 addresses become `[ip]`** (ports and `/prefix` lengths are kept), on by default.
  The adapter flag `--allow-ip-text` turns only this rule off, e.g. for a private LAN dashboard;
* e-mail addresses become `[email]`.

Only whitelisted fields of each kind survive; anything else a source returns (raw payloads,
topic names, config) is dropped, never forwarded. A source error is reported as its exception
type plus the filtered message. Clients should simply display these placeholders.

## 6. Read-only

There is no `ops.*` message from client to adapter. Anything a client sends with an `ops.` type
is answered with `error` ("unknown message type"), like any other unknown type. Restarting
services or re-running jobs from the game is a later card with its own allowlist, confirmation
and audit log; it will not reuse these read-only messages.

## 7. Client sketch (GTNH mod, Forge 1.7.10)

Not compiled and not part of card 5a: this is how card 5b is expected to consume the feed with
the structure the mod already has (bridge thread -> server tick -> bounded blob to clients).
`hermes-adapter/hermes_adapter/ops_mirror.py` is a tested reference receiver for the same rules
in Python (`tests/test_ops_convergence.py` proves snapshot + diffs rebuild the adapter's exact
model under random churn, source failures, staleness and caps).

**1. Opt in** (`bridge/ForemanBridge.java`, where the hello is built):

```java
JsonArray features = new JsonArray();
features.add(new JsonPrimitive("ops"));
hello.add("features", features);
```

**2. Keep the state on the server thread** (new `server/OpsSync.java`, called from the
`default:` branch of `AgentWorldSync.apply`, next to `board.apply(type, m)`):

```java
public final class OpsSync {
    // one map per kind, keyed by id; LinkedHashMap keeps the adapter's order from the snapshot
    private final Map<String, Map<String, JsonObject>> byKind = new HashMap<>();
    private final Map<String, Integer> caps = new HashMap<>(); // from ops.snapshot "limits"
    private boolean dirty, seen;   // seen = an ops.snapshot arrived on this connection

    boolean apply(String type, JsonObject m) {
        if (!type.startsWith("ops.")) return false;
        if (type.equals("ops.snapshot")) {
            byKind.clear();
            for (String kind : KINDS) {               // service, job, usage, alert, source
                Map<String, JsonObject> map = new LinkedHashMap<>();
                for (JsonElement el : array(m, plural(kind))) {
                    if (el.isJsonObject()) put(map, kind, el.getAsJsonObject());
                }
                byKind.put(kind, map);
            }
            readLimits(m);                            // keep our own caps if absent
            seen = dirty = true;
        } else if (type.equals("ops.remove")) {
            Map<String, JsonObject> map = byKind.get(str(m, "kind"));
            if (map != null && map.remove(str(m, "id")) != null) dirty = true;
        } else if (type.endsWith(".upsert")) {        // ops.<kind>.upsert {<kind>: {...}}
            String kind = type.substring(4, type.length() - 7);
            if (KINDS.contains(kind) && m.has(kind) && m.get(kind).isJsonObject()) {
                put(byKind.computeIfAbsent(kind, k -> new LinkedHashMap<>()), kind, m.getAsJsonObject(kind));
                dirty = true;
            }
        }
        return true;                                  // unknown ops.* types: ignored, still "ours"
    }

    void disconnected() { seen = false; dirty = true; } // panels show "ops feed offline"

    private void put(Map<String, JsonObject> map, String kind, JsonObject e) {
        String id = str(e, "id");
        if (id.isEmpty()) return;
        map.put(id, e);                               // upsert = replace the whole entity
        int cap = caps.getOrDefault(kind, DEFAULT_CAP.get(kind));
        while (map.size() > cap) map.remove(map.keySet().iterator().next()); // never grow unbounded
    }
}
```

**3. Replicate to players** like `BoardSync`: encode a compact binary blob (only the fields the
panels draw, strings already bounded by this spec), at most once per `boardSyncSeconds`, only
when `dirty` and only if the bytes changed; send the current blob to players who log in. The
client keeps it in a `ClientOps` holder that the card 5b panels read. Sort on the client
(services by `state` severity then `group`/`name`, alerts newest first); do not rely on upsert
arrival order.

**4. Display rules** the panels should follow: show `unknown`/stale in grey, never green; show
the source line (`OpsSource.state` + `detail`) when a source is `error` or `stale`; treat a
missing `remainingPct` as unknown rather than 0 %; `[ip]`, `[redacted]`, `[path]` and
`[withheld: ...]` are shown as they are.

## 8. Versioning

`features` negotiation is how extensions evolve: new optional fields can appear at any time
(receivers ignore unknown fields), new entity kinds arrive as new `ops.<kind>.upsert` types and
new `ops.snapshot` keys (receivers ignore them until they support them). A breaking change would
get a new feature name (e.g. `ops2`), never a silent change of `ops`.

## 9. Verifying an adapter

```
python3 -m hermes_adapter --ops-mock --ops-once                 # one ops.snapshot (generic data), then exit
python3 -m hermes_adapter --port 17878 --ops-mock               # serve; in a second shell:
python3 scripts/ops_watch.py --port 17878                       # opted-in client: snapshot + live ops.* lines
python3 scripts/ops_watch.py --port 17878 --plain --seconds 30  # control client: must report 0 ops.* messages
OPS_MOCK=1 scripts/run-upstream-checks.sh                       # upstream zod schema + fake-mod.ts while ops traffic flows
```

`hermes-adapter/tests/test_ops*.py` cover normalization, bounds, privacy canaries, plugin loading,
the wire (opt-in, snapshot, incremental upsert/remove, error status, caps), fuzzed input and
stalled clients.

Any adapter (not only this one) can check its output with the validator in
`hermes-adapter/hermes_adapter/ops_schema.py`: `validate(message, strict=True)` returns the list
of spec violations (types, required fields, enums, lengths, ranges, id format, caps). The
`ops_watch.py` client runs it on every message it receives, and this adapter's tests run it on
every message they see on the wire, on two full cycles of the mock and on the JSON examples in
this document.
