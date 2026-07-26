# Event Bus — Principal Engineer Review

> **Reviewer:** OpenCode (Principal Engineer simulation)
> **Scope:** All 8 Event Bus files under `backend/src/event-bus/`
> **Mode:** Pre-merge infrastructure review

---

## Overall Score

**6.5 / 10**

The Event Bus is structurally sound but has a cluster of issues that range from naming problems to real reliability risks. It is neither over-engineered nor under-engineered in aggregate — it is **unevenly engineered**: some parts are abstracted too far (`Publisher`, `EventEnvelope`, dead error subclasses), while other parts lack necessary safeguards (no timeouts, no bulk unsubscribe, no handler isolation).

The core design (interface-based, typed, sync+async, lifecycle hooks) is correct. The execution has rough edges that will compound as the codebase grows.

---

## Strengths

| Area | Why |
|------|-----|
| **No business leakage** | Zero references to Voice, Calls, Providers, AI, Android, Notifications. Passes the strictest purity test. |
| **Interface-first** | `EventBus` interface allows swapping implementations (in-process → Redis → Kafka) without changing any subscriber or publisher code. |
| **No external dependencies** | Uses only `node:crypto` (built-in) and the existing `pino` logger. Zero new npm packages. |
| **Type-safe payloads** | `Event<T>` with generic `T` prevents type confusion at compile time. Handlers are typed per event type. |
| **Sync + async strategy** | Sync handlers for critical path (validation, state mutation). Async handlers for side effects (notifications, logging). Correct default (sync). |
| **Hook system** | `before`/`after`/`error` lifecycle hooks are the right extensibility mechanism. Clean separation from dispatch logic. |
| **No circular dependencies** | Verified import graph is a DAG. |
| **Modest size** | ~521 lines is appropriate for an event bus backbone. Not a god object. |
| **Testable registry** | `SubscriberRegistry` can be injected into `DefaultEventBus`, enabling unit tests without instantiation. |

---

## Weaknesses

### Critical (must fix before production)

| # | Issue | File | Impact |
|---|-------|------|--------|
| 1 | **No handler timeout** | `dispatcher.ts:50-52` | A single hung sync handler blocks every subsequent handler for that event type AND blocks the publisher indefinitely. With 30+ services, one slow handler takes down the entire bus. |
| 2 | **Async errors are invisible to the publisher** | `dispatcher.ts:65-67` | Async handler errors only reach error hooks — they are NOT in `PublishResult.errors`. The publisher believes the event was fully handled when it was not. This breaks at-least-once semantics. |
| 3 | **No bulk unsubscribe / scope cleanup** | `registry.ts:35-44` | When a service shuts down, its handlers remain registered. There is no way to unregister all handlers for a service at once. Services must track every `symbol` returned by `subscribe()` — a leak-prone pattern. |
| 4 | **`symbol` as handler ID is unmanageable** | `registry.ts:19` | Symbols are unique but opaque — they cannot be serialized, logged meaningfully, or observed in debugging. A string-based ID (namespace-prefixed) would allow runtime introspection, structured logging, and bulk operations. |

### Medium (should fix before Phase 2)

| # | Issue | File | Impact |
|---|-------|------|--------|
| 5 | **`EventDispatcher` and `DefaultEventBus` have overlapping hook management** | `bus.ts:63-73`, `dispatcher.ts:18-40` | The bus exposes `onBeforeEvent/onAfterEvent/onError` which delegate to the dispatcher's `addBeforeHook/addAfterHook/addErrorHook`. The dispatcher's hook management methods are essentially private but technically public. This is a leaky abstraction — the dispatcher should accept hooks via constructor or bus should manage hooks directly. |
| 6 | **`EventEnvelope` is nearly redundant with `Event`** | `types.ts:16-19` | `publishedAt` is computed as `new Date().toISOString()` after publishing — it differs from `metadata.timestamp` only by the dispatch duration (microseconds for sync). In practice, callers will use one or the other, never both. This adds complexity without value. |
| 7 | **`Publisher` class is a thin wrapper that could be a function** | `publisher.ts:5-40` | The class does one thing: create an `Event` object with metadata and delegate to `EventBus.publish()`. This is a factory function, not a class. Making it a class implies it has state or lifecycle — it has neither (the `source` string is the only parameter, and `withSource()` creates a new instance every call). |
| 8 | **`createLifecycleHook()` returns no-op stubs** | `hooks.ts:67-79` | Empty stubs with "future use" comments are dead code. They add complexity (importers may believe tracing is active), they are untested, and they will likely be rewritten when the actual tracing/metrics implementation arrives. |
| 9 | **`EventPublishError` and `EventSubscriptionError` are defined but never thrown** | `errors.ts:11-23` | Dead code. The error hierarchy suggests more error types exist than actually do. If these are never thrown, they should not exist. |
| 10 | **`createLifecycleHook` naming mismatch** | `hooks.ts:67` | Returns `onBeforeEvent`/`onAfterEvent` but the bus expects `before`/`after`/`error` shaped hooks. User must re-map properties. The return shape doesn't match any interface — it's ad-hoc. |

### Minor (design debt, address over time)

| # | Issue | File | Impact |
|---|-------|------|--------|
| 11 | **`async` naming in `SubscribeOptions` is ambiguous** | `types.ts:24` | `async` is a JavaScript keyword and doesn't describe the behavioral implication. A sync handler runs before `publish()` resolves. An async handler runs via `queueMicrotask`. The difference is reliability, not just asynchrony. `dispatchMode: 'sync' \| 'fireAndForget'` would be clearer. |
| 12 | **Priority documentation gap** | `registry.ts:48-49` | Priority sorts descending (higher = runs first), but there is no documentation of the range or convention. Without a convention, all handlers will default to `0`, making the priority system dead weight. |
| 13 | **`sort()` on every `get()` call is wasteful** | `registry.ts:48` | `registry.get()` is called once per `dispatch()`, and it calls `sort()` on every call. For events with many subscribers (e.g., `CallCreated` with 10+ handlers), this is O(n log n) on every publish. Priority ordering should be maintained on insertion. |
| 14 | **No wildcard / catch-all subscription** | `registry.ts` | Auditing, metrics, event store, and debug tools need to observe all events. Currently, the only way to observe all events is via `before`/`after` hooks, which don't get typed payloads and don't run in the handler context. A `subscribe('*', handler)` pattern is needed. |
| 15 | **Handler identity via reference equality is fragile** | `dispatcher.ts:30-40` | Removing a hook requires passing the exact function reference that was added. If the hook is an inline arrow function, removal is impossible. Same issue affects `removeAfterHook` and `removeErrorHook`. |
| 16 | **`shutdown()` clears registry but not hooks** | `bus.ts:75-78` | `shutdown()` calls `registry.clear()` but does NOT clear the dispatcher's hook arrays (`beforeHooks`, `afterHooks`, `errorHooks`). After shutdown, hooks still hold references to handler closures, preventing garbage collection. |
| 17 | **`event.version` is unused** | `types.ts:11` | The `version` field exists on every event but is never validated, checked, or used for schema evolution. It's metadata without behavior — the definition of dead weight. Either enforce it or drop it. |
| 18 | **`isShutdown()` is sync but `shutdown()` is async** | `bus.ts:25-26` | Minor consistency issue. `shutdown()` is `Promise<void>` presumably for future graceful draining. `isShutdown()` is sync. This is fine as long as documented, but the inconsistency suggests `shutdown()` doesn't actually need to be async right now. |
| 19 | **Anonymous handler names in minified builds** | `registry.ts:24` | `handler.name || 'anonymous'` works in development but will fail in production where bundlers minify function names. All real-world handler names will become `'anonymous'`, making error logs useless. |
| 20 | **`entries()` exposes internal mutable array references** | `registry.ts:69-75` | The method copies the Map keys but the arrays inside are also copies. Actually, the arrays ARE new (spread syntax creates a shallow copy), so this is safe. False alarm — this is fine. |

---

## Architecture Questions

### 1. Is this Event Bus over-engineered?

**Partially.** The `Publisher` class, `EventEnvelope` type, `createLifecycleHook()` stub, and unused error subclasses add abstraction without value. Removing those would reduce the codebase by ~80 lines (~15%) without losing any capability.

The core bus (types, registry, dispatcher, bus interface + implementation) is appropriately engineered — not over-, not under-.

### 2. Is it under-engineered?

**Partially.** Missing features for production readiness:
- No handler timeout
- No subscription scoping / bulk cleanup
- No dead-letter mechanism for async failures
- No wildcard subscriptions

These are not features — they are **reliability requirements** for any event-driven system. The bus will work for 1-2 services in a demo, but adding the 3rd service will expose these gaps.

### 3-5. Fewer abstractions?

Yes. The implementation consolidates naturally into 4-5 files instead of 8:

- Merge `errors.ts` unused subclasses into `types.ts` or remove them
- Merge `dispatcher.ts` into `bus.ts` (hooks can be managed at the bus level)
- Replace `Publisher` class with a `createEvent()` factory function in `types.ts`
- Remove `EventEnvelope` (use `Event` directly)

Result: `types.ts`, `registry.ts`, `bus.ts`, `hooks.ts`, `index.ts` — 5 files instead of 8.

### 6. Hidden circular dependency risks?

**None.** Verified.

### 7. Service Locator risk?

**Low but non-zero.** The Event Bus is a communication mechanism, not a service locator — services publish events and handle events, they don't resolve services through the bus. However, if a shared dependency injection container passes the same `EventBus` instance everywhere, and services use the `Publisher` to indirectly invoke each other, the bus could become a de facto service bus in a way that mimics service location. Architecture rules should explicitly forbid using the bus for request-response patterns.

### 8. Scale support?

- **10 services**: Yes. In-process bus handles this easily. Sync handlers run sequentially per event type, which is fine at this scale.
- **30 services**: **At risk.** With 30 services potentially subscribing to shared events (e.g., `CallCreated`), a single slow handler blocks all others. Need handler timeouts and potentially parallel dispatch (`Promise.all` for independent handlers).
- **100 services**: **No.** The in-process bus will not scale to 100 services. At this point, the bus must be extracted to Redis PubSub or Kafka. The `EventBus` interface supports this swap, but the in-process infrastructure (single event loop, sequential sync dispatch, memory-backed registry) becomes a bottleneck well before 100 services.

### 9. Distributed messaging evolution?

**Yes.** The `EventBus` interface is clean enough that a `RedisEventBus` or `KafkaEventBus` can implement it without changing subscribers. The challenge will be that distributed event serialization (JSON → Buffer) will require payloads to be serializable, which the current `Event<T>` doesn't enforce.

### 10-14. Future capabilities (persistence, tracing, metrics, retries, DLQ)?

| Capability | Can add without API changes? | Current blocker |
|-----------|------------------------------|-----------------|
| Persistence | ✅ Via `onAfterEvent` hook | None |
| Tracing | ✅ Via hook or handler middleware | None |
| Metrics | ✅ Via hook | None |
| Retries | ⚠️ Requires changes | Sync errors are returned in `PublishResult` but not retried. Would need dispatcher changes. |
| Dead-letter queue | ⚠️ Requires changes | Async errors are invisible to `PublishResult`. DLQ needs visibility into async failures. |
| Event replay | ❌ Requires significant changes | Replay needs event persistence (Phase 4), a replay API, and idempotency guarantees. None exist. |

### 15. Cancellation support?

**No.** There is no `AbortSignal` or cancellation mechanism. If a handler starts an async operation that needs cancellation (e.g., an HTTP request), there is no way to cancel it via the bus. For MVP this is acceptable, but for long-running handlers it will be needed.

---

## File-by-File Code Review

### 1. `types.ts` — 52 lines

**Purpose:** All public type definitions for the Event Bus.

**Responsibilities:**
- Define event structure (`Event<T>`, `EventMetadata`, `EventEnvelope`)
- Define handler type (`EventHandler<T>`)
- Define options and result types (`SubscribeOptions`, `PublishResult`, `PublishHandlerError`)
- Define hook signatures (`BeforeEventHook`, `AfterEventHook`, `ErrorHook`)

**Problems:**
- `EventEnvelope` is redundant with `Event`
- `async` field in `SubscribeOptions` is ambiguous naming
- `version` in `Event` is declared but never enforced or used by any other module
- `PublishResult` includes `asyncHandlerCount` but async errors are never surfaced here — misleading
- `BeforeEventHook` and `AfterEventHook` use generic `Event` (i.e., `Event<unknown>`) — hooks lose payload typing. Acceptable for infrastructure hooks, but limits typed hooks.

**Complexity Score:** 2/10 (simple data types)

**Verdict:** **Keep** — with minor improvements (remove `EventEnvelope`, rename `async`)

---

### 2. `errors.ts` — 23 lines

**Purpose:** Error class hierarchy.

**Responsibilities:**
- Base `EventBusError` with `code` field
- `EventPublishError` — never thrown
- `EventSubscriptionError` — never thrown

**Problems:**
- Two of three classes are dead code
- `EventBusError.code` exists but the bus never uses it for programmatic error handling — it throws generic `Error` on shutdown (line 41 of `bus.ts`), not `EventBusError`

**Complexity Score:** 1/10 (trivial, partially dead)

**Verdict:** **Simplify** — keep only `EventBusError` (it is used conceptually), remove the two subclasses until they are actually thrown.

---

### 3. `registry.ts` — 76 lines

**Purpose:** In-memory handler registry with typed subscriptions.

**Responsibilities:**
- Add/remove/get handlers by event type
- Maintain insertion-sorted order (with priority) on access
- Query subscriber existence and count

**Problems:**
- `remove()` is O(n \* m) where n = event types, m = handlers per type — iterates ALL entries to find by symbol. A reverse map (symbol → eventType) would make this O(1).
- `get()` sorts on every call — O(n log n) per publish. Should maintain sorted order on insertion.
- `symbol` as ID is opaque and unmanageable at scale
- `entries()` method is used nowhere in the codebase
- `hasSubscribers()` is never called outside tests

**Complexity Score:** 4/10 (simple data structure, minor perf issues)

**Verdict:** **Keep** — but fix `remove()` performance (add reverse map) and defer sorting (sort on insertion, not on every read).

---

### 4. `dispatcher.ts` — 116 lines

**Purpose:** Routes published events to registered handlers, manages lifecycle hooks.

**Responsibilities:**
- Dispatch sync handlers sequentially
- Schedule async handlers via `queueMicrotask`
- Run before/after/error hooks
- Collect and propagate handler errors

**Problems:**
- **No handler timeout**: a sync handler that hangs blocks the bus forever
- **Async errors are invisible to `PublishResult`**: publisher sees `asyncHandlerCount: 3, errors: []` even if all 3 fail
- Hook management methods are technically public but semantically internal — leaky abstraction
- Hook removal by reference is fragile (inline arrow functions cannot be removed)
- `notifyBeforeHooks` and `notifyAfterHooks` silently swallow hook errors — good for resilience, but bad for debugging (hook authors have no way to know their hook is broken)
- `queueMicrotask` for async handlers means they queue BEFORE the publisher receives the `PublishResult` — so by the time the caller inspects `result`, async handlers haven't even started yet

**Complexity Score:** 6/10 (moderate complexity, real reliability gaps)

**Verdict:** **Keep** — but requires significant fixes (timeout, async error reporting, remove hook management leak).

---

### 5. `publisher.ts` — 41 lines

**Purpose:** Convenience wrapper that creates `Event` objects with metadata and publishes them.

**Responsibilities:**
- Generate UUID-based `eventId`
- Generate ISO 8601 timestamps
- Assign correlation/causation IDs
- Delegate to `EventBus.publish()`

**Problems:**
- **This is a factory function, not a class.** It has one behavior (`publish`) and one piece of state (`source`). A function `createAndPublishEvent(bus, source, type, version, payload, options?)` would do the same thing without the class overhead.
- `withSource()` creates a new `Publisher` instance every call — unnecessary object churn
- `new Date().toISOString()` is called twice per publish (here and in `publish` for `publishedAt` in bus.ts... wait, no, the envelope timestamp is created here, not in bus.ts). Actually it's only called once per `publish()` call. Minor, but `Date.now()` is faster than `new Date()`.
- `version` is accepted but never validated — caller can pass any number

**Complexity Score:** 2/10 (simple, over-engineered as a class)

**Verdict:** **Simplify** — replace with `createEvent()` factory function in `types.ts`, let callers use `EventBus.publish()` directly.

---

### 6. `bus.ts` — 83 lines

**Purpose:** EventBus interface + DefaultEventBus implementation.

**Responsibilities:**
- Implement `EventBus` interface by delegating to registry + dispatcher
- Provide hook registration methods (delegating to dispatcher)
- Gate operations behind shutdown state

**Problems:**
- `shutdown()` clears registry but NOT dispatcher hooks — memory leak
- `_isShutdown` flag with prefix `_` suggests "private" but TypeScript already has `private` keyword — inconsistent style
- `EventBus` interface includes `onBeforeEvent/onAfterEvent/onError` but these are cross-cutting concerns, not core bus operations. They could be on a separate `EventBusHooks` interface to keep `EventBus` focused on publish/subscribe.
- Constructor accepts optional `SubscriberRegistry` but NOT optional `EventDispatcher` — limits testability (can't inject a mock dispatcher)

**Complexity Score:** 4/10 (clean delegation, minor issues)

**Verdict:** **Keep** — fix shutdown memory leak, make dispatcher injectable for testing.

---

### 7. `hooks.ts` — 79 lines

**Purpose:** Pre-built lifecycle hooks for logging and future tracing/metrics.

**Responsibilities:**
- `createEventLoggerHook()` — pino logging of event dispatch, completion, and errors
- `createLifecycleHook()` — empty stubs for future tracing

**Problems:**
- `createLifecycleHook()` is dead code — empty stubs with no consumers
- Logger hook uses `logger.debug` for success and `logger.warn`/`logger.error` for failures — reasonable, but `logger.debug` means in production, successful dispatches are invisible by default. This is fine for reducing noise but can make debugging hard.
- Logger hook accesses `event.metadata.eventId` etc. — correct, no issues here

**Complexity Score:** 3/10 (simple, partially dead)

**Verdict:** **Keep** — but remove `createLifecycleHook()` stub until tracing is actually being implemented.

---

### 8. `index.ts` — 24 lines

**Purpose:** Public API barrel file.

**Responsibilities:**
- Export all public types and classes

**Problems:**
- Exports `SubscriberRegistry` and `EventDispatcher` which are internal implementation details — callers should only need `EventBus`, `DefaultEventBus`, `Publisher`, types, hooks, and errors. Exposing registry and dispatcher couples consumers to implementation.
- Exports `createLifecycleHook` which is a dead stub

**Complexity Score:** 1/10 (trivial)

**Verdict:** **Simplify** — export only what consumers need: `DefaultEventBus`, `Publisher`, `createEventLoggerHook`, error classes, and type exports. Hide `SubscriberRegistry`, `EventDispatcher`, `createLifecycleHook`.

---

## API Review

### EventBus Interface

```typescript
interface EventBus {
  publish<T>(event: Event<T>): Promise<PublishResult>;
  subscribe<T>(eventType, handler, options?): symbol;
  unsubscribe(id: symbol): boolean;
  getSubscriberCount(eventType?: string): number;
  onBeforeEvent(hook: BeforeEventHook): void;
  onAfterEvent(hook: AfterEventHook): void;
  onError(hook: ErrorHook): void;
  shutdown(): Promise<void>;
  isShutdown(): boolean;
}
```

**What's right:**
- `publish` and `subscribe` are the correct primitives
- Generics on `publish<T>` and `subscribe<T>` provide type safety
- `shutdown` is essential for graceful teardown

**What's wrong:**
- `subscribe` returns `symbol` — should return a `Disposable` or `Subscription` object with an `unsubscribe()` method, enabling `using` keyword support (TypeScript 5.2+)
- `getSubscriberCount` is an observability concern, not a core bus operation — should be on a separate `EventBusAdmin` interface
- `onBeforeEvent/onAfterEvent/onError` are hooks, not bus operations — separate interface
- `unsubscribe` requires tracking an opaque token — `subscribe` should return a self-disposing object
- No `AbortSignal` support for cancellation
- No way to check if a specific handler is registered

**Suggested interface split:**

```typescript
interface EventBus {
  publish<T>(event: Event<T>): Promise<PublishResult>;
  subscribe<T>(eventType: string, handler: EventHandler<T>, options?: SubscribeOptions): Subscription;
}

interface Subscription {
  readonly eventType: string;
  unsubscribe(): void;
  [Symbol.dispose](): void;
}

interface EventBusHooks {
  onBeforeEvent(hook: BeforeEventHook): void;
  onAfterEvent(hook: AfterEventHook): void;
  onError(hook: ErrorHook): void;
}

interface EventBusLifecycle {
  shutdown(): Promise<void>;
  isShutdown(): boolean;
}
```

### Publisher

```typescript
class Publisher {
  constructor(bus: EventBus, source: string);
  publish<T>(type, version, payload, options?): Promise<EventEnvelope<T>>;
  withSource(source): Publisher;
}
```

**What's right:**
- Convenience wrapper reduces boilerplate for callers
- Accepts `correlationId` for trace chains

**What's wrong:**
- Class with no stateful behavior — should be a function
- `withSource()` creates garbage
- Returns `EventEnvelope` — should return `PublishResult` for consistency

---

## Migration Readiness

### Can existing services migrate one-by-one?

**Yes.** The Event Bus is additive — no existing code is modified. Migration pattern:

1. Instantiate `EventBus` in `index.ts`
2. Call `EventBus.publish()` alongside the existing direct call
3. Add a subscriber that mirrors the direct call (dual-write)
4. Verify events are handled correctly
5. Remove the direct call

### Will migration require downtime?

**No.** The Event Bus is an in-process addition. No database, no external service, no API change. Hot-reloadable in dev.

### Will migration require breaking APIs?

**No.** The Event Bus is purely internal. REST API responses, WebSocket messages, and MCP tool outputs are unchanged. External consumers see zero difference.

### Safest migration order:

1. **Instantiate bus in `index.ts`** — add `DefaultEventBus` creation at startup
2. **Add event type constants** — create `EventTypes` object alongside each service (e.g., `CallEvents.Created`, `CallEvents.Ended`)
3. **Publish alongside existing code** — every action publishes an event AND does the existing direct call
4. **Subscribe to own events** — services subscribe to their own events (no-op initially, validates wiring)
5. **Cross-service subscriptions** — one service at a time, add subscribers that replace direct calls
6. **Remove direct calls** — after all consumers have migrated

---

## Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Hung handler blocks bus | Medium | High | Add handler timeout (configurable per handler or per bus) |
| Async handler failure goes undetected | High | High | Surface async errors in `PublishResult` or add event-level error callback |
| Handler leaks on service restart | Medium | Medium | Add subscription scoping (e.g., `ServiceScope` that bulk-unsubscribes) |
| Minified handler names in production | High | Medium | `handler.name` becomes empty string after minification — all errors show "anonymous" |
| Concurrent publish ordering undefined | Low | Medium | Document that sequential `await publish()` guarantees FIFO; concurrent does not |
| No `AbortSignal` for long handlers | Low | Low | Acceptable for MVP; add when handlers perform I/O that needs cancellation |
| `new Date().toISOString()` clock skew | Low (in-process) | Medium | Acceptable for MVP; switch to `performance.now()`-based ordering when distributed |

---

## Future Risks

| Risk | Why |
|------|-----|
| **In-process bus as scaling bottleneck** | The in-process bus is single-threaded and memory-backed. It will not scale to the distributed deployment model. This is acceptable for MVP but must be explicitly called out as a future bottleneck. |
| **No schema registry** | Without a central event schema registry, different services may publish different payload shapes for the same event type. TypeScript generics help at compile time but provide zero runtime protection. |
| **Event version field useless** | `version` exists but is never validated. When schema evolution happens, there will be no migration path, no compatibility checking, no version negotiation. |
| **No dead letter queue** | Events that fail processing are silently ignored (async) or returned in errors array (sync). There is no mechanism to store, inspect, or replay failed events. |
| **Debugging async flows** | With `queueMicrotask` and no tracing, debugging a chain of async event handlers will be extremely difficult. |

---

## Over-Engineering Analysis

### What is over-engineered:

1. **`Publisher` as a class** — 41 lines for what should be a 10-line function
2. **`EventEnvelope`** — `publishedAt` is `metadata.timestamp + epsilon`. Never useful.
3. **`createLifecycleHook()`** — empty stubs are code noise
4. **Error subclass hierarchy (3 classes, 2 unused)** — keeps the promise of a rich error hierarchy without delivering it
5. **`SubscriberRegistry.entries()`** — method is never used in production code
6. **`SubscriberRegistry.hasSubscribers()`** — never used in production code

### What is under-engineered:

1. **No handler timeout** — single most likely production failure mode
2. **Async error invisibility** — fire-and-forget without feedback is dangerous
3. **No subscription scoping** — `symbol`-based tracking is fragile
4. **No wildcard subscriptions** — missing essential pattern for observability
5. **Hook removal by reference** — fragile, untestable pattern

### Net assessment:

The over-engineered parts add ~80 lines of code. The under-engineered parts represent real reliability gaps. The net is that the bus is **about right in size but wrong in prioritization** — it abstracts the easy things and omits the hard things.

---

## Simplification Opportunities

| Change | Lines Saved | Benefit |
|--------|------------|---------|
| Remove `Publisher` class, export `createEvent()` + `publishEvent()` functions | ~30 | Less abstraction, same capability |
| Remove `EventEnvelope` type, return `Event<T>` from publish | ~5 | Less type surface |
| Remove `createLifecycleHook()` | ~15 | Dead code elimination |
| Remove unused error subclasses | ~12 | Dead code elimination |
| Merge `dispatcher.ts` into `bus.ts` | ~0 (net) | Fewer files, less indirection |
| Remove `entries()` and `hasSubscribers()` from `SubscriberRegistry` | ~15 | Dead code elimination |

**Total potential reduction: ~77 lines (~15%) without losing any capability.**

---

## Recommended Improvements

### Before Phase 2 (must fix):

1. **Add handler timeout** — wrap sync handler execution with a configurable timeout (e.g., `setTimeout` + reject)
2. **Surface async errors** — track async handler completion and surface failures via a collector or event-level callback
3. **Add subscription scoping** — introduce `SubscriptionScope` or return `{ unsubscribe: () => void }` instead of bare `symbol`
4. **Replace `symbol` with string IDs** — use namespace-prefixed strings (e.g., `"calls:handler:notify-presence"`) for debuggability
5. **Remove `createLifecycleHook()`** — dead code
6. **Remove unused error subclasses** — dead code
7. **Fix `shutdown()` to clear dispatcher hooks** — memory leak

### Before Phase 3 (should fix):

8. **Add wildcard subscription** — `subscribe('*', handler)` for logging, metrics, audit
9. **Document priority conventions** — or remove priority if unused
10. **Optimize registry** — maintain sorted order on insertion, add symbol→eventType reverse map
11. **Separate `EventBus` interface** — split hooks into `EventBusHooks`, lifecycle into `EventBusLifecycle`
12. **Replace `Publisher` class with function** — simpler, same capability
13. **Limit index.ts exports** — hide internal implementation details

### Future considerations:

14. **`AbortSignal` support** — for handler cancellation
15. **Event schema registry** — runtime event payload validation
16. **Handler middleware pipeline** — for cross-cutting concerns (validation, enrichment, auth)
17. **Parallel sync dispatch for independent handlers** — `Promise.all` for handlers that don't share state
