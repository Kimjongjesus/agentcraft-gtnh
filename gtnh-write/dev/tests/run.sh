#!/usr/bin/env bash
# Plain-Java checks of the write module's pure parts (no Minecraft on the classpath; any JDK 8+).
# Usage (from gtnh-write/): dev/tests/run.sh   (uses $JAVA_HOME/bin when set, else javac/java on PATH)
set -euo pipefail
BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
OUT=build/pure-checks
SRC=src/main/java/dev/agentcraft/gtnh/write
rm -rf "$OUT"
mkdir -p "$OUT"
"${BIN}javac" --release 8 -Xlint:-options -d "$OUT" \
  "$SRC"/proto/*.java "$SRC"/core/*.java "$SRC"/mc/ControlLink.java ../gtnh-mod/src/main/java/dev/agentcraft/gtnh/bridge/WebSocketClient.java "$SRC"/client/ClientWriteState.java "$SRC"/client/DecisionKind.java "$SRC"/client/FormLogic.java \
  dev/tests/Check.java dev/tests/ProtoCheck.java dev/tests/GateCheck.java dev/tests/ControllerCheck.java dev/tests/LockAuditCheck.java dev/tests/ClientCheck.java dev/tests/HardeningCheck.java
for c in ProtoCheck GateCheck ControllerCheck LockAuditCheck ClientCheck HardeningCheck; do
  "${BIN}java" -ea -cp "$OUT" "$c"
done
