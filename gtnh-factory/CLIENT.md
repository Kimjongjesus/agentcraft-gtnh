# Optional Oracle client

Install the same AI Factory jar on the client only after the approved trial gate.
A server does not require clients to install it. No Minecraft packets or world
commands are added. All requests go to the Python Oracle, not the Java telemetry
listener. The companion is disabled until configured locally.

Create `config/oracle-client.properties` in the client instance:

```properties
enabled=true
endpoint=http://127.0.0.1:8790
tokenFile=oracle-client.token
```

The endpoint must be HTTPS or numeric loopback HTTP. Use an approved local tunnel
for a remote Oracle; do not send bearer credentials over plaintext LAN HTTP.
Put an existing Oracle player credential in `config/oracle-client.token` using a
local private editor, never chat. On POSIX the token file must have no group/other
permissions (0600). On Windows restrict its ACL to your account. Do not commit or
share either local configuration or credentials. Configuration loads at startup.
A missing/invalid configuration disables the companion and emits only a generic
warning; credential values are never logged. No credential is entered in-game.

Commands (handled by Forge's client command registry):

- `/oracle status`
- `/oracle ask power Why is the furnace idle?` (personas: plain, terminal, power,
  chem, storage; unknown personas fall back to plain)
- `/oracle diagnose Electric Blast Furnace`
- `/oracle shift`

Requests are GET-only, single-flight, on a daemon worker; no queue grows under
spam. Responses return on the client tick, not the HTTP worker. Leaving/changing
a world discards pending responses. Connect/read timeouts are 3/10 seconds,
responses are capped at 512 KiB, and redirects are rejected. Chat is bounded to
24 lines; remote text cannot insert interactive Minecraft chat components.

The approved GET ask route authenticates with the Authorization header, rejects
empty/oversized/duplicate query parameters, and does not log request query text.
Disable URL-query logging in any future proxy as well; query strings are private.

The passive upper-right HUD refreshes base status every 15 seconds and shift
history every 60 seconds while a world is open. It shares the same single-flight
worker (busy reads are not queued). It shows freshness, incomplete coverage,
faults, zero stock among returned rows, and shift event/capture counts. Cached
data is marked stale and cleared on a world change; a failed read shows unknown.
It is hidden with F1, debug overlay, or another screen. It adds no input handling.

Press `B` (remappable in Controls / Factory Oracle) for the non-pausing Oracle
screen. Check for GTNH key conflicts before trial. Ten tabs cover shift/status,
detective, persona listing/ask, what-if/improvements, X-ray/locate,
recorder/why-at-time, quests, historian/tour, crew board and existing blueprint
ghost/validation. The mode button cycles read views within a tab. Fields are
labelled; Enter or Read fetches, Tab changes field focus, mouse wheel or Prev/Next
pages output, Escape closes. Responses retain the API's provenance, incomplete
coverage and non-execution labels. The screen is a paginated textual inspection
view, not a 3D in-world ghost placer or navigational HUD. Blueprint IDs come from
existing proposals; creating/approving them remains outside the in-game client.
No authored-record write controls or world-action paths exist.

Verification is currently local build + synthetic socket tests only. No live
GTNH client/server trial is implied by compilation.
