# Phase 3.3 — Deletion Coordination & call.deleted

**Status:** Complete  
**Date:** 2026-07-26  
**Scope:** DeletionCoordinator, `call.deleted` event, repository API refinement — no `call.expired`, no persistence

---

## Summary

Introduced a `DeletionCoordinator` that owns all post-deletion logic: structured audit logging and publishing `call.deleted`. The sweeper no longer logs per-session deletions or imports Event Bus / publisher modules — it delegates to the coordinator. The `SessionRepository.delete()` return type changed from `boolean` to `VoiceCallSession | undefined`. `call.deleted` follows the existing event conventions with minimal payload.

---

## Coordinator Design

```
DeletionCoordinator (voicebridge/coordinator.ts)
│
└── handleDeleted(session)
      │
      ├── compute retentionMs from completedAt
      │
      ├── logger.info({ callId, userId, status, reason, completedAt, retentionExpiresAt, retentionMs }, ...)
      │   → structured audit log
      │
      └── publishCallDeleted(userId, callId, status, retentionMs)
          → call.deleted event on Event Bus
```

**No Event Bus constructor dependency.** The coordinator imports `publishCallDeleted` directly from the calls publisher, which is already installed on the event bus during startup.

**Future extensibility:** `handleDeleted` is a single method. Future phases can add metrics hooks, persistence hooks, or additional subscribers inside this method without changing the sweeper.

---

## Repository API

```typescript
// Before (Phase 3.2)
delete(callId: string): boolean;

// After (Phase 3.3)
delete(callId: string): VoiceCallSession | undefined;
```

Implementation in `service.ts`:

```typescript
export function deleteSession(callId: string): VoiceCallSession | undefined {
  const session = sessions.get(callId);
  if (!session) return undefined;
  sessions.delete(callId);
  return session;
}
```

The return value enables callers to use the deleted session without a separate lookup. The sweeper already has the session from `list()`, so it ignores the return value.

---

## `call.deleted` Event

**Constant:** `CALL_DELETED = 'call.deleted'`

**Payload:**

```typescript
interface CallDeletedPayload {
  userId: string;
  callId: string;
  statusAtDeletion: string;
  retentionMs: number;
}
```

**Publisher:**

```typescript
export const publishCallDeleted = (userId, callId, statusAtDeletion, retentionMs): void =>
  publisher.publish<CallDeletedPayload>(CALL_DELETED, { userId, callId, statusAtDeletion, retentionMs });
```

**Subscriber:** Validation-only log (`[EventBus] CallDeleted received`). No business logic.

Following the existing `{domain}.{action}` convention with minimal identifiers and deletion context. No transcript, no messages, no session data.

---

## Deletion Flow

```
SessionSweeper.sweep()  [every 5 minutes]
  │
  ├── repository.list()               → iterate sessions
  │
  ├── isExpired(session) → true
  │
  ├── repository.delete(session.id)   → removes from Map
  │
  ├── coordinator.handleDeleted(session)
  │     ├── audit log (callId, userId, status, reason, retentionMs)
  │     └── publishCallDeleted(userId, callId, status, retentionMs)
  │
  └── log { expiredCount }          → summary only
```

---

## Files Created

| File | Lines | Purpose |
|---|---|---|
| `backend/src/voicebridge/coordinator.ts` | 25 | `DeletionCoordinator` — audit log + `call.deleted` publishing |

## Files Modified

| File | Change |
|---|---|
| `backend/src/voicebridge/calls/events.ts` | Added `CALL_DELETED`, `CallDeletedPayload` |
| `backend/src/voicebridge/calls/publisher.ts` | Added `publishCallDeleted` |
| `backend/src/voicebridge/calls/subscribers.ts` | Added `calls.deleted-logger` subscriber |
| `backend/src/voicebridge/calls/index.ts` | Re-exported `publishCallDeleted`, `CallDeletedPayload` |
| `backend/src/voicebridge/service.ts` | Changed `deleteSession` return type to `VoiceCallSession \| undefined` |
| `backend/src/voicebridge/sweeper.ts` | Added `coordinator` to options, changed `delete` return type, removed per-session log, removed publisher/Event Bus imports |
| `backend/src/index.ts` | Imported `DeletionCoordinator`, wired to sweeper |

---

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| Expired sessions deleted from Map | Yes — `sessions.delete()` still called |
| `call.deleted` emitted exactly once per deletion | Yes — coordinator publishes once in `handleDeleted` |
| No duplicate publication | Yes — sweeper calls `handleDeleted` once per expired session, no other publisher calls |
| No Event Bus infrastructure changes | Yes — `event-bus/` untouched, publisher uses existing shared publisher |
| Sweeper has no Event Bus imports | Yes — only imports `logger`, `VoiceCallSession` type, `DeletionCoordinator` type |
| Sweeper has no publisher imports | Yes — delegation to coordinator |
| Non-expired sessions remain | Yes — `isExpired` check unchanged |
| `call.deleted` subscriber is validation-only | Yes — no business logic |

---

## Regression Check

- `sweeper.ts` no longer imports or logs per-session deletion — coordinator owns that
- `sweeper.ts` no longer computes `retentionAgeMs` — coordinator computes `retentionMs`
- `service.ts` `deleteSession` still removes from Map — same runtime effect, richer return type
- `calls/index.ts`, `publisher.ts`, `subscribers.ts`, `events.ts` all follow the exact existing template — no pattern drift
- `index.ts` coordinator creation is stateless — no shutdown needed, no DI changes
- No circular dependencies — coordinator imports publisher, sweeper imports coordinator type, index.ts wires both
- `CleanupScheduler` untouched

---

## Explicitly Not Implemented

- `call.expired` is NOT implemented
- Persistence is NOT implemented
- No metrics hooks (future placeholder in coordinator)
- No persistence hooks (future placeholder in coordinator)
- Event Bus infrastructure unchanged

Ready for Phase 3.4 when approved.
