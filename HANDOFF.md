# Disposable GTNH test-world harness — local-code handoff

Status: local code is ready for a demo/review decision. Real GTNH acceptance is deferred,
not passed. This follows the latest authorization for local work only.

## Built

- `tools/test-world/harness.py`: Linux/Python standard-library CLI with init, preflight,
  snapshot, reset, verify, start, stop and health commands.
- Full-server copies with SHA256 inventories (including dimensions and empty directories).
  Reset creates a new generation and atomically changes control metadata; source, snapshots
  and previous generations remain in place. No world deletion, overwrite or pruning.
- Private managed roots, lifecycle lock, link/path checks, PID/start-time/boot-ID ownership,
  fail-closed process/RAM/cgroup checks and required human idle confirmation.
- Loopback, online mode, one whitelist identity, restricted operators, disabled RCON/query,
  single-player capacity and manual EULA acceptance checked before launch.
- Explicit Java/jar selection, optional trusted JVM argument file, bounded startup/stall
  deadlines, current-run Done marker plus owned listening socket, graceful FIFO stop only,
  bounded console head/tail, and a clean-stop receipt before later lifecycle operations.
- `tools/test-world/README.md`: actual CLI examples, manual authenticated single-account
  preparation and a detailed real machine-content restoration acceptance procedure.

## Verified locally

Parent reran `python3 -m unittest discover -s tools/test-world/tests -v`:
44 tests passed in 12.271 seconds, exit code 0. `git diff --check` passed. Python syntax
checks and focused public-safety scans passed. All eight README operation examples parsed
with the real CLI parser (placeholder targets were not executed).

Tests exercise synthetic snapshot/change/reset/exact comparison, source and displaced-copy
preservation, path/link attacks, corrupt copies, lifecycle locks, cgroup budgets, guard
refusals, single-identity policy, launch argument handling, owned-port readiness, stale
identity/log rejection, boot/stall timeout, bounded crash-tail capture, supervisor failure,
graceful stop and unclean-run refusal. Fake subprocesses use real local sockets and stdin.
Idle-process and memory observations are mocked for lifecycle integration tests and refusal
logic is tested separately. These are not real Minecraft files, machine NBT or a JVM.

Raw verification output stays outside the public repository. No evidence directory,
world copy, real identity, operational address or credentials are committed.
No unrelated adapter/UI/Java builds were run because those modules are unchanged.

## Not verified / risks

- No remote host was contacted this run. No real server/world, VM or infrastructure was
  touched. Real GTNH boot time and required heap remain UNKNOWN. Example/default heap
  values are not measured results.
- Real snapshot -> approved machine mutation -> reset -> byte comparison -> client-visible
  machine/contents restoration is NOT performed. Synthetic bytes cannot satisfy it.
- The default process denylist is deliberately conservative but cannot enumerate all games
  or jobs. Add reviewed executable basenames and obtain a fresh idle confirmation. Inability
  to inspect any process fails closed; no privilege escalation or bypass is authorized.
- Java, mods and JVM argument files are trusted code, not a filesystem/network sandbox.
  Pack-specific launch inputs, absolute mod paths, external integrations and JDK compatibility
  need inspection in the approved live window. No real Java argument expansion was tested.
- Disk capacity is not reserved. Every generation and snapshot is a full copy; failed partial
  copies and application logs are retained. Plan capacity manually; no cleanup is automatic.
- Timeout reports failure without killing the server. Failed/unclean runs block new starts,
  snapshots and resets; stop can leave a stopped but unclean result. Recovery requires separate
  operator investigation, not deleting records or inventing a clean-stop receipt.
- Loopback-only networking requires an approved local client or separately approved access
  method. Online UUID ownership and successful authentication still need live verification.
- The required before/after test timing helpers were attempted, but rejected the execution
  context as a delegated-child board mutation. No timing marker success is claimed and no
  guard was bypassed. This workflow integration limitation needs intake attention.

## Decisions needed before live acceptance

1. Decide whether to send this local-code candidate for the independent review requested by
   the task. The builder has not self-approved, pushed, merged or deployed it.
2. Approve an idle test window and the specific remote commands/configuration/mutations.
   Recheck memory and gaming/job activity first; stop on any failed guard.
3. Confirm the disposable source, verified online account, reviewed launch jar/argument file,
   suitable heap/reserve and approved client access. Leave all real worlds untouched.
4. Execute the documented real machine restoration procedure and record actual boot time,
   configured heap/headroom, private before/changed/restored observations and pre-boot hashes.
   Only then can the full original acceptance criteria be evaluated.
