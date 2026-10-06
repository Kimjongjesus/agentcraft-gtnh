#!/usr/bin/env bash
# Upstream compatibility checks for the Hermes adapter (run on ai-ops):
#   1. starts the adapter on a loopback test port against the live (read-only) Hermes state,
#   2. validates every message against upstream's zod schema (foreman/src/protocol.ts),
#   3. connects upstream's own client, foreman/scripts/fake-mod.ts, and prints its view.
# Needs `npm ci` in foreman/ once (node_modules is gitignored). Writes nothing to Hermes.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
PORT="${PORT:-17878}"
SECONDS_TO_WATCH="${SECONDS_TO_WATCH:-8}"

[ -d "$REPO/foreman/node_modules" ] || { echo "run: (cd foreman && npm ci) first" >&2; exit 2; }
# let scripts/validate-upstream.ts resolve ws/zod from foreman's node_modules
[ -e "$HERE/node_modules" ] || ln -s ../foreman/node_modules "$HERE/node_modules"

cd "$HERE"
python3 -m hermes_adapter --port "$PORT" --poll 2 --log-level INFO &
ADAPTER=$!
trap 'kill $ADAPTER 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && break
  sleep 0.2
done

echo "== upstream schema validation =="
(cd "$REPO/foreman" && node --import tsx ../hermes-adapter/scripts/validate-upstream.ts --port "$PORT" --seconds "$SECONDS_TO_WATCH")

echo "== upstream fake-mod.ts --once =="
(cd "$REPO/foreman" && timeout 30 node --import tsx scripts/fake-mod.ts --port "$PORT" --once --no-color)
