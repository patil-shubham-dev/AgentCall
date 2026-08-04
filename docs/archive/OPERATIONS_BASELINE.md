# Operations Baseline — VoiceBridge v1.0.0

> Production SLO baselines. All values are derived from code analysis and in-process load tests. Update when real production data is available.

---

## Startup Time

| Metric | Baseline | Source |
|--------|----------|--------|
| No DB mode | < 500ms | Code-path analysis — no I/O |
| DB mode (empty DB) | < 2s | Code-path analysis — pool creation + Phase A (0 records) + Phase B (0 callbacks) |
| DB mode (10K sessions) | < 10s | Estimated — Phase A loads all sessions, Phase B rebuilds timers |
| DB mode (100K sessions) | < 60s | Estimated — dominated by DB read time |
| startup.duration metric | Recorded | `metrics.recordTiming('startup.duration', ...)` |

### Bottlenecks

- Phase A load: `RecoveryManager.loadFromDatabase()` does a full table scan of `sessions` and `callbacks`. No pagination.
- Phase B rebuild: iterates all callbacks and paused sessions. Synchronous in the startup path.

---

## Shutdown Time

| Metric | Baseline | Source |
|--------|----------|--------|
| Normal shutdown | < 2s | Code-path analysis — stop timers, close HTTP, close WS, close pool |
| Force-kill timeout | 10s | `FORCE_KILL_TIMEOUT_MS = 10_000` in index.ts |
| DB pool drain | < 1s | `await pool.end()` — depends on active queries |
| shutdown.duration metric | Recorded | `metrics.recordTiming('shutdown.duration', ...)` |

### Failure modes

- If `pool.end()` hangs: process exits after 10s force-kill
- If `app.close()` hangs: same 10s timeout applies
- Unclosed WebSocket connections: signalingServer.close() closes all immediately

---

## Memory Usage

| Condition | RSS Baseline | Source |
|-----------|-------------|--------|
| Process baseline (no sessions) | ~40 MB | Node.js 20 baseline + Fastify + pg |
| Per session (InMemory) | ~32 KB | Load test: 1600 ops @ +1MB ≈ 32KB/op |
| Per WebSocket connection | ~10 KB | ws library overhead |
| Per callback record | ~500 bytes | Small object (callId, resumeAt, userId) |
| 1000 active sessions | ~72 MB | 40MB + 1000 × 32KB |
| 10K active sessions | ~360 MB | 40MB + 10000 × 32KB |
| 100K active sessions | ~3.2 GB | Exceeds pod limit (512MB) |
| Peak during recovery | +20% | Phase A loads all records before dispatching |

### Memory limits

- K8s pod limit: **512 MB** (see deployment.yaml)
- Threshold: reduce replicas or increase limit if `sessions.active` exceeds 14,000 per pod

**Unbounded Maps:**
- `MetricsCollector.counters` — bounded by unique code paths (~30 expected)
- `MetricsCollector.gauges` — same bound
- `MetricsCollector.timings` — 1000-sample cap per key
- `phoneConnections` — one entry per connected WebSocket
- `session-lock locks` — one entry per active locked operation

**Memory growth warning:** `InMemorySessionRepository.sessions` is the only unbounded structure. The sweeper deletes expired sessions every 5 minutes, but if the create rate exceeds the delete rate, memory grows until the pod limit is hit.

---

## CPU Usage

| Condition | CPU Baseline | Source |
|-----------|-------------|--------|
| Idle (no requests) | < 1% | Event loop: sweep timer + health ping + GC |
| 100 req/s (InMemory) | < 5% | Bulk of work is synchronous Map operations |
| 500 req/s (InMemory) | ~15% | Load test shows 42K ops/sec = negligible CPU |
| 100 req/s (DB mode) | ~10% | DB round-trip is I/O-bound, not CPU |
| Phase A recovery | Short burst | Full table scan + in-memory insert |
| GC cycles | Periodic | V8 GC runs automatically |

### CPU-intensive operations

| Operation | CPU cost | Notes |
|-----------|----------|-------|
| JSON serialization (response) | Low | Fastify uses fast-json-stringify for known schemas |
| Event dispatch (synchronous) | Very low | No work done in handlers (log-only) |
| pino log formatting | Low | Structured JSON, no pretty-print in production |
| Pool acquire/release | Very low | pg-pool overhead |
| Session lock acquire/release | Negligible | Promise-chain mutex, no contention at <1000 req/s |

---

## DB Latency

| Metric | Baseline | Source |
|--------|----------|--------|
| db.ping (local PostgreSQL) | < 5ms | Expected for local/LAN |
| db.ping (cloud PostgreSQL) | 5-30ms | Network round-trip |
| Session select by ID | < 10ms | Primary key lookup |
| Session insert | < 10ms | Single-row insert |
| Session update | < 10ms | Primary key update |
| Session delete | < 10ms | Primary key delete |
| Bulk load (Phase A) | ~100ms/10K rows | Full table scan |
| Slow query threshold | 250ms | Logged as warning by InstrumentedRepository |

### Pool settings (recommended production)

| Parameter | Default | Recommended | Rationale |
|-----------|---------|-------------|-----------|
| DB_POOL_MIN | 2 | 5 | Prevent cold-start latency on traffic spikes |
| DB_POOL_MAX | 10 | 50 | Support 500+ req/s without waiting |
| DB_POOL_ACQUIRE_TIMEOUT | 10000 | 10000 | 10 seconds before timing out |
| DB_POOL_IDLE_TIMEOUT | 30000 | 30000 | Close idle connections after 30s |

---

## Retry Rate

| Metric | Baseline | Source |
|--------|----------|--------|
| Retry attempts per operation | 1 max | `maxRetries: 1` in retry.ts |
| Base delay | 50-100ms | `baseDelayMs: 50-100` |
| Total attempts per op | 2 (initial + 1 retry) | |
| Expected retry rate (normal) | < 0.1% | No transient failures in normal operation |
| Expected retry rate (DB degraded) | 10-50% | Depends on DB health |
| Session lock conflicts | < 0.01% | Lock held < 1ms, low contention |

### When retries happen

- Transient network errors (`ECONNRESET`, `ETIMEDOUT`, `EPIPE`, etc.)
- Not retried: validation errors, not-found errors, permission errors
- Dual-write failures: retried once, then logged (write survives in one store)

---

## Expected Metrics (Baseline Values)

### Counters

| Counter | After 1 hour idle | After 1 hour @ 100 sessions/min |
|---------|-------------------|----------------------------------|
| sessions.created | 0 | 6,000 |
| sessions.completed | 0 | 5,700 |
| sessions.cancelled | 0 | 300 |
| callbacks.scheduled | 0 | 2,000 |
| startup.complete | 1 | 1 |
| repo.*.ok | ~60 (health+pings) | 36,060+ |
| repo.*.error | 0 | ~0 |
| dual-write.failures | 0 | 0 |

### Gauges

| Gauge | Idle baseline | Under load |
|-------|--------------|------------|
| sessions.active | 0 | 50-200 |
| sessions.paused | 0 | 10-50 |
| sessions.completed | 0 | 50-5,700 (depends on sweep) |
| callbacks.count | 0 | 10-50 |
| scheduler.timers | 0 | 10-50 |
| db.pool.total | poolMin | poolMin to poolMax |
| db.pool.idle | poolMin | poolMin to poolMax |
| db.pool.waiting | 0 | 0-5 (normal), >5 (warning) |

### Timings

| Timing | Baseline | Warning threshold |
|--------|----------|-------------------|
| startup.duration | < 2s | > 30s |
| shutdown.duration | < 2s | > 10s |
| db.ping (avg) | < 5ms | > 500ms |
| session.findById (avg) | < 1ms (memory) / < 10ms (DB) | > 250ms |
| session.create (avg) | < 1ms (memory) / < 10ms (DB) | > 250ms |
| session.save (avg) | < 1ms (memory) / < 10ms (DB) | > 250ms |

---

## Expected Alerts

| Alert | Trigger | Expected frequency |
|-------|---------|-------------------|
| VoiceBridgeDatabaseUnreachable | `db.ok == 0` for >1m | Zero (healthy infrastructure) |
| VoiceBridgePoolExhaustion | `pool.waiting > 5` for >1m | Rare (under-provisioned pool) |
| VoiceBridgeHighLatency | `db.ping > 500ms` for >2m | Rare (network issues) |
| VoiceBridgeErrorRateHigh | `repo.errors > 10/5m` | Zero (normal) |
| VoiceBridgeSlowQueries | `slow_queries > 5/5m` | Zero (normal) |
| VoiceBridgeRecoveryFailure | `recovery.failure > 0` | Zero (at startup) |
| VoiceBridgeHighMemoryUsage | Pod memory > 90% | Rare (under-provisioned limit) |
| VoiceBridgeHighCPUUsage | CPU > 80% for 5m | Rare (traffic spike) |

---

## Pool Usage

| Traffic level | Active connections | Waiting | Notes |
|--------------|-------------------|---------|-------|
| Idle | poolMin (2-5) | 0 | Health ping uses 1 connection |
| 100 req/s | 10-20 | 0 | Well within poolMax=50 |
| 500 req/s | 30-50 | 0-3 | Near poolMax |
| 1000 req/s | 50 | 5-20 | Pool exhausted — increase poolMax |
| 5000 req/s | 50 | 50+ | Pool exhausted — increase poolMax or add replicas |

**Scale recommendation:** When `pool.waiting > 5` for more than 1 minute, either increase `DB_POOL_MAX` or add HPA replicas (which adds another pool in another pod).

---

## Recovery Duration

| DB size | Phase A (load) | Phase B (timers) | Total |
|---------|---------------|-------------------|-------|
| 0 sessions | < 10ms | < 10ms | < 20ms |
| 1K sessions | < 100ms | < 100ms | < 200ms |
| 10K sessions | < 1s | < 1s | < 2s |
| 100K sessions | < 5s | < 5s | < 10s |

---

## SLO Targets

| Indicator | SLO | Measurement |
|-----------|-----|-------------|
| API availability | 99.9% | /health returns ok |
| API latency p99 | < 500ms | metrics collector timing |
| DB availability | 99.95% | db.ping success |
| Recovery time | < 30s | startup.duration metric |
| Error rate | < 0.1% | repo.*.error / repo.*.ok |
| Pool exhaustion | < 5 waiting | db.pool.waiting gauge |
| Data loss on restart | 0 (DB mode) | Full recovery from DB |
