# Memory Review — RC-1

## InMemory Repositories

### `InMemorySessionRepository.sessions: Map<string, VoiceCallSession>`

- Grows with every session created
- Shrinks only via `delete()` (sweeper or explicit)
- No automatic eviction of expired sessions (relies on sweeper)

**Impact:** If sweeper interval (5 min) misses expired sessions (e.g., shutdown), memory holds stale data until next sweep. In `database` mode, this map is created but never used for reads after recovery — it's dead memory.

### `InMemoryCallbackRepository.callbacks: Map<string, CallbackData>`

- One entry per user with an active callback
- Small, bounded by active paused calls

## MetricsCollector

### Counters: `Map<string, number>`

- Grows unbounded — every unique metric name adds a new entry
- No eviction policy
- **Issue:** An attacker could trigger unique metric names (e.g., `session.findByUserId.ok` with different suffixes) to inflate the map

### Gauges: `Map<string, number>`

- Same unbounded growth issue
- `setGauge` is called with dynamic keys like `sessions.active`, `sessions.paused`, `sessions.completed`, `db.pool.total`, etc.
- Limited to known keys in practice, but no protection against arbitrary keys

### Timings: `Map<string, number[]>` with 1000-sample cap

- Bounded by `maxTimingSamples = 1000`
- Samples array is capped via splice — O(n) operation on every sample insertion when at limit

## phoneConnections Global Map

```typescript
const phoneConnections = new Map<string, WebSocket>();
```

- One entry per connected user
- Cleaned up on WebSocket close/error events
- **Potential leak:** If a WebSocket error occurs without a close event, the entry remains. The error handler logs but does not delete from the map. However, WebSocket spec guarantees `close` fires after `error`.

**Issue:** The eviction timer in `signaling/server.ts` cleans up `clientRateLimits` but NOT `phoneConnections`. The phone connections map has no external cleanup mechanism.

## Timer Leaks

### CleanupScheduler

- Each `schedule()` creates a `setTimeout` and stores it in a `Map`
- `cancel()` clears the timeout and removes from map
- `shutdown()` clears all timeouts

**Issue:** If a timer fires and the callback throws, the `timeout` object is garbage collected but an error is swallowed. The callback function is a closure that may retain references to large objects. However, since timers are cleared, this is bounded.

### DatabaseHealthMonitor

- Single interval timer, `unref()` d
- Properly cleaned up in `stop()`

### SessionSweeper

- Single interval timer, properly cleaned up in `stop()`

### PersistenceVerifier

- Single interval timer (optional), cleaned up in `stop()`

## EventBus Subscribers

- All 14 subscribers are registered during startup
- `shutdown()` calls `registry.clear()` — all handler references released
- No subscriber leaks

## Debounce/Drain Gaps

**Issue:** No mechanism to detect stalled timers. If a `resume` timer is scheduled for 24h in the future, the `CleanupScheduler` holds a reference to the timeout object and callback closure for 24 hours. The closure captures `(userId, callId, delayMinutes, resumeAt)` — small objects, negligible memory.

## Retained Closures

- `shutdown` function in `index.ts` captures `shuttingDown` flag, `app`, `signalingServer`, `pool`, `sessionSweeper`, `dbHealth`, `verifier`, `cleanupScheduler`, `eventBus`, `metrics`
- These closures persist for the lifetime of the process
- Acceptable — each is a reference to existing objects

## Garbage Collection

**Issue:** In-memory session objects are mutable and shared by reference. `VoiceCallSession.messages` is a mutable array. Multiple async operations that read the same session share the same object reference. This prevents GC of the messages array while any reference exists, but this is by design (in-memory state).

## Load Test Memory

From `load-test.ts`, 1000 sessions + messages uses approximately:
- 1000 sessions × ~500 bytes = ~500KB
- 1000 messages × ~200 bytes = ~200KB
- Total: < 1MB for 1000 sessions

At 10K sessions: ~8-10MB. Acceptable.

## Score

**Memory: 7/10**

Deducted for: unbounded MetricsCollector maps (counters/gauges), no eviction for metric keys, potential phoneConnections leak on WebSocket error without close event, dead InMemory repos in `database` mode retaining memory for no benefit.
