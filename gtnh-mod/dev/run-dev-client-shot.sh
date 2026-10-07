#!/usr/bin/env bash
# Dev/QA screenshot run (test PC): a plain Forge 1.7.10 dev server + dev client from RFG,
# both with this mod, no GTNH modpack. The client runs inside a private headless mutter
# compositor (its own dbus session, virtual monitor, Xwayland), auto-joins the dev server,
# aims at the agent NPC and saves screenshots (DevShots), then quits.
#
# Usage (from gtnh-mod/, with GRADLE_USER_HOME/JAVA_HOME exported):
#   dev/run-dev-client-shot.sh <adapter ws url> [shots] [every-ticks]
# Screenshots land in run/client/screenshots/. Nothing outside run/ is written.
set -euo pipefail

URL="${1:?adapter ws:// url}"
SHOTS="${2:-4}"
EVERY="${3:-500}"
PORT=25571
ROOT="$(pwd)"

mkdir -p run/server/config run/client/config
# Same person/machine that already accepted the Minecraft EULA for the GTNH server copy.
echo "eula=true" > run/server/eula.txt
cat > run/server/server.properties <<EOF
online-mode=false
server-port=$PORT
server-ip=127.0.0.1
level-type=FLAT
spawn-monsters=false
spawn-animals=false
spawn-protection=0
motd=AgentCraft dev QA server
EOF
for side in server client; do
  sed "s#^    S:adapterUrl=.*#    S:adapterUrl=$URL#" dev/agentcraftgtnh.dev.cfg > "run/$side/config/agentcraftgtnh.cfg"
done

rm -f run/server/console.fifo
mkfifo run/server/console.fifo
sleep infinity > run/server/console.fifo &
HOLDER=$!
JAVA_TOOL_OPTIONS="-Dagentcraft.dev.noon=1" ./gradlew --no-daemon runServer < run/server/console.fifo > run/server-dev.log 2>&1 &
SERVER=$!
cleanup() {
  echo stop > run/server/console.fifo 2>/dev/null || true
  sleep 8
  kill "$SERVER" 2>/dev/null || true
  kill "$HOLDER" 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 120); do
  grep -q 'Done (' run/server-dev.log 2>/dev/null && break
  sleep 2
done
grep -q 'Done (' run/server-dev.log || { echo "dev server did not start"; exit 1; }

# private headless compositor; the client is its child so it inherits DISPLAY/WAYLAND_DISPLAY.
# mutter does not exit when its command does, so the wrapper stops mutter ($PPID) afterwards.
timeout 900 dbus-run-session -- mutter --headless --wayland --virtual-monitor 1280x720 --wayland-display agentcraft-qa -- \
  sh -c 'JAVA_TOOL_OPTIONS="-Dagentcraft.dev.connect=127.0.0.1:$1 -Dagentcraft.dev.shots=$2 -Dagentcraft.dev.shotEvery=$3" "$0" --no-daemon runClient > run/client-dev.log 2>&1; kill -TERM $PPID' \
  "$ROOT/gradlew" "$PORT" "$SHOTS" "$EVERY" || true

ls -la run/client/screenshots/ 2>/dev/null || echo "no screenshots"
