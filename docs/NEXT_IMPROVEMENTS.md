# AgentCall — Next Improvements

Generated from a full ground-truth audit (2026-08-02). The core call loop (create → ring →
answer → converse → complete, including SSE push and per-AI identity) is verified working
end-to-end. Everything below is real, evidence-backed, prioritized work — not a wishlist.

Work through this **in priority order** (Batch 1 → Batch 2 → Batch 3 → Cleanup). Do not skip
ahead or bundle unrelated items into one commit. Report back with real evidence (live test
results, logs, before/after behavior) after each item — "should work" is not acceptable at any
point in this project; verify everything live before marking it done.

Everything must remain \$0/month. No paid services, ever, for any item below.

---

## BATCH 1 — Cheap, high daily-impact fixes

These directly hurt the product every time it's used. Fix these first.

### 1. The ENTER-key send trap
The text input field is multi-line (`singleLine=false`), so pressing ENTER inserts a newline
instead of sending the message. The send button is a small icon that visually jumps ~430px
when the keyboard opens, making it easy to miss. This has already cost real testing time
(multiple wasted 45-second exchanges during live verification).

**Fix:** Either make ENTER send the message (standard chat-app behavior), or make the send
action unambiguous and stable regardless of keyboard state (e.g. an `imeAction = Send` on the
keyboard itself, in addition to the button). Verify live: type a message, press ENTER, confirm
it sends rather than inserting a newline.

### 2. Unrendered `options` from `create_call`
The AI can pass quick-reply `options` when creating a call. The phone actually fetches this
data into view state (`CallViewModel.options`) but never renders it — the human always has to
type or record a full answer even when the AI offered a small set of choices. This is a real,
half-built feature, not a missing one.

**Fix — pick one:**
- (a) Render `options` as tappable chips/buttons on the active-call screen. Tapping one sends
  that option's text as the reply through the normal message path. This is likely the
  shortest path since the data already arrives correctly.
- (b) If chips aren't worth the effort right now, stop the AI-facing tool schema from
  encouraging `options` usage (or clearly document that they're not currently rendered), so
  nobody relies on a feature that silently does nothing.

Confirm with the project owner which direction to take if effort estimates differ
significantly, otherwise default to (a).

### 3. Silent voice-reply loss (no retry, no user feedback)
Every other network action in this app (answer, complete, cancel) uses a persisted
retry-with-backoff pattern so a message can never be silently dropped. The voice-reply path
(on-device STT → `POST /calls/:id/user-text`) does **not** — it's fire-and-forget
(`CallService.kt` STT result handler). A flaky mobile network moment loses the user's spoken
reply with zero indication to the user that anything went wrong.

**Fix:** Apply the same persisted retry-with-backoff pattern already used for answer/complete/
cancel to the voice-reply POST. If a reply fails to send after retries are exhausted, surface
this clearly in the UI (e.g. a visible "message not sent, tap to retry" state) rather than
failing silently. Also apply the same treatment to the text-reply send path if it doesn't
already have it — confirm which paths currently lack retry coverage before assuming only voice
is affected.

Verify live: simulate a network drop mid-send (e.g. airplane mode toggle during a voice reply),
confirm the message either recovers via retry or the user is clearly told it didn't send.

---

## BATCH 2 — Real gaps, moderate effort, fix soon

These aren't urgent emergencies but are compounding issues worth closing before they become
harder to unwind.

### 4. Per-call authorization (ownership check)
Any authenticated AI identity can currently read the transcript of, or complete/cancel, *any*
call — including calls created by a different AI identity. This was harmless when there was
only one shared credential; it became a real gap the moment per-AI-client identity (named
keys) was introduced, because the security model didn't catch up with the new feature.

**Fix:** Add a basic ownership check — a call's `agentId` (the identity that created it) must
match the authenticated identity making the request, for `get_transcript`, `complete_call`,
`cancel_call`, and any other per-call operation. Decide and document the behavior for
cross-agent access attempts (clean 403, not a silent no-op or crash). Confirm this doesn't
break the existing single-default-key backward-compatibility path — test explicitly with the
default key alongside at least one named key.

### 5. MCP session leak (unbounded growth)
MCP sessions never expire server-side unless the client sends an explicit `DELETE`. In
practice, most disconnects are *not* clean (network drops, a connector app closing, an AI
client timing out) — these leave the session in memory forever, growing unbounded until the
next process restart.

**Fix:** Add an idle-expiry mechanism for MCP sessions (e.g. a periodic sweep, similar in
spirit to the existing stale-call sweeper, that closes/removes sessions with no activity past
a reasonable threshold). Verify with a live test: open a session, abandon it without a clean
disconnect, confirm it's cleaned up after the threshold rather than persisting indefinitely.

### 6. Misleading "Online" availability status
"Online" currently means "this AI key was used to authenticate within the last 5 minutes." An
AI client that's connected and idle (not actively polling/authenticating) shows as Offline,
even though it may genuinely be available. This undermines the actual point of the
availability feature — a user checking the dashboard will often see their actually-connected
AI listed as Offline.

**Fix:** Investigate what a more accurate liveness signal would be — e.g., an open/active MCP
session rather than recent authentication — and adjust the online/busy/offline logic
accordingly. If a perfect signal isn't available for free, at minimum widen the window or
clearly label what "Online" actually measures so it's not misleading. Report your proposed
approach before implementing, since this affects UX semantics, not just code.

---

## BATCH 3 — Worth doing, not urgent

### 7. Unauthenticated + unlimited `/phone/token` minting
`POST /phone/token` has no dedicated rate limit (only the global limiter applies), and combined
with item 8 below, is the weakest link in the current auth chain. Low real-world risk at
current single-user scale, but cheap to harden.

**Fix:** Add a specific, tighter rate limit to this endpoint beyond the global one. Don't add
full authentication here (it's meant to be the entry point for a new phone) — just constrain
abuse potential.

### 8. Dev-mode auth bypass footgun
If `SERVICE_TOKEN=dev-service-token` is ever set in production by accident, it silently grants
full service-role access and skips WebSocket auth entirely. This is currently harmless because
the deployed instance correctly does not use this value — but it's a landmine for any future
deploy/config mistake.

**Fix:** Add a hard startup guard — if this exact dev-token value is detected while running in
a production-like environment (e.g. `NODE_ENV=production`), refuse to start and log a loud,
explicit error, rather than silently running in an insecure mode.

### 9. `MCP_API_SPEC.md` is actively wrong
This 550-line document describes a stdio transport with a different entrypoint and different
tool names than what actually exists today (the real system is 6 tools over Streamable HTTP,
embedded in the backend). This is the single most misleading document in the repo and risks
misleading any future work — including future AI-assisted sessions — that treats it as ground
truth.

**Fix:** Either fully rewrite this file to match current reality, or delete it and replace any
references to it with a short pointer to the actual current tool/transport documentation.
Confirm nothing else in the repo links to it expecting the old content.

---

## CLEANUP — Low priority, do opportunistically

- Remove dead code: unused `PersistenceBurnIn` export, unused `STT_ENABLED`/`STT_MODEL` env
  vars (the backend does no STT — it's on-device only), `load-test.ts` (not wired into the
  actual test runner).
- Continue the stale-docs cleanup started earlier — a large number of root-level markdown
  files predate the most recent work and describe outdated states of the project.
- Expand automated test coverage for the newer, currently manual-verification-only paths:
  SSE watcher timeout/re-dispose behavior, multi-waiter races, mid-wait terminal transitions,
  MCP session expiry, cross-agent ownership checks (once item 4 lands), and the retry logic
  added in item 3.
- Known verification gap (item 6, flagged 2026-08-04): the `ai_wait_status` WS push leg was
  never runtime-observed on the test device — only the status-polling fallback was proven live.
  Not urgent (the fallback covers the user-visible behavior), but worth confirming the push
  path whenever a device/setup exists that can actually exercise it.

---

## Working notes

- This document assumes the reader has full context of the AgentCall project already
  (architecture, prior fixes, the \$0/month constraint, and the project's established standard
  of live-evidence-before-claiming-done). If anything here is ambiguous, ask before guessing.
- Every item should be verified against the **actually deployed** instance, not just local
  code — this project has previously lost time to code that was correct but not yet deployed,
  or to stale build artifacts on the test device. Always confirm what's actually live.
