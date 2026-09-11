# Live Validation — Heartbeat/Abort Decoupling Fix — 2026-09-11

**Task:** live end-to-end validation of the 2026-09-09 heartbeat/abort decoupling change
(session-registry `AgentGoneCause`, ai-wait durable persistence), real device, real call.
**Outcome: live tests NOT RUN — blocked at setup. No fix attempted (per instructions).**
This report documents exactly what was verified, what blocks each test, and what is needed
to unblock. It also contains the requested v2 dormant-code recommendation.

**Report written:** 2026-09-11 ~15:25–15:35 UTC (PC clock).
Device clock at check time: `Fri Sep 11 20:53:58 IST 2026` (= 15:23:58 UTC — agrees with PC UTC).

---

## 1. SETUP VERIFICATION (per-test-run prerequisites)

| # | Check | Verdict | Evidence |
|---|---|---|---|
| S1 | Android device connected via USB (`adb devices`) | **PASS** | `NZUSAIFEPHPNX8W4PV device product:RMX3867IN model:RMX3867 device:RE5C86L1` (via `C:\Users\91808\AppData\Local\Android\Sdk\platform-tools\adb.exe`; `adb` is not on PATH — full path required). Note: task PC has no `adb` on PATH; `where.exe adb` finds nothing. |
| S2 | App installed on device | **PASS** | `pm list packages` → `package:com.agentcall.app`. |
| S3 | `adb logcat` capture to file works | **PASS** | `docs/logcat-live-validation-20260911-152353.log` (4,253,040 bytes, threadtime format). Capture job was scoped to its shell session and has exited — no runaway process; file retained as proof capture works. Re-run filtered (`com.agentcall` tags) for the real test run. |
| S4 | Backend running in the SAME environment the phone points at | **FAIL (mismatch)** | Phone `DEFAULT_HOST = "agentcall-66ke.onrender.com"` (`mobile/android/app/build.gradle.kts:23`); app base URL = `https://<host>/api/v1/`, port 4000 only for IP hosts (`ApiClient.kt:62-78`). Render prod `GET /health` → `{"status":"ok","timestamp":"2026-09-11T15:22:07.906Z","version":"2.0.0"}` — prod is UP. **No backend process listens locally** (all `node.exe` PIDs are opencode MCP servers — nvidia-vision / image-generation; zero LISTEN sockets owned by node; local `PORT=4000` free). So the only backend the phone talks to is Render prod. |
| S5 | On-device override of server host | **UNKNOWN** | Release build — `/data/data/com.agentcall.app/shared_prefs/agentcall_host.xml` not readable (`NO_SHARED_PREFS_ACCESS`). Cannot confirm whether Settings > server-host was ever overridden. Needs the human to confirm (assumed stock → Render prod). |
| S6 | Backend server logs available for `AgentGoneCause` / sweep evidence (required by TEST 1, TEST 3) | **FAIL** | Prod logs live on Render (no access from here). Local backend not running, so no local logs either. TEST 1 requires citing which `AgentGoneCause` fired and that no `cancelCallsByAgent`/`forceDisposeAiWaits` ran — impossible without server logs. |
| S7 | MCP auth credential for creating real calls against the test backend | **FAIL (missing)** | `POST /mcp` without token → `401 Unauthorized` (endpoint live, auth enforced). Prod needs service token or AI key (Settings > Add AI). Neither is available in this session (local `backend/.env` `SERVICE_TOKEN` is dev-only and does not apply to prod). This environment also has **no AgentCall MCP tools** (`create_call`/`send_message` are not installed here) — calls would have to be driven as raw MCP-over-HTTPS, which likewise needs the credential. |
| S8 | **The fix under test is deployed where the phone points** | **FAIL (blocking)** | The 2026-09-09 change is **uncommitted working-tree only**: `git status --short` shows `M backend/src/mcp/session-registry.ts`, `M backend/src/mcp/endpoint.ts`, `M backend/src/voicebridge/service.ts`, `M backend/src/voicebridge/types.ts`, `M backend/src/index.ts`, `M backend/src/mcp/tools.ts`, `M` 5 test files, `?? mcp-heartbeat-noabort.test.ts`, `??` the two 09-09 audit docs. `git log --all --since=2026-09-08` is **empty** (covers `origin/main` too) — the fix exists on no branch, local or remote. `render.yaml` builds the Docker backend from git (default branch `main`, auto-deploy). **Therefore Render prod cannot be running the fix; it runs pre-fix code where silence-triggered sweeps still abort calls.** A live test against prod today would exercise the OLD behavior, not the fix — any TEST 1 result would be meaningless as validation. Fix diff stat: 11 files, +214/−77, and it needs **no DB migration** (ai-wait fields ride in the `sessions.data` JSONB blob — `service.ts` `persistAiWaitState` comment; verified in diff). Deploying = code push only, but it is still a **production deploy requiring explicit approval** (not done in this session). |

**Setup conclusion:** S1–S3 pass (device + logging viable). S4/S6/S7/S8 fail: the phone talks to pre-fix prod, prod logs are unavailable, and there is no MCP credential. Running the live tests now would produce invalid results. **Stopped here, per the task's stop-and-report rule. Nothing was changed, fixed, committed, or deployed.**

---

## 2. TEST 1 — SILENT-AGENT, CALL MUST SURVIVE — **NOT RUN (blocked)**

**Blockers:** S8 (prod lacks the fix — a silence window would test old abort behavior), S7 (no credential to create the call), S6 (no server logs to cite `AgentGoneCause`).

**Static facts gathered for the eventual run (code as in working tree):**
- Cause taxonomy confirmed: `AgentGoneCause = 'explicit-delete' | 'liveness-timeout' | 'idle-timeout'` (`session-registry.ts:27`); only `explicit-delete` reaches `forceDisposeAiWaits` → `cancelCallsByAgent` (`endpoint.ts:146-156`); sweeps log `calls untouched, call-level sweeps own termination` (`endpoint.ts:148`).
- Thresholds that define the silence window: liveness `MCP_LIVENESS_TIMEOUT_MS` default **45s** (sweep every 5s), idle `MCP_SESSION_IDLE_MS` default **30 min** (sweep every 60s) (`config.ts:63-80`). A 3–4 min real silence crosses the liveness sweep but not the idle sweep — so on fixed code the expected backend evidence is a `liveness-timeout` presence event with calls untouched; on prod (pre-fix) the expected result is the bug (call aborted ~45s after silence). This also cross-checks the phone banner expectation ("AI is not currently responding").
- Note: the liveness sweep only closes sessions whose agent still has open calls (`sweepDead` + `hasOpenCalls` gate, `session-registry.ts:135-164`) — the test call must be in pending/active/paused for the sweep path to fire at all.

**To unblock:** deploy fix to a backend the phone talks to (option A/B below) + MCP credential + human to answer/observe banner + server-log access for that backend.

## 3. TEST 2 — EXPLICIT DISCONNECT STILL ABORTS — **NOT RUN (blocked)**

**Blockers:** same as TEST 1 (S7, S8).

**Static facts gathered (no guessing, per instructions):**
- The real client-initiated disconnect path is transport close after `DELETE /mcp`: `activeSession.transport.onclose = () => { sessions.delete(generated); … }` (`endpoint.ts:249-252`) → `delete()` → `notifyIfLastGone(agentName, 'explicit-delete')` (`session-registry.ts:66-73`) → abort path (`endpoint.ts:151-155`). So the TEST 2 trigger is exactly `DELETE /mcp` with the `Mcp-Session-Id` header on the test session.
- TEST 2 is runnable against **pre-fix prod** too (explicit-delete path predates the fix and is behaviorally unchanged by it), but doing so burns a real answered call while S7 still blocks call creation — so no partial run was possible either.

## 4. TEST 3 — MCP PING BEHAVIOR DURING SILENCE — **NOT OBSERVED (blocked)**

**Blockers:** S6 (needs backend request/connection logs), plus no silence window was run.

**Static facts (framing only, no editorializing):** `notifications/ping` refreshes only `lastHeartbeatAt`, never `lastActivityAt` (`endpoint.ts:194-198`, `session-registry.ts:93-96`), and the header comment states the 30-min idle sweep is deliberately un-delayable by pings (`session-registry.ts:10-13`). Whether the OpenCode MCP client emits pings during exploration remains unobserved — to be read from backend logs during the TEST 1 window in the unblocked run.

## 5. TEST 4 — RESTART RECOVERY — **NOT RUN live; feasibility assessed**

- **Needs no DB migration** (JSONB-blob persistence — see S8). `restoreAiWaits()` is wired at boot after the stale-session sweep (`index.ts` diff hunk, `+9` lines) and revives only open calls with unexpired deadlines — ordering and expiry-skip verified in the diff.
- **Live-on-phone variant is likely untestable as described:** restarting the backend kills the MCP session transport and (for prod) the WS/FCM-adjacent server state the live call depends on; the "same call survives a backend restart on the phone UI" expectation is not something the fix claims (the fix claims the *lease fact* survives on the row for `getAiWaitStatus`/status checks post-`restoreAiWaits()`, not uninterrupted phone UI). Recommend scoping TEST 4 to the log/API level even in the unblocked run: pre-restart `GET` call detail shows `ai_wait.active` + deadline; post-restart logs show `[aiWait] restored wait leases from persisted session rows`; post-restart `GET` shows the same lease/deadline.
- Local-backend variant is vacuous without a durable repo (local `.env` has no `DATABASE_URL`; in-memory/file repo does not survive process restart by definition) — TEST 4 needs the fix deployed against a **database-backed** backend (prod `PERSISTENCE_MODE=database`, or local + Postgres).

---

## 6. What is needed to unblock (decision for the human)

- **A. Deploy the fix to Render prod** (commit + push; auto-deploy; no migration needed; ~minutes on free plan; verify `/health` + version/marker after). Then live tests run against prod with real FCM rings. Risk: prod deploy + real-call testing on prod data.
- **B. Run the fixed backend locally + `adb reverse tcp:4000 tcp:4000` + set phone Settings host to `127.0.0.1`** (reversible via Reset-to-default). Then create an AI key against local, run all four tests locally. Risk/caveat: FCM rings originate from the local backend via the same Firebase project — the phone's `ringFromEvent` host validation may drop rings for a non-production host (see `ApiClient.kt:42-47` persistence comment); needs checking if rings don't arrive. No prod impact.
- **Either way:** an MCP credential (AI key) for the test backend, server-log access for it, and a human present to answer calls and read the phone UI (banner vs aborted, same-callId check).

---

## 7. OPEN QUESTION — V2 DORMANT CODE: RECOMMENDATION **(b) DELETE FROM THE TREE**

*(Recommendation only — nothing deleted in this session. Basis: `docs/AUDIT_V2_READINESS_2026-09-09.md` re-verified fresh today: **zero changes under `backend/src/v2/`** in the working tree — `git diff --stat -- backend/src/v2/` empty; the only `index.ts` hunk is the v1 `restoreAiWaits()` boot call; `ENGINE_V2`/`PERSISTENCE_MODE=v2` still configured nowhere including `render.yaml`.)*

**Recommendation: delete `backend/src/v2/` (+ v2 tests, v2 route registration, v2 schema applier wiring) in a dedicated commit, keeping `docs/v2/` and both audit docs as the design record, ideally with a `v2-dormant-archive` tag on the pre-deletion commit so resurrection is one `git checkout` away.**

Reasoning, weighed honestly:

1. **Deletion here is reversible; retention costs are ongoing.** `git rm` destroys nothing — full history remains. The real question is only whether the code earns its carrying cost in the tree. The main risk people fear from (b) ("losing the head start") is therefore much smaller than it looks, while the costs of (a) accrue every week.
2. **Drift has already started — observed in this very session's fix.** v1 just gained deadline-based ai-wait leases, durable `aiWait*` row fields, and abort-decoupling semantics. v2's `turn.lease` has *no expiry semantics* (`active_until: null` always, `call-service.ts:1039-1054`), its FSM has *no `cancelled`/`aborted` states* (`call-fsm.ts:7-14`), and its silence policy is advisory-only. The two engines are now semantically divergent on exactly the lifecycle questions a future merge would need them to agree on. Every further v1 lifecycle fix widens this gap.
3. **The "head start" is mostly design knowledge, not runnable advantage — and the knowledge is already preserved outside the code.** The audit sizes a real switchover as Large regardless: ring/FCM consumer, per-user active-call query, pending-ring TTL, pause/callback drivers, `send_message_and_wait` replacement (largest single item), phone REST migration (every `ApiService.kt` call site), `ai_wait` push shape, caller badge, cancel semantics. That unbuilt layer is precisely where a resumed effort would diverge from today's v2; the built layer (event log, SSE replay, HTTP idempotency, rehydrate/verifier, barge-in/silence designs) would substantially survive — but as *reference design*, which `docs/v2/` + the 240-line readiness audit already capture better than source does. A months-later streaming-TTS effort would design fresh against real transport constraints anyway (the audit's own §5 conclusion).
4. **Concrete ongoing costs of (a):** onboarding confusion (two call models, two persistence stories, two lease semantics); the `ENGINE_V2` flag mirage (it changes v1 wait math while sounding like it routes to v2 — audit §1.6); accidental-coupling surface (`index.ts` constructs both; a future contributor will wire them together casually); CI/test load (11 v2 test files, PG-gated suites needing a dedicated DB).
5. **The honest counter-case:** v2's core is well-tested, has zero TODO markers, and its Postgres seq/advisory-lock plumbing + RPO-0 recovery suites are genuinely good work that a fresh rewrite would take weeks to re-earn. If streaming-TTS is **staffed and scheduled in the next ~4–8 weeks with an owner**, keep dormant instead — but then fix the carrying costs explicitly: a `backend/src/v2/README.md` "DORMANT — not live traffic" marker, CODEOWNERS, and an `ENGINE_V2` rename/clarification. Dormant without ownership is just slow deletion with extra confusion.

**Bottom line:** default to (b) delete-from-tree (history + docs preserve everything worth keeping), unless streaming-TTS has a near-term owner — in which case (a) keep is justified only with explicit dormant-ownership markers. Final call is yours.

---

## 8. Timestamps & artifacts cited

- PC UTC at health check: `2026-09-11T15:22:07.906Z` (server timestamp in prod `/health` body; request made ~15:22 UTC).
- PC UTC at device check: `2026-09-11T15:23:57Z`; device `date`: `Fri Sep 11 20:53:58 IST 2026` (IST = UTC+5:30 — consistent).
- `adb` full path: `C:\Users\91808\AppData\Local\Android\Sdk\platform-tools\adb.exe` (not on PATH).
- Logcat proof file: `docs/logcat-live-validation-20260911-152353.log` (4,253,040 bytes; unfiltered `*:V` — use tag filters next run).
- Git: branch `main`, HEAD `cf44131` (2026-09-08); fix uncommitted (`git status`: 10× `M`, 3× `??`); `git log --all --since=2026-09-08` empty.
- No unit tests were run in this session (out of scope per task); no code changed; no deploy performed.
