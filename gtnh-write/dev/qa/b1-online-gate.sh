#!/usr/bin/env bash
# B1 (card 7 QA): the arming gate in ONLINE mode, on the test PC, throwaway data only.
#   - verify (all PASS), status
#   - whitelist off -> disarmed within 30 s and audited; whitelist on -> re-armed
#   - op / deop / whitelist add / whitelist remove of a second placeholder profile
#   - the offline dev client must be refused by the online-mode server
#   - wrong key on the control service (handshake fails), a policy that names another actor (disarmed)
# Usage: QA_DIR=$HOME/.../qa-b1 JAVA_HOME=... GRADLE_USER_HOME=... b1-online-gate.sh   (output is the evidence; run it in the background)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
R="$HERE/run-write-qa.sh"
QA="${QA_DIR:?QA_DIR}"
OWNER="00000000-0000-4000-8000-000000000001"   # placeholder version 4 UUID
EV="$QA/evidence"
mkdir -p "$EV"
step() { echo; echo "######## $*  ($(date +%H:%M:%S))"; }
run() { RUN_WAIT="${WAIT:-2}" "$R" server run "$1"; }

step "prep + start (online-mode=true, white-list=true, whitelist = owner only, ops empty)"
"$R" prep online "$OWNER" QAOwner
"$R" control start
"$R" server start
sleep 3
step "agentcraft write verify"; WAIT=3 run "agentcraft write verify"
step "agentcraft write status"; run "agentcraft write status"

step "whitelist off (expect: disarmed within 30 s, audited)"
run "whitelist off"; t0=$(date +%s)
AUD="$("$R" srvdir)/agentcraft-write-audit.log"
for i in $(seq 1 18); do
  sleep 2
  grep -qi "disarm" <(tail -n 6 "$AUD") && break
done
echo "(game-side audit showed the disarm $(( $(date +%s) - t0 )) s after 'whitelist off'; the limit is 30 s)"
grep -i "disarm\|arm" "$AUD" | tail -3
run "agentcraft write status"
step "whitelist on (expect: re-armed by itself within 30 s)"
run "whitelist on"; sleep 32
run "agentcraft write status"

step "op QAOther (a second op: expect disarmed after verify)"; run "op QAOther"; WAIT=3 run "agentcraft write verify" | grep -i "ops-only\|DISARM\|all checks"
step "deop QAOther"; run "deop QAOther"; WAIT=3 run "agentcraft write verify" | grep -i "ops-only\|DISARM\|all checks"
step "op QAOwner (the owner as the only op is allowed)"; run "op QAOwner"; WAIT=3 run "agentcraft write verify" | grep -i "ops-only\|DISARM\|all checks"; run "deop QAOwner"
step "whitelist add QAOther (expect FAIL whitelist-is-owner)"; run "whitelist add QAOther"; WAIT=3 run "agentcraft write verify" | grep -i "whitelist-is\|DISARM\|all checks"
step "whitelist remove QAOther"; run "whitelist remove QAOther"; WAIT=3 run "agentcraft write verify" | grep -i "whitelist-is\|DISARM\|all checks"

step "the offline dev client joins this online-mode server (expect: refused, 'Failed to verify username')"
"$R" client start
for i in $(seq 1 90); do
  grep -qi "Failed to verify username\|Disconnecting\|lost connection" "$QA/server.log" "$QA/client.log" 2>/dev/null && break
  sleep 2
done
sleep 4
echo "--- server log lines about the join:"; grep -i "verify username\|Disconnect\|logged in\|lost connection\|UUID of player" "$QA/server.log" | tail -8
echo "--- client log lines:"; grep -i "verify username\|Disconnect\|Failed\|Connection Lost\|connect" "$QA/client.log" | grep -vi "adapter\|STDERR\|gson" | tail -8
"$R" client stop

step "wrong key on the control service (handshake must fail -> disarmed)"
"$R" control stop; "$R" control start --wrong-key; sleep 36
WAIT=3 run "agentcraft write verify" | grep -i "control-link\|DISARM\|all checks"; run "agentcraft write status" | head -3
echo "--- control service log:"; tail -5 "$QA/control.log" | cut -c1-200
"$R" control stop; "$R" control start; sleep 36
step "right key again (expect re-armed)"; WAIT=3 run "agentcraft write verify" | grep -i "control-link\|DISARM\|all checks"

step "a policy that names a different actor (expect FAIL policy-actors-equal-owner)"
"$R" control stop; "$R" control start --other-actor; sleep 36
WAIT=3 run "agentcraft write verify" | grep -i "policy-actors\|DISARM\|all checks"
"$R" control stop; "$R" control start; sleep 36
step "right policy again (expect re-armed)"; WAIT=3 run "agentcraft write verify" | grep -i "policy-actors\|DISARM\|all checks"

step "game-side audit (last 30)"; WAIT=3 run "agentcraft write audit 30"
step "stop"
SRVDIR="$("$R" srvdir)"
cp "$QA/server.log" "$EV/server.log"; cp "$QA/control-audit.jsonl" "$EV/control-audit.jsonl" 2>/dev/null; cp "$QA/control.log" "$EV/control.log"; cp "$QA/client.log" "$EV/client.log" 2>/dev/null
cp "$SRVDIR/agentcraft-write-audit.log" "$EV/agentcraft-write-audit.log" 2>/dev/null
"$R" server stop; "$R" control stop
echo "evidence in $EV"
