# Event Bus — Architecture & Design

> **Phase:** 1B (Foundation)
> **Status:** Implemented, verified
> **ADRs:** ADR-0003 (Event-Driven Architecture), ADR-0010 (Service Boundaries)

---

## Architecture Overview

The Event Bus is a lightweight, in-process pub/sub infrastructure that becomes the only communication mechanism between internal services. It is:

- **Generic** — has zero knowledge of business concepts (no Voice, Calls, Providers, AI, Notifications, Android)
- **In-process** — runs within the same Node.js process; designed for extraction to Redis PubSub later
- **Typed** — TypeScript generics provide type-safe event payloads without coupling to concrete types
- **Sync + Async** — handlers can register as synchronous (publisher awaits) or asynchronous (fire-and-forget via microtask)

### Position in Architecture

```
                     ┌─────────────────────┐
                     │   REST / WebSocket   │
                     └──────────┬──────────┘
                                │
                     ┌──────────▼──────────┐
                     │    Service Facade   │
                     │   (future phase)    │
                     └──────────┬──────────┘
                                │ publishes
                                ▼
              ┌─────────────────────────────────┐
              │           Event Bus              │
              │                                  │
              │  publish → dispatcher → handlers │
              │            │                     │
              │  subscribe ← registry            │
              │                                  │
              │  lifecycle hooks (before/after)  │
              │  error hooks                     │
              │  logging hooks                   │
              └─────────────────────────────────┘
                          │           │
              ┌───────────▼───┐ ┌───▼───────────┐
              │  Service A    │ │  Service B    │
              │  (handler)    │ │  (handler)    │
              └───────────────┘ └───────────────┘
```

### Event Flow

```
Publisher                          Event Bus                       Subscriber
    │                                 │                               │
    │  publish("CallCreated",...)      │                               │
    │────────────────────────────────►│                               │
    │                                 │  beforeEvent hook             │
    │                                 │──┐                            │
    │                                 │  │ (log, trace start)         │
    │                                 │◄─┘                            │
    │                                 │                               │
    │                                 │  dispatch to sync handlers    │
    │                                 │──────────────────────────────►│
    │                                 │◄──────────────────────────────│
    │                                 │                               │
    │                                 │  schedule async handlers      │
    │                                 │──┐                            │
    │                                 │  │ (queueMicrotask)           │
    │                                 │  │  ────────────────────────► │
    │                                 │◄─┘                            │
    │                                 │                               │
    │                                 │  afterEvent hook              │
    │                                 │──┐                            │
    │                                 │  │ (log, trace end, metrics)  │
    │                                 │◄─┘                            │
    │◄────────────────────────────────│                               │
    │  { eventId, syncCount, errors }  │                               │
```

---

## Module Structure

```
backend/src/event-bus/
├── types.ts          Event + EventMetadata + EventEnvelope + EventHandler + hook interfaces
├── errors.ts         EventBusError, EventPublishError, EventSubscriptionError
├── registry.ts       SubscriberRegistry — handler add/remove/query
├── dispatcher.ts     EventDispatcher — routes events to handlers, runs lifecycle hooks
├── publisher.ts      Publisher — creates proper Event with metadata, publishes via bus
├── bus.ts            DefaultEventBus — concrete implementation, ties all modules together
├── hooks.ts          Pre-built hooks: createEventLoggerHook(), createLifecycleHook()
└── index.ts          Public exports
```

### Dependency Graph

```
types.ts  ←─────── errors.ts
  │                    │
  ├← registry.ts      │
  │     │              │
  │     └← dispatcher  │
  │           │        │
  │           └← bus   │
  │              │     │
  └──────────────┼─────┘
          publisher
              │
              └── index.ts ←── hooks.ts
```

No circular dependencies. Event Bus imports only from `types.ts`, `registry.ts`, `dispatcher.ts`, `errors.ts`, and the shared logger (`../common/logger.js` in hooks). No business module is imported.

---

## Public Interfaces

### Event (generic envelope)

```typescript
interface Event<T = unknown> {
  type: string;          // PascalCase, e.g. "CallCreated"
  version: number;       // Payload schema version, start at 1
  payload: T;            // Typed via generic parameter
  metadata: EventMetadata;
}

interface EventMetadata {
  eventId: string;        // UUID
  timestamp: string;      // ISO 8601
  correlationId: string;  // Trace chain identifier
  causationId?: string;   // Parent event ID (for causality chains)
  source: string;         // Publishing service/module name
}

interface EventEnvelope<T = unknown> {
  event: Event<T>;
  publishedAt: string;
}
```

### EventBus (core interface)

```typescript
interface EventBus {
  publish<T>(event: Event<T>): Promise<PublishResult>;
  subscribe<T>(eventType: string, handler: EventHandler<T>, options?: SubscribeOptions): symbol;
  unsubscribe(id: symbol): boolean;
  getSubscriberCount(eventType?: string): number;
  onBeforeEvent(hook: BeforeEventHook): void;
  onAfterEvent(hook: AfterEventHook): void;
  onError(hook: ErrorHook): void;
  shutdown(): Promise<void>;
  isShutdown(): boolean;
}
```

### Publisher (convenience layer)

```typescript
class Publisher {
  constructor(bus: EventBus, source: string);

  publish<T>(
    type: string,
    version: number,
    payload: T,
    options?: { correlationId?: string; causationId?: string },
  ): Promise<EventEnvelope<T>>;

  withSource(source: string): Publisher;
}
```

### EventHandler

```typescript
type EventHandler<T = unknown> = (event: Event<T>) => Promise<void> | void;

interface SubscribeOptions {
  async?: boolean;    // true = fire-and-forget (via microtask)
  priority?: number;  // Higher runs first (default 0)
  name?: string;      // Handler name for error reporting
}
```

### PublishResult

```typescript
interface PublishResult {
  eventId: string;
  type: string;
  syncHandlerCount: number;
  asyncHandlerCount: number;
  errors: PublishHandlerError[];
}

interface PublishHandlerError {
  handlerName: string;
  error: Error;
}
```

### Lifecycle Hooks

```typescript
interface BeforeEventHook {
  (event: Event): void | Promise<void>;
}

interface AfterEventHook {
  (event: Event, result: PublishResult): void | Promise<void>;
}

interface ErrorHook {
  (error: Error, event: Event): void | Promise<void>;
}
```

---

## Sync vs Async Dispatch

| Aspect | Sync Handler | Async Handler |
|--------|-------------|---------------|
| Registration | `subscribe(type, handler)` | `subscribe(type, handler, { async: true })` |
| Execution | Before `publish()` resolves | Via `queueMicrotask()` after `publish()` returns |
| Error impact | Reported in `PublishResult.errors` | Caught by error hooks only |
| Use case | Validation, logging, state mutation | Notifications, side effects |

---

## Error Handling

- `EventBusError` — base class with `code` field
- `EventPublishError` — publish failures
- `EventSubscriptionError` — subscription failures
- Handler errors during sync dispatch are collected in `PublishResult.errors` and reported to error hooks
- Handler errors during async dispatch are caught by error hooks (not surfaced in `PublishResult`)
- Lifecycle hook errors are silently swallowed (hooks must not break dispatch)

---

## Future Extension Points

The architecture is designed for these future capabilities without modifying the public API:

| Capability | Extension Point | When |
|-----------|----------------|------|
| Persistence | `onAfterEvent` hook can write to DB | Phase 4 |
| Distributed messaging | Replace `DefaultEventBus` with Redis PubSub implementation of `EventBus` | Phase 4+ |
| Tracing | `createLifecycleHook()` stubs — add OpenTelemetry spans | Phase 2+ |
| Retries | Add retry wrapper in `dispatcher.ts` around sync handlers | Phase 2+ |
| Dead letter queue | Add hook to write failed events to DLQ | Phase 2+ |
| Metrics | `onAfterEvent` hook emits counter/duration metrics | Phase 2+ |
| Handler timeouts | Optional timeout wrapper in `dispatcher.ts` | Phase 2+ |

---

## Files Added

| File | Lines | Purpose |
|------|-------|---------|
| `backend/src/event-bus/types.ts` | 65 | Core type definitions |
| `backend/src/event-bus/errors.ts` | 22 | Error classes |
| `backend/src/event-bus/registry.ts` | 83 | Handler registry |
| `backend/src/event-bus/dispatcher.ts` | 117 | Event dispatch logic |
| `backend/src/event-bus/publisher.ts` | 45 | Publisher abstraction |
| `backend/src/event-bus/bus.ts` | 84 | EventBus implementation |
| `backend/src/event-bus/hooks.ts` | 81 | Logger + lifecycle hooks |
| `backend/src/event-bus/index.ts` | 24 | Public exports |

**Total: 8 files, ~521 lines of TypeScript**
