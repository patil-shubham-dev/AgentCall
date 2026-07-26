# Phase 2.3 — Call Lifecycle Migration (Dual-Write Only)

**Status:** Complete  
**Date:** 2026-07-26  
**Template:** Follows the exact canonical structure from Notifications and Presence modules

---

## Events Introduced

| Event | Type Constant | Payload |
|---|---|---|
| CallCreated | `call.created` | `{ userId, callId }` |
| CallAnswered | `call.answered` | `{ userId, callId }` |
| CallPaused | `call.paused` | `{ userId, callId, delayMinutes, resumeAt }` |
| CallEnded | `call.ended` | `{ userId, callId }` |
| CallCancelled | `call.cancelled` | `{ userId, callId }` |

All events use version `1`. All payloads contain only identifiers and minimal context. No business logic.

Only existing call lifecycle transitions were mapped — no new business states invented.

## Files Created

### `backend/src/voicebridge/calls/events.ts` (32 lines)
Event type constants and 5 payload interfaces. Uses `{domain}.{action}` convention.

### `backend/src/voicebridge/calls/publisher.ts` (42 lines)
- Shared publisher (`createEventPublisher('voicebridge.calls', 1)`)
- `install(eventBus)` re-export for wiring
- 5 typed publish helper functions: `publishCallCreated`, `publishCallAnswered`, `publishCallPaused`, `publishCallEnded`, `publishCallCancelled`
- No boilerplate — all publish logic delegated to the shared publisher

### `backend/src/voicebridge/calls/subscribers.ts` (69 lines)
- `registerCallSubscribers(eventBus)` — registers 5 validation-only subscribers scoped to `'calls'`
- Each subscriber logs receipt with identifiers
- No call logic, no state modification, no side effects

### `backend/src/voicebridge/calls/index.ts` (20 lines)
- `register(eventBus)` — single entry point that calls `install()` + `registerCallSubscribers()`
- Re-exports all publish helpers and event payload types for callers in `service.ts`

## Files Modified

### `backend/src/voicebridge/service.ts` (+1 import block, +5 publish calls)

Dual-write pattern in each lifecycle function:

| Function | Event Published | Placement |
|---|---|---|
| `createCall()` | `publishCallCreated(userId, callId)` | After session stored, before `notifyPhone()` |
| `addMessage()` (ai, pending→active) | `publishCallAnswered(userId, callId)` | After status transition to active |
| `scheduleCallback()` | `publishCallPaused(userId, callId, delayMinutes, resumeAt)` | After status set to paused |
| `completeCall()` | `publishCallEnded(userId, callId)` | After state change, before `notifyPhone()` |
| `cancelCall()` | `publishCallCancelled(userId, callId)` | After state change, before `notifyPhone()` |

Existing code paths unchanged. Events confirm state transitions — they don't trigger them.

### `backend/src/index.ts` (+2 lines)

```typescript
import { register as registerCalls } from './voicebridge/calls/index.js';
// ...
registerCalls(eventBus);
```

One import, one call. Same pattern as notifications and presence.

## Dual-Write Pattern

```
createCall(input):
  create session / store in map          (EXISTING)
  publish call.created                   (DUAL-WRITE)
  notifyPhone (call_incoming)            (EXISTING + its own events)

addMessage(callId, 'ai', ...):
  if status === 'pending':
    status → 'active'                    (EXISTING)
    publish call.answered               (DUAL-WRITE)
  notifyPhone (ai_message)               (EXISTING + its own events)

scheduleCallback(...):
  status → 'paused'                      (EXISTING)
  publish call.paused                    (DUAL-WRITE)
  notifyPhone (callback_scheduled)       (EXISTING + its own events)

completeCall(callId):
  status → 'completed'                   (EXISTING)
  publish call.ended                     (DUAL-WRITE)
  notifyPhone (call_ended)               (EXISTING + its own events)

cancelCall(callId):
  status → 'cancelled'                   (EXISTING)
  publish call.cancelled                 (DUAL-WRITE)
  notifyPhone (call_cancelled)           (EXISTING + its own events)
```

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| MCP Server `eslint src/ --ext .ts` | Pass (no regression) |
| Existing call behaviour unchanged | Yes — no code path removed, only event publishes added |
| No duplicate execution | Yes — subscribers are validation-only, do not execute call logic |
| No circular dependencies | Yes — publisher imports from `common/` and `event-bus/` |
| Dual-write pattern consistent | Yes — matches notifications and presence modules exactly |
| No Event Bus internals modified | Yes — `event-bus/` untouched |
| No new dependencies | Yes — only uses shared publisher + event types |

## Can Direct Call Lifecycle Calls Be Removed?

**No.** Same reasoning as notifications and presence:

- Subscribers are validation-only (log receipt, no real logic)
- Subscribers have no access to the `sessions` Map or `phoneConnections` Map
- Event delivery reliability has not been proven under load
- The existing path is the single source of truth for call state
- Direct call removal requires promoting subscribers to the primary execution path, which is a future phase

## Architectural Issues

None discovered. The calls module follows the canonical template exactly. No new patterns, no drift, no surprises.

**Template consistency check:**

| Aspect | Notifications | Presence | Calls |
|---|---|---|---|
| 4-file structure | ✓ | ✓ | ✓ |
| Shared publisher | ✓ | ✓ | ✓ |
| `register(eventBus)` export | ✓ | ✓ | ✓ |
| `{domain}.{action}` events | ✓ | ✓ | ✓ |
| Minimal payloads | ✓ | ✓ | ✓ |
| Validation-only subscribers | ✓ | ✓ | ✓ |
| No business logic in events | ✓ | ✓ | ✓ |

All three modules are structurally identical. The migration template is now validated across three independent domain migrations.

Ready for Signaling migration when approved.
