#!/usr/bin/env bash
# Plain-Java checks of the mod's pure parts (no Minecraft on the classpath; any JDK 8+).
# Usage (from gtnh-mod/): dev/tests/run.sh   (uses $JAVA_HOME/bin when set, else javac/java on PATH)
set -euo pipefail
BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
OUT=build/pure-checks
SRC=src/main/java/dev/agentcraft/gtnh
rm -rf "$OUT"
mkdir -p "$OUT"
"${BIN}javac" --release 8 -Xlint:-options -d "$OUT" \
  "$SRC/hq/Anchor.java" "$SRC/hq/StationAssigner.java" \
  "$SRC/ui/Theme.java" "$SRC/ui/TextLayout.java" "$SRC/ui/PlateDeclutter.java" \
  "$SRC"/edit/*.java \
  dev/tests/StationAssignerCheck.java dev/tests/UiPureCheck.java dev/tests/PlateLayoutCheck.java dev/tests/EditCheck.java
for c in StationAssignerCheck UiPureCheck PlateLayoutCheck EditCheck; do
  "${BIN}java" -ea -cp "$OUT" "$c"
done
