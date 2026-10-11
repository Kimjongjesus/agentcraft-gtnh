#!/usr/bin/env bash
# Card 7 QA runner (test PC only, throwaway data only). Starts, on 127.0.0.1 and with small heaps:
#   - the control service in --dry-run with the example board fixture (mock executors: nothing real runs),
#   - optionally the READ adapter on a generated fixture Hermes home (generic data) for the task wall / decisions,
#   - a plain Forge 1.7.10 dev server (RetroFuturaGradle runServer of gtnh-write, FLAT world, no modpack),
#   - optionally the dev client inside a private headless mutter compositor (as gtnh-mod/dev/run-dev-client-shot.sh).
# Nothing here ever points at a real server, world, Hermes or Pterodactyl. Everything is written under $QA_DIR and
# gtnh-write/run/ (the server refuses to start without the arena marker that 'prep' writes).
#
# Usage (needs JAVA_HOME and GRADLE_USER_HOME exported; the core dev jar must be built: ../gtnh-mod/build/libs):
#   export QA_DIR=$HOME/gtnh-dev/card7/qa-b1
#   run-write-qa.sh prep online   <owner-uuid-v4> <owner-name>     # B1: online-mode, owner whitelisted, ops empty
#   run-write-qa.sh prep loopback <owner-name>                     # B2: loopback dry-run override, offline UUID of <owner-name>
#   run-write-qa.sh control start [--wrong-key|--other-actor|--policy-file F] | stop | log | status | unlock-hermes
#   run-write-qa.sh adapter start|stop
#   run-write-qa.sh server start|stop|cmd "<console command>"|run "<command>"|feed <file>|log
#   run-write-qa.sh client start|stop|shot-dir
#   run-write-qa.sh srvdir | stop-all
# QA_CONTROL_SRC=<dir>  run the control service from another copy of hermes-adapter (see b2-dry-run-screens.sh)
# Ports (override with env): QA_MC_PORT=25581 QA_CONTROL_PORT=17879 QA_ADAPTER_PORT=17878. Heaps: QA_SERVER_XMX/QA_CLIENT_XMX (1536M).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRITE_DIR="${WRITE_DIR:-$(cd "$HERE/../.." && pwd)}"
REPO="$(cd "$WRITE_DIR/.." && pwd)"
QA="${QA_DIR:?set QA_DIR to a throwaway directory such as \$HOME/gtnh-dev/card7/qa-b1}"
case "$QA" in */qa-*) ;; *) echo "refusing: QA_DIR must be named qa-<something>" >&2; exit 2 ;; esac
MC_PORT="${QA_MC_PORT:-25581}"
CONTROL_PORT="${QA_CONTROL_PORT:-17879}"
ADAPTER_PORT="${QA_ADAPTER_PORT:-17878}"
SERVER_XMX="${QA_SERVER_XMX:-1536M}"
CLIENT_XMX="${QA_CLIENT_XMX:-1536M}"
SRV="$WRITE_DIR/run/server"
CLI="$WRITE_DIR/run/client"
FIFO="$SRV/console.fifo"
PIDS="$QA/pids"
GRADLE_ARGS=(--no-daemon --console=plain "-Dorg.gradle.jvmargs=-Xmx512m")
mkdir -p "$QA" "$PIDS"
chmod 700 "$QA"

pid_alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

prep() {
  local mode="${1:?online|loopback}" owner name
  if [ "$mode" = online ]; then
    owner="${2:?owner uuid (version 4)}"; name="${3:?owner name}"
  else
    name="${2:?owner name}"; owner="$(python3 "$HERE/qa_setup.py" offline-uuid "$name")"
  fi
  if pid_alive "$PIDS/server.pid" || pid_alive "$PIDS/control.pid"; then echo "stop the running QA processes first" >&2; exit 1; fi
  rm -rf "$SRV" "$CLI/screenshots" "$QA/state" "$QA/fixture"
  rm -f "$CLI"/devshot-*.png
  rm -f "$QA/control-audit.jsonl" "$QA/control.log" "$QA/server.log" "$QA/client.log" "$QA/adapter.log"
  mkdir -p "$SRV/config" "$CLI/config" "$QA/state" "$QA/shots"
  chmod 700 "$QA/state"
  : > "$SRV/.qa-arena"
  echo "eula=true" > "$SRV/eula.txt"
  {
    echo "server-port=$MC_PORT"
    echo "server-ip=127.0.0.1"
    echo "online-mode=$([ "$mode" = online ] && echo true || echo false)"
    echo "white-list=true"
    echo "level-type=FLAT"
    echo "spawn-monsters=false"
    echo "spawn-animals=false"
    echo "spawn-npcs=false"
    echo "spawn-protection=0"
    echo "max-players=4"
    echo "view-distance=4"
    echo "motd=AgentCraft write QA arena"
  } > "$SRV/server.properties"
  printf '[{"uuid":"%s","name":"%s"}]\n' "$owner" "$name" > "$SRV/whitelist.json"
  # two placeholder profiles in the server's profile cache, so op / whitelist add work by name without any Mojang lookup
  printf '[{"name":"%s","uuid":"%s","expiresOn":"2099-01-01 00:00:00 +0000"},{"name":"QAOther","uuid":"00000000-0000-4000-8000-000000000002","expiresOn":"2099-01-01 00:00:00 +0000"}]\n' "$name" "$owner" > "$SRV/usercache.json"
  if [ "$mode" = online ]; then echo '[]' > "$SRV/ops.json"
  else printf '[{"uuid":"%s","name":"%s","level":4}]\n' "$owner" "$name" > "$SRV/ops.json"; fi
  python3 "$HERE/qa_setup.py" key "$QA/key"
  python3 "$HERE/qa_setup.py" key "$QA/key-wrong"
  python3 "$HERE/qa_setup.py" policy "$REPO" "$QA/policy.json" "$owner" --chat
  python3 "$HERE/qa_setup.py" policy "$REPO" "$QA/policy-other-actor.json" "00000000-0000-4000-8000-000000000002" --chat
  python3 "$HERE/qa_setup.py" board "$REPO" "$QA/board.json"
  cat > "$SRV/config/agentcraftgtnhwrite.cfg" <<EOF
write {
    S:owner=$owner
    S:controlUrl=ws://127.0.0.1:$CONTROL_PORT/
    S:keyFile=$QA/key
    I:presenceRadius=8
}
EOF
  for side in server client; do
    sed -e "s#^    S:adapterUrl=.*#    S:adapterUrl=ws://127.0.0.1:$ADAPTER_PORT#" "$REPO/gtnh-mod/dev/agentcraftgtnh.dev.cfg" > "$WRITE_DIR/run/$side/config/agentcraftgtnh.cfg"
  done
  echo "$owner" > "$QA/owner-uuid"
  echo "$name" > "$QA/owner-name"
  echo "$mode" > "$QA/mode"
  echo "prepared $mode arena: owner=$owner name=$name (server dir $SRV)"
}

control() {
  case "${1:?start|stop|log}" in
    start)
      shift
      local policy="$QA/policy.json" key="$QA/key" extra=()
      while [ $# -gt 0 ]; do
        case "$1" in
          --wrong-key) key="$QA/key-wrong" ;;
          --other-actor) policy="$QA/policy-other-actor.json" ;;
          --policy-file) policy="$2"; shift ;;
          *) extra+=("$1") ;;
        esac
        shift
      done
      if pid_alive "$PIDS/control.pid"; then echo "control service already running" >&2; exit 1; fi
      (cd "${QA_CONTROL_SRC:-$REPO/hermes-adapter}"; nohup python3 -m hermes_control serve --policy "$policy" --key-file "$key" --state-dir "$QA/state" \
        --audit-file "$QA/control-audit.jsonl" --bind 127.0.0.1 --port "$CONTROL_PORT" --dry-run \
        --board-fixture "$QA/board.json" "${extra[@]+"${extra[@]}"}" < /dev/null > "$QA/control.log" 2>&1 &
        echo $! > "$PIDS/control.pid")
      sleep 2
      pid_alive "$PIDS/control.pid" && echo "control service up on 127.0.0.1:$CONTROL_PORT (dry run, policy $(basename "$policy"))" || { echo "control service did not start:"; cat "$QA/control.log"; exit 1; }
      ;;
    stop) [ -f "$PIDS/control.pid" ] && kill "$(cat "$PIDS/control.pid")" 2>/dev/null || true; rm -f "$PIDS/control.pid"; echo "control stopped" ;;
    unlock-hermes) # the Hermes-side lock can only be cleared here (terminal tool), never over the wire
      (cd "${QA_CONTROL_SRC:-$REPO/hermes-adapter}"; python3 -m hermes_control unlock --yes --state-dir "$QA/state") ;;
    status) (cd "${QA_CONTROL_SRC:-$REPO/hermes-adapter}"; python3 -m hermes_control status --state-dir "$QA/state") ;;
    log) cat "$QA/control.log" ;;
  esac
}

adapter() {
  case "${1:?start|stop}" in
    start)
      if pid_alive "$PIDS/adapter.pid"; then echo "adapter already running" >&2; exit 1; fi
      [ -d "$QA/fixture/hermes-home" ] || python3 "$HERE/qa_setup.py" fixture "$REPO" "$QA/fixture"
      (cd "$REPO/hermes-adapter"; nohup python3 -m hermes_adapter --bind 127.0.0.1 --port "$ADAPTER_PORT" --hermes-home "$QA/fixture/hermes-home" \
        --cast "$QA/fixture/hermes-home/agentcraft-cast.json" --poll 2 --no-journal < /dev/null > "$QA/adapter.log" 2>&1 &
        echo $! > "$PIDS/adapter.pid")
      sleep 2
      pid_alive "$PIDS/adapter.pid" && echo "read adapter up on 127.0.0.1:$ADAPTER_PORT (generic fixture data)" || { echo "adapter did not start:"; cat "$QA/adapter.log"; exit 1; }
      ;;
    stop) [ -f "$PIDS/adapter.pid" ] && kill "$(cat "$PIDS/adapter.pid")" 2>/dev/null || true; rm -f "$PIDS/adapter.pid"; echo "adapter stopped" ;;
  esac
}

server() {
  [ -f "$SRV/.qa-arena" ] || { echo "no arena marker in $SRV: run 'prep' first (this script only starts throwaway arenas)" >&2; exit 2; }
  case "${1:?start|stop|cmd|run|feed|log}" in
    start)
      if pid_alive "$PIDS/server.pid"; then echo "server already running" >&2; exit 1; fi
      rm -f "$FIFO"; mkfifo "$FIFO"
      (nohup sleep infinity > "$FIFO" 2>/dev/null < /dev/null & echo $! > "$PIDS/holder.pid")
      local opts="-Dagentcraft.dev.noon=1 ${QA_SERVER_JVM:-}"
      (cd "$WRITE_DIR"; _JAVA_OPTIONS="-Xms256M -Xmx$SERVER_XMX" JAVA_TOOL_OPTIONS="$opts" nohup nice -n 10 ./gradlew "${GRADLE_ARGS[@]}" runServer < "$FIFO" > "$QA/server.log" 2>&1 &
        echo $! > "$PIDS/server.pid")
      for _ in $(seq 1 180); do
        grep -q 'Done (' "$QA/server.log" 2>/dev/null && break
        pid_alive "$PIDS/server.pid" || break
        sleep 2
      done
      grep -q 'Done (' "$QA/server.log" && echo "server up on 127.0.0.1:$MC_PORT" || { echo "server did not start; tail:"; tail -30 "$QA/server.log"; exit 1; }
      ;;
    cmd) shift; echo "$*" > "$FIFO" ;;
    run) # one command, then the log lines it produced
      shift; local n; n=$(wc -l < "$QA/server.log"); echo "$*" > "$FIFO"; sleep "${RUN_WAIT:-2}"; tail -n +"$((n + 1))" "$QA/server.log" ;;
    feed) # a file of console commands (blank lines and # comments skipped; "sleep N" waits)
      local n; n=$(wc -l < "$QA/server.log")
      while read -r line; do
        case "$line" in ''|'#'*) ;; sleep\ *) sleep "${line#sleep }" ;; *) echo "$line" > "$FIFO"; sleep "${FEED_DELAY:-0.4}" ;; esac
      done < "${2:?commands file}"
      sleep "${FEED_SETTLE:-2}"; tail -n +"$((n + 1))" "$QA/server.log" ;;
    log) cat "$QA/server.log" ;;
    stop)
      if pid_alive "$PIDS/server.pid" && [ -p "$FIFO" ]; then echo stop > "$FIFO"; fi
      for _ in $(seq 1 45); do pid_alive "$PIDS/server.pid" || break; sleep 1; done
      for f in server holder; do [ -f "$PIDS/$f.pid" ] && kill "$(cat "$PIDS/$f.pid")" 2>/dev/null || true; rm -f "$PIDS/$f.pid"; done
      rm -f "$FIFO"; echo "server stopped" ;;
  esac
}

client() {
  case "${1:?start|stop|shot-dir}" in
    start)
      if pid_alive "$PIDS/client.pid"; then echo "client already running" >&2; exit 1; fi
      mkdir -p "$CLI/config"
      # a private headless compositor (own dbus session, virtual monitor, Xwayland); the client is its child. mutter does
      # not exit when its command does, so the wrapper stops mutter ($PPID) afterwards.
      local opts="-Dagentcraft.dev.connect=127.0.0.1:$MC_PORT -Dagentcraft.dev.shotOnChat=1 -Dagentcraft.dev.writeAuto=1 ${QA_CLIENT_JVM:-}"
      (cd "$WRITE_DIR"; nohup timeout 1500 dbus-run-session -- mutter --headless --wayland --virtual-monitor 1280x720 --wayland-display agentcraft-qa7 -- \
        sh -c '_JAVA_OPTIONS="$3" JAVA_TOOL_OPTIONS="$1" nice -n 10 "$0" --no-daemon --console=plain -Dorg.gradle.jvmargs=-Xmx512m runClient > "$2" 2>&1; kill -TERM $PPID' \
        "$WRITE_DIR/gradlew" "$opts" "$QA/client.log" "-Xms256M -Xmx$CLIENT_XMX" < /dev/null > "$QA/mutter.log" 2>&1 &
        echo $! > "$PIDS/client.pid")
      for _ in $(seq 1 120); do grep -q 'DevShots active' "$QA/client.log" 2>/dev/null && break; sleep 2; done
      grep -q 'DevShots active' "$QA/client.log" && echo "client starting (log $QA/client.log)" || { echo "client did not come up; tail:"; tail -20 "$QA/client.log"; exit 1; }
      ;;
    stop)
      [ -f "$PIDS/client.pid" ] && kill "$(cat "$PIDS/client.pid")" 2>/dev/null || true
      rm -f "$PIDS/client.pid"
      pkill -f "$WRITE_DIR/run/client" 2>/dev/null || true
      pkill -KILL -f "wayland-display agentcraft-qa7" 2>/dev/null || true   # only the compositor this script started
      echo "client stopped" ;;
    shot-dir) echo "$CLI" ;;
  esac
}

case "${1:-}" in
  prep) shift; prep "$@" ;;
  control) shift; control "$@" ;;
  adapter) shift; adapter "$@" ;;
  server) shift; server "$@" ;;
  client) shift; client "$@" ;;
  srvdir) echo "$SRV" ;;
  stop-all) client stop || true; server stop || true; adapter stop || true; control stop || true ;;
  *) sed -n '2,22p' "$0"; exit 2 ;;
esac
