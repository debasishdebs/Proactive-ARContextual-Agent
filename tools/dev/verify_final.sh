#!/bin/bash
# Full 15-task verification, sequential, one Gradle job at a time. Result file: $OUT (start line has the git sha; DONE line at the end).
R=${R:-$HOME/Desktop/rokid/proactive-context-agent}
W=${W:-/tmp/pca-verify}; mkdir -p "$W"; OUT=$W/verify-final.txt
echo "start $(date +%H:%M) git $(git -C $R rev-parse --short HEAD)" > $OUT
run() { local name=$(echo "$*" | tr ' :' '__' | cut -c1-60); bash $R/tools/dev/run_gradle_locked.sh "$R" "$W/vf_$name.log" "$@"; echo "$(date +%H:%M) $* rc=$(grep '^rc=' "$W/vf_$name.log" | tail -1 | cut -d= -f2)" >> $OUT; }
run :core:model:test; run :core:hud:test; run :core:capture:test; run :core:ui:testDebugUnitTest
run :services:ai:test; run :services:memory:test; run :services:connectors:test; run :services:glasses-link:test
run :services:voice:test; run :services:orchestration:test; run :nexus-port:test
run :nexus-bridge:testDebugUnitTest; run :glasses:testDebugUnitTest; run :phone:testDebugUnitTest
run :phone:assembleDebug :glasses:assembleDebug :nexus-bridge:assembleDebug
echo VERIFY-FINAL-DONE >> $OUT
# Tip: after merging branches run the module :clean tasks first ('Unresolved reference' for symbols that exist = stale cache).
