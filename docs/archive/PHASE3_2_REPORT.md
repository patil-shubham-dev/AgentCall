# Phase 3.2 — Session Deletion (No Events)

**Status:** Complete  
**Date:** 2026-07-26  
**Scope:** Convert dry-run sweep to actual deletion — no events, no persistence, no Event Bus changes

---

## Summary

Refactored the sweeper to depend on a `SessionRepository` abstraction instead of importing service.ts directly. Converted the dry-run log into actual `sessions.delete()` calls. Expired sessions are now removed from memory. No events published, no clients notified, no Event Bus touched.

---

## Repository Abstraction

```typescript
// sweeper.ts:4-7
export interface SessionRepository {
  list(): Iterable<VoiceCallSession>;
  delete(callId: string): boolean;
}
```

The sweeper knows only this interface. The implementation is provided at wiring time by `service.ts` exports (`getSessions`, `deleteSession`). The sweeper has no direct dependency on the `sessions` Map or any storage implementation.

### Implementation (injected via index.ts)

```typescript
repository: { list: getSessions, delete: deleteSession }
```

Where:
- `getSessions` = `Array.from(sessions.values())` — iterates all in-memory sessions
- `deleteSession(callId)` = `sessions.delete(callId)` — removes from Map

---

## Deletion Flow

```
SessionSweeper.sweep()  [every 5 minutes]
  │
  ├── repository.list()        → iterate all sessions
  │
  ├── isExpired(session)       → check retentionExpiresAt
  │     │
  │     ├── false → skip
  │     │
  │     └── true  → compute retentionAgeMs from completedAt
  │                 repository.delete(session.id)
  │                 log: { callId, status, reason, retentionAgeMs }
  │
  └── log sweep complete with count
```

**What happens to the deleted session:**
- Removed from the `sessions` Map in `service.ts`
- No further lookup by `callId` will find it (returns `undefined`)
- `GET /api/v1/calls/:callId` returns 404
- The object is eligible for garbage collection

**What does NOT happen:**
- No `call.deleted` event
- No notification to the phone
- No database write
- No cleanup of `phoneConnections` or `scheduledCallbacks` (those are managed by their own lifecycle paths)

---

## Files Modified

| File | Change |
|---|---|
| `backend/src/voicebridge/sweeper.ts` | Added `SessionRepository` interface, replaced `getSessions` with `repository`, changed `sweep()` from dry-run log to actual `repository.delete()` with structured logging |
| `backend/src/voicebridge/service.ts` | Added `deleteSession(callId)` export wrapping `sessions.delete(callId)` |
| `backend/src/index.ts` | Import `deleteSession`, wire `repository: { list: getSessions, delete: deleteSession }` |

No new files created.

---

## Logging

On each deletion:

```json
{
  "callId": "uuid",
  "status": "completed",
  "reason": "retention_expired",
  "retentionAgeMs": 3605000
}
```

On sweep completion (if any deletions occurred):

```json
{
  "expiredCount": 3
}
```

---

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| Expired sessions removed from Map | Yes — `sessions.delete()` called |
| Non-expired sessions remain | Yes — `isExpired` check skips them |
| Active sessions untouched | Yes — `retentionExpiresAt` is `undefined` for `pending`/`active`/`paused` |
| No Event Bus activity | Yes — sweeper doesn't import event bus or publishers |
| No `call.deleted` events | Yes — confirmed: no event published |
| No clients notified | Yes — sweeper only calls `logger.info` |
| No persistence writes | Yes — no database code touched |

---

## Regression Check

- `sweeper.ts` no longer imports `getSessions` from `service.ts` — depends only on `SessionRepository` interface
- `SessionRepository` interface is defined in `sweeper.ts` (consumer defines contract)
- `service.ts` unchanged except for adding `deleteSession` export — all existing functions work identically
- `index.ts` wiring updated to pass `repository` object in place of bare `getSessions`
- `CleanupScheduler` untouched — still generic infrastructure
- No domain module (routes, calls, signaling, notifications, presence) modified

---

## Explicitly Not Implemented

- `call.deleted` event is NOT implemented
- Persistence is NOT implemented
- Event Bus is unchanged
- No `SessionStore` abstraction created
- No cleanup of related maps (`phoneConnections`, `scheduledCallbacks`)
- No notification to clients on deletion

Ready for Phase 3.3 when approved.
