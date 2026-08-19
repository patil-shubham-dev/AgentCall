#!/bin/bash
# Test 5-unplugged orchestrator: waits 60 min idle (phone unplugged, on battery),
# then triggers a call via the deployed Render backend and polls the record
# remotely (no adb). Writes timestamps + driver log to the t5u marks dir.
MARK_DIR="C:/Users/91808/AppData/Local/Temp/e2e/t5u"
mkdir -p "$MARK_DIR"
echo "T0 $(date +%s%3N) $(date -u +%FT%TZ)" > "$MARK_DIR/timeline.txt"
# 60-minute idle window (phone must sit untouched, screen off, on battery)
sleep 3600
echo "TRIGGER $(date +%s%3N) $(date -u +%FT%TZ)" >> "$MARK_DIR/timeline.txt"
cd "$(dirname "$0")/.." || exit 1
node scripts/test5-render-ring.mjs >> "$MARK_DIR/driver.log" 2>&1
echo "DONE $(date +%s%3N) $(date -u +%FT%TZ)" >> "$MARK_DIR/timeline.txt"
