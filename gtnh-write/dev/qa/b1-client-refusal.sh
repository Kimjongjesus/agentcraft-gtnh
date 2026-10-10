#!/usr/bin/env bash
# B1 add-on (card 7 QA): the offline-mode dev client must be refused by the ONLINE-mode server. Throwaway data only.
# Usage: QA_DIR=$HOME/.../qa-b1 JAVA_HOME=... GRADLE_USER_HOME=... b1-client-refusal.sh   (run it in the background)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
R="$HERE/run-write-qa.sh"
QA="${QA_DIR:?QA_DIR}"
EV="$QA/evidence"
mkdir -p "$EV"
"$R" prep online 00000000-0000-4000-8000-000000000001 QAOwner
"$R" control start
"$R" server start
sleep 3
"$R" client start
for i in $(seq 1 60); do
  grep -qi "lost connection\|Failed to verify" "$QA/server.log" && break
  sleep 2
done
sleep 30   # let the client show its own error before it is stopped
echo "--- server log (join attempt):"
grep -i "verify username\|lost connection\|logged in\|joined the game\|UUID of player\|Disconnect" "$QA/server.log" | tail -8
echo "--- client log (reason shown to the player):"
grep -i "Failed to login\|Invalid session\|verify\|Disconnect\|Bad login\|lost connection\|Connection Lost\|authenticat" "$QA/client.log" | tail -8
echo "--- players on the server now:"; RUN_WAIT=2 "$R" server run "list"
cp "$QA/server.log" "$EV/client-refusal-server.log"; cp "$QA/client.log" "$EV/client-refusal-client.log"
"$R" client stop; "$R" server stop; "$R" control stop
