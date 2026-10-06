#!/usr/bin/env bash
# Cleanly stop the dev-copy GTNH server started by run-dev-server.sh ("stop" on the console,
# which saves the world), waiting up to GTNH_DEV_STOP_WAIT seconds. Never kill -9s the server.
set -euo pipefail

SERVER_DIR="${GTNH_DEV_SERVER:-$HOME/gtnh-dev/server}"
WAIT="${GTNH_DEV_STOP_WAIT:-180}"
cd "$SERVER_DIR"

if [ ! -f dev-server.pid ] || ! kill -0 "$(cat dev-server.pid)" 2>/dev/null; then
  echo "dev server not running"
else
  pid="$(cat dev-server.pid)"
  echo "stop" > console.fifo
  for _ in $(seq 1 "$WAIT"); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "server pid $pid still running after ${WAIT}s; NOT force-killing (check dev-console.log)" >&2
    exit 1
  fi
  echo "dev server pid $pid exited"
fi

if [ -f console-holder.pid ]; then
  kill "$(cat console-holder.pid)" 2>/dev/null || true
  rm -f console-holder.pid
fi
rm -f dev-server.pid console.fifo
