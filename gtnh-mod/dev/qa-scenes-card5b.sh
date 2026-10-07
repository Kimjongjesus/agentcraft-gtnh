#!/usr/bin/env bash
# Card-5b QA scenes on the RFG dev server (dev/run-hq-qa.sh start ... running with a dev client,
# arena fed from dev/qa-arena-card5b.txt, adapter serving the demo_hq.py fixture WITH --ops-mock).
# Screenshots land in run/client/screenshots/devshot-*.png. DEV/QA ONLY, generic data only.
#
# Usage (from gtnh-mod/): dev/qa-scenes-card5b.sh <fixture dir> [adapter dir]
set -euo pipefail
FIX="${1:?fixture dir (the one given to demo_hq.py init)}"
ADAPTER="${2:-../hermes-adapter}"
Q=dev/run-hq-qa.sh

shot() { # shot <camera anchor> <name> [settle seconds]
  $Q cmd "agentcraft anchor tp $1 Developer"
  sleep "${3:-4}"
  $Q cmd "say devshot $2"
  sleep 4
}
gui() { # gui <kind> <arg|-> <select> <name>
  $Q cmd "say devgui $1 $2 $3"
  sleep 3
  $Q cmd "say devshot $4"
  sleep 4
  $Q cmd "say devgui close"
  sleep 1
}
mark() { echo "== $(date +%H:%M:%S) $*"; }

mark "A: the ops wall at three distances (level of detail)"
$Q cmd "say devhud off"
shot cam_ops_far 01-ops-wall-far 6
shot cam_ops_mid 02-ops-wall-mid
shot cam_ops_fleet 03-fleet-near
shot cam_ops_cron 04-cron-near
shot cam_ops_usage 05-usage-near
shot cam_ops_alerts 06-alerts-near

mark "B: fleet beacon = worst of (agents, ops): everyone answered, a mock service is down -> red"
python3 "$ADAPTER/scripts/demo_hq.py" "$FIX" answer-all
sleep 6
$Q cmd "agentcraft ops"
shot cam_ops_beacon 07-beacon-worst-of 4

mark "C: the edit tool handles the ops kinds (palette, inspector, rebind + resize, duplicate)"
gui editor palette - 08-palette-ops-kinds
gui editor palette scroll:end 08b-palette-ops-kinds-end
gui inspector -66,4,-252 - 09-inspector-fleet-board
$Q cmd "say devact panel.set pos=-66,4,-252 binding=hosts w=4 h=3"
sleep 3
$Q cmd "say devact panel.set pos=-50,4,-252 binding=critical-only"
sleep 2
$Q cmd "say devact panel.set pos=-50,4,-252 binding=all"
sleep 2
shot cam_ops_fleet 10-fleet-rebound-hosts
$Q cmd "agentcraft edit history"

mark "D: decision toast: a NEW decision arrives (demo-ready) -> HUD toast + sound"
$Q cmd "say devhud on"
$Q cmd "agentcraft anchor tp cam_ops_mid Developer"
sleep 3
python3 "$ADAPTER/scripts/demo_hq.py" "$FIX" demo-ready
sleep 5
$Q cmd "say devshot 11-toast-new-decision"
sleep 5
gui decisions - - 12-decision-screen
mark "E: toast status / test from the console (the player form is /agentcraft toast ...)"
$Q cmd "agentcraft toast status Developer"
sleep 2
$Q cmd "say devshot 13-toast-status-chat"
sleep 4
$Q cmd "agentcraft toast test Developer"
sleep 2
$Q cmd "say devshot 14-toast-test"
sleep 5
$Q cmd "agentcraft toast mute Developer"
sleep 2
$Q cmd "agentcraft toast status Developer"
sleep 2
$Q cmd "say devshot 15-toast-muted-status"
sleep 4
$Q cmd "agentcraft toast unmute Developer"
sleep 2
$Q cmd "say devhud off"
mark "done"
