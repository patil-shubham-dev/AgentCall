# Phase 3.4 — Lifecycle Coordination

**Status:** Complete  
**Date:** 2026-07-26  
**Scope:** LifecycleCoordinator, `call.expired`, callback resume moved from inline setTimeout, max pause TTL — no persistence, no Event Bus redesign, no new cleanup mechanisms

---

## Summary

Introduced a `LifecycleCoordinator` that owns all time-driven lifecycle transitions: callback resume and paused session expiry. The inline `setTimeout` in `service.ts:scheduleCallback` was replaced with a call to `coordinator.resumeCallback()`, which uses the existing `CleanupScheduler` for both the callback resume timeout and the new 24-hour max pause TTL. When a paused session exceeds 24 hours without being resumed, it is force-cancelled and `call.expired` is published.

---

## LifecycleCoordinator Design

```
LifecycleCoordinator (voicebridge/lifecycle-coordinator.ts)
│
├── constructor(cleanupScheduler, sessionStore, notifyPhone)
│
├── resumeCallback(userId, callId, delayMinutes, resumeAt)  [public]
│     │
│     ├── cleanupScheduler.schedule("resume:{callId}", resumeAt)
│     │     └── handleResume()
│     │
│     └── cleanupScheduler.schedule("pause-ttl:{callId}", resumeAt + 24h)
│           └── handlePauseExpiry()
│
├── handleResume()  [private]
│     │
│     ├── getSession(callId) → session
│     ├── if not paused → return (guard against stale timers)
│     ├── status → pending
│     ├── publishCallResumed(userId, callId, delayMinutes, resumeAt)
│     ├── notifyPhone(call_incoming)
│     └── deleteScheduledCallback(userId)
│
└── handlePauseExpiry()  [private]
      │
      ├── getSession(callId) → session
      ├── if not paused → return (guard against stale timers)
      ├── status → cancelled, completedAt, retentionExpiresAt
      ├── compute pausedDurationMinutes from resumeAt - delay
      ├── publishCallExpired(userId, callId, paused_ttl_expired, ...)
      ├── notifyPhone(call_expired)
      └── deleteScheduledCallback(userId)
```

### Dependencies

- `CleanupScheduler` — generic infrastructure, already exists, used for both timers
- `LifecycleSessionStore` interface — `{ getSession, deleteScheduledCallback }`
- `notifyPhone` function — for sending WebSocket notifications

No direct access to `sessions` Map, `scheduledCallbacks` Map, or any module internals.

---

## Responsibilities

| Component | Owns |
|---|---|
| **LifecycleCoordinator** | time-driven state transitions (callback resume, paused TTL expiry) |
| **DeletionCoordinator** | post-deletion coordination (audit log, `call.deleted`) |
| **SessionSweeper** | finding expired sessions and calling delete |
| **CleanupScheduler** | generic one-shot timer scheduling (used by LifecycleCoordinator) |

Do NOT merge these responsibilities.

---

## `call.expired` Event

**Constant:** `CALL_EXPIRED = 'call.expired'`

**Payload:**

```typescript
interface CallExpiredPayload {
  userId: string;
  callId: string;
  reason: string;               // 'paused_ttl_expired'
  pausedDurationMinutes: number;
}
```

**Publisher:**

```typescript
export const publishCallExpired = (userId, callId, reason, pausedDurationMinutes): void =>
  publisher.publish<CallExpiredPayload>(CALL_EXPIRED, { userId, callId, reason, pausedDurationMinutes });
```

**Subscriber:** Validation-only log (`[EventBus] CallExpired received`). No business logic.

Minimal payload following existing conventions: identifiers and expiry context only. No transcript, no message history, no unnecessary metadata.

---

## Lifecycle Flow (Updated)

```
scheduleCallback(params)
  │
  ├── status → paused                          (service.ts)
  ├── publishCallPaused(...)                   (service.ts)
  ├── notifyPhone(callback_scheduled)          (service.ts)
  │
  └── coordinator.resumeCallback(...)
        │
        ├── cleanupScheduler: resume:{callId} @ resumeAt
        │     │
        │     └── handleResume
        │           ├── status → pending
        │           ├── publishCallResumed
        │           ├── notifyPhone(call_incoming)
        │           └── deleteScheduledCallback
        │
        └── cleanupScheduler: pause-ttl:{callId} @ resumeAt + 24h
              │
              └── handlePauseExpiry (only if still paused)
                    ├── status → cancelled, completedAt, retentionExpiresAt
                    ├── publishCallExpired
                    ├── notifyPhone(call_expired)
                    └── deleteScheduledCallback
```

**Stale timer guard:** Both `handleResume` and `handlePauseExpiry` check `session.status === 'paused'` before acting. If the call was completed or cancelled normally before the timer fires, the guard prevents any state corruption.

---

## Files Created

| File | Lines | Purpose |
|---|---|---|
| `backend/src/voicebridge/lifecycle-coordinator.ts` | 65 | `LifecycleCoordinator` — owns callback resume + paused TTL expiry |

## Files Modified

| File | Change |
|---|---|
| `backend/src/voicebridge/calls/events.ts` | Added `CALL_EXPIRED`, `CallExpiredPayload` |
| `backend/src/voicebridge/calls/publisher.ts` | Added `publishCallExpired` |
| `backend/src/voicebridge/calls/subscribers.ts` | Added `calls.expired-logger` subscriber |
| `backend/src/voicebridge/calls/index.ts` | Re-exported `publishCallExpired`, `CallExpiredPayload` |
| `backend/src/voicebridge/service.ts` | Replaced inline `setTimeout` with `coordinator.resumeCallback()`, removed `publishCallResumed` import (moved to coordinator), added `deleteScheduledCallback`/`setLifecycleCoordinator` exports, added module-level `lifecycleCoordinator` variable |
| `backend/src/index.ts` | Imported `LifecycleCoordinator`, `getCall`, `deleteScheduledCallback`, `setLifecycleCoordinator`, `notifyPhone`; created and wired coordinator after `cleanupScheduler` |

---

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| Callback resume still works | Yes — same logic, moved to coordinator |
| Paused sessions expire after 24h | Yes — `handlePauseExpiry` scheduled at `resumeAt + 24h` |
| Exactly one `call.expired` per expiry | Yes — published once in `handlePauseExpiry` |
| No duplicate events | Yes — status guard prevents re-triggering |
| No stale timer corruption | Yes — both handlers check `status === 'paused'` |
| Event Bus unchanged | Yes — only added new event type, no infrastructure changes |
| No new cleanup mechanisms | Yes — CleanupScheduler already existed, used as-is |
| No persistence | Yes — no database code touched |

---

## Regression Check

- `service.ts:scheduleCallback` still sets status to paused, stores callback, publishes, notifies — only the inline `setTimeout` was replaced
- `service.ts:completeCall` and `cancelCall` unchanged — orphaned timers guarded by status check
- `service.ts` no longer imports `publishCallResumed` — moved to coordinator (correct: coordinator owns the event)
- `CleanupScheduler` used as-is — no modifications
- `DeletionCoordinator` untouched — still owns post-deletion coordination
- `SessionSweeper` untouched — still finds expired sessions and deletes them
- `call.expired` follows exact existing event template — no pattern drift

---

## Explicitly Not Implemented

- Persistence is NOT implemented
- Event Bus is NOT redesigned
- No new cleanup mechanisms added
- `call.expired` subscriber is validation-only (no business logic)
- No changes to `DeletionCoordinator` or `SessionSweeper`

Ready for persistence work when approved.
