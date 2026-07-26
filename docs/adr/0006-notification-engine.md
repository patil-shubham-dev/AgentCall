# ADR-0006: Notification Engine

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

AgentCall needs to notify users of incoming calls, missed calls, scheduled callbacks, and task completions. The current system has no notification system — the Android app maintains a persistent WebSocket connection for real-time events, but there is no structured notification delivery, no push fallback, and no notification history.

## Decision

Implement a Notification Engine as a core runtime service with the following capabilities:

### Notification Types

| Type | Trigger | Delivery |
|------|---------|----------|
| `call.incoming` | Call Manager creates call | Push + in-app toast |
| `call.missed` | Call timeout without answer | Push + notification list |
| `callback.scheduled` | Callback Engine schedules | In-app notification |
| `callback.ready` | Callback timer fires | Push + in-app |
| `task.completed` | MCP `notify_completion` | Push + notification list |
| `action.required` | High-priority AI request | Push + persistent in-app |
| `system.alert` | System event | Notification list |

### Delivery Pipeline

```
Trigger → Notification Engine → Device Router
  → [Online devices: in-app WebSocket message]
  → [Offline devices: FCM/APNs push]
  → [Persist to notification_log]
```

### Priority Levels

| Level | Behavior |
|-------|----------|
| `urgent` | High-priority push, persistent alert, repeat until acknowledged |
| `high` | Push notification, in-app badge |
| `normal` | In-app notification only |
| `low` | Notification list only (no push) |

## Alternatives Considered

- **WebSocket-only**: No push fallback for background/killed state. Rejected for production reliability.
- **Push-only**: High latency, no delivery guarantee. Rejected for real-time calls.
- **External service (Firebase-only)**: Vendor lock-in. Rejected for self-hosted deployments.

## Consequences

**Positive:**
- Reliable notification delivery (push + in-app)
- Notification history for users to review missed events
- Clear priority system for urgent vs. informational
- Vendor-agnostic push (FCM, APNs, future protocols)

**Negative:**
- Push infrastructure adds complexity (FCM project, APNs certificate)
- Multiple delivery channels require synchronization
- Notification storage adds database volume

## Tradeoffs

- In-app first vs. push first: in-app is faster and free; push is fallback for offline
- Persistence adds DB cost but enables notification history

## Future Work

- Notification preferences (per-type opt-in/opt-out)
- Do Not Disturb integration
- Notification grouping by provider
- Push notification analytics (delivery rates, latency)
