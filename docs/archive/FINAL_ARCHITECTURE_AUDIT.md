# Architecture Audit — RC-1

## Layering

```
routes.ts → VoiceBridgeService → Repository (interface)
                                     ├── InMemorySessionRepository
                                     ├── DatabaseSessionRepository
                                     ├── DualWriteSessionRepository
                                     ├── PrimaryDatabaseSessionRepository
                                     └── InstrumentedSessionRepository
```

**Verdict:** Clean. Routes never touch repositories directly. Service never handles HTTP. Event bus is side-channel.

## Dependency Direction

Everything flows inward: HTTP → Service → Repository. Event bus subscriptions are orthogonal.

Findings:
- **`service.ts` imports `WebSocket` and `phoneConnections` directly** — the Map is a module-level global, not injected. This couples the service to the signaling layer.

## Coupling

| Source | Target | Type | Risk |
|--------|--------|------|------|
| `VoiceBridgeService` | `phoneConnections` (module-global Map) | Static coupling | HIGH — untestable without mocking module-level state |
| `VoiceBridgeService` | `notifyPhone` (module-level function) | Static coupling | MEDIUM — passed as callback, but Map is imported |
| `service.ts` | `ws` package | Direct import | MEDIUM — module uses `WebSocket.OPEN` constant |

## Cohesion

Event bus subscribers (calls/presence/notifications/signaling) have near-zero cohesion — they only log. The events.firehose is published but never consumed for actual business logic.

## DI Quality

- Services use constructor DI for repositories
- `LifecycleCoordinator` receives `notifyPhone` as a function parameter — correct
- `RecoveryManager` receives concrete repos — correct
- But `VoiceBridgeService` uses module-level `notifyPhone` AND `WebSocket` — DI leak

## Repository Boundaries

**Issue: `PrimaryDatabase*Repository` is a thin delegation layer that only adds debug logging.**

```typescript
// primary-db-session-repository.ts
async findById(callId: string): Promise<VoiceCallSession | undefined> {
  const result = await this.db.findById(callId);
  logger.debug({ callId, found: !!result }, '[PrimaryDatabaseSessionRepository] findById');
  return result;
}
```

This adds no behavioral difference from `Database*Repository`. The instrumentation layer already provides observability. This is an unnecessary abstraction with no clear purpose.

## Lifecycle Management

```
startup:
  validateConfig()
  create MetricsCollector
  create EventBus + register subscriptions
  create Fastify app
  create InMemory repos (ALWAYS — even in database mode)
  create DB pool (if URL set)
  create RecoveryManager + loadFromDatabase() ← copies DB → memory
  wrap with instrumentation
  create VoiceBridgeService
  register Helmet, Compress, CORS, RateLimit
  create LifecycleCoordinator
  setLifecycleCoordinator(coordinator) ← setter, not constructor
  rebuildTimers()
  create DeletionCoordinator
  create SessionSweeper + initial sweep() + start()
  create DB health monitor + start()
  registerRoutes()
  listen()
  createSignalingServer()
```

**Issues:**

1. **InMemory repos are always created** (lines 91-92) even in `database` mode. This wastes memory and the `PrimaryDatabase*Repository` never uses them, but RecoveryManager still receives them as `memorySessionRepo`.

2. **Ordering gap:** `LifecycleCoordinator` receives `sessionRepository` AFTER instrumentation wrapping, but timers rebuilt via `RecoveryManager` write through the same repo. This is correct but fragile — a future refactor could reorder these.

3. **`cleanupScheduler` is used before it's created** — no, it's created at line 88. OK.

```
shutdown:
  sessionSweeper.stop()
  dbHealth?.stop()
  verifier?.stop()
  cleanupScheduler.shutdown()
  app.close() ← drains HTTP
  signalingServer?.close()
  eventBus.shutdown()
  logger.flush()
  pool.end()
```

**Issues:**

4. **`eventBus.shutdown()` is called after signaling closes** — but signaling publishes events during disconnect. If a WebSocket is still closing when the bus shuts down, events may fail with "EventBus is shut down". Order should be: stop accepting connections → drain active connections → shutdown bus → close pool.

5. **No timer drain.** `cleanupScheduler.shutdown()` clears all timers with `clearTimeout`. Any pending callback execution is silently discarded. If a resume callback was 1ms away from firing, it's lost.

## Event Bus

- In-process, synchronous dispatch with microtask-based async handlers
- All subscribers are no-op loggers — no business logic in any subscriber
- Shutdown blocks new publishes but doesn't wait for in-flight async handlers

**Issue:** Async handlers scheduled via `queueMicrotask` are fire-and-forget. The `shutdown()` method clears the registry but does not wait for pending microtasks. Events in flight are silently dropped.

## Scheduler (`CleanupScheduler`)

- `setTimeout` based with `unref()` — process can exit while timers are pending
- No persistence across restarts (timer state is purely in memory)
- Callbacks are fire-and-forget — errors are unhandled
- Fixed: recovery does rebuild timers, but only for `resume` and `pause-ttl` events

## Recovery

**Issue: `RecoveryManager.loadFromDatabase()` copies DB records into InMemory repos.**

In `database` mode, the InMemory repos are created but never used for reads after recovery. However, `RecoveryManager.loadFromDatabase()` still writes to them:

```
// index.ts:117
recoveryManager = new RecoveryManager(dbSessionRepo, dbCallbackRepo, sessionRepo, callbackRepo);
//                                              ^                       ^
//                                        Database*Repo            InMemoryRepo
await recoveryManager.loadFromDatabase();
//                                              ^
//                                        Copies DB → InMemory (wasted effort in database mode)
```

The recovery load is unnecessary in `database` mode since `PrimaryDatabase*Repository` reads directly from the DB.

## Persistence

**Issue: Dual-write DB failures are silently fire-and-forget:**

```typescript
// dual-write-session-repository.ts
async create(session: VoiceCallSession): Promise<void> {
  await this.memory.create(session);
  this.database.create(session).catch((err) => {  // ← fire-and-forget
    logger.error({ err, callId: session.id }, '[DualWriteSessionRepository] database create failed');
  });
}
```

The memory write is awaited. The DB write is not. On DB failure, the error is logged but the caller gets no indication. The in-memory state has the data, but the DB is silently out of sync until the next `PersistenceVerifier` run (if enabled).

## Dead Code

1. `PrimaryDatabaseSessionRepository` — no behavioral difference from `DatabaseSessionRepository` except debug logging
2. `PrimaryDatabaseCallbackRepository` — same
3. `PersistenceBurnIn` — independent tool not wired anywhere
4. All event bus subscribers in `calls/`, `notifications/`, `presence/`, `signaling/` — they only log, no business logic
5. `event-publisher.ts`'s `createEventPublisher` has a synchronous `.publish()` that catches errors silently
6. `PersistenceVerifier` — only available in `dual-write` and `database-read` modes; useful but never fires alerts externally

## Cyclic Dependencies

None detected. Dependency graph is a DAG.

## Score

**Architecture: 7/10**

Deducted for: unnecessary abstraction layer (`PrimaryDatabase*` repos), module-level mutable state (`phoneConnections` map), fire-and-forget dual-write failures, no-op event subscribers, and silent timer loss during shutdown.
