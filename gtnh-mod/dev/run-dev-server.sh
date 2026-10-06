#!/usr/bin/env bash
# Boot the DEV COPY of the GTNH server (gaming-spare ~/gtnh-dev/server) with a capped heap.
#
# Never point this at the real Pterodactyl world. It refuses to start unless server.properties
# carries the dev-copy port (25570), and it never edits server.properties itself
# (prepare-dev-copy.sh does that once).
#
# Console: commands go in through a FIFO, output goes to dev-console.log:
#   echo "list" > ~/gtnh-dev/server/console.fifo
# Stop cleanly with stop-dev-server.sh.
set -euo pipefail

SERVER_DIR="${GTNH_DEV_SERVER:-$HOME/gtnh-dev/server}"
JAVA="${GTNH_DEV_JAVA:-$HOME/gtnh-dev/jdk25/bin/java}"
XMX="${GTNH_DEV_XMX:-5G}"
XMS="${GTNH_DEV_XMS:-1G}"

cd "$SERVER_DIR"

if ! grep -qx 'server-port=25570' server.properties; then
  echo "refusing: $SERVER_DIR/server.properties is not the dev copy (server-port != 25570)" >&2
  exit 1
fi
if [ -f dev-server.pid ] && kill -0 "$(cat dev-server.pid)" 2>/dev/null; then
  echo "dev server already running (pid $(cat dev-server.pid))" >&2
  exit 1
fi

rm -f console.fifo
mkfifo -m 600 console.fifo
# keep a writer on the FIFO so the server's stdin never sees EOF
nohup sleep infinity > console.fifo 2>/dev/null &
echo $! > console-holder.pid

nohup "$JAVA" "-Xms$XMS" "-Xmx$XMX" \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
  -Dfml.readTimeout=180 \
  @java9args.txt \
  -jar lwjgl3ify-forgePatches.jar nogui < console.fifo > dev-console.log 2>&1 &
echo $! > dev-server.pid
echo "started dev server pid $(cat dev-server.pid) (heap $XMS..$XMX), log: $SERVER_DIR/dev-console.log"
