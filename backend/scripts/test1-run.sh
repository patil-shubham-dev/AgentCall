#!/usr/bin/env bash
# Test 1 — normal call lifecycle. Drives the phone UI fast enough to beat the
# 60s ring window. Requires: adb on PATH, local backend on 127.0.0.1:4000,
# adb reverse tcp:4000 set, app pointed at 127.0.0.1.
set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
MARK="C:/Users/91808/AppData/Local/Temp/e2e"
rm -rf "$MARK"
mkdir -p "$MARK"

echo "[T1] starting driver (normal phase)..."
cd "$(dirname "$0")/.."
node scripts/e2e-driver.mjs normal > /tmp/e2e-normal.log 2>&1 &
DRIVER_PID=$!

# Wait for the call id
CALL_ID=""
for i in $(seq 1 40); do
  if [ -f "$MARK/normal.call" ]; then CALL_ID=$(cat "$MARK/normal.call"); break; fi
  sleep 0.5
done
if [ -z "$CALL_ID" ]; then echo "[T1] FAIL: no call created"; cat /tmp/e2e-normal.log; kill -9 $DRIVER_PID 2>/dev/null; exit 1; fi
CREATED_AT=$(cat "$MARK/normal.createdAt")
echo "[T1] call=$CALL_ID created_at_ms=$CREATED_AT"

# Poll for the incoming-call UI to come to focus
RING_FOCUSED=0
for i in $(seq 1 60); do
  F=$("$ADB" shell dumpsys window 2>/dev/null | grep -oE "IncomingCallActivity" | head -1)
  if [ "$F" = "IncomingCallActivity" ]; then RING_FOCUSED=1; break; fi
  sleep 0.5
done
if [ "$RING_FOCUSED" != "1" ]; then
  echo "[T1] FAIL: incoming-call UI never focused"
  "$ADB" shell dumpsys window | grep -i mCurrentFocus
  kill -9 $DRIVER_PID 2>/dev/null
  exit 1
fi
RING_AT=$(date +%s%3N)
echo "[T1] ring UI focused at ms=$RING_AT (latency vs create: $((RING_AT - CREATED_AT)) ms)"

# Dump once and find the Answer button bounds
"$ADB" shell uiautomator dump /sdcard/t1.xml >/dev/null 2>&1
"$ADB" pull /sdcard/t1.xml "$MARK/t1.xml" >/dev/null 2>&1
ANSWER_LINE=$(grep -oE '<node[^>]*content-desc="Answer"[^>]*>' "$MARK/t1.xml" | head -1)
BOUNDS=$(echo "$ANSWER_LINE" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
if [ -z "$BOUNDS" ]; then
  # Fall back to the known action-row position from the banner layout
  echo "[T1] WARN: Answer node not found in dump; using fallback coords"
  ANSWER_X=438; ANSWER_Y=1867
else
  COORDS=$(echo "$BOUNDS" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')
  read X1 Y1 X2 Y2 <<< "$COORDS"
  ANSWER_X=$(( (X1 + X2) / 2 ))
  ANSWER_Y=$(( (Y1 + Y2) / 2 ))
fi
echo "[T1] tapping Answer at $ANSWER_X,$ANSWER_Y"
"$ADB" shell input tap "$ANSWER_X" "$ANSWER_Y"
sleep 2
F=$("$ADB" shell dumpsys window 2>/dev/null | grep -oE "CallActivity|IncomingCallActivity" | head -1)
echo "[T1] after answer, focused=$F"
echo "$RING_AT" > "$MARK/t1.ringMs"
echo "$ANSWER_X $ANSWER_Y" > "$MARK/t1.answerBtn"

# Signal the driver: phone answered
touch "$MARK/normal.answer"

# Wait for the driver to send message 1 and for the user to reply
# (we auto-tap the first quick-reply chip when the call is active)
sleep 6
echo "[T1] waiting for active-call screen..."
for i in $(seq 1 20); do
  F=$("$ADB" shell dumpsys window 2>/dev/null | grep -oE "CallActivity" | head -1)
  if [ "$F" = "CallActivity" ]; then break; fi
  sleep 0.5
done
echo "[T1] current focus: $("$ADB" shell dumpsys window 2>/dev/null | grep -i mCurrentFocus | head -1)"

# Send user replies via the text input + Send button (quick-reply chips
# disappear after the first pick, so the second turn needs the text field).
send_text_reply() {
  "$ADB" shell uiautomator dump /sdcard/t1x.xml >/dev/null 2>&1
  "$ADB" pull /sdcard/t1x.xml "$MARK/t1x.xml" >/dev/null 2>&1
  # Text input field: EditText with a hint containing 'Message' or similar
  INPUT_LINE=$(grep -oE '<node[^>]*class="android.widget.EditText"[^>]*>' "$MARK/t1x.xml" | head -1)
  B=$(echo "$INPUT_LINE" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
  if [ -n "$B" ]; then
    C=$(echo "$B" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')
    read X1 Y1 X2 Y2 <<< "$C"
    CX=$(( (X1 + X2) / 2 )); CY=$(( (Y1 + Y2) / 2 ))
    echo "[T1] tapping text input at $CX,$CY"
    "$ADB" shell input tap "$CX" "$CY"
    sleep 1
    "$ADB" shell input text "Yes"
    sleep 1
    # Send button (content-desc "Send")
    SEND_LINE=$(grep -oE '<node[^>]*content-desc="Send"[^>]*>' "$MARK/t1x.xml" | head -1)
    SB=$(echo "$SEND_LINE" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
    if [ -n "$SB" ]; then
      SC=$(echo "$SB" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')
      read SX1 SY1 SX2 SY2 <<< "$SC"
      SX=$(( (SX1 + SX2) / 2 )); SY=$(( (SY1 + SY2) / 2 ))
      echo "[T1] tapping Send at $SX,$SY"
      "$ADB" shell input tap "$SX" "$SY"
    else
      echo "[T1] WARN: no Send button; pressing Enter"
      "$ADB" shell input keyevent 66
    fi
  else
    echo "[T1] WARN: no text input found"
  fi
}

# First reply
send_text_reply

# Wait for the driver to log the FIRST reply, then give it a moment to
# send message 2 (TTS), then send the second reply. Do NOT wait for
# SECOND_REPLY_AT before sending — that log line only appears once the
# reply is received, i.e. after the send (deadlock -> 60s driver timeout).
for i in $(seq 1 60); do
  if grep -q "FIRST_REPLY_AT" /tmp/e2e-normal.log 2>/dev/null; then break; fi
  sleep 0.5
done
sleep 8
# Second reply
send_text_reply

# Signal complete after both turns
touch "$MARK/normal.complete"
echo "[T1] signaled complete; waiting for driver..."
for i in $(seq 1 40); do
  if grep -q "COMPLETED_AT" /tmp/e2e-normal.log 2>/dev/null; then break; fi
  sleep 0.5
done
sleep 2
echo "[T1] DRIVER LOG:"
cat /tmp/e2e-normal.log
echo "[T1] DONE"
