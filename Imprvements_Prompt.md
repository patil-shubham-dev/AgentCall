# AgentCall — Improvement & Bug Fix Prompts

Generated from a full discovery pass (2026-07-31). Nothing below has been implemented yet.
Work through this **in priority order** — do not skip ahead or bundle unrelated items together.
Every item must remain fully free (\$0/month) — no paid APIs, no paid tiers, no exceptions.

Report back with real evidence (logcat, backend logs, live test results) after each numbered
section — do not mark anything done based on "should work."

---

## PRIORITY 0 — Regression: ringing fails in the most common real-world state

**This is the most important item in this document.** Live testing found that when the app
is backgrounded (not force-stopped, not swiped away — just sitting behind another app, which
is the normal everyday state), an incoming call does **not** ring or show the full-screen call
UI. The WebSocket receives `call_incoming`, the notification posts with a full-screen intent
attached, but the activity never launches — focus stays on whatever app the user was in. It
only works if the user force-stops and relaunches the app.

This is worse than "doesn't ring when fully closed," because it's the state the phone will be
in most of the time in real use.

**Investigate and fix:**
1. Confirm the exact cause: Android 14 background-activity-launch (BAL) restrictions,
   OEM-level full-screen-intent suppression, or something else. Check
   `NotificationManager.canUseFullScreenIntent()` at runtime in this exact state (not just at
   app startup) and log the result.
2. Check whether the existing foreground service (`SignalingForegroundService`) is in a
   state (e.g. `FOREGROUND_SERVICE` process importance, not `TOP`) that Android now treats as
   background for BAL purposes, even though the process is alive.
3. Propose and implement a fix appropriate for this exact state — this may require a
   different notification/activity-launch strategy than what's used for the fully-closed case.
4. Fix the secondary issue found alongside this: if the ring UI is destroyed without the user
   declining (e.g. recents-swipe during ring), the call stays "pending" server-side for up to
   30 minutes with an orphaned notification. Ensure this either re-rings appropriately or
   auto-declines cleanly, not silently orphaned.
5. Re-test live in all three states — force-stopped, backgrounded-but-alive, and swiped from
   recents — and report real evidence for each, not an assumption that the same fix covers all
   three.

---

## PRIORITY 1 — Fallback message plays before the real greeting

**Root cause of two separate complaints (why the AI called, and message ordering).**

On answer, the phone immediately speaks a generic filler line ("AI needs your input.") because
`call.result` is null while a call is pending/active — the real reason/summary already exists
on the server (it's a required field on `create_call`) but the mobile app has no way to show it
on the active-call screen; it only appears briefly during the ring.

**Fix:**
1. Carry the `reason`/`summary` from the incoming-call payload through to the active-call
   screen and `CallService`, so it's available immediately on answer — no network round-trip
   needed, it already arrived with the ring.
2. Remove the generic "AI needs your input." fallback entirely, or only use it in the genuine
   edge case where no reason/summary was ever provided at all.
3. Also fix the related backend detail: the server only flips a call from `pending` to
   `active` when the AI sends its first message — not when the phone answers. This means the
   phone can be in a live voice session while the server still reports the call as `pending`.
   Flip status to `active` on answer, not on first AI message.
4. Re-test: answer a call and confirm the correct reason/context is heard/shown immediately,
   with no generic filler line first.

---

## PRIORITY 2 — Custom messages on Decline / Call Back Later

When the user taps **Decline**, instead of silently cancelling, send a message to the AI
first, explaining the situation, so the AI can decide what to do next (keep working on
something else, try again if truly important, or stop).

When the user taps **Later**, send a message telling the AI to try again after a set delay,
and let it continue other work in the meantime — using the existing schedule-callback
mechanism, but attaching real context to it (currently the callback just re-rings with the
original reason, with no explanation of why it was postponed).

**Implement:**
1. Add a way to attach a short text message to both the decline (`/cancel`) and the later
   (`/callback`) actions, so it reaches the AI — either by sending it as a user message just
   before cancelling, or by adding a `note` field that gets delivered appropriately in each
   flow. Choose whichever fits the existing architecture more cleanly and explain the choice.
2. Default message text (used unless the user has customized it in Settings):
   - **Decline default:** "The user is currently busy and can't answer right now. If this is
     important, try calling again shortly. If not, continue working on any other task that
     doesn't need the user's input, and call again once that's done. If there's nothing else to
     do, stop here."
   - **Later default:** "The user wants you to call back in {X} minutes. Until then, continue
     working on any subtask that doesn't need input — don't try to finish the entire task, just
     make progress on what you can — and call back when the time is up."
3. Confirm the "Later" delay value the user picks (5/10/15/30/60 min, already supported) is
   correctly substituted into the message.
4. Test live: decline a call and confirm the AI actually receives the message before/at the
   same time the call ends. Same for Later.

---

## PRIORITY 3 — Editable message templates in Settings

Add a section to the existing Settings screen where the user can view and edit the Decline
and Later message templates from Priority 2, instead of them being hardcoded.

1. Store templates locally on the phone (Room or SharedPreferences — whichever fits the
   existing settings storage pattern already in use).
2. Add a simple text-editing UI in Settings for both templates, with a "reset to default"
   option.
3. Confirm edited templates are actually used the next time Decline/Later is tapped.

---

## PRIORITY 4 — Per-AI-client identity ("who is calling me")

Right now every AI client (Opencode, Claude, ChatGPT, Codex, any MCP-compatible tool) shares
one single credential and is completely indistinguishable to the phone. The goal: let the user
set up each AI tool with its own identity, so incoming calls show which AI is actually calling
(e.g. "Call from Opencode" vs. "Call from Claude Desktop"), using a method that works with any
standard MCP client setup — no custom code needed per AI tool.

**Implement:**
1. Add a simple "Add AI" flow in the mobile app: user taps a button, names the AI (e.g.
   "Opencode"), and the app/backend generates a new unique key for that AI.
2. The user pastes that generated key into their AI tool's normal MCP connection settings
   (works the same way regardless of which AI client it is — Opencode config, Claude Desktop
   config, ChatGPT connector settings, etc.).
3. Backend: support multiple named keys instead of one shared `SERVICE_TOKEN`/`MCP_API_KEY` —
   a simple key-hash-to-label mapping. Every call/message gets tagged with which key made it.
4. Phone: display the associated AI name/profile on incoming calls and in call history,
   reusing the existing profile display groundwork already in the app.
5. Keep this fully backward compatible — a single default key/profile should still work for
   users who don't bother setting up multiple AIs.
6. This must remain \$0/month — no paid identity/auth service, just a simple lookup table on
   the existing backend.

---

## PRIORITY 5 — Show AI availability status during a call

Currently, once a call is answered, there's no indication of whether the AI is actually
"there" listening, or whether it has stopped/finished its turn and nothing is currently
checking for replies. Add a simple status the user can see (e.g. "AI is thinking" vs. "AI is
not currently responding") so the user isn't confused when nothing happens after they reply.

**Note: this does NOT make an AI client run continuously — that's outside the app's control.**
This only adds visibility into whether something is currently listening, using signals the
backend can already observe (e.g. whether a `send_message_and_wait` poll is currently
in-flight for this call).

1. Add a lightweight status marker on the backend, updated whenever an AI-side poll/request
   is active for a call.
2. Surface this status to the phone (e.g. via the existing WebSocket connection) so the
   active-call screen can show something like "waiting for AI" vs. "AI not currently
   connected" instead of just sitting silently with no explanation.
3. Keep this simple — a boolean/timestamp-based liveness marker is enough, no need for
   anything elaborate.

---

## PRIORITY 6 — Faster / more natural back-and-forth (free improvements only)

True live, real-time voice conversation (interrupt-capable, like a live phone call) is **not
achievable for free** — it generally requires paid real-time voice infrastructure. Do not
attempt to build this. Instead, apply the following free improvements to make the existing
turn-based flow feel snappier:

1. Reduce the reply-polling interval from 2s to a shorter interval (e.g. 500ms–1s) — check
   this doesn't meaningfully increase backend load given the free-tier constraint.
2. Pre-warm the on-device TTS engine as soon as the call is answered, rather than waiting
   until the first message arrives, to cut the delay before the AI's voice starts.
3. Investigate enabling on-device partial speech recognition results (if supported by the
   current `SpeechRecognizer` setup) so the phone can start processing what the user is saying
   sooner, rather than waiting for the recognizer to fully finalize.
4. Do not introduce any paid speech/voice service to chase this — flag clearly if any further
   improvement would require one, rather than adopting it silently.

---

## Notes for whoever picks this up

- Everything above was scoped against a strict \$0/month constraint — no item requires a paid
  service. If implementation reveals that something can't be done for free, stop and flag it
  rather than substituting a paid alternative.
- Priority 0 and Priority 1 are bug fixes to existing, previously-"verified" behavior — treat
  them with the same live-evidence discipline as prior fixes in this project (real logcat/
  backend logs, not "should work now").
- Priorities 2–6 are new features — confirm scope with real questions before implementing if
  anything here is ambiguous, rather than guessing.