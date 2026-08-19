#!/bin/bash
# Test 2 runner (kill -9 while ringing): start the driver, detect CALL_CREATED,
# verify the ring, kill -9 the driver immediately, then poll the backend for the
# abort and the phone screen for the "AI disconnected" state. Records wall-clock.
set -u
MARK="C:/Users/91808/AppData/Local/Temp/e2e"
export PATH="$PATH:/c/Users/91808/AppData/Local/Android/Sdk/platform-tools"
mkdir -p "$MARK"
echo "T2_START $(date +%s%3N) $(date -u +%FT%TZ)"

rm -f "$MARK/t2-*.log" "$MARK/t2.killedAt" "$MARK/t2.called" "$MARK/t2.abortedAt" "$MARK/t2.ui"

cd /c/Users/91808/Desktop/AgentCall/backend || exit 1
nohup node scripts/e2e-driver.mjs ring-kill > "$MARK/t2-driver.log" 2>&1 &
# The bash job PID is NOT the node PID on Windows/MSYS; resolve the real one
# from the driver log (the driver prints process.pid) and wmic as a cross-check.
sleep 3
DRIVER=$(cat "$MARK/ring-kill.pid" 2>/dev/null | tr -d '\r')
if [ -z "$DRIVER" ]; then
  DRIVER=$(wmic process where "name='node.exe'" get ProcessId,CommandLine 2>/dev/null | grep 'e2e-driver.mjs ring-kill' | awk '{print $NF}' | head -1)
fi
echo "DRIVER_PID $DRIVER"

# Wait for CALL_CREATED (up to 30s)
for i in $(seq 1 60); do
  if grep -q CALL_CREATED "$MARK/t2-driver.log" 2>/dev/null; then break; fi
  sleep 0.5
done
CALL=$(grep -oE 'CALL_CREATED [0-9a-f-]+' "$MARK/t2-driver.log" | head -1 | awk '{print $2}')
echo "CALL $CALL at $(date +%s%3N)"
echo "$CALL" > "$MARK/t2.called"

# Verify the ring is up (IncomingCallActivity focused) — quick single check
sleep 2
FOCUS=$(adb shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1)
echo "FOCUS_AT_RING $FOCUS"
echo "$FOCUS" > "$MARK/t2.ui"

# Kill -9 the driver NOW (the abort must land before the phone's 60s ring timeout)
KILL_AT=$(date +%s%3N)
taskkill //F //PID "$DRIVER" 2>&1 | head -1
echo "KILL_AT $KILL_AT $(date -u +%FT%TZ)" 
echo "$KILL_AT" > "$MARK/t2.killedAt"

# Poll backend status every 2s for up to 90s; record when it flips to aborted
TOKEN=$(curl -s -X POST http://127.0.0.1:4000/api/v1/phone/token -H 'Content-Type: application/json' -d '{"user_id":"solo-user"}' | grep -oE '"token":"[^"]*"' | cut -d'"' -f4)
LAST=""
for i in $(seq 1 45); do
  sleep 2
  ST=$(curl -s "http://127.0.0.1:4000/api/v1/calls/$CALL" -H "Authorization: Bearer $TOKEN" 2>/dev/null | grep -oE '"status":"[^"]*"' | cut -d'"' -f4)
  if [ "$ST" != "$LAST" ]; then
    echo "STATUS $LAST -> $ST at $(date +%s%3N) ($(date -u +%FT%TZ))"
    LAST=$ST
    if [ "$ST" = "aborted" ]; then
      echo "ABORTED_AT $(date +%s%3N)" > "$MARK/t2.abortedAt"
      break
    fi
  fi
  # Also poll the phone screen every 4th tick for the AI-disconnected UI
  if [ $((i % 2)) -eq 0 ]; then
    adb shell uiautomator dump /sdcard/t2ui.xml >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 adb pull /sdcard/t2ui.xml "$MARK/t2-ui.xml" >/dev/null 2>&1
    if grep -q "AI disconnected" "$MARK/t2-ui.xml" 2>/dev/null; then
      echo "PHONE_AI_DISCONNECTED_VISIBLE at $(date +%s%3N) ($(date -u +%FT%TZ))"
      cp "$MARK/t2-ui.xml" "$MARK/t2-disconnected-ui.xml" 2>/dev/null
      break
    fi
  fi
done
echo "T2_DONE $(date +%s%3N)"
