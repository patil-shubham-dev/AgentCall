# Phase 2.2A — First Event Bus Migration (Notifications Only)

**Status:** Complete  
**Date:** 2026-07-26

## Events Introduced

| Event | Type Constant | Payload |
|---|---|---|
| NotificationRequested | `notification.requested` | `{ userId, notificationType, payload }` |
| NotificationDelivered | `notification.delivered` | `{ userId, notificationType }` |
| NotificationFailed | `notification.failed` | `{ userId, notificationType, error }` |

All events use version `1`. Payloads contain only identifiers and the notification type — no business logic.

## Files Created

### `backend/src/voicebridge/notifications/events.ts`
Event type constants and payload type interfaces.

### `backend/src/voicebridge/notifications/publisher.ts`
- Module-level `EventBus` reference setter (`setEventBus()`)
- Three publish helpers: `publishNotificationRequested`, `publishNotificationDelivered`, `publishNotificationFailed`
- Each helper constructs a minimal `Event<T>` with auto-generated metadata and fires it on the bus
- Publish errors are logged, not silently swallowed

### `backend/src/voicebridge/notifications/subscribers.ts`
- `registerNotificationSubscribers(eventBus)` — registers three validation-only subscribers scoped to `'notifications'`:
  - **requested-validator**: logs receipt + payload metadata
  - **delivered-logger**: logs delivery confirmation
  - **failed-logger**: logs failure details
- These exist for validation only — they do NOT send WebSocket messages (no duplicate notifications)

### `backend/src/voicebridge/notifications/index.ts`
Barrel exports: `setEventBus`, `registerNotificationSubscribers`, event payload types.

## Files Modified

### `backend/src/voicebridge/service.ts` (+1 import, +5 lines in `notifyPhone`)
- Imported three publish helpers from `./notifications/publisher.js`
- Inside `notifyPhone()`, added dual-write pattern:
  1. `publishNotificationRequested()` before the send attempt
  2. `publishNotificationDelivered()` after successful send
  3. `publishNotificationFailed()` after send failure or no connection
- Existing behavior completely unchanged

### `backend/src/index.ts` (+1 import, +2 lines in `main()`)
- Imported `setEventBus` and `registerNotificationSubscribers`
- After EventBus creation: `setEventBus(eventBus)` wires the publisher
- Then: `registerNotificationSubscribers(eventBus)` registers validation subscribers

## Dual-Write Pattern

```
notifyPhone(userId, payload)
  ├─ publish notification.requested  (Event Bus)
  ├─ ws.send(payload)                (existing direct call — UNCHANGED)
  ├─ publish notification.delivered  (Event Bus)  [on success]
  └─ publish notification.failed     (Event Bus)  [on failure]
```

The direct WebSocket send is the primary execution path. Event publishing runs in parallel. Subscribers validate but do not send.

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| MCP Server `eslint src/ --ext .ts` | Pass (no regression) |
| Existing notification behaviour unchanged | Yes — no code path modified, only event publishes added alongside |
| No duplicate notifications | Yes — subscribers do not send WS messages |
| No circular dependencies | Yes — verified: publisher imports from event-bus, not vice versa |

## Can Direct Notification Calls Be Safely Removed?

**Not yet.** Current subscribers are validation-only. Before removing the direct `ws.send()` call, subscribers must be promoted to the primary execution path (i.e., they must actually send the WebSocket message). This requires:

1. Access to the `phoneConnections` Map (currently private to `service.ts`)
2. Confirmation that event delivery is reliable enough to replace the direct call
3. A decision on whether the Event Bus becomes the sole path or remains a parallel validation path

Direct call removal should happen in a follow-up phase (Phase 2.2B or later), after the subscriber path is verified to produce identical results in production-like conditions.
