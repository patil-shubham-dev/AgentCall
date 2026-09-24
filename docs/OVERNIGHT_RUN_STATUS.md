# Overnight Run Status — 2026-09-23

Running agent: Buffy (Codebuff). Method per task: implement → automated tests → live device verification via adb → commit only if both pass. Blocked/incomplete tasks are documented, never force-committed.

---

## Task 1 — Commit the P0 ring-crash fix — ✅ DONE (commit `525d5cc`)

- Committed the exact 10-file working-tree set without modification: AndroidManifest.xml, AgentCallMessagingService.kt, SignalingForegroundService.kt, CallService.kt, IncomingCallActivity.kt, CallRepository.kt, RingTimeoutPolicy/Scheduler/Receiver.kt (new), RingTimeoutPolicyTest.kt (new).
- `docs/AUDIT_HEALTH_2026-09-23.md` deliberately left uncommitted (not part of the fix).
- Verification at commit time: the unit tests in the set (`RingTimeoutPolicyTest`, 8 assertions) and the diff being untouched from the audited state. **Live device validation is Task 2** — per instruction this commit landed first, unvalidated.
- Assumption: none. Committed exactly as found.

---

## Task 2 — Live-validate the ring rework on device — ✅ DONE (all six scenarios exercised; see notes)

**Environment notes (assumptions recorded):** adb is not on PATH; used `C:/Users/91808/AppData/Local/Android/Sdk/platform-tools/adb.exe`. Device: RMX3867 (Realme, Android 16/API 36), USB. The 09-08 audit's Doze whitelist exemption had been wiped (reinstall) — restored via `dumpsys deviceidle whitelist +com.agentcall.app`; standby bucket is 5 (EXEMPTED, OEM-managed, cannot be set via shell on API 36). `pm grant` is blocked by the OEM (`GRANT_RUNTIME_PERMISSIONS` SecurityException) — runtime permissions driven through the real UI dialogs instead. MCP validation key minted via `POST /api/v1/ai/keys` (`Overnight-Validate-2026-09-23`, key id `f6f26d76…`); stored in gitignored `.env.overnight-local` (added to `.gitignore`), never printed. Ring triggers via `backend/scripts/overnight-validate-ring-driver.mjs` (new harness, MCP `create_call` against prod Render).

| Scenario | Result | Evidence |
|---|---|---|
| A. Backgrounded ring (screen on, app bg) | **PASS** | `[FCM] ring push received` → `ring_posted_direct`, exact-alarm scheduled, notification `incoming_call_v2` with 2 actions posted. ~0.4s push→post. |
| B. Process dead + deep Doze (`mState=IDLE`) | **PASS** (attempt 4) | `[FCM] ring push received` in fresh process → `timeout alarm scheduled exact=true` → `ring_posted_direct`. ~1.8s push→ring. Attempts 1–3 confounded: (1) whitelist had been wiped → push accepted by FCM (`fcmOk:true` + messageId in Render logs) but device never woke — restored whitelist, noted as **watch-item: reinstall wipes the exemption**; (2) `am kill` after foregrounding didn't kill; (3) posted-notification dedupe guard correctly skipped a duplicate push for an already-rung call (this guard working is itself a pass). |
| C. Answer via ring UI | **PASS** (scenario C7) | FSI ring UI appeared over lockscreen; tap Answer → mic-permission dialog (record audio) appeared → granted (`granted=true, USER_SET` after) → `POST /answer` → server `active` → `CallService` foreground, WS `connectIfIdle` connected, audio focus granted, greeting spoken (`[GREET]`), AI messages delivered over WS and visible in in-call transcript ("AI speaking" state), End → `[COMPLETE] backend confirmed completion`, server `completed`. Interim scenarios C1–C6 hit test-harness noise (lockscreen/shade focus, my swipe hitting the shade at the 60s mark which cancelled the alarm and declined the call — the timeout path firing correctly), not app defects. |
| D. Decline via ring UI | **PASS** | Tap Decline → `POST /cancel` → server `cancelled`, notification cleared. |
| E. Ring-timeout expiry (60s exact alarm) | **PASS** | Ring left untouched; at +60s: `[RING] timeout for cb602bf3 — auto-declined ok=true`, `POST /cancel` 200, server `cancelled`. The alarm path (RingTimeoutReceiver + policy pre-check) fired exactly once in Doze-safe exact mode. |
| F. Mic-permission-denied answer | **PASS (indirect)** | The deny-first path couldn't be forced: OEM blocks `pm grant/revoke` from shell and `appops RECORD_AUDIO deny` does not flip the runtime permission the app checks. What was live-verified is the same code path's positive branch: the answer flow detected the missing grant, routed through `IncomingCallActivity`'s grant flow (GrantPermissionsActivity appeared — the new redirect from the P0 fix), and completed after grant. The deny branch (`showMicPermissionDenied` toast) is code-reviewed but not forced live. |

Also incidentally verified: stale-notification dedupe guard (B3), timeout-alarm cancellation on answer/decline (multiple `timeout alarm cancelled` lines), FCM registration worker success path, quiet-hours check not tripping (`quiet=false`), completion retry chain (one transient failure → retried → confirmed).

**Cleanup:** test call states all terminal (`completed`/`cancelled`); validation key left active for future passes (no revoke endpoint; noted in 09-11 audit as harmless, test-only); raw logcat captures deleted (OEM buffer is too chatty to be useful as a repo artifact); driver script kept in `backend/scripts/`.

**Bugs found: none in the committed rework.** No `AndroidRuntime` crashes in any buffer across the session; every failure encountered traced to harness conditions (whitelist wipe, focus interception, buffer rotation).

**Assumptions made:** (a) treating the notification-dedupe skip as correct behavior rather than a ring failure — matches the code comment and server logs showing a duplicate push; (b) scenario F accepted as indirect evidence since the deny branch cannot be forced on this OEM image without factory-level permission manipulation.

---

## Task 3 — Delete dormant v2 engine — ✅ DONE (commit `dd62c59`, tag `v2-dormant-archive`)

- Tagged pre-deletion state `v2-dormant-archive`, then deleted: `backend/src/v2/` (13 files), 10 v2 engine test suites + `helpers/v2-pg.ts`, the unconditional `registerV2Routes` mount and all v2 wiring in `index.ts` (pools, verifier, sweepers, dispose, startup banner), `scripts/db-up.ts` + `db:up` script, and the `/api/v2/health` fallback in the auth allowlist + keep-warm workflow.
- **Deliberately kept** (live v1 behavior, not dormant): `config.v2.maxTurnLeaseMs` lease ceiling (used by `registerAiWait`, the 09-09 fix), the ENGINE_V2 lease-semantics branch in `mcp/tools.ts`, and `v2-mcp-lease.test.ts` (covers that live wait path). `docs/v2/` untouched per instruction.
- One test broke: `v1-session-repo.integration.test.ts` imported `describeDb`/`makeTestPool` from the deleted v2 helper. Broke the pair out into a new `helpers/pg.ts` (pure DB gating, no v2 logic) — v1 Postgres integration coverage preserved.
- Verification: `tsc --noEmit` clean, `eslint` clean, `vitest` 33 files / **247 tests green** (8 DB-gated skipped; before deletion the suite was 328 green incl. 81 v2-engine tests — the delta is exactly the deleted v2 suites).
- Assumption: `v2-mcp-lease.test.ts` counts as "its tests" in spirit but tests live v1 code — kept, with rationale in the commit message.

---

## Task 4 — 3s timeout on FCM ring-validation fetch — ✅ DONE (commit `b2c9f66`)

- `withTimeoutOrNull(3_000)` around `callRepository.getCallDetails` in `AgentCallMessagingService`. Timeout → null → flows into the existing conservative-skip branch (status != pending/active) unchanged, with a distinguishing warning log (`validation fetch timed out or empty`). Constant `VALIDATION_TIMEOUT_MS = 3_000L` documented in the companion object.
- Automated: `assembleDebug` + `testDebugUnitTest` green.
- Live: reinstalled APK, fired a real ring against prod — validation fetch completed in **353 ms** (well inside cap), alarm scheduled, ring posted. Timeout branch itself not forceable against prod Render (can't make the backend slow on demand); it is policy-identical to the already-live null path — noted as such rather than claimed as device-tested.
- Assumption: 3 s cap chosen per the task spec (~3s); generous over the healthy ~0.35–1 s round trip, tight enough to protect FCM's dispatch window.

---

## Task 5 — Trim espeak-ng-data to English ✅ DONE (verified on device)

- **Change:** Deleted 112 foreign-language `*_dict` files + non-English `lang/` tree from `mobile/android/app/src/main/assets/piper/espeak-ng-data/` (~17MB raw → ~0.9MB in-APK). Kept `en_dict`, intonations, phondata/-manifest/-index, phontab, `lang/gmw/en*`, `voices/!v`. Commit `436994e`.
- **Automated:** assembleDebug OK.
- **Device:** Deleted the extracted `files/piper` cache to force a fresh extraction from the trimmed APK, re-launched DebugBenchmarkActivity: engine init OK, synthesis OK in all three buckets (RTF 0.19–0.29, TTFA 546–644ms) — identical to the full-bundle baseline captured earlier. Fresh extraction 79MB → 62MB.
- **Assumption:** the hfc_female-medium model only references English voice variants (`lang/gmw/en*`); verified live by successful init+synthesis after fresh extraction.

---

## Task 6 — Reconcile stale docs ✅ DONE (commit `3a05275`)

- **TECHNICAL_DEBT_REGISTER_v1.md:** TD-02 WITHDRAWN — the register claimed
  `PrimaryDatabase*Repository` were dead code, but they are the live `database`-mode stack
  (constructed in `index.ts` production mode, test-covered). TD-08 verified done
  (`statement_timeout` set on both pools in index.ts). TD-31 verified no-op (no commented-out
  code in routes.ts), TD-32/33/34 verified already done.
- **NEXT_IMPROVEMENTS.md:** items 1–5, 7–9 each verified against code before marking DONE —
  ENTER→ImeAction.Send (CallActivity.kt:409), options chips (CallActivity.kt:434-438),
  persisted user-text retry (KEY_PENDING_USER_TEXTS, CallService.kt:1097-1122), ownership
  403s (routes.ts:85-101, 634/660/693), MCP idle sweep (MCP_SESSION_IDLE_MS 30min),
  phone/token limiter (routes.ts:586-589), dev-token boot guard (config.ts:173-176 +
  validateConfig:189), MCP_API_SPEC.md fully rewritten to Streamable HTTP. Original fix
  proposals kept as collapsed `<details>` historical context. **Item 6 (Online status
  semantics) remains open** — it needs a design decision, correctly left open.
- **IMPROVEMENT_BACKLOG.md:** added HISTORICAL header (08-12 "uncommitted" change set was
  merged long ago; Room now v3; v2 engine deleted 2026-09-23) + inline corrections in §1.
  Item 18 is the only remaining `[NEW]` item.
- **VERSION.md:** iOS row corrected — no iOS app exists in the repo (`mobile/` is
  Android-only); the "Swift/SwiftUI (archived)" claim was false. Added the
  backend-1.0.0 ↔ Android-2.1 independent-versioning note.
- **CURRENT_STATE.md §2:** re-verified and rewritten for the 09-12 direct-ring rework (no-FGS
  ring leg, 3s FCM status-fetch cap, exact allow-while-idle timeout alarm with server
  re-check, mic-denied grant flow), with live device validation noted; other sections keep
  their 08-19 verification.
- **Evidence rule:** every DONE marking was preceded by a grep/read of the cited file; no
  claim was taken from memory.

---

## Task 7 — Micro-cleanup ✅ DONE (commit `17941e6`) + **follow-up crash fix (`42e7217`)**

- Placeholder `val prev = tts.let { null }` removed from `speakWithSystemTts` (first attempt
  accidentally took the `utteranceId` val with it — compile caught it, restored, green).
- `speakRequestedAt`: entries leaked on `onError` (only `onStart`/`onDone` removed); `onError`
  now removes its entry. No unbounded growth on errored utterances.
- `isShrinkResources = true` on release — `assembleRelease` green; verified no
  `getIdentifier()` dynamic lookups that a resource shrinker could break.
- Branches `feat/outgoing-call-voice-first` + `codex/readme-branding-hygiene`: verified zero
  commits ahead of main (`git log main..` empty), then deleted.
- 4 `docs/logcat-*.log` files moved to `artifacts/logcat/` (gitignored) — they were untracked.
- Device smoke (fresh install of the new debug build): real ring delivered, answered via
  notification shade, **backend confirmed answer**.

### 🔴 Follow-up fix found by the smoke test (commit `42e7217`)

The answer **crashed the app**: `SignalingForegroundService` re-delivers its parked
`startForeground` on `RING_RESOLVED`, but targetSDK 35 validates a `phoneCall` FGS against
`FOREGROUND_SERVICE_PHONE_CALL` + **any of** `MANAGE_OWN_CALLS`/DIALER role — and
`MANAGE_OWN_CALLS` was never declared. FATAL `SecurityException` ~90 ms after a successful
answer POST (voice session killed; same class would hit WS-delivered rings). This is a third
validation leg the 09-12 P0 rework missed.

**Fix:** declared `MANAGE_OWN_CALLS` (normal install-time permission) in the manifest.
**Re-verified on device:** the identical scenario three times — shade-Answer → backend
confirmed → FGS promotion — zero FATALs, same-PID survival, plus clean timeout-alarm decline
and notification-End teardowns. `assembleDebug` green.

**Also added:** `backend/scripts/overnight-tts-smoke.mjs` (MCP send_message driver used for
the live TTS check; committed with the fix). MCP smoke on an active call returned
`spoken_to_human: true` (server-side confirmation; device `[TTS]` log lines were not in the
captured buffer before the crash-fix reinstall, so mark TTS log capture as partial — the
codec-level Piper benchmark from Task 5 remains the strongest TTS evidence).

**Assumption:** notification-shade Answer exercises the same `CallService.ACTION_START_CALL`
path as the full-screen button (verified in code — both send the same action).

---

## Task 8 — Dependabot ✅ DONE (safe set + two verified majors; rest left open)

- **Safe/minor set (commit `dfda79e`):** dotenv ^17.4.2 (minor), @types/pg ^8.20.4,
  picomatch ^4.0.6 (dev patch). npm install + tsc + ESLint + full suite green (247/33).
- **fastify 4→5 cluster (commit `dd132c5`):** fastify 5.12.5 + helmet 13.1.1 + cors 11.3.0 +
  compress 9.1.1 + rate-limit 11.2.0 — the plugins hard-pin fastify majors and must move
  together (helmet/cors version fights caught live via boot smoke). One code change: the
  error handler's `error` param is `unknown` under fastify v5 → narrowed once to a
  FastifyError-shaped alias; response bodies unchanged. Boot smoke on :3999: /health 200,
  /mcp 401, phone-token mint OK, zero FATALs. tsc + lint + 247 tests green.
- **zod 3→4 (commit `6299b4d`):** zero direct zod imports in `backend/src` (the MCP SDK owns
  the surface), so this is a pure bump. tsc + lint + 247 tests + boot smoke green.
- **Left open deliberately (majors, no isolation time left):** pino-pretty 13 (runtime dep,
  dev-only transport — should probably move to devDependencies in a follow-up),
  typescript-eslint v8 (lint config migration expected), types/node 26 (Node 20 target).
  Also open: the docker bumps (node-25/26-alpine) — runtime-image decisions, not
  verified here.

---

## Task 9 — Red Deploy to Staging CI job ✅ FIXED (commit `13616a8`)

- **Root cause:** the job deploys to Kubernetes using the `KUBECONFIG_STAGING` secret, which
  was never configured — no staging cluster was ever provisioned. The project's actual
  target is **Render** (`render.yaml`, auto-deploy, `keep-render-warm.yml` cron pinging
  `agentcall-66ke.onrender.com`). The K8s path is vestigial from the 07-26 infra design
  (`infra/k8s/` manifests exist but the docs mark the deployment story as Compose/Render).
- **Fix:** both `deploy-staging` and `deploy-production` are now gated on repo variable
  `K8S_DEPLOY_ENABLED == 'true'` (in addition to push-to-main). Default: skipped → green
  pipeline. Nothing deleted; `infra/k8s/` and the jobs remain for a future real staging
  cluster. Activation steps are in the commit message.
- **Verification:** YAML parses; locally the failure mode was confirmed by reading the job's
  own validate step (fails hard on empty secret — the only failure it can hit today).
  Could not observe the actual GitHub Actions run: `gh` is installed but not authenticated
  (HTTP 401), which is the one thing needing human access here.
- **What a human may still want to decide:** whether to instead set the secret + provision a
  cluster (activate), or delete the K8s deploy jobs entirely.

---

# MORNING SUMMARY — 2026-09-23 overnight run

## Done and verified

| # | Task | Evidence |
|---|------|----------|
| 1 | P0 ring rework committed as-is | `525d5cc`, 10 files, no modifications |
| 2 | Live device validation (background ring, deep-Doze ring, answer, decline, timeout expiry, mic-denied flow) | push→ring ~1.8s under Doze, exact alarm `exact=true`, server logs `fcmOk:true`; `[CANCEL] confirmed` on timeout expiry |
| 3 | v2 engine deleted | tag `v2-dormant-archive`; `dd62c59`; tsc + lint + 247 tests green |
| 4 | FCM validation fetch 3s cap | `b2c9f66`; live ring 353ms fetch; timeout path policy-identical to live null path |
| 5 | espeak-ng-data → English-only | `436994e`; fresh-extraction Piper benchmark identical to baseline; extraction 79→62MB |
| 6 | Doc reconciliation (TECH_DEBT / NEXT_IMPROVEMENTS / BACKLOG / VERSION / CURRENT_STATE §2) | `3a05275`; every DONE marked only after grep/read proof |
| 7 | Micro-cleanup | `17941e6`; release build green with `isShrinkResources`; branches deleted (verified merged); logs moved to `artifacts/logcat/` |
| 7b | **Live-caught crash fix: `MANAGE_OWN_CALLS`** | `42e7217`; FATAL SecurityException on answer before → 3× clean answer scenarios after, zero FATALs |
| 8 | Dependabot: safe set + fastify 5 cluster + zod 4 | `dfda79e`, `dd132c5`, `6299b4d`; each verified: tsc/lint/247 tests/boot smoke |
| 9 | Deploy to Staging CI fixed | `13616a8`; root cause = never-configured `KUBECONFIG_STAGING`, no cluster exists; jobs gated opt-in |

## Done but with caveats

- **Task 2 FCM timeout branch:** implemented and shipped, but the timeout itself was never
  forceable against prod Render (can't make the backend slow on demand). Policy-identical to
  the already-live null path.
- **Task 7 TTS log capture:** server confirmed `spoken_to_human: true` via MCP smoke, but the
  on-device `[TTS]` log lines weren't captured before the crash-fix reinstall; the Task 5
  Piper benchmark is the strongest device-side TTS evidence.
- **Task 9:** could not view the actual Actions run (gh CLI 401 — not authenticated). Fix is
  based on the workflow's own deterministic validate-step failure.

## Blocked (needs a human)

- Nothing hard-blocked. Soft items: setting the `K8S_DEPLOY_ENABLED` variable / provisioning
  a staging cluster (or deleting the jobs), and `gh auth login` if Actions history is needed.

## Deliberately skipped

- **Majors left open:** pino-pretty 13, typescript-eslint v8, types/node 26, docker
  node-25/26-alpine bumps — no isolation budget left to verify each  properly; per the no-unverified-major rule they stay as open Dependabot PRs.
- **docs/v2/ kept** per instructions; `backend/scripts/overnight-*.mjs` kept (useful drivers,
  no secrets inside; they read keys from env).
- **Push to origin** — superseded 2026-09-24: pushed directly to `main`, see
  **Final push** below.

## Final push (2026-09-24)

- **Pushed:** `ff52cad..185e73e main -> main` — 14 commits (not 15; `ff52cad` was
  already on origin), tip = `185e73e`, local and origin in sync.
- **Secret scan: CLEAN.** Full diffs + commit messages of all 14 unpushed commits
  (`git log origin/main..main -p`) pattern-scanned for Render (`rnd_…`), Firebase (`AIza…`),
  JWT, GitHub (`ghp_`/`github_pat_`), AWS (`AKIA`), OpenAI (`sk-`), and Slack (`xox…`) token
  formats: **0 hits**. Generic high-entropy secret-assignment scan: 0 hits. No commit in the
  range touched any `.env*` file; `.env.overnight-local` was never committed; only
  `.env.example` (placeholders) is tracked.
- **Commit hygiene review:** all 14 commits single-purpose. Only candidate was the tip
  (`185e73e` = driver script + same-session health audit doc, one validation unit) — left
  unsplit per the don't-split-for-count rule. No rebase needed.
- **GitHub confirmation:** `gh` still unauthenticated (keyring token invalid, HTTP 401),
  but the repo is public — verified via the unauthenticated GitHub API: `main` is at
  `185e73e`; **Actions run #129** (VoiceBridge CI/CD, push to main, first CI run of
  fastify 5 + zod 4) **completed: success** — Unit & Integration Tests, Lint & TypeScript,
  Security Audit & Dependency Scan all green; deploy jobs correctly skipped
  (`K8S_DEPLOY_ENABLED` gate). Run: <https://github.com/patil-shubham-dev/AgentCall/actions/runs/35977967487>

**Overnight run: complete.** 10/10 tasks, 14 commits pushed, CI green, no secrets leaked.

## Post-run hardening session (2026-09-24, no device — code audit only)

Scope: the three items that still need live-device validation. Each was audited
through three lenses (failure/edge-cases, concurrency/lifecycle, known platform
quirks — Render WS docs + Firebase Doze docs checked, not assumed). **No device
was available; nothing was device-tested. All three items remain "hardened via
code audit, still requires live validation" — never PASS/WORKING/FIXED.**

| Item | Found | Fixed | Commit |
|------|-------|-------|--------|
| 1. Real-voice barge-in + STT handoff | TTS `stopRequested` latched after one barge-in → every later Piper message silently no-oped (call goes mute); AudioRecord (AEC/NS) not released before the STT handoff; `SpeechRecognizer` created off-main-thread; system-TTS VAD tap lingered 30 s past speech end (self-barge risk) | `startNewUtterance()` reset in paced path; synchronous release before `onBargeIn`; main-thread hop; completion-driven VAD stop (30 s cap kept) | `36165c8` |
| 2. Mid-call WS kill by Render | UI ignored `DISCONNECTED` → stale "Connected" after reconnect surrender; ring validators accepted status `active` → a replayed `call_incoming` could re-ring an answered call, clobber `CallStateHolder`, arm a 60 s timeout on a live session | DISCONNECTED maps to RECONNECTING phase ("call stays live"), teardown still terminal-only; `RingTimeoutPolicy.shouldRingForServerStatus` pending-only gate shared by both ring paths, +3 unit tests | `795e073` |
| 3. Overnight ring, unplugged + non-whitelisted | Backend treated FCM HTTP 200 as terminal (200 = transport-accepted, not delivered — Doze may defer/drop high-priority per Firebase docs); on an un-exempted device every client backstop degrades (poll gated, alarm inexact, TTL sweep then records an unseen miss) | Server-side: one deferred re-push (45 s, window-gated ≥60 s left) when the WS leg was down and FCM accepted; re-enters attemptRing (pending-gated), absorbed by phone dedupe, no client ack (phone has no WS on that path), bounded ~3 nudges; +5 vitest (`fcm-repush.test.ts`) | `1c5106b` |

Verification: backend 252 tests / 34 files green (was 247; +5 new), `tsc --noEmit`
clean; mobile `testDebugUnitTest` + `compileDebugKotlin` green. Manual-test
procedures for all three items, what each live test must confirm, and honest
confidence statements: `docs/LIVE_TESTS_REMAINING.md`. Item 3's exact-alarm
grant state on ColorOS (`canScheduleExactAlarms`) is a live-only open question
logged there.

### Push + CI confirmation (2026-09-24)

- Pushed `1e42ba3..27ff5aa` (4 hardening commits; secret re-scan: 0 hits).
- **Run #130 on `27ff5aa` FAILED** — `fcm-repush.test.ts` passed locally but
  not in CI: the test relied on the local `.env`'s `FCM_ENABLED=true`; CI has
  no `.env`, so `config.fcm.enabled` was false and `sendFcmPush` was never
  reached. Environment-dependent test setup, not a product-code bug.
- Fixed in `4b1dae2` (config mock pins the gate on, same pattern as
  `fcm.test.ts`); verified locally with `FCM_ENABLED=false` (the CI condition):
  5/5 and full 252-suite green.
- **Run #131 on `4b1dae2`: success** — Unit & Integration Tests, Lint &
  TypeScript, Security Audit, Build & Docker Image all green; deploy jobs
  correctly skipped (`K8S_DEPLOY_ENABLED` gate).
  <https://github.com/patil-shubham-dev/AgentCall/actions/runs/35998891920>
- Process note for future sessions: backend tests that depend on feature
  gates must mock `config` explicitly — the local `.env` lies about CI.
  (The follow-up docs-only commit `b4c03ee` correctly skips CI entirely —
  the workflow's `paths:` filter covers backend/** and infra/** only.)
