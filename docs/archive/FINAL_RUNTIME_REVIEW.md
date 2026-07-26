# Runtime Review — RC-1

## Cold Startup

1. `validateConfig()` — fails early if `SERVICE_TOKEN` missing or invalid `PERSISTENCE_MODE`
2. Create empty `MetricsCollector` — zero cost
3. Create `DefaultEventBus` + register 14 empty log-only subscribers
4. Create Fastify app with bodyLimit, requestId header, trustProxy
5. Create `CleanupScheduler` — empty
6. Create InMemory repos (always, even in database mode ← wasted allocation)
7. If `persistenceMode === 'database'`:
   - Create `pg.Pool(connectionString)` — this connects immediately (pg pools lazily connect by default, but the Pool constructor parses the string)
   - Create `RecoveryManager`
   - `await recoveryManager.loadFromDatabase()` — loads ALL sessions from DB into InMemory (wasted in database mode)
   - Wrap with `PrimaryDatabase*Repository` — thin delegation with debug logging
8. Wrap with `Instrumented*Repository` — adds timing, retry, slow-query logging
9. Create `VoiceBridgeService`
10. Register Helmet, Compress, CORS, RateLimit
11. Create `LifecycleCoordinator` with the instrumented repos
12. `voiceBridgeService.setLifecycleCoordinator(lifecycleCoordinator)` — setter injection
13. `await recoveryManager.rebuildTimers(...)` — rebuilds scheduler timers
14. Create `DeletionCoordinator`, `SessionSweeper`, initial `sweep()`, `start()`
15. Create `DatabaseHealthMonitor`, `start()`
16. Register routes
17. `await app.ready()`
18. `await app.listen()`
19. `createSignalingServer(app.server)` — starts WebSocket server
20. Record `startup.duration` metric

**Issue:** If the database is unavailable during startup in `database` mode, the Pool constructor won't fail (it's lazy). But the first `loadFromDatabase()` query will throw, and the error propagates to `main().catch()` which calls `process.exit(1)`. The service never starts.

## Recovery Walkthrough

Phase A (`loadFromDatabase`):
- Lists ALL sessions from DB
- Creates each in InMemory repo (one-by-one, no batch)
- Lists ALL callbacks from DB
- Saves each in InMemory repo
- Logs counts

Phase B (`rebuildTimers`):
- Lists callbacks from InMemory
- For each callback: looks up session, computes `delayMinutes`, calls `lifecycleCoordinator.resumeCallback()`
- `resumeCallback()` schedules two timers: `resume:X` and `pause-ttl:X`
- Lists sessions from InMemory
- For each paused session without a callback: calls `lifecycleCoordinator.recoverOrphanedPause()`

**Issue:** If a session was paused at time T and the recovery happens at T+25h, the pause-ttl (24h) has already expired. `recoverOrphanedPause` correctly cancels it via `handlePauseExpiry`. 

**Issue:** `delayMinutes` is computed as `Math.max(1, Math.floor((cb.resumeAt - pausedMs) / 60000))`. If `pausedAt` was never set (missing field), it falls back to `Date.now()` which skews the delay calculation. There's no validation that `pausedAt` exists in the DB record.

## First Request

`POST /api/v1/calls` with body → parsed → validated → `createCall()` → `sessionRepo.create()` → event published → notification sent.

Flow is synchronous. The create call returns `201` with `call_id`.

## Concurrent Requests (1000 simultaneous)

- Fastify handles HTTP concurrency via async handlers
- InMemory repos use `Map` which is NOT thread-safe in pure JS, BUT Node.js is single-threaded for JS execution. `async/await` yields to the event loop, so concurrent requests interleave. However:
  - `Map.get/set/delete` are individual micro-operations — no yield point inside them
  - Between `await sessionRepo.findById()` and `session.messages.push(msg)`, another request could modify the session
  - **Race:** `addMessage` reads session, pushes message, then calls `sessionRepo.save(session)`. If two AI messages arrive concurrently for the same session, one message could be lost (lost update problem)

**Evidence:**

```typescript
// service.ts:120-147
const session = await this.sessionRepo.findById(callId);
if (!session) return undefined;
session.messages.push(msg);  // ← concurrent access: both requests get the same sessions
// ...
await this.sessionRepo.save(session);  // ← second save overwrites first
```

In database mode with `PrimaryDatabaseSessionRepository`, the `save()` is an `INSERT ... ON CONFLICT DO UPDATE` with no version check. Last writer wins.

## Pause → Resume → Callback

- `scheduleCallback()` sets `session.status = 'paused'`, saves callback to repo, schedules two timers
- Timer fires, `handleResume` reads session, if not paused → no-op (correct)
- If still paused: sets status to `pending`, publishes event, notifies phone, deletes callback

**Race:** If the user manually resumes via a second request while the timer is about to fire:
- Request 1 sets status to `pending`, deletes callback
- Timer `handleResume` fires, reads session (status = `pending`, not paused), returns immediately
- Both end up thinking the other handled it → correct behavior (resumed once)

**Double-resume race:** If two timer-like events could fire simultaneously (only one timer per call), impossible. But if the recovery system and a timer fire for the same call:
- Recovery loads a callback with `resumeAt = 1000`
- Timer fires at `resumeAt`, calls `handleResume`, deletes callback from DB
- Recovery builds timer for the same callback, but `handleResume` checks `status !== 'paused'` → no-op
- Correct, but the timer was wasted. Not a correctness issue.

## Shutdown

1. `sessionSweeper.stop()` — clears interval
2. `dbHealth.stop()` — clears interval
3. `verifier.stop()` — clears interval
4. `cleanupScheduler.shutdown()` — clears ALL timers without executing them
5. `await app.close()` — stops accepting new connections, waits for in-flight requests
6. `signalingServer?.close()` — closes WebSocket server
7. `await eventBus.shutdown()` — clears registry
8. `logger.flush?.()` — pino flush
9. `await pool.end()` — closes PG connections

**Issue:** `sessionSweeper.stop()` stops the sweeper but does not await any in-progress `sweep()`. If a sweep is mid-execution deleting sessions, it may be interrupted.

**Issue:** `cleanupScheduler.shutdown()` uses `clearTimeout()` on all pending timers. If the app has scheduled a `resume` callback that was supposed to fire in 100ms, and shutdown starts, the callback is silently dropped. The session remains `paused` in the DB. On next restart, recovery picks it up. But if the restart is not for 24 hours (pause-ttl), the session stays paused.

## Restart → Recovery

- Cold startup re-reads all sessions from DB
- RecoveryManager.loadFromDatabase + rebuildTimers
- Sessions that were paused get their timers rebuilt

**This works correctly.** No data loss for sessions. In-flight callback timers are re-created.

## Production Deployment

- Rolling update with `maxUnavailable: 0`
- Old pod drains connections → shutdown → new pod starts → recovery
- During transition, both pods share the DB
- In `database` mode, both pods read/write the same DB — correct

**Issue:** WebSocket connections are pinned to pods. During rolling update, connected phones disconnect and must reconnect. The `phoneConnections` map is per-pod. Reconnection triggers `registerPhone` which replaces the old connection. This is handled correctly in `registerPhone()`.

## Rollback

- `kubectl rollout undo` — reverts to previous image
- New pods start with old code
- DB schema remains compatible (no migrations in this release)

This works because there are no schema changes.

## Score

**Runtime: 6/10**

Deducted for: lost update race on concurrent message writes, silent timer loss on shutdown, no-op event subscribers adding latency with zero value, fire-and-forget dual-write failures, `phoneConnections` as global mutable state.
