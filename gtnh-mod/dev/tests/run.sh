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
  "$SRC"/edit/*.java "$SRC"/ops/*.java \
  dev/tests/StationAssignerCheck.java dev/tests/UiPureCheck.java dev/tests/PlateLayoutCheck.java dev/tests/EditCheck.java \
  dev/tests/OpsCheck.java
# card 5b: a real ops.* wire trace from the adapter (python3, ../hermes-adapter) for OpsCheck
python3 dev/tests/make_ops_trace.py "$OUT/ops-trace.jsonl" > /dev/null 2>&1
for c in StationAssignerCheck UiPureCheck PlateLayoutCheck EditCheck; do
  "${BIN}java" -ea -cp "$OUT" "$c"
done
"${BIN}java" -ea -cp "$OUT" OpsCheck "$OUT/ops-trace.jsonl"
# card 7: scan the built core jar for the write path's wire names when one exists (run ./gradlew assemble first)
have_jar=0
for j in build/libs/agentcraftgtnh-*.jar; do
  case "$j" in *-sources.jar) ;; *) [ -e "$j" ] && have_jar=1 ;; esac
done
if [ "$have_jar" -eq 1 ]; then dev/tests/core-jar-scan.sh; else echo "core-jar-scan: skipped (no core jar in build/libs yet)"; fi
