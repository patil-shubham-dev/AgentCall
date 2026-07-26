# Phase 2.5C — Complete Call Lifecycle (call.resumed)

**Status:** Complete  
**Date:** 2026-07-26  
**Audit reference:** [PHASE2_5A_SESSION_AUDIT.md](./PHASE2_5A_SESSION_AUDIT.md)  
**Scope:** Step 1 only — no cleanup, no retention, no persistence

---

## Summary

Added the single missing lifecycle event `call.resumed` to the Calls module, closing the only gap identified in the Phase 2.5A session audit. The `scheduleCallback` timeout now publishes `call.resumed` when restoring a paused session to `pending`. The Call Lifecycle is now fully represented by events.

---

## Files Modified

| File | Change |
|---|---|
| `backend/src/voicebridge/calls/events.ts` | Added `CALL_RESUMED` constant, `CallResumedPayload` interface |
| `backend/src/voicebridge/calls/publisher.ts` | Added `publishCallResumed()` export |
| `backend/src/voicebridge/calls/subscribers.ts` | Added `calls.resumed-logger` subscriber (validation-only log) |
| `backend/src/voicebridge/calls/index.ts` | Re-exported `publishCallResumed`, `CallResumedPayload` |
| `backend/src/voicebridge/service.ts` | Imported `publishCallResumed`, wired into `scheduleCallback` timeout |

---

## New Event

### `call.resumed`

**Constant:** `CALL_RESUMED = 'call.resumed'`

**Payload:**

```typescript
interface CallResumedPayload {
  userId: string;
  callId: string;
  delayMinutes: number;
  resumeAt: string;
}
```

**Publisher:**

```typescript
export const publishCallResumed = (
  userId: string,
  callId: string,
  delayMinutes: number,
  resumeAt: string,
): void =>
  publisher.publish<CallResumedPayload>(CALL_RESUMED, { userId, callId, delayMinutes, resumeAt });
```

**Subscriber:** Validation-only log (`[EventBus] CallResumed received`). No business logic, no state mutation.

**Placement in scheduleCallback timeout:**

```
existing.status = 'pending';          // existing state mutation (unchanged)
publishCallResumed(...);               // NEW: event publish after state change
notifyPhone(call_incoming);            // existing notification logic (unchanged)
scheduledCallbacks.delete(...);        // existing cleanup (unchanged)
```

---

## Lifecycle Coverage

All existing lifecycle transitions now have events:

```
call.created
    │
    ▼
call.answered
    │
    ├──► call.paused
    │        │
    │        ▼
    │    call.resumed  ◄── NEW — closes the gap
    │        │
    └────────┘
    │
    ├──► call.ended
    │
    └──► call.cancelled
```

**Every existing lifecycle transition now has an event.** No gaps remain.

---

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| MCP Server `eslint src/ --ext .ts` | Pass (no regression) |
| Existing callback behaviour unchanged | Yes — publish added after state mutation, before existing notification |
| No duplicate execution | Yes — subscriber is validation-only log |
| No circular dependencies | Yes — publisher imports from `common/` and `event-bus/` only |
| No Event Bus internals modified | Yes — `event-bus/` untouched |
| No new dependencies | Yes — only uses shared publisher + event types |

---

## Regression Check

- `scheduleCallback` logic flow is preserved: state mutation → publish → notify → cleanup
- No code paths removed
- No subscriber executes business logic
- Existing callback tests continue to pass without modification

---

## Remaining Work

Future functionality only (not existing lifecycle transitions):

- **Cleanup implementation** — session retention TTL, per-session removal timers, periodic sweep
- **`call.expired` event** — for paused session max TTL expiry
- **`call.deleted` event** — for session removal after retention window
- **`SessionStore` abstraction** — swapable in-memory / database backends
- **Persistence layer** — database migration, repository, read-through cache
