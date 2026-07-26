# ADR-0007: Callback Engine

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

When a user misses a call or requests a later callback, AgentCall must schedule, manage, and fire callbacks with configurable retry behavior. The current system has a simple "call me back in X minutes" timer on the Android app, but no server-side callback scheduling, no retry logic, and no integration with the notification system.

## Decision

Implement a Callback Engine as a core runtime service with:

### Callback Lifecycle

```
Scheduled → Pending → Ready → Fired → Completed
                                         ↘ Missed → Retry (up to N times)
                                                              ↘ Exhausted → Marked Failed
```

### Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| Initial delay | 5 min | Time before first callback |
| Retry intervals | 5, 10, 30, 60 min | Intervals for retries |
| Max retries | 5 | Maximum retry attempts |
| Urgent only | false | Only retry if call was urgent |
| Silent retry | false | Don't notify before retry |
| Notification only | false | Send notification instead of full call |

### Integration

1. **User declines or misses call** → Callback Engine schedules retry
2. **User says "call me back in X"** → Callback Engine schedules with custom delay
3. **AI requests callback** → Callback Engine schedules via `notify_completion` or create_call
4. **Timer fires** → Callback Engine publishes `CallbackReady` event
5. **Notification Engine** receives event → notifies user
6. **User accepts** → Call Manager creates new call
7. **User declines again** → Callback Engine retries or exhausts

## Alternatives Considered

- **Client-side scheduling**: Android sets a timer. Rejected because it doesn't work when app is killed.
- **In-process timers (setTimeout)**: Works for demo but lost on restart. Rejected for production.
- **External scheduler (Bull, Sidekiq)**: Added dependency. Rejected for MVP; can add later.

## Consequences

**Positive:**
- Reliable callback scheduling (survives restarts)
- Configurable retry policy per user preference
- Integration with Notification Engine for user-facing alerts
- AI can request callbacks via MCP

**Negative:**
- Requires persistent storage for callback schedules
- Timer management adds complexity (especially for large numbers of users)
- Retry logic can annoy users if not configured well

## Tradeoffs

- In-process vs. external scheduler: in-process is simpler but less durable. Acceptable with database persistence.

## Future Work

- Integrate with external scheduler for production reliability
- Smart retry (time-of-day aware, user presence aware)
- Callback analytics (answer rate by time/delay)
- User-facing callback management UI in mobile app
