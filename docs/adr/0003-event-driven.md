# ADR-0003: Event-Driven Architecture

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

AgentCall has multiple runtime services that must communicate without tight coupling. Direct service-to-service calls create circular dependencies, make testing difficult, and prevent independent scaling.

## Decision

Adopt an event-driven architecture with an in-process Event Bus as the primary communication mechanism.

### Event Bus Contract

```typescript
interface EventBus {
  publish<T>(event: Event<T>): Promise<void>;
  subscribe<T>(eventType: string, handler: EventHandler<T>): void;
  unsubscribe(eventType: string, handler: EventHandler): void;
}

interface Event<T> {
  type: string;        // PascalCase: "CallCreated"
  version: number;     // Start at 1
  payload: T;
  metadata: {
    timestamp: string; // ISO 8601
    correlationId: string;
    source: string;    // Service name
  };
}
```

### Event Catalog (Initial)

| Event | Publisher | Subscribers | Payload |
|-------|-----------|-------------|---------|
| `CallCreated` | Call Manager | Notification Engine, Presence Engine, Event Bus Logger | `{ callId, userId, providerId, priority }` |
| `CallAccepted` | Communication Gateway | Call Manager, Presence Engine | `{ callId, userId, deviceId }` |
| `CallEnded` | Call Manager | History Service, Presence Engine, Notification Engine | `{ callId, duration, reason }` |
| `PresenceChanged` | Presence Engine | Device Router, Provider Registry | `{ userId, oldStatus, newStatus }` |
| `NotificationSent` | Notification Engine | History Service | `{ notificationId, userId, type }` |
| `ProviderConnected` | Provider Registry | Notification Engine | `{ providerId, userId }` |
| `DeviceRegistered` | Device Router | Presence Engine | `{ deviceId, userId, platform }` |
| `CallbackScheduled` | Callback Engine | Notification Engine | `{ callbackId, callId, scheduledAt }` |

## Alternatives Considered

- **Direct HTTP calls between services**: Rejected due to tight coupling and serialization overhead.
- **Message queue (RabbitMQ, Kafka)**: Premature for MVP. Event Bus can be extracted to external broker later.
- **Shared database polling**: Rejected due to latency and coupling.

## Consequences

**Positive:**
- Loose coupling between services
- Each service can be tested independently
- Easy to add new event subscribers without modifying publishers
- Clear audit trail via event log

**Negative:**
- Eventual consistency (some subscribers may lag)
- Debugging requires event tracing
- Event schema versioning adds complexity

## Tradeoffs

- In-process Event Bus vs. external message broker: in-process is simpler but prevents horizontal scaling. Acceptable for MVP with plan to extract.

## Future Work

- Extract Event Bus to Redis PubSub for horizontal scaling
- Event sourcing for complete audit trail
- Dead letter queue for failed event handlers
