# AgentCall Production Reliability Plan

## Mission

Production-harden the AgentCall calling path with **zero UI/UX/visual redesign**.

Primary goals:
1. Reliable Android FCM registration and retry.
2. Verified Render FCM configuration.
3. Decouple dispatched phone-call lifetime from ChatGPT MCP session lifetime.
4. Make health/reachability checks tolerant of Render cold starts.
5. Validate the complete ChatGPT → Render → FCM → Android path with Windows completely OFF.
6. Preserve OpenCode/Claude/local MCP behavior, explicit cancellation, TTL expiry, and existing call behavior.

## Non-goals / hard constraints

- Do **not** redesign UI/UX.
- Do **not** change colors, typography, font sizes, spacing, dimensions, layouts, icons, animations, navigation, or visual hierarchy.
- Do not perform unrelated refactoring.
- Do not weaken authentication/security.
- Do not hardcode secrets.
- Do not disable MCP cleanup globally.
- Do not make every call immortal.
- Do not remove TTL or explicit cancellation.
- Do not claim tests/configuration are verified unless actually verified.

---

# 1. Senior Engineering Team Approach

Treat this as a production reliability project reviewed by:

- Senior Android engineer — FCM, WorkManager, process death, background execution.
- Senior backend engineer — call state machine, MCP lifecycle, persistence, concurrency.
- Senior distributed-systems engineer — transport/application lifecycle separation, races, retries, idempotency.
- Senior Firebase/FCM engineer — token lifecycle and HTTP v1 delivery.
- Senior DevOps/SRE engineer — Render cold starts, configuration, readiness, observability.
- Senior QA/release engineer — failure-mode, regression, and end-to-end testing.

The implementation must solve root causes, not merely hide symptoms.

---

# 2. Current Architecture / Findings to Verify

The intended ChatGPT path is:

**ChatGPT → AgentCall MCP on Render → `create_call` → call dispatch → FCM → Android → incoming-call handling**

Windows is **not** part of this path. Therefore ChatGPT must work while the Windows computer is completely OFF.

The backend resolves the ChatGPT AI-key identity as `ChatGPT`; this is authoritative for the ChatGPT path, rather than `clientInfo.name`.

The backend tracks MCP sessions in `mcpSessions` and uses agent readiness checks.

## Critical lifecycle finding

The current architecture appears to have a dangerous coupling:

- ChatGPT creates a call.
- The MCP request returns.
- ChatGPT's MCP transport/session can later disconnect.
- `onAgentGone` can invoke logic equivalent to `cancelCallsByAgent("ChatGPT")`.
- A phone call is an asynchronous application operation and must not depend on the originating MCP transport remaining connected.

### Required invariant

Once a call has successfully crossed the **ring-dispatch boundary**, an MCP disconnect must **not** cancel it.

Conceptual lifecycle:

`CREATED/PENDING`
→ readiness/delay
→ `RING_DISPATCHED/RINGING`
→ `ACCEPTED | REJECTED | CANCELLED | EXPIRED | FAILED`

Use existing project state names if available.

Do **not** solve this by simply disabling `onAgentGone` or ignoring all pending calls. Pre-dispatch cleanup, explicit cancellation, and TTL expiry must remain correct.

---

# 3. Workstream A — Android FCM Registration

## Problem

Current cold-start registration can time out while Render wakes. The failure is persisted, but there is no durable retry mechanism, leaving the device unregistered.

## Required implementation

Implement one canonical FCM registration/reconciliation mechanism using **WorkManager**.

Requirements:

- `NetworkType.CONNECTED`.
- Exponential backoff.
- Unique work.
- No duplicate worker storm.
- Obtain the current `FirebaseMessaging.getInstance().token`.
- POST the token to the backend.
- Mark success only after backend confirmation.
- Persist success/error through the existing `FcmRegistrationStore` semantics.
- Retry transient network/cold-start failures.
- Distinguish permanent/configuration failures where practical.
- Never log the complete token.

## Trigger points

Use one canonical enqueue function and trigger it from:

1. App startup.
2. `FirebaseMessagingService.onNewToken()`.
3. Existing Settings/manual refresh path if appropriate.
4. WorkManager retry.

Do not create separate registration implementations.

## Token rotation

`onNewToken()` must cause reconciliation.

Do not assume an FCM token is permanent.

## Retry design

A Render cold-start timeout must be recoverable. WorkManager should retry rather than permanently recording failure.

Avoid aggressive polling or battery abuse.

---

# 4. Workstream B — Render / FCM Configuration

Verify the actual production deployment rather than assuming values.

Expected configuration should include, as applicable:

- `FCM_ENABLED=true`
- production Firebase project ID consistent with the real Firebase project (currently expected to be `agentcall-prod`)
- valid service-account credentials using the existing supported secret mechanism
- required Firebase Cloud Messaging HTTP v1 permissions

## Safe validation

When FCM is enabled:

- missing project ID → clear configuration error.
- missing/unreadable service-account configuration → clear configuration error.
- mismatched project/configuration → observable diagnostic.

When FCM is intentionally disabled, missing FCM credentials should not falsely fail the application.

Never expose:

- private keys;
- access tokens;
- bearer AI keys;
- complete credential files.

Add safe startup/readiness diagnostics only if they fit the existing architecture.

## Diagnostic token

Before final production testing, remove the known dummy token if it exists:

`test-diagnostic-token-001`

Do not confuse a database token row with successful real-device registration.

---

# 5. Workstream C — ChatGPT MCP / Call Lifecycle

This is the most important backend fix.

## Principle

**MCP transport lifetime and phone-call lifetime are different lifecycles.**

After successful ring dispatch:

> MCP disconnect MUST NOT cancel the phone call.

## Audit before editing

Trace actual code for:

- `create_call`
- bearer-token identity resolution
- readiness checks
- call creation
- delayed/ring dispatch
- FCM sender
- MCP session registration
- MCP liveness sweep
- `onAgentGone`
- `cancelCallsByAgent`
- explicit cancellation
- TTL/expiry
- accept/reject
- retry timers
- database persistence/state

## Correct fix

Use the existing authoritative call state if possible.

Establish the exact moment when FCM/ring dispatch is considered successful.

Only after that boundary should the call become independent of MCP session presence.

Potential acceptable approaches include:
- existing `RINGING`/dispatched state;
- a minimal `ringDispatched` state;
- an atomic/transactional transition.

Do not invent duplicate state if an existing state can safely represent it.

### Required behavior

**Before dispatch**
- preserve legitimate existing readiness/session cleanup behavior.

**Dispatch succeeds**
- transition to dispatched/ringing atomically enough to prevent cancellation races.

**MCP disconnect after dispatch**
- do not cancel the call.

**FCM dispatch fails**
- retain appropriate retry/failure behavior.
- do not falsely mark the call as ringing.

**Explicit cancel**
- must continue to cancel.

**TTL**
- must continue to expire the call.

**Accept/reject**
- must continue normally.

## Race conditions

Audit and protect at least:

1. MCP disconnect racing with dispatch.
2. Dispatch success racing with `onAgentGone`.
3. Retry timer racing with cancellation/expiry.
4. Multiple lifecycle events attempting conflicting terminal states.

Do not implement a check-then-act sequence that can cancel a call after it has already been dispatched.

Do not globally disable `cancelCallsByAgent`.

---

# 6. Workstream D — Health / Render Cold Start

The Settings health probe is currently too short for a Render cold start.

Fix only the underlying reachability behavior.

Prefer:
- reasonable timeout;
- transient retry;
- reuse canonical API client where practical;
- existing loading state if one exists.

Do not redesign Settings.

Do not globally alter every network timeout unless code inspection proves it necessary.

A cold-start timeout should be treated as recoverable, not as proof that the backend is permanently unavailable.

---

# 7. Observability

Add only useful, non-sensitive diagnostics.

Android events:
- token retrieval;
- registration enqueue;
- registration attempt;
- success;
- transient failure;
- retry;
- token refresh.

Backend events:
- FCM config validation;
- call creation;
- identity resolution;
- readiness decision;
- dispatch attempted;
- dispatch success/failure;
- MCP connect/disconnect;
- call state transition;
- cancellation reason;
- expiry reason.

Log call IDs and redacted identifiers.

Never log complete FCM tokens, AI keys, private keys, or access tokens.

---

# 8. Testing Plan

## FCM registration

Test:
1. Fresh install.
2. Real FCM token retrieval.
3. Warm backend.
4. Cold backend.
5. Initial timeout.
6. WorkManager retry.
7. Backend becomes available.
8. Registration succeeds.
9. Success timestamp/state persists.
10. Error state is not left stale.
11. Token rotation.
12. Duplicate enqueue does not create multiple workers.

## ChatGPT / Windows OFF

Real end-to-end test:

- Windows completely OFF.
- ChatGPT AgentCall plugin available through ChatGPT.
- Real Android FCM token registered.
- Phone connected to internet.
- Phone may be locked / app process killed according to test.
- Initiate call from ChatGPT.
- Verify Render receives call.
- Verify identity is `ChatGPT`.
- Verify dispatch.
- Verify real FCM delivery.
- Verify Android incoming call behavior.
- Verify accept/reject.

## MCP disconnect

1. ChatGPT creates a call.
2. `create_call` returns.
3. MCP session disconnects.
4. If ring was dispatched, call must continue.
5. `onAgentGone` must not cancel the dispatched call.
6. Explicit cancellation still works.
7. TTL expiry still works.

## Cold start

1. Allow Render to become idle where possible.
2. Initiate ChatGPT call.
3. Observe wake-up.
4. Ensure registration/call flow survives transient timeout.
5. Ensure health does not permanently report failure.

## Regression

Verify existing:
- OpenCode/local MCP path.
- Claude/local MCP path if applicable.
- ChatGPT path.
- Windows ON.
- Windows OFF.

---

# 9. Implementation Order

### Phase 1 — Read-only audit
No edits. Map actual code and compare against this plan.

### Phase 2 — Android FCM reliability
Implement WorkManager registration/reconciliation and test it.

### Phase 3 — Backend FCM configuration
Validate production configuration and clean dummy token.

### Phase 4 — Call lifecycle
Implement the smallest state-aware lifecycle fix.

### Phase 5 — Health/cold start
Improve reachability behavior without UI changes.

### Phase 6 — Full end-to-end validation
Run Windows-OFF ChatGPT test and regression suite.

---

# 10. Git / Change Discipline

Before editing:
- inspect `git status`;
- inspect existing diff;
- preserve unrelated user changes.

During editing:
- focused diffs;
- no mass formatting;
- no generated-file churn;
- no unnecessary dependency upgrades;
- no unrelated refactoring.

After editing:
- inspect complete diff;
- specifically check for accidental UI/UX/visual changes;
- check API/schema changes;
- check dependency changes;
- run build/lint/tests;
- verify no secrets were introduced.

---

# 11. Acceptance Criteria

## Android
- Real device obtains FCM token.
- Token reaches production backend.
- Cold-start timeout does not permanently break registration.
- WorkManager retries transient failures.
- Token rotation reconciles.
- No duplicate worker storm.

## Backend
- Production FCM configuration verified.
- Safe configuration validation exists where appropriate.
- Dummy diagnostic token removed.
- Secrets never exposed.

## ChatGPT
- Works with Windows completely OFF.
- Identity resolves as `ChatGPT`.
- Dispatched call survives MCP disconnect.
- Explicit cancellation works.
- TTL expiry works.
- FCM failure is not reported as successful ringing.

## Health
- Render cold start does not cause a misleading permanent failure.
- Existing Settings UI remains visually unchanged.

## Regression
- OpenCode/Claude paths remain functional.
- Existing readiness/session semantics are preserved except for the necessary post-dispatch lifecycle decoupling.

---

# 12. Final Engineering Report

At completion report:

1. Confirmed root causes.
2. Files changed.
3. Exact implementation changes.
4. Why the lifecycle fix is safe.
5. FCM retry behavior.
6. Actual Render configuration verification.
7. Health/cold-start changes.
8. Tests actually executed and results.
9. Windows-OFF result.
10. Remaining risks/limitations.

Clearly distinguish **verified**, **inferred**, **not tested**, and **blocked by environment**.
