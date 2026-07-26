# Phase 2.2B — Presence Event Migration (Dual-Write Only)

**Status:** Complete  
**Date:** 2026-07-26

## Events Introduced

| Event | Type Constant | Payload |
|---|---|---|
| UserConnected | `user.connected` | `{ userId }` |
| UserDisconnected | `user.disconnected` | `{ userId }` |
| PresenceUpdated | `presence.updated` | `{ userId }` |

All events use version `1`. Payloads contain only the user identifier — no business logic.

## Files Created

### `backend/src/voicebridge/presence/events.ts`
Event type constants and payload type interfaces.

### `backend/src/voicebridge/presence/publisher.ts`
- Module-level `EventBus` reference setter (`setEventBus()`)
- Three publish helpers: `publishUserConnected`, `publishUserDisconnected`, `publishPresenceUpdated`
- Publish errors are logged with context (userId + event type)

### `backend/src/voicebridge/presence/subscribers.ts`
- `registerPresenceSubscribers(eventBus)` — registers three validation-only subscribers scoped to `'presence'`:
  - **connected-validator**: logs receipt
  - **disconnected-logger**: logs receipt
  - **updated-logger**: logs receipt (connection replaced)
- These exist for validation only — they do NOT manage connections

### `backend/src/voicebridge/presence/index.ts`
Barrel exports: `setEventBus`, `registerPresenceSubscribers`, event payload types.

## Files Modified

### `backend/src/voicebridge/service.ts` (+1 import block, +5 lines in `registerPhone`)
- Imported three publish helpers from `./presence/publisher.js`
- Inside `registerPhone()`, added dual-write pattern:
  1. **New connection (no existing):** `publishUserConnected(userId)` after map set
  2. **Reconnection (existing replaced):** `publishPresenceUpdated(userId)` instead of connected
  3. **Disconnection:** `publishUserDisconnected(userId)` inside `ws.on('close')` after map delete
- Existing behavior completely unchanged

### `backend/src/index.ts` (+1 import, +2 lines in `main()`)
- Imported `setEventBus as setPresenceEventBus` and `registerPresenceSubscribers`
- After notification wiring: `setPresenceEventBus(eventBus)` + `registerPresenceSubscribers(eventBus)`

## Dual-Write Pattern

```
registerPhone(userId, ws)
  ├─ existing: close old WS (if replacing)     (UNCHANGED)
  ├─ phoneConnections.set(userId, ws)          (UNCHANGED)
  ├─ publish user.connected                     [first connection]
  │  └─ or publish presence.updated             [reconnection]
  └─ ws.on('close') →
       ├─ phoneConnections.delete(userId)       (UNCHANGED)
       └─ publish user.disconnected

notifyPhone(userId, payload)
  ├─ publish notification.requested             (from Phase 2.2A)
  ├─ ws.send(payload)                           (UNCHANGED)
  └─ publish notification.delivered/failed      (from Phase 2.2A)
```

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| MCP Server `eslint src/ --ext .ts` | Pass (no regression) |
| Existing behaviour unchanged | Yes — no code path modified, only event publishes added alongside |
| No duplicate execution | Yes — subscribers are validation-only, do not manage connections |
| No circular dependencies | Yes — publisher imports from event-bus, not vice versa |

## Can Direct Presence Calls Be Removed?

**Not yet.** Same reasoning as Phase 2.2A:

- Subscribers are validation-only (log receipt, no actual connection management)
- The `phoneConnections` Map is private to `service.ts` — subscribers have no access
- Event delivery reliability has not been proven under load
- Direct call removal requires promoting subscribers to the primary path, which needs access to connection state and a reliability assessment
