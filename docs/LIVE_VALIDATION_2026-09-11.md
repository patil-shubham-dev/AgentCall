# Live Validation — Heartbeat/Abort Decoupling Fix — 2026-09-11

**Scope:** live end-to-end validation of the 2026-09-09 decoupling change
(`McpSessionRegistry` `AgentGoneCause`: only `explicit-delete` aborts calls;
`aiWaitActiveUntil`/`aiWaitCount`/`aiWaitLastActiveAt` persisted to the session
row + `restoreAiWaits()` at boot). Real device, real calls, no unit tests.

## Verdicts

| Test | Verdict |
|---|---|
| TEST 1 — silent agent, answered call must survive | **PASS** (phone + server evidence) |
| TEST 2 — explicit disconnect still aborts | **PASS** (with one important nuance, §4) |
| TEST 3 — MCP ping behavior during silence | Observation recorded, no editorializing (§5) |
| TEST 4 — restart recovery | **PASS at API/log level**; live prod restart explicitly out of scope (§6) |
| FCM production bug (found during setup) | **Found, fixed, physically confirmed** — separate finding (§2) |
| Full-screen-intent check (extra request) | Finding recorded (§7) |
| V2 dormant-code question | Recommendation: **(b) delete from tree** (§8) |

**No fix was attempted for any TEST failure** (nothing failed). One production
bug found en route (FCM) was fixed as explicitly instructed mid-session.

**Clock basis:** all timestamps UTC unless marked IST (device = UTC+5:30;
verified `Fri Sep 11 20:53:58 IST` = `15:23:58Z` at check time).

**Environment:** phone `RMX3867` (`NZUSAIFEPNX8W4PV`, package
`com.agentcall.app`) over USB; backend = Render prod
`https://agentcall-66ke.onrender.com` (service `srv-d9jqt3km0tmc73bhm450`,
free plan, `PERSISTENCE_MODE=database`, Postgres on Neon). Phone
`DEFAULT_HOST` points at the same prod host. Deploys during session:
`ae7e6f1` (the 09-09 fix, pushed 15:34:38Z, manually deployed ~15:44:56Z),
`86f530b` (FCM secret fix, deployed 17:24:03Z → live 17:25:27Z as
`dep-dai3icu1egvs73daokqg`). Test identity: dedicated AI key
`LiveVal-2026-09-11` (minted via `POST /api/v1/ai/keys` with a phone token,
**deleted after the run** — key list verified back to the 5 pre-existing
keys, none `busy`). MCP driven as raw Streamable-HTTP (this harness has no
AgentCall MCP tools); phone driven via `uiautomator` XML dumps + `input tap`
(coordinates always derived from dumps, never assumed).

---

## 1. Setup note (supersedes the morning's BLOCKED report)

The first half of the day was blocked: the fix was uncommitted (working tree
only) while the phone talks to Render prod, no MCP credential was at hand,
and prod logs were unreachable. All three were resolved: fix committed and
manually deployed, the opencode `agentcall` MCP entry supplied the credential
pattern (a dedicated test key was minted for isolation — the shared `Opencode`
key was online and active, which would have contaminated the silence test),
and a Render API key unlocked server logs, env vars, and deploy control.
The initial `docs/LIVE_VALIDATION_2026-09-11.md` captured the blocked state;
this rewrite is the final record.

---

## 2. FINDING — FCM push was completely broken in prod (fixed, confirmed)

**What was broken:** every FCM ring send from prod died with
`[fcm] transport error (kept token, retry next ring)`. With the phone's WS
parked (FCM-only idle design) and Home polling only `health` + `ai/keys`
(never `active-call`), **no backgrounded/killed-app call could ring any phone
at all**. Three consecutive sends for call `62c86a2a` failed identically
(15:48:00/15/30Z), each retried on the 15s ring cadence.

**Root cause:** `FIREBASE_SERVICE_ACCOUNT_PATH=firebase-service-account.json`
(relative) can never resolve inside the Docker image — `backend/secrets/` is
not copied in. The key already existed as a Render Secret File (mounted at
`/etc/secrets/<filename>` at runtime), but nothing ever looked there, and
`validateConfig` only checked the variable was *set*, not that the file was
*readable* — so boot passed and every send failed late with an empty error.

**Why Secret Files and not a Dockerfile COPY:** baking the JSON into the image
leaves key material extractable from layer history forever (deleting it in a
later layer does not remove it). The fix (`86f530b`,
`fix(fcm): resolve service-account key via Render Secret Files mount`):
`resolveSecretFilePath()` falls through to `/etc/secrets/<basename>` when the
configured path is absent (local dev untouched — file exists where
configured); `validateConfig` fail-fasts at boot when FCM is enabled but the
resolved key is unreadable; the effective key path (never key material) is
logged once at auth init; the Dockerfile grants `appuser` group-1000
membership so the mount is readable (no new packages, no secrets in any
layer); 7 new unit tests, full suite 321 green, lint clean. No base64 env var
was needed — Secret Files were available and already populated on this plan.
No dashboard env changes were needed either.

**Proof it worked:** first send after deploy logged
`serviceAccountPath=/etc/secrets/firebase-service-account.json` then
`[fcm] ring push delivered … fcmMessageId=projects/agentcall-prod/messages/0:1789147672081359%…`
(17:27:52.224Z, 576 ms), `fcmOk:true`. **Physical confirmation:** the device
posted the `incoming_call_v2` notification (2 actions) + signaling foreground
service (observed via `dumpsys notification`), and the watching owner
confirmed hearing/seeing the ring. Boot logs for the new instance are clean
(no FCM fail-fast, sweepers started, persistence verifier clean).

**Standing rule adopted:** never fetch or print full secret-file contents via
the Render API again — metadata/existence/non-sensitive fields only. (One
secret-files query early in the session returned plaintext key material into
the transcript. Rotation was explicitly declined by the owner; recorded here
as decided, not as an open item.)

---

## 3. TEST 1 — silent agent, answered call must survive — PASS

Call `87f4a28e-c0be-4f16-8606-76282481c81b` (`liveval-t1d`).

- 17:51:05Z created (MCP, session `ac94022b`); ring gate `presence_has_agent`,
  FCM delivered 17:51:05.238Z (`fcmOk:true`, 120 ms).
- 17:51:14Z answered by adb tap at dump-derived coordinates (middle circle
  center 540,1927 — layout verified on two independent dumps); server
  `POST …/answer` → `Call session answered` 17:51:16.423Z. Phone showed the
  in-call screen: `AI Agent / Connected / via AI harness / 00:32` plus the
  exact banner **`AI is not currently responding — your reply will be saved`**.
- 17:53:22Z initial `send_message` delivered (re-init session `f076b005`;
  the first session had already been swept at 17:51:53Z with
  `cause:liveness-timeout`, calls untouched — expected). **Silence window
  17:53:22Z → 17:57:56Z (4m34s): zero MCP/REST traffic from the test
  identity** (presence `last_seen_at` frozen at `17:53:23.787Z` throughout);
  genuine repo exploration done meanwhile (`v2/recovery.ts`,
  `ai-keys.ts` online-window read).
- During silence, backend 17:54:13.152Z:
  `agentName=LiveVal-2026-09-11 cause=liveness-timeout —
  [MCP] agent presence lost (sweep); calls untouched, call-level sweeps own
  termination` + `closed:1, timeoutMs:45000`. **Zero** `cancelCallsByAgent` /
  `forceDisposeAiWaits` / abort lines in the whole 17:51–17:59 window.
- Phone mid-silence (dump 17:55:36Z) and end-silence (dump 17:57:56Z): timer
  advancing `04:18` → `06:42`, banner still shown, message bubble + answer
  input present. No abort, no ended state.
- Post-silence 17:58:34Z: session re-initialized (old one swept — normal MCP
  recovery, `SESSION_NOT_FOUND` on the dead session first), `send_message`
  200. Dump 17:59:22Z: **new bubble visible on the same screen, timer `08:04`
  still running** — delivered to the same call, not a new one. Server
  `Message added to session` 17:58:35Z for the same callId.
- 18:04:50Z call completed via `complete_call` (cleanup).

**Method notes:** (a) the adb-forced answer bypasses the notification-tap
step — it does not validate notification taps; (b) no AI message may be sent
before the phone answers — `addMessage` flips a `pending` call to `active`
server-side (`service.ts:681-686`), which desyncs a still-ringing phone (this
is what produced the confusing `CallAnswered` 17:39:04Z on the earlier
`e2439048` attempt; choreography fixed from 1d onward);
(c) the 60s phone-side `RING_TIMEOUT` bounds answer automation — two early
attempts missed it (tap round-trips ~40–100s); the passing run tapped 12s
after create.

---

## 4. TEST 2 — explicit disconnect still aborts — PASS (with nuance)

First attempt (`f72e1ff7`): inconclusive as designed — the DELETE at +82s hit
an already-swept session (404) after the phone's 60s ring timeout had already
cancelled the call (server `POST …/cancel` 18:08:11Z). Logs did confirm the
liveness sweep is presence-only even for ringing calls.

Second attempt (`b4bf0b34`, DELETE 200 on a 6s-old live session): explicit
path **fired** —
`[MCP] last session for agent closed explicitly; aborting open calls`
(18:09:47Z) — but the call was **not** aborted:
`[agent-disconnect] skipping dispatched call (MCP disconnect cannot cancel
after ring)`. Status stayed `pending`; notification remained.

**Nuance (pre-existing design, not this fix):** since `a127574`, a call whose
ring already dispatched (`ringDispatchedAt` set) is immune to
disconnect-abort — deliberately, so a deliberate agent shutdown cannot kill a
call the user is already being rung for. The 09-09 diff did not touch this
decision (verified: skip logic predates the fix; endpoint diff only reworded
comments/wiring around the same `forceDisposeAiWaits → cancelCallsByAgent`
chain).

Decisive run (`e512e5b4`, REST-created with **no live MCP session**, ring gate
`ready:false reason:presence_missing_agent_offline`, retry scheduled, never
dispatched; session opened then `DELETE /mcp` 200 at +5s):
`[MCP] last session for agent closed explicitly` 18:13:00.388Z →
`call_aborted` push queued → `Call session aborted, reason:agent_disconnected`
→ `[agent-disconnect] sweep result: aborted:1, skippedDispatched:0`. API
status **`aborted`**; ring-retry teardown clean (`no pending session,
abort`, no resurrection). **Explicit-delete aborts exactly as before — PASS.**

---

## 5. TEST 3 — ping behavior during silence (facts only)

- The test client (raw Streamable-HTTP, full control) sent **zero**
  `notifications/ping` or any other traffic during the 17:53:22–17:57:56Z
  window — silence was total by construction, and presence `last_seen_at`
  frozen at `17:53:23.787Z` corroborates it.
- The agent-online indicator source (`GET /api/v1/agents/:id/status`) read
  `online:true` at both mid- and end-silence: `online` = live MCP session **or**
  key-authenticated within `ONLINE_WINDOW_MS` = **5 min** (`ai-keys.ts:44`);
  the 45s sweep fires underneath without moving the indicator. No flicker.
- Whether the owner's separate OpenCode client emits pings during such
  windows is **unobserved**: backend request logs do not distinguish
  ping-notifications from tool calls, and that client is a different agent
  identity. Stating plainly as requested, no conclusion drawn.

---

## 6. TEST 4 — restart recovery (API/log level) — PASS as scoped

- Mid-`send_message_and_wait` (25s window) row read:
  `ai_wait={active:True, activeUntil:2026-09-11T18:02:53Z}` — the lease **with
  deadline is on the durable row** while the wait is live.
- Wait returned `outcome:timeout` (call still `active`); post-wait row:
  `ai_wait={active:False}` — dispose clears the durable fact (matches the
  `clearPersistedAiWait` design).
- A live prod restart was **deliberately not performed** (Render restart =
  redeploy: drops the live call and every session; the task allows log/API
  level instead). `restoreAiWaits()` boot wiring (runs after the stale sweep,
  revives only open calls with unexpired deadlines) is as committed, and its
  revive/skip-expired/skip-terminal branches are covered by
  `mcp-heartbeat-noabort.test.ts`. No migration is ever needed (fields ride
  the `sessions.data` JSONB blob).

---

## 7. FINDING — full-screen intent is correctly implemented (no gap)

Checked because the test-push ring surfaced as notification-only on an
unlocked phone. Code review: manifest declares `USE_FULL_SCREEN_INTENT`;
`CallService` builds the ring notification with `CATEGORY_CALL`,
`CallStyle.forIncomingCall` (Answer/Decline), **unconditional**
`setFullScreenIntent(pi, true)` plus a `setContentIntent` fallback, with a
comment explaining exactly the inert-notification failure mode. All present.
The observed notification-only behavior on an unlocked, in-use phone is
standard platform behavior (full-screen fires when the device is idle/locked;
otherwise heads-up, which auto-dismisses and was simply missed by the +30s
dump). **Not a user-facing gap, not an OEM quirk — no action.**

---

## 8. V2 dormant code — recommendation (b) DELETE FROM THE TREE

Unchanged from the morning's analysis; the day's evidence strengthens it:
`backend/src/v2/` received **zero** changes today (`git diff --stat --
backend/src/v2/` empty), while v1's lifecycle semantics moved again (FCM
dispatch immunity, abort-decoupling live-verified) — the drift cited in the
morning report is actively widening. Delete `backend/src/v2/` (+ v2 tests,
route registration, schema wiring) in one commit, keep `docs/v2/` and both
09-09 audits as the design record, tag the pre-deletion commit
(e.g. `v2-dormant-archive`) so resurrection is one checkout away. Reversible
via git; the "head start" that matters (event-log/SSE/idempotency design) is
preserved in docs, while the unbuilt transport/tool-binding layer — where any
resumed effort would diverge anyway — is not lost by deleting code. Only
keep dormant instead if streaming-TTS gets a staffed owner in the next
~4–8 weeks, and then only with an explicit `DORMANT` marker + owner.
**Nothing deleted in this session** — recommendation only, final call is the
owner's. (Note: `ENGINE_V2`/`PERSISTENCE_MODE=v2` remain set nowhere,
including prod.)

---

## 9. Incidental observations (not verdicts)

- **Stale-decline race (observed, pre-existing):** on attempt `e2439048`, an
  AI `send_message` auto-answered server-side while the phone still showed
  RINGING; the phone's 60s ring timeout then POSTed `/cancel` against the
  now-active call (17:40:26Z). The in-UI stale-decline guard (Backlog item 14)
  does not cover the FGS-timeout path. Worth a backlog item; not caused by
  this fix, not fixed here.
- **CI is red on `main`, pre-existing:** `VoiceBridge CI/CD` fails identically
  on `ae7e6f1` and `86f530b` — Lint/TypeScript ✅, tests ✅, build ✅,
  security ✅, **`Deploy to Staging` ❌** (Production skipped). Infra/secret
  issue with the staging target, unrelated to either commit.
- **Secrets hygiene:** per standing instruction, no secret-file contents were
  fetched or printed after the one early incident (recorded in §2); all
  Render API citations above are metadata/log lines only.
- **Leftovers:** test key deleted; all test calls terminal (`completed` /
  `cancelled` / `aborted`; pending-TTL reaped the rest); one phone token
  minted for `solo-user` remains valid (no revoke endpoint found — harmless,
  test-only); 4.2 MB + 14.5 MB logcat captures and per-step UI dumps retained
  on disk under `docs/*.log` (gitignored) and the temp dir, not committed.

## Artifacts & exact anchors

- Backend logs via Render API (`srv-d9jqt3km0tmc73bhm450`): create/dispatch
  17:51:05Z; answer 17:51:16Z; sweeps 17:51:53 / 17:54:13 / 17:59:23Z;
  messages 17:53:23 / 17:58:35Z; TEST-2 abort chain 18:13:00Z; FCM delivery
  17:27:52Z + 17:51:05Z.
- Phone dumps (temp dir): `liveval-t1d.xml` (answer tap target + `Connected`
  + banner), `liveval-t1d-mid.xml`, `liveval-t1d-end.xml`,
  `liveval-t1d-post.xml` (post-silence bubble, timer `08:04`).
- Commits: `ae7e6f1` (fix), `2a9cc14` + `ccd06f6` (audits/report),
  `86f530b` (FCM secret fix). Tree clean at end of session except this
  report update.
