#!/bin/bash
# Test 3 runner (kill -9 mid-call): create call, answer on the phone, kill -9
# the driver while active, then watch for the abort + "AI disconnected" UI.
set -u
MARK="C:/Users/91808/AppData/Local/Temp/e2e"
export PATH="$PATH:/c/Users/91808/AppData/Local/Android/Sdk/platform-tools"
mkdir -p "$MARK"
echo "T3_START $(date +%s%3N) $(date -u +%FT%TZ)"

rm -f "$MARK/t3-*.log" "$MARK/midcall-kill.pid" "$MARK/t3.called" "$MARK/t3.killedAt" "$MARK/t3.abortedAt" "$MARK/t3.ui"

cd /c/Users/91808/Desktop/AgentCall/backend || exit 1
nohup node scripts/e2e-driver.mjs midcall-kill > "$MARK/t3-driver.log" 2>&1 &
sleep 3
DRIVER=$(cat "$MARK/midcall-kill.pid" 2>/dev/null | tr -d '\r')
if [ -z "$DRIVER" ]; then
  DRIVER=$(wmic process where "name='node.exe'" get ProcessId,CommandLine 2>/dev/null | grep 'e2e-driver.mjs midcall-kill' | awk '{print $NF}' | head -1)
fi
echo "DRIVER_PID $DRIVER"

# Wait for the ring, then tap Answer (full-screen button ~[438,1867])
for i in $(seq 1 60); do
  if grep -q CALL_CREATED "$MARK/t3-driver.log" 2>/dev/null; then break; fi
  sleep 0.5
done
CALL=$(grep -oE 'CALL_CREATED [0-9a-f-]+' "$MARK/t3-driver.log" | head -1 | awk '{print $2}')
echo "CALL $CALL at $(date +%s%3N)"
echo "$CALL" > "$MARK/t3.called"

# Wait for IncomingCallActivity to appear (up to 15s), then answer
ANSWERED=""
for i in $(seq 1 30); do
  sleep 0.5
  FOCUS=$(adb shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1)
  if echo "$FOCUS" | grep -q IncomingCallActivity; then
    sleep 0.7
    adb shell input tap 438 1867 2>&1
    sleep 2
    FOCUS2=$(adb shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1)
    echo "AFTER_ANSWER_FOCUS $FOCUS2"
    ANSWERED=1
    break
  fi
done
if [ -z "$ANSWERED" ]; then echo "NO_RING_RENDERED"; fi

# Verify the call went active, then kill -9 the driver mid-call
TOKEN=$(curl -s -X POST http://127.0.0.1:4000/api/v1/phone/token -H 'Content-Type: application/json' -d '{"user_id":"solo-user"}' | grep -oE '"token":"[^"]*"' | cut -d'"' -f4)
for i in $(seq 1 15); do
  sleep 1
  ST=$(curl -s "http://127.0.0.1:4000/api/v1/calls/$CALL" -H "Authorization: Bearer $TOKEN" 2>/dev/null | grep -oE '"status":"[^"]*"' | cut -d'"' -f4)
  if [ "$ST" = "active" ]; then echo "CALL_ACTIVE at $(date +%s%3N) ($(date -u +%FT%TZ))"; break; fi
done

KILL_AT=$(date +%s%3N)
taskkill //F //PID "$DRIVER" 2>&1 | head -1
echo "KILL_AT $KILL_AT $(date -u +%FT%TZ)"
echo "$KILL_AT" > "$MARK/t3.killedAt"

# Poll status + phone UI for the abort / AI-disconnected
LAST=""
for i in $(seq 1 40); do
  sleep 2
  ST=$(curl -s "http://127.0.0.1:4000/api/v1/calls/$CALL" -H "Authorization: Bearer $TOKEN" 2>/dev/null | grep -oE '"status":"[^"]*"' | cut -d'"' -f4)
  if [ "$ST" != "$LAST" ]; then
    echo "STATUS $LAST -> $ST at $(date +%s%3N) ($(date -u +%FT%TZ)) elapsed_from_kill=$(( $(date +%s%3N) - KILL_AT ))ms"
    LAST=$ST
    if [ "$ST" = "aborted" ]; then echo "$(date +%s%3N)" > "$MARK/t3.abortedAt"; fi
  fi
  if [ $((i % 2)) -eq 0 ]; then
    MSYS_NO_PATHCONV=1 adb shell uiautomator dump /sdcard/t3ui.xml >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 adb pull /sdcard/t3ui.xml "$MARK/t3-ui.xml" >/dev/null 2>&1
    if grep -q "AI disconnected" "$MARK/t3-ui.xml" 2>/dev/null; then
      echo "PHONE_AI_DISCONNECTED_VISIBLE at $(date +%s%3N) ($(date -u +%FT%TZ))"
      cp "$MARK/t3-ui.xml" "$MARK/t3-disconnected-ui.xml" 2>/dev/null
      break
    fi
  fi
done
echo "T3_DONE $(date +%s%3N)"
