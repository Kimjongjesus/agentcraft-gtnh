#!/usr/bin/env bash
# Core-jar scan (card 7): fails if any class of the built core jar contains "action.", "acwrite" or
# "hermes_control" (the write path lives only in the separate gtnh-write jar).
# Usage (from gtnh-mod/, after ./gradlew assemble): dev/tests/core-jar-scan.sh [jar...]
#   default jars: build/libs/agentcraftgtnh-*.jar except -sources (so the reobf jar AND the -dev jar are scanned)
set -euo pipefail
BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
OUT=build/pure-checks
mkdir -p "$OUT"
"${BIN}javac" --release 8 -Xlint:-options -d "$OUT" dev/tests/CoreJarScan.java
if [ "$#" -eq 0 ]; then
  set --
  for j in build/libs/agentcraftgtnh-*.jar; do
    case "$j" in *-sources.jar) continue ;; esac
    set -- "$@" "$j"
  done
fi
if [ "$#" -eq 0 ] || [ ! -e "$1" ]; then
  echo "core-jar-scan: no core jar in build/libs (run ./gradlew assemble first)" >&2
  exit 2
fi
"${BIN}java" -cp "$OUT" CoreJarScan "$@"
