#!/usr/bin/env bash
# One-time settings for the DEV COPY only (test PC, $GTNH_DEV_SERVER):
# offline mode, port 25570 (the real server uses 25569), a DEV COPY motd, and loopback-only
# binding so an offline-mode server is not reachable from the LAN by default.
# Keeps the original file as server.properties.orig the first time it runs.
set -euo pipefail

DEV_ROOT="${AGENTCRAFT_DEV_ROOT:-$HOME/agentcraft-dev}"   # dev copy + portable JDK live here
SERVER_DIR="${GTNH_DEV_SERVER:-$DEV_ROOT/server}"
BIND="${GTNH_DEV_BIND:-127.0.0.1}"
cd "$SERVER_DIR"

case "$SERVER_DIR" in
  */*-dev/*) ;;
  *) echo "refusing: $SERVER_DIR is not under a *-dev directory (AGENTCRAFT_DEV_ROOT)" >&2; exit 1 ;;
esac

[ -f server.properties.orig ] || cp -p server.properties server.properties.orig

sed -i \
  -e 's/^online-mode=.*/online-mode=false/' \
  -e 's/^server-port=.*/server-port=25570/' \
  -e 's/^motd=.*/motd=GTNH DEV COPY - AgentCraft test (not the real world)/' \
  -e "s/^server-ip=.*/server-ip=$BIND/" \
  server.properties

grep -E '^(online-mode|server-port|server-ip|motd)=' server.properties
