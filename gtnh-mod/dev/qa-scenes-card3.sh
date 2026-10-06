#!/usr/bin/env bash
# Card-3 QA scenes on the RFG dev server (dev/run-hq-qa.sh start ... running with a dev client,
# arena fed from dev/qa-arena.txt then dev/qa-arena-card3.txt, adapter serving the demo_hq.py
# fixture). Screenshots land in run/client/screenshots/devshot-*.png. DEV/QA ONLY.
#
# Usage (from gtnh-mod/): dev/qa-scenes-card3.sh <fixture dir> [adapter dir]
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
gui() { # gui <taskwall|library> <binding|-> <select> <name>
  $Q cmd "say devgui $1 $2 $3"
  sleep 3
  $Q cmd "say devshot $4"
  sleep 4
  $Q cmd "say devgui close"
  sleep 1
}
mark() { echo "== $(date +%H:%M:%S) $*"; }

mark "A: initial board: wall, atriums, library label, screens"
shot cam_wall 20-taskwall
shot cam_atrium 21-atrium
shot cam_library 22-library-block
gui taskwall - t_game 23-taskwall-screen
gui taskwall homelab t_music 24-taskwall-blocked-detail
gui library - plan-t_inv 25-library-plan
gui library - handoff-t_old 26-library-handoff
shot cam_monitor 27-monitor-still-working
shot cam_beacon 28-beacon-summary

mark "B: board changes -> wall + atrium follow within seconds"
$Q cmd "agentcraft anchor tp cam_wall Developer"
sleep 3
python3 "$ADAPTER/scripts/demo_hq.py" "$FIX" wall-move
sleep 6
$Q cmd "say devshot 29-taskwall-after-move-6s"
sleep 5
shot cam_atrium 30-atrium-after-move 2
gui library - plan-t_guide 31-library-new-plan
gui library - q=game 32-library-search
shot cam_beacon_near 33-beacon-label
$Q cmd "agentcraft board"
mark "done"
