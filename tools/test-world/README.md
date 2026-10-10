# Disposable GTNH test-world harness

A Linux, Python standard-library-only harness for **a disposable dedicated-server copy**.
Its purpose is repeatable stopped-server snapshots and non-destructive restore rehearsal,
not production operation or permission to implement world actions.

**Current authorization covers local code development and synthetic tests only.** Real GTNH boot,
authenticated client login, and the machine-content undo demonstration below are deferred
until separately approved. Neither required JVM heap nor real boot time is known yet.
No local synthetic result establishes GTNH compatibility or live undo acceptance.

## Scope and safety boundary

Read [the roadmap](../../docs/ROADMAP.md), [the write-path proposal](../../docs/write-path.md)
(especially section 7), and [the world-action boundary](../../docs/world-protocol.md#7-read-only-and-action).
The target is GTNH 2.9.x / Forge 1.7.10. The write-path proposal is not an implemented feature;
this harness does not arm it, add an action module, or change the adapter's read-only boundary.

- The source is read-only to the harness: copy its complete server tree into a **new managed
  generation** under the harness root. Never start Java in the source, modify its permissions,
  or edit its EULA, properties, whitelist, ops, world, or logs.
- Supply an owner-approved disposable source, not a running production directory. The source
  must be stable and stopped throughout copying; a copy of files actively being saved is not
  a consistent backup. Keep source and state root separate and non-overlapping.
- Use real copies, not hard links that let writes affect the source. Unsafe symlinks, path
  traversal, unmanaged paths, ambiguous ownership, and uncertain process state must be refused.
- Snapshot the **full server tree**, not merely `world/`: dimensions, player data, machine NBT,
  configs, mods, properties, identity lists, and other files required for reproducibility.
  Snapshots are permitted only after a clean stop and confirmed process exit.
- Any directory-enumeration error aborts import, snapshot, reset or verification. Unreadable
  subtrees are never treated as empty. Failed partial copies remain for inspection, without
  a completed snapshot manifest or active-generation switch.
- Reset copies a named snapshot into another **new generation**, verifies it, then switches
  the active generation. Preserve the previous generation and snapshot for inspection.
  No in-place restore, deletion, pruning, or automatic retention cleanup is authorized.
  Plan disk capacity accordingly; insufficient space must fail without destroying prior data.
- Writes are confined to the explicitly acknowledged disposable state root. No production,
  VM/hypervisor, Hermes profile, board, service, deployment, or credential writes. No remote
  management or external automation is part of this harness.
- Do not run an unreviewed copied launch script. Select a compatible installed JDK and the
  pack's reviewed Java arguments; invoke Java using an argument list, not a shell string.
  `/path/to/jdk/bin/java` is a placeholder, not a download or installation instruction.

The disposable acknowledgement identifies a local write boundary. It is **not** permission
for a live boot, machine mutation, network change, EULA acceptance, or future world-action tier.

## CLI contract

Run from `tools/test-world/`. These examples use public placeholders only; state and copies
belong outside the repository. Replace paths deliberately, not with a production world.
Each subcommand accepts `--root` after its name. No configuration file is required.
Use the exact real, non-symlink JDK executable and the relative launch jar/argument file
identified by inspecting the approved pack's launch instructions. The filenames below
are placeholders, not a claim that a particular pack ships them.

```sh
python3 harness.py --help
python3 harness.py init --root /path/to/harness-state --source /path/to/disposable-copy --ack-disposable
python3 harness.py preflight --root /path/to/harness-state --java /path/to/jdk/bin/java --jar server-launch.jar --java-args-file java9args.txt --xms 1G --xmx 6G --reserve-mib 2048 --idle-confirmed
python3 harness.py snapshot --root /path/to/harness-state baseline
python3 harness.py reset --root /path/to/harness-state baseline
python3 harness.py verify --root /path/to/harness-state baseline
python3 harness.py start --root /path/to/harness-state --java /path/to/jdk/bin/java --jar server-launch.jar --java-args-file java9args.txt --xms 1G --xmx 6G --reserve-mib 2048 --boot-timeout 600 --stall-timeout 120 --idle-confirmed
python3 harness.py health --root /path/to/harness-state
python3 harness.py stop --root /path/to/harness-state --stop-timeout 120 --idle-confirmed
```

This is a command reference, **not a script to run sequentially without approvals**. `baseline`
is an example snapshot NAME. The heap values are starting examples, not measured requirements.
Omit `--java-args-file` only if the reviewed pack launch genuinely needs no argument file.
The argument file is trusted executable configuration, not a sandbox: inspect it for agents,
external paths, launch targets and memory options. It must contain JVM options only, not a
main class, `-jar`, or another launch target. Explicit heap flags follow the file so ordinary
duplicate heap options cannot silently increase the budget. Ambient `JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS`, and `_JAVA_OPTIONS` are refused when nonempty. No JDK version or actual
GTNH launcher compatibility is certified until the deferred live test.

`active.json` supplies the active generation ID; its server is under
`generations/GENERATION_ID/server/`. Inspect this metadata rather than guessing the newest
directory: failed copies remain present. Runtime state belongs outside any Git checkout.
The root and its control directories must be private and owned by the invoking user.

| Command | Intended behavior |
| --- | --- |
| `init --source PATH --ack-disposable` | Read a stable source; create managed state and a new full-server generation. No boot or source edits. |
| `preflight` | Check managed paths, stopped/running state, Java/launch inputs, server isolation, EULA, identity policy, RAM and gaming/job guards. Report failures without repairing infrastructure. It does not reserve disk or memory. |
| `snapshot NAME` | After confirmed clean shutdown, copy the entire active server and record a file inventory and content hashes. Never overwrite an existing named snapshot. |
| `reset NAME` | Require a stopped server; make and verify a new generation from the snapshot, preserving the previous one. Do not boot automatically. |
| `verify NAME` | While stopped, compare the active full tree to that snapshot: relative paths, missing/extra files, and file-content hashes. Run immediately after reset and **before Java starts**. |
| `start` | Re-run all mandatory guards and explicit human idle confirmation; launch only the managed active generation, with bounded boot/stall timeouts and current-run diagnostics. |
| `stop` | Re-check required guards and human confirmation; send the server's normal console `stop`, wait for saving and actual process exit, and record the outcome. |
| `health` | Report managed-process identity/liveness, current-run startup `Done` evidence, and the isolated listening port together; old logs or an unrelated port listener cannot establish readiness. |

A stale PID or a port alone is not process ownership. Never signal an arbitrary process to clear
a port. If the harness cannot identify its own run, stop and investigate rather than guessing.
After a managed run, lifecycle operations require its recorded zero exit, no boot failure,
both processes gone, and a matching receipt written by a successful `stop`. Merely exiting,
even with status zero, is not clean shutdown evidence. A failed boot, supervisor crash, or
stop timeout leaves evidence but blocks later snapshots, resets and starts; there is no
force/recovery flag. An operator must investigate and approve separate recovery/staging.
Do not delete run records or manufacture a stop receipt. An untouched never-started import
can be snapshotted, but source consistency remains the operator's responsibility.

## RAM, gaming/job guards, and bounded lifecycle

Startup **and stop** must fail closed on missing, unreadable, stale, or failed required RAM and
gaming/job-guard inputs. Check actual headroom, including applicable container/cgroup limits,
and leave explicit reserve for the OS, client, and other workloads; host-wide free memory alone
is insufficient. A user-supplied heap does not prove that it is safe or sufficient for GTNH.

Require a fresh human confirmation that gaming and conflicting jobs are idle for the intended
operation. Automated probes support that confirmation; they do not replace it. Unknown or busy
means refusal. There is no force, skip-guard, unattended approval, or idle-confirmation bypass.
The built-in denylist covers common Java, Steam/Wine, build, Node, Python and agent processes,
not every possible game or job. Before a live test, inventory local game/job executable names
and supply each additional basename via repeatable `--block-exe BASENAME` on preflight/start.
Those additions persist for stop. No command-line arguments or process environments are read.
The guard does not exempt the calling automation's ancestor processes: run from an approved
plain terminal when idle, rather than bypassing a refusal caused by a Python/agent launcher.
Unreadable process executables, including those owned by other users, fail closed; do not
escalate privileges merely to make a probe pass. Continuous post-start gaming detection is
not implemented: the owner must preserve the approved idle window and supervise the run.
Do not stop games, jobs, or services to make a guard pass. If guards block shutdown of a running
disposable server, report that it remains running and obtain an approved clean-stop path; do not
claim it stopped or create a snapshot. Do not work around a refusal with arbitrary process kills.

Configure bounded startup and no-progress/stall timeouts using the implemented CLI. An expired
timeout is a failed/unknown run, never readiness. Preserve private diagnostics: run identity,
Java exit status if available, elapsed time, last progress, bounded current-run log tail,
port check, and guard failures. State clearly whether the managed process is still alive.
No automatic restart loop, SIGKILL-as-clean-stop, or snapshot of a failed/uncertain shutdown.
Real boot timing and required heap remain **unknown until measured on an approved GTNH run**.
Timeouts return a failure and retain the process handle; they do not stop the server. Use
`health` and an approved guarded `stop`. In `runs/RUN_ID/`, `console.log` retains the bounded
head and `console-tail.log` the bounded tail (each `--log-bytes`, default 1 MiB); `state.json`
records dropped bytes and failure. The server's own logs/world files are not size-capped.
No disk pruning is built in: plan capacity for every full copy plus growing server logs.

Health requires all of these at once:

1. The exact managed Java process for this run is alive.
2. The startup `Done` marker was emitted **during this run**, after its launch boundary.
3. The configured isolated port is reachable/listening for this run, not another server.

This checks server readiness, not authenticated login, functioning mods, or restored machine
contents. Those require the separate live acceptance procedure.

## Manual isolated-server preparation (after approval)

Prepare only the active disposable generation, while stopped. Keep the source unchanged.
The server must use these properties:

```properties
online-mode=true
white-list=true
max-players=1
enable-rcon=false
enable-query=false
server-ip=127.0.0.1
server-port=25575
```

Both `server.properties` and `eula.txt` must use the harness's deliberately strict Java
Properties subset: ASCII keys matching `[A-Za-z0-9_.-]+` immediately followed by `=`,
and printable ASCII values without leading/trailing spaces. Empty values and literal
spaces, `:` and `=` inside values are allowed. CR, LF and CRLF line endings, blank lines,
and comments beginning with `#` or `!` after optional space/tab/form-feed indentation are
supported. Duplicate keys, colon/whitespace separators, escapes, continuations and other
non-ASCII/control characters in entries are refused rather than normalized. The harness
does not rewrite copied files to make them pass. Any approved compatibility edits belong
only in the stopped disposable generation; preserve the source and prior generations.

`25575` is an example isolated nonproduction port, **not a verified free port**. Select and
verify a free port different from the production server; retain the loopback bind. Never broaden
the bind, open a firewall, establish a tunnel, or edit routing without explicit separate approval.
A client on another machine cannot directly reach loopback: arrange an approved access method
or a local client instead of weakening isolation. Online-mode account authentication is required;
this is not a promise of fully offline operation, nor permission for this local-only phase to
contact external services.

- The owner must read and manually accept the Minecraft EULA in the disposable generation's
  `eula.txt`. The harness must not write `eula=true` or treat `--ack-disposable` as acceptance.
  A copied accepted file is not a new permission grant; confirm the owner's acceptance for this use.
- Review copied `whitelist.json` and `ops.json` before any boot. Other whitelist or operator
  entries may be removed **only from the disposable generation and only after explicit approval**.
  Preserve the source and prior generations. Never boot with unreviewed copied operators: ops
  can bypass the whitelist in this server version.
- Before launch, manually prepare `whitelist.json` in the stopped active disposable generation
  as an array containing exactly one object with `uuid` and `name` fields. Obtain the account's
  verified online UUID through the owner's normal authenticated identity flow, not a guessed
  offline UUID. Use its canonical lowercase hyphenated form. `ACCOUNT_NAME` denotes the account
  symbolically here; never commit the actual name or UUID. The harness validates shape, not
  ownership or external authentication. `ops.json` must be an empty array or one object with
  that same UUID/name (and valid optional `level`/`bypassesPlayerLimit` fields). Any operator
  grant for the manual demonstration requires explicit owner permission.
- There is no general console/enrollment CLI and no RCON. The private FIFO is solely the
  harness's graceful-stop transport, not an invitation to bypass its lifecycle. An empty or
  multi-account whitelist is refused. If a verified online identity cannot be obtained for
  offline preparation, stop for an approved enrollment procedure; do not weaken authentication.
- Do not write account passwords, session tokens, authentication secrets, or credentials into
  arguments, files, logs, source control, or screenshots. Authentication stays in the normal
  client/account flow. Console commands carry only the symbolic account name in public docs.

Property checks and whitelist setup do not enable the proposed write module. Its separate
review, identity gates, negative tests, and owner approval remain required.

## Deferred live acceptance: GT machine-content undo

**Not performed or authorized by local synthetic testing.** Obtain explicit owner permission
for the disposable-server boot, the authenticated client, and the named in-game mutations first.
Run the RAM/gaming/job guards and fresh human idle confirmation for every start and stop.
The demonstration must show restoration of an actual GregTech machine and its contents, not
merely restoration of a marker file or a matching directory hash.

1. **Establish an isolated, non-ticking baseline.** In the disposable world choose an agreed GT
   machine and location (dimension and block coordinates). Disconnect power, pipes, automation,
   AE access, neighboring transfers, and active recipes; choose an inert setup so contents,
   fluids, and energy cannot change between observations. Record machine/block type, registry
   identity and metadata, tier and orientation, exact location, every relevant inventory slot
   (including empty slots), item types/counts and relevant NBT, tank fluid identities/amounts,
   and stored energy. Include upgrades and covers where applicable. If hidden NBT cannot be
   observed in the GUI, retain an approved private read-only stopped-world inspection alongside
   the client observations; a pretty screenshot alone does not establish unseen fields.
2. **Save the baseline.** Capture private baseline GUI/world screenshots and notes. Disconnect
   the client, cleanly stop the managed server, and prove saving completed and the process exited.
   Take `snapshot baseline`. Retain its complete file inventory/content hashes privately.
3. **Make an observable, owner-approved mutation.** Boot with all guards, join using the single
   authenticated whitelisted account, and manually change the agreed machine/block and contents.
   For example replace/remove the machine at its recorded location and alter item slots/counts
   or fluids as specifically approved. Record exact before/after values and client screenshots.
   Do not invoke world-action automation or mutate a production world.
4. **Prove that change persisted.** Cleanly stop again, prove process exit, and retain private
   changed-state hashes and relevant stopped-world/NBT inspection. Show that the target machine
   or contents actually changed, not just a timestamp or unrelated log. If necessary, use an
   additional approved boot/client inspection and clean stop to establish persistence. Preserve
   the changed generation; optional `snapshot changed` is a separate named snapshot, not an
   overwrite of the baseline.
5. **Reset while stopped, then verify before boot.** Run `reset baseline`, producing a new active
   generation and leaving the changed generation intact. Immediately run `verify baseline`
   **before starting Java**. Require a hash-identical full server file tree, with no missing or
   extra files. Keep the successful comparison privately. If it fails, do not boot or claim undo.
6. **Verify in-game restoration.** Only after that exact comparison passes, boot with all guards
   and join the authenticated client. Visually inspect the same dimension/location and verify
   machine identity/type/tier/orientation and all baseline slot/item counts, relevant NBT,
   fluids, energy, covers and upgrades. Compare to the baseline evidence; record private restored
   screenshots and any approved read-only supplemental inspection. Then cleanly stop.

The first post-reset boot changes logs, session locks, and potentially world files; that is why
hash verification must precede boot. **Hashes alone are not real in-game undo proof.** Live
acceptance requires both pre-boot byte restoration and post-boot machine/content observations.
If ticking cannot be controlled, or a field cannot be verified, mark acceptance incomplete and
explain the limitation rather than silently omitting it.

## Evidence and completion reporting

Keep server copies, state, world data, console logs, identities, screenshots, NBT inspections,
and hash manifests outside the public repository. No private evidence, actual host paths,
addresses, account names, board data, or credentials are to be committed. A public report may
state sanitized test counts and outcomes, with no private evidence attached.

Report these independently:

| Layer | What its result establishes |
| --- | --- |
| Local synthetic unit/integration tests | Only the tested filesystem, guard, lifecycle, and fake-server behavior; record the actual executed command and result, not an assumed pass. |
| Approved real GTNH boot | Pack/JDK launch and measured boot time/heap/headroom, current-run `Done` plus port, and authenticated client access. Currently deferred; boot time and required heap unknown. |
| Approved GT machine-content undo | Persisted real mutation, preserved old generation, exact reset verification before boot, and matching machine/contents observed in-game afterward. Currently deferred. |

No live result is claimed in this README. Local implementation completion is not live
acceptance, production readiness, approval of card 7, or authorization for autonomous world writes.

## Local synthetic verification

From the repository root, with `TMPDIR` set to an existing private scratch directory outside
the checkout, run `python3 -m unittest discover -s tools/test-world/tests -v`.
The suite creates only synthetic bytes and a fake executable, not valid Minecraft world files.
It mocks idle-process and memory observations for lifecycle tests, separately testing refusal
logic. The fake server opens real loopback sockets, emits test markers, and exits via stdin
`stop`; it does not emulate Forge, authentication, JVM argument expansion, or machine NBT.
It exercises full-copy/change/reset/hash comparison and preservation of source/old generations,
but it must never be reported as the deferred live machine-content acceptance demonstration.
