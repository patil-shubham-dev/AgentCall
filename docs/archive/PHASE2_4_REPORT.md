# Phase 2.4 — Signaling Migration (Dual-Write Only)

**Status:** Complete  
**Date:** 2026-07-26  
**Template:** Follows the exact canonical structure from Notifications, Presence, and Calls

---

## Events Introduced

| Event | Type Constant | Payload |
|---|---|---|
| SignalingConnected | `signaling.connected` | `{ userId }` |
| SignalingDisconnected | `signaling.disconnected` | `{ userId }` |
| SignalingMessageReceived | `signaling.message_received` | `{ userId, messageType, size }` |
| SignalingFailed | `signaling.failed` | `{ userId, reason }` |

All events use version `1`. All payloads contain only identifiers and minimal context. No business logic.

Only existing signaling boundary events were mapped — no new signaling states invented.

## Files Created

### `backend/src/voicebridge/signaling/events.ts` (30 lines)
Event type constants and 4 payload interfaces. Uses `{domain}.{action}` convention.

### `backend/src/voicebridge/signaling/publisher.ts` (38 lines)
- Shared publisher (`createEventPublisher('voicebridge.signaling', 1)`)
- `install(eventBus)` re-export for wiring
- 4 typed publish helper functions: `publishSignalingConnected`, `publishSignalingDisconnected`, `publishSignalingMessageReceived`, `publishSignalingFailed`

### `backend/src/voicebridge/signaling/subscribers.ts` (62 lines)
- `registerSignalingSubscribers(eventBus)` — registers 4 validation-only subscribers scoped to `'signaling'`
- Each subscriber logs receipt with identifiers
- No signaling logic, no state modification, no message sending

### `backend/src/voicebridge/signaling/index.ts` (19 lines)
- `register(eventBus)` — single entry point, same pattern as all prior modules
- Re-exports publish helpers and event payload types for callers

## Files Modified

### `backend/src/signaling/server.ts` (+1 import block, +7 publish calls)

Dual-write pattern at each signaling boundary:

| Location | Event Published | Placement |
|---|---|---|
| Connection accepted, phone registered, connected message sent | `publishSignalingConnected(userId)` | After `ws.send({ type: 'connected' })` |
| Message received, validated (size + rate), parsed | `publishSignalingMessageReceived(userId, msgType, msgSize)` | After type extraction, before log |
| Message too large | `publishSignalingFailed(userId, 'message too large')` | Before error is sent to client |
| Message rate limited | `publishSignalingFailed(userId, 'message rate limited')` | Before error is sent to client |
| WebSocket close | `publishSignalingDisconnected(userId)` | After rate-limit map cleanup, before log |
| WebSocket error | `publishSignalingFailed(userId, 'WebSocket error')` | Before rate-limit map cleanup |

Existing code paths unchanged. Events observe signaling boundaries — they don't process signaling data.

### `backend/src/index.ts` (+2 lines)

```typescript
import { register as registerSignaling } from './voicebridge/signaling/index.js';
// ...
registerSignaling(eventBus);
```

One import, one call. Same pattern as all prior modules.

---

## Dual-Write Pattern

```
Connection:
  registerPhone(userId, ws)              (EXISTING)
  ws.send({ type: 'connected', ... })    (EXISTING)
  publish signaling.connected            (DUAL-WRITE)

Message received:
  validate size + rate                   (EXISTING)
  if failed: publish signaling.failed    (DUAL-WRITE)
  parse message type                     (EXISTING)
  publish signaling.message_received     (DUAL-WRITE)

Disconnection:
  cleanup clientRateLimits               (EXISTING)
  publish signaling.disconnected         (DUAL-WRITE)

Error:
  publish signaling.failed               (DUAL-WRITE)
  cleanup clientRateLimits               (EXISTING)
```

---

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| MCP Server `eslint src/ --ext .ts` | Pass (no regression) |
| Existing signaling behaviour unchanged | Yes — no code path removed, only event publishes added |
| No duplicate execution | Yes — subscribers are validation-only, do not send messages or modify state |
| No circular dependencies | Yes — publisher imports from `common/` and `event-bus/`; `server.ts` imports from publisher (one-way) |
| Template identical to prior domains | Yes — 4-file structure, shared publisher, `register(eventBus)`, `{domain}.{action}` events, validation-only subscribers |
| No Event Bus internals modified | Yes — `event-bus/` untouched |
| No new dependencies | Yes — only uses shared publisher + event types |

## Can Direct Signaling Logic Be Removed?

**No.** Same reasoning as all prior phases:

- Subscribers are validation-only (log receipt, no real logic)
- Subscribers have no access to `WebSocketServer`, rate-limit maps, or connection state
- Event delivery reliability has not been proven under load
- Signaling operations are real-time and synchronous — converting them to event-driven would require architectural changes to the WebSocket message handling pipeline

## Architectural Issues

None discovered. The signaling module follows the canonical template exactly. No new patterns, no drift.

**Template consistency check across all 4 domains:**

| Aspect | Notifications | Presence | Calls | Signaling |
|---|---|---|---|---|
| 4-file structure | ✓ | ✓ | ✓ | ✓ |
| Shared publisher | ✓ | ✓ | ✓ | ✓ |
| `register(eventBus)` export | ✓ | ✓ | ✓ | ✓ |
| `{domain}.{action}` events | ✓ | ✓ | ✓ | ✓ |
| Minimal payloads | ✓ | ✓ | ✓ | ✓ |
| Validation-only subscribers | ✓ | ✓ | ✓ | ✓ |
| No business logic in events | ✓ | ✓ | ✓ | ✓ |

All four modules are structurally identical. The migration template is validated across 4 independent domain migrations.

Ready for Phase 2.5 (Sessions) when approved.
