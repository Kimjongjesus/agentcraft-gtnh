#!/usr/bin/env bash
# Dev/QA run for the HQ (card 2): a plain Forge 1.7.10 dev server from RFG (FLAT world, loopback,
# port 25571, no GTNH modpack) with this mod, console through a FIFO, optionally a dev client
# under an existing X display (Xvfb on ai-ops, or any $DISPLAY) that auto-joins and takes a
# screenshot whenever the server says "devshot NAME" (DevShots, -Dagentcraft.dev.shotOnChat).
#
# Usage (from gtnh-mod/, with GRADLE_USER_HOME/JAVA_HOME exported):
#   dev/run-hq-qa.sh start <adapter ws url> [display]   # server (+ client if a display is given)
#   dev/run-hq-qa.sh cmd "<console command>"             # e.g. "agentcraft anchor list"
#   dev/run-hq-qa.sh stop
# Logs: run/server-dev.log, run/client-dev.log; screenshots: run/client/devshot-*.png.
# Nothing outside run/ is written.
set -euo pipefail

PORT=25571
ROOT="$(pwd)"
FIFO=run/server/console.fifo

case "${1:-}" in
  start)
    URL="${2:?adapter ws:// url}"
    DISP="${3:-}"
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
spawn-npcs=false
spawn-protection=0
motd=AgentCraft dev QA server
EOF
    for side in server client; do
      sed "s#^    S:adapterUrl=.*#    S:adapterUrl=$URL#" dev/agentcraftgtnh.dev.cfg > "run/$side/config/agentcraftgtnh.cfg"
    done
    rm -f "$FIFO"
    mkfifo "$FIFO"
    nohup sleep infinity > "$FIFO" 2>/dev/null &
    echo $! > run/console-holder.pid
    JAVA_TOOL_OPTIONS="-Dagentcraft.dev.noon=1" nohup ./gradlew --no-daemon --offline runServer < "$FIFO" > run/server-dev.log 2>&1 &
    echo $! > run/server-gradle.pid
    for _ in $(seq 1 150); do
      grep -q 'Done (' run/server-dev.log 2>/dev/null && break
      sleep 2
    done
    grep -q 'Done (' run/server-dev.log || { echo "dev server did not start"; exit 1; }
    echo "server up on 127.0.0.1:$PORT"
    if [ -n "$DISP" ]; then
      DISPLAY="$DISP" JAVA_TOOL_OPTIONS="-Dagentcraft.dev.connect=127.0.0.1:$PORT -Dagentcraft.dev.shotOnChat=1" \
        nohup ./gradlew --no-daemon --offline runClient > run/client-dev.log 2>&1 &
      echo $! > run/client-gradle.pid
      echo "client starting on $DISP (log run/client-dev.log)"
    fi
    ;;
  cmd)
    shift
    echo "$*" > "$FIFO"
    ;;
  feed)
    # feed a file of console commands (blank lines and # comments skipped), then print the log lines it produced
    FILE="${2:?commands file}"
    START=$(wc -l < run/server-dev.log)
    while read -r line; do
      case "$line" in ''|'#'*) ;; *) echo "$line" > "$FIFO"; sleep "${FEED_DELAY:-0.3}";; esac
    done < "$FILE"
    sleep "${FEED_SETTLE:-3}"
    tail -n +"$((START + 1))" run/server-dev.log
    ;;
  run)
    # one command, then the server log lines it produced
    shift
    START=$(wc -l < run/server-dev.log)
    echo "$*" > "$FIFO"
    sleep "${RUN_WAIT:-2}"
    tail -n +"$((START + 1))" run/server-dev.log
    ;;
  stop)
    # only talk to the console while a server reads it: writing a FIFO nobody reads blocks forever
    if [ -p "$FIFO" ] && [ -f run/server-gradle.pid ] && kill -0 "$(cat run/server-gradle.pid)" 2>/dev/null; then
      echo "say devquit" > "$FIFO"; sleep 5; echo stop > "$FIFO"
    fi
    for _ in $(seq 1 60); do
      [ -f run/server-gradle.pid ] && kill -0 "$(cat run/server-gradle.pid)" 2>/dev/null || break
      sleep 1
    done
    for f in run/client-gradle.pid run/server-gradle.pid run/console-holder.pid; do
      [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true
      rm -f "$f"
    done
    rm -f "$FIFO"
    echo stopped
    ;;
  *)
    sed -n '2,13p' "$0"
    exit 2
    ;;
esac
