#!/usr/bin/env bash
# B2 (card 7 QA): the loopback dry-run override, dev client screenshots, on the test PC, throwaway data only.
#   part 1: owner = the offline dev player (version 3 UUID), NO override property -> verify must FAIL online-mode and owner-uuid-v4
#   part 2: -Dagentcraft.write.devOverride=loopback-dry-run -> online-mode / v4 show OVERRIDDEN, everything else PASS;
#           the dev client (headless mutter) walks dispatch -> Confirm -> applied (dry run), the lock while a Confirm screen is
#           open, a refused request, unlock, the decision screen, forms, write actions, chat, /ask. Control service: --dry-run.
# Usage: QA_DIR=$HOME/.../qa-b2 JAVA_HOME=... GRADLE_USER_HOME=... b2-dry-run-screens.sh   (run it in the background)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
R="$HERE/run-write-qa.sh"
QA="${QA_DIR:?QA_DIR}"
EV="$QA/evidence"
mkdir -p "$EV"
step() { echo; echo "######## $*  ($(date +%H:%M:%S))"; }
run() { RUN_WAIT="${WAIT:-2}" "$R" server run "$1"; }

step "prep (online-mode=false, server-ip=127.0.0.1, white-list=true, whitelist + ops = the dev player)"
"$R" prep loopback Developer
step "the UNMODIFIED control service must refuse this policy (its actors are an offline, version 3 UUID)"
QA_CONTROL_SRC= "$R" control start || true
echo
echo "QA-ONLY WORKAROUND: the control service has no dev allowance for a version 3 actor, so from here it runs from a throwaway COPY"
echo "(QA_CONTROL_SRC) whose actor check is skipped when QA_ALLOW_V3_ACTOR is set. Nothing in the repository is changed."
: "${QA_CONTROL_SRC:?QA_CONTROL_SRC = a copy of hermes-adapter with the actor version check relaxed for QA}"
export QA_ALLOW_V3_ACTOR=1
"$R" control start
step "part 1: server WITHOUT the override property"
"$R" server start
sleep 4
WAIT=3 run "agentcraft write verify"
run "agentcraft write status"
cp "$QA/server.log" "$EV/part1-no-override-server.log"
"$R" server stop

step "part 2: server WITH -Dagentcraft.write.devOverride=loopback-dry-run"
QA_SERVER_JVM="-Dagentcraft.write.devOverride=loopback-dry-run" "$R" server start
sleep 4
WAIT=3 run "agentcraft write verify"
run "agentcraft write status"
"$R" adapter start
step "client (offline dev player 'Developer', private headless mutter)"
"$R" client start
for i in $(seq 1 150); do grep -q "Developer joined the game" "$QA/server.log" && break; sleep 2; done
grep "Developer joined\|UUID of player" "$QA/server.log" | tail -3
sleep 25
step "scenes A (dispatch, confirm, applied, lock during confirm, refused)"
"$R" server feed "$HERE/b2-scenes-a.txt"
step "after the lock: status + audit"
WAIT=3 run "agentcraft write status"
WAIT=3 run "agentcraft write audit 20"
"$R" control status
cp "$QA/control-audit.jsonl" "$EV/control-audit-after-lock.jsonl"
step "unlock: the game side from the console, then the Hermes side (terminal tool only)"
run "agentcraft write unlock"
"$R" control unlock-hermes
sleep 6
WAIT=3 run "agentcraft write status"
step "scenes B (decisions, forms, write actions, chat, /ask)"
"$R" server feed "$HERE/b2-scenes-b.txt"
sleep 3
step "final status + audit"
WAIT=3 run "agentcraft write status"
WAIT=3 run "agentcraft write audit 30"

step "collect evidence"
SRVDIR="$("$R" srvdir)"
mkdir -p "$QA/shots"
cp "$("$R" client shot-dir)"/screenshots/devshot-*.png "$QA/shots/" 2>/dev/null
cp "$QA/server.log" "$EV/part2-server.log"; cp "$QA/control.log" "$EV/control.log"; cp "$QA/client.log" "$EV/client.log"; cp "$QA/adapter.log" "$EV/adapter.log"
cp "$QA/control-audit.jsonl" "$EV/control-audit.jsonl"
cp "$SRVDIR/agentcraft-write-audit.log" "$EV/agentcraft-write-audit.log" 2>/dev/null
ls -la "$QA/shots"
step "stop"
"$R" client stop; "$R" server stop; "$R" adapter stop; "$R" control stop
echo "evidence in $EV, screenshots in $QA/shots"
