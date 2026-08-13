# AgentCall — Improvement Backlog & Implementation Guide

> **Purpose:** Session-memory + work order for future agent sessions. This document captures
> (a) the exact state of the codebase as of 2026-08-12, (b) 15 approved improvement items with
> design decisions, verified file references, implementation steps, verification plans, and
> known risks, and (c) the cross-cutting gates that apply to every item.
>
> **Freshness rule:** every fact below was verified against the tree on 2026-08-12. Before
> implementing an item, re-verify the cited files/lines — the tree will have moved. Anything
> marked `[VERIFY]` was NOT confirmed at write time and must be checked first.
>
> **Companion docs:** `REAL_CALL_IMPROVEMENTS.md` (Phases 1–3), `docs/v2/*` (engine v2 design),
> `AGENTS.md` (conventions), `docs/TECHNICAL_DEBT_REGISTER_v1.md`.

---

## 0. Context-restoration protocol (read this first, every session)

1. Read this document in full.
2. Read `REAL_CALL_IMPROVEMENTS.md` §6–§7 and the session log (what shipped before this backlog).
3. If an item touches the engine: read `docs/v2/README.md` + `10-roadmap.md` §2 (milestones M0–M6).
4. Never start an item without re-verifying its "Current state" references.
5. Every change ends with the Section 3 gates green, or a written reason they're not.

---

## 1. State of the world (2026-08-12) — do not redo

**What works today (shipped before this backlog):**

| Area | Status |
|------|--------|
| Backend signaling + voicebridge | Ring/answer/complete/cancel/callback; pending-session TTL sweep; single delivery path with dedupe deque; agent-ready gate (`attemptRing` 15s × 12 retries); missed-call semantics (`call_expired {reason}`); `GET /api/v1/agents/:agentId/status` endpoint. **21 test files / 159 tests green, lint clean** |
| Android incoming call | `IncomingCallActivity` (accept/decline/call-back-later, 60 s ring timeout, caller tune), FGS notification, ring-open/resolved bookkeeping |
| Android active call | `CallActivity` + `CallViewModel` (`CallPhase` enum: CONNECTING/OUTGOING/RINGING/ACTIVE/RECONNECTING/ENDED), waveform, transcript, quick replies, mute (`ACTION_SET_MUTED`), speaker, reconnect banner |
| Android outgoing call (new today) | Profile "Call" button → `CallActivity(call_id=UUID, caller_name, outgoing=true)` → OUTGOING phase + looping ringback → first `call_answered`/`ai_message` → ACTIVE → `ACTION_START_CALL` (service: `markCallAnswered` upserts the record, voice session, POST ANSWER) → cancel-while-ringing sends `ACTION_CANCEL_CALL`. **Compiled: `assembleDebug` green. Lint unverifiable on this machine (offline). Uncommitted.** |
| DI | `AppModule` now provides raw `Context` (`provideApplicationContext`) — required by `CallAudioManager`'s `@Inject constructor(context: Context)` |

**Key file map (mobile):** `call/CallService.kt` (FGS, actions, TTS/record, event collect),
`call/CallViewModel.kt` (FSM + UI state), `call/CallActivity.kt` (in-call UI),
`call/IncomingCallActivity.kt` (ring UI), `call/SignalingClient.kt` (WS client),
`call/CallAudioManager.kt`, `call/CallerTuneManager.kt` + `settings/MessageTemplates.kt`,
`profile/ProfileDetailScreen.kt` (history UI), `data/database/*` (Room, version = 1),
`home/HomeViewModel.kt` (already consumes `/api/v1/ai/keys`).

---

## 2. Approved improvement items

Legend: `[NEW]` = not started · `[VERIFY]` = fact unconfirmed, check first · `[BLOCKED]` = needs
external resource (networked machine, device).

---

### Item 1 — Missed-call history + "Call back" action

**Status:** `[DONE]` (2026-08-12 session 2; manual E2E on device pending) · **Why:** users currently get zero feedback when an AI call expires
unanswered; history shows a raw "expired" status with no way to return the call.

**Current state (verified):**
- WS `call_expired` → `VoiceBridgeEvent.CallExpired(callId, reason)` — `SignalingClient.kt:298-302`.
- `CallService.kt:418-425` handles it: emits `CallEnded`, `saveCallEnded(callId, "expired")`, then `stopSelf()`. **Silent — no notification today.**
- `ProfileDetailScreen.kt` `statusLabel` maps only `ended/cancelled/started`; "expired" renders raw.
- Drawable `ic_missed_call` already exists (`CallService.kt:667`, used as the ongoing-notification "End" action icon — reuse carefully).
- `CallRecordEntity` carries `agentId`, `callerName`, `startedAt`, `status` — enough to render "Missed".

**Design decisions:**
- "Missed" = `status == "expired"`. Do NOT reuse `ic_missed_call` semantics: the notification icon and the history icon may diverge; use the same asset but different tinting/context.
- **Call back** on a missed row reuses the *outgoing-call flow* (Item 8's prompt mechanism): launch `CallActivity` with fresh UUID + `caller_name` + `outgoing=true` + `EXTRA_CONTEXT_SUMMARY` = the preformatted callback prompt. One code path, no new call engine.
- Missed-call notification is **silent** (importance LOW, no sound/vibration), fired only when the app/activity is not foreground (check `CallService` foreground state; reuse existing channel pattern from the ring notification).

**Implementation steps:**
1. `ProfileDetailScreen.statusLabel`: add `"expired" -> "Missed"`; tint red-amber.
2. `CallHistoryItem`: add a "Call back" affordance on `expired` rows → outgoing `CallActivity` (same intent builder as the profile Call button; reuse via a small shared `OutgoingCallLauncher` or inline builder).
3. `CallService` `CallExpired` branch: if app not foregrounded, post silent notification (channel `missed_calls`) with tap → deep link `profile/{agentId}` (add route in `MainActivity` NavHost if missing — `profile/{profileId}` exists).
4. Confirm the record's `agentId` is populated for agent-initiated calls (it is for outgoing; `[VERIFY]` for ring-expired agent calls — `markCallAnswered` runs only after answer, so `saveCallEnded` on expired may see a record created by the backend sync path).

**Verification:** unit — `statusLabel` mapping; manual — run backend locally, let a call expire, check history row shows "Missed" + Call back launches ringback; check silent notification appears when app backgrounded and tap opens the profile.

**Risks:** expired-call records may have empty `agentId` if the call record was never upserted locally (see step 4 `[VERIFY]`) — fall back to the profile of the caller name or skip the Call-back action.

---

### Item 2 — Home agent online/offline chip

**Status:** `[DONE]` (mobile half completed 2026-08-12 session 2; manual E2E pending) · **Why:** users need to know *why* an agent didn't
pick up; the chip is the passive answer.

**Current state (verified):**
- `GET /api/v1/agents/:agentId/status` exists — `backend/src/routes.ts:629`.
- `McpSessionRegistry.getAgentStatus(agentName)` → `{ online: boolean, lastSeenAt: string|null }` — `session-registry.ts:55`.
- Online = live MCP session OR ai-key auth within `ONLINE_WINDOW_MS = 5 min` — `ai-keys.ts:44`.
- Mobile: `ApiService` has **no** status method; `HomeViewModel` already loads `/api/v1/ai/keys` (used by Settings + home feed).

**Design decisions:**
- Add `ApiService.getAgentStatus(agentId): AgentStatusResponse` (`[VERIFY]` response body shape from `routes.ts:629` — expect `{ online, lastSeenAt }`).
- Fetch once per agent row in `HomeViewModel` (refresh on home resume + pull-to-refresh), not per frame. Chip states: online (green) / offline (slate, with "Last seen Xm ago" tooltip text if `lastSeenAt` present).
- The chip is informational only — no behavior change to ring flow.

**Implementation steps:**
1. `ApiService`: status method + response model (`data/model/Models.kt`).
2. `HomeViewModel`: per-agent status map, `refreshAgentStatuses()`, error-tolerant (offline → chips hidden, never crash).
3. `HomeScreen` row: small dot + text chip next to agent name.

**Verification:** backend tests exist for `getAgentStatus` (`agent-ready-gate.test.ts:289`); mobile: manual — start backend, add ai-key, see chip flip green while key active, slate after 5 min; offline server → no chips, no crash.

**Risks:** rate — one GET per agent per resume is fine; don't poll.

---

### Item 3 — Surface call summaries in history

**Status:** `[DONE]` (2026-08-12 session 2) · **Why:** `CallRecordEntity.summary` was written but never rendered;
history showed raw transcripts instead of the useful AI-generated recap.

**Current state (verified):** `CallRecordEntity.kt:24` `summary: String`. **Writer of `summary`
is unverified** — `[VERIFY]` whether backend `COMPLETE` response includes a summary and whether
`CallService.attemptComplete` stores it; today most records likely hold `""`.

**Design decisions:**
- Make summary truth: populate it at call end (from the last `ai_message` or the COMPLETE response summary field if present — `[VERIFY]`), store via repository.
- History item renders: summary line (bold, 2-line max) → expandable transcript (existing behavior stays). Blank summary → fall back to current transcript-only view.

**Implementation steps:**
1. `[VERIFY]` backend COMPLETE payload for a summary field (`voicebridge/service.ts`, `attemptComplete`); if absent, derive client-side from the final `ai_message` (first 140 chars, no trailing punctuation).
2. `CallRepository`: persist summary in `saveCallEnded` (or a new `saveCallSummary`).
3. `ProfileDetailScreen` `CallHistoryItem`: summary block above the transcript.

**Verification:** backend `[VERIFY]` + unit for truncation; manual — end a call, reopen profile, summary visible.

**Risks:** none material; keep summary field sync in the existing end-call write (avoid a second write path).

---

### Item 4 — Commit the outgoing-call change set + green lint

**Status:** `[DONE]` (8 commits on `feat/outgoing-call-voice-first`; lint/CI still
pending on a networked machine) · **Why:** the outgoing-call work
(Items-of-today: CallActivity/ViewModel/ProfileDetailScreen/AppModule + docs) is a large
uncommitted change set; Android lint cannot run on this machine (offline, `dl.google.com` unreachable).

**Current state:** `git status` shows the session's 48-file change set uncommitted.

**Design decisions (commit plan, per `commit-hygiene` + AGENTS.md):**
- Never commit to `main` — use a feature branch (e.g. `feat/outgoing-call-voice-first`).
- Conventional commits, scoped, atomic — suggested order:
  1. `feat(call): outgoing call flow with ringback + cancel (CallViewModel OUTGOING phase, CallActivity, ProfileDetailScreen entry)`
  2. `fix(di): provide application Context for CallAudioManager (Dagger MissingBinding)`
  3. `docs(call): mark REAL_CALL_IMPROVEMENTS §6.3/§6.4 + task table items 3.1–3.4 done; add IMPROVEMENT_BACKLOG.md`
- Backend changes already in the set: keep them as their own commits if their tests/lint pass (`npm run lint`, `npm test` — 21 files / 159 tests).

**Implementation steps (on a networked machine):**
1. `git checkout -b feat/outgoing-call-voice-first`
2. Stage in the order above; verify each commit builds (`:app:compileDebugKotlin`, `:app:assembleDebug`).
3. Run `./gradlew :app:lintDebug`; fix findings (expected: unused-import level or style; no known blockers).
4. Run `npm run lint && npm test` in `backend/` for the backend portions.
5. Open PR against `develop` per CONTRIBUTING.md.

**Verification:** CI green (lint, typecheck, tests) on the PR; on-device smoke of outgoing call.

**Risks:** the change set touches docs + backend + mobile; keep commit boundaries clean so
review is easy. Do NOT run `lintDebug` on this offline machine (it fails at
`lintAnalyzeDebug` on a network fetch, not on code).

---

### Item 5 — Voicemail on decline

**Status:** `[DONE]` (2026-08-12 session 2; manual E2E pending) · **Why:** declining is the worst outcome for the caller — the agent never
learns why; a voicemail lets the user leave intent and the agent acts on it next time.

**Current state (verified):**
- Decline (IncomingCallActivity.kt:225-231) sends `ACTION_CANCEL_CALL` + `EXTRA_TEXT = MessageTemplates.declineMessage(this)`.
- `MessageTemplates.kt` exposes `declineMessage/laterMessage/laterTemplateRaw` + setters, SharedPreferences-backed (`settings/MessageTemplates.kt:29-59`).
- Local message persistence + retry channels already exist (`KEY_PENDING_CANCELS`, `enqueueUserText`).

**Design decisions:**
- Add a third decline variant: **"Leave voicemail"** → new `MessageTemplates.voicemailMessage(context)` (default: "I missed your call — please call me back when you're available."), delivered through the **same cancel path** (`ACTION_CANCEL_CALL` + `EXTRA_TEXT`), so the existing `attemptCancel` + `savePendingNote` machinery carries it — no new transport.
- Store the voicemail locally as a user message in the call's transcript (`saveUserTextMessage`-style) so it appears in history even before the agent syncs. `[VERIFY]` `CallRepository` has a suitable method (`saveUserTextMessage` exists per CallViewModel usage).
- UX: voicemail button only while ringing (same row as decline), mic icon.

**Implementation steps:**
1. `MessageTemplates`: add `voicemailMessage` + persistence key + reset.
2. `IncomingCallActivity`: third button (mic badge), wires `ACTION_CANCEL_CALL` + voicemail text; keep decline/later untouched.
3. `CallRepository`: persist voicemail text into the call's transcript (guard against duplicate writes — voicemail is written once at cancel).
4. Settings → templates: expose voicemail editing (matches existing template editors).

**Verification:** unit — template default + edit round-trip; manual — decline with voicemail, check history transcript shows the message and backend receives `CANCEL` with the text.

**Risks:** duplicate delivery if cancel retries — idempotency note: text arrives once per call id (existing retry uses `attemptCancel` which is idempotent; store-once flag keyed by callId).

---

### Item 6 — Quiet hours / per-agent DND

**Status:** `[DONE]` (2026-08-12 session 2 — mobile-side per user decision; manual E2E pending) · **Why:** the biggest trust feature for a calling app — the product must
never ring the user at 3 AM.

> **Design deviation (user decision, 2026-08-12):** the original design assumed a
> `user_preferences` table, but the backend is 100% in-memory ("No DB, no Redis" —
> there is no database or migration story at all). Per the user, quiet hours are
> **mobile-only**: stored in SharedPreferences (survives app + server restarts), no
> backend changes. When a call rings during the window: the ring is silent (dedicated
> quiet channel, full-screen UI still shows so an important call can be answered), and
> the calling AI receives the window via the existing decline/cancel note
> ("the user's phone is in quiet hours today (HH:MM–HH:MM)…"). The AI never assumes
> quiet hours and is never told outside an actual call. Per-agent ranges are supported
> in `QuietHoursManager` but not yet exposed in the UI (Settings exposes the global
> window). DST caveat documented: rules are wall-clock local minutes.

**Current state (verified):**
- Agent-initiated calls flow: `createCall` → gated by `isAgentReadyForCall` → `attemptRing(callId, RING_RETRY_INTERVAL_MS=15s × 12)` → push → phone rings.
- No user-side time preference exists anywhere (`[VERIFY]` DB schema for a preferences/settings table — v2 schema docs may define one).

**Design decisions:**
- **Authoritative gate is server-side**: `attemptRing` (and the initial `createCall` push) checks a quiet-hours rule before ringing; enforcement window is configurable per agent + a global default.
- Storage: `user_preferences` key-value (JSONB) — `GET/PUT /api/v1/preferences/quiet-hours` (`{ enabled, perAgent: { agentId: {startMin, endMin} }, global: {...} }`). Reuse existing DB pool + repository pattern. `[VERIFY]` whether a migrations story exists for the new table (backend uses Knex per AGENTS.md — write a new migration).
- Phone-side: during quiet hours the ring may still arrive for calls created before the window started; mobile suppresses the ring **sound** (reuse ringtone plumbing: play silent) but still records the call as missed. Ring-suppression is secondary; server gate is primary.
- No end-user visibility of ring retries — quiet-hour hits simply don't ring and the call becomes `call_expired` (existing TTL path).

**Implementation steps:**
1. Backend: migration for `user_preferences`; route `GET/PUT /api/v1/preferences/quiet-hours` with Zod validation.
2. `service.ts`: in the ring path, `isWithinQuietHours(callId, now)` check (per-agent then global); when blocked, let the pending-TTL sweep expire the call (no ring).
3. Mobile `SettingsScreen`: per-agent + global quiet-hours editor (time pickers, Material3 `TimePicker`).
4. Mobile `IncomingCallActivity`/`CallerTuneManager`: if quiet hours active → silent ring variant (still vibrate? no — fully silent by default; make it a toggle "Allow vibrations").

**Verification:** backend unit tests — gate blocks inside window, allows outside, per-agent overrides global; manual — set quiet hours, trigger agent call, expect no ring + missed record.

**Risks:** timezone semantics — store **local device timezone offset** with each rule or normalize to UTC at write time; document which. DST edge cases are a known trap; prefer "minutes since local midnight" per profile.

---

### Item 7 — Per-agent ringtones

**Status:** `[DONE]` (2026-08-12 session 2; migration test + device E2E pending) · **Why:** agents should be distinguishable like real contacts.

**Current state (verified):**
- `CallerTuneManager.setUri(uri, label)` / `resetToDefault()` — global single tune, SharedPreferences-backed (`settings/CallerTuneManager.kt:29-36`); used by `IncomingCallActivity` at ring.
- Room DB version = 1 (`AgentCallDatabase.kt:14`); `fallbackToDestructiveMigration()` in DEBUG only (`AppModule.kt:38-42`) — **a real Migration is mandatory for any schema change**.

**Design decisions:**
- Store per-agent tone **in `AiProfileEntity`** (new columns: `ringtoneUri TEXT`, `ringtoneLabel TEXT`) — ties tone to agent, survives reinstall via existing profile rows. Shared with Item 9 (quick replies) in **one additive migration 1 → 2**.
- `CallerTuneManager` gains per-agent API (`getUriForAgent(agentId)`, fall back to global tune, then system default) while keeping the global path for backwards compat.
- `IncomingCallActivity` picks tune by the ringing agent's id.
- Ringtone picking reuses `ACTION_RINGTONE_PICKER` (or `RingtoneManager.ACTION_RINGTONE_PICKER`) — no new picker UI.

**Implementation steps:**
1. Write `Migration(1, 2)` adding the two columns (+ `quickReplies TEXT` JSON for Item 9) — **never** rely on destructive fallback outside DEBUG; remove the DEBUG-only fallback after migration exists (keep for dev only).
2. `AiProfileEntity` + DAO: expose `getProfile(agentId)` fields.
3. `CallerTuneManager`: per-agent storage; `ProfileDetailScreen`: "Ringtone" row → picker → save.
4. `IncomingCallActivity`: resolve tune per agent.

**Verification:** migration test (open DB v1 fixture, assert v2 data + columns); manual — set tone for agent A only, ring A vs B, hear different tones.

**Risks:** Room migration must be tested before removing the destructive fallback; picker returns `URI|null` (null = system default) — treat null explicitly.

---

### Item 8 — Call-back & decline via preformatted agent prompts (user-scoped)

**Status:** `[DONE]` (defaults rewritten; payload chain verified — `[VERIFY]` #3 resolved:
`attemptCancel` sends `CancelRequest(note)`; backend `cancelCall(callId, note)`
records the note as a user message in the agent's session — `service.ts:621-629`) ·
**Why (user decision):** no scheduling engine/UI. Decline and call-back
are just **prompts to the agent** so it knows to call afterwards. Accepted trade-off: there is
no guarantee the AI is active on the phone — the existing agent-ready gate + ring retry
(`attemptRing`, 3-min window) and persisted callbacks (`savePendingCallback`) are the only
delivery assurances. **Scope limit: do not build more than prompts unless a strictly better
option exists and is agreed.**

**Current state (verified):**
- Decline/later already send `MessageTemplates.declineMessage/laterMessage` via `ACTION_CANCEL_CALL`/`ACTION_SCHEDULE_CALLBACK` with `EXTRA_NOTE` (`IncomingCallActivity.kt:225-244, 313-316`).
- `MessageTemplates.kt` defaults are editable in Settings.

**Design decisions:**
- The prompt is the mechanism. Default texts must state intent explicitly:
  - Decline: "...Please call me back when you're available."
  - Call back later (N min): "...Please call me back in about N minutes."
  - Missed-call Call-back action: the outgoing call's `EXTRA_CONTEXT_SUMMARY` carries the same
    call-back prompt (Item 1 reuses this).
- Keep existing transport (`ACTION_CANCEL_CALL` + `EXTRA_TEXT`; `ACTION_SCHEDULE_CALLBACK` +
  `EXTRA_NOTE` untouched — it already persists + retries).
- No new backend endpoint, no new UI surface beyond template defaults.

**Implementation steps:**
1. Rewrite `MessageTemplates` defaults to the prompt style above (English defaults; keep editability).
2. Missed-call "Call back" (Item 1) reuses the prompt as `EXTRA_CONTEXT_SUMMARY`.
3. `[VERIFY]` the agent (MCP side) actually receives the cancel `note`/text so the prompt reaches it — check `attemptCancel` payload.

**Verification:** manual — decline a call, confirm the agent-side message contains the call-back intent; verify no regressions in cancel/retry tests.

**Risks:** prompts are soft guidance — an offline agent can't act; that is the accepted
trade-off. Keep wording imperative and specific so any LLM agent follows it.

---

### Item 9 — Quick replies per agent

**Status:** `[DONE]` (2026-08-12 session 2; manual E2E pending) · **Why:** quick-reply chips were global today; each agent persona should
own its chips.

**Current state (verified):**
- Chips render from `state.callContext.options` (`CallActivity.kt`, `QuickReplyChips`) — server-supplied at call creation.
- `MessageTemplates` (Settings) is global, SharedPreferences-backed.
- No per-agent message/template storage.

**Design decisions:**
- Per-agent quick replies live **on-device** in `AiProfileEntity.quickReplies` (JSON string array, max 4 chips) — same migration as Item 7.
- Precedence: profile chips (if set) → server `callContext.options` → fallback empty.
- Settings → profile page: "Quick replies" editor (add/remove/reorder ≤ 4).

**Implementation steps:**
1. Migration 1→2 adds `quickReplies TEXT` (with Item 7).
2. `ProfileDetailViewModel`/`ProfileDetailScreen`: editor + save.
3. `CallViewModel` `connect`: merge profile chips into `callContext.options` when set.
4. `MessageTemplates`: keep global as the base layer (used when a profile has none).

**Verification:** unit — precedence logic; manual — set chips for agent A, start call, only A's chips appear; B shows server options.

**Risks:** JSON parse failures must degrade to no-chips (never crash); cap length at write time.

---

### Item 10 — On-device E2E pass

**Status:** `[BLOCKED]` (needs networked machine + device + running backend/coturn) · **Why:** ringback, cancel-while-ringing, mute, reconnect banner, and the outgoing handoff are all untested on hardware.

**Design decisions:** the pass is a scripted manual run, not automation (no instrumented device farm on this machine). Sequence below is the canonical order.

**Test script (run top-to-bottom):**
1. **Baseline**: `docker compose up` (backend + coturn), install debug APK, grant notifications + mic, set server host in Settings, add an ai-key.
2. **Incoming**: trigger agent call (MCP/tool or scripted `createCall`) → ring UI ≤ 60 s → accept → voice session starts, transcript scrolls.
3. **Outgoing**: Profile → Call → ringback audible → agent answers → ringback **stops**, timer starts at ACTIVE, greeting plays.
4. **Cancel while ringing**: start outgoing, press Cancel within the ringing window → `ACTION_CANCEL_CALL` delivered, activity closes, history shows "Cancelled".
5. **Mute**: toggle mute mid-call → agent TTS suppressed + notification reads "Muted"; unmute restores.
6. **Speaker**: toggle → audio routes to speaker (verify via `setCommunicationDevice` after Item 11).
7. **Reconnect**: airplane mode 10 s mid-call → banner "Reconnecting — the call stays live" → disable airplane mode → call resumes; if link stays dead → ENDED.
8. **Decline paths**: decline / call-back-later / voicemail (Item 5) — each ends the ring and records correctly.
9. **Missed**: let ring TTL expire → silent missed notification (backgrounded) → history "Missed" → Call back works.
10. **Battery**: end a call, wait 2 min, verify no wake lock / no TTS engine held (Item 13 checklist).

**Verification:** every step above passes; record device logs (`adb logcat -s CallService CallViewModel IncomingCallActivity`) for the repo if anything fails.

**Risks:** backend must be reachable from the phone (same LAN or port-forward); coturn for NAT'd networks.

---

### Item 11 — Migrate `isSpeakerphoneOn` → `setCommunicationDevice`

**Status:** `[DONE]` (compile-verified; manual E2E step 6 pending on device) ·
**Why:** `AudioManager.isSpeakerphoneOn` is deprecated.

**Current state (verified):** `CallAudioManager.kt:78,86` and `CallActivity.kt:492` use `isSpeakerphoneOn`.

**Design decisions:**
- API 31+: `audioManager.getDevices(GET_DEVICES_OUTPUTS).firstOrNull { it.type == TYPE_BUILTIN_SPEAKER }` → `setCommunicationDevice(device)`; toggle back to the earpiece/wired device on unmute. API 30−: keep `isSpeakerphoneOn` (not deprecated there — safe).
- Centralize in `CallAudioManager` (`fun setSpeakerphone(on: Boolean)`); `CallActivity`'s local `isSpeakerOn` remember-state delegates to it (remove the duplicated AudioManager block at CallActivity.kt:492).

**Implementation steps:**
1. `CallAudioManager.setSpeakerphone(on)`: SDK gate (31+) + fallback.
2. `CallActivity` speaker control → `audioManager` (injected VM-level, already in CallViewModel) instead of raw `context.getSystemService`.
3. Delete the inline deprecated block.

**Verification:** compile; manual E2E step 6; `adb shell dumpsys audio` to confirm routing change.

**Risks:** `setCommunicationDevice` needs `MODIFY_AUDIO_SETTINGS` (already granted for calls); device null on some hardware → fallback to legacy path.

---

### Item 12 — Unit tests for `CallViewModel` FSM

**Status:** `[DONE]` (2026-08-12 session 2 — pure `CallStateMachine` extracted + JUnit matrix written;
`testDebugUnitTest` needs a networked machine for the new `junit` dep) · **Why:** phase bugs (e.g., ringback never stopping, stuck RECONNECTING) are the highest-risk part of the call UX and are currently only testable on device.

**Current state (verified):** `CallViewModel` mixes state transitions with Android side-effects (`Log`, `SignalingClient`, services) — not directly unit-testable. Android module has **no unit tests** (`src/test` absent).

**Design decisions:**
- Extract a **pure** `CallStateMachine` (no Android deps): inputs `PhaseEvent` (`CallAnswered`, `AiMessage`, `ReconnectStateChanged`, `Tick`, `Ended`, ...), outputs new `CallPhase` + flags (e.g., `ringbackShouldStop`, `timerRunning`). `CallViewModel` delegates and applies side effects.
- Keep the machine in `call/state/CallStateMachine.kt`; `CallPhase` moves with it (keep a typealias at the old location for the UI).
- Test source set: `src/test/java` with JUnit4 + `kotlinx-coroutines-test` (`[VERIFY]` build.gradle.kts `testImplementation` — likely needs adding `junit:junit` + `org.jetbrains.kotlinx:kotlinx-coroutines-test`; check `libs.versions.toml` before adding — non-negotiable #2).

**Test cases (minimum):**
- OUTGOING → ACTIVE on `call_answered` (assert `ringbackShouldStop=true`).
- OUTGOING → stays until first `ai_message` if no `call_answered`.
- CONNECTING → RECONNECTING → ACTIVE on reconnect success (timer paused then resumed).
- RECONNECTING → ENDED when reconnect exceeds the tolerance.
- Terminal: ENDED ignores all later events.
- Cancel-from-OUTGOING transitions cleanly.

**Implementation steps:**
1. Extract machine + move `CallPhase`.
2. Wire `CallViewModel` to it; verify app still compiles (`assembleDebug`).
3. Add test deps; write the matrix; `./gradlew :app:testDebugUnitTest`.

**Verification:** unit suite green; no behavior change in manual smoke.

**Risks:** the refactor must not change runtime behavior — run the Item 10 smoke before/after. If the ViewModel is too entangled, extract incrementally (machine first, side effects later).

---

### Item 13 — Battery audit

**Status:** `[DONE]` (code 2026-08-12 session 2; `dumpsys` verification on device pending) · **Why:** no evidence the app holds resources after a call ends.

**Current state (verified):**
- Wake lock acquired in `ACTION_START_CALL` (`CallService.kt:156` `acquireWakeLock()`), released in `endCall()`.
- TTS init: `initTts()` on `ACTION_PREWARM_TTS` or lazily; `textToSpeech` is a service field — engine stays alive app-wide.
- FGS: `startForeground(NOTIFICATION_ID_ONGOING, ...)` at START_CALL; `endCall()` should `stopForeground(STOP_FOREGROUND_REMOVE)` — `[VERIFY]` it does.
- Recording: `startRecording/stopRecording` (`ACTION_START/STOP_RECORDING`).

**Design decisions (audit → fix):**
1. Prove the happy path: after ENDED, assert — no wake lock (`adb shell dumpsys power | grep -i wakelock`), no FGS notification, audio focus abandoned, recorder closed.
2. Fix gaps found; the two known candidates: (a) TTS engine retained indefinitely — add `textToSpeech?.shutdown()` after an idle timeout (e.g., 60 s post-ENDED) while keeping PREWARM working; (b) `stopSelf()` paths that skip `endCall()` — enumerate all `stopSelf` call sites (`CallService.kt:194, 410, 416, 424`) and ensure each releases resources once.
3. Add a release-once guard (`released` flag) so double-calls can't throw.

**Implementation steps:**
1. Walk every terminal path (END_CALL, CallCancelled, CallExpired, disconnect) — single `releaseCallResources()`.
2. TTS idle shutdown with restart-on-demand (`initTts()` already re-inits; make it lazy-safe).
3. `adb` battery checks after a 5-min-call scenario.

**Verification:** dumpsys checks above; `adb shell dumpsys batterystats --reset` before, discharge deltas after 30 min idle with one call.

**Risks:** TTS shutdown/restart can add startup latency — measure and keep the idle window conservative.

---

### Item 15 — Realtime duplex audio (v2 M2/M4)

**Status:** `[DEFERRED]` (user decision 2026-08-12 session 2 — defer to roadmap) · **Why:** the single
biggest perceived-quality jump — replaces record-clip-then-reply with a conversation.

**Design decisions (from the v2 docs — do not re-derive):**
- M2 (realtime conversation): streaming STT partials + final, streaming TTS (token→audio), barge-in ≤ 50 ms, silence events (`silence.detected`, `call.noactivity`), turn leases + `turn.ended`.
- M4 (tools & media): `ToolInvoker` + `invoke_tool`; scope-filtered subscriptions; media channel (WS `v2/media`) + WebRTC/coturn attach via a `TransportProvider` seam (never bind core to one engine; `wrtc`/`mediasoup`/LiveKit are adapter options; coturn stays).
- Keep the $0 constraint: on-device STT/TTS remains the default; providers are optional adapters (`@agentcall/*-provider-*`).

**Implementation steps (phased):**
1. M2: `EventPlane` in-process + outbox write path; `POST /api/v2/*` (create/message/utterance/hangup); SSE `events`; MCP `send_message_and_wait` lease semantics. 45s cap behind flag (see Item 16).
2. Streaming adapters: STT partials first (Android `SpeechRecognizer` partial results on-device, then provider adapters); streaming TTS queue with barge-in cut.
3. M4: `TransportProvider` + WebRTC attach; measure barge-in p95 ≤ 50 ms; E2E Playwright call test.

**Verification:** per-roadmap exit criteria — scripted-provider determinism, barge-in budget measured, WebRTC E2E call green, v1 suite still green behind façade.

**Risks:** M2–M4 is the critical path; schedule as a separate work stream from Items 1–13 (different code paths, same repo — merge discipline per item 4).

---

### Item 16 — No-45s-cap engine (v2 M1)

**Status:** `[PARTIAL]` — **M1 engine core landed (2026-08-12 session 3)**; **M2 realtime
core landed (2026-08-13 session 4)**; M3–M6 remain on the roadmap. · **Why:** the
documented 45-second reply window is user-facing and wrong for long agent turns.

**Current state (verified):** **No `MESSAGE_WINDOW_MS`/45s constant exists in the tree**
(searched `backend/src` + mobile). The cap is a *behavioral* constraint of the current
async flow (`[VERIFY]` against live behavior — likely the ai-wait lease `activeUntil` /
reply-poll cadence, `AI_REPLY_POLL_INTERVAL_MS=500` in `config.ts:49`).

**Design decisions:**
- Do not bolt on a timeout flag in the current engine. Implement the M1 engine core (EventPlane + event log + tolerance events) and remove the cap **behind a flag** (`PERSISTENCE_MODE`/`ENGINE_V2`-style), per roadmap M1 exit criteria: v1 suite green, v2 contract tests green, cap gone behind flag.
- Replace user-facing timeouts with `silence.detected` / `call.noactivity` escalation events so AI clients never wait forever (roadmap risk R7).

**Implementation steps:** follow `docs/v2/10-roadmap.md` M1 (3–5 wk estimate) — milestones and exit criteria are the source of truth; this item is the roadmap, not a new design.

**What shipped (M1 core, session 3):**
- `backend/src/v2/` — new additive namespace, zero v1 surface changes:
  - `ids.ts` (UUID v7, no new dep) · `events.ts` (envelope + M1 catalog + zod EventRegistry)
  - `event-log.ts` (`EventLogStore` iface + `InMemoryEventLogStore`, contiguous per-call sequence, cursor replay) — Postgres impl slots in at M3
  - `event-plane.ts` (in-process bus: outbox write first, per-call total order, replay+cursor, id-dedupe)
  - `call-fsm.ts` (pure FSM creating→ringing→connecting→connected→paused→completed/failed; `INVALID_TRANSITION`=409)
  - `idempotency.ts` (`Idempotency-Key`, 24h TTL, `X-Idempotent-Replay`)
  - `call-service.ts` (`createCall/answerCall/sendMessage/submitUtterance/hangupCall/failCall/archiveCall`, ownership, idempotent `client_message_id`, advisory silence policy → `silence.detected`×3 → `call.noactivity`)
  - `routes.ts` (`POST/GET /api/v2/calls`, `answer`, `hangup`, `messages`, `utterances`, `transcript`, `DELETE`; SSE `GET /calls/:id/events` with `Last-Event-ID`/`?after=` resume, heartbeat, `stream.end`)
- `config.ts`: `v2.engineV2` (`ENGINE_V2=true`), `v2.noactivityEscalationMs` (default 5 min), `v2.sseHeartbeatMs`, `v2.idempotencyTtlMs`.
- **45s cap gone behind flag:** `send_message_and_wait` with `ENGINE_V2=true` uses turn-lease semantics — `timeout_seconds` becomes an optional client window (no maximum); absent = wait until reply/call-end/noactivity escalation. `registerAiWait(callId, null)` = no-expiry lease. Flag off = today's behavior byte-for-byte (schema still caps at 45).
- Tests: 5 new files / 46 tests (`v2-fsm`, `v2-event-log` (log+plane), `v2-call-service`, `v2-routes` (REST+SSE), `v2-mcp-lease`).

**Verification (green):** roadmap M1 gate — v1 suite green (164), v2 contract tests green (46), total **210 tests / 26 files**, `tsc --noEmit` + ESLint clean. Cap removed only when flag on.

**What shipped (M2 realtime core, session 4):**
- `providers.ts` — TTS provider seam (roadmap §3.2 R2/R10): `TtsProvider`/`TtsCallbacks`/`TtsHandle`; `SyncTtsProvider` ($0 on-device default, preserves M1 event sequence) + `ScriptedTtsProvider` (deterministic token streams for tests). `TtsHandle.stop()` is the synchronous barge-in budget.
- `call-service.ts` — engine-level streaming lifecycle: `sendMessage` streams through the provider (queued → `message.started` on first byte → `message.completed`); new `speak()` (non-blocking `say()`), `stopSpeaking()` (`turn.cancelled(ai_stop)` + `message.failed`), `submitUtterancePartial()` (partials → finalize: `speech.started` → `speech.partial`* → `speech.final` + `transcript.partial.cleared`; idempotency binds at finalize); **barge-in**: first partial cuts the live stream synchronously → `user.interrupted` + `turn.cancelled(barge_in)` before `speech.started`; `turn.lease` emitted only on wait-state change (active after AI turn, released on human speech/ai_stop/error); snapshot `active_turn`/`ai_wait` live; `getTranscript(includePartials)` exposes open partials with `is_partial`.
- `routes.ts` — `POST /calls/:id/speak`, `POST /calls/:id/stop-speaking`, `POST /calls/:id/utterances/partial` (not idempotency-wrapped — client key binds at finalize), `GET transcript?partials=true`.
- `events.ts` — catalog + schemas for `speech.partial`, `transcript.partial.cleared`, `user.interrupted`.
- Tests: `v2-realtime.test.ts` (11 tests) — scripted-provider determinism, TTFB/speak latency, **barge-in p95 ≤ 50 ms measured** (engine path + seam), ai_stop, provider-error, partial streams, transcript partials.

**Verification (green):** M2 gate — 223 tests / 27 files, `tsc --noEmit` + ESLint clean. M2 exit criteria met: scripted-provider tests deterministic; barge-in p95 measured ≤ 50 ms (both at the `TtsHandle.stop()` seam and through the engine barge path).

**Remaining (M3+ per roadmap):** Postgres event log + recovery (M3), tools/media channel + WS `/v2/media` audio transport (M4). Phase-1 façade re-pointing of `complete_call`/`cancel_call` through v2 `hangupCall` deferred until the WS `/phone` projection exists (Phase 3) — v2 tracks its own calls only.

**Risks:** engine work must not destabilize the shipped async flow — façade keeps v1 shapes (roadmap R5).

---

### Item 17 — Privacy as marketing (on-device STT/TTS)

**Status:** `[DONE]` Phase A (audit → `docs/SECURITY_GUIDELINES.md` §Privacy data-flow audit)
+ Phase B (Settings → Privacy & Data section) + Phase C copy (gated on the audit),
2026-08-12 session 2 · **Why:** "your voice stays on your device" is a defensible differentiator —
but **only if true**. Honesty gate: audit the data flow before writing any claim.

**Current state:** `[VERIFY]` — where does audio go today? (a) Does the Android recorder upload
audio to the backend, or is transcription on-device? (b) Is TTS on-device (Android engine) or
server-side? (c) What does the backend retain (transcripts, recordings, retention)? The
answer determines every word of the copy.

**Design decisions:**
- **Phase A — truth audit**: trace `CallService.startRecording` → transport → backend storage;
  document the flow in `docs/SECURITY_GUIDELINES.md`; identify retention (roadmap R9 default:
  30 d, configurable, encrypted, deletable).
- **Phase B — in-app surface**: Settings → "Privacy & data" section with a plain-language flow
  diagram; per-call privacy note in the in-call screen; one-tap "delete my data" if the backend
  supports it (right-to-erasure — R9).
- **Phase C — claims**: only after A. If on-device STT/TTS is the default, badge it
  ("Voice stays on-device"); if transcription is server-side, say "encrypted in transit,
  deleted after N days" — never claim on-device falsely.

**Implementation steps:**
1. `[VERIFY]` audio/transcript flow (A). Record findings in the doc above.
2. Add the Privacy section (B) — new `PrivacyScreen` or a section in `SettingsScreen`.
3. Add claims (C) gated on A.

**Verification:** copy matches the audit; a fresh-install user can find the privacy info in ≤ 2 taps.

**Risks:** regulatory exposure if claims overstate — the audit (A) is a hard prerequisite,
not a nice-to-have.

---

## 3. Cross-cutting gates (apply to every item)

1. **No destructive ops.** No `git reset --hard`, no deleting files/branches, no DB drops on
   non-local. Room migrations: **write real `Migration(1, 2)`**; the DEBUG-only destructive
   fallback (`AppModule.kt:38-42`) is dev comfort, not a release strategy.
2. **New dependencies.** Verify existence on the registry before adding (`pre-flight-check`).
   For tests: confirm `junit`/`kotlinx-coroutines-test` availability in `libs.versions.toml`
   before referencing (Item 12).
3. **Commits.** Conventional (`feat|fix|docs|refactor(scope): ...`), scoped, atomic; feature
   branch; never `main` (Item 4 ordering).
4. **Verification.** Every item ends with its listed verification run — build
   (`./gradlew :app:assembleDebug`), tests, and (on a networked machine) `lintDebug` +
   `npm run lint && npm test` for backend.
5. **This machine is offline.** Lint/CI/device work (`Item 4`, `Item 10`) is
   `[BLOCKED]` here — plan around it, never fake it.
6. **Suggestion vs. instruction.** Items are the user-approved backlog; if a cheaper/better
   path appears, propose it and get explicit sign-off before deviating (Items 8's scope note).

---

## 4. Session log

- **2026-08-13 (session 4)** — **Item 16 (v2 M2 realtime core) implemented**: TTS provider
  seam (`providers.ts`), engine streaming lifecycle + barge-in + `turn.lease` (`call-service.ts`),
  `speak`/`stop-speaking`/`utterances/partial` + `transcript?partials=true` routes, 11 new
  `v2-realtime` tests (barge-in p95 ≤ 50 ms measured at seam + engine path). M1 suite updated
  for the lease event (sendMessage 9→10). Full suite **223 tests / 27 files** green, lint +
  typecheck clean. Committed as 4 atomic commits (M1: `74266fc` engine core, `5d71a3f` service
  + REST/SSE, `fade701` contract tests, `eb46590` docs) + M2 commit.

- **2026-08-12 (session 3)** — **Item 16 (v2 M1) implemented**: new `backend/src/v2/` engine
  (EventPlane + in-memory event log + pure FSM + `V2CallService` + idempotency + `/api/v2/*`
  REST + SSE). `ENGINE_V2` flag removes the `send_message_and_wait` 45s cap (turn-lease
  semantics, `registerAiWait(null)` no-expiry lease, noactivity escalation). 46 new v2
  contract tests; full suite 210 green, lint/typecheck clean. Committed (M1 set above).


- **2026-08-12 (session 2)** — Backlog wave 2 executed (user scope: Items 1–13 + 17;
v2 engine items 15/16 deferred to roadmap). **Item 6 design deviation:** backend verified
100% in-memory (no DB/migrations); per user decision quiet hours are mobile-only
(`QuietHoursManager`, SharedPreferences) with a silent-ring channel and the window told to
the calling AI via the decline/cancel note — no backend changes. **Item 1:** `expired` →
"Missed" + red-amber + Call-back (reuses outgoing flow with `callbackPrompt`), silent
missed-call notification when backgrounded → deep link to profile. **Item 2 (mobile half):**
`ApiService.getAgentStatus` + `HomeViewModel.agentStatus` + "Last seen" chip. **Item 3:**
summary derived from last AI message, sent with COMPLETE, persisted + rendered in history.
**Item 5:** voicemail template + 4th ring button + local transcript write via the cancel path.
**Item 7/9:** Room `Migration(1,2)` (`ringtoneUri`, `ringtoneLabel`, `quickReplies` — schema v2
exported), per-agent tune resolution at ring time, ringtone picker + quick-replies editor on
the profile screen, `CallViewModel` merges profile chips over server options. **Item 12:**
pure `CallStateMachine` extracted (`call/state/`), ViewModel delegates, JUnit matrix written
(deps added; run on a networked machine). **Item 13:** release-once guard in
`releaseCallResources()` + TTS idle shutdown (60 s, re-init on demand) + `callId` reset on
end. **Item 17:** Phase A audit documented, Settings Privacy & Data section added. Verified:
backend typecheck + lint + 164 tests green; `assembleDebug` green offline. **Review fix
(Item 1 + 5 correctness):** the reviewer caught that unanswered rings created no
`call_records` row — `saveCallEnded("expired")` no-oped, the missed notification was
silently dropped, and decline/voicemail note inserts hit the transcript FK. Fixed by
`CallRepository.markCallRinging` (row created when the ring starts) + handling
`CallExpired`/`CallEnded`/`CallCancelled` in `SignalingForegroundService`, plus the
`AgentStatus` chip now mints its phone token first and `onNewIntent` re-evaluates quiet
hours. **Remaining on a networked/device machine:** `lintDebug`, `testDebugUnitTest`,
Room migration test (`MigrationTestHelper`), Item 10 device pass, `dumpsys` battery checks.

- **2026-08-12 (later session)** — Backlog executed in two waves. (a) **Item 4:**
  full change set committed as 8 atomic commits on `feat/outgoing-call-voice-first`
  (ci/infra, backend config consolidation, agent-ready gate + presence + TTL +
  Bearer WS auth, android toolchain bump, TokenManager removal + poll backoff,
  DI context fix, outgoing call flow, docs); backend lint + 164 tests green,
  `assembleDebug` green at each stage; `lintDebug` still blocked offline.
  (b) **Item 8:** decline/callback defaults rewritten as imperative call-back
  prompts; `[VERIFY]` resolved — the cancel note reaches the agent as a user
  message in its session. (c) **Item 11:** speaker routing migrated to
  `setCommunicationDevice` (API 31+) with earpiece/headset fallback and
  `clearCommunicationDevice()`; `CallActivity` delegates to `CallViewModel`.

- **2026-08-12** — Backlog created. Outgoing-call + voice-first shipped this session
  (ringback, cancel, mute, reconnect banner, profile Call button; Dagger Context fix;
  `assembleDebug` green; lint blocked offline; uncommitted — see Item 4).
  Backlog items 1–13, 15–17 defined with verified file references; item 14 (iOS) excluded by
  user decision; item 8 scoped to prompts-only per user decision.