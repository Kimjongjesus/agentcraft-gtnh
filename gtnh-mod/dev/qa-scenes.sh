#!/usr/bin/env bash
# Card-2 QA scenes on the RFG dev server (dev/run-hq-qa.sh start ... already running, arena fed
# from dev/qa-arena.txt, adapter serving the demo_hq.py fixture). Drives the fixture phase by phase
# and takes a screenshot per state (run/client/screenshots/devshot-*.png). DEV/QA ONLY.
#
# Usage (from gtnh-mod/): dev/qa-scenes.sh <fixture dir> [adapter dir]
set -euo pipefail
FIX="${1:?fixture dir (the one given to demo_hq.py init)}"
ADAPTER="${2:-../hermes-adapter}"
Q=dev/run-hq-qa.sh

shot() { # shot <camera anchor> <name> [settle seconds]
  $Q cmd "agentcraft anchor tp $1 Developer"
  sleep "${3:-5}"
  $Q cmd "say devshot $2"
  sleep 4
}
phase() {
  echo "== $(date +%H:%M:%S) phase $1"
  python3 "$ADAPTER/scripts/demo_hq.py" "$FIX" "$1"
}
mark() { echo "== $(date +%H:%M:%S) $*"; }

mark "A: initial board (12 agents)"
shot cam_overview 01-overview
shot cam_desks 02-desks
shot cam_monitor 03-monitor
shot cam_user 04-waiting-marker
shot cam_lounge 05-lounge
shot cam_beacon 06-beacon-waiting
$Q cmd "agentcraft anchor show 40 Developer"
shot cam_overview 07-anchor-overlay
$Q cmd "agentcraft anchor show 0 Developer"
$Q cmd "agentcraft cap 9"
sleep 3
shot cam_user 08-overflow-sign
$Q cmd "agentcraft cap 12"

mark "B: new progress note -> monitor"
$Q cmd "agentcraft anchor tp cam_monitor Developer"
sleep 3
phase progress
sleep 3
$Q cmd "say devshot 09-monitor-updated-3s"
sleep 5

mark "C: Builder A blocks with DEMO READY -> walks to the user station"
$Q cmd "agentcraft anchor tp cam_overview Developer"
sleep 3
phase demo-ready
sleep 3
$Q cmd "say devshot 10-walking"
sleep 2
$Q cmd "say devshot 10b-walking"
sleep 6
shot cam_user 11-arrived-waiting 3

mark "D: terminal anchor set -> Scheduler walks there"
$Q cmd "agentcraft anchor set terminal -44.5 4 -224.5 north"
sleep 10
shot cam_lounge 12-terminal-set 3

mark "E: unblocked -> back to work, fleet working"
phase unblock
sleep 10
shot cam_beacon 13-beacon-working 3
shot cam_desks 14-desks-working 3

mark "F: a crash -> fleet error"
phase crash
sleep 8
shot cam_beacon 15-beacon-error 3

mark "G: all idle"
phase all-idle
sleep 8
shot cam_beacon 16-beacon-idle 3
shot cam_overview 17-overview-idle 3
mark "done"
